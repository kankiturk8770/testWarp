package cafe.kankipro.warp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.kankipro.warp.core.ConnState
import cafe.kankipro.warp.core.Engine
import cafe.kankipro.warp.core.IpFamily
import cafe.kankipro.warp.core.Options
import cafe.kankipro.warp.core.Phase
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
                    Tab.LOG -> LogScreen()
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
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("کانکی وارپ", style = T.title, color = K.champagne)
            Spacer(Modifier.weight(1f))
            Text(
                if (s.egress?.warp == "plus") "WARP+" else "WARP",
                style = T.small, color = K.rose,
            )
        }
        Spacer(Modifier.height(8.dp))
        Portal(s, 264.dp, onToggle)
        Spacer(Modifier.height(16.dp))
        Headline(s)
        Spacer(Modifier.height(16.dp))
        StageRow(s)
        Spacer(Modifier.height(24.dp))
        if (s.phase == Phase.RUNNING) Facts(s) else TransportPicker(options, s.phase == Phase.IDLE || s.phase == Phase.ERROR, onOptions)
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
    val sub = when (s.phase) {
        Phase.RUNNING -> s.transport?.title.orEmpty()
        Phase.CONNECTING -> s.message
        Phase.ERROR -> s.error.orEmpty()
        else -> "دکمه را بزن؛ اولین بار یک اکانت رایگان WARP ساخته می‌شود."
    }
    Text(title, style = T.big, color = if (s.phase == Phase.ERROR) K.ember else K.champagne)
    Spacer(Modifier.height(4.dp))
    Text(sub, style = T.body, color = K.rose, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    if (s.phase == Phase.CONNECTING && s.attempts > 1) {
        Spacer(Modifier.height(4.dp))
        Text(
            "پروتکل ${fa(s.attempt)} از ${fa(s.attempts)}: ${s.transport?.title.orEmpty()}",
            style = T.small, color = K.gold,
        )
    }
}

/** The four stages, named, so the rings of the portal can be read. */
@Composable
private fun StageRow(s: ConnState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Stage.entries.forEachIndexed { i, st ->
            val color = when {
                i < s.proven -> K.gold
                s.phase == Phase.ERROR && i == s.proven -> K.ember
                s.phase == Phase.CONNECTING && i == s.proven -> K.champagne
                else -> K.rose.copy(alpha = 0.5f)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(6.dp))
                Text(st.title, style = T.small, color = color)
            }
        }
    }
}

@Composable
private fun Facts(s: ConnState) {
    val t by Engine.traffic.collectAsState()
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(s.startedAt) {
        while (true) { now = SystemClock.elapsedRealtime(); delay(1000) }
    }
    val e = s.egress
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(K.wine).padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Fact("آی‌پی خروجی", e?.ip ?: "—", ltr = true)
        Fact("محل خروج", listOfNotNull(e?.loc?.let(::flag), e?.loc, e?.colo?.takeIf { it.isNotBlank() }?.let { "($it)" }).joinToString(" "), ltr = true)
        Fact("پینگ", e?.pingMs?.let { "${fa(it.toInt())} میلی‌ثانیه" } ?: "—")
        Fact("دروازهٔ کلادفلر", s.gateway.ifBlank { "—" }, ltr = true)
        Fact("دانلود / آپلود", "${bytes(t.down)} / ${bytes(t.up)}")
        Fact("سرعت", "↓ ${bytes(t.downRate)}/s   ↑ ${bytes(t.upRate)}/s")
        Fact("مدت اتصال", clock(((now - s.startedAt) / 1000).coerceAtLeast(0)), last = true)
    }
}

@Composable
private fun Fact(label: String, value: String, ltr: Boolean = false, last: Boolean = false) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = T.body, color = K.rose)
        Spacer(Modifier.weight(1f))
        Text(
            value, style = T.strong.copy(textDirection = if (ltr) TextDirection.Ltr else TextDirection.Rtl),
            color = K.champagne,
        )
    }
    if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(K.wineLine))
}

@Composable
private fun TransportPicker(options: Options, enabled: Boolean, onOptions: (Options) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text("راه رسیدن به کلادفلر", style = T.strong, color = K.champagne)
        Spacer(Modifier.height(4.dp))
        Text(
            if (options.autoFallback) "اگر این یکی جواب نداد، دو پروتکل دیگر خودکار امتحان می‌شود."
            else "فقط همین پروتکل امتحان می‌شود.",
            style = T.small, color = K.rose,
        )
        Spacer(Modifier.height(12.dp))
        Transport.entries.forEach { t ->
            val on = t == options.transport
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp))
                    .border(1.dp, if (on) K.gold else K.wineLine, RoundedCornerShape(16.dp))
                    .background(if (on) K.wine else Color.Transparent)
                    .clickable(enabled = enabled) { onOptions(options.copy(transport = t)) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(t.title, style = T.strong, color = if (on) K.gold else K.champagne)
                    Text(t.hint, style = T.small, color = K.rose)
                }
                Box(
                    Modifier.size(18.dp).clip(CircleShape)
                        .border(2.dp, if (on) K.gold else K.wineLine, CircleShape)
                        .padding(4.dp).clip(CircleShape).background(if (on) K.gold else Color.Transparent)
                )
            }
        }
    }
}

// ------------------------------------------------------------ settings

@Composable
private fun Settings(options: Options, onOptions: (Options) -> Unit, onForget: () -> Unit) {
    val s by Engine.state.collectAsState()
    val locked = s.phase == Phase.CONNECTING || s.phase == Phase.RUNNING
    var confirm by remember { mutableStateOf(false) }
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
        Toggle("پروتکل جایگزین خودکار", "اگر پروتکل انتخابی جواب نداد، بقیه را امتحان کن.", options.autoFallback, !locked) {
            onOptions(options.copy(autoFallback = it))
        }
        Spacer(Modifier.height(24.dp))
        Text("اکانت WARP", style = T.strong, color = K.champagne)
        Spacer(Modifier.height(4.dp))
        Text(
            "اکانت رایگان و کلیدها فقط روی همین گوشی است. اکانت تازه یعنی آی‌پی داخلی و کلید تازه.",
            style = T.small, color = K.rose,
        )
        Spacer(Modifier.height(8.dp))
        val account by Engine.account.collectAsState()
        val busy = account?.startsWith("در حال") == true
        Box(
            Modifier.clip(RoundedCornerShape(14.dp)).border(1.dp, if (busy) K.wineLine else K.ember, RoundedCornerShape(14.dp))
                .clickable(enabled = !locked && !busy) { confirm = true }.heightIn(min = 44.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) { Text(if (busy) "در حال ساخت…" else "ساختن اکانت تازه", style = T.strong, color = if (busy) K.rose else K.ember) }
        account?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = T.body, color = if (it.startsWith("ساخته نشد")) K.ember else K.gold)
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
private fun Section(title: String, hint: String, content: @Composable () -> Unit) {
    Text(title, style = T.strong, color = K.champagne)
    Text(hint, style = T.small, color = K.rose)
    Spacer(Modifier.height(8.dp))
    content()
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun <E> Choice(items: List<E>, selected: E, name: (E) -> String, enabled: Boolean, onPick: (E) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.wine).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { e ->
            val on = e == selected
            Box(
                Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp))
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
private fun LogScreen() {
    val lines by Engine.log.collectAsState()
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
            TextButton({
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("log", lines.joinToString("\n")))
            }) { Text("کپی", style = T.strong, color = K.gold) }
            TextButton({ Engine.clearLog() }) { Text("پاک کردن", style = T.strong, color = K.rose) }
        }
        Spacer(Modifier.height(8.dp))
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            LazyColumn(
                Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(K.wine).padding(12.dp),
                state = list,
            ) {
                if (lines.isEmpty()) item {
                    Text("هنوز چیزی نیست. وصل شو تا مراحل اینجا بیاید.", style = T.body, color = K.rose)
                }
                items(lines) { l ->
                    Text(
                        l, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp,
                        color = when {
                            "error" in l.lowercase() || "[-]" in l -> K.ember
                            "[+]" in l || "verified" in l -> K.gold
                            else -> K.champagne.copy(alpha = 0.8f)
                        },
                    )
                }
            }
        }
    }
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

fun clock(sec: Long): String = "%02d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60)

/** Flag emoji from a two-letter country code. */
fun flag(cc: String): String? {
    if (cc.length != 2 || !cc.all { it.isLetter() }) return null
    val u = cc.uppercase()
    return String(Character.toChars(0x1F1E6 + (u[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (u[1] - 'A')))
}
