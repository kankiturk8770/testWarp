package cafe.kankipro.warp.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.math.abs
import kotlin.random.Random

/**
 * Finds WARP WireGuard gateways that answer from this network, with a real handshake of this
 * account (WgHello: the same first packet the engine sends, so only an address that would really
 * connect answers). Two rounds: a wide sweep, one handshake per address, then the fastest few are
 * measured again several times for ping, jitter and loss.
 *
 * Cloudflare's addresses are anycast: the same address is served by every data centre and the
 * network decides which one answers. So the scan cannot ask for "Germany"; it finds the paths that
 * are fastest and steadiest from here, and the connection then says where it came out.
 */
object WgScan {
    data class Hit(val endpoint: String, val pingMs: Int, val jitterMs: Int, val lossPct: Int) {
        /** Kanki's gaming score: lower is better. */
        val score: Int get() = pingMs + jitterMs * 3 + lossPct * 20
    }

    /** The core's own lists (wireguard.rs WG_PREFIXES_V4, WG_PORTS). */
    val PREFIXES = listOf(
        "162.159.192", "162.159.195", "188.114.96", "188.114.97", "188.114.98", "188.114.99", "162.159.193",
    )
    val PORTS = listOf(
        2408, 500, 1701, 4500, 854, 859, 864, 878, 880, 890, 891, 894, 903, 908, 928, 934, 939, 942,
        943, 945, 946, 955, 968, 987, 988, 1002, 1010, 1014, 1018, 1070, 1074, 1180, 1387, 1843, 2371,
        2506, 3138, 3476, 3581, 3854, 4177, 4198, 4233, 5279, 5956, 7103, 7152, 7156, 7281, 7559, 8319,
        8742, 8854, 8886,
    )

    /**
     * [wide]: how many addresses the first round tries (white-list hunting wants many, gaming fewer);
     * [samples]: handshakes per address in the second round. [onProgress] gets (done, total, found).
     */
    suspend fun scan(
        id: WarpWg,
        disguise: Boolean,
        wide: Int = 600,
        keep: Int = 12,
        samples: Int = 8,
        onProgress: (Int, Int, Int) -> Unit = { _, _, _ -> },
    ): List<Hit> = withContext(Dispatchers.IO) {
        val candidates = candidates(wide)
        val gate = Semaphore(48)
        var done = 0
        var found = 0
        val first = coroutineScope {
            candidates.map { ep ->
                async {
                    gate.withPermit {
                        ensureActive()
                        val rtt = handshake(id, ep, disguise, 900)
                        synchronized(this@WgScan) {
                            done++
                            if (rtt != null) found++
                            onProgress(done, candidates.size + keep * samples, found)
                        }
                        rtt?.let { ep to it }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        val best = first.sortedBy { it.second }.take(keep)
        val hits = coroutineScope {
            best.map { (ep, _) ->
                async {
                    val rtts = ArrayList<Int>()
                    repeat(samples) {
                        ensureActive()
                        handshake(id, ep, disguise, 1000)?.let { rtts += it }
                        synchronized(this@WgScan) {
                            done++
                            onProgress(done, candidates.size + keep * samples, found)
                        }
                        delay(120)
                    }
                    if (rtts.isEmpty()) null else summarize(ep, rtts, samples)
                }
            }.awaitAll().filterNotNull()
        }
        hits.sortedBy { it.score }
    }

    fun summarize(ep: String, rtts: List<Int>, sent: Int): Hit {
        val avg = rtts.average().toInt()
        val jitter = if (rtts.size < 2) 0 else rtts.zipWithNext { a, b -> abs(a - b) }.average().toInt()
        val loss = ((sent - rtts.size) * 100) / sent.coerceAtLeast(1)
        return Hit(ep, avg, jitter, loss)
    }

    /** Every address of the ranges on the common ports first, then random pairs up to [n]. */
    fun candidates(n: Int): List<String> {
        val out = LinkedHashSet<String>()
        for (p in PREFIXES) for (port in listOf(2408, 500, 1701, 4500)) out += "$p.1:$port"
        val rnd = Random(System.nanoTime())
        var guard = 0
        while (out.size < n && guard++ < n * 20) {
            out += "${PREFIXES.random(rnd)}.${rnd.nextInt(1, 255)}:${PORTS.random(rnd)}"
        }
        return out.toList()
    }

    /** One real handshake to [endpoint]; the round trip in ms, or null without a valid answer. */
    fun handshake(id: WarpWg, endpoint: String, disguise: Boolean, timeoutMs: Int): Int? {
        val hello = WgHello.of(id.config(endpoint, disguise)) ?: return null
        val host = endpoint.substringBeforeLast(':')
        val port = endpoint.substringAfterLast(':').toIntOrNull() ?: return null
        return try {
            DatagramSocket().use { s ->
                s.soTimeout = timeoutMs
                val to = InetSocketAddress(host, port)
                val sent = hello.hello()
                sent.junk.forEach { s.send(DatagramPacket(it, it.size, to)) }
                val start = System.nanoTime()
                s.send(DatagramPacket(sent.packet, sent.packet.size, to))
                val buf = ByteArray(256)
                val deadline = start + timeoutMs * 1_000_000L
                while (System.nanoTime() < deadline) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(pkt)
                    } catch (_: SocketTimeoutException) {
                        return null
                    }
                    if (hello.answers(buf.copyOf(pkt.length), sent)) {
                        return ((System.nanoTime() - start) / 1_000_000).toInt().coerceAtLeast(1)
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
