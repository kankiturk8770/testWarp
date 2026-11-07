package cafe.kankipro.warp

import android.Manifest
import android.net.VpnService
import android.os.Build
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
import cafe.kankipro.warp.core.Engine
import cafe.kankipro.warp.core.Phase
import cafe.kankipro.warp.core.Prefs
import cafe.kankipro.warp.service.WarpService
import cafe.kankipro.warp.ui.App
import cafe.kankipro.warp.ui.K
import cafe.kankipro.warp.ui.KankiTheme

class MainActivity : ComponentActivity() {
    private val askVpn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (VpnService.prepare(this) == null) WarpService.start(this)
    }
    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        var options by mutableStateOf(Prefs.options(this))
        setContent {
            KankiTheme {
                Box(Modifier.fillMaxSize().background(K.night).safeDrawingPadding()) {
                    App(
                        options = options,
                        onOptions = { options = it; Prefs.save(this@MainActivity, it) },
                        onToggle = { toggle() },
                        onForget = { Engine.forgetIdentity(this@MainActivity); Engine.note("WARP identity deleted") },
                    )
                }
            }
        }
    }

    private fun toggle() {
        when (Engine.state.value.phase) {
            Phase.RUNNING, Phase.CONNECTING -> WarpService.stop(this)
            Phase.STOPPING -> Unit
            else -> {
                val ask = VpnService.prepare(this)
                if (ask != null) askVpn.launch(ask) else WarpService.start(this)
            }
        }
    }
}
