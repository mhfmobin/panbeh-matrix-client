package ir.panbeh.app;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

/** Message notifications, shared by the app's WebView (plugin) and the headless one (JS interface). */
final class Notifier {
    /** Messages that sound go to a channel per kind, so each can have its own sound in Android's settings. */
    static final String CH_DMS = "dms", CH_GROUPS = "groups", CH_MENTIONS = "mentions", CH_QUIET = "messages_quiet", CH_SERVICE = "service", CH_CALLS = "calls";
    static final String EXTRA_ROOM = "roomId";
    /** Incoming-call intents: "open" | "answer" | "decline", plus the ring's event id and whether it's a video call. */
    static final String EXTRA_CALL = "callAction", EXTRA_EVENT = "eventId", EXTRA_VIDEO = "video";
    /** Our secret on every call intent we make: MainActivity is exported, so any app (or a browser intent: link) can send it
     *  call extras; only ones carrying this are acted on. Kept in prefs: a ringing notification outlives the process. */
    static final String EXTRA_TOKEN = "callToken";
    private static String token;
    /** Notification id of an incoming call (tag = room id); messages use 1. */
    static final int CALL_ID = 2;
    private static final String GROUP = "ir.panbeh.app.MESSAGES";

    private Notifier() {}

    /** ctx with the page's language (PanbehPlugin.setLanguage): its strings for everything the app shows itself. Until the page
     *  has said, Persian: an update from before the English UI may sync and notify before the app is opened again. */
    static Context localized(Context ctx) {
        String lang = ctx.getSharedPreferences("panbeh", Context.MODE_PRIVATE).getString("lang", "fa");
        Configuration c = new Configuration(ctx.getResources().getConfiguration());
        c.setLocale(Locale.forLanguageTag(lang));
        return ctx.createConfigurationContext(c);
    }

    static void createChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.deleteNotificationChannel("messages"); // before the per-kind channels
        for (String[] c : new String[][] { { CH_DMS, localized(ctx).getString(R.string.channel_dms) }, { CH_GROUPS, localized(ctx).getString(R.string.channel_groups) },
                { CH_MENTIONS, localized(ctx).getString(R.string.channel_mentions) } }) {
            NotificationChannel msg = new NotificationChannel(c[0], c[1], NotificationManager.IMPORTANCE_HIGH);
            msg.enableVibration(true);
            nm.createNotificationChannel(msg);
        }
        NotificationChannel quiet = new NotificationChannel(CH_QUIET, localized(ctx).getString(R.string.channel_quiet), NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(quiet);
        NotificationChannel svc = new NotificationChannel(CH_SERVICE, localized(ctx).getString(R.string.channel_service), NotificationManager.IMPORTANCE_MIN);
        svc.setShowBadge(false);
        nm.createNotificationChannel(svc);
        NotificationChannel calls = new NotificationChannel(CH_CALLS, localized(ctx).getString(R.string.channel_calls), NotificationManager.IMPORTANCE_HIGH);
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
        if (caller == null || caller.isEmpty()) caller = localized(ctx).getString(R.string.app_name); // CallStyle throws on an unnamed caller
        Person.Builder who = new Person.Builder().setName(caller).setImportant(true);
        Bitmap icon = decode(iconDataUrl);
        if (icon != null) who.setIcon(IconCompat.createWithBitmap(circle(ctx, icon)));
        PendingIntent open = callIntent(ctx, "open", roomId, eventId, video);
        Notification n = new NotificationCompat.Builder(ctx, CH_CALLS)
            .setSmallIcon(R.drawable.ic_stat_panbeh)
            .setColor(0xFF007DED)
            .setContentTitle(caller)
            .setContentText(localized(ctx).getString(video ? R.string.call_video : R.string.call_voice))
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
        if (Build.VERSION.SDK_INT >= 26) CallConnectionService.incoming(ctx, roomId, eventId, caller, video); // headset buttons can answer
    }

    static void cancelCall(Context ctx, String roomId) {
        NotificationManagerCompat.from(ctx).cancel(roomId, CALL_ID);
        if (Build.VERSION.SDK_INT >= 26) CallConnectionService.ringEnded(roomId);
    }

    static synchronized String callToken(Context ctx) {
        if (token != null) return token;
        android.content.SharedPreferences p = ctx.getSharedPreferences("calls", Context.MODE_PRIVATE);
        token = p.getString("token", null);
        if (token == null) {
            byte[] b = new byte[16];
            new SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            token = sb.toString();
            p.edit().putString("token", token).commit(); // before any intent carries it
        }
        return token;
    }

    /** A call intent we made ourselves (see EXTRA_TOKEN). */
    static boolean isTrustedCall(Context ctx, Intent i) {
        String t = i == null || i.getStringExtra(EXTRA_CALL) == null ? null : i.getStringExtra(EXTRA_TOKEN);
        return t != null && MessageDigest.isEqual(t.getBytes(StandardCharsets.UTF_8), callToken(ctx).getBytes(StandardCharsets.UTF_8));
    }

    /** "decline" goes to CallReceiver (no UI); the others open the app. */
    private static PendingIntent callIntent(Context ctx, String action, String roomId, String eventId, boolean video) {
        int code = (roomId + action).hashCode();
        if (action.equals("decline")) {
            Intent i = new Intent(ctx, CallReceiver.class)
                .putExtra(EXTRA_CALL, action).putExtra(EXTRA_ROOM, roomId).putExtra(EXTRA_EVENT, eventId).putExtra(EXTRA_VIDEO, video)
                .putExtra(EXTRA_TOKEN, callToken(ctx));
            return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        Intent i = new Intent(ctx, MainActivity.class)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_CALL, action).putExtra(EXTRA_ROOM, roomId).putExtra(EXTRA_EVENT, eventId).putExtra(EXTRA_VIDEO, video)
            .putExtra(EXTRA_TOKEN, callToken(ctx))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Reply / mark-as-read buttons on a message notification (NotifyReceiver). */
    static final String EXTRA_NOTIFY = "notifyAction", KEY_REPLY = "reply";
    /** A message's event id, kept in its extras so a deleted one can be taken out. */
    private static final String KEY_EVENT = "eventId";
    /** Messages kept in one chat's notification. */
    private static final int MAX_MESSAGES = 7;
    /** Rooms given a conversation shortcut by this process (pushing again per message is wasted work). */
    private static final java.util.Set<String> shortcuts = new java.util.HashSet<>();

    /** One notification per room (tag = room id), Android's conversation style: the chat's recent messages, newest last,
     *  with reply and mark-as-read buttons. Earlier messages are read back from the notification already showing, so
     *  nothing has to be kept here and they survive the process dying. The payload is src/native.ts's: `sender` names who
     *  wrote it in a group, `hidden` = a notification that mustn't name the chat (no shortcut, no photo). */
    @SuppressLint("MissingPermission") // checked via areNotificationsEnabled; SecurityException caught
    static void show(Context ctx, org.json.JSONObject o) {
        String roomId = o.optString("roomId"), title = o.optString("title"), sender = o.optString("sender"), text = o.optString("text", o.optString("body"));
        long ts = o.optLong("ts", System.currentTimeMillis());
        boolean group = o.optBoolean("group"), sound = o.optBoolean("sound", true), hidden = o.optBoolean("hidden");
        if (roomId.isEmpty()) return;
        createChannels(ctx);
        NotificationManagerCompat nm = NotificationManagerCompat.from(ctx);
        if (!nm.areNotificationsEnabled()) return;
        Bitmap icon = decode(o.optString("icon", null)), senderIcon = decode(o.optString("senderIcon", null));
        Bitmap round = icon == null ? null : circle(ctx, icon);
        String channel = !sound ? CH_QUIET : o.optBoolean("mention") ? CH_MENTIONS : group ? CH_GROUPS : CH_DMS;

        android.service.notification.StatusBarNotification shown = active(ctx, roomId);
        NotificationCompat.MessagingStyle style = shown == null ? null : NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(shown.getNotification());
        if (style == null) style = new NotificationCompat.MessagingStyle(new Person.Builder().setName(localized(ctx).getString(R.string.notify_me)).setKey("me").build());
        style.setConversationTitle(group ? title : null).setGroupConversation(group);
        Person.Builder from = new Person.Builder().setName(group && sender != null && !sender.isEmpty() ? sender : title).setKey(group ? "sender:" + sender : "room:" + roomId);
        if (group ? senderIcon != null : round != null) from.setIcon(IconCompat.createWithBitmap(group ? circle(ctx, senderIcon) : round));
        NotificationCompat.MessagingStyle.Message m = new NotificationCompat.MessagingStyle.Message(text, ts, from.build());
        m.getExtras().putString(KEY_EVENT, o.optString("eventId"));
        style.addMessage(m);
        java.util.List<NotificationCompat.MessagingStyle.Message> msgs = style.getMessages();
        while (msgs.size() > MAX_MESSAGES) msgs.remove(0);

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_panbeh)
            .setContentTitle(title)
            .setContentText(group && sender != null && !sender.isEmpty() ? sender + ": " + text : text)
            .setStyle(style)
            .setWhen(ts)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(sound ? NotificationCompat.PRIORITY_HIGH : NotificationCompat.PRIORITY_LOW)
            .setColor(0xFF007DED)
            .setGroup(GROUP)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(openRoom(ctx, roomId))
            .addAction(new NotificationCompat.Action.Builder(0, localized(ctx).getString(R.string.notify_reply), notifyIntent(ctx, "reply", roomId))
                .addRemoteInput(new androidx.core.app.RemoteInput.Builder(KEY_REPLY).setLabel(localized(ctx).getString(R.string.notify_reply_hint)).build())
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .setShowsUserInterface(false)
                .build())
            .addAction(new NotificationCompat.Action.Builder(0, localized(ctx).getString(R.string.notify_read), notifyIntent(ctx, "read", roomId))
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                .setShowsUserInterface(false)
                .build())
            .addAction(new NotificationCompat.Action.Builder(0, localized(ctx).getString(R.string.notify_mute), notifyIntent(ctx, "mute", roomId))
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MUTE)
                .setShowsUserInterface(false)
                .build());
        if (!hidden) b.setShortcutId(conversationShortcut(ctx, roomId, title, round));
        if (round != null) b.setLargeIcon(round);
        try {
            nm.notify(roomId, 1, b.build());
            // the group summary keeps several chats bundled together on Android 7+
            nm.notify(GROUP, 0, new NotificationCompat.Builder(ctx, channel)
                .setSmallIcon(R.drawable.ic_stat_panbeh)
                .setColor(0xFF007DED)
                .setGroup(GROUP)
                .setGroupSummary(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setAutoCancel(true)
                .build());
        } catch (SecurityException e) {
            // POST_NOTIFICATIONS revoked meanwhile
        }
    }

    /** A message deleted after it was notified: out of its chat's notification, silently (the whole notification if it was the last). */
    @SuppressLint("MissingPermission") // SecurityException caught
    static void remove(Context ctx, String roomId, String eventId) {
        android.service.notification.StatusBarNotification shown = active(ctx, roomId);
        if (shown == null) return;
        NotificationCompat.MessagingStyle style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(shown.getNotification());
        if (style == null) return;
        java.util.List<NotificationCompat.MessagingStyle.Message> msgs = style.getMessages();
        if (!msgs.removeIf((m) -> eventId.equals(m.getExtras().getString(KEY_EVENT)))) return;
        if (msgs.isEmpty()) { cancel(ctx, roomId); return; }
        NotificationCompat.MessagingStyle.Message last = msgs.get(msgs.size() - 1);
        CharSequence who = last.getPerson() == null ? null : last.getPerson().getName();
        try {
            NotificationManagerCompat.from(ctx).notify(roomId, 1, new NotificationCompat.Builder(ctx, shown.getNotification())
                .setStyle(style)
                .setContentText(style.isGroupConversation() && who != null ? who + ": " + last.getText() : last.getText())
                .setWhen(last.getTimestamp())
                .setOnlyAlertOnce(true) // an update, not a new message
                .build());
        } catch (SecurityException e) {
            // POST_NOTIFICATIONS revoked meanwhile
        }
    }

    /** The chat's message notification, if it's showing. */
    private static android.service.notification.StatusBarNotification active(Context ctx, String roomId) {
        for (android.service.notification.StatusBarNotification n : ctx.getSystemService(NotificationManager.class).getActiveNotifications()) {
            if (n.getId() == 1 && roomId.equals(n.getTag())) return n;
        }
        return null;
    }

    /** A long-lived shortcut per chat: Android 11+ then lists the chat under Conversations (priority, bubbles, per-chat settings). */
    private static String conversationShortcut(Context ctx, String roomId, String title, Bitmap icon) {
        if (shortcuts.add(roomId)) {
            try {
                androidx.core.content.pm.ShortcutInfoCompat.Builder s = new androidx.core.content.pm.ShortcutInfoCompat.Builder(ctx, roomId)
                    .setShortLabel(title.isEmpty() ? localized(ctx).getString(R.string.app_name) : title)
                    .setLongLived(true)
                    .setIntent(new Intent(ctx, MainActivity.class).setAction(Intent.ACTION_VIEW).putExtra(EXTRA_ROOM, roomId));
                if (icon != null) s.setIcon(IconCompat.createWithBitmap(icon));
                androidx.core.content.pm.ShortcutManagerCompat.pushDynamicShortcut(ctx, s.build());
            } catch (RuntimeException e) {
                shortcuts.remove(roomId); // rate-limited or refused: the notification works without it
            }
        }
        return roomId;
    }

    /** A reply from the notification that couldn't be sent: tapping opens the chat. */
    @SuppressLint("MissingPermission")
    static void replyFailed(Context ctx, String roomId, String text) {
        createChannels(ctx);
        try {
            NotificationManagerCompat.from(ctx).notify(roomId, 1, new NotificationCompat.Builder(ctx, CH_QUIET)
                .setSmallIcon(R.drawable.ic_stat_panbeh)
                .setColor(0xFF007DED)
                .setContentTitle(localized(ctx).getString(R.string.notify_failed))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openRoom(ctx, roomId))
                .build());
        } catch (SecurityException e) {
            // POST_NOTIFICATIONS revoked meanwhile
        }
    }

    /** To NotifyReceiver. Reply must be mutable: Android fills in the typed text. */
    private static PendingIntent notifyIntent(Context ctx, String action, String roomId) {
        Intent i = new Intent(ctx, NotifyReceiver.class).putExtra(EXTRA_NOTIFY, action).putExtra(EXTRA_ROOM, roomId);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (action.equals("reply") && Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : PendingIntent.FLAG_IMMUTABLE);
        return PendingIntent.getBroadcast(ctx, (roomId + action).hashCode(), i, flags);
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
