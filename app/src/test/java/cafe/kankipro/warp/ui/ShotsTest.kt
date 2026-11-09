package cafe.kankipro.warp.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cafe.kankipro.warp.core.ConnState
import cafe.kankipro.warp.core.Engine
import cafe.kankipro.warp.core.Options
import cafe.kankipro.warp.core.Phase
import cafe.kankipro.warp.core.Probe
import cafe.kankipro.warp.core.Transport
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Pictures of every screen with sample data, into app/build/shots (looks only, no logic). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class ShotsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getProperty("user.dir") ?: ".", "build/shots").apply { mkdirs() }

    private fun shot(name: String, state: ConnState, tab: Tab = Tab.HOME, options: Options = Options(), act: () -> Unit = {}) {
        Engine.update { state }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            KankiTheme {
                Box(Modifier.fillMaxSize().background(K.night)) {
                    App(options, {}, {}, {}, initialTab = tab)
                }
            }
        }
        rule.mainClock.advanceTimeBy(900)
        rule.waitForIdle()
        act()
        rule.mainClock.advanceTimeBy(900)
        rule.waitForIdle()
        val bmp = runCatching { rule.onRoot().captureToImage().asAndroidBitmap() }.getOrElse {
            val v = rule.activity.window.decorView.rootView
            Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { b -> v.draw(android.graphics.Canvas(b)) }
        }
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun idle() = shot("1-idle", ConnState())

    @Test fun connecting() = shot(
        "2-connecting",
        ConnState(phase = Phase.CONNECTING, proven = 2, transport = Transport.MASQUE_H2, attempt = 1, attempts = 3,
            message = "دروازه پیدا شد: 162.159.198.2:443"),
    )

    @Test fun running() = shot(
        "3-running",
        ConnState(phase = Phase.RUNNING, proven = 4, transport = Transport.MASQUE_H2,
            egress = Probe.Egress("104.28.212.71", "FRA", "DE", "on", 86), gateway = "162.159.198.2:443",
            startedAt = 0),
    )

    @Test fun gamerRunning() = shot(
        "3b-gamer-running",
        ConnState(phase = Phase.RUNNING, proven = 4, transport = Transport.AMNEZIA, gamer = true,
            egress = Probe.Egress("104.28.212.71", "FRA", "TR", "on", 74), gateway = "188.114.97.3:4500", startedAt = 0,
            actual = "Amnezia · پوشش کامل"),
        options = Options(gamer = true),
    )

    @Test fun pinned() = shot(
        "2b-pinned",
        ConnState(),
        options = Options(transport = Transport.WIREGUARD, pins = mapOf(Transport.WIREGUARD to "188.114.99.172:5956"),
            pinPing = mapOf(Transport.WIREGUARD to 111)),
    )

    @Test fun failed() = shot(
        "4-error",
        ConnState(phase = Phase.ERROR, proven = 1, error = "هیچ‌کدام از پروتکل‌ها جواب نداد. گزارش را ببین یا پروتکل دیگری امتحان کن."),
    )

    @Test fun gamer() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val net = cafe.kankipro.warp.core.GameEngine.networkKey(ctx)
        cafe.kankipro.warp.core.Prefs.saveGameHits(ctx, net, listOf(
            cafe.kankipro.warp.core.WgScan.Hit("188.114.97.3:4500", 74, 3, 0, "FRA"),
            cafe.kankipro.warp.core.WgScan.Hit("162.159.192.7:2408", 61, 6, 0, "IST"),
            cafe.kankipro.warp.core.WgScan.Hit("162.159.195.40:1701", 95, 9, 0, "DUS"),
            cafe.kankipro.warp.core.WgScan.Hit("188.114.96.9:1002", 88, 12, 0, "IST"),
            cafe.kankipro.warp.core.WgScan.Hit("188.114.99.4:8854", 78, 4, 0, "MXP"),
            cafe.kankipro.warp.core.WgScan.Hit("162.159.198.2:500", 92, 7, 0, "VIE"),
            cafe.kankipro.warp.core.WgScan.Hit("162.159.199.9:2408", 118, 15, 0, "DXB"),
        ))
        shot("7-gamer", ConnState(), options = Options(gamer = true))
    }

    private fun ipSample() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val net = cafe.kankipro.warp.core.GameEngine.networkKey(ctx)
        val g = cafe.kankipro.warp.core.IpTest.Kind.GAMING
        val h2 = cafe.kankipro.warp.core.IpTest.Kind.H2
        val h3 = cafe.kankipro.warp.core.IpTest.Kind.H3
        fun f(ep: String, k: cafe.kankipro.warp.core.IpTest.Kind, ping: Int, j: Int, loss: Int, colo: String, proto: Boolean = true) =
            cafe.kankipro.warp.core.IpTest.Found(ep, k, proto, !proto, ping, j, loss, colo, net, 1)
        cafe.kankipro.warp.core.IpTest.save(ctx, listOf(
            f("162.159.193.10:2408", g, 74, 3, 0, "IST"), f("188.114.97.3:4500", g, 81, 4, 0, "FRA"),
            f("162.159.192.7:1002", g, 66, 9, 16, "IST"), f("188.114.99.4:8854", g, 92, 5, 0, "MXP"),
            f("162.159.195.40:1701", g, 140, 12, 0, "DUS"), f("162.159.198.2:500", g, 176, 7, 0, "VIE"),
            f("188.114.98.244:1010", g, 338, 54, 0, "MXP"), f("188.114.96.86:7559", g, 912, 49, -1, "MXP"),
            f("162.159.204.9:443", g, 0, 0, 0, "", proto = false),
            f("162.159.198.12:443", h2, 96, 6, 0, "IST"), f("162.159.199.77:443", h2, 131, 14, 25, ""),
            f("162.159.198.40:4443", h3, 88, 4, 0, "IST"),
        ))
    }

    @Test fun ipTest() { ipSample(); shot("8-iptest", ConnState(), Tab.IPTEST) }

    @Test fun ipTestAll() {
        ipSample()
        shot("8b-iptest-all", ConnState(), Tab.IPTEST) {
            rule.onNodeWithText("همه‌ی دروازه‌های کارکن").performClick()
            rule.onNodeWithText("فهرست طلایی").performClick()
        }
    }

    @Test fun ipTestNormal() {
        ipSample()
        shot("8c-iptest-normal", ConnState(), Tab.IPTEST) {
            rule.onNodeWithText("کاربر عادی").performClick()
            rule.mainClock.advanceTimeBy(500)
            rule.onAllNodesWithText("MASQUE · HTTP/2")[1].performClick()
        }
    }

    @Test fun settings() = shot("5-settings", ConnState(), Tab.SETTINGS)

    @Test fun settingsAntiFilter() = shot("5c-settings-filter", ConnState(), Tab.SETTINGS, Options(fragment = true, frag = cafe.kankipro.warp.core.Frag.TINY)) {
        rule.onRoot().performTouchInput { swipeUp(startY = bottom * 0.9f, endY = top, durationMillis = 300) }
    }

    @Test fun gamerSettings() = shot("5b-gamer-settings", ConnState(), Tab.SETTINGS, Options(gamer = true))

    @Test fun log() {
        Engine.clearLog()
        listOf("try 1/3: MASQUE · HTTP/2", "17:20:01 [+] loaded existing masque identity",
            "17:20:03 [+] using cloudflare edge 162.159.198.2:443", "verified via MASQUE · HTTP/2: 104.28.212.71 FRA warp=on")
            .forEach { Engine.note(it) }
        shot("6-log", ConnState(), Tab.LOG)
    }
}
