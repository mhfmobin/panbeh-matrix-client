package ir.panbeh.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.core.app.RemoteInput;
import com.getcapacitor.JSObject;

/** Reply / mark-as-read on a message notification: the notification goes, and whichever page is running sends it. */
public class NotifyReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent i) {
        String roomId = i.getStringExtra(Notifier.EXTRA_ROOM), action = i.getStringExtra(Notifier.EXTRA_NOTIFY);
        if (roomId == null || action == null) return;
        JSObject a = new JSObject().put("action", action).put("roomId", roomId);
        if (action.equals("reply")) {
            Bundle in = RemoteInput.getResultsFromIntent(i);
            CharSequence text = in == null ? null : in.getCharSequence(Notifier.KEY_REPLY);
            if (text == null || text.toString().trim().isEmpty()) return;
            a.put("text", text.toString());
        }
        // gone at once, so Android stops showing the reply as pending; the page marks the chat read, or says if it failed
        Notifier.cancel(ctx, roomId);
        if (PanbehPlugin.deliver("notifyAction", a)) return;
        if (SyncService.instance != null && SyncService.instance.deliverJs("panbehNotifyAction", a)) return;
        // no page to send it (background service off and the app closed): say so rather than lose it silently
        if (action.equals("reply")) Notifier.replyFailed(ctx, roomId, a.getString("text"));
    }
}
