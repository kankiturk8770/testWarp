package cafe.kankipro.warp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.kankipro.warp.core.Colos
import cafe.kankipro.warp.core.ConnState
import cafe.kankipro.warp.core.Dns
import cafe.kankipro.warp.core.Engine
import cafe.kankipro.warp.core.GameEngine
import cafe.kankipro.warp.core.IpFamily
import cafe.kankipro.warp.core.Options
import cafe.kankipro.warp.core.Phase
import cafe.kankipro.warp.core.PinScan
import cafe.kankipro.warp.core.Scan
import cafe.kankipro.warp.core.Stage
import cafe.kankipro.warp.core.Transport
import cafe.kankipro.warp.core.WgScan
import kotlinx.coroutines.delay

enum class Tab(val title: String) { HOME("اتصال"), SETTINGS("تنظیمات"), LOG("گزارش") }

@Composable
fun App(
    options: Options,
    onOptions: (Options) -> Unit,
    onToggle: () -> Unit,
    onForget: () -> Unit,
    initialTab: Tab = Tab.HOME,
    onGameScan: (Boolean) -> Unit = {},
) {
    var tab by remember { mutableStateOf(initialTab) }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(Modifier.fillMaxSize().background(K.night)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    Tab.HOME -> Home(options, onOptions, onToggle, onGameScan)
                    Tab.SETTINGS -> Settings(options, onOptions, onForget)
                    Tab.LOG -> LogScreen(options)
                }
            }
            NavBar(tab, options.gamer) { tab = it }
        }
    }
}

@Composable
private fun NavBar(tab: Tab, gamer: Boolean, onTab: (Tab) -> Unit) {
    val accent = if (gamer) K.amber else K.gold
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(K.wineLine))
        Row(
            Modifier.fillMaxWidth().background(K.night).padding(horizontal = 24.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            Tab.entries.forEach { t ->
                val on = t == tab
                Column(
                    Modifier.clip(RoundedCornerShape(10.dp)).clickable { onTab(t) }
                        .heightIn(min = 40.dp).padding(horizontal = 18.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(t.title, style = T.small.copy(fontSize = 13.sp), color = if (on) accent else K.rose)
                    Spacer(Modifier.height(3.dp))
                    Box(Modifier.size(width = 14.dp, height = 2.dp).clip(CircleShape).background(if (on) accent else Color.Transparent))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- home

@Composable
private fun Home(options: Options, onOptions: (Options) -> Unit, onToggle: () -> Unit, onGameScan: (Boolean) -> Unit) {
    val s by Engine.state.collectAsState()
    var showAll by remember { mutableStateOf(false) }
    val running = s.phase == Phase.RUNNING
    val free = s.phase == Phase.IDLE || s.phase == Phase.ERROR
    val gamer = if (running || s.phase == Phase.CONNECTING) s.gamer else options.gamer
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // top bar: wordmark, and Iran mode for the normal user
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    "KANKI WARP",
                    style = T.title.copy(fontSize = 15.sp, letterSpacing = 3.sp, textDirection = TextDirection.Ltr),
                    color = K.champagne,
                )
            }
            Spacer(Modifier.weight(1f))
            if (!gamer) IranChip(options.iran, free) { onOptions(options.copy(iran = it)) }
        }
        ModeSwitch(options.gamer, free) { onOptions(options.copy(gamer = it)) }
        Spacer(Modifier.height(18.dp))
        Portal(s.copy(gamer = gamer), 216.dp, onToggle)
        Spacer(Modifier.height(14.dp))
        Status(s, options)
        Spacer(Modifier.height(14.dp))
        Pipeline(s.copy(gamer = gamer))
        Spacer(Modifier.height(18.dp))
        if (running) {
            InfoGrid(s)
            Spacer(Modifier.height(10.dp))
            SmallButton(if (showAll) "بستن" else "پروتکل‌ها", K.rose, true) { showAll = !showAll }
            if (showAll) {
                Spacer(Modifier.height(10.dp))
                if (options.gamer) GamerPanel(options, false, onOptions, onGameScan) else ProtocolGrid(options, false, onOptions)
            }
        } else if (options.gamer) {
            GamerPanel(options, free, onOptions, onGameScan)
        } else {
            ProtocolGrid(options, free, onOptions)
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** A small «ایران» checkbox; its arrow opens one paragraph that closes itself after five seconds. */
@Composable
private fun IranChip(on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    var info by remember { mutableStateOf(false) }
    LaunchedEffect(info) { if (info) { delay(5000); info = false } }
    Column(horizontalAlignment = Alignment.End) {
        Row(
            Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) K.ok.copy(alpha = 0.12f) else K.wine)
                .border(1.dp, if (on) K.ok else K.wineLine, RoundedCornerShape(18.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.clickable(enabled = enabled) { onChange(!on) }.padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(if (on) K.ok else Color.Transparent)
                        .border(1.5.dp, if (on) K.ok else K.rose, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center,
                ) { if (on) Icon(Ic.check, null, tint = K.night, modifier = Modifier.size(12.dp)) }
                Spacer(Modifier.width(6.dp))
                Text("ایران", style = T.small, color = if (on) K.ok else K.champagne)
            }
            Box(
                Modifier.clickable { info = !info }.padding(start = 2.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                contentAlignment = Alignment.Center,
            ) { Icon(Ic.info, "توضیح", tint = K.rose, modifier = Modifier.size(16.dp)) }
        }
    }
    if (info) {
        AlertLikeTip(
            "حالت ایران: اول MASQUE · HTTP/2 روی پورت ۴۴۳ که شبیه یک سایت معمولی است، با دست‌دادن تکه‌تکه و اسکن کامل دروازه؛ " +
                "اگر نشد Amnezia، بعد Gool و آخر Psiphon (بدون کلادفلر). اتصال اول کندتر است."
        ) { info = false }
    }
}

@Composable
private fun AlertLikeTip(text: String, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = K.wine,
        text = { Text(text, style = T.body, color = K.champagne) },
        confirmButton = { TextButton(onClose) { Text("باشه", style = T.strong, color = K.gold) } },
    )
}

/** «عادی» or «گیمر»: one capsule, the pill slides to the chosen side. */
@Composable
private fun ModeSwitch(gamer: Boolean, enabled: Boolean, onPick: (Boolean) -> Unit) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(22.dp)).background(K.wine)
            .border(1.dp, K.wineLine, RoundedCornerShape(22.dp)).padding(3.dp)
    ) {
        val half = maxWidth / 2
        val x by animateDpAsState(if (gamer) half else 0.dp, tween(240), label = "pill")
        val pill by animateColorAsState(if (gamer) K.amber else K.gold, tween(240), label = "pillColor")
        Box(Modifier.offset(x = x).width(half).fillMaxHeight().clip(RoundedCornerShape(19.dp)).background(pill))
        Row(Modifier.fillMaxSize()) {
            listOf(Triple(false, "عادی", Ic.shield), Triple(true, "گیمر", Ic.gamepad)).forEach { (g, name, icon) ->
                val on = g == gamer
                Row(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(19.dp)).clickable(enabled = enabled) { onPick(g) },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, null, tint = if (on) K.night else K.rose, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(name, style = T.strong.copy(fontSize = 14.sp), color = if (on) K.night else K.champagne)
                }
            }
        }
    }
}

/** Under the button: the state in one word, then where the connection goes (or is going). */
@Composable
private fun Status(s: ConnState, options: Options) {
    val ctx = LocalContext.current
    val title = when (s.phase) {
        Phase.RUNNING -> "وصل"
        Phase.CONNECTING -> "در حال اتصال"
        Phase.STOPPING -> "در حال قطع"
        Phase.ERROR -> "وصل نشد"
        Phase.IDLE -> "آماده"
    }
    Text(
        if (s.phase == Phase.RUNNING && s.gamer) "حالت بازی فعال" else title,
        style = T.big.copy(fontSize = 24.sp),
        color = when (s.phase) { Phase.ERROR -> K.ember; Phase.RUNNING -> K.ok; else -> K.champagne },
    )
    when (s.phase) {
        Phase.RUNNING -> {
            var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
            LaunchedEffect(s.startedAt) { while (true) { now = SystemClock.elapsedRealtime(); delay(1000) } }
            Text(clock(((now - s.startedAt) / 1000).coerceAtLeast(0)), style = T.mono.copy(fontSize = 18.sp), color = K.ok)
            Spacer(Modifier.height(2.dp))
            Text(destination(s.egress?.colo, s.egress?.pingMs?.toInt()) + "  ·  " + s.actual.ifBlank { s.transport?.title.orEmpty() },
                style = T.small, color = K.rose, textAlign = TextAlign.Center)
        }
        Phase.CONNECTING -> Text(s.message, style = T.small, color = K.rose, textAlign = TextAlign.Center)
        Phase.ERROR -> Text(s.error.orEmpty(), style = T.small, color = K.rose, textAlign = TextAlign.Center)
        else -> {
            // what the button will connect to
            val line = if (options.gamer) {
                val best = remember(options.gamer) { cafe.kankipro.warp.core.Prefs.gameHits(ctx, GameEngine.networkKey(ctx)).firstOrNull() }
                val scan by GameEngine.scan.collectAsState()
                val top = scan.hits.firstOrNull() ?: best
                if (top == null) "اول مسیر بازی را اسکن کن" else destination(top.colo, top.pingMs) + "  ·  ${options.gameTransport.title}"
            } else {
                val pin = options.pins[options.transport]
                if (options.iran) "حالت ایران · MASQUE · HTTP/2 ← Amnezia ← Gool ← Psiphon"
                else options.transport.title + (pin?.let { "  ·  آی‌پی ثابت" + (options.pinPing[options.transport]?.let { " $it ms" } ?: "") } ?: "")
            }
            Text(line, style = T.small, color = K.rose, textAlign = TextAlign.Center)
        }
    }
}

/** «🇩🇪 فرانکفورت · 82 ms», or what part of it is known. */
private fun destination(colo: String?, ping: Int?): String {
    val c = colo?.uppercase().orEmpty()
    val flag = flag(Colos.country(c))
    val city = Colos.NAMES[c] ?: c.ifBlank { null }
    return listOfNotNull(listOfNotNull(flag, city).joinToString(" ").ifBlank { null }, ping?.let { ltr("$it ms") }).joinToString("  ·  ")
}

/** The four stages as one line: lit when proven, the current one blinks, a failed one is red. */
@Composable
private fun Pipeline(s: ConnState) {
    val blink by rememberInfiniteTransition(label = "blink")
        .animateFloat(0.3f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "a")
    val accent = when { s.phase == Phase.RUNNING -> K.ok; s.gamer -> K.amber; else -> K.gold }
    val stages = Stage.entries
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 34.dp), verticalAlignment = Alignment.CenterVertically) {
            stages.forEachIndexed { i, _ ->
                val proven = i < s.proven
                val current = s.phase == Phase.CONNECTING && i == s.proven
                val failed = s.phase == Phase.ERROR && i == s.proven
                Box(
                    Modifier.size(8.dp).alpha(if (current) blink else 1f).clip(CircleShape)
                        .background(when { proven -> accent; failed -> K.ember; current -> K.champagne; else -> K.wineLine })
                )
                if (i < stages.size - 1) {
                    Box(Modifier.weight(1f).height(1.5.dp).background(if (i + 1 <= s.proven) accent else K.wineLine))
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth()) {
            stages.forEachIndexed { i, st ->
                Text(
                    st.title, style = T.small.copy(fontSize = 11.sp),
                    color = when {
                        i < s.proven -> accent
                        s.phase == Phase.ERROR && i == s.proven -> K.ember
                        s.phase == Phase.CONNECTING && i == s.proven -> K.champagne
                        else -> K.rose.copy(alpha = 0.7f)
                    },
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f), maxLines = 1,
                )
            }
        }
    }
}

/** Connected: ping and city, speed and total, and the two addresses (tap to copy). */
@Composable
private fun InfoGrid(s: ConnState) {
    val t by Engine.traffic.collectAsState()
    val ctx = LocalContext.current
    val e = s.egress
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile(Modifier.weight(1f), "پینگ") {
                val ping = e?.pingMs?.toInt()
                Text(ping?.let { "$it ms" } ?: "—", style = T.title.copy(textDirection = TextDirection.Ltr), color = pingColor(ping))
                Text(destination(e?.colo, null).ifBlank { "—" }, style = T.small, color = K.champagne, maxLines = 1)
            }
            Tile(Modifier.weight(1f), "سرعت") {
                Text("↓ ${bytes(t.downRate)}/s", style = T.strong.copy(textDirection = TextDirection.Ltr, fontSize = 15.sp), color = K.champagne)
                Text("↑ ${bytes(t.upRate)}/s  ·  " + bytes(t.down + t.up), style = T.small.copy(textDirection = TextDirection.Ltr), color = K.rose)
            }
        }
        Spacer(Modifier.height(8.dp))
        Tile(Modifier.fillMaxWidth(), null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Copyable(Modifier.weight(1f), "آی‌پی خروجی", e?.ip, ctx)
                Copyable(Modifier.weight(1f), "گره ورود", s.gateway.ifBlank { null }, ctx)
            }
        }
    }
}

@Composable
private fun Tile(modifier: Modifier, label: String?, content: @Composable () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(K.wine).border(1.dp, K.wineLine, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (label != null) {
            Text(label, style = T.small.copy(fontSize = 11.sp), color = K.rose)
            Spacer(Modifier.height(2.dp))
        }
        content()
    }
}

@Composable
private fun Copyable(modifier: Modifier, label: String, value: String?, ctx: Context) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = value != null) { copy(ctx, value.orEmpty()) }.padding(vertical = 2.dp)) {
        Text(label, style = T.small.copy(fontSize = 11.sp), color = K.rose)
        Text(value ?: "—", style = T.strong.copy(textDirection = TextDirection.Ltr, fontSize = 13.sp), color = K.champagne,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun copy(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("kanki-warp", text))
    Toast.makeText(ctx, "کپی شد", Toast.LENGTH_SHORT).show()
}

private fun pingColor(ms: Int?) = when {
    ms == null -> K.champagne
    ms < 100 -> K.ok
    ms <= 180 -> K.warn
    else -> K.ember
}

/** Two or three words under each protocol's name: what it is good for. */
private fun short(t: Transport) = when (t) {
    Transport.AMNEZIA -> "پوشش کامل"
    Transport.WIREGUARD -> "سبک و سریع"
    Transport.MASQUE_H2 -> "شبیه HTTPS"
    Transport.MASQUE_H3 -> "QUIC · کم‌پینگ"
    Transport.GOOL -> "دو لایه"
    Transport.MIM -> "سخت‌گیرانه"
    Transport.PSIPHON -> "شرایط خیلی سخت"
}

/** The normal user's protocols, two to a row; the white-IP scan is the small radar on each. */
@Composable
private fun ProtocolGrid(options: Options, enabled: Boolean, onOptions: (Options) -> Unit) {
    val ctx = LocalContext.current
    val latest by rememberUpdatedState(options)
    val scan by PinScan.state.collectAsState()
    val iranLocked = options.iran
    Column(Modifier.fillMaxWidth()) {
        Transport.entries.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { t ->
                    val on = t == options.transport && !iranLocked
                    val inIran = iranLocked && t in Options.IRAN
                    val busy = scan.transport == t && scan.running
                    val pin = options.pins[t]
                    Column(
                        Modifier.weight(1f).heightIn(min = 72.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (on || inIran) K.wine else Color.Transparent)
                            .border(1.dp, when { on -> K.gold; inIran -> K.ok.copy(alpha = 0.6f); else -> K.wineLine }, RoundedCornerShape(14.dp))
                            .clickable(enabled = enabled && !iranLocked) { onOptions(options.copy(transport = t)) }
                            .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t.title, style = T.strong.copy(fontSize = 14.sp), color = if (on) K.gold else K.champagne,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(short(t), style = T.small.copy(fontSize = 11.sp), color = K.rose, maxLines = 1)
                            }
                            if (PinScan.canScan(t)) {
                                RadarButton(busy, enabled && !scan.running || busy, if (on) K.gold else K.rose) {
                                    if (busy) PinScan.cancel()
                                    else PinScan.start(ctx, t) { ep, ms ->
                                        onOptions(latest.copy(pins = latest.pins + (t to ep), pinPing = latest.pinPing + (t to ms)))
                                    }
                                }
                            }
                        }
                        when {
                            busy -> Text(
                                "${percent(scan.done, scan.total)} · ${fa(scan.found)} جواب",
                                style = T.small.copy(fontSize = 11.sp), color = K.gold, maxLines = 1,
                            )
                            pin != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                                options.pinPing[t]?.let { Badge("$it ms", pingColor(it)) }
                                Spacer(Modifier.width(4.dp))
                                Text("آی‌پی ثابت", style = T.small.copy(fontSize = 11.sp), color = K.champagne, modifier = Modifier.weight(1f), maxLines = 1)
                                Icon(Ic.close, "حذف", tint = K.rose, modifier = Modifier.size(18.dp).clip(CircleShape).clickable(enabled = enabled) {
                                    onOptions(options.copy(pins = options.pins - t, pinPing = options.pinPing - t))
                                })
                            }
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        val last = scan.result
        if (!scan.running && last != null) {
            Spacer(Modifier.height(4.dp))
            Text(last, style = T.small.copy(fontSize = 11.sp), color = if (scan.found > 0) K.ok else K.ember)
        }
    }
}

private fun percent(done: Int, total: Int) = if (total <= 0) "۰٪" else fa((done * 100 / total).coerceIn(0, 100)) + "٪"

/** The scan key: a radar that turns when a scan runs (tap again to stop). */
@Composable
private fun RadarButton(busy: Boolean, enabled: Boolean, tint: Color, onClick: () -> Unit) {
    val spin by rememberInfiniteTransition(label = "radar")
        .animateFloat(0.35f, 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "r")
    Box(
        Modifier.size(36.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Ic.radar, "آی‌پی سفید", tint = if (busy) K.gold.copy(alpha = spin) else if (enabled) tint else K.wineLine,
            modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun Badge(text: String, color: Color) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 1.dp)
    ) { Text(text, style = T.small.copy(textDirection = TextDirection.Ltr, fontSize = 11.sp), color = color, maxLines = 1) }
}

@Composable
internal fun SmallButton(text: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(16.dp)).border(1.dp, if (enabled) color else K.wineLine, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick).heightIn(min = 32.dp).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = T.small, color = if (enabled) color else K.rose, maxLines = 1) }
}

/**
 * The gamer: the scan first (it decides everything), its results as two columns, Turkey and Germany,
 * best first (Frankfurt leads Germany), then the engine. Each result: city, ping, jitter, loss.
 */
@Composable
private fun GamerPanel(options: Options, enabled: Boolean, onOptions: (Options) -> Unit, onGameScan: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val scan by GameEngine.scan.collectAsState()
    LaunchedEffect(Unit) { GameEngine.loadSaved(ctx) }
    val chosen = scan.hits.firstOrNull()?.endpoint
    Column(Modifier.fillMaxWidth()) {
        // the scan bar
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.wine)
                .border(1.dp, if (scan.running) K.amber else K.wineLine, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Ic.radar, null, tint = K.amber, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (scan.running) (if (scan.fast) "اسکن سریع…" else "اسکن کامل…") else "اسکن مسیر بازی", style = T.strong.copy(fontSize = 14.sp), color = K.champagne)
                Text(
                    when {
                        scan.running && scan.phase == 1 -> "پیدا کردن گره‌ها · ${percent(scan.done, scan.total)} · ${fa(scan.found)} جواب"
                        scan.running -> "تشخیص شهر · ${fa(scan.done)} از ${fa(scan.total)}"
                        scan.error != null -> scan.error!!
                        scan.hits.isEmpty() -> "سریع: حدود یک دقیقه · کامل: دقیق‌تر"
                        else -> "شبکه: " + scan.network.removePrefix("cell:").replace("wifi", "وای‌فای")
                    },
                    style = T.small.copy(fontSize = 11.sp), color = if (scan.error != null && !scan.running) K.ember else K.rose, maxLines = 1,
                )
            }
            if (!scan.running) {
                SmallButton("سریع", K.amber, enabled) { onGameScan(true) }
                Spacer(Modifier.width(6.dp))
                SmallButton("کامل", K.rose, enabled) { onGameScan(false) }
            }
        }
        if (scan.running) {
            Spacer(Modifier.height(6.dp))
            val frac = if (scan.total > 0) scan.done.toFloat() / scan.total else 0f
            val half = if (scan.phase == 1) frac / 2 else 0.5f + frac / 2
            Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(K.wineLine)) {
                Box(Modifier.fillMaxWidth(half.coerceIn(0f, 1f)).fillMaxHeight().background(K.amber))
            }
        }
        // results: Turkey | Germany
        val tr = Colos.turkey(scan.hits)
        val de = Colos.germanyFirst(scan.hits)
        val rest = Colos.otherCountries(scan.hits)
        if (scan.hits.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CountryColumn(Modifier.weight(1f), "TR", "ترکیه", tr, chosen, enabled && !scan.running) { GameEngine.pick(ctx, it) }
                CountryColumn(Modifier.weight(1f), "DE", "آلمان", de, chosen, enabled && !scan.running) { GameEngine.pick(ctx, it) }
            }
            // every other country found, two to a row, each with its flag
            rest.chunked(2).forEach { row ->
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (cc, list) ->
                        CountryColumn(Modifier.weight(1f), cc, Colos.countryName(cc), list, chosen, enabled && !scan.running, 4) { GameEngine.pick(ctx, it) }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        // the engine
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Transport.AMNEZIA to "پوشش کامل", Transport.WIREGUARD to "سبک‌ترین").forEach { (t, sub) ->
                val on = t == options.gameTransport
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (on) K.wine else Color.Transparent)
                        .border(1.dp, if (on) K.amber else K.wineLine, RoundedCornerShape(12.dp))
                        .clickable(enabled = enabled) { onOptions(options.copy(gameTransport = t)) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(t.title, style = T.strong.copy(fontSize = 14.sp), color = if (on) K.amber else K.champagne)
                        Text(sub, style = T.small.copy(fontSize = 11.sp), color = K.rose)
                    }
                    Radio(on, K.amber)
                }
            }
        }
    }
}

@Composable
private fun CountryColumn(
    modifier: Modifier, cc: String, name: String, hits: List<WgScan.Hit>, chosen: String?, enabled: Boolean,
    max: Int = 8,
    onPick: (WgScan.Hit) -> Unit,
) {
    Column(modifier) {
        Text(listOfNotNull(flag(cc), name).joinToString("  "), style = T.small, color = K.champagne)
        Spacer(Modifier.height(4.dp))
        if (hits.isEmpty()) {
            Text("پیدا نشد", style = T.small.copy(fontSize = 11.sp), color = K.rose)
        }
        hits.take(max).forEach { HitRow(it, it.endpoint == chosen, enabled) { onPick(it) } }
    }
}

/** One result: city, then ping (coloured), jitter and loss; the chosen one has the accent border. */
@Composable
private fun HitRow(h: WgScan.Hit, chosen: Boolean, enabled: Boolean, onPick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp))
            .background(if (chosen) K.wine else Color.Transparent)
            .border(1.dp, if (chosen) K.amber else K.wineLine, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onPick).padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Colos.NAMES[h.colo] ?: h.colo.ifBlank { "نامعلوم" }, style = T.small, color = if (chosen) K.amber else K.champagne,
                modifier = Modifier.weight(1f), maxLines = 1)
            Text("${h.pingMs} ms", style = T.strong.copy(textDirection = TextDirection.Ltr, fontSize = 14.sp), color = pingColor(h.pingMs))
        }
        Text("لرزش ${fa(h.jitterMs)} · افت ${fa(h.lossPct)}٪", style = T.small.copy(fontSize = 10.sp),
            color = if (h.gameReady) K.ok else K.rose)
    }
}

@Composable
private fun Radio(on: Boolean, color: Color = K.gold) {
    Box(
        Modifier.size(16.dp).clip(CircleShape)
            .border(2.dp, if (on) color else K.wineLine, CircleShape)
            .padding(4.dp).clip(CircleShape).background(if (on) color else Color.Transparent)
    )
}

// ------------------------------------------------------------ settings

@Composable
private fun Settings(options: Options, onOptions: (Options) -> Unit, onForget: () -> Unit) {
    val s by Engine.state.collectAsState()
    val ctx = LocalContext.current
    val locked = s.phase == Phase.CONNECTING || s.phase == Phase.RUNNING
    var confirm by remember { mutableStateOf(false) }
    val account by Engine.account.collectAsState()
    val busy = account?.startsWith("در حال") == true
    val info = remember(account, busy) { Engine.accountInfo(ctx) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("تنظیمات", style = T.title, color = K.champagne)
        if (locked) Text("برای تغییر، اول قطع کن", style = T.small, color = K.warn)
        Spacer(Modifier.height(16.dp))
        Section("پیدا کردن گره") { Choice(Scan.entries, options.scan, { it.title }, !locked) { onOptions(options.copy(scan = it)) } }
        Section("نوع آی‌پی") { Choice(IpFamily.entries, options.ip, { it.title }, !locked) { onOptions(options.copy(ip = it)) } }
        Section("DNS") { Choice(Dns.entries, options.dns, { it.title }, !locked) { onOptions(options.copy(dns = it)) } }
        if (!options.gamer) Section("کشور خروج Psiphon") {
            Choice(PSIPHON_REGIONS, PSIPHON_REGIONS.firstOrNull { it.first == options.psiphonRegion } ?: PSIPHON_REGIONS[0],
                { it.second }, !locked) { onOptions(options.copy(psiphonRegion = it.first)) }
        }
        Toggle("تکه‌تکه کردن دست‌دادن", options.fragment, !locked) { onOptions(options.copy(fragment = it)) }
        Toggle("پروتکل جایگزین خودکار", options.autoFallback, !locked) { onOptions(options.copy(autoFallback = it)) }
        if (options.gamer) ZeroTrustCard(locked)
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(K.wine)
                .border(1.dp, K.wineLine, RoundedCornerShape(16.dp)).padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("اکانت WARP", style = T.strong, color = K.champagne, modifier = Modifier.weight(1f))
                Badge(if (info.device.isNotBlank()) "فعال" else "ساخته نشده", if (info.device.isNotBlank()) K.ok else K.rose)
            }
            Spacer(Modifier.height(8.dp))
            KeyLine("شناسه‌ی دستگاه", info.device.take(8).ifBlank { "—" } + if (info.device.length > 8) "…" else "")
            KeyLine("آی‌پی داخلی", info.ipv4.ifBlank { "—" })
            KeyLine("کلید WireGuard / Amnezia", if (info.wireguard) "✓" else "—")
            KeyLine("گواهی MASQUE", if (info.masque) "✓" else "—")
            Spacer(Modifier.height(10.dp))
            SmallButton(if (busy) "در حال ساخت…" else "اکانت تازه", if (busy) K.rose else K.ember, !locked && !busy) { confirm = true }
            account?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = T.small, color = if (it.startsWith("ساخته نشد")) K.ember else K.ok)
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = K.wine,
            title = { Text("اکانت تازه بسازم؟", style = T.strong, color = K.champagne) },
            text = { Text("اکانت فعلی از این گوشی پاک و همین الان یک اکانت تازه ساخته می‌شود.", style = T.body, color = K.rose) },
            confirmButton = { TextButton({ confirm = false; onForget() }) { Text("بساز", style = T.strong, color = K.ember) } },
            dismissButton = { TextButton({ confirm = false }) { Text("نه", style = T.strong, color = K.champagne) } },
        )
    }
}

private val PSIPHON_REGIONS = listOf("" to "خودکار", "DE" to "آلمان", "TR" to "ترکیه", "NL" to "هلند", "FR" to "فرانسه")

@Composable
private fun KeyLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = T.small, color = K.rose, modifier = Modifier.weight(1f))
        Text(value, style = T.small.copy(textDirection = TextDirection.Ltr), color = K.champagne)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(title, style = T.small, color = K.rose)
    Spacer(Modifier.height(6.dp))
    content()
    Spacer(Modifier.height(16.dp))
}

/** A capsule of equal segments; the chosen one is filled. */
@Composable
private fun <E> Choice(items: List<E>, selected: E, name: (E) -> String, enabled: Boolean, onPick: (E) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(K.wine)
            .border(1.dp, K.wineLine, RoundedCornerShape(20.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        items.forEach { e ->
            val on = e == selected
            Box(
                Modifier.weight(1f).heightIn(min = 36.dp).clip(RoundedCornerShape(17.dp))
                    .background(if (on) K.gold else Color.Transparent)
                    .clickable(enabled = enabled) { onPick(e) },
                contentAlignment = Alignment.Center,
            ) { Text(name(e), style = T.small, color = if (on) K.night else K.champagne, maxLines = 1) }
        }
    }
}

@Composable
private fun Toggle(title: String, value: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = T.body, color = K.champagne, modifier = Modifier.weight(1f))
        Switch(
            value, onChange, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = K.night, checkedTrackColor = K.gold,
                uncheckedThumbColor = K.rose, uncheckedTrackColor = K.wine, uncheckedBorderColor = K.wineLine,
            ),
        )
    }
}

// ----------------------------------------------------------------- log

@Composable
private fun LogScreen(options: Options) {
    val lines by Engine.log.collectAsState()
    val s by Engine.state.collectAsState()
    val ctx = LocalContext.current
    val list = rememberLazyListState()
    LaunchedEffect(Unit) {
        while (true) { Engine.pumpLog(); delay(700) }
    }
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.scrollToItem(lines.size - 1) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("گزارش", style = T.title, color = K.champagne)
            Spacer(Modifier.weight(1f))
            SmallButton("کپی", K.gold, lines.isNotEmpty()) { copy(ctx, lines.joinToString("\n")) }
            Spacer(Modifier.width(6.dp))
            SmallButton("پاک کردن", K.rose, lines.isNotEmpty()) { Engine.clearLog() }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Badge("Aether " + (Engine.coreVersion?.let { "v$it" } ?: "—"), K.gold)
            Badge((if (s.gamer || options.gamer) "گیمر · " else "") + (s.transport ?: if (options.gamer) options.gameTransport else options.transport).title, K.champagne)
            if (Engine.chainText.isNotBlank() && !options.gamer) Badge(Engine.chainText, K.rose)
        }
        Spacer(Modifier.height(10.dp))
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            LazyColumn(
                Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(Color(0xFF00030A))
                    .border(1.dp, K.wineLine, RoundedCornerShape(14.dp)).padding(12.dp),
                state = list,
            ) {
                if (lines.isEmpty()) item {
                    Text("هنوز خالی است. وصل شو تا مراحل این‌جا بیاید.", style = T.body, color = K.rose)
                }
                items(lines) { l ->
                    Text(highlight(l), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp)
                }
            }
        }
    }
}

private val TIME = Regex("""^·?\s*\d\d:\d\d:\d\d""")
private val ADDR = Regex("""\[?[0-9a-fA-F]*[.:][0-9a-fA-F.:]+]?:\d{2,5}|\b\d{1,3}(?:\.\d{1,3}){3}\b""")
private val GOOD = Regex("""\[\+]|validated|verified|still works|ready|passed|✓""", RegexOption.IGNORE_CASE)
private val BAD = Regex("""\[-]|error|failed|refused|timed out|no usable|did not|not usable""", RegexOption.IGNORE_CASE)

/** Terminal colours: time grey, addresses accent, success green, errors red, the rest light. */
fun highlight(line: String): AnnotatedString = buildAnnotatedString {
    val base = when {
        BAD.containsMatchIn(line) -> K.ember
        GOOD.containsMatchIn(line) -> K.ok
        else -> K.champagne.copy(alpha = 0.85f)
    }
    val time = TIME.find(line)
    var from = 0
    if (time != null) {
        withStyle(SpanStyle(color = K.ash)) { append(time.value) }
        from = time.range.last + 1
    }
    var i = from
    for (m in ADDR.findAll(line, from)) {
        withStyle(SpanStyle(color = base)) { append(line.substring(i, m.range.first)) }
        withStyle(SpanStyle(color = K.gold)) { append(m.value) }
        i = m.range.last + 1
    }
    withStyle(SpanStyle(color = base)) { append(line.substring(i)) }
}

// ----------------------------------------------------------------- text

private val FA = "۰۱۲۳۴۵۶۷۸۹"
fun fa(n: Int): String = n.toString().map { if (it in '0'..'9') FA[it - '0'] else it }.joinToString("")

fun bytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
    else -> "%.2f GB".format(b / 1073741824.0)
}

/** Keeps a Latin run (a size, an address) in one piece inside Persian text. */
fun ltr(s: String): String = "\u2066$s\u2069"

fun clock(sec: Long): String = "%02d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60)

/** Flag emoji from a two-letter country code. */
fun flag(cc: String): String? {
    if (cc.length != 2 || !cc.all { it.isLetter() }) return null
    val u = cc.uppercase()
    return String(Character.toChars(0x1F1E6 + (u[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (u[1] - 'A')))
}
