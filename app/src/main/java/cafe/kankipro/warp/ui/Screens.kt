package cafe.kankipro.warp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.widget.Toast
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.kankipro.warp.core.ConnState
import cafe.kankipro.warp.core.Engine
import cafe.kankipro.warp.core.GameEngine
import cafe.kankipro.warp.core.IpFamily
import cafe.kankipro.warp.core.Options
import cafe.kankipro.warp.core.Phase
import cafe.kankipro.warp.core.PinScan
import cafe.kankipro.warp.core.Scan
import cafe.kankipro.warp.core.Stage
import cafe.kankipro.warp.core.Transport
import kotlinx.coroutines.delay

enum class Tab(val title: String) { HOME("اتصال"), SETTINGS("تنظیمات"), LOG("گزارش") }

@Composable
fun App(
    options: Options,
    onOptions: (Options) -> Unit,
    onToggle: () -> Unit,
    onForget: () -> Unit,
    initialTab: Tab = Tab.HOME,
) {
    var tab by remember { mutableStateOf(initialTab) }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(Modifier.fillMaxSize().background(K.night)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    Tab.HOME -> Home(options, onOptions, onToggle)
                    Tab.SETTINGS -> Settings(options, onOptions, onForget)
                    Tab.LOG -> LogScreen(options)
                }
            }
            NavBar(tab) { tab = it }
        }
    }
}

@Composable
private fun NavBar(tab: Tab, onTab: (Tab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(K.wine).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        Tab.entries.forEach { t ->
            val on = t == tab
            Column(
                Modifier.clip(RoundedCornerShape(12.dp)).clickable { onTab(t) }
                    .heightIn(min = 44.dp).padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(t.title, style = T.strong, color = if (on) K.gold else K.rose)
                Spacer(Modifier.height(4.dp))
                Box(Modifier.size(width = 20.dp, height = 3.dp).clip(CircleShape).background(if (on) K.gold else Color.Transparent))
            }
        }
    }
}

// ---------------------------------------------------------------- home

@Composable
private fun Home(options: Options, onOptions: (Options) -> Unit, onToggle: () -> Unit) {
    val s by Engine.state.collectAsState()
    var showAll by remember { mutableStateOf(false) }
    val running = s.phase == Phase.RUNNING
    val free = s.phase == Phase.IDLE || s.phase == Phase.ERROR
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("کانکی وارپ", style = T.title, color = K.champagne)
            Spacer(Modifier.weight(1f))
            Text(if (s.egress?.warp == "plus") "WARP+" else "WARP", style = T.small, color = K.rose)
        }
        Spacer(Modifier.height(8.dp))
        Portal(s, 248.dp, onToggle)
        Spacer(Modifier.height(16.dp))
        Headline(s)
        Spacer(Modifier.height(18.dp))
        Pipeline(s)
        Spacer(Modifier.height(22.dp))
        if (running) {
            InfoGrid(s)
            Spacer(Modifier.height(16.dp))
            ActiveCard(s, options)
            Spacer(Modifier.height(8.dp))
            SmallButton(if (showAll) "بستن فهرست" else "نمایش همه‌ی پروتکل‌ها", K.rose, true) { showAll = !showAll }
            if (showAll) {
                Spacer(Modifier.height(12.dp))
                if (options.gamer) GamerPanel(options, false, onOptions) else TransportPicker(options, false, onOptions)
            }
        } else {
            ModeSwitch(options.gamer, free) { onOptions(options.copy(gamer = it)) }
            if (options.gamer) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(K.amber))
                    Spacer(Modifier.width(6.dp))
                    Text("حالت تأخیر کم و ضد افت بسته فعال است", style = T.small, color = K.amber)
                }
            }
            Spacer(Modifier.height(16.dp))
            if (options.gamer) GamerPanel(options, free, onOptions) else TransportPicker(options, free, onOptions)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Headline(s: ConnState) {
    val title = when (s.phase) {
        Phase.RUNNING -> "وصل هستی"
        Phase.CONNECTING -> "در حال اتصال"
        Phase.STOPPING -> "در حال قطع"
        Phase.ERROR -> "وصل نشد"
        Phase.IDLE -> "وصل نیستی"
    }
    Text(title, style = T.big, color = if (s.phase == Phase.ERROR) K.ember else K.champagne)
    if (s.phase == Phase.RUNNING) {
        var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
        LaunchedEffect(s.startedAt) { while (true) { now = SystemClock.elapsedRealtime(); delay(1000) } }
        Text(clock(((now - s.startedAt) / 1000).coerceAtLeast(0)), style = T.mono,
            color = if (s.gamer) K.amber else K.gold)
        Text(s.transport?.title.orEmpty() + if (s.gamer) " · گیمر" else "", style = T.small, color = K.rose)
        return
    }
    val sub = when (s.phase) {
        Phase.CONNECTING -> s.message
        Phase.ERROR -> s.error.orEmpty()
        else -> "دکمه را بزن؛ اولین بار یک اکانت رایگان WARP ساخته می‌شود."
    }
    Spacer(Modifier.height(4.dp))
    Text(sub, style = T.body, color = K.rose, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    if (s.phase == Phase.CONNECTING && s.attempts > 1) {
        Spacer(Modifier.height(4.dp))
        Text(
            (if (s.gamer) "دروازه‌ی" else "پروتکل") + " ${fa(s.attempt)} از ${fa(s.attempts)}" +
                (if (s.gamer) "" else ": ${s.transport?.title.orEmpty()}"),
            style = T.small, color = K.gold,
        )
    }
}

/**
 * The four stages as one line: a stage turns gold when proven and the line to the next one fills
 * with it; the stage being worked on blinks; a failed one is red.
 */
@Composable
private fun Pipeline(s: ConnState) {
    val blink by rememberInfiniteTransition(label = "blink")
        .animateFloat(0.3f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "a")
    val gold = if (s.gamer) K.amber else K.gold
    val stages = Stage.entries
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 30.dp), verticalAlignment = Alignment.CenterVertically) {
            stages.forEachIndexed { i, _ ->
                val proven = i < s.proven
                val current = s.phase == Phase.CONNECTING && i == s.proven
                val failed = s.phase == Phase.ERROR && i == s.proven
                val color = when {
                    proven -> gold
                    failed -> K.ember
                    current -> K.champagne
                    else -> K.wineLine
                }
                Box(
                    Modifier.size(if (current) 12.dp else 10.dp).alpha(if (current) blink else 1f)
                        .clip(CircleShape).background(color)
                )
                if (i < stages.size - 1) {
                    Box(Modifier.weight(1f).height(2.dp).background(if (i + 1 < s.proven || (proven && i + 1 == s.proven)) gold else K.wineLine))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            stages.forEachIndexed { i, st ->
                val color = when {
                    i < s.proven -> gold
                    s.phase == Phase.ERROR && i == s.proven -> K.ember
                    s.phase == Phase.CONNECTING && i == s.proven -> K.champagne
                    else -> K.rose.copy(alpha = 0.6f)
                }
                Text(st.title, style = T.small, color = color, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                    maxLines = 1)
            }
        }
    }
}

/** «کاربر عادی» or «کاربر گیمر»: one capsule, the gold pill slides to the chosen side. */
@Composable
private fun ModeSwitch(gamer: Boolean, enabled: Boolean, onPick: (Boolean) -> Unit) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).background(K.wine)
            .border(1.dp, if (gamer) K.amber.copy(alpha = 0.6f) else K.wineLine, RoundedCornerShape(26.dp)).padding(4.dp)
    ) {
        val half = maxWidth / 2
        // RTL: «عادی» is on the right (start), «گیمر» on the left
        val x by animateDpAsState(if (gamer) half else 0.dp, tween(260), label = "pill")
        val pill by animateColorAsState(if (gamer) K.amber else K.gold, tween(260), label = "pillColor")
        Box(Modifier.offset(x = x).width(half).fillMaxHeight().clip(RoundedCornerShape(22.dp)).background(pill))
        Row(Modifier.fillMaxSize()) {
            listOf(false to "کاربر عادی", true to "کاربر گیمر 🎮").forEach { (g, name) ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(22.dp))
                        .clickable(enabled = enabled) { onPick(g) },
                    contentAlignment = Alignment.Center,
                ) { Text(name, style = T.strong, color = if (g == gamer) K.night else K.champagne) }
            }
        }
    }
}

/** Connected: two tiles (ping and place; speed and total) and one full-width tile of addresses. */
@Composable
private fun InfoGrid(s: ConnState) {
    val t by Engine.traffic.collectAsState()
    val ctx = LocalContext.current
    val e = s.egress
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(Modifier.weight(1f), "پینگ") {
                val ping = e?.pingMs?.toInt()
                Text(ping?.let { "$it ms" } ?: "—", style = T.title.copy(textDirection = TextDirection.Ltr), color = pingColor(ping))
                Text(
                    listOfNotNull(e?.loc?.let(::flag), e?.loc?.takeIf { it.isNotBlank() },
                        e?.colo?.takeIf { it.isNotBlank() }?.let { "($it)" }).joinToString(" ").ifBlank { "—" },
                    style = T.small.copy(textDirection = TextDirection.Ltr), color = K.champagne,
                )
            }
            Tile(Modifier.weight(1f), "سرعت") {
                Text("↓ ${bytes(t.downRate)}/s", style = T.strong.copy(textDirection = TextDirection.Ltr), color = K.champagne)
                Text("↑ ${bytes(t.upRate)}/s", style = T.small.copy(textDirection = TextDirection.Ltr), color = K.rose)
                Text("کل " + ltr(bytes(t.down + t.up)), style = T.small, color = K.rose)
            }
        }
        Spacer(Modifier.height(10.dp))
        Tile(Modifier.fillMaxWidth(), "آدرس‌ها (برای کپی بزن)") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Copyable(Modifier.weight(1f), "خروجی", e?.ip, ctx)
                Copyable(Modifier.weight(1f), "دروازه", s.gateway.ifBlank { null }, ctx)
            }
        }
    }
}

@Composable
private fun Tile(modifier: Modifier, label: String, content: @Composable () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(K.wine, K.glass)))
            .border(1.dp, K.wineLine, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(label, style = T.small, color = K.rose)
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun Copyable(modifier: Modifier, label: String, value: String?, ctx: Context) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = value != null) {
            copy(ctx, value.orEmpty())
        }.padding(vertical = 4.dp)
    ) {
        Text(label, style = T.small, color = K.rose)
        Text(value ?: "—", style = T.strong.copy(textDirection = TextDirection.Ltr, fontSize = 14.sp), color = K.champagne,
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
    ms <= 180 -> K.gold
    else -> K.amber
}

/** Connected: only the protocol in use, compact. */
@Composable
private fun ActiveCard(s: ConnState, options: Options) {
    val t = s.transport ?: return
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(K.wine)
            .border(1.dp, if (s.gamer) K.amber else K.gold, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(t.title + if (s.gamer) " گیمینگ" else "", style = T.strong, color = if (s.gamer) K.amber else K.gold)
            val pin = options.pins[t]
            Text(
                when {
                    s.gamer -> "مستقیم روی گوشی، بدون لایه‌ی اضافه"
                    pin != null -> "آی‌پی ثابت"
                    else -> "دروازه را هسته پیدا کرد"
                },
                style = T.small, color = K.rose,
            )
        }
        Box(Modifier.size(8.dp).clip(CircleShape).background(K.ok))
    }
}

@Composable
private fun TransportPicker(options: Options, enabled: Boolean, onOptions: (Options) -> Unit) {
    val ctx = LocalContext.current
    val latest by rememberUpdatedState(options)
    val scan by PinScan.state.collectAsState()
    Column(Modifier.fillMaxWidth()) {
        Text("راه رسیدن به کلادفلر", style = T.strong, color = K.champagne)
        Spacer(Modifier.height(4.dp))
        Text(
            (if (options.autoFallback) "اگر این یکی جواب نداد، دو پروتکل دیگر خودکار امتحان می‌شود. "
            else "فقط همین پروتکل امتحان می‌شود. ") +
                "«آی‌پی سفید» بین آدرس‌های کلادفلر با دست‌دادن واقعی می‌گردد و یکی که از این شبکه جواب بدهد را ثابت می‌گذارد؛ با آی‌پی ثابت، هسته فقط به همان وصل می‌شود.",
            style = T.small, color = K.rose,
        )
        Spacer(Modifier.height(12.dp))
        Transport.entries.forEach { t ->
            val on = t == options.transport
            val pin = options.pins[t]
            Column(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp))
                    .border(1.dp, if (on) K.gold else K.wineLine, RoundedCornerShape(16.dp))
                    .background(if (on) K.wine else Color.Transparent)
                    .clickable(enabled = enabled) { onOptions(options.copy(transport = t)) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.title, style = T.strong, color = if (on) K.gold else K.champagne)
                        Text(t.hint, style = T.small, color = K.rose)
                    }
                    Radio(on)
                }
                if (PinScan.canScan(t)) {
                    Spacer(Modifier.height(10.dp))
                    val mine = scan.transport == t
                    val busy = mine && scan.running
                    // one capsule row: [ping badge] address ............ [حذف] [آی‌پی سفید]
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(K.night.copy(alpha = 0.6f))
                            .border(1.dp, K.wineLine, RoundedCornerShape(22.dp)).padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when {
                            busy -> Text(
                                "${fa(scan.done)}/${fa(scan.total)} · ${fa(scan.found)} جواب",
                                style = T.small, color = K.gold, modifier = Modifier.weight(1f), maxLines = 1,
                            )
                            pin != null -> {
                                options.pinPing[t]?.let { ms ->
                                    Badge("$ms ms", pingColor(ms))
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(pin, style = T.small.copy(textDirection = TextDirection.Ltr), color = K.champagne,
                                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            else -> Text("خودکار", style = T.small, color = K.rose, modifier = Modifier.weight(1f), maxLines = 1)
                        }
                        if (pin != null && !busy) {
                            SmallButton("حذف", K.rose, enabled) {
                                onOptions(options.copy(pins = options.pins - t, pinPing = options.pinPing - t))
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                        if (busy) {
                            SmallButton("توقف", K.ember, true) { PinScan.cancel() }
                        } else {
                            SmallButton("آی‌پی سفید", K.gold, enabled && !scan.running) {
                                PinScan.start(ctx, t) { ep, ms ->
                                    onOptions(latest.copy(pins = latest.pins + (t to ep), pinPing = latest.pinPing + (t to ms)))
                                }
                            }
                        }
                    }
                    if (mine && !scan.running && scan.result != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(scan.result!!, style = T.small, color = if (scan.found > 0) K.ok else K.ember)
                    }
                }
            }
        }
    }
}

@Composable
private fun Radio(on: Boolean, color: Color = K.gold) {
    Box(
        Modifier.size(18.dp).clip(CircleShape)
            .border(2.dp, if (on) color else K.wineLine, CircleShape)
            .padding(4.dp).clip(CircleShape).background(if (on) color else Color.Transparent)
    )
}

@Composable
private fun Badge(text: String, color: Color) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) { Text(text, style = T.small.copy(textDirection = TextDirection.Ltr), color = color, maxLines = 1) }
}

@Composable
private fun SmallButton(text: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(18.dp)).border(1.dp, if (enabled) color else K.wineLine, RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick).heightIn(min = 36.dp).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = T.small, color = if (enabled) color else K.rose, maxLines = 1) }
}

/**
 * «کاربر گیمر»: Amnezia or WireGuard straight on the phone (no layer in between), and the scan
 * that finds this network's best paths by ping, jitter and loss.
 */
@Composable
private fun GamerPanel(options: Options, enabled: Boolean, onOptions: (Options) -> Unit) {
    val ctx = LocalContext.current
    val scan by GameEngine.scan.collectAsState()
    LaunchedEffect(Unit) { GameEngine.loadSaved(ctx) }
    Column(Modifier.fillMaxWidth()) {
        Text("حالت بازی", style = T.strong, color = K.champagne)
        Spacer(Modifier.height(4.dp))
        Text(
            "Amnezia یا WireGuard مستقیم روی گوشی، بدون هیچ لایه‌ی اضافه؛ بسته‌ی بازی کوتاه‌ترین راه را می‌رود.",
            style = T.small, color = K.rose,
        )
        Spacer(Modifier.height(12.dp))
        listOf(
            Transport.AMNEZIA to "با پوشش Amnezia؛ وقتی WireGuard ساده بسته است",
            Transport.WIREGUARD to "بی‌پوشش؛ سبک‌ترین، اگر باز باشد",
        ).forEach { (t, hint) ->
            val on = t == options.gameTransport
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp))
                    .border(1.dp, if (on) K.amber else K.wineLine, RoundedCornerShape(16.dp))
                    .background(if (on) K.wine else Color.Transparent)
                    .clickable(enabled = enabled) { onOptions(options.copy(gameTransport = t)) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${t.title} گیمینگ", style = T.strong, color = if (on) K.amber else K.champagne)
                    Text(hint, style = T.small, color = K.rose)
                }
                Radio(on, K.amber)
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("بهترین مسیر برای بازی", style = T.strong, color = K.champagne)
                Text(
                    "شبکه: " + scan.network.removePrefix("cell:").replace("wifi", "وای‌فای").ifBlank { "—" },
                    style = T.small, color = K.rose,
                )
            }
            SmallButton(if (scan.running) "در حال اسکن…" else "اسکن", K.amber, enabled && !scan.running) {
                GameEngine.startScan(ctx, options.gameTransport)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (scan.running) {
            Text(
                "${fa(scan.done)} از ${fa(scan.total)} · ${fa(scan.found)} دروازه جواب داد",
                style = T.small, color = K.amber,
            )
        }
        scan.error?.let { Text(it, style = T.small, color = K.ember) }
        if (scan.hits.isEmpty() && !scan.running) {
            Text(
                "هنوز اسکن نشده. «اسکن» را بزن؛ آدرس‌ها و پورت‌های WireGuard کلادفلر با دست‌دادن واقعی امتحان می‌شوند و بهترین‌ها به ترتیب امتیاز (پینگ + لرزش×۳ + افت×۲۰) می‌آیند.",
                style = T.small, color = K.rose,
            )
        }
        scan.hits.forEachIndexed { i, h ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (i == 0) K.wine else Color.Transparent)
                    .border(1.dp, if (i == 0) K.amber else K.wineLine, RoundedCornerShape(14.dp))
                    .clickable(enabled = enabled && !scan.running) { GameEngine.pick(ctx, h) }
                    .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (i == 0) "★" else fa(i + 1), style = T.strong, color = if (i == 0) K.amber else K.rose)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(h.endpoint, style = T.strong.copy(textDirection = TextDirection.Ltr, fontSize = 14.sp),
                        color = K.champagne, maxLines = 1)
                    Text(
                        "لرزش ${fa(h.jitterMs)} · افت ${fa(h.lossPct)}٪" + if (h.gameReady) " · مناسب بازی" else "",
                        style = T.small, color = if (h.gameReady) K.ok else K.rose,
                    )
                }
                Badge("${h.pingMs} ms", pingColor(h.pingMs))
            }
        }
        if (scan.hits.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("★ اول امتحان می‌شود؛ اگر از تونل رد نشد، بعدی‌ها خودکار. روی هر کدام بزنی، ★ می‌شود.",
                style = T.small, color = K.rose)
        }
    }
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
        if (locked) {
            Spacer(Modifier.height(4.dp))
            Text("برای عوض کردن تنظیمات اول قطع کن.", style = T.small, color = K.gold)
        }
        Spacer(Modifier.height(16.dp))
        Section("پیدا کردن دروازه", "هسته IPهای کلادفلر را روی چند پورت امتحان می‌کند و بهترین را نگه می‌دارد.") {
            Choice(Scan.entries, options.scan, { it.title }, !locked) { onOptions(options.copy(scan = it)) }
        }
        Section("نوع آی‌پی", "اگر اینترنتت IPv6 ندارد، IPv4 بماند.") {
            Choice(IpFamily.entries, options.ip, { it.title }, !locked) { onOptions(options.copy(ip = it)) }
        }
        Toggle("تکه‌تکه کردن دست‌دادن", "شروع اتصال را تکه‌تکه می‌فرستد تا فیلتر آن را نشناسد.", options.fragment, !locked) {
            onOptions(options.copy(fragment = it))
        }
        Toggle("پروتکل جایگزین خودکار", "اگر پروتکل انتخابی (یا آی‌پی ثابتش) جواب نداد، بقیه را امتحان کن.", options.autoFallback, !locked) {
            onOptions(options.copy(autoFallback = it))
        }
        Spacer(Modifier.height(12.dp))
        // the account card
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(Brush.verticalGradient(listOf(K.wine, K.glass)))
                .border(1.dp, K.wineLine, RoundedCornerShape(20.dp)).padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("اکانت WARP", style = T.strong, color = K.champagne, modifier = Modifier.weight(1f))
                Badge(if (info.device.isNotBlank()) "فعال" else "ساخته نشده", if (info.device.isNotBlank()) K.ok else K.rose)
            }
            Spacer(Modifier.height(10.dp))
            KeyLine("شناسه‌ی دستگاه", info.device.take(8).ifBlank { "—" } + if (info.device.length > 8) "…" else "")
            KeyLine("آی‌پی داخلی", info.ipv4.ifBlank { "—" })
            KeyLine("کلید WireGuard / Amnezia", if (info.wireguard) "✓ آماده" else "هنوز نه")
            KeyLine("گواهی MASQUE", if (info.masque) "✓ آماده" else "هنوز نه")
            Spacer(Modifier.height(8.dp))
            Text("اکانت رایگان و کلیدها فقط روی همین گوشی است. اکانت تازه یعنی آی‌پی داخلی و کلید تازه.",
                style = T.small, color = K.rose)
            Spacer(Modifier.height(10.dp))
            SmallButton(if (busy) "در حال ساخت…" else "ساختن اکانت تازه", if (busy) K.rose else K.ember, !locked && !busy) {
                confirm = true
            }
            account?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = T.small, color = if (it.startsWith("ساخته نشد")) K.ember else K.ok)
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = K.wine,
            title = { Text("اکانت تازه بسازم؟", style = T.strong, color = K.champagne) },
            text = { Text("اکانت فعلی از این گوشی پاک می‌شود و همین الان یک اکانت تازه در کلادفلر ساخته می‌شود (برای پروتکلی که انتخاب کردی).", style = T.body, color = K.rose) },
            confirmButton = { TextButton({ confirm = false; onForget() }) { Text("پاک کن", style = T.strong, color = K.ember) } },
            dismissButton = { TextButton({ confirm = false }) { Text("نه", style = T.strong, color = K.champagne) } },
        )
    }
}

@Composable
private fun KeyLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = T.small, color = K.rose, modifier = Modifier.weight(1f))
        Text(value, style = T.small.copy(textDirection = TextDirection.Ltr), color = K.champagne)
    }
}

@Composable
private fun Section(title: String, hint: String, content: @Composable () -> Unit) {
    Text(title, style = T.strong, color = K.champagne)
    Text(hint, style = T.small, color = K.rose)
    Spacer(Modifier.height(8.dp))
    content()
    Spacer(Modifier.height(20.dp))
}

/** A capsule of equal segments; the chosen one is gold. */
@Composable
private fun <E> Choice(items: List<E>, selected: E, name: (E) -> String, enabled: Boolean, onPick: (E) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(K.wine)
            .border(1.dp, K.wineLine, RoundedCornerShape(24.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { e ->
            val on = e == selected
            Box(
                Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp))
                    .background(if (on) K.gold else Color.Transparent)
                    .clickable(enabled = enabled) { onPick(e) },
                contentAlignment = Alignment.Center,
            ) { Text(name(e), style = T.small, color = if (on) K.night else K.champagne, maxLines = 1) }
        }
    }
}

@Composable
private fun Toggle(title: String, hint: String, value: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = T.strong, color = K.champagne)
            Text(hint, style = T.small, color = K.rose)
        }
        Spacer(Modifier.width(12.dp))
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
            SmallButton("کپی گزارش", K.gold, lines.isNotEmpty()) { copy(ctx, lines.joinToString("\n")) }
            Spacer(Modifier.width(6.dp))
            SmallButton("پاک کردن", K.rose, lines.isNotEmpty()) { Engine.clearLog() }
        }
        Spacer(Modifier.height(10.dp))
        // summary chips
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Badge("Aether " + (Engine.coreVersion?.let { "v$it" } ?: "—"), K.gold)
            Badge((if (s.gamer || options.gamer) "گیمر · " else "") + (s.transport ?: if (options.gamer) options.gameTransport else options.transport).title, K.champagne)
            if (Engine.chainText.isNotBlank() && !options.gamer) Badge(Engine.chainText, K.rose)
        }
        Spacer(Modifier.height(10.dp))
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            LazyColumn(
                Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Color(0xFF0E0408))
                    .border(1.dp, K.wineLine, RoundedCornerShape(16.dp)).padding(12.dp),
                state = list,
            ) {
                if (lines.isEmpty()) item {
                    Text("هنوز چیزی نیست. وصل شو تا مراحل این‌جا بیاید.", style = T.body, color = K.rose)
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
private val GOOD = Regex("""\[\+]|validated|verified|still works|ready|passed""", RegexOption.IGNORE_CASE)
private val BAD = Regex("""\[-]|error|failed|refused|timed out|no usable|did not""", RegexOption.IGNORE_CASE)

/** Terminal colours: time grey, addresses gold, success green, errors red, the rest champagne. */
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
