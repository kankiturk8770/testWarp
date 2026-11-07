package cafe.kankipro.warp.core

import org.json.JSONObject

/** How the core reaches Cloudflare. [flags] are the core's own command-line switches. */
enum class Transport(val title: String, val hint: String, val flags: List<String>) {
    // WireGuard with AmneziaWG 1.5 style disguise: signature packets (I1-I5) that look like other
    // protocols and junk packets around the handshake. Cloudflare's plain WireGuard server drops
    // them unread, so they need no server of ours.
    // Strongest profile first; if it does not pass on a gateway, the core tries balanced, light and off
    // there before giving up on that gateway.
    AMNEZIA("Amnezia", "WireGuard با بسته‌های پوششی شدید، مثل AmneziaVPN", listOf("--wg", "--noize", "aggressive")),
    WIREGUARD("WireGuard", "WireGuard با پوشش متعادل؛ اگر نشد سبک‌تر و شدیدتر", listOf("--wg")),
    MASQUE_H2("MASQUE · HTTP/2", "روی TCP 443، مثل یک سایت معمولی؛ وقتی UDP بسته است", listOf("--masque", "--h2")),
    MASQUE_H3("MASQUE · HTTP/3", "روی QUIC (UDP)؛ کم‌پینگ‌ترین اگر باز باشد", listOf("--masque", "--h3")),
    GOOL("Gool", "WireGuard داخل MASQUE؛ دو لایه", listOf("--gool")),
    MIM("MASQUE در MASQUE", "دو تونل تو در تو؛ سخت‌ترین برای فیلتر، کندتر", listOf("--mim"));

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name }
    }
}

/** How hard the core looks for a Cloudflare gateway that answers. */
enum class Scan(val title: String, val value: String) {
    TURBO("سریع", "turbo"),
    BALANCED("متعادل", "balanced"),
    THOROUGH("کامل", "thorough"),
    IRONCLAD("با تونل واقعی", "ironclad");
}

enum class IpFamily(val title: String, val value: String) {
    V4("IPv4", "v4"), V6("IPv6", "v6"), BOTH("هر دو", "both");
}

data class Options(
    val transport: Transport = Transport.AMNEZIA,
    val scan: Scan = Scan.TURBO,
    val ip: IpFamily = IpFamily.V4,
    val fragment: Boolean = true,
    val autoFallback: Boolean = true,
    val mtu: Int = 1500,
    val port: Int = 1819,
    /** Normal mode: a gateway the white-list scan found for a transport, used instead of the core's own scan. */
    val pins: Map<Transport, String> = emptyMap(),
    /** The handshake time the scan measured for each pin, for its badge. */
    val pinPing: Map<Transport, Int> = emptyMap(),
    /** «کاربر گیمر»: Amnezia/WireGuard straight on the TUN (GameEngine) instead of the core. */
    val gamer: Boolean = false,
    val gameTransport: Transport = Transport.AMNEZIA,
) {
    /** The core's arguments: one SOCKS5 listener on the loopback, identity kept in [configPath]. */
    fun toArgs(configPath: String): List<String> = buildList {
        add("--bind"); add("127.0.0.1:$port")
        add("--config"); add(configPath)
        add("--log-level"); add("info")
        addAll(transport.flags)
        add("--scan"); add(scan.value)
        add("--ip"); add(ip.value)
        if (fragment) add("--fragment")
        // Reuse the gateway that worked last time without rescanning, when it still answers; with a
        // pinned gateway nothing else is tried: the core goes to that one only and never scans.
        add(if (pins[transport] != null && pinnable) "--no-quick-reconnect" else "--quick-reconnect")
        pins[transport]?.let { pin ->
            when (transport) {
                Transport.AMNEZIA, Transport.WIREGUARD -> { add("--wg-peer"); add(pin) }
                Transport.MASQUE_H2, Transport.MASQUE_H3 -> { add("--peer"); add(pin) }
                else -> Unit
            }
        }
    }

    /** Transports that take a pinned gateway. */
    val pinnable: Boolean get() = transport in PINNABLE

    fun toJson(): String = JSONObject()
        .put("transport", transport.name)
        .put("scan", scan.name)
        .put("ip", ip.name)
        .put("fragment", fragment)
        .put("autoFallback", autoFallback)
        .put("pins", JSONObject().apply { pins.forEach { (t, v) -> put(t.name, v) } })
        .put("pinPing", JSONObject().apply { pinPing.forEach { (t, v) -> put(t.name, v) } })
        .put("gamer", gamer)
        .put("gameTransport", gameTransport.name)
        .toString()

    companion object {
        fun fromJson(raw: String?): Options {
            if (raw.isNullOrBlank()) return Options()
            return try {
                val j = JSONObject(raw)
                Options(
                    transport = Transport.of(j.optString("transport")) ?: Transport.AMNEZIA,
                    scan = Scan.entries.firstOrNull { it.name == j.optString("scan") } ?: Scan.TURBO,
                    ip = IpFamily.entries.firstOrNull { it.name == j.optString("ip") } ?: IpFamily.V4,
                    fragment = j.optBoolean("fragment", true),
                    autoFallback = j.optBoolean("autoFallback", true),
                    pins = j.optJSONObject("pins")?.let { o ->
                        o.keys().asSequence().mapNotNull { k -> Transport.of(k)?.let { it to o.optString(k) } }
                            .filter { it.second.isNotBlank() }.toMap()
                    } ?: emptyMap(),
                    pinPing = j.optJSONObject("pinPing")?.let { o ->
                        o.keys().asSequence().mapNotNull { k -> Transport.of(k)?.let { it to o.optInt(k) } }.toMap()
                    } ?: emptyMap(),
                    gamer = j.optBoolean("gamer", false),
                    gameTransport = Transport.of(j.optString("gameTransport"))
                        ?.takeIf { it == Transport.AMNEZIA || it == Transport.WIREGUARD } ?: Transport.AMNEZIA,
                )
            } catch (_: Exception) {
                Options()
            }
        }

        val PINNABLE = setOf(Transport.AMNEZIA, Transport.WIREGUARD, Transport.MASQUE_H2, Transport.MASQUE_H3)

        /** Order tried after the user's choice: what survives a hostile network first. */
        val RANKING = listOf(
            Transport.AMNEZIA, Transport.MASQUE_H2, Transport.WIREGUARD, Transport.GOOL, Transport.MASQUE_H3, Transport.MIM
        )

        /**
         * The transports one connection tries, at most [limit]: the user's choice as it is, then the
         * one that worked last time, then [RANKING]. The fallbacks always fragment the handshake.
         */
        fun chain(options: Options, remembered: Transport?, limit: Int = 3): List<Options> {
            if (!options.autoFallback) return listOf(options)
            val out = mutableListOf(options)
            val order = listOfNotNull(remembered) + RANKING
            for (t in order) {
                if (out.size >= limit) break
                if (out.any { it.transport == t }) continue
                out += options.copy(transport = t, fragment = true)
            }
            return out
        }
    }
}
