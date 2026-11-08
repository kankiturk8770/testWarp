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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cafe.kankipro.warp.core.ConnState
import cafe.kankipro.warp.core.Phase
import cafe.kankipro.warp.core.Stage

/**
 * Connected, everything turns green (a glance tells anyone it is on); in gamer mode the key shows a
 * gamepad on cyan-to-green and the halo glows slowly: game mode is on.
 *
 * The connect button: a power key inside one ring cut into four arcs, one arc per stage of the
 * connection (engine, tunnel, edge, open internet), clockwise from the top. An arc lights when its
 * stage is proven; the one being worked on pulses; a failed one turns red. Connected, the key fills
 * with the accent and a soft halo sits behind it. Idle, only the halo breathes, slowly. Nothing
 * rotates: only alpha animates.
 */
@Composable
fun Portal(state: ConnState, size: Dp, onTap: () -> Unit) {
    val t = rememberInfiniteTransition(label = "reactor")
    val breath by t.animateFloat(0.15f, 0.4f, infiniteRepeatable(tween(2800), RepeatMode.Reverse), label = "breath")
    val pulse by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "pulse")
    val running = state.phase == Phase.RUNNING
    // Connected is green for everyone, so a glance is enough; the gamer's is cyan into green.
    val accent = when {
        running && state.gamer -> K.ok
        running -> K.ok
        state.gamer -> K.amber
        else -> K.gold
    }
    val glow by t.animateFloat(0.25f, 0.5f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "glow")
    val connecting = state.phase == Phase.CONNECTING
    val failed = state.phase == Phase.ERROR
    val label = when (state.phase) {
        Phase.RUNNING -> "قطع"
        Phase.CONNECTING -> "لغو"
        else -> "اتصال"
    }

    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(this.size.width / 2, this.size.height / 2)
            val outer = this.size.minDimension / 2
            // halo
            val haloAlpha = when {
                running && state.gamer -> glow
                running -> 0.32f
                state.phase == Phase.IDLE -> breath
                else -> 0.18f
            }
            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = haloAlpha), Color.Transparent), c, outer), outer, c)
            // the four stage arcs
            val stroke = 6.dp.toPx()
            val r = outer * 0.80f
            val gap = 10f
            val n = Stage.entries.size
            val sweep = 360f / n - gap
            for (i in 0 until n) {
                val color = when {
                    i < state.proven -> accent
                    connecting && i == state.proven -> accent.copy(alpha = pulse)
                    failed && i == state.proven -> K.ember
                    else -> K.wineLine
                }
                drawArc(
                    color, startAngle = -90f + gap / 2 + i * (sweep + gap), sweepAngle = sweep, useCenter = false,
                    topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            // a thin inner guide ring
            drawCircle(K.wineLine.copy(alpha = 0.6f), outer * 0.64f, c, style = Stroke(1.dp.toPx()))
        }
        val key = size * 0.46f
        Box(
            Modifier.size(key).clip(CircleShape)
                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onTap)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    when {
                        running && state.gamer -> Brush.linearGradient(listOf(K.amber, K.ok), Offset.Zero, Offset(this.size.width, this.size.height))
                        running -> Brush.radialGradient(listOf(accent, accent.copy(alpha = 0.78f)), center, this.size.minDimension / 2)
                        else -> Brush.radialGradient(listOf(K.wine, K.night), center, this.size.minDimension / 2)
                    }
                )
                drawCircle(if (running) accent else K.wineLine, style = Stroke(1.5.dp.toPx()))
            }
            Icon(
                if (running && state.gamer) Ic.gamepad else Ic.power, contentDescription = null,
                tint = when {
                    running -> K.night
                    failed -> K.ember
                    connecting -> accent
                    else -> K.champagne
                },
                modifier = Modifier.size(key * 0.38f),
            )
        }
    }
}
