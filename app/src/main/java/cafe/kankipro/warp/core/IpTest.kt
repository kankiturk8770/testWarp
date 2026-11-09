package cafe.kankipro.warp.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.random.Random

/**
 * The «تست آی‌پی» screen: two separate scans, because each protocol has its own ranges.
 *  - gaming: WireGuard's ranges (WgScan.PREFIXES), a real WireGuard handshake of this account;
 *  - normal: MASQUE's ranges (H2Scan.PREFIXES), a TLS handshake on 443.
 * Every run takes random ranges, random addresses and random ports (never in order, never the same
 * ones), few at a time with a random pause between sends, so a filter sees no sweep.
 *
 * Stage 1, alive: the protocol's own port; for addresses that did not answer, TCP 443/80 says whether
 * the address itself is alive (kept in the archive for very hard days). Stage 2, exact: the ones that
 * answered again several times for ping, jitter and loss. Gaming then learns each one's city
 * (GameEngine.locate: one short real connection per /24).
 *
 * The proof for gaming is the WireGuard handshake, never TCP: some networks (and the cloud test
 * machine's proxy) answer every TCP connect themselves.
 */
object IpTest {
    enum class Kind(val title: String) { GAMING("گیمینگ"), NORMAL("کاربر عادی") }

    data class Found(
        val endpoint: String,
        val kind: Kind,
        /** The protocol answered: a valid WireGuard handshake (gaming) or TLS on 443 (normal). */
        val proto: Boolean,
        /** TCP 443 or 80 opened (the address is alive, maybe only that). */
        val tcp: Boolean,
        val pingMs: Int = 0,
        val jitterMs: Int = 0,
        val lossPct: Int = 0,
        val colo: String = "",
        val network: String = "",
        val seenAt: Long = 0,
    ) {
        val country: String get() = Colos.country(colo)
        val score: Int get() = pingMs + jitterMs * 3 + lossPct * 20
        fun hit() = WgScan.Hit(endpoint, pingMs, jitterMs, lossPct, colo)
    }

    data class State(
        val kind: Kind? = null,
        val running: Boolean = false,
        /** 1 alive, 2 exact ping, 3 city (gaming only). */
        val stage: Int = 0,
        val done: Int = 0,
        val total: Int = 0,
        val found: Int = 0,
        val network: String = "",
        /** This network's archive, newest result of each address. */
        val gaming: List<Found> = emptyList(),
        val normal: List<Found> = emptyList(),
        /** The archive of every network. */
        val archived: Int = 0,
        val result: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** A game takes up to about 200 ms; more than that is not shown. */
    const val MAX_GAME_PING = 200

    // ------------------------------------------------------------ pure parts (tested)

    /**
     * [n] distinct «ip:port» from [prefixes] (each a /24 like «162.159.192»), in random order: the
     * ranges shuffled, then a random range, a random address 1..254 and a random port each time.
     */
    fun randomTargets(prefixes: List<String>, ports: List<Int>, n: Int, rnd: Random): List<String> {
        val ranges = prefixes.shuffled(rnd)
        val out = LinkedHashSet<String>()
        var guard = 0
        while (out.size < n && guard++ < n * 30) {
            out += "${ranges.random(rnd)}.${rnd.nextInt(1, 255)}:${ports.random(rnd)}"
        }
        return out.toList().shuffled(rnd)
    }

    /** The golden gaming list: the protocol answered, ping up to [MAX_GAME_PING], no or little loss; loss first, then ping. */
    fun golden(list: List<Found>): List<Found> =
        list.filter { it.kind == Kind.GAMING && it.proto && it.pingMs in 1..MAX_GAME_PING && it.lossPct <= 10 }
            .sortedWith(compareBy({ it.lossPct }, { it.pingMs }, { it.jitterMs }))

    /** Every gaming result a game can use, country by country (Turkey and Germany first, then by best score). */
    fun byCountry(list: List<Found>): List<Pair<String, List<Found>>> =
        list.filter { it.kind == Kind.GAMING && it.proto && it.pingMs in 1..MAX_GAME_PING && it.lossPct < 100 }
            .groupBy { it.country.ifBlank { "??" } }
            .mapValues { (_, v) -> v.sortedBy { it.score } }.toList()
            .sortedWith(compareBy({ it.first != "TR" }, { it.first != "DE" }, { it.first == "??" }, { it.second.first().score }))

    /** Old and new results together: the newest of each kind+address+network wins, at most [cap], newest first. */
    fun merge(old: List<Found>, new: List<Found>, cap: Int = 4000): List<Found> =
        (new + old).sortedByDescending { it.seenAt }
            .distinctBy { Triple(it.kind, it.endpoint, it.network) }.take(cap)

    fun line(f: Found): String = listOf(
        f.kind.name, f.endpoint, if (f.proto) 1 else 0, if (f.tcp) 1 else 0, f.pingMs, f.jitterMs, f.lossPct,
        f.colo, f.network.replace(',', ' ').replace('\n', ' '), f.seenAt,
    ).joinToString(",")

    fun parse(line: String): Found? = runCatching {
        val p = line.split(',')
        if (p.size < 10) return null
        Found(p[1], Kind.valueOf(p[0]), p[2] == "1", p[3] == "1", p[4].toInt(), p[5].toInt(), p[6].toInt(), p[7], p[8], p[9].toLong())
    }.getOrNull()

    // ------------------------------------------------------------ the archive

    private fun file(ctx: Context) = File(File(ctx.filesDir, "iptest").apply { mkdirs() }, "archive.csv")

    @Synchronized fun archive(ctx: Context): List<Found> =
        runCatching { file(ctx).readLines().mapNotNull(::parse) }.getOrDefault(emptyList())

    @Synchronized internal fun save(ctx: Context, new: List<Found>): List<Found> {
        val all = merge(archive(ctx), new)
        runCatching { file(ctx).writeText(all.joinToString("\n") { line(it) }) }
        return all
    }

    /** Shows this network's archive (the screen calls it when it opens). */
    fun load(ctx: Context) {
        if (_state.value.running) return
        show(ctx, archive(ctx), _state.value.result)
    }

    private fun show(ctx: Context, all: List<Found>, result: String?, s: State = _state.value) {
        val net = GameEngine.networkKey(ctx)
        val here = all.filter { it.network == net }
        _state.value = s.copy(
            network = net, archived = all.size, result = result,
            gaming = here.filter { it.kind == Kind.GAMING },
            normal = here.filter { it.kind == Kind.NORMAL && (it.proto || it.tcp) }.sortedWith(compareBy({ !it.proto }, { it.score })),
        )
    }

    fun clear(ctx: Context) {
        if (_state.value.running) return
        runCatching { file(ctx).delete() }
        show(ctx, emptyList(), "بایگانی پاک شد")
    }

    // ------------------------------------------------------------ running

    fun cancel() {
        job?.cancel()
        _state.value = _state.value.copy(running = false, stage = 0, result = "اسکن متوقف شد")
    }

    /** Starts the [kind] scan; gaming learns cities with a short connection, so the VPN must be allowed first. */
    fun start(ctx: Context, kind: Kind, disguise: Boolean) {
        if (_state.value.running || GameEngine.scan.value.running) return
        val net = GameEngine.networkKey(ctx)
        _state.value = _state.value.copy(kind = kind, running = true, stage = 1, done = 0, total = 0, found = 0, network = net, result = null)
        job = scope.launch {
            val rnd = Random(System.nanoTime())
            val found = try {
                if (kind == Kind.GAMING) gaming(ctx, net, disguise, rnd) else normal(net, rnd)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Engine.note("ip test ${kind.name.lowercase()}: ${e.message}")
                _state.value = _state.value.copy(running = false, stage = 0, result = e.message ?: "خطا")
                return@launch
            }
            val all = save(ctx, found)
            val proto = found.count { it.proto }
            val alive = found.count { !it.proto && it.tcp }
            Engine.note("ip test ${kind.name.lowercase()}: ${found.size} answered ($proto ${if (kind == Kind.GAMING) "handshake" else "tls"}, $alive tcp only); archive ${all.size}")
            val text = when {
                found.isEmpty() -> "هیچ آدرسی از این شبکه جواب نداد"
                kind == Kind.GAMING -> "${fa(proto)} دروازه‌ی بازی، ${fa(golden(found).size)} طلایی" +
                    (if (alive > 0) "؛ ${fa(alive)} آی‌پی فقط زنده (بایگانی)" else "")
                else -> "${fa(proto)} آی‌پی سفید روی ۴۴۳" + (if (alive > 0) "؛ ${fa(alive)} فقط زنده (بایگانی)" else "")
            }
            show(ctx, all, text, _state.value.copy(running = false, stage = 0))
        }
    }

    private fun progress(stage: Int, done: Int, total: Int, found: Int) {
        _state.value = _state.value.copy(stage = stage, done = done, total = total, found = found)
    }

    private suspend fun gaming(ctx: Context, net: String, disguise: Boolean, rnd: Random): List<Found> {
        val err = Engine.register(ctx, Transport.WIREGUARD)
        val id = WarpWg.load(ctx) ?: error("اکانت WireGuard ساخته نشد: ${err ?: "?"}")
        val now = System.currentTimeMillis()
        // Stage 1: one handshake per random address, in small groups, a random 15–45 ms apart and a
        // random rest between groups.
        val targets = randomTargets(WgScan.PREFIXES, WgScan.PORTS, 700, rnd)
        val first = HashMap<String, Int>()
        var done = 0
        for (group in targets.chunked(50)) {
            val got = WgScan.probe(id, disguise, group, waitMs = 1200, gapMs = { rnd.nextLong(15, 46) }) { _, _ -> }
            first += got
            done += group.size
            progress(1, done, targets.size, first.size)
            delay(rnd.nextLong(150, 450))
        }
        // The addresses that gave no handshake: is the address itself alive? (TCP; archive only.)
        val answeredHosts = first.keys.map { it.substringBeforeLast(':') }.toSet()
        val silent = targets.map { it.substringBeforeLast(':') }.distinct().filter { it !in answeredHosts }.shuffled(rnd).take(150)
        val tcpAlive = tcpCheck(silent, rnd) { d -> progress(1, targets.size, targets.size, first.size + d) }
        // Stage 2: the ones that answered, again six times, for ping, jitter and loss.
        val best = first.entries.sortedBy { it.value }.map { it.key }.take(80)
        val rtts = HashMap<String, MutableList<Int>>()
        val samples = 6
        repeat(samples) { round ->
            progress(2, round, samples, best.size)
            WgScan.probe(id, disguise, best.shuffled(rnd), waitMs = 900, gapMs = { rnd.nextLong(15, 36) })
                .forEach { (ep, ms) -> rtts.getOrPut(ep) { ArrayList() } += ms }
            delay(rnd.nextLong(80, 220))
        }
        val hits = best.mapNotNull { ep -> rtts[ep]?.let { WgScan.summarize(ep, it, samples) } }
        // Stage 3: the city of each /24, with a short real connection.
        val located = if (hits.isEmpty()) emptyList() else GameEngine.locate(ctx, id, disguise, hits, 5000) { d, t, f -> progress(3, d, t, f) }
        val coloOf = located.associateBy { it.endpoint }
        val proto = hits.map { h ->
            val l = coloOf[h.endpoint]
            Found(h.endpoint, Kind.GAMING, proto = true, tcp = false, pingMs = l?.pingMs ?: h.pingMs, jitterMs = h.jitterMs,
                lossPct = h.lossPct, colo = l?.colo.orEmpty(), network = net, seenAt = now)
        }
        // answered stage 1 but not stage 2: still a gateway, kept with its first ping and full loss
        val dropped = first.keys.filter { it !in rtts }.map { ep ->
            Found(ep, Kind.GAMING, proto = true, tcp = false, pingMs = first[ep] ?: 0, lossPct = 100, network = net, seenAt = now)
        }
        val alive = tcpAlive.map { Found("$it:443", Kind.GAMING, proto = false, tcp = true, network = net, seenAt = now) }
        return proto + dropped + alive
    }

    private suspend fun normal(net: String, rnd: Random): List<Found> {
        val now = System.currentTimeMillis()
        val hosts = randomTargets(H2Scan.PREFIXES, listOf(443), 240, rnd).map { it.substringBeforeLast(':') }
        // Stage 1: TLS on 443, at most eight at a time, each after a random pause.
        val gate = Semaphore(8)
        var done = 0
        var ok = 0
        val first = coroutineScope {
            hosts.map { h ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        ensureActive()
                        delay(rnd.nextLong(20, 160))
                        val ms = H2Scan.handshake(h, 2000)
                        synchronized(this@IpTest) {
                            done++
                            if (ms != null) ok++
                            progress(1, done, hosts.size, ok)
                        }
                        ms?.let { h to it }
                    }
                }
            }.awaitAll().filterNotNull().toMap()
        }
        val silent = hosts.filter { it !in first }.shuffled(rnd).take(120)
        val tcpAlive = tcpCheck(silent, rnd) { d -> progress(1, hosts.size, hosts.size, ok + d) }
        // Stage 2: the fastest 24 again four times, one after another.
        val best = first.entries.sortedBy { it.value }.map { it.key }.take(24)
        val samples = 4
        val rtts = HashMap<String, MutableList<Int>>()
        for (round in 0 until samples) {
            progress(2, round, samples, best.size)
            for (h in best.shuffled(rnd)) {
                kotlin.coroutines.coroutineContext.ensureActive()
                H2Scan.handshake(h, 2000)?.let { rtts.getOrPut(h) { ArrayList() } += it }
                delay(rnd.nextLong(30, 120))
            }
        }
        val exact = best.map { h ->
            val s = rtts[h]?.let { WgScan.summarize("$h:443", it, samples) }
            Found("$h:443", Kind.NORMAL, proto = true, tcp = true, pingMs = s?.pingMs ?: first[h] ?: 0,
                jitterMs = s?.jitterMs ?: 0, lossPct = s?.lossPct ?: 100, network = net, seenAt = now)
        }
        val rest = first.keys.filter { it !in best }.map { h ->
            Found("$h:443", Kind.NORMAL, proto = true, tcp = true, pingMs = first[h] ?: 0, network = net, seenAt = now)
        }
        val alive = tcpAlive.map { Found("$it:443", Kind.NORMAL, proto = false, tcp = true, network = net, seenAt = now) }
        return exact + rest + alive
    }

    /** Which of [hosts] open TCP 443 or 80; six at a time with random pauses. Not a proof of WARP: only «alive». */
    private suspend fun tcpCheck(hosts: List<String>, rnd: Random, onFound: (Int) -> Unit): List<String> {
        val gate = Semaphore(6)
        var n = 0
        return coroutineScope {
            hosts.map { h ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        ensureActive()
                        delay(rnd.nextLong(20, 120))
                        val alive = tcp(h, 443) || tcp(h, 80)
                        if (alive) synchronized(this@IpTest) { n++; onFound(n) }
                        h.takeIf { alive }
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private fun tcp(host: String, port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(host, port), 800) }
        true
    }.getOrDefault(false)

    private const val FA = "۰۱۲۳۴۵۶۷۸۹"
    private fun fa(n: Int): String = n.toString().map { if (it in '0'..'9') FA[it - '0'] else it }.joinToString("")
}
