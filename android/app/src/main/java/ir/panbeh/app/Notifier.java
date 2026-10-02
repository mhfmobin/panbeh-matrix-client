package ir.panbeh.app;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.util.Base64;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;

/** Message notifications, shared by the app's WebView (plugin) and the headless one (JS interface). */
final class Notifier {
    static final String CH_MESSAGES = "messages", CH_QUIET = "messages_quiet", CH_SERVICE = "service";
    static final String EXTRA_ROOM = "roomId";
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
