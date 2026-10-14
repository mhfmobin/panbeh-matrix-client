package ir.panbeh.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import ir.panbeh.app.R

private fun vazir(w: Int) = Font(R.font.vazirmatn, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
val Vazirmatn = FontFamily(vazir(300), vazir(400), vazir(500), vazir(600), vazir(700), vazir(800))

/** The web app's CSS tokens (src/styles.css). */
@Immutable
data class PanbehColors(
    val dark: Boolean,
    val accent: Color,
    val bg: Color,
    val panel: Color,
    val hover: Color,
    val text: Color,
    val muted: Color,
    val border: Color,
    val bubbleIn: Color,
    val bubbleOut: Color,
    val outText: Color,
    val outMeta: Color,
    /** day/notice pills over the wallpaper */
    val pill: Color,
    val chatBg: Color,
    /** the ✓✓ of a read message of mine */
    val readTick: Color,
    val danger: Color = Color(0xFFE53935),
    val online: Color = Color(0xFF4FAE4E),
)

/** color-mix(in srgb, a p%, b) */
fun mix(a: Color, p: Float, b: Color) = lerp(b, a, p)

fun panbehColors(dark: Boolean, accent: Color) = if (!dark) PanbehColors(
    dark = false, accent = accent,
    bg = Color.White, panel = Color.White, hover = Color(0xFFF4F4F5), text = Color.Black, muted = Color(0xFF707579),
    border = Color(0xFFE6E6E6), bubbleIn = Color.White, bubbleOut = mix(accent, .14f, Color.White),
    outText = Color.Black, outMeta = mix(accent, .8f, Color.Black), pill = mix(accent, .45f, Color(0xFF506070)),
    chatBg = mix(accent, .12f, Color(0xFFE8EFE0)), readTick = Color(0xFF4FAE4E),
) else PanbehColors(
    dark = true, accent = accent,
    bg = Color(0xFF181818), panel = Color(0xFF212121), hover = Color(0xFF2C2C2C), text = Color.White, muted = Color(0xFFAAAAAA),
    border = Color(0xFF303030), bubbleIn = Color(0xFF212121), bubbleOut = mix(accent, .7f, Color(0xFF212121)),
    outText = Color.White, outMeta = Color.White.copy(alpha = .7f), pill = Color.Black.copy(alpha = .35f),
    chatBg = mix(accent, .18f, Color(0xFF0F0F0F)), readTick = Color.White.copy(alpha = .7f),
)

val LocalColors = staticCompositionLocalOf { panbehColors(false, Color(0xFF007DED)) }
val LocalWallpaper = staticCompositionLocalOf { "doodle" }

object Panbeh {
    val colors: PanbehColors @Composable get() = LocalColors.current
}

private val SENDER_COLORS = listOf(0xFFE17076, 0xFFFAA774, 0xFFA695E7, 0xFF7BC862, 0xFF6EC9CB, 0xFF65AADD, 0xFFEE7AAE).map { Color(it) }

/** Same hash as the web app's colorFor, so a person has the same colour in both. */
fun colorFor(id: String): Color {
    var h = 0
    for (c in id) h = h * 31 + c.code
    return SENDER_COLORS[((h.toLong() and 0xFFFFFFFFL) % SENDER_COLORS.size).toInt()]
}

fun parseColor(hex: String): Color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color(0xFF007DED))

@Composable
fun PanbehTheme(theme: String = "system", accent: String = "#007ded", wallpaper: String = "doodle", content: @Composable () -> Unit) {
    val dark = when (theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val c = panbehColors(dark, parseColor(accent))
    val scheme = if (dark) darkColorScheme(
        primary = c.accent, onPrimary = Color.White, background = c.bg, onBackground = c.text, surface = c.panel, onSurface = c.text,
        surfaceVariant = c.hover, onSurfaceVariant = c.muted, outline = c.border, outlineVariant = c.border,
        surfaceContainer = c.panel, surfaceContainerHigh = c.hover, surfaceContainerLow = c.panel, error = c.danger,
    ) else lightColorScheme(
        primary = c.accent, onPrimary = Color.White, background = c.bg, onBackground = c.text, surface = c.panel, onSurface = c.text,
        surfaceVariant = c.hover, onSurfaceVariant = c.muted, outline = c.border, outlineVariant = c.border,
        surfaceContainer = c.panel, surfaceContainerHigh = c.hover, surfaceContainerLow = c.panel, error = c.danger,
    )
    val base = Typography()
    fun TextStyle.v() = copy(fontFamily = Vazirmatn)
    val type = Typography(
        displayLarge = base.displayLarge.v(), displayMedium = base.displayMedium.v(), displaySmall = base.displaySmall.v(),
        headlineLarge = base.headlineLarge.v(), headlineMedium = base.headlineMedium.v(), headlineSmall = base.headlineSmall.v(),
        titleLarge = base.titleLarge.v(), titleMedium = base.titleMedium.v().copy(fontSize = 16.sp), titleSmall = base.titleSmall.v(),
        bodyLarge = base.bodyLarge.v().copy(fontSize = 15.sp, lineHeight = 22.sp), bodyMedium = base.bodyMedium.v(), bodySmall = base.bodySmall.v(),
        labelLarge = base.labelLarge.v(), labelMedium = base.labelMedium.v(), labelSmall = base.labelSmall.v(),
    )
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, LocalColors provides c, LocalWallpaper provides wallpaper) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
