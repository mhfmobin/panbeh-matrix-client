package ir.panbeh.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** "Decline" on the incoming-call notification: stop ringing and hand the action to the call code via [NotifyState.callActions]. */
class CallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val roomId = intent.getStringExtra(Notifier.EXTRA_ROOM) ?: return
        Notifier.cancelCall(ctx, roomId)
        NotifyState.callActions.tryEmit(
            CallAction(
                intent.getStringExtra(Notifier.EXTRA_CALL) ?: "decline",
                roomId,
                intent.getStringExtra(Notifier.EXTRA_EVENT),
                intent.getBooleanExtra(Notifier.EXTRA_VIDEO, false),
            )
        )
    }
}
