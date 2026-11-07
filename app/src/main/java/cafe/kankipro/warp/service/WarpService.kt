package cafe.kankipro.warp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import cafe.kankipro.warp.MainActivity
import cafe.kankipro.warp.R
import cafe.kankipro.warp.core.Engine
import cafe.kankipro.warp.core.Options
import cafe.kankipro.warp.core.Phase
import cafe.kankipro.warp.core.Prefs
import cafe.kankipro.warp.core.Probe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * The whole connection, 0 to 100:
 *  1. a TUN for every app except this one (the core's own traffic must not loop into it)
 *  2. for each transport of the chain: start the core (it registers a WARP account the first
 *     time, finds a Cloudflare gateway, opens MASQUE/WireGuard) and wait for its SOCKS5 port
 *  3. hev moves the TUN's packets into that SOCKS5 port
 *  4. an HTTPS request through the tunnel must come back; only then «متصل»
 *  5. while running: traffic every second, core health every 2 s, ping every 30 s
 */
class WarpService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = AtomicInteger(0)
    @Volatile private var tun: ParcelFileDescriptor? = null
    private var worker: Job? = null
    private var watch: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> shutdown(null)
            ACTION_START -> {
                foreground("در حال اتصال…")
                if (worker?.isActive != true) {
                    val options = Prefs.options(this)
                    worker = scope.launch { bringUp(options) }
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun bringUp(options: Options) {
        val mine = session.incrementAndGet()
        fun alive() = session.get() == mine
        Engine.resetStats()
        Engine.update { it.copy(phase = Phase.CONNECTING, proven = 0, error = null, egress = null,
            gateway = "", message = "آماده کردن تونل گوشی…", gamer = false) }
        try {
            val fd = openTun(options)
            val chain = Options.chain(options, Prefs.lastGood(this))
            Engine.note("chain: " + chain.joinToString(" → ") { it.transport.title })
            var hevStarted = false
            var lastError: String? = null
            for ((i, c) in chain.withIndex()) {
                if (!alive()) return
                Engine.update { it.copy(transport = c.transport, attempt = i + 1, attempts = chain.size,
                    proven = 0, gateway = "", message = "روشن کردن هسته با ${c.transport.title}…") }
                Engine.note("try ${i + 1}/${chain.size}: ${c.transport.title}")

                // The account first, on its own and with all the time it needs (once per transport family).
                if (!Engine.hasIdentity(this, c.transport)) {
                    Engine.update { it.copy(message = "ساخت اکانت رایگان WARP در کلادفلر… (فقط بار اول، تا چند دقیقه)") }
                    val err = Engine.register(this, c.transport)
                    if (!alive()) return
                    if (err != null) {
                        Engine.note("${c.transport.title}: account: $err")
                        lastError = "اکانت WARP ساخته نشد: $err"
                        continue
                    }
                    Engine.note("${c.transport.title}: account ready")
                }

                val listening = try {
                    Engine.startCore(this, c)
                    Engine.awaitSocks(c.port, CORE_LISTEN_MS)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Engine.note("${c.transport.title}: ${e.message}")
                    lastError = e.message
                    false
                }
                if (!listening) continue
                if (!alive()) return
                Engine.update { it.copy(proven = 1, message = "وصل کردن برنامه‌ها به تونل…") }

                if (!hevStarted) {
                    Engine.startHev(this, c, fd.fd) { rc ->
                        Engine.note("tun2socks ended rc=$rc")
                        if (alive() && Engine.state.value.phase == Phase.RUNNING) {
                            shutdown("موتور تونل گوشی متوقف شد")
                        }
                    }
                    if (!Engine.awaitHev()) error("موتور تونل گوشی روشن نشد")
                    hevStarted = true
                }
                // The gateway may already be known (a remembered one answers at once).
                Engine.update { it.copy(proven = if (it.gateway.isNotBlank()) 3 else 2,
                    message = if (it.gateway.isNotBlank()) "فرستادن یک درخواست واقعی از داخل تونل…" else "پیدا کردن دروازه‌ی کلادفلر…") }

                val budget = if (i == 0) FIRST_PROBE_MS else NEXT_PROBE_MS
                val check = Engine.awaitInternet(c.port, budget) { n ->
                    if (n > 1) Engine.update { s -> s.copy(message = "آزمایش تونل، بار $n…") }
                }
                if (!alive()) return
                val egress = check.egress
                if (egress != null) {
                    Prefs.rememberGood(this, c.transport)
                    Engine.note("verified via ${c.transport.title}: ${egress.ip} ${egress.colo} warp=${egress.warp}")
                    Engine.update { it.copy(phase = Phase.RUNNING, proven = 4, message = "",
                        egress = egress, startedAt = SystemClock.elapsedRealtime()) }
                    foreground("وصل · ${c.transport.title} · ${egress.colo}")
                    startWatch(mine, c)
                    return
                }
                lastError = check.error ?: "از داخل تونل جوابی نیامد"
                Engine.note("${c.transport.title}: $lastError")
            }
            error(lastError?.let { "وصل نشد: $it" } ?: "هیچ‌کدام از پروتکل‌ها جواب نداد. گزارش را ببین یا پروتکل دیگری امتحان کن.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (alive()) shutdown(e.message ?: "خطای ناشناخته")
        }
    }

    private fun openTun(o: Options): ParcelFileDescriptor {
        runCatching { tun?.close() }
        val b = Builder()
            .setSession("Kanki Warp")
            .setMtu(o.mtu)
            .addAddress("172.19.0.1", 30)
            .addRoute("0.0.0.0", 0)
            .addAddress("fdfe:dcba:9876::1", 126)
            .addRoute("::", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("1.0.0.1")
        // The core's own sockets go to Cloudflare directly, never back into the TUN.
        runCatching { b.addDisallowedApplication(packageName) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) b.setMetered(false)
        val fd = b.establish() ?: error("اجازهٔ VPN داده نشده")
        tun = fd
        return fd
    }

    private fun startWatch(mine: Int, o: Options) {
        watch?.cancel()
        watch = scope.launch {
            var tick = 0
            while (isActive && session.get() == mine) {
                Engine.pushStats()
                Engine.pumpLog()
                if (tick % 2 == 0) {
                    val r = Engine.poll()
                    if (!r.running) {
                        shutdown(r.error?.takeIf { it.isNotBlank() } ?: "هسته متوقف شد")
                        return@launch
                    }
                }
                if (tick > 0 && tick % 30 == 0) {
                    Probe.trace(o.port, 5000)?.let { e -> Engine.update { it.copy(egress = e) } }
                }
                tick++
                delay(1000)
            }
        }
    }

    /**
     * Takes everything down; with [error] the screen keeps the reason. The phone's traffic is
     * released first (tun2socks, then the TUN) and the screen says «وصل نیستی» right then; the
     * core winds down after that in the background (a new connect waits for it in startCore).
     */
    fun shutdown(error: String?) {
        session.incrementAndGet()
        Engine.update { it.copy(phase = if (error != null) Phase.ERROR else Phase.STOPPING, proven = 0,
            error = error, message = "") }
        error?.let { Engine.note("error: $it") }
        watch?.cancel()
        val w = worker
        worker = null
        scope.launch {
            w?.cancel()
            Engine.stopHev()
            runCatching { tun?.close() }
            tun = null
            if (error == null) Engine.update { cafe.kankipro.warp.core.ConnState() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            Engine.stopCore()
            Engine.pumpLog()
            stopSelf()
        }
    }

    override fun onRevoke() {
        shutdown(null)
        super.onRevoke()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun foreground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "اتصال", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, WarpService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Kanki Warp")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "قطع", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    companion object {
        private const val CHANNEL = "conn"
        private const val CORE_LISTEN_MS = 15_000L
        // The account is made before these start; they cover the gateway search and the tunnel.
        private const val FIRST_PROBE_MS = 60_000L
        private const val NEXT_PROBE_MS = 35_000L
        const val ACTION_START = "cafe.kankipro.warp.START"
        const val ACTION_STOP = "cafe.kankipro.warp.STOP"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, WarpService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, WarpService::class.java).setAction(ACTION_STOP))
        }
    }
}
