package cafe.kankipro.warp.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.kankipro.warp.core.Colos
import cafe.kankipro.warp.core.Engine
import cafe.kankipro.warp.core.IpTest
import cafe.kankipro.warp.core.IpTest.Kind
import cafe.kankipro.warp.core.Options
import cafe.kankipro.warp.core.Phase
import cafe.kankipro.warp.core.WgScan

/**
 * «تست آی‌پی», two pages that never mix: گیمینگ and کاربر عادی.
 * Gaming: the scan card, the golden list (a row that slides sideways; the arrow opens all of it),
 * then every working gateway behind an arrow: countries as chips that slide sideways, the chosen
 * country's addresses as one list, lowest ping first, any ping.
 * Normal: tick which protocols to scan, one scan card, then each protocol in its own closed card
 * with the same country chips. Tapping an address offers what it can be: the gamer's gateway, or the
 * fixed IP of each protocol that can use it, or a copy.
 */
@Composable
internal fun IpTestScreen(
    options: Options,
    onOptions: (Options) -> Unit,
    onScan: (List<Kind>) -> Unit,
    onImport: (Kind, List<String>) -> Unit,
    onConnect: (WgScan.Hit) -> Unit,
) {
    val ctx = LocalContext.current
    val st by IpTest.state.collectAsState()
    val conn by Engine.state.collectAsState()
    val free = conn.phase == Phase.IDLE || conn.phase == Phase.ERROR
    LaunchedEffect(Unit) { IpTest.load(ctx) }
    var page by rememberSaveable { mutableStateOf(if (st.kind != null && st.kind != Kind.GAMING) 1 else 0) }
    var action by remember { mutableStateOf<IpTest.Found?>(null) }
    var importing by remember { mutableStateOf<Kind?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("تست آی‌پی", style = T.title, color = K.champagne)
                Text(
                    if (free) "شبکه: " + st.network.removePrefix("cell:").replace("wifi", "وای‌فای") else "برای اسکن اول قطع کن",
                    style = T.small.copy(fontSize = 11.sp), color = if (free) K.rose else K.warn,
                )
            }
            Badge("بایگانی ${fa(st.archived)}", K.rose)
        }
        Spacer(Modifier.height(12.dp))
        Segments(page) { page = it }
        Spacer(Modifier.height(14.dp))
        if (page == 0) GamingPage(st, free, onScan, { importing = Kind.GAMING }) { action = it }
        else NormalPage(st, free, onScan, { importing = Kind.H2 }) { action = it }
        if (!st.running && st.result != null) {
            Spacer(Modifier.height(10.dp))
            Text(st.result!!, style = T.small.copy(fontSize = 11.sp), color = K.rose)
        }
        if (st.archived > 0 && !st.running) {
            Spacer(Modifier.height(14.dp))
            SmallButton("پاک کردن بایگانی", K.ember, true) { IpTest.clear(ctx) }
        }
        Spacer(Modifier.height(24.dp))
    }
    importing?.let { k ->
        ImportDialog(k, onClose = { importing = null }) { kind, list -> importing = null; onImport(kind, list) }
    }
    action?.let { f ->
        ActionDialog(f, free, onClose = { action = null },
            onConnect = { onConnect(f.hit()); action = null },
            onPin = { t ->
                onOptions(options.copy(pins = options.pins + (t to f.endpoint), pinPing = options.pinPing + (t to f.pingMs)))
                Toast.makeText(ctx, "آی‌پی ثابت ${t.title} شد", Toast.LENGTH_SHORT).show()
                action = null
            },
            onCopy = { copy(ctx, f.endpoint); action = null })
    }
}

/** «گیمینگ» | «کاربر عادی»: one capsule, the pill slides. */
@Composable
private fun Segments(page: Int, onPick: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(21.dp)).background(K.wine)
            .border(1.dp, K.wineLine, RoundedCornerShape(21.dp)).padding(3.dp),
    ) {
        listOf(Triple("گیمینگ", Ic.gamepad, K.amber), Triple("کاربر عادی", Ic.shield, K.gold)).forEachIndexed { i, (name, icon, color) ->
            val on = page == i
            Row(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(18.dp)).background(if (on) color else Color.Transparent)
                    .clickable { onPick(i) },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, tint = if (on) K.night else K.rose, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
                Text(name, style = T.strong.copy(fontSize = 14.sp), color = if (on) K.night else K.champagne)
            }
        }
    }
}

// ---------------------------------------------------------------- gaming

@Composable
private fun GamingPage(st: IpTest.State, free: Boolean, onScan: (List<Kind>) -> Unit, onImport: () -> Unit, onTap: (IpTest.Found) -> Unit) {
    val list = st.of(Kind.GAMING)
    val golden = IpTest.golden(list)
    val working = IpTest.working(list)
    ScanCard(
        title = "اسکن گیمینگ", sub = "بازه‌های WireGuard · دست‌دادن واقعی · شهر هر دروازه",
        color = K.amber, icon = Ic.gamepad, st = st, mine = { it == Kind.GAMING }, enabled = free,
    ) { onScan(listOf(Kind.GAMING)) }
    Spacer(Modifier.height(8.dp))
    ImportButton(K.amber, free && !st.running, onImport)
    Spacer(Modifier.height(18.dp))

    // the golden list: a sideways row; the arrow opens all of it
    var allGolden by rememberSaveable { mutableStateOf(false) }
    SectionHeader(Ic.star, "فهرست طلایی", "افت صفر یا کم، پینگ تا ${fa(IpTest.MAX_GAME_PING)}", golden.size, K.amber,
        open = allGolden, canOpen = golden.size > 2) { allGolden = !allGolden }
    Spacer(Modifier.height(8.dp))
    when {
        golden.isEmpty() -> EmptyNote("هنوز خالی؛ «اسکن گیمینگ» را بزن")
        allGolden -> golden.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { f -> GoldCard(f, Modifier.weight(1f)) { onTap(f) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
            items(golden, key = { it.endpoint }) { f -> GoldCard(f, Modifier.width(150.dp)) { onTap(f) } }
        }
    }
    Spacer(Modifier.height(18.dp))

    // every working gateway, any ping, behind an arrow
    var all by rememberSaveable { mutableStateOf(false) }
    SectionHeader(Ic.globe, "همه‌ی دروازه‌های کارکن", "کشور به کشور، پینگ کم به زیاد (۳۰۰ و ۱۰۰۰ هم)", working.size, K.amber,
        open = all, canOpen = working.isNotEmpty()) { all = !all }
    AnimatedVisibility(all) {
        Column { Spacer(Modifier.height(8.dp)); CountryBrowser(working, K.amber, onTap) }
    }
    val alive = list.count { !it.proto && it.tcp }
    if (alive > 0) {
        Spacer(Modifier.height(8.dp))
        Text("${fa(alive)} آی‌پی دیگر فقط زنده‌اند (TCP)؛ در بایگانی برای روز سخت", style = T.small.copy(fontSize = 11.sp), color = K.rose)
    }
    Spacer(Modifier.height(10.dp))
    Text("کلادفلر انیکست است: این‌که کدام شهرها پیدا شود دست مسیر شبکه‌ی توست، نه اسکن. همه‌ی بازه‌ها امتحان می‌شود.",
        style = T.small.copy(fontSize = 11.sp), color = K.rose.copy(alpha = 0.75f))
}

/** A golden gateway as a small card: flag and city, big ping, loss and jitter, the address. */
@Composable
private fun GoldCard(f: IpTest.Found, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(K.amber.copy(alpha = 0.14f), K.wine)))
            .border(1.dp, K.amber.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(12.dp),
    ) {
        Text(place(f), style = T.small, color = K.champagne, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text("${f.pingMs} ms", style = T.title.copy(fontSize = 22.sp, textDirection = TextDirection.Ltr), color = pingColor(f.pingMs))
        Text("افت ${fa(f.lossPct)}٪ · لرزش ${fa(f.jitterMs)}", style = T.small.copy(fontSize = 10.sp),
            color = if (f.lossPct == 0) K.ok else K.warn)
        Spacer(Modifier.height(4.dp))
        Text(f.endpoint, style = T.small.copy(fontSize = 10.sp, textDirection = TextDirection.Ltr), color = K.rose, maxLines = 1)
    }
}

// ---------------------------------------------------------------- normal

@Composable
private fun NormalPage(st: IpTest.State, free: Boolean, onScan: (List<Kind>) -> Unit, onImport: () -> Unit, onTap: (IpTest.Found) -> Unit) {
    var picked by rememberSaveable { mutableStateOf(Kind.NORMAL.map { it.name }) }
    val kinds = Kind.NORMAL.filter { it.name in picked }
    Text("برای کدام پروتکل‌ها؟", style = T.small, color = K.rose)
    Spacer(Modifier.height(6.dp))
    Kind.NORMAL.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { k ->
                val on = k.name in picked
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (on) K.wine else Color.Transparent)
                        .border(1.dp, if (on) K.gold else K.wineLine, RoundedCornerShape(12.dp))
                        .clickable(enabled = !st.running) { picked = if (on) picked - k.name else picked + k.name }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)).background(if (on) K.gold else Color.Transparent)
                            .border(1.5.dp, if (on) K.gold else K.rose, RoundedCornerShape(5.dp)),
                        contentAlignment = Alignment.Center,
                    ) { if (on) Icon(Ic.check, null, tint = K.night, modifier = Modifier.size(13.dp)) }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(k.title, style = T.strong.copy(fontSize = 13.sp), color = if (on) K.gold else K.champagne, maxLines = 1)
                        Text(k.sub, style = T.small.copy(fontSize = 10.sp), color = K.rose, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    Text("Psiphon آی‌پی کلادفلر ندارد؛ اسکن و آی‌پی ثابت لازم ندارد.", style = T.small.copy(fontSize = 10.sp), color = K.rose.copy(alpha = 0.75f))
    Spacer(Modifier.height(10.dp))
    ScanCard(
        title = "اسکن کاربر عادی", sub = if (kinds.isEmpty()) "اول یک پروتکل را تیک بزن" else kinds.joinToString(" · ") { it.title },
        color = K.gold, icon = Ic.radar, st = st, mine = { it != Kind.GAMING }, enabled = free && kinds.isNotEmpty(),
    ) { onScan(kinds) }
    Spacer(Modifier.height(8.dp))
    ImportButton(K.gold, free && !st.running, onImport)
    Spacer(Modifier.height(14.dp))
    Kind.NORMAL.forEach { k ->
        ProtocolResults(k, st.of(k), onTap)
        Spacer(Modifier.height(8.dp))
    }
}

/** One protocol's results in a card of its own, closed until its arrow is tapped. */
@Composable
private fun ProtocolResults(k: Kind, list: List<IpTest.Found>, onTap: (IpTest.Found) -> Unit) {
    var open by rememberSaveable(k.name) { mutableStateOf(false) }
    val working = IpTest.working(list)
    val alive = list.count { !it.proto && it.tcp }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(K.wine)
            .border(1.dp, if (open) K.gold.copy(alpha = 0.6f) else K.wineLine, RoundedCornerShape(16.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = working.isNotEmpty()) { open = !open }.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(k.title, style = T.strong.copy(fontSize = 14.sp), color = K.champagne)
                Text(
                    when {
                        working.isEmpty() && list.isEmpty() -> "هنوز اسکن نشده"
                        working.isEmpty() -> "چیزی کار نکرد" + if (alive > 0) " · ${fa(alive)} فقط زنده" else ""
                        else -> "بهترین ${working.first().pingMs} ms" + (if (alive > 0) " · ${fa(alive)} فقط زنده" else "") +
                            " · برای " + k.transports.joinToString("، ") { it.title }
                    },
                    style = T.small.copy(fontSize = 10.sp), color = K.rose, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            CountPill(working.size, K.gold)
            Spacer(Modifier.width(4.dp))
            Arrow(open, if (working.isNotEmpty()) K.gold else K.wineLine)
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) { CountryBrowser(working, K.gold, onTap) }
        }
    }
}

// ---------------------------------------------------------------- shared

/**
 * Countries as chips that slide sideways (flag, name, count; «همه» first), and the chosen country's
 * addresses below as one plain list, lowest ping first.
 */
@Composable
private fun CountryBrowser(list: List<IpTest.Found>, accent: Color, onTap: (IpTest.Found) -> Unit) {
    val countries = IpTest.byCountry(list)
    var cc by rememberSaveable { mutableStateOf("") }
    if (cc.isNotEmpty() && countries.none { it.first == cc }) cc = ""
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
        item { Chip("همه", list.size, cc == "", accent) { cc = "" } }
        items(countries, key = { it.first }) { (code, l) ->
            Chip(listOfNotNull(flag(code), if (code == "??") "نامعلوم" else Colos.countryName(code)).joinToString(" "), l.size, cc == code, accent) { cc = code }
        }
    }
    Spacer(Modifier.height(6.dp))
    val shown = if (cc.isEmpty()) list else countries.firstOrNull { it.first == cc }?.second.orEmpty()
    shown.forEach { f -> AddrRow(f, accent) { onTap(f) } }
}

@Composable
private fun Chip(text: String, n: Int, on: Boolean, accent: Color, showCount: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) accent else K.wine)
            .border(1.dp, if (on) accent else K.wineLine, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = T.small.copy(fontSize = 12.sp), color = if (on) K.night else K.champagne, maxLines = 1)
        if (showCount) {
            Spacer(Modifier.width(6.dp))
            Text(fa(n), style = T.small.copy(fontSize = 11.sp), color = if (on) K.night.copy(alpha = 0.7f) else K.rose)
        }
    }
}

/** One address on one line: ping dot, city, the address, loss; ping on the far side. */
@Composable
private fun AddrRow(f: IpTest.Found, accent: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(pingColor(f.pingMs)))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(place(f), style = T.small.copy(fontSize = 12.sp), color = K.champagne, maxLines = 1)
            Text(f.endpoint, style = T.small.copy(fontSize = 11.sp, textDirection = TextDirection.Ltr), color = K.rose, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${f.pingMs} ms", style = T.strong.copy(fontSize = 14.sp, textDirection = TextDirection.Ltr), color = pingColor(f.pingMs))
            Text(if (f.lossPct < 0) "یک بار" else "افت ${fa(f.lossPct)}٪", style = T.small.copy(fontSize = 10.sp),
                color = if (f.lossPct == 0) K.ok else K.rose)
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(K.wineLine.copy(alpha = 0.5f)))
}

/** The scan key as a card: icon, name, what it scans; while it runs, the three stages and a bar, tap to stop. */
@Composable
private fun ScanCard(
    title: String, sub: String, color: Color, icon: ImageVector, st: IpTest.State, mine: (Kind) -> Boolean,
    enabled: Boolean, onStart: () -> Unit,
) {
    val busy = st.running && st.kind?.let(mine) == true
    val other = st.running && !busy
    val pulse by rememberInfiniteTransition(label = "pulse")
        .animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "p")
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(Brush.horizontalGradient(listOf(color.copy(alpha = if (busy) 0.22f else 0.12f), K.wine)))
            .border(1.dp, if (enabled || busy) color else K.wineLine, RoundedCornerShape(18.dp))
            .clickable(enabled = (enabled && !other) || busy) { if (busy) IpTest.cancel() else onStart() }
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(color.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = color, modifier = Modifier.size(24.dp).alpha(if (busy) pulse else 1f)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (busy) "$title…" else title, style = T.strong.copy(fontSize = 16.sp), color = K.champagne)
                Text(
                    if (busy && st.steps > 1) "${st.kind?.title} · ${fa(st.step)} از ${fa(st.steps)}" else sub,
                    style = T.small.copy(fontSize = 11.sp), color = K.rose, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                Modifier.clip(RoundedCornerShape(14.dp)).background(if (busy) K.ember.copy(alpha = 0.18f) else if (enabled && !other) color else K.wineLine)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(if (busy) "توقف" else "شروع", style = T.strong.copy(fontSize = 13.sp),
                    color = if (busy) K.ember else if (enabled && !other) K.night else K.rose)
            }
        }
        if (busy) {
            Spacer(Modifier.height(12.dp))
            val wg = st.kind?.wireguard == true
            val names = if (wg) listOf("زنده", "پینگ و افت", "شهر") else listOf("زنده", "پینگ و افت")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                names.forEachIndexed { i, n ->
                    val stage = i + 1
                    val c = when { stage < st.stage -> K.ok; stage == st.stage -> color; else -> K.wineLine }
                    Column(Modifier.weight(1f)) {
                        Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(K.wineLine)) {
                            val frac = when {
                                stage < st.stage -> 1f
                                stage == st.stage && st.total > 0 -> st.done.toFloat() / st.total
                                else -> 0f
                            }
                            val anim by animateFloatAsState(frac.coerceIn(0f, 1f), tween(300), label = "f")
                            Box(Modifier.fillMaxWidth(anim).fillMaxHeight().background(c))
                        }
                        Spacer(Modifier.height(3.dp))
                        Text("${fa(stage)}. $n", style = T.small.copy(fontSize = 10.sp), color = if (stage <= st.stage) K.champagne else K.rose)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                when (st.stage) {
                    1 -> "${percent(st.done, st.total)} · ${fa(st.found)} جواب"
                    2 -> "دور ${fa((st.done + 1).coerceAtMost(st.total))} از ${fa(st.total)} · ${fa(st.found)} آی‌پی"
                    else -> "${fa(st.done)} از ${fa(st.total)} بازه · ${fa(st.found)} شهر"
                },
                style = T.small.copy(fontSize = 11.sp), color = color,
            )
        }
    }
}

@Composable
private fun SectionHeader(
    icon: ImageVector, title: String, sub: String, n: Int, color: Color, open: Boolean, canOpen: Boolean, onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = canOpen, onClick = onToggle).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = T.strong, color = color)
            Text(sub, style = T.small.copy(fontSize = 10.sp), color = K.rose, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        CountPill(n, color)
        Spacer(Modifier.width(4.dp))
        Arrow(open, if (canOpen) color else K.wineLine)
    }
}

@Composable
private fun CountPill(n: Int, color: Color) {
    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(fa(n), style = T.small.copy(fontSize = 12.sp), color = color)
    }
}

@Composable
private fun Arrow(open: Boolean, color: Color) {
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(220), label = "arrow")
    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
        Icon(Ic.down, if (open) "بستن" else "باز کردن", tint = color, modifier = Modifier.size(24.dp).rotate(turn))
    }
}

@Composable
private fun EmptyNote(text: String) = Text(text, style = T.small.copy(fontSize = 12.sp), color = K.rose,
    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, K.wineLine, RoundedCornerShape(12.dp)).padding(14.dp),
    textAlign = TextAlign.Center)

private fun place(f: IpTest.Found): String = when {
    !f.proto -> "فقط زنده (TCP)"
    f.colo.isNotBlank() -> listOfNotNull(flag(f.country), Colos.NAMES[f.colo] ?: f.colo).joinToString(" ")
    else -> "شهر نامعلوم"
}

/** What an address can be: the gamer's gateway, each protocol's fixed IP, or a copy. */
@Composable
private fun ActionDialog(
    f: IpTest.Found, free: Boolean, onClose: () -> Unit, onConnect: () -> Unit,
    onPin: (cafe.kankipro.warp.core.Transport) -> Unit, onCopy: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = K.wine,
        title = {
            Column {
                Text(place(f), style = T.strong, color = K.champagne)
                Text(f.endpoint, style = T.small.copy(textDirection = TextDirection.Ltr), color = K.rose)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${f.pingMs} ms · " + (if (f.lossPct < 0) "یک بار سنجیده شد" else "افت ${fa(f.lossPct)}٪ · لرزش ${fa(f.jitterMs)}"),
                    style = T.small, color = pingColor(f.pingMs))
                if (f.kind == Kind.GAMING) WideButton("وصل گیمر با این", Ic.gamepad, K.amber, free, onConnect)
                f.kind.transports.forEach { t -> WideButton("آی‌پی ثابت ${t.title}", Ic.edit, K.gold, true) { onPin(t) } }
                WideButton("کپی آی‌پی:پورت", Ic.copy, K.champagne, true, onCopy)
                if (f.kind == Kind.GAMING && !free) Text("برای وصل گیمر اول قطع کن", style = T.small.copy(fontSize = 11.sp), color = K.warn)
            }
        },
        confirmButton = { TextButton(onClose) { Text("بستن", style = T.strong, color = K.rose) } },
    )
}

@Composable
private fun WideButton(text: String, icon: ImageVector, color: Color, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 42.dp).clip(RoundedCornerShape(12.dp))
            .border(1.dp, if (enabled) color.copy(alpha = 0.7f) else K.wineLine, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (enabled) color else K.wineLine, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = T.strong.copy(fontSize = 13.sp), color = if (enabled) K.champagne else K.rose)
    }
}

/** «آی‌پی از کانال»: a paper plane landing in a tray, on a glowing badge. */
@Composable
private fun ImportButton(color: Color, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(Brush.horizontalGradient(listOf(K.wine, color.copy(alpha = 0.10f))))
            .border(1.dp, if (enabled) color.copy(alpha = 0.5f) else K.wineLine, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelBadge(color, 40)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("آی‌پی از کانال", style = T.strong.copy(fontSize = 14.sp), color = if (enabled) K.champagne else K.rose)
            Text("آی‌پی:پورت‌هایی که کانال‌ها می‌گذارند را بچسبان؛ همین‌جا تست و جدا ذخیره می‌شوند",
                style = T.small.copy(fontSize = 10.sp), color = K.rose, maxLines = 2)
        }
        Icon(Ic.edit, null, tint = if (enabled) color else K.wineLine, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ChannelBadge(color: Color, size: Int) {
    Box(
        Modifier.size(size.dp).clip(CircleShape)
            .background(Brush.linearGradient(listOf(Color(0xFF2AABEE), color))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Ic.inbox, null, tint = Color.White.copy(alpha = 0.35f), modifier = Modifier.size((size * 0.62f).dp))
        Icon(Ic.plane, null, tint = Color.White, modifier = Modifier.size((size * 0.42f).dp).rotate(-28f))
    }
}

/**
 * Paste what a channel posted: every «ip:port» in it is taken (other words are skipped), for one scan
 * at a time, so the lists never mix. Then the same stages as a scan test them.
 */
@Composable
internal fun ImportDialog(start: Kind, onClose: () -> Unit, onGo: (Kind, List<String>) -> Unit) {
    val ctx = LocalContext.current
    var kind by remember { mutableStateOf(start) }
    var text by remember { mutableStateOf("") }
    val found = remember(text, kind) { IpTest.parseList(text, kind) }
    val kinds = if (start == Kind.GAMING) listOf(Kind.GAMING) else Kind.NORMAL
    val accent = if (start == Kind.GAMING) K.amber else K.gold
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = K.wine,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelBadge(accent, 34)
                Spacer(Modifier.width(10.dp))
                Text("آی‌پی از کانال", style = T.strong, color = K.champagne)
            }
        },
        text = {
            Column {
                if (kinds.size > 1) {
                    Text("این آی‌پی‌ها برای کدام است؟", style = T.small, color = K.rose)
                    Spacer(Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(kinds) { k -> Chip(k.title, 0, k == kind, accent, showCount = false) { kind = k } }
                    }
                    Spacer(Modifier.height(8.dp))
                } else {
                    Text("برای گیمینگ (WireGuard / Amnezia)", style = T.small, color = K.rose)
                    Spacer(Modifier.height(8.dp))
                }
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr,
                ) {
                    androidx.compose.material3.OutlinedTextField(
                        text, { text = it }, minLines = 4, maxLines = 8,
                        placeholder = { Text("162.159.192.7:2408\n188.114.97.3:1002\n…", color = K.rose.copy(alpha = 0.6f)) },
                        textStyle = T.small.copy(textDirection = TextDirection.Ltr, color = K.champagne),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accent, unfocusedBorderColor = K.wineLine, cursorColor = accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (found.isEmpty()) (if (kind == Kind.H2) "آی‌پی یا آی‌پی:پورت بچسبان" else "آی‌پی:پورت بچسبان (پورت لازم است)")
                        else "${fa(found.size)} آی‌پی:پورت پیدا شد",
                        style = T.small.copy(fontSize = 11.sp), color = if (found.isEmpty()) K.rose else K.ok, modifier = Modifier.weight(1f),
                    )
                    SmallButton("چسباندن", accent, true) {
                        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.let { text = if (text.isBlank()) it else text + "\n" + it }
                    }
                }
            }
        },
        confirmButton = {
            TextButton({ onGo(kind, found) }, enabled = found.isNotEmpty()) {
                Text("تست و ذخیره", style = T.strong, color = if (found.isNotEmpty()) accent else K.rose)
            }
        },
        dismissButton = { TextButton(onClose) { Text("بستن", style = T.strong, color = K.champagne) } },
    )
}
