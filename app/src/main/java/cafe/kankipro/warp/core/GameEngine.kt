package cafe.kankipro.warp.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.telephony.TelephonyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.amnezia.awg.backend.Backend
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.Config

/**
 * The gamer mode: WireGuard (or Amnezia) straight on the phone's TUN with the AmneziaWG engine,
 * to a gateway the gamer scan picked. No core, no tun2socks, no SOCKS: a game's UDP packet goes
 * from the TUN into WireGuard and out, the shortest path the phone can give it.
 * The WARP account is the one the core registered (WarpWg); the core only runs to make it once.
 */
object GameEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private class T : Tunnel {
        override fun getName(): String = "kwarp"
        override fun onStateChange(newState: Tunnel.State) {}
        override fun isIpv4ResolutionPreferred(): Boolean = true
        override fun isMetered(): Boolean = false
    }

    private val tunnel = T()
    @Volatile private var backend: Backend? = null

    /** GoBackend's constructor differs between library versions: the one taking a Context first, the rest stubbed. */
    private fun backend(ctx: Context): Backend = backend ?: synchronized(this) {
        backend ?: run {
            val c = GoBackend::class.java.constructors
                .filter { it.parameterTypes.firstOrNull() == Context::class.java }
                .maxBy { it.parameterTypes.size }
            val args = c.parameterTypes.mapIndexed { i, t -> if (i == 0) ctx.applicationContext else stub(t) }.toTypedArray()
            (c.newInstance(*args) as Backend).also { backend = it }
        }
    }

    private fun stub(type: Class<*>): Any? = when {
        type.isInterface -> java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, m, a ->
            when (m.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === a?.getOrNull(0)
                "toString" -> "KwarpStub"
                else -> when (m.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    else -> null
                }
            }
        }
        type == java.lang.Boolean.TYPE -> false
        type == java.lang.Integer.TYPE -> 0
        type == java.lang.Long.TYPE -> 0L
        else -> null
    }

    fun isUp(ctx: Context): Boolean =
        runCatching { backend(ctx).getState(tunnel) == Tunnel.State.UP }.getOrDefault(false)

    // ------------------------------------------------------------ scanning

    private val _scan = MutableStateFlow<ScanState>(ScanState())
    val scan: StateFlow<ScanState> = _scan

    data class ScanState(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val found: Int = 0,
        val hits: List<WgScan.Hit> = emptyList(),
        val network: String = "",
        val error: String? = null,
        /** 1: finding gateways that answer; 2: a short real connection to each, to learn its city. */
        val phase: Int = 1,
        /** The quick scan: fewer addresses and samples, about a minute. */
        val fast: Boolean = false,
    )

    /** The gamer scan for [transport] (Amnezia scans with its disguise): best paths of this network. */
    fun startScan(ctx: Context, transport: Transport, fast: Boolean = false) {
        if (_scan.value.running) return
        val net = networkKey(ctx)
        _scan.value = ScanState(running = true, network = net, hits = Prefs.gameHits(ctx, net), fast = fast)
        scope.launch {
            val err = Engine.register(ctx, Transport.WIREGUARD)
            val id = WarpWg.load(ctx)
            if (err != null || id == null) {
                _scan.value = _scan.value.copy(running = false, error = err ?: "اکانت WireGuard پیدا نشد")
                return@launch
            }
            val disguise = transport == Transport.AMNEZIA
            // Phase 1: a wide sweep (about 1500 of WARP's ~96,000 address:port pairs), real handshakes.
            val found = runCatching {
                // Quick: ~600 pairs (about 8 s of handshakes), the best 10, 4 samples each; full: 2000 / 16 / 8.
                val (wide, keep, samples) = if (fast) Triple(600, 10, 4) else Triple(2000, 16, 8)
                WgScan.scan(id, disguise, wide = wide, keep = keep, samples = samples) { d, t, f ->
                    _scan.value = _scan.value.copy(done = d, total = t, found = f, phase = 1)
                }
            }.getOrElse { emptyList() }
            // Phase 2: the city of each, by connecting for a moment and asking Cloudflare where we came out.
            val located = if (found.isEmpty()) emptyList() else locate(ctx, id, disguise, found, if (fast) 4000 else 6000)
            val hits = (located.ifEmpty { found }).sortedWith(compareBy({ it.country !in setOf("DE", "TR") }, { it.score }))
            if (hits.isNotEmpty()) Prefs.saveGameHits(ctx, net, hits)
            _scan.value = _scan.value.copy(
                running = false, hits = if (hits.isNotEmpty()) hits else _scan.value.hits,
                error = if (hits.isEmpty()) "هیچ دروازه‌ای از این شبکه جواب نداد" else null,
            )
        }
    }

    /**
     * Brings the engine up on each gateway for a few seconds, asks Cloudflare's trace which data centre
     * answered, and measures the ping a game would see (DNS round trip through the tunnel). Anycast
     * means only a real connection can tell; the handshake alone cannot.
     */
    private suspend fun locate(
        ctx: Context, id: WarpWg, disguise: Boolean, hits: List<WgScan.Hit>, waitMs: Long,
    ): List<WgScan.Hit> {
        // Anycast is routed per address range: one gateway of each /24 tells the city of all of it,
        // so only the best of each range is connected to; the rest take its city. Much quicker.
        val range = { h: WgScan.Hit -> h.endpoint.substringBeforeLast(':').substringBeforeLast('.') }
        val heads = hits.groupBy(range).values.map { it.first() }
        val city = HashMap<String, Pair<String, Int>>() // range → (colo, dns ping)
        for ((n, h) in heads.withIndex()) {
            _scan.value = _scan.value.copy(phase = 2, done = n, total = heads.size, found = city.size)
            try {
                up(ctx, id.config(h.endpoint, disguise))
                var e: Probe.Egress? = null
                val until = SystemClock.elapsedRealtime() + waitMs
                while (e == null && SystemClock.elapsedRealtime() < until) {
                    e = Probe.direct(2000)
                    if (e == null) delay(200)
                }
                if (e != null) {
                    val ping = Probe.dnsPing(3, 1200)?.toInt() ?: h.pingMs
                    city[range(h)] = e.colo.uppercase() to ping
                    Engine.note("scan: ${h.endpoint} → ${e.colo} ${ping}ms")
                }
            } catch (e: Exception) {
                Engine.note("scan: ${h.endpoint} not usable (${reason(e)})")
            } finally {
                runCatching { backend(ctx).setState(tunnel, Tunnel.State.DOWN, null) }
            }
        }
        _scan.value = _scan.value.copy(done = heads.size, found = city.size)
        return hits.mapNotNull { h ->
            val (colo, ping) = city[range(h)] ?: return@mapNotNull null
            // the one connected to has the real in-tunnel ping; the others keep their handshake ping
            if (h in heads) h.copy(colo = colo, pingMs = ping) else h.copy(colo = colo)
        }
    }

    /** Makes [hit] this network's first choice (the one connect uses). */
    fun pick(ctx: Context, hit: WgScan.Hit) {
        val net = networkKey(ctx)
        val hits = listOf(hit) + Prefs.gameHits(ctx, net).filter { it.endpoint != hit.endpoint }
        Prefs.saveGameHits(ctx, net, hits)
        _scan.value = _scan.value.copy(hits = hits, network = net)
    }

    fun loadSaved(ctx: Context) {
        if (_scan.value.running) return
        val net = networkKey(ctx)
        _scan.value = ScanState(network = net, hits = Prefs.gameHits(ctx, net))
    }

    /** «wifi» or «cell:<operator>», so each network keeps its own best gateways. */
    fun networkKey(ctx: Context): String = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                "cell:" + ctx.getSystemService(TelephonyManager::class.java).networkOperatorName.ifBlank { "?" }
            else -> "other"
        }
    }.getOrDefault("other")

    // ---------------------------------------------------------- connecting

    /**
     * Connects the gamer mode: the account (once), a gateway (the chosen one, else this network's
     * best saved one, else a short scan), the engine, and then a real request through the tunnel
     * before it says «وصل».
     */
    fun connect(ctx: Context, transport: Transport, endpoint: String?) {
        job?.cancel()
        Engine.resetStats()
        Engine.update { ConnState(phase = Phase.CONNECTING, transport = transport, attempt = 1, attempts = 1,
            message = "آماده کردن اکانت…", gamer = true) }
        job = scope.launch {
            try {
                val err = Engine.register(ctx, Transport.WIREGUARD)
                val id = WarpWg.load(ctx) ?: error(err ?: "اکانت WireGuard ساخته نشد")
                Engine.update { it.copy(proven = 1, message = "انتخاب بهترین دروازه برای بازی…") }
                val net = networkKey(ctx)
                var hits = Prefs.gameHits(ctx, net)
                if (endpoint == null && hits.isEmpty()) {
                    Engine.update { it.copy(message = "اسکن دروازه‌ها برای بازی…") }
                    hits = WgScan.scan(id, transport == Transport.AMNEZIA, wide = 300, keep = 6, samples = 5)
                    if (hits.isNotEmpty()) Prefs.saveGameHits(ctx, net, hits.take(6))
                }
                // The chosen one first, then the next best of this network's list: if one fails the
                // tunnel test, the next is tried at once (up to four), no tap needed.
                val tries = (listOfNotNull(endpoint) + hits.map { it.endpoint }).distinct().take(4)
                if (tries.isEmpty()) error("هیچ دروازه‌ای از این شبکه جواب نداد؛ «اسکن» را بزن")
                var egress: Probe.Egress? = null
                var used = ""
                // Zero Trust first when the user joined a team; if no gateway passes with it, the
                // personal WARP account goes through the same gateways again, as before.
                val team = ZeroTrust.team(ctx)
                val accounts = listOfNotNull(ZeroTrust.identity(ctx)?.let { it to "Zero Trust ($team)" }) + (id to "")
                var via = ""
                for ((acct, label) in accounts) {
                if (egress != null) break
                if (label.isNotEmpty()) Engine.note("game: trying $label first")
                else if (accounts.size > 1) Engine.note("game: Zero Trust did not pass; back to personal WARP")
                // Zero Trust: the organization's own gateway first, then the scanned ones.
                val list = (acct.ownGateways() + tries).distinct().take(if (acct.team) 6 else 4)
                if (acct.team) {
                    Engine.note("game: Zero Trust gateways ${list.joinToString(" ")}; device id in the headers: ${acct.stamped}")
                    // what Cloudflare answers, with and without the device id in the handshake
                    list.firstOrNull()?.let { gw ->
                        Engine.note("game: zt check: " + WgScan.raw(acct.config(gw, false), gw))
                    }
                }
                for ((n, ep) in list.withIndex()) {
                    Engine.update { it.copy(gateway = ep, proven = 1, attempt = n + 1, attempts = list.size,
                        message = when {
                            n == 0 && label.isNotEmpty() -> "روشن کردن موتور بازی با Zero Trust…"
                            n == 0 -> "روشن کردن موتور بازی…"
                            else -> "دروازه‌ی قبلی رد شد؛ امتحان $ep…"
                        }) }
                    // Zero Trust's gateway answers only plain WireGuard handshakes (checked on a phone), so no Amnezia junk there
                    up(ctx, acct.config(ep, transport == Transport.AMNEZIA && !acct.team, dns = Prefs.options(ctx).dns.servers))
                    Engine.update { it.copy(proven = 2, message = "دست دادن با کلادفلر…") }
                    // Gateway proven = data came back from it; internet proven = a real request answered.
                    val deadline = SystemClock.elapsedRealtime() + 9_000
                    while (SystemClock.elapsedRealtime() < deadline && egress == null) {
                        if (rx(ctx) > 0) Engine.update { if (it.proven < 3) it.copy(proven = 3) else it }
                        egress = Probe.direct(2500)
                        if (egress == null) delay(300)
                    }
                    if (egress != null) { used = ep; via = label; break }
                    Engine.note("game: $ep did not pass the tunnel test")
                    runCatching { backend(ctx).setState(tunnel, Tunnel.State.DOWN, null) }
                }
                }
                val out0 = egress ?: error("هیچ‌کدام از ${tries.size} دروازه از داخل تونل جواب نداد؛ دوباره «اسکن» بزن")
                val out = out0.copy(pingMs = Probe.dnsPing() ?: out0.pingMs)
                // The one that worked goes to the top of this network's list.
                hits.firstOrNull { it.endpoint == used }?.let { pick(ctx, it) }
                val ep = used
                // Say what really connected: Amnezia only when the engine took the config with its junk packets.
                val actual = when {
                    // Zero Trust goes plain on purpose: its gateway answers no disguised handshake
                    via.isNotEmpty() -> "WireGuard ساده"
                    transport != Transport.AMNEZIA -> "WireGuard ساده (بی‌پوشش)"
                    took == "full" -> "Amnezia · پوشش کامل (امضای I1–I4 + آشغال)"
                    took == "without I1..I3" -> "Amnezia · فقط بسته‌ی آشغال (موتور امضاها را نپذیرفت)"
                    else -> "WireGuard ساده: موتور پوشش Amnezia را نپذیرفت"
                } + (if (via.isNotEmpty()) " · $via" else "")
                Engine.note("game: verified $ep: ${out.ip} ${out.colo}")
                Engine.note("✓ وصل با: $actual")
                Engine.update { it.copy(phase = Phase.RUNNING, proven = 4, message = "", egress = out, actual = actual,
                    startedAt = SystemClock.elapsedRealtime()) }
                // accounts the open network could not make, now through this tunnel
                scope.launch { Engine.prepareAll(ctx) }
                watch(ctx)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                runCatching { backend(ctx).setState(tunnel, Tunnel.State.DOWN, null) }
                Engine.note("game: ${e.message}")
                Engine.update { it.copy(phase = Phase.ERROR, proven = 0, error = e.message ?: reason(e), message = "", gamer = true) }
            }
        }
    }

    /**
     * Starts the engine with [text], and if the engine refuses it, again with less: first without the
     * I1..I3 signature packets (the part an engine build is most likely to refuse), then with no
     * disguise at all. Each refusal is written to the log with the engine's own reason, so a
     * «وصل نشد» never comes without one.
     */
    /** Which of [up]'s configs the engine took: «full» (with the disguise) or a simpler one. */
    @Volatile private var took = ""

    private fun up(ctx: Context, text: String) {
        val noI = text.lines().filterNot { it.trim().matches(Regex("I[1-5]\\s*=.*")) }.joinToString("\n")
        val plain = noI.lines().filterNot { it.trim().matches(Regex("J(c|min|max)\\s*=.*")) }.joinToString("\n")
        val tries = listOf("full" to text, "without I1..I3" to noI, "plain WireGuard" to plain).distinctBy { it.second }
        var last: Exception? = null
        for ((name, cfg) in tries) {
            try {
                val config = Config.parse(cfg.byteInputStream())
                backend(ctx).setState(tunnel, Tunnel.State.UP, config)
                took = name
                if (name != "full") Engine.note("game: the engine took the config $name")
                return
            } catch (e: Exception) {
                last = e
                Engine.note("game: the engine refused the config ($name): ${reason(e)}")
                runCatching { backend(ctx).setState(tunnel, Tunnel.State.DOWN, null) }
            }
        }
        throw IllegalStateException("موتور بازی روشن نشد: ${reason(last)}")
    }

    /** The engine's exceptions often carry no message, only a reason code: say that instead. */
    private fun reason(e: Throwable?): String {
        if (e == null) return "?"
        val code = runCatching { e.javaClass.getMethod("getReason").invoke(e)?.toString() }.getOrNull()
        return listOfNotNull(e.message?.takeIf { it.isNotBlank() }, code, e.javaClass.simpleName).first()
    }

    private suspend fun watch(ctx: Context) {
        var tick = 0
        var lastRx = 0L
        var lastTx = 0L
        while (scope.isActive) {
            val s = stats(ctx)
            if (s != null) {
                val (rxB, txB) = s
                Engine.setTraffic(Traffic(up = txB, down = rxB, upRate = (txB - lastTx).coerceAtLeast(0),
                    downRate = (rxB - lastRx).coerceAtLeast(0)))
                lastRx = rxB; lastTx = txB
            }
            if (!isUp(ctx)) {
                Engine.update { it.copy(phase = Phase.ERROR, proven = 0, error = "موتور بازی متوقف شد") }
                return
            }
            // the ping every 5 s (one small DNS packet), where the traffic comes out every 60 s
            if (tick > 0 && tick % 5 == 0) Probe.dnsPing()?.let { ms ->
                Engine.update { s -> s.copy(egress = s.egress?.copy(pingMs = ms)) }
            }
            if (tick > 0 && tick % 60 == 0) Probe.direct(4000)?.let { e ->
                Engine.update { it.copy(egress = e.copy(pingMs = it.egress?.pingMs ?: e.pingMs)) }
            }
            tick++
            delay(1000)
        }
    }

    fun disconnect(ctx: Context) {
        job?.cancel()
        job = null
        runCatching { backend(ctx).setState(tunnel, Tunnel.State.DOWN, null) }
        Engine.resetStats()
        Engine.update { ConnState() }
    }

    private fun rx(ctx: Context): Long = stats(ctx)?.first ?: 0L

    /** Bytes received and sent this session. */
    private fun stats(ctx: Context): Pair<Long, Long>? = runCatching {
        val s: Any = backend(ctx).getStatistics(tunnel)
        val rx = s.javaClass.getMethod("totalRx").invoke(s) as Long
        val tx = s.javaClass.getMethod("totalTx").invoke(s) as Long
        rx to tx
    }.getOrNull()
}
