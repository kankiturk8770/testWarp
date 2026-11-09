package cafe.kankipro.warp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.kankipro.warp.core.Colos
import cafe.kankipro.warp.core.Engine
import cafe.kankipro.warp.core.IpTest
import cafe.kankipro.warp.core.Options
import cafe.kankipro.warp.core.Phase
import cafe.kankipro.warp.core.Transport
import cafe.kankipro.warp.core.WgScan

/**
 * «تست آی‌پی»: two separate scans (gaming on WireGuard's ranges, normal on MASQUE's), the golden
 * gaming list, every gaming gateway country by country (tap one: the gamer mode connects with it),
 * and the white IPs of 443 for the normal user (tap one: it becomes MASQUE · HTTP/2's fixed IP).
 */
@Composable
internal fun IpTestScreen(
    options: Options,
    onOptions: (Options) -> Unit,
    onScan: (IpTest.Kind) -> Unit,
    onConnect: (WgScan.Hit) -> Unit,
) {
    val ctx = LocalContext.current
    val st by IpTest.state.collectAsState()
    val conn by Engine.state.collectAsState()
    val free = conn.phase == Phase.IDLE || conn.phase == Phase.ERROR
    LaunchedEffect(Unit) { IpTest.load(ctx) }
    var showAll by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("تست آی‌پی", style = T.title, color = K.champagne, modifier = Modifier.weight(1f))
            Badge("بایگانی ${fa(st.archived)}", K.rose)
        }
        Text(
            if (free) "شبکه: " + st.network.removePrefix("cell:").replace("wifi", "وای‌فای") else "برای اسکن اول قطع کن",
            style = T.small, color = if (free) K.rose else K.warn,
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScanButton(Modifier.weight(1f), "اسکن گیمینگ", "بازه‌های WireGuard", Ic.gamepad, K.amber,
                st.running && st.kind == IpTest.Kind.GAMING, free && !st.running) { onScan(IpTest.Kind.GAMING) }
            ScanButton(Modifier.weight(1f), "اسکن کاربر عادی", "بازه‌های MASQUE", Ic.shield, K.gold,
                st.running && st.kind == IpTest.Kind.NORMAL, free && !st.running) { onScan(IpTest.Kind.NORMAL) }
        }
        if (st.running) {
            Spacer(Modifier.height(10.dp))
            Progress(st)
        } else st.result?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = T.small, color = K.champagne)
        }

        // gaming: golden list, then every country
        val golden = IpTest.golden(st.gaming)
        val countries = IpTest.byCountry(st.gaming)
        Spacer(Modifier.height(16.dp))
        Header("فهرست طلایی گیمینگ", "افت صفر یا کم، بعد پینگ کم", K.amber)
        if (golden.isEmpty()) Empty("هنوز خالی؛ «اسکن گیمینگ» را بزن")
        golden.take(if (showAll) golden.size else 6).forEach {
            FoundRow(it, K.amber, free && !st.running) { onConnect(it.hit()) }
        }
        if (golden.size > 6) {
            Spacer(Modifier.height(4.dp))
            SmallButton(if (showAll) "کمتر" else "همه‌ی ${fa(golden.size)} تا", K.rose, true) { showAll = !showAll }
        }
        if (countries.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Header("کشور به کشور", "همه، تا ${fa(IpTest.MAX_GAME_PING)} ms؛ بزن تا گیمر با آن وصل شود", K.amber)
            countries.forEach { (cc, list) ->
                Spacer(Modifier.height(8.dp))
                Text(listOfNotNull(flag(cc), Colos.countryName(cc), "(${fa(list.size)})").joinToString("  "),
                    style = T.strong.copy(fontSize = 14.sp), color = K.champagne)
                list.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { f ->
                            Box(Modifier.weight(1f)) { FoundRow(f, K.amber, free && !st.running) { onConnect(f.hit()) } }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        // normal: white IPs on 443
        Spacer(Modifier.height(18.dp))
        Header("آی‌پی سفید کاربر عادی", "کمک برای MASQUE · HTTP/2؛ بزن تا آی‌پی ثابت شود", K.gold)
        val pinned = options.pins[Transport.MASQUE_H2]
        if (st.normal.isEmpty()) Empty("هنوز خالی؛ «اسکن کاربر عادی» را بزن")
        st.normal.take(24).forEach { f ->
            FoundRow(f, K.gold, free && !st.running && f.proto, chosen = f.endpoint == pinned) {
                onOptions(options.copy(pins = options.pins + (Transport.MASQUE_H2 to f.endpoint),
                    pinPing = options.pinPing + (Transport.MASQUE_H2 to f.pingMs)))
            }
        }
        if (st.archived > 0 && !st.running) {
            Spacer(Modifier.height(16.dp))
            SmallButton("پاک کردن بایگانی", K.ember, true) { IpTest.clear(ctx) }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun ScanButton(
    modifier: Modifier, title: String, sub: String, icon: ImageVector, color: Color, busy: Boolean, enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier.heightIn(min = 84.dp).clip(RoundedCornerShape(16.dp)).background(if (busy) K.wine else Color.Transparent)
            .border(1.dp, if (enabled || busy) color else K.wineLine, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled || busy) { if (busy) IpTest.cancel() else onClick() }
            .padding(12.dp),
    ) {
        Icon(icon, null, tint = if (enabled || busy) color else K.wineLine, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(6.dp))
        Text(if (busy) "توقف" else title, style = T.strong.copy(fontSize = 14.sp), color = if (enabled || busy) K.champagne else K.rose)
        Text(sub, style = T.small.copy(fontSize = 11.sp), color = K.rose)
    }
}

@Composable
private fun Progress(st: IpTest.State) {
    val accent = if (st.kind == IpTest.Kind.GAMING) K.amber else K.gold
    val stages = if (st.kind == IpTest.Kind.GAMING) 3 else 2
    val name = when (st.stage) {
        1 -> "مرحله‌ی ۱ از ${fa(stages)}: زنده بودن · ${percent(st.done, st.total)} · ${fa(st.found)} جواب"
        2 -> "مرحله‌ی ۲ از ${fa(stages)}: پینگ دقیق و افت · دور ${fa(st.done + 1)} از ${fa(st.total)}"
        else -> "مرحله‌ی ۳ از ۳: تشخیص شهر · ${fa(st.done)} از ${fa(st.total)}"
    }
    Text(name, style = T.small, color = K.champagne)
    Spacer(Modifier.height(6.dp))
    val frac = if (st.total > 0) st.done.toFloat() / st.total else 0f
    val all = ((st.stage - 1).coerceAtLeast(0) + frac) / stages
    Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(K.wineLine)) {
        Box(Modifier.fillMaxWidth(all.coerceIn(0f, 1f)).fillMaxHeight().background(accent))
    }
}

@Composable
private fun Header(title: String, sub: String, color: Color) {
    Text(title, style = T.strong, color = color)
    Text(sub, style = T.small.copy(fontSize = 11.sp), color = K.rose)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Empty(text: String) = Text(text, style = T.small.copy(fontSize = 12.sp), color = K.rose)

/** One address: the city (or «فقط زنده»), the address, ping, jitter and loss. */
@Composable
private fun FoundRow(f: IpTest.Found, accent: Color, enabled: Boolean, chosen: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp))
            .background(if (chosen) K.wine else Color.Transparent)
            .border(1.dp, if (chosen) accent else K.wineLine, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val where = when {
                !f.proto -> "فقط زنده (TCP)"
                f.colo.isNotBlank() -> listOfNotNull(flag(f.country), Colos.NAMES[f.colo] ?: f.colo).joinToString(" ")
                else -> "—"
            }
            Text(where, style = T.small, color = if (chosen) accent else K.champagne, modifier = Modifier.weight(1f), maxLines = 1)
            if (f.proto && f.pingMs > 0) Text("${f.pingMs} ms", style = T.strong.copy(textDirection = TextDirection.Ltr, fontSize = 14.sp),
                color = pingColor(f.pingMs))
        }
        Text(f.endpoint, style = T.small.copy(textDirection = TextDirection.Ltr, fontSize = 11.sp), color = K.rose, maxLines = 1)
        if (f.proto) Text("لرزش ${fa(f.jitterMs)} · افت ${fa(f.lossPct)}٪", style = T.small.copy(fontSize = 10.sp),
            color = if (f.lossPct == 0) K.ok else K.rose)
    }
}
