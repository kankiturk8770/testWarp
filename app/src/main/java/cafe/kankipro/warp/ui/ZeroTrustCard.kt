package cafe.kankipro.warp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.kankipro.warp.core.ZeroTrust

/**
 * The gamer's Zero Trust card: one field for the enrolment link or token; once joined,
 * the team name and a «حذف» key. While it is off nothing changes: the gamer uses personal WARP.
 */
@Composable
fun ZeroTrustCard(locked: Boolean) {
    val ctx = LocalContext.current
    val z by ZeroTrust.state.collectAsState()
    LaunchedEffect(Unit) { ZeroTrust.refresh(ctx) }
    var link by remember { mutableStateOf("") }
    val on = z.step == ZeroTrust.Step.ON
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(16.dp)).background(K.wine)
            .border(1.dp, if (on) K.ok.copy(alpha = 0.6f) else K.wineLine, RoundedCornerShape(16.dp)).padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Zero Trust · گیمر", style = T.strong, color = K.champagne, modifier = Modifier.weight(1f))
            Badge(if (on) "فعال" else "خاموش", if (on) K.ok else K.rose)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (on) "اول با تیم وصل می‌شود؛ اگر نشد خودش برمی‌گردد به WARP."
            else "لینک یا توکن Zero Trust را بگذار؛ اگر کار نکرد، WARP مثل قبل.",
            style = T.small, color = K.rose,
        )
        Spacer(Modifier.height(10.dp))
        when (z.step) {
            ZeroTrust.Step.ON -> {
                KeyLineSmall("تیم", ltr("${z.team}.cloudflareaccess.com"))
                Spacer(Modifier.height(8.dp))
                SmallButton("حذف", K.ember, !locked) { ZeroTrust.remove(ctx) }
            }
            ZeroTrust.Step.WORKING -> Unit
            ZeroTrust.Step.OFF -> {
                Field("لینک com.cloudflare.warp://… یا توکن", link, KeyboardType.Uri) { link = it.trim() }
                Spacer(Modifier.height(8.dp))
                SmallButton("ثبت", K.gold, !locked && link.length >= 32) { ZeroTrust.join(ctx, link) }
            }
        }
        if (z.message.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(z.message, style = T.small, color = K.amber)
        }
        if (z.error.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(z.error, style = T.small, color = K.ember)
        }
    }
}

@Composable
private fun KeyLineSmall(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = T.small, color = K.rose, modifier = Modifier.weight(1f))
        Text(value, style = T.small, color = K.champagne)
    }
}

/** A one-line field in the card's style; text inside is left to right (team, email, code). */
@Composable
private fun Field(hint: String, value: String, type: KeyboardType, onChange: (String) -> Unit) {
    Column {
        Text(hint, style = T.small, color = K.rose)
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value, onChange, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = type, autoCorrectEnabled = false),
            textStyle = T.mono.copy(color = K.champagne, fontSize = 14.sp, textDirection = TextDirection.Ltr),
            cursorBrush = SolidColor(K.gold),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(K.night)
                .border(1.dp, K.wineLine, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}
