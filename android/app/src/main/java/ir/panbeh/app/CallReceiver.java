package ir.panbeh.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import com.getcapacitor.JSObject;

/** "Decline" on the incoming-call notification: stop ringing and let whichever page is running send the decline. */
public class CallReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent i) {
        String roomId = i.getStringExtra(Notifier.EXTRA_ROOM);
        if (roomId == null) return;
        Notifier.cancelCall(ctx, roomId);
        JSObject a = PanbehPlugin.callAction(i);
        // ponytail: with no page running (service off) we only stop ringing; the caller's ring times out
        if (!PanbehPlugin.deliverCall(a) && SyncService.instance != null) SyncService.instance.deliverCall(a);
    }
}
