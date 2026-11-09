package cafe.kankipro.warp

import android.Manifest
import android.net.VpnService
import android.os.Build
import cafe.kankipro.warp.ui.Tab
import cafe.kankipro.warp.core.ZeroTrust
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import cafe.kankipro.warp.core.Engine
import cafe.kankipro.warp.core.GameEngine
import cafe.kankipro.warp.core.IpTest
import cafe.kankipro.warp.core.Transport
import cafe.kankipro.warp.core.Phase
import cafe.kankipro.warp.core.Prefs
import cafe.kankipro.warp.service.WarpService
import cafe.kankipro.warp.ui.App
import cafe.kankipro.warp.ui.K
import cafe.kankipro.warp.ui.KankiTheme

class MainActivity : ComponentActivity() {
    /** What to do once Android grants the VPN: connect, or run the gamer scan (it connects for a moment). */
    private var afterVpn: () -> Unit = { start() }
    private val askVpn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (VpnService.prepare(this) == null) afterVpn()
    }
    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        var options by mutableStateOf(Prefs.options(this))
        // opened from Cloudflare's Zero Trust enrolment link: join, in gamer mode, on the settings tab
        val fromLink = zeroTrustLink(intent)
        if (fromLink && !options.gamer) { options = options.copy(gamer = true); Prefs.save(this, options) }
        // every protocol's WARP account now, not when connecting
        lifecycleScope.launch { Engine.prepareAll(applicationContext) }
        setContent {
            KankiTheme {
                Box(Modifier.fillMaxSize().background(K.night).safeDrawingPadding()) {
                    App(
                        options = options,
                        onOptions = { options = it; Prefs.save(this@MainActivity, it) },
                        onToggle = { toggle() },
                        initialTab = if (fromLink) Tab.SETTINGS else Tab.HOME,
                        onGameScan = { fast -> withVpn { GameEngine.startScan(applicationContext, Prefs.options(this@MainActivity).gameTransport, fast) } },
                        // gaming learns cities with a short connection: the VPN must be allowed first
                        onIpScan = { kind ->
                            val disguise = Prefs.options(this@MainActivity).gameTransport == Transport.AMNEZIA
                            if (kind == IpTest.Kind.GAMING) withVpn { IpTest.start(applicationContext, kind, disguise) }
                            else IpTest.start(applicationContext, kind, disguise)
                        },
                        // a gateway picked on the IP test screen: gamer mode, that gateway first, connect
                        onGameConnect = { hit ->
                            if (!options.gamer) { options = options.copy(gamer = true); Prefs.save(this@MainActivity, options) }
                            GameEngine.pick(applicationContext, hit)
                            withVpn { GameEngine.connect(applicationContext, options.gameTransport, hit.endpoint) }
                        },
                        onForget = {
                            lifecycleScope.launch { Engine.remakeAccount(applicationContext, options.transport) }
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        zeroTrustLink(intent)
    }

    /** True when [intent] is the com.cloudflare.warp:// link of a Zero Trust enrolment; joins with it. */
    private fun zeroTrustLink(intent: Intent?): Boolean {
        val link = intent?.data?.takeIf { it.scheme == "com.cloudflare.warp" } ?: return false
        ZeroTrust.join(applicationContext, link.toString())
        return true
    }

    private fun toggle() {
        val s = Engine.state.value
        when (s.phase) {
            Phase.RUNNING, Phase.CONNECTING -> if (s.gamer) GameEngine.disconnect(this) else WarpService.stop(this)
            Phase.STOPPING -> Unit
            else -> {
                withVpn { start() }
            }
        }
    }

    private fun withVpn(then: () -> Unit) {
        val ask = VpnService.prepare(this)
        if (ask != null) { afterVpn = then; askVpn.launch(ask) } else then()
    }

    /** The mode picked on the home screen decides the engine: the core (normal) or straight WireGuard (gamer). */
    private fun start() {
        val o = Prefs.options(this)
        if (o.gamer) GameEngine.connect(applicationContext, o.gameTransport, null) else WarpService.start(this)
    }
}
