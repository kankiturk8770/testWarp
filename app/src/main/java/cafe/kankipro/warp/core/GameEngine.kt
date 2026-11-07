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
    )

    /** The gamer scan for [transport] (Amnezia scans with its disguise): best paths of this network. */
    fun startScan(ctx: Context, transport: Transport) {
        if (_scan.value.running) return
        val net = networkKey(ctx)
        _scan.value = ScanState(running = true, network = net, hits = Prefs.gameHits(ctx, net))
        scope.launch {
            val err = Engine.register(ctx, Transport.WIREGUARD)
            val id = WarpWg.load(ctx)
            if (err != null || id == null) {
                _scan.value = _scan.value.copy(running = false, error = err ?: "اکانت WireGuard پیدا نشد")
                return@launch
            }
            val hits = runCatching {
                WgScan.scan(id, transport == Transport.AMNEZIA, wide = 400, keep = 10, samples = 10) { d, t, f ->
                    _scan.value = _scan.value.copy(done = d, total = t, found = f)
                }
            }.getOrElse { emptyList() }
            if (hits.isNotEmpty()) Prefs.saveGameHits(ctx, net, hits.take(6))
            _scan.value = _scan.value.copy(
                running = false, hits = if (hits.isNotEmpty()) hits.take(6) else _scan.value.hits,
                error = if (hits.isEmpty()) "هیچ دروازه‌ای از این شبکه جواب نداد" else null,
            )
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
                for ((n, ep) in tries.withIndex()) {
                    Engine.update { it.copy(gateway = ep, proven = 1, attempt = n + 1, attempts = tries.size,
                        message = if (n == 0) "روشن کردن موتور بازی…" else "دروازه‌ی قبلی رد شد؛ امتحان $ep…") }
                    up(ctx, id.config(ep, transport == Transport.AMNEZIA))
                    Engine.update { it.copy(proven = 2, message = "دست دادن با کلادفلر…") }
                    // Gateway proven = data came back from it; internet proven = a real request answered.
                    val deadline = SystemClock.elapsedRealtime() + 9_000
                    while (SystemClock.elapsedRealtime() < deadline && egress == null) {
                        if (rx(ctx) > 0) Engine.update { if (it.proven < 3) it.copy(proven = 3) else it }
                        egress = Probe.direct(2500)
                        if (egress == null) delay(300)
                    }
                    if (egress != null) { used = ep; break }
                    Engine.note("game: $ep did not pass the tunnel test")
                    runCatching { backend(ctx).setState(tunnel, Tunnel.State.DOWN, null) }
                }
                val out = egress ?: error("هیچ‌کدام از ${tries.size} دروازه از داخل تونل جواب نداد؛ دوباره «اسکن» بزن")
                // The one that worked goes to the top of this network's list.
                hits.firstOrNull { it.endpoint == used }?.let { pick(ctx, it) }
                val ep = used
                Engine.note("game: verified via ${transport.title} $ep: ${out.ip} ${out.colo}")
                Engine.update { it.copy(phase = Phase.RUNNING, proven = 4, message = "", egress = out,
                    startedAt = SystemClock.elapsedRealtime()) }
                watch(ctx)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                runCatching { backend(ctx).setState(tunnel, Tunnel.State.DOWN, null) }
                Engine.note("game: ${e.message}")
                Engine.update { it.copy(phase = Phase.ERROR, proven = 0, error = e.message ?: "وصل نشد", message = "", gamer = true) }
            }
        }
    }

    /** Some library builds reject I1..I5; then the same config goes without them (junk packets stay). */
    private fun up(ctx: Context, text: String) {
        val config = runCatching { Config.parse(text.byteInputStream()) }.getOrElse {
            Engine.note("game: config without I1..I5 (${it.message})")
            Config.parse(text.lines().filterNot { l -> l.trim().matches(Regex("I[1-5]\\s*=.*")) }.joinToString("\n").byteInputStream())
        }
        backend(ctx).setState(tunnel, Tunnel.State.UP, config)
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
            if (tick > 0 && tick % 30 == 0) Probe.direct(4000)?.let { e -> Engine.update { it.copy(egress = e) } }
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
