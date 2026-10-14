package ir.panbeh.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import ir.panbeh.app.data.AppLock
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import ir.panbeh.app.ui.Root
import ir.panbeh.app.ui.theme.PanbehTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent {
            val prefs by PanbehApp.instance.state.prefs.collectAsStateWithLifecycle()
            PanbehTheme(prefs.theme, prefs.accent, prefs.wallpaper) { Root() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() { super.onResume(); visible = true }
    override fun onPause() { super.onPause(); visible = false }
    override fun onStart() { super.onStart(); AppLock.onShown() }
    override fun onStop() { super.onStop(); AppLock.onHidden() }

    private fun handle(intent: Intent?) {
        val app = PanbehApp.instance
        val data = intent?.data
        when {
            data != null && data.scheme == OAUTH_SCHEME -> lifecycleScope.launch {
                runCatching { app.accounts.finishOAuth(data.toString()) }
                    .onSuccess { app.state.ready(it) }
                    .onFailure { app.state.loginError.value = ir.panbeh.client.loginErrText(it) }
            }
            data != null && intent.action == Intent.ACTION_VIEW -> app.state.pendingLink.value = data.toString()
            intent?.getStringExtra(EXTRA_ROOM) != null -> app.state.pendingRoom.value = intent.getStringExtra(EXTRA_ROOM)
            intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_SEND_MULTIPLE -> intents.tryEmit(intent)
        }
        setIntent(Intent()) // consumed: don't replay it after a configuration change
    }

    companion object {
        const val OAUTH_SCHEME = "ir.panbeh.app.kotlin"
        const val EXTRA_ROOM = "room"
        /** The activity is in the foreground (notifications for the open chat are skipped). */
        @Volatile var visible = false
        /** Share intents for the UI to pick up. */
        val intents = MutableSharedFlow<Intent>(replay = 1, extraBufferCapacity = 4)
    }
}
