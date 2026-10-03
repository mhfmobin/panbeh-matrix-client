package ir.panbeh.app;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.RingtoneManager;
import android.os.Build;
import android.util.Base64;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.Person;
import androidx.core.graphics.drawable.IconCompat;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;

/** Message notifications, shared by the app's WebView (plugin) and the headless one (JS interface). */
final class Notifier {
    static final String CH_MESSAGES = "messages", CH_QUIET = "messages_quiet", CH_SERVICE = "service", CH_CALLS = "calls";
    static final String EXTRA_ROOM = "roomId";
    /** Incoming-call intents: "open" | "answer" | "decline", plus the ring's event id and whether it's a video call. */
    static final String EXTRA_CALL = "callAction", EXTRA_EVENT = "eventId", EXTRA_VIDEO = "video";
    /** Notification id of an incoming call (tag = room id); messages use 1. */
    static final int CALL_ID = 2;
    private static final String GROUP = "ir.panbeh.app.MESSAGES";

    private Notifier() {}

    static void createChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        NotificationChannel msg = new NotificationChannel(CH_MESSAGES, ctx.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH);
        msg.enableVibration(true);
        nm.createNotificationChannel(msg);
        NotificationChannel quiet = new NotificationChannel(CH_QUIET, ctx.getString(R.string.channel_quiet), NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(quiet);
        NotificationChannel svc = new NotificationChannel(CH_SERVICE, ctx.getString(R.string.channel_service), NotificationManager.IMPORTANCE_MIN);
        svc.setShowBadge(false);
        nm.createNotificationChannel(svc);
        NotificationChannel calls = new NotificationChannel(CH_CALLS, ctx.getString(R.string.channel_calls), NotificationManager.IMPORTANCE_HIGH);
        calls.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        calls.enableVibration(true);
        calls.setVibrationPattern(new long[] { 0, 1000, 1000 });
        nm.createNotificationChannel(calls);
    }

    /** Rings until answered, declined or timed out; full screen over the lock screen (where Android allows it). */
    @SuppressLint("MissingPermission") // SecurityException caught
    static void showCall(Context ctx, String roomId, String eventId, String caller, boolean video, String iconDataUrl, long timeoutMs) {
        createChannels(ctx);
        if (caller == null || caller.isEmpty()) caller = ctx.getString(R.string.app_name); // CallStyle throws on an unnamed caller
        Person.Builder who = new Person.Builder().setName(caller).setImportant(true);
        Bitmap icon = decode(iconDataUrl);
        if (icon != null) who.setIcon(IconCompat.createWithBitmap(circle(ctx, icon)));
        PendingIntent open = callIntent(ctx, "open", roomId, eventId, video);
        Notification n = new NotificationCompat.Builder(ctx, CH_CALLS)
            .setSmallIcon(R.drawable.ic_stat_panbeh)
            .setColor(0xFF3390EC)
            .setContentTitle(caller)
            .setContentText(ctx.getString(video ? R.string.call_video : R.string.call_voice))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), AudioManager.STREAM_RING) // before Android 8 (no channels)
            .setOngoing(true)
            .setTimeoutAfter(Math.max(1000, timeoutMs))
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(who.build(),
                callIntent(ctx, "decline", roomId, eventId, video), callIntent(ctx, "answer", roomId, eventId, video)))
            .build();
        n.flags |= Notification.FLAG_INSISTENT; // keep ringing, like a phone
        try {
            NotificationManagerCompat.from(ctx).notify(roomId, CALL_ID, n);
        } catch (SecurityException e) {
            // POST_NOTIFICATIONS revoked meanwhile
        }
    }

    static void cancelCall(Context ctx, String roomId) {
        NotificationManagerCompat.from(ctx).cancel(roomId, CALL_ID);
    }

    /** "decline" goes to CallReceiver (no UI); the others open the app. */
    private static PendingIntent callIntent(Context ctx, String action, String roomId, String eventId, boolean video) {
        int code = (roomId + action).hashCode();
        if (action.equals("decline")) {
            Intent i = new Intent(ctx, CallReceiver.class)
                .putExtra(EXTRA_CALL, action).putExtra(EXTRA_ROOM, roomId).putExtra(EXTRA_EVENT, eventId).putExtra(EXTRA_VIDEO, video);
            return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        Intent i = new Intent(ctx, MainActivity.class)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_CALL, action).putExtra(EXTRA_ROOM, roomId).putExtra(EXTRA_EVENT, eventId).putExtra(EXTRA_VIDEO, video)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** One notification per room (tag = room id): the newest message replaces the previous one. */
    @SuppressLint("MissingPermission") // checked via areNotificationsEnabled; SecurityException caught
    static void show(Context ctx, String roomId, String title, String body, String iconDataUrl, boolean sound) {
        createChannels(ctx);
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, sound ? CH_MESSAGES : CH_QUIET)
            .setSmallIcon(R.drawable.ic_stat_panbeh)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(sound ? NotificationCompat.PRIORITY_HIGH : NotificationCompat.PRIORITY_LOW)
            .setColor(0xFF3390EC)
            .setGroup(GROUP)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(openRoom(ctx, roomId));
        Bitmap icon = decode(iconDataUrl);
        if (icon != null) b.setLargeIcon(circle(ctx, icon));
        NotificationManagerCompat nm = NotificationManagerCompat.from(ctx);
        if (!nm.areNotificationsEnabled()) return;
        try {
            nm.notify(roomId, 1, b.build());
            // the group summary keeps several chats bundled together on Android 7+
            nm.notify(GROUP, 0, new NotificationCompat.Builder(ctx, sound ? CH_MESSAGES : CH_QUIET)
                .setSmallIcon(R.drawable.ic_stat_panbeh)
                .setColor(0xFF3390EC)
                .setGroup(GROUP)
                .setGroupSummary(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setAutoCancel(true)
                .build());
        } catch (SecurityException e) {
            // POST_NOTIFICATIONS revoked meanwhile
        }
    }

    static void cancel(Context ctx, String roomId) {
        NotificationManagerCompat nm = NotificationManagerCompat.from(ctx);
        nm.cancel(roomId, 1);
        if (Build.VERSION.SDK_INT >= 23) {
            NotificationManager sys = ctx.getSystemService(NotificationManager.class);
            boolean any = false;
            for (android.service.notification.StatusBarNotification n : sys.getActiveNotifications()) {
                if (n.getId() == 1) { any = true; break; }
            }
            if (!any) nm.cancel(GROUP, 0);
        }
    }

    static void cancelAll(Context ctx) {
        NotificationManagerCompat nm = NotificationManagerCompat.from(ctx);
        if (Build.VERSION.SDK_INT >= 23) {
            for (android.service.notification.StatusBarNotification n : ctx.getSystemService(NotificationManager.class).getActiveNotifications()) {
                if (n.getId() != SyncService.NOTIFICATION_ID) nm.cancel(n.getTag(), n.getId());
            }
        }
    }

    static PendingIntent openRoom(Context ctx, String roomId) {
        Intent i = new Intent(ctx, MainActivity.class)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_ROOM, roomId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(ctx, roomId.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static Bitmap decode(String dataUrl) {
        if (dataUrl == null) return null;
        int comma = dataUrl.indexOf(',');
        if (!dataUrl.startsWith("data:") || comma < 0) return null;
        try {
            byte[] bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Bitmap circle(Context ctx, Bitmap src) {
        int size = Math.min(src.getWidth(), src.getHeight());
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(out);
        RoundedBitmapDrawable d = androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(ctx.getResources(),
            Bitmap.createBitmap(src, (src.getWidth() - size) / 2, (src.getHeight() - size) / 2, size, size));
        d.setCircular(true);
        d.setBounds(0, 0, size, size);
        d.draw(c);
        return out;
    }
}
