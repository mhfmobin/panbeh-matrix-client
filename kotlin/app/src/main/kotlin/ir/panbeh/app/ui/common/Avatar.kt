package ir.panbeh.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import ir.panbeh.app.media.Mxc
import ir.panbeh.app.ui.theme.colorFor
import ir.panbeh.app.ui.theme.mix

/** Round avatar: the mxc picture, or the first letter on the person's/room's colour. */
@Composable
fun Avatar(mxc: String?, name: String, id: String, size: Dp = 42.dp, modifier: Modifier = Modifier) {
    val px = with(LocalDensity.current) { (size * 2).roundToPx() }.coerceAtMost(512)
    Box(modifier.size(size).clip(CircleShape)) {
        if (mxc.isNullOrBlank()) Letter(name, id, size)
        else SubcomposeAsyncImage(
            model = Mxc(mxc, px, px), contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(size), loading = { Letter(name, id, size) }, error = { Letter(name, id, size) },
        )
    }
}

@Composable
private fun Letter(name: String, id: String, size: Dp) {
    val c = colorFor(id)
    Box(Modifier.size(size).background(Brush.verticalGradient(listOf(mix(Color.White, .25f, c), c))), contentAlignment = Alignment.Center) {
        val ch = name.removePrefix("@").removePrefix("#").removePrefix("!").trim().let { s ->
            if (s.isEmpty()) "?" else String(Character.toChars(s.codePointAt(0))).uppercase()
        }
        Text(ch, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.42f).sp)
    }
}
