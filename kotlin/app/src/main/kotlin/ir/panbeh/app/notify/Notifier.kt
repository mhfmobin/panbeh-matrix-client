package ir.panbeh.app.notify

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import ir.panbeh.app.MainActivity
import ir.panbeh.app.R
import ir.panbeh.app.sync.SyncService

/** One message to show: what [Notifier.show] needs. [sender] is empty in a DM and when the chat is hidden. */
data class MessageNote(
    val roomId: String,
    val eventId: String?,
    val title: String,
    val sender: String,
    val senderKey: String,
    val text: String,
    val ts: Long,
    val group: Boolean,
    val sound: Boolean,
    val mention: Boolean,
    /** the notification mustn't name the chat: no conversation shortcut, no photo */
    val hidden: Boolean,
    val icon: Bitmap? = null,
    val senderIcon: Bitmap? = null,
)

/** Message and call notifications. */
object Notifier {
    /** Messages that sound go to a channel per kind, so each can have its own sound in Android's settings. */
    const val CH_DMS = "dms"
    const val CH_GROUPS = "groups"
    const val CH_MENTIONS = "mentions"
    const val CH_QUIET = "messages_quiet"
    const val CH_SERVICE = "service"
    const val CH_CALLS = "calls"

    /** extras of our own intents (the room for the activity is [MainActivity.EXTRA_ROOM]) */
    const val EXTRA_ROOM = "roomId"
    const val EXTRA_CALL = "callAction"
    const val EXTRA_EVENT = "eventId"
    const val EXTRA_VIDEO = "video"
    const val EXTRA_NOTIFY = "notifyAction"
    const val KEY_REPLY = "reply"

    const val MESSAGE_ID = 1
    /** Notification id of an incoming call (tag = room id). */
    const val CALL_ID = 2

    private const val GROUP = "ir.panbeh.app.kotlin.MESSAGES"
    private const val KEY_EVENT = "eventId"
    private const val MAX_MESSAGES = 7
    private const val ACCENT = 0xFF007DED.toInt()

    /** Rooms given a conversation shortcut by this process (pushing again per message is wasted work). */
    private val shortcuts = HashSet<String>()

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("messages") // before the per-kind channels
        for ((id, name) in listOf(CH_DMS to R.string.channel_dms, CH_GROUPS to R.string.channel_groups, CH_MENTIONS to R.string.channel_mentions)) {
            nm.createNotificationChannel(NotificationChannel(id, ctx.getString(name), NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
        }
        nm.createNotificationChannel(NotificationChannel(CH_QUIET, ctx.getString(R.string.channel_quiet), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, ctx.getString(R.string.channel_service), NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(CH_CALLS, ctx.getString(R.string.channel_calls), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 1000, 1000)
        })
    }

    /**
     * One notification per room (tag = room id): Android's conversation style, the chat's recent messages newest last, with reply,
     * mark-as-read and mute buttons. Earlier messages are read back from the notification already showing, so nothing is kept
     * here and they survive the process dying.
     */
    @SuppressLint("MissingPermission") // checked via areNotificationsEnabled; SecurityException caught
    fun show(ctx: Context, n: MessageNote) {
        if (n.roomId.isEmpty()) return
        createChannels(ctx)
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val round = n.icon?.let { circle(ctx, it) }
        val channel = when {
            !n.sound -> CH_QUIET
            n.mention -> CH_MENTIONS
            n.group -> CH_GROUPS
            else -> CH_DMS
        }

        val shown = active(ctx, n.roomId)
        val style = shown?.let { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it.notification) }
            ?: NotificationCompat.MessagingStyle(Person.Builder().setName(ctx.getString(R.string.notify_me)).setKey("me").build())
        style.setConversationTitle(if (n.group) n.title else null).setGroupConversation(n.group)
        val from = Person.Builder()
            .setName(if (n.group && n.sender.isNotEmpty()) n.sender else n.title)
            .setKey(if (n.group) "sender:${n.senderKey}" else "room:${n.roomId}")
        val fromIcon = if (n.group) n.senderIcon?.let { circle(ctx, it) } else round
        if (fromIcon != null) from.setIcon(IconCompat.createWithBitmap(fromIcon))
        val m = NotificationCompat.MessagingStyle.Message(n.text, n.ts, from.build())
        m.extras.putString(KEY_EVENT, n.eventId ?: "")
        style.addMessage(m)
        val msgs = style.messages
        while (msgs.size > MAX_MESSAGES) msgs.removeAt(0)

        val b = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_panbeh)
            .setContentTitle(n.title)
            .setContentText(if (n.group && n.sender.isNotEmpty()) "${n.sender}: ${n.text}" else n.text)
            .setStyle(style)
            .setWhen(n.ts)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (n.sound) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .setColor(ACCENT)
            .setGroup(GROUP)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(openRoom(ctx, n.roomId))
            .addAction(
                NotificationCompat.Action.Builder(0, ctx.getString(R.string.notify_reply), notifyIntent(ctx, "reply", n.roomId))
                    .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel(ctx.getString(R.string.notify_reply_hint)).build())
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                    .setShowsUserInterface(false)
                    .build()
            )
            .addAction(
                NotificationCompat.Action.Builder(0, ctx.getString(R.string.notify_read), notifyIntent(ctx, "read", n.roomId))
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                    .setShowsUserInterface(false)
                    .build()
            )
            .addAction(
                NotificationCompat.Action.Builder(0, ctx.getString(R.string.notify_mute), notifyIntent(ctx, "mute", n.roomId))
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MUTE)
                    .setShowsUserInterface(false)
                    .build()
            )
        if (!n.hidden) b.setShortcutId(conversationShortcut(ctx, n.roomId, n.title, round))
        if (round != null) b.setLargeIcon(round)
        try {
            nm.notify(n.roomId, MESSAGE_ID, b.build())
            // the group summary keeps several chats bundled together
            nm.notify(
                GROUP, 0,
                NotificationCompat.Builder(ctx, channel)
                    .setSmallIcon(R.drawable.ic_stat_panbeh)
                    .setColor(ACCENT)
                    .setGroup(GROUP)
                    .setGroupSummary(true)
                    .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                    .setAutoCancel(true)
                    .build()
            )
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS revoked meanwhile
        }
    }

    /** A message deleted after it was notified: out of its chat's notification, silently (the whole notification if it was the last). */
    @SuppressLint("MissingPermission") // SecurityException caught
    fun remove(ctx: Context, roomId: String, eventId: String) {
        val shown = active(ctx, roomId) ?: return
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(shown.notification) ?: return
        val msgs = style.messages
        if (!msgs.removeIf { eventId == it.extras.getString(KEY_EVENT) }) return
        if (msgs.isEmpty()) { cancel(ctx, roomId); return }
        val last = msgs[msgs.size - 1]
        val who = last.person?.name
        try {
            NotificationManagerCompat.from(ctx).notify(
                roomId, MESSAGE_ID,
                NotificationCompat.Builder(ctx, shown.notification)
                    .setStyle(style)
                    .setContentText(if (style.isGroupConversation && who != null) "$who: ${last.text}" else last.text)
                    .setWhen(last.timestamp)
                    .setOnlyAlertOnce(true) // an update, not a new message
                    .build()
            )
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS revoked meanwhile
        }
    }

    /** The chat's message notification, if it's showing. */
    private fun active(ctx: Context, roomId: String): StatusBarNotification? =
        ctx.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull { it.id == MESSAGE_ID && it.tag == roomId }

    /** A long-lived shortcut per chat: Android 11+ then lists the chat under Conversations (priority, bubbles, per-chat settings). */
    private fun conversationShortcut(ctx: Context, roomId: String, title: String, icon: Bitmap?): String {
        if (shortcuts.add(roomId)) {
            try {
                val s = ShortcutInfoCompat.Builder(ctx, roomId)
                    .setShortLabel(title.ifEmpty { ctx.getString(R.string.app_name) })
                    .setLongLived(true)
                    .setIntent(Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra(MainActivity.EXTRA_ROOM, roomId))
                if (icon != null) s.setIcon(IconCompat.createWithBitmap(icon))
                ShortcutManagerCompat.pushDynamicShortcut(ctx, s.build())
            } catch (e: RuntimeException) {
                shortcuts.remove(roomId) // rate-limited or refused: the notification works without it
            }
        }
        return roomId
    }

    /** A reply from the notification that couldn't be sent: tapping opens the chat. */
    @SuppressLint("MissingPermission")
    fun replyFailed(ctx: Context, roomId: String, text: String) {
        createChannels(ctx)
        try {
            NotificationManagerCompat.from(ctx).notify(
                roomId, MESSAGE_ID,
                NotificationCompat.Builder(ctx, CH_QUIET)
                    .setSmallIcon(R.drawable.ic_stat_panbeh)
                    .setColor(ACCENT)
                    .setContentTitle(ctx.getString(R.string.notify_failed))
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setContentIntent(openRoom(ctx, roomId))
                    .build()
            )
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS revoked meanwhile
        }
    }

    /** To [NotifyReceiver]. Reply must be mutable: Android fills in the typed text. */
    private fun notifyIntent(ctx: Context, action: String, roomId: String): PendingIntent {
        val i = Intent(ctx, NotifyReceiver::class.java).putExtra(EXTRA_NOTIFY, action).putExtra(EXTRA_ROOM, roomId)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (action == "reply") PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(ctx, (roomId + action).hashCode(), i, flags)
    }

    /** Takes a chat's notification away (and the summary when it was the last). */
    fun cancel(ctx: Context, roomId: String) {
        val nm = NotificationManagerCompat.from(ctx)
        nm.cancel(roomId, MESSAGE_ID)
        val sys = ctx.getSystemService(NotificationManager::class.java)
        if (sys.activeNotifications.none { it.id == MESSAGE_ID }) nm.cancel(GROUP, 0)
    }

    /** All message and call notifications (not the background service's). */
    fun cancelAll(ctx: Context) {
        val nm = NotificationManagerCompat.from(ctx)
        for (n in ctx.getSystemService(NotificationManager::class.java).activeNotifications) {
            if (n.id != SyncService.NOTIFICATION_ID) nm.cancel(n.tag, n.id)
        }
    }

    /** Opens the app at a chat. */
    fun openRoom(ctx: Context, roomId: String): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(MainActivity.EXTRA_ROOM, roomId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(ctx, roomId.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * Rings until answered, declined or timed out; full screen over the lock screen where Android allows it.
     * "answer" and "open" start MainActivity with [EXTRA_CALL] / [EXTRA_EVENT] / [EXTRA_VIDEO] extras (and the room as
     * [MainActivity.EXTRA_ROOM]); "decline" goes to [CallReceiver] and from there to [NotifyState.callActions].
     */
    @SuppressLint("MissingPermission") // SecurityException caught
    fun showCall(ctx: Context, roomId: String, eventId: String, caller: String, video: Boolean, icon: Bitmap?, timeoutMs: Long) {
        createChannels(ctx)
        val name = caller.ifEmpty { ctx.getString(R.string.app_name) } // CallStyle throws on an unnamed caller
        val who = Person.Builder().setName(name).setImportant(true)
        if (icon != null) who.setIcon(IconCompat.createWithBitmap(circle(ctx, icon)))
        val open = callIntent(ctx, "open", roomId, eventId, video)
        val n = NotificationCompat.Builder(ctx, CH_CALLS)
            .setSmallIcon(R.drawable.ic_stat_panbeh)
            .setColor(ACCENT)
            .setContentTitle(name)
            .setContentText(ctx.getString(if (video) R.string.call_video else R.string.call_voice))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setTimeoutAfter(maxOf(1000L, timeoutMs))
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    who.build(),
                    callIntent(ctx, "decline", roomId, eventId, video),
                    callIntent(ctx, "answer", roomId, eventId, video),
                )
            )
            .build()
        n.flags = n.flags or Notification.FLAG_INSISTENT // keep ringing, like a phone
        try {
            NotificationManagerCompat.from(ctx).notify(roomId, CALL_ID, n)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS revoked meanwhile
        }
    }

    fun cancelCall(ctx: Context, roomId: String) {
        NotificationManagerCompat.from(ctx).cancel(roomId, CALL_ID)
    }

    private fun callIntent(ctx: Context, action: String, roomId: String, eventId: String, video: Boolean): PendingIntent {
        val code = (roomId + action).hashCode()
        if (action == "decline") {
            val i = Intent(ctx, CallReceiver::class.java)
                .putExtra(EXTRA_CALL, action).putExtra(EXTRA_ROOM, roomId).putExtra(EXTRA_EVENT, eventId).putExtra(EXTRA_VIDEO, video)
            return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val i = Intent(ctx, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_CALL, action).putExtra(EXTRA_ROOM, roomId).putExtra(MainActivity.EXTRA_ROOM, roomId)
            .putExtra(EXTRA_EVENT, eventId).putExtra(EXTRA_VIDEO, video)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** A centre-cropped circle of [src], for avatars. */
    fun circle(ctx: Context, src: Bitmap): Bitmap {
        val size = minOf(src.width, src.height)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val d = RoundedBitmapDrawableFactory.create(ctx.resources, Bitmap.createBitmap(src, (src.width - size) / 2, (src.height - size) / 2, size, size))
        d.isCircular = true
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(out))
        return out
    }
}
