package cafe.kankipro.warp.core

import android.content.Context
import java.io.File
import java.util.Base64

/**
 * The WireGuard side of the WARP account the core registered (files/warp/identity.toml), turned
 * into a plain AmneziaWG config. Used by the gamer mode (straight on the TUN, no core in between)
 * and by the WireGuard scanner.
 *
 * The headers stay plain WireGuard (H1..H4 = 1..4, written out with the disguise, the "reserved" bytes zero), as every stock
 * WireGuard client of a WARP account sends them; Cloudflare answers those in kind. The account's
 * client_id could go in those bytes ([header]), but then the answers would carry it too and a
 * mismatch drops every packet, so it is kept only for a later test.
 */
data class WarpWg(
    val privateKey: String,
    val peerKey: String,
    val ipv4: String,
    val ipv6: String,
    val clientId: ByteArray,
) {
    /** The header word of message [type] (1 init, 2 response, 3 cookie, 4 data). */
    fun header(type: Int): Long {
        val c = clientId
        if (c.size != 3) return type.toLong()
        return type.toLong() or ((c[0].toLong() and 0xff) shl 8) or ((c[1].toLong() and 0xff) shl 16) or
            ((c[2].toLong() and 0xff) shl 24)
    }

    /**
     * An AmneziaWG config for [endpoint]. [disguise] adds the Amnezia junk packets Cloudflare's plain
     * server drops unread; the size fields S1..S4 stay 0, those a plain server cannot read.
     */
    fun config(endpoint: String, disguise: Boolean, mtu: Int = 1280): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = $privateKey")
        appendLine("Address = " + listOfNotNull(ipv4.takeIf { it.isNotBlank() }?.let { slash(it, 32) },
            ipv6.takeIf { it.isNotBlank() }?.let { slash(it, 128) }).joinToString(", "))
        appendLine("DNS = 1.1.1.1, 1.0.0.1")
        appendLine("MTU = $mtu")
        if (disguise) {
            // Signature packets sent before each handshake (AmneziaWG 1.5), the same idea as the core's
            // «aggressive» profile: they look like the start of HTTP and other protocols. Only tags the
            // engine's parser knows (wgtunnel/amneziawg-go device/obf.go: b, t, r, rc, rd), each with a
            // single number; ranges like «40-64» or <c> are refused there. They go once per handshake
            // (about every two minutes), never with game packets, so they cost no ping.
            for ((n, spec) in SIGNATURES.withIndex()) appendLine("I${n + 1} = $spec")
            appendLine("Jc = 4")
            appendLine("Jmin = 40")
            appendLine("Jmax = 70")
            // Once any Amnezia key is set, the engine's config reader (wgtunnel/wireproxy-awg,
            // CreateIPCRequest) sends h1..h4 to the Go side whether given or not, empty when not, and
            // amneziawg-go cannot parse an empty header: the whole config was refused
            // (GO_ACTIVATION_ERROR_CODE). These are plain WireGuard's own types, what Cloudflare reads.
            for (n in 1..4) appendLine("H$n = $n")
        }
        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = $peerKey")
        appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
        appendLine("Endpoint = $endpoint")
        appendLine("PersistentKeepalive = 25")
    }

    private fun slash(ip: String, bits: Int) = if ('/' in ip) ip else "$ip/$bits"

    companion object {
        /** The I1..I4 signature packets: CRLF CRLF, «POST», «GET» and a random one. */
        val SIGNATURES = listOf(
            "<b 0x0d0a0d0a><t><rc 48>",
            "<b 0x504f5354><t><rd 20><rc 40>",
            "<b 0x474554><rc 50>",
            "<r 80>",
        )

        /** Reads the WireGuard identity; null until the core has registered one. */
        fun load(ctx: Context): WarpWg? {
            val f = File(File(ctx.filesDir, "warp"), "identity.toml")
            if (!f.isFile) return null
            return parse(f.readText())
        }

        fun parse(toml: String): WarpWg? {
            val kv = HashMap<String, String>()
            toml.lineSequence().forEach { line ->
                val p = line.split('=', limit = 2)
                if (p.size == 2) kv[p[0].trim()] = p[1].trim().removeSurrounding("\"")
            }
            val priv = kv["wg_private_key"]?.takeIf { it.isNotBlank() } ?: return null
            val peer = kv["wg_peer_public_key"]?.takeIf { it.isNotBlank() } ?: return null
            val cid = kv["client_id"]?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() } ?: ByteArray(0)
            return WarpWg(priv, peer, kv["ipv4"].orEmpty(), kv["ipv6"].orEmpty(), cid)
        }
    }
}
