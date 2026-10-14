package ir.panbeh.app.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ir.panbeh.app.PanbehApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Short status line at the bottom (common.tsx toast). */
object Toasts {
    val events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    fun show(text: String) { events.tryEmit(text) }
}

fun toast(text: String) = Toasts.show(text)

fun copyText(text: String, label: String = "پنبه") {
    val cm = PanbehApp.instance.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    if (Build.VERSION.SDK_INT < 33) toast("کپی شد") // Android 13+ shows its own confirmation
}

@Composable
fun ToastHost() {
    var text by remember { mutableStateOf<String?>(null) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        Toasts.events.collect { t ->
            text = t; visible = true
            delay(1800)
            visible = false
        }
    }
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 88.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visible, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
            Surface(shape = RoundedCornerShape(20.dp), color = Color(0xE6202020)) {
                Text(text ?: "", color = Color.White, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            }
        }
    }
}

@Composable
fun <T> StateFlow<T>.collectAsStateCompat(): State<T> = collectAsState()
