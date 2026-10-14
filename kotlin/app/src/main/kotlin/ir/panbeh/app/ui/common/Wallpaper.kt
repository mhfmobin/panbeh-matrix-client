package ir.panbeh.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.unit.dp
import ir.panbeh.app.ui.theme.LocalWallpaper
import ir.panbeh.app.ui.theme.Panbeh
import ir.panbeh.app.ui.theme.mix

// the web app's doodle tile (160×160 viewBox)
private val DOODLE = listOf(
    "M20 30c0-6 8-8 10-2 2-6 10-4 10 2 0 7-10 12-10 12s-10-5-10-12z",
    "M111 30a9 9 0 1 0 18 0a9 9 0 1 0 -18 0",
    "M70 70l4 9 10 1-7 7 2 10-9-5-9 5 2-10-7-7 10-1z",
    "M20 110h24v18H20zM20 110l12 10 12-10",
    "M110 100c10-10 26 6 16 16l-10 10",
    "M130 70q5-10 10 0t10 0",
    "M70 140a8 8 0 1 0 16 0",
).map { PathParser().parsePathString(it).toPath() }

/** The chat background: doodle, gradient or plain, tinted by the accent (pref `wallpaper`). */
@Composable
fun Wallpaper(modifier: Modifier = Modifier) {
    val c = Panbeh.colors
    when (LocalWallpaper.current) {
        "plain" -> Box(modifier.background(c.chatBg))
        "gradient" -> Canvas(modifier.background(c.chatBg)) {
            fun blob(x: Float, y: Float, color: Color, r: Float) = drawRect(
                Brush.radialGradient(listOf(color, Color.Transparent), Offset(size.width * x, size.height * y), size.maxDimension * r),
            )
            blob(.15f, .2f, c.accent.copy(alpha = .45f), .45f)
            blob(.85f, .3f, Color(0xFFF6C177).copy(alpha = .45f), .45f)
            blob(.3f, .85f, Color(0xFF7BC8A4).copy(alpha = .45f), .5f)
            blob(.8f, .8f, Color(0xFFE0578B).copy(alpha = .35f), .5f)
        }
        else -> Canvas(modifier.background(Brush.linearGradient(listOf(mix(c.accent, .22f, c.chatBg), c.chatBg, c.chatBg)))) {
            val tile = 160.dp.toPx()
            val scale = tile / 160f
            val stroke = Stroke(width = 1.6f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val ink = (if (c.dark) Color.White else Color.Black).copy(alpha = if (c.dark) .05f else .07f)
            var y = 0f
            while (y < size.height) {
                var x = 0f
                while (x < size.width) {
                    translate(x, y) {
                        drawContext.transform.scale(scale, scale, Offset.Zero)
                        DOODLE.forEach { drawPath(it, ink, style = Stroke(width = 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
                        drawContext.transform.scale(1 / scale, 1 / scale, Offset.Zero)
                    }
                    x += tile
                }
                y += tile
            }
        }
    }
}
