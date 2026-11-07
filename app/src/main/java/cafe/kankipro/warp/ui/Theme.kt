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

/** Kanki's wine and gold, at night. */
object K {
    val night = Color(0xFF16060D)    // background: deep wine, not grey-black
    val wine = Color(0xFF2B0C19)     // raised surfaces
    val wineLine = Color(0xFF45172A) // hairlines and idle rings
    val gold = Color(0xFFE9B949)     // proven / action
    val champagne = Color(0xFFF6E3B4) // primary text
    val rose = Color(0xFFB58A97)     // secondary text
    val ember = Color(0xFFFF5A5F)    // failure
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
