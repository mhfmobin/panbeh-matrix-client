package ir.panbeh.app.ui.common

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import ir.panbeh.core.media.blurhashDecode

/** The blurred stand-in (MSC2448) shown before media is downloaded; nothing if the hash is bad. */
@Composable
fun BlurhashImage(hash: String?, modifier: Modifier = Modifier) {
    val bmp = remember(hash) {
        hash?.let { h -> blurhashDecode(h, 32, 32)?.let { Bitmap.createBitmap(it, 32, 32, Bitmap.Config.ARGB_8888).asImageBitmap() } }
    } ?: return
    Image(bmp, null, modifier, contentScale = ContentScale.Crop)
}
