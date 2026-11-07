package cafe.kankipro.warp.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cafe.kankipro.warp.core.ConnState
import cafe.kankipro.warp.core.Phase
import cafe.kankipro.warp.core.Stage

/**
 * The connect button seen as a warp tunnel head-on. The disc is the phone; each ring around it is
 * one stage on the way out (core, phone tunnel, Cloudflare gateway, internet), innermost first.
 *
 * Light on purpose: no rotation and no per-frame geometry. Idle, the rings breathe (only their
 * alpha changes, slowly); connecting, a ring turns gold the moment its stage is proven, from the
 * centre out, and only the ring being worked on pulses; connected, everything is still.
 */
@Composable
fun Portal(state: ConnState, size: Dp, onTap: () -> Unit) {
    val t = rememberInfiniteTransition(label = "portal")
    val breath by t.animateFloat(0.45f, 1f, infiniteRepeatable(tween(2600), RepeatMode.Reverse), label = "breath")
    val pulse by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
    val rings = Stage.entries.size
    val idle = state.phase == Phase.IDLE
    val connecting = state.phase == Phase.CONNECTING
    val running = state.phase == Phase.RUNNING
    val failed = state.phase == Phase.ERROR
    val gold = if (state.gamer) K.amber else K.gold

    val label = when (state.phase) {
        Phase.RUNNING -> "قطع"
        Phase.CONNECTING -> "لغو"
        Phase.STOPPING -> "…"
        else -> "وصل شو"
    }

    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(this.size.width / 2, this.size.height / 2)
            val outer = this.size.minDimension / 2
            val disc = outer * 0.42f
            val step = (outer - disc) / rings
            if (running) {
                drawCircle(Brush.radialGradient(listOf(gold.copy(alpha = 0.22f), Color.Transparent), c, outer), outer, c)
            }
            for (i in 0 until rings) {
                val r = disc + step * (i + 0.6f)
                val stroke = 2.dp.toPx() + i * 0.6f.dp.toPx()
                val proven = i < state.proven
                val active = connecting && i == state.proven
                val broken = failed && i == state.proven
                when {
                    proven -> drawCircle(gold, r, c, style = Stroke(stroke * 1.2f))
                    active -> drawCircle(gold.copy(alpha = pulse), r, c, style = Stroke(stroke * 1.2f))
                    broken -> drawCircle(
                        K.ember, r, c,
                        style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
                    )
                    idle -> drawCircle(K.wineLine.copy(alpha = breath * (1f - i * 0.12f)), r, c, style = Stroke(stroke))
                    else -> drawCircle(K.wineLine, r, c, style = Stroke(stroke))
                }
            }
        }
        val discSize = size * 0.42f
        Box(
            Modifier
                .size(discSize)
                .clip(CircleShape)
                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onTap)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    Brush.radialGradient(
                        if (running) listOf(gold, Color(0xFFB8862A)) else listOf(K.wine, K.night),
                        center, this.size.minDimension / 2
                    )
                )
                drawCircle(if (running) gold else K.wineLine, style = Stroke(1.5.dp.toPx()))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, style = T.strong, color = if (running) K.night else K.champagne, textAlign = TextAlign.Center)
            }
        }
    }
}
