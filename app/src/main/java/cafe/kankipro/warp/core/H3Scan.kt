package cafe.kankipro.warp.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/**
 * The white-IP check for MASQUE over HTTP/3 (and Gool and MASQUE-in-MASQUE, which ride the same
 * QUIC gateways): does UDP reach a QUIC server at this address and port?
 *
 * One 1200-byte QUIC long-header packet with a version no server speaks (0x?a?a?a?a, kept for exactly
 * this, RFC 9000 §15). A QUIC server answers it with a Version Negotiation packet that echoes our
 * connection ids, so no crypto is needed and the answer says which probe it belongs to. Any other
 * packet back from that address also counts, as the core's own prober does.
 * Not yet seen on a phone: if a scan finds nothing, the log line «ip test h3» says so.
 */
object H3Scan {
    /** The core's MASQUE ports (prober.rs MASQUE_PORTS). */
    val PORTS = listOf(443, 500, 1701, 4500, 4443, 8443, 8095)

    /** A probe: the packet and our source connection id (the server echoes it as its destination id). */
    class Probe(val packet: ByteArray, val scid: ByteArray)

    fun probe(rnd: Random): Probe {
        val dcid = ByteArray(8).also { rnd.nextBytes(it) }
        val scid = ByteArray(8).also { rnd.nextBytes(it) }
        val head = byteArrayOf((0xC0 or rnd.nextInt(16)).toByte(), 0x1a, 0x2a, 0x3a, 0x4a, 8) + dcid + byteArrayOf(8) + scid
        return Probe(head + ByteArray(1200 - head.size), scid)
    }

    /** The scid a Version Negotiation answer echoes back (bytes 6..13), or null if [data] is not one. */
    fun echoedId(data: ByteArray): ByteArray? {
        if (data.size < 14 || data[0].toInt() and 0x80 == 0) return null
        if ((1..4).any { data[it].toInt() != 0 }) return null
        val len = data[5].toInt() and 0xff
        if (len != 8 || data.size < 6 + len) return null
        return data.copyOfRange(6, 6 + len)
    }

    /**
     * One probe to each of [endpoints] from one socket, [gapMs] apart; returns the round trip of each
     * that answered within [waitMs] after the last send.
     */
    suspend fun probe(
        endpoints: List<String>,
        waitMs: Long,
        rnd: Random,
        gapMs: () -> Long,
        onEach: (Int, Int) -> Unit = { _, _ -> },
    ): Map<String, Int> {
        val byId = ConcurrentHashMap<String, Pair<String, Long>>()
        val byAddr = ConcurrentHashMap<String, Pair<String, Long>>()
        val result = ConcurrentHashMap<String, Int>()
        DatagramSocket().use { s ->
            s.soTimeout = 100
            val open = AtomicBoolean(true)
            val reader = Thread {
                val buf = ByteArray(1500)
                while (open.get()) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(pkt)
                    } catch (_: SocketTimeoutException) {
                        continue
                    } catch (_: Exception) {
                        break
                    }
                    val now = System.nanoTime()
                    val id = echoedId(buf.copyOf(pkt.length))?.let { hex(it) }
                    val hit = (id?.let { byId.remove(it) })
                        ?: byAddr.remove("${pkt.address.hostAddress}:${pkt.port}")
                    if (hit != null) result.putIfAbsent(hit.first, ((now - hit.second) / 1_000_000).toInt().coerceAtLeast(1))
                }
            }.apply { isDaemon = true; start() }
            try {
                for ((n, ep) in endpoints.withIndex()) {
                    coroutineContext.ensureActive()
                    val port = ep.substringAfterLast(':').toIntOrNull()
                    if (port != null) runCatching {
                        val p = probe(rnd)
                        val t0 = System.nanoTime()
                        byId[hex(p.scid)] = ep to t0
                        byAddr[ep] = ep to t0
                        s.send(DatagramPacket(p.packet, p.packet.size, InetSocketAddress(ep.substringBeforeLast(':'), port)))
                    }
                    onEach(n + 1, result.size)
                    delay(gapMs())
                }
                val until = System.currentTimeMillis() + waitMs
                while (System.currentTimeMillis() < until && result.size < endpoints.size) {
                    coroutineContext.ensureActive()
                    onEach(endpoints.size, result.size)
                    delay(60)
                }
            } finally {
                open.set(false)
                reader.join(300)
            }
        }
        return result
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
