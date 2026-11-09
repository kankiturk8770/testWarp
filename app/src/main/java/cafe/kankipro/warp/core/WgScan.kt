package cafe.kankipro.warp.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.random.Random

/**
 * Finds WARP WireGuard gateways that answer from this network, with a real handshake of this
 * account (WgHello: the first packet the engine itself sends, so only an address that completes the
 * key exchange with this account counts; a ping or an open port does not).
 *
 * All handshakes of one account go out one after another from one socket, a few ms apart, each with
 * a later time than the last (WgHello.stamp). Sent at once, as the first version did, the server
 * took one and dropped the rest as replays: that is why a scan found 12 of 470 and the second
 * round found none.
 *
 * Two rounds: a wide sweep, one handshake per address, then the fastest few again several times
 * for ping, jitter and loss. Cloudflare's addresses are anycast: the network, not the address,
 * decides the data centre, so the scan finds the best paths from here and the connection then says
 * where it came out.
 */
object WgScan {
    data class Hit(
        val endpoint: String,
        val pingMs: Int,
        val jitterMs: Int,
        val lossPct: Int,
        /** The Cloudflare data centre a short real connection came out of (FRA, IST, …); blank until checked. */
        val colo: String = "",
    ) {
        val country: String get() = Colos.country(colo)

        /** Kanki's gaming score: lower is better. */
        val score: Int get() = pingMs + jitterMs * 3 + lossPct * 20

        /** Good enough to game on: no loss, steady, under 120 ms. */
        val gameReady: Boolean get() = lossPct == 0 && pingMs < 120 && jitterMs <= 15
    }

    /** The core's own lists (wireguard.rs WG_PREFIXES_V4, WG_PORTS). */
    val PREFIXES = listOf(
        "162.159.192", "162.159.195", "188.114.96", "188.114.97", "188.114.98", "188.114.99", "162.159.193",
        // MASQUE's ranges too (prober.rs MASQUE_CIDRS_V4): from some networks these reach other data centres
        // (Istanbul from Turkey, where the WireGuard ranges go to Frankfurt and Milan). If they do not take
        // WireGuard, their handshakes simply get no answer.
        "162.159.198", "162.159.199", "162.159.196", "162.159.197", "162.159.204",
    )
    val PORTS = listOf(
        2408, 500, 1701, 4500, 854, 859, 864, 878, 880, 890, 891, 894, 903, 908, 928, 934, 939, 942,
        943, 945, 946, 955, 968, 987, 988, 1002, 1010, 1014, 1018, 1070, 1074, 1180, 1387, 1843, 2371,
        2506, 3138, 3476, 3581, 3854, 4177, 4198, 4233, 5279, 5956, 7103, 7152, 7156, 7281, 7559, 8319,
        8742, 8854, 8886,
        // also tried: 443 and 8443, the ports that most often get through filtering
        443, 8443,
    )

    /** Time between two handshakes of one account. */
    private const val GAP_MS = 12L

    /**
     * [wide]: addresses of the first round; [keep]: how many go to the second; [samples]:
     * handshakes each there. [onProgress] gets (done, total, answered).
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
        val total = candidates.size + keep * samples
        var found = 0
        val first = probe(id, disguise, candidates, waitMs = 1200) { done, ok ->
            if (ok) found++
            onProgress(done, total, found)
        }
        // The fastest half, plus the fastest of every range: the fastest ones usually all reach one data
        // centre, and another range is where another city (Istanbul, say) shows up.
        val sorted = first.entries.sortedBy { it.value }.map { it.key }
        val perRange = sorted.groupBy { it.substringBeforeLast('.') }.values.map { it.first() }
        val best = (sorted.take(keep / 2) + perRange + sorted).distinct().take(keep)
        if (best.isEmpty()) return@withContext emptyList()
        val rtts = HashMap<String, MutableList<Int>>()
        repeat(samples) { round ->
            val got = probe(id, disguise, best, waitMs = 900) { done, _ ->
                onProgress(candidates.size + round * best.size + done, total, found)
            }
            got.forEach { (ep, ms) -> rtts.getOrPut(ep) { ArrayList() } += ms }
            delay(60)
        }
        best.mapNotNull { ep -> rtts[ep]?.let { summarize(ep, it, samples) } }.sortedBy { it.score }
    }

    /**
     * One handshake to each of [endpoints] from one socket, [GAP_MS] apart; answers are matched by
     * the sender index they name and checked cryptographically (WgHello.answers). Returns the round
     * trip of each endpoint that answered.
     */
    suspend fun probe(
        id: WarpWg,
        disguise: Boolean,
        endpoints: List<String>,
        waitMs: Long,
        /** Time before the next handshake; the IP test gives a random one so the sends have no fixed rhythm. */
        gapMs: () -> Long = { GAP_MS },
        onEach: (Int, Boolean) -> Unit = { _, _ -> },
    ): Map<String, Int> {
        class Out(val ep: String, val hello: WgHello, val sent: WgHello.Sent, val t0: Long)
        val pending = ConcurrentHashMap<Int, Out>()
        val result = ConcurrentHashMap<String, Int>()
        var done = 0
        DatagramSocket().use { s ->
            s.soTimeout = 100
            val open = java.util.concurrent.atomic.AtomicBoolean(true)
            val reader = Thread {
                val buf = ByteArray(512)
                while (open.get()) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(pkt)
                    } catch (_: SocketTimeoutException) {
                        continue
                    } catch (_: Exception) {
                        break
                    }
                    val data = buf.copyOf(pkt.length)
                    val idx = WgHello.answerFor(data) ?: continue
                    val o = pending[idx] ?: continue
                    if (o.hello.answers(data, o.sent)) {
                        pending.remove(idx)
                        result[o.ep] = ((System.nanoTime() - o.t0) / 1_000_000).toInt().coerceAtLeast(1)
                    }
                }
            }.apply { isDaemon = true; start() }
            try {
                for (ep in endpoints) {
                    coroutineContext.ensureActive()
                    val hello = WgHello.of(id.config(ep, disguise))
                    val host = ep.substringBeforeLast(':')
                    val port = ep.substringAfterLast(':').toIntOrNull()
                    if (hello != null && port != null) {
                        runCatching {
                            val to = InetSocketAddress(host, port)
                            val sent = hello.hello(WgHello.stamp())
                            sent.junk.forEach { s.send(DatagramPacket(it, it.size, to)) }
                            val t0 = System.nanoTime()
                            pending[sent.sender] = Out(ep, hello, sent, t0)
                            s.send(DatagramPacket(sent.packet, sent.packet.size, to))
                        }
                    }
                    done++
                    onEach(done, false)
                    delay(gapMs())
                }
                val until = System.currentTimeMillis() + waitMs
                var seen = 0
                while (System.currentTimeMillis() < until && pending.isNotEmpty()) {
                    coroutineContext.ensureActive()
                    if (result.size > seen) { repeat(result.size - seen) { onEach(done, true) }; seen = result.size }
                    delay(50)
                }
                if (result.size > seen) repeat(result.size - seen) { onEach(done, true) }
            } finally {
                open.set(false)
                reader.join(300)
            }
        }
        return result
    }

    /**
     * One handshake to [endpoint] with [cfg]'s headers; says what came back: its size and first four
     * bytes in hex (byte 0 the type, 1..3 the "reserved" bytes Zero Trust uses for the device id), or
     * that nothing came. For the log, to see whether Cloudflare's answers carry the device id.
     */
    fun raw(cfg: String, endpoint: String, waitMs: Int = 2500): String = runCatching {
        val hello = WgHello.of(cfg) ?: return "no config"
        DatagramSocket().use { s ->
            s.soTimeout = waitMs
            val to = InetSocketAddress(endpoint.substringBeforeLast(':'), endpoint.substringAfterLast(':').toInt())
            val sent = hello.hello(WgHello.stamp())
            sent.junk.forEach { s.send(DatagramPacket(it, it.size, to)) }
            s.send(DatagramPacket(sent.packet, sent.packet.size, to))
            val out = "sent ${sent.packet.copyOf(4).joinToString("") { "%02x".format(it) }}"
            val buf = ByteArray(512)
            val pkt = DatagramPacket(buf, buf.size)
            try {
                s.receive(pkt)
            } catch (_: SocketTimeoutException) {
                return "$out, no answer"
            }
            val head = buf.copyOf(minOf(4, pkt.length)).joinToString("") { "%02x".format(it) }
            "$out, answer ${pkt.length} bytes, head $head, valid ${hello.answers(buf.copyOf(pkt.length), sent)}"
        }
    }.getOrElse { "error ${it.message}" }

    fun summarize(ep: String, rtts: List<Int>, sent: Int): Hit {
        val avg = rtts.average().toInt()
        val jitter = if (rtts.size < 2) 0 else rtts.zipWithNext { a, b -> abs(a - b) }.average().toInt()
        val loss = ((sent - rtts.size) * 100) / sent.coerceAtLeast(1)
        return Hit(ep, avg, jitter, loss)
    }

    /** Every address of the ranges on the common ports first, then random pairs up to [n]. */
    fun candidates(n: Int): List<String> {
        val out = LinkedHashSet<String>()
        for (p in PREFIXES) for (port in listOf(2408, 500, 443, 8443, 1701, 4500)) out += "$p.1:$port"
        // a few known MASQUE gateways on WireGuard's main ports
        for (h in listOf("162.159.198.1", "162.159.198.2", "162.159.199.1", "162.159.199.2")) for (port in listOf(2408, 500, 4500, 443)) out += "$h:$port"
        val rnd = Random(System.nanoTime())
        var guard = 0
        while (out.size < n && guard++ < n * 20) {
            out += "${PREFIXES.random(rnd)}.${rnd.nextInt(1, 255)}:${PORTS.random(rnd)}"
        }
        return out.toList().take(n)
    }
}
