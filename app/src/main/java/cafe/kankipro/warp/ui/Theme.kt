package cafe.kankipro.warp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.sp
import cafe.kankipro.warp.R

/**
 * AMOLED black with a blue cast: the screen is off where nothing is drawn, and one electric blue
 * carries every action. The gamer mode swaps the accent for a cyan, so the mode shows at a glance.
 * (The old names stay so every screen keeps compiling: gold is the accent, wine the surface.)
 */
object K {
    val night = Color(0xFF000510)     // background: AMOLED black, blue cast
    val wine = Color(0xFF08111F)      // raised surfaces
    val wineLine = Color(0xFF15243A)  // hairlines, idle ring segments
    val gold = Color(0xFF4D9FFF)      // the accent: actions, proven stages
    val champagne = Color(0xFFE6EEFF) // primary text
    val rose = Color(0xFF7F90AE)      // secondary text
    val ember = Color(0xFFF87171)     // failure
    val ok = Color(0xFF34D399)        // healthy: good ping, success lines
    val warn = Color(0xFFFBBF24)      // middling ping
    val amber = Color(0xFF2EE6D6)     // the gamer mode's accent (cyan)
    val ash = Color(0xFF5B6B86)       // timestamps in the log
    val glass = Color(0x6608111F)     // tile fill over the background
}

val Vazir = FontFamily(
    Font(R.font.vazir_regular, FontWeight.Normal),
    Font(R.font.vazir_medium, FontWeight.Medium),
    Font(R.font.vazir_bold, FontWeight.Bold),
    Font(R.font.vazir_black, FontWeight.Black),
)

/** Persian first: a line that starts with a Latin word (WireGuard, MASQUE) must still read right to left. */
private val rtl = TextDirection.Rtl

object T {
    val title = TextStyle(fontFamily = Vazir, fontWeight = FontWeight.Black, fontSize = 20.sp, textDirection = rtl)
    val big = TextStyle(fontFamily = Vazir, fontWeight = FontWeight.Bold, fontSize = 28.sp, textDirection = rtl)
    val body = TextStyle(fontFamily = Vazir, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 22.sp, textDirection = rtl)
    val strong = TextStyle(fontFamily = Vazir, fontWeight = FontWeight.Bold, fontSize = 16.sp, textDirection = rtl)
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 20.sp, textDirection = TextDirection.Ltr)
    val small = TextStyle(fontFamily = Vazir, fontWeight = FontWeight.Medium, fontSize = 12.sp, textDirection = rtl)
}

@Composable
fun KankiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = K.gold, onPrimary = K.night,
            background = K.night, onBackground = K.champagne,
            surface = K.wine, onSurface = K.champagne,
            error = K.ember,
        ),
        content = content,
    )
}
