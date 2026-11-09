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
 * The «تست آی‌پی» screen. Each scan has its own ranges and its own proof, and its results stay apart:
 *  - GAMING: WireGuard's ranges, a real WireGuard handshake (Amnezia's disguise when that engine is picked);
 *  - AMNEZIA / WIREGUARD (normal user): the same handshake, with or without the disguise;
 *  - H2: MASQUE's ranges, a TLS handshake on TCP 443;
 *  - H3: MASQUE's ranges and ports, a QUIC packet (H3Scan); Gool and MASQUE-in-MASQUE use these too.
 * Every run takes random ranges, addresses and ports, a few at a time with random pauses.
 *
 * Stage 1 alive (the protocol's own answer; TCP 443/80 for the silent ones, kept as «alive only»),
 * stage 2 exact (ping, jitter, loss), stage 3 the city (WireGuard kinds: one short real connection per
 * /24, GameEngine.locate; MASQUE kinds take the city a /24 already showed on this network).
 * The proof is never TCP alone: some networks (and the cloud test machine's proxy) open every TCP connect.
 */
object IpTest {
    enum class Kind(val title: String, val sub: String) {
        GAMING("گیمینگ", "WireGuard / Amnezia مستقیم"),
        AMNEZIA("Amnezia", "دست‌دادن با پوشش"),
        WIREGUARD("WireGuard", "دست‌دادن ساده"),
        H2("MASQUE · HTTP/2", "TLS روی TCP 443"),
        H3("MASQUE · HTTP/3", "QUIC؛ برای Gool و MASQUE در MASQUE هم");

        /** The normal user's transports an address of this scan can be the fixed IP of. */
        val transports: List<Transport>
            get() = when (this) {
                GAMING, AMNEZIA, WIREGUARD -> listOf(Transport.AMNEZIA, Transport.WIREGUARD)
                H2 -> listOf(Transport.MASQUE_H2)
                H3 -> listOf(Transport.MASQUE_H3, Transport.GOOL, Transport.MIM)
            }

        val wireguard: Boolean get() = this == GAMING || this == AMNEZIA || this == WIREGUARD

        companion object {
            val NORMAL = listOf(AMNEZIA, WIREGUARD, H2, H3)
        }
    }

    data class Found(
        val endpoint: String,
        val kind: Kind,
        /** The protocol itself answered (handshake, TLS or QUIC). */
        val proto: Boolean,
        /** TCP 443 or 80 opened (the address is alive, maybe only that). */
        val tcp: Boolean,
        val pingMs: Int = 0,
        val jitterMs: Int = 0,
        /** -1: answered once, not measured again (too many to sample). */
        val lossPct: Int = 0,
        val colo: String = "",
        val network: String = "",
        val seenAt: Long = 0,
    ) {
        val country: String get() = Colos.country(colo)
        val score: Int get() = pingMs + jitterMs * 3 + lossPct.coerceAtLeast(0) * 20
        val range: String get() = endpoint.substringBeforeLast(':').substringBeforeLast('.')
        fun hit() = WgScan.Hit(endpoint, pingMs, jitterMs, lossPct.coerceAtLeast(0), colo)
    }

    data class State(
        val running: Boolean = false,
        /** The scan running now, and which of how many. */
        val kind: Kind? = null,
        val step: Int = 0,
        val steps: Int = 0,
        /** 1 alive, 2 exact, 3 city. */
        val stage: Int = 0,
        val done: Int = 0,
        val total: Int = 0,
        val found: Int = 0,
        val network: String = "",
        /** This network's results by scan, newest of each address. */
        val results: Map<Kind, List<Found>> = emptyMap(),
        /** The archive of every network. */
        val archived: Int = 0,
        val result: String? = null,
    ) {
        fun of(k: Kind): List<Found> = results[k].orEmpty()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** The golden list's limit: a game is still fine up to about 200 ms. */
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

    private val V4 = Regex("""(?<![\d.])(\d{1,3}(?:\.\d{1,3}){3})(?::(\d{1,5}))?(?![\d.])""")
    private val V6 = Regex("""\[([0-9a-fA-F:]+)]:(\d{1,5})""")

    /**
     * The «ip:port» in text pasted from a channel (one per line, or mixed with other words), for [kind].
     * A bare IPv4 is taken only for HTTP/2 (port 443); the other kinds need the port, which is what
     * differs between networks. Invalid octets or ports are dropped, duplicates once, at most [max].
     */
    fun parseList(text: String, kind: Kind, max: Int = 1000): List<String> {
        val out = LinkedHashSet<String>()
        for (m in V4.findAll(text)) {
            val ip = m.groupValues[1]
            if (ip.split('.').any { it.toInt() > 255 }) continue
            val port = m.groupValues[2].toIntOrNull() ?: if (kind == Kind.H2) 443 else continue
            if (port !in 1..65535) continue
            out += "$ip:$port"
        }
        for (m in V6.findAll(text)) {
            val port = m.groupValues[2].toInt()
            if (port in 1..65535) out += "[${m.groupValues[1]}]:$port"
        }
        return out.take(max)
    }

    /** Usable now: the protocol answered and the last measurement did not lose everything. */
    fun working(list: List<Found>): List<Found> =
        list.filter { it.proto && it.pingMs > 0 && it.lossPct < 100 }.sortedBy { it.pingMs }

    /** The golden gaming list: measured, ping up to [MAX_GAME_PING], no or little loss; loss first, then ping. */
    fun golden(list: List<Found>): List<Found> =
        working(list).filter { it.lossPct in 0..10 && it.pingMs <= MAX_GAME_PING }
            .sortedWith(compareBy({ it.lossPct }, { it.pingMs }, { it.jitterMs }))

    /** Every working one by country, each country lowest ping first; Turkey and Germany first, unknown last. */
    fun byCountry(list: List<Found>): List<Pair<String, List<Found>>> =
        working(list).groupBy { it.country.ifBlank { "??" } }.toList()
            .sortedWith(compareBy({ it.first != "TR" }, { it.first != "DE" }, { it.first == "??" }, { it.second.first().pingMs }))

    /** The city of each /24 this network has already shown (newest first wins). */
    fun knownColos(list: List<Found>): Map<String, String> =
        list.filter { it.colo.isNotBlank() }.sortedByDescending { it.seenAt }
            .groupBy { it.range }.mapValues { it.value.first().colo }

    /** Old and new results together: the newest of each kind+address+network wins, at most [cap], newest first. */
    fun merge(old: List<Found>, new: List<Found>, cap: Int = 5000): List<Found> =
        (new + old).sortedByDescending { it.seenAt }
            .distinctBy { Triple(it.kind, it.endpoint, it.network) }.take(cap)

    fun line(f: Found): String = listOf(
        f.kind.name, f.endpoint, if (f.proto) 1 else 0, if (f.tcp) 1 else 0, f.pingMs, f.jitterMs, f.lossPct,
        f.colo, f.network.replace(',', ' ').replace('\n', ' '), f.seenAt,
    ).joinToString(",")

    fun parse(line: String): Found? = runCatching {
        val p = line.split(',')
        if (p.size < 10) return null
        // 0.11.0 wrote the MASQUE · HTTP/2 scan as NORMAL
        val kind = if (p[0] == "NORMAL") Kind.H2 else Kind.valueOf(p[0])
        Found(p[1], kind, p[2] == "1", p[3] == "1", p[4].toInt(), p[5].toInt(), p[6].toInt(), p[7], p[8], p[9].toLong())
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
        _state.value = s.copy(network = net, archived = all.size, result = result, results = here.groupBy { it.kind })
    }

    fun clear(ctx: Context) {
        if (_state.value.running) return
        runCatching { file(ctx).delete() }
        show(ctx, emptyList(), "بایگانی پاک شد")
    }

    // ------------------------------------------------------------ running

    fun cancel() {
        job?.cancel()
        _state.value = _state.value.copy(running = false, stage = 0, kind = null, result = "اسکن متوقف شد")
    }

    /**
     * Runs the [kinds] one after another; the WireGuard kinds learn cities with a short connection, so
     * the VPN must be allowed first. [gamingDisguise]: the gamer engine is Amnezia.
     */
    fun start(ctx: Context, kinds: List<Kind>, gamingDisguise: Boolean, given: List<String>? = null) {
        if (_state.value.running || GameEngine.scan.value.running || kinds.isEmpty()) return
        val net = GameEngine.networkKey(ctx)
        _state.value = _state.value.copy(running = true, kind = kinds.first(), step = 1, steps = kinds.size, stage = 1,
            done = 0, total = 0, found = 0, network = net, result = null)
        job = scope.launch {
            val rnd = Random(System.nanoTime())
            val lines = ArrayList<String>()
            for ((i, kind) in kinds.withIndex()) {
                _state.value = _state.value.copy(kind = kind, step = i + 1, stage = 1, done = 0, total = 0, found = 0)
                val found = try {
                    run(ctx, kind, net, gamingDisguise, rnd, given)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Engine.note("ip test ${kind.name.lowercase()}: ${e.message}")
                    lines += "${kind.title}: ${e.message ?: "خطا"}"
                    continue
                }
                val all = save(ctx, found)
                val ok = working(found).size
                val alive = found.count { !it.proto && it.tcp }
                Engine.note("ip test ${kind.name.lowercase()}: ${found.count { it.proto }} answered, $ok working, $alive tcp only; archive ${all.size}")
                lines += "${kind.title}: " + if (ok == 0) "چیزی پیدا نشد" else "${fa(ok)} آی‌پی کارکن" +
                    (if (kind == Kind.GAMING) "، ${fa(golden(found).size)} طلایی" else "")
                show(ctx, all, null)
            }
            show(ctx, archive(ctx), lines.joinToString("\n"), _state.value.copy(running = false, stage = 0, kind = null))
        }
    }

    private fun progress(stage: Int, done: Int, total: Int, found: Int) {
        _state.value = _state.value.copy(stage = stage, done = done, total = total, found = found)
    }

    /** [given]: addresses pasted from a channel, tested instead of random ones (in random order all the same). */
    private suspend fun run(ctx: Context, kind: Kind, net: String, gamingDisguise: Boolean, rnd: Random, given: List<String>?): List<Found> {
        val list = given?.shuffled(rnd)
        return when (kind) {
            Kind.GAMING -> wireguard(ctx, kind, net, gamingDisguise, rnd, list ?: randomTargets(WgScan.PREFIXES, WgScan.PORTS, 700, rnd), sample = 200, samples = 6)
            Kind.AMNEZIA -> wireguard(ctx, kind, net, true, rnd, list ?: randomTargets(WgScan.PREFIXES, WgScan.PORTS, 500, rnd), sample = 150, samples = 4)
            Kind.WIREGUARD -> wireguard(ctx, kind, net, false, rnd, list ?: randomTargets(WgScan.PREFIXES, WgScan.PORTS, 500, rnd), sample = 150, samples = 4)
            Kind.H2 -> h2(ctx, net, rnd, list?.map { it.substringBeforeLast(':') }
                ?: randomTargets(H2Scan.PREFIXES, listOf(443), 240, rnd).map { it.substringBeforeLast(':') })
            Kind.H3 -> h3(ctx, net, rnd, list ?: randomTargets(H2Scan.PREFIXES, H3Scan.PORTS, 500, rnd))
        }
    }

    private suspend fun wireguard(
        ctx: Context, kind: Kind, net: String, disguise: Boolean, rnd: Random, targets: List<String>, sample: Int, samples: Int,
    ): List<Found> {
        val err = Engine.register(ctx, Transport.WIREGUARD)
        val id = WarpWg.load(ctx) ?: error("اکانت WireGuard ساخته نشد: ${err ?: "?"}")
        val now = System.currentTimeMillis()
        // Stage 1: one handshake per random address, in groups of 50, a random 15–45 ms apart, a random rest between groups.
        val first = HashMap<String, Int>()
        var done = 0
        for (group in targets.chunked(50)) {
            first += WgScan.probe(id, disguise, group, waitMs = 2000, gapMs = { rnd.nextLong(15, 46) })
            done += group.size
            progress(1, done, targets.size, first.size)
            delay(rnd.nextLong(150, 450))
        }
        // The silent ones: is the address itself alive? (TCP; archive only.)
        val answeredHosts = first.keys.map { it.substringBeforeLast(':') }.toSet()
        val silent = targets.map { it.substringBeforeLast(':') }.distinct().filter { it !in answeredHosts }.shuffled(rnd).take(120)
        val tcpAlive = tcpCheck(silent, rnd) { d -> progress(1, targets.size, targets.size, first.size + d) }
        // Stage 2: every one that answered (up to [sample], fastest first), [samples] more handshakes.
        val best = first.entries.sortedBy { it.value }.map { it.key }.take(sample)
        val rtts = HashMap<String, MutableList<Int>>()
        repeat(samples) { round ->
            progress(2, round, samples, best.size)
            WgScan.probe(id, disguise, best.shuffled(rnd), waitMs = 1500, gapMs = { rnd.nextLong(15, 36) })
                .forEach { (ep, ms) -> rtts.getOrPut(ep) { ArrayList() } += ms }
            delay(rnd.nextLong(80, 220))
        }
        val hits = best.map { ep ->
            rtts[ep]?.let { WgScan.summarize(ep, it, samples) } ?: WgScan.Hit(ep, first[ep] ?: 0, 0, 100)
        }
        // Stage 3: the city of each /24 not yet known on this network, with a short real connection.
        val known = knownColos(archive(ctx).filter { it.network == net }).toMutableMap()
        val unknown = hits.filter { it.lossPct < 100 && known[it.endpoint.substringBeforeLast(':').substringBeforeLast('.')] == null }
        if (unknown.isNotEmpty()) {
            GameEngine.locate(ctx, id, disguise, unknown, 5000) { d, t, f -> progress(3, d, t, f) }.forEach {
                known[it.endpoint.substringBeforeLast(':').substringBeforeLast('.')] = it.colo
            }
        }
        val measured = hits.map { h ->
            Found(h.endpoint, kind, proto = true, tcp = false, pingMs = h.pingMs, jitterMs = h.jitterMs, lossPct = h.lossPct,
                colo = known[h.endpoint.substringBeforeLast(':').substringBeforeLast('.')].orEmpty(), network = net, seenAt = now)
        }
        // beyond [sample]: answered once, kept with that ping, loss not measured
        val rest = first.keys.filter { it !in best }.map { ep ->
            Found(ep, kind, proto = true, tcp = false, pingMs = first[ep] ?: 0, lossPct = -1,
                colo = known[ep.substringBeforeLast(':').substringBeforeLast('.')].orEmpty(), network = net, seenAt = now)
        }
        val alive = tcpAlive.map { Found("$it:443", kind, proto = false, tcp = true, network = net, seenAt = now) }
        return measured + rest + alive
    }

    private suspend fun h2(ctx: Context, net: String, rnd: Random, hosts: List<String>): List<Found> {
        val now = System.currentTimeMillis()
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
                        val ms = H2Scan.handshake(h, 2500)
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
        val silent = hosts.filter { it !in first }.shuffled(rnd).take(100)
        val tcpAlive = tcpCheck(silent, rnd) { d -> progress(1, hosts.size, hosts.size, ok + d) }
        // Stage 2: the fastest 30 again four times, one after another.
        val best = first.entries.sortedBy { it.value }.map { it.key }.take(30)
        val samples = 4
        val rtts = HashMap<String, MutableList<Int>>()
        for (round in 0 until samples) {
            progress(2, round, samples, best.size)
            for (h in best.shuffled(rnd)) {
                kotlin.coroutines.coroutineContext.ensureActive()
                H2Scan.handshake(h, 2500)?.let { rtts.getOrPut(h) { ArrayList() } += it }
                delay(rnd.nextLong(30, 120))
            }
        }
        val known = knownColos(archive(ctx).filter { it.network == net })
        val exact = best.map { h ->
            val s = rtts[h]?.let { WgScan.summarize("$h:443", it, samples) }
            Found("$h:443", Kind.H2, proto = true, tcp = true, pingMs = s?.pingMs ?: first[h] ?: 0, jitterMs = s?.jitterMs ?: 0,
                lossPct = s?.lossPct ?: 100, colo = known[h.substringBeforeLast('.')].orEmpty(), network = net, seenAt = now)
        }
        val rest = first.keys.filter { it !in best }.map { h ->
            Found("$h:443", Kind.H2, proto = true, tcp = true, pingMs = first[h] ?: 0, lossPct = -1,
                colo = known[h.substringBeforeLast('.')].orEmpty(), network = net, seenAt = now)
        }
        val alive = tcpAlive.map { Found("$it:443", Kind.H2, proto = false, tcp = true, network = net, seenAt = now) }
        return exact + rest + alive
    }

    private suspend fun h3(ctx: Context, net: String, rnd: Random, targets: List<String>): List<Found> {
        val now = System.currentTimeMillis()
        // Stage 1: one QUIC packet per address and port, in groups of 50 with random pauses.
        val first = HashMap<String, Int>()
        var done = 0
        for (group in targets.chunked(50)) {
            first += H3Scan.probe(group, waitMs = 2000, rnd = rnd, gapMs = { rnd.nextLong(15, 46) })
            done += group.size
            progress(1, done, targets.size, first.size)
            delay(rnd.nextLong(150, 450))
        }
        // Stage 2: up to 150 of them, four more times.
        val best = first.entries.sortedBy { it.value }.map { it.key }.take(150)
        val samples = 4
        val rtts = HashMap<String, MutableList<Int>>()
        repeat(samples) { round ->
            progress(2, round, samples, best.size)
            H3Scan.probe(best.shuffled(rnd), waitMs = 1500, rnd = rnd, gapMs = { rnd.nextLong(15, 36) })
                .forEach { (ep, ms) -> rtts.getOrPut(ep) { ArrayList() } += ms }
            delay(rnd.nextLong(80, 220))
        }
        val known = knownColos(archive(ctx).filter { it.network == net })
        val exact = best.map { ep ->
            val s = rtts[ep]?.let { WgScan.summarize(ep, it, samples) }
            Found(ep, Kind.H3, proto = true, tcp = false, pingMs = s?.pingMs ?: first[ep] ?: 0, jitterMs = s?.jitterMs ?: 0,
                lossPct = s?.lossPct ?: 100, colo = known[ep.substringBeforeLast(':').substringBeforeLast('.')].orEmpty(), network = net, seenAt = now)
        }
        val rest = first.keys.filter { it !in best }.map { ep ->
            Found(ep, Kind.H3, proto = true, tcp = false, pingMs = first[ep] ?: 0, lossPct = -1,
                colo = known[ep.substringBeforeLast(':').substringBeforeLast('.')].orEmpty(), network = net, seenAt = now)
        }
        return exact + rest
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
