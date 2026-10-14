package ir.panbeh.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import ir.panbeh.app.R

private fun vazir(w: Int) = Font(R.font.vazirmatn, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
val Vazirmatn = FontFamily(vazir(400), vazir(500), vazir(600), vazir(700))

@Composable
fun PanbehTheme(dark: Boolean = isSystemInDarkTheme(), accent: Color = Color(0xFF007DED), content: @Composable () -> Unit) {
    val colors = if (dark) darkColorScheme(primary = accent, background = Color(0xFF181818), surface = Color(0xFF212121))
    else lightColorScheme(primary = accent, background = Color.White, surface = Color.White)
    val base = Typography()
    fun TextStyle.v() = copy(fontFamily = Vazirmatn)
    val type = Typography(
        displayLarge = base.displayLarge.v(), displayMedium = base.displayMedium.v(), displaySmall = base.displaySmall.v(),
        headlineLarge = base.headlineLarge.v(), headlineMedium = base.headlineMedium.v(), headlineSmall = base.headlineSmall.v(),
        titleLarge = base.titleLarge.v(), titleMedium = base.titleMedium.v(), titleSmall = base.titleSmall.v(),
        bodyLarge = base.bodyLarge.v(), bodyMedium = base.bodyMedium.v(), bodySmall = base.bodySmall.v(),
        labelLarge = base.labelLarge.v(), labelMedium = base.labelMedium.v(), labelSmall = base.labelSmall.v(),
    )
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = colors, typography = type, content = content)
    }
}
