package cafe.kankipro.warp.core

import android.content.Context
import android.os.SystemClock
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The four things that must be proven, in the order they happen, before the app says «وصل».
 * The core opens its SOCKS5 port before it has found a gateway, so an open port only proves
 * the core; the gateway is proven by the core's own log line naming the edge it uses.
 */
enum class Stage(val title: String) {
    CORE("هسته"),
    TUNNEL("تونل گوشی"),
    GATEWAY("دروازهٔ کلادفلر"),
    INTERNET("اینترنت"),
}

enum class Phase { IDLE, CONNECTING, RUNNING, STOPPING, ERROR }

data class ConnState(
    val phase: Phase = Phase.IDLE,
    /** Stages proven so far in this attempt (0..4). */
    val proven: Int = 0,
    /** One plain sentence: what is happening now. */
    val message: String = "",
    val error: String? = null,
    val transport: Transport? = null,
    val attempt: Int = 0,
    val attempts: Int = 0,
    val egress: Probe.Egress? = null,
    val gateway: String = "",
    val startedAt: Long = 0,
)

/** Traffic counters on their own flow: they tick every second and must not redraw the screen. */
data class Traffic(val up: Long = 0, val down: Long = 0, val upRate: Long = 0, val downRate: Long = 0)

/**
 * Everything between the VPN service and the native libraries: the core job, the
 * tun2socks thread, and the state the screens draw.
 */
object Engine {
    private val _state = MutableStateFlow(ConnState())
    val state: StateFlow<ConnState> = _state

    private val _traffic = MutableStateFlow(Traffic())
    val traffic: StateFlow<Traffic> = _traffic

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    fun update(f: (ConnState) -> ConnState) = _state.update(f)

    // ------------------------------------------------------------------ log

    fun note(line: String) = append(listOf("· $line"))

    /** Pulls new core/hev lines out of the native ring and reads the useful facts in them. */
    fun pumpLog() {
        val raw = runCatching { if (Native.ensure()) Native.drainLog() else null }.getOrNull() ?: return
        val lines = raw.split('\n').map { it.trimEnd() }.filter { it.isNotBlank() }.map(::shorten)
        if (lines.isEmpty()) return
        lines.forEach(::learn)
        append(lines)
    }

    private fun append(lines: List<String>) = _log.update { (it + lines).takeLast(500) }

    fun clearLog() {
        _log.value = emptyList()
    }

    /** env_logger prefixes every line with a timestamp and module; keep the time and the text. */
    private fun shorten(line: String): String {
        val m = Regex("""^\[(\S+)T(\d\d:\d\d:\d\d)\S*\s+(\w+)\s+[^\]]*]\s*(.*)$""").find(line) ?: return line
        return "${m.groupValues[2]} ${m.groupValues[4]}"
    }

    private val GATEWAY = Regex("""(?:using cloudflare edge|selected MASQUE gateway)\s+([0-9a-fA-F.:\[\]]+)""")
    private val CACHED = Regex("""cached (?:gateway|endpoint)\s+([0-9a-fA-F.:\[\]]+)\s+still works""")

    /** Set when the core's log says its tunnel carries data; the probe waits for it. */
    @Volatile var tunnelReady = false

    private fun learn(line: String) {
        if ("validated" in line || "serving socks5" in line || "still works" in line ||
            "using cloudflare edge" in line || "masque-in-masque ready" in line) tunnelReady = true
        val gw = GATEWAY.find(line)?.groupValues?.get(1) ?: CACHED.find(line)?.groupValues?.get(1)
        if (gw != null) update {
            val proven = if (it.phase == Phase.CONNECTING && it.proven == Stage.GATEWAY.ordinal) it.proven + 1 else it.proven
            it.copy(gateway = gw, proven = proven)
        }
        when {
            "provisioning dedicated masque account" in line || "no masque identity found" in line ||
                "provision" in line && "wireguard" in line -> say("ساخت اکانت رایگان WARP در کلادفلر…")
            "enrolling masque key" in line -> say("ثبت کلید MASQUE در کلادفلر…")
            "hunting for a working MASQUE gateway" in line || "scanning" in line.lowercase() && "gateway" in line ->
                say("گشتن دنبال دروازه‌ای که جواب بدهد…")
            gw != null -> say("دروازه پیدا شد: $gw")
        }
    }

    private fun say(text: String) = update { if (it.phase == Phase.CONNECTING) it.copy(message = text) else it }

    // ----------------------------------------------------------------- core

    @Volatile private var jobId = 0L

    fun identityPath(ctx: Context): String {
        val dir = File(ctx.filesDir, "warp").apply { mkdirs() }
        return File(dir, "identity.toml").absolutePath
    }

    /**
     * The identity files [transport] needs, as the core names them next to [identityPath]:
     * identity.toml (WireGuard), identity-masque.toml, -masque-gool.toml, -masque-secondary.toml.
     */
    private fun identityFiles(ctx: Context, transport: Transport): List<File> {
        val dir = File(ctx.filesDir, "warp")
        val masque = File(dir, "identity-masque.toml")
        return when (transport) {
            Transport.MASQUE_H2, Transport.MASQUE_H3 -> listOf(masque)
            Transport.GOOL -> listOf(masque, File(dir, "identity-masque-gool.toml"))
            Transport.MIM -> listOf(masque, File(dir, "identity-masque-secondary.toml"))
            Transport.WIREGUARD, Transport.AMNEZIA -> listOf(File(dir, "identity.toml"))
        }
    }

    fun hasIdentity(ctx: Context, transport: Transport): Boolean =
        identityFiles(ctx, transport).all { it.length() > 0 }

    /** Deletes the saved WARP accounts and gateways. */
    fun forgetIdentity(ctx: Context) {
        File(ctx.filesDir, "warp").deleteRecursively()
    }

    /**
     * Makes the WARP account [transport] needs, and nothing else: the core's `--register` mode
     * talks to Cloudflare's API and returns without scanning or opening a tunnel. Done once, apart
     * from connecting, so a slow API (from Iran it goes through fronted addresses and retries)
     * is never cut short by the time a connection attempt gets. Null on success, else the reason.
     */
    suspend fun register(ctx: Context, transport: Transport, timeoutMs: Long = 240_000): String? =
        withContext(Dispatchers.IO) {
            if (hasIdentity(ctx, transport)) return@withContext null
            if (!Native.ensure()) return@withContext "هسته بارگذاری نشد: ${Native.loadError}"
            stopCore()
            clearCoreEnv()
            val set = when (transport) {
                Transport.MASQUE_H2, Transport.MASQUE_H3 -> "masque"
                Transport.GOOL -> "gool"
                Transport.MIM -> "mim"
                Transport.WIREGUARD, Transport.AMNEZIA -> "wg"
            }
            val args = listOf("--config", identityPath(ctx), "--log-level", "info", "--register", set)
            note("register: ${args.joinToString(" ")}")
            val reply = JSONObject(Native.coreStart(JSONArray(args).toString()))
            if (!reply.optBoolean("ok")) return@withContext reply.optString("error", "هسته شروع نشد")
            jobId = reply.optLong("job")
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (SystemClock.elapsedRealtime() < deadline) {
                pumpLog()
                val r = poll()
                if (!r.running) {
                    stopCore()
                    pumpLog()
                    return@withContext when {
                        hasIdentity(ctx, transport) -> null
                        !r.error.isNullOrBlank() -> r.error
                        else -> "اکانت ساخته نشد"
                    }
                }
                delay(300)
            }
            stopCore()
            "کلادفلر در ${timeoutMs / 1000} ثانیه جواب نداد"
        }

    /** What the settings screen says about the account: null when nothing is going on. */
    private val _account = MutableStateFlow<String?>(null)
    val account: StateFlow<String?> = _account
    @Volatile var accountBusy = false
        private set

    /** «ساختن اکانت تازه»: forget the old accounts and make a new one now, for [transport]. */
    suspend fun remakeAccount(ctx: Context, transport: Transport) {
        if (accountBusy) return
        accountBusy = true
        try {
            forgetIdentity(ctx)
            _account.value = "در حال ساخت اکانت در کلادفلر… (تا چند دقیقه)"
            val err = register(ctx, transport)
            _account.value = if (err == null) "اکانت تازه ساخته شد ✓" else "ساخته نشد: $err"
        } finally {
            accountBusy = false
        }
    }

    /**
     * The core reads its settings from environment variables its argument parser sets, and never
     * clears them. A second start in the same process would inherit the first one's switches
     * (worst of all --register, which makes the core register and exit instead of serving), so
     * they are dropped first. Java's System.getenv() is a copy taken when the process started and
     * never shows what the core set later, hence the explicit list (the names in the core's cli.rs).
     */
    private fun clearCoreEnv() {
        CORE_ENV.forEach { runCatching { Os.unsetenv("AETHER_$it") } }
    }

    private val CORE_ENV = (
        "ACCESS_CLIENT_ID ACCESS_CLIENT_SECRET ACCESS_EMAIL ACCESS_TOKEN API_ADDRESS API_HOST API_SNI CONFIG " +
        "DISABLE_EXTENSION_PERMUTATION DISABLE_GREASE DISABLE_OCSP_STAPLING DISABLE_SCT DISABLE_SESSION_TICKET DNS " +
        "ECH ECH_DNS ECH_DOMAIN ENABLE_ECH_GREASE ENABLE_GREASE_SIGALGS EXIT_LOC EXIT_LOC_SECS GATEWAY GOOL_INNER " +
        "GOOL_MODE HTTP_PROXY IP LOG_LEVEL MARK MASQUE_CONFIG MASQUE_H2_FRAGMENT MASQUE_H2_FRAGMENT_DELAY " +
        "MASQUE_H2_FRAGMENT_SIZE MASQUE_H2_PEER MASQUE_HTTP2 MASQUE_NO_DATA_CHECK MASQUE_RECONNECT_SECS MASQUE_SNI " +
        "MASQUE_STARTUP_SECS MASQUE_VALIDATE_SECS MIM_INNER_PEER MIM_OUTER_PEER MIM_PEERS NOIZE PEER PERF_PROFILE " +
        "PROTOCOL PSIPHON PSIPHON_BIN PSIPHON_BIND PSIPHON_CDN_IPS PSIPHON_CDN_SETS PSIPHON_CDN_SNI PSIPHON_CONFIG " +
        "PSIPHON_DIR PSIPHON_HTTP PSIPHON_MODE PSIPHON_REGION PSIPHON_SERVER_ENTRIES QUICK_RECONNECT QUIC_V2 REGISTER " +
        "ROUTES_FILE ROUTE_BLOCK ROUTE_DIRECT SCAN SOCKS STATS STATS_SECS TEAM TLS13_CIPHERS TLS_ALPS " +
        "TLS_CERT_COMPRESSION TLS_CIPHERS TLS_GROUPS TLS_KEY_SHARES TLS_SERVER_PADDING TLS_SIGALGS TLS_TRUST_ANCHORS " +
        "TLS_VERIFY TOR TOR_BIND TOR_BRIDGES TOR_BRIDGE_FILE TOR_DIR TOR_HTTP TOR_RELAYS TOR_RELAY_PORTS UPSTREAM " +
        "WG_CONFIG WG_KEEPALIVE WG_NO_DATA_CHECK WG_NO_PROFILE_RETRY WG_PEER WG_RECONNECT_SECS WG_VALIDATE_SECS " +
        "WIW_INNER_PEER WIW_OUTER_PEER WIW_PEERS"
        ).split(' ')

    suspend fun startCore(ctx: Context, options: Options): Long = withContext(Dispatchers.IO) {
        if (!Native.ensure()) error("هسته بارگذاری نشد: ${Native.loadError}")
        stopCore()
        clearCoreEnv()
        tunnelReady = false
        val args = options.toArgs(identityPath(ctx))
        note("core ${args.joinToString(" ")}")
        val reply = JSONObject(Native.coreStart(JSONArray(args).toString()))
        if (!reply.optBoolean("ok")) error(reply.optString("error", "هسته شروع نشد"))
        val id = reply.optLong("job")
        if (id <= 0) error("هسته شناسهٔ کار نداد")
        jobId = id
        id
    }

    data class Report(val running: Boolean, val error: String?)

    /** One look at the core job: still running, or ended (with its error, if any). */
    fun poll(): Report {
        val id = jobId
        if (id <= 0) return Report(false, null)
        val raw = runCatching { Native.jobPoll(id) }.getOrNull() ?: return Report(true, null)
        return try {
            val j = JSONObject(raw)
            if (!j.optBoolean("ok")) return Report(false, j.optString("error"))
            if (j.optString("state") == "running") return Report(true, null)
            val result = j.optJSONObject("result")
            val err = if (result != null && !result.optBoolean("ok", true)) result.optString("error") else null
            Report(false, err)
        } catch (_: Exception) {
            Report(true, null)
        }
    }

    private val stopLock = kotlinx.coroutines.sync.Mutex()

    /** Cancels the core job and waits for it to let go of its port. */
    suspend fun stopCore() = stopLock.withLock {
        val id = jobId
        if (id <= 0) return@withLock
        jobId = 0
        runCatching { Native.jobCancel(id) }
        val deadline = SystemClock.elapsedRealtime() + 4000
        while (SystemClock.elapsedRealtime() < deadline) {
            val raw = runCatching { Native.jobPoll(id) }.getOrNull() ?: break
            if (!raw.contains("\"running\"")) break
            delay(100)
        }
        runCatching { Native.jobFree(id) }
    }

    /** Waits until the core's SOCKS5 port accepts a connection, or the core ends. */
    suspend fun awaitSocks(port: Int, timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            pumpLog()
            val r = poll()
            if (!r.running) error(r.error?.takeIf { it.isNotBlank() } ?: "هسته پیش از آماده شدن بسته شد")
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500); return@withContext true }
            } catch (_: Exception) {
                delay(400)
            }
        }
        false
    }

    /** The probe's answer, or why there is none (the core's own error when it ended). */
    data class Check(val egress: Probe.Egress?, val error: String?)

    /**
     * Sends a real request through the tunnel once the core says it is up, and keeps trying until
     * one comes back or [timeoutMs] passes. Probing before that only blocks on a tunnel that does
     * not exist yet, and a blocked probe was what kept a ready tunnel waiting; so before the core's
     * signal it only tries every 5 s (in case the log wording changes), after it every 0.4 s.
     */
    suspend fun awaitInternet(port: Int, timeoutMs: Long, onTry: (Int) -> Unit): Check =
        withContext(Dispatchers.IO) {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            var n = 0
            var lastTry = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() < deadline) {
                pumpLog()
                val r = poll()
                if (!r.running) return@withContext Check(null, r.error?.takeIf { it.isNotBlank() } ?: "هسته بسته شد")
                val now = SystemClock.elapsedRealtime()
                if (tunnelReady || now - lastTry >= 5000) {
                    n++
                    onTry(n)
                    lastTry = now
                    Probe.trace(port, if (tunnelReady) 4000 else 2500)?.let { return@withContext Check(it, null) }
                    if (tunnelReady) delay(400)
                } else {
                    delay(200)
                }
            }
            Check(null, null)
        }

    // ------------------------------------------------------------ tun2socks

    private val hevLock = Any()
    @Volatile private var hevThread: Thread? = null
    @Volatile private var hevInFlight = false
    @Volatile private var hevStopAsked = false

    private fun hevConfig(ctx: Context, options: Options): String {
        val f = File(File(ctx.filesDir, "hev").apply { mkdirs() }, "tunnel.yml")
        f.writeText(
            """
            tunnel:
              mtu: ${options.mtu}
            socks5:
              port: ${options.port}
              address: 127.0.0.1
              udp: 'udp'
            misc:
              connect-timeout: 8000
              read-write-timeout: 120000
              log-file: stderr
              log-level: warn
            """.trimIndent() + "\n"
        )
        return f.absolutePath
    }

    /**
     * Runs hev on [fd] in its own thread. hev keeps a stop flag that a stop() outside a run
     * leaves set, which makes the next run return 0 at once; so stop() is only called while a run
     * is in flight, and a run that returns 0 without being asked to is started once more.
     */
    fun startHev(ctx: Context, options: Options, fd: Int, onExit: (Int) -> Unit) {
        val config = hevConfig(ctx, options)
        synchronized(hevLock) {
            hevStopAsked = false
            val t = Thread({
                var rc = -1
                for (attempt in 0..1) {
                    if (hevStopAsked) break
                    hevInFlight = true
                    rc = runCatching { Native.hevRun(config, fd) }.getOrDefault(-1)
                    hevInFlight = false
                    if (rc != 0 || hevStopAsked) break
                    Thread.sleep(120)
                }
                if (!hevStopAsked) onExit(rc)
            }, "kwarp-hev")
            t.isDaemon = true
            hevThread = t
            t.start()
        }
    }

    suspend fun awaitHev(timeoutMs: Long = 2000): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val t = hevThread ?: return false
            if (t.isAlive && hevInFlight) return true
            if (!t.isAlive) return false
            delay(50)
        }
        return hevInFlight
    }

    /** Stops hev and waits for its thread, so the TUN descriptor can be closed after it. */
    fun stopHev() {
        val t = synchronized(hevLock) {
            hevStopAsked = true
            hevThread.also { hevThread = null }
        } ?: return
        var waited = 0
        while (waited < 400 && t.isAlive && !hevInFlight) {
            Thread.sleep(10); waited += 10
        }
        if (hevInFlight) runCatching { Native.hevStop() }
        runCatching { t.join(2500) }
    }

    // ---------------------------------------------------------------- stats

    private var lastUp = 0L
    private var lastDown = 0L

    fun pushStats() {
        val s = runCatching { Native.hevStats() }.getOrNull() ?: return
        if (s.size < 4) return
        val up = s[1]
        val down = s[3]
        _traffic.value = Traffic(up, down, (up - lastUp).coerceAtLeast(0), (down - lastDown).coerceAtLeast(0))
        lastUp = up
        lastDown = down
    }

    fun resetStats() {
        lastUp = 0; lastDown = 0
        _traffic.value = Traffic()
    }
}
