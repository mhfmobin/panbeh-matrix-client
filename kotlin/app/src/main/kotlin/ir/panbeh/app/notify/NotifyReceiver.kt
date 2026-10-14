package ir.panbeh.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import ir.panbeh.app.PanbehApp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Reply / mark-as-read / mute on a message notification: the notification goes at once, the client does the rest. */
class NotifyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val roomId = i.getStringExtra(Notifier.EXTRA_ROOM) ?: return
        val action = i.getStringExtra(Notifier.EXTRA_NOTIFY) ?: return
        var text: String? = null
        if (action == "reply") {
            text = RemoteInput.getResultsFromIntent(i)?.getCharSequence(Notifier.KEY_REPLY)?.toString()
            if (text.isNullOrBlank()) return
        }
        // gone at once, so Android stops showing the reply as pending; a failed reply comes back as a notice
        Notifier.cancel(ctx, roomId)
        val pending = goAsync()
        val app = PanbehApp.instance
        val job = app.scope.launch { NotificationEngine.onButton(action, roomId, text) }
        // a broadcast may only run ~10 s; a cold start can take longer, so the job carries on in the process without holding the receiver
        app.scope.launch {
            try { withTimeoutOrNull(9_000) { job.join() } } finally { pending.finish() }
        }
    }
}
