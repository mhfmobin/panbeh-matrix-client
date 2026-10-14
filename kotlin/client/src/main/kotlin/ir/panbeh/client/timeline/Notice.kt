package ir.panbeh.client.timeline

import ir.panbeh.core.admin.HISTORY
import ir.panbeh.core.admin.JOIN_RULES
import ir.panbeh.core.admin.PowerLevels
import ir.panbeh.core.admin.levelChanges
import ir.panbeh.core.admin.roleLabel
import ir.panbeh.core.media.isGif
import ir.panbeh.core.time.fmtDuration
import ir.panbeh.core.time.num
import ir.panbeh.core.util.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Bidi-isolate a name inside a Farsi sentence so Latin names/punctuation don't reorder it. */
fun bdi(s: String) = "⁨$s⁩"

const val VERIFY_REQUEST = "m.key.verification.request"

private val REPLY_FALLBACK = Regex("^(> .*\\n)+\\n?")

/** The body without the quoted reply fallback. */
fun stripReplyFallback(body: String) = REPLY_FALLBACK.replaceFirst(body, "")

/** A media message's caption (MSC2530): body differs from filename; otherwise body is just the name. */
fun captionOf(c: JsonObject): String {
    val f = c.string("filename")
    return if (!f.isNullOrEmpty() && c.string("body") != f) c.string("body") ?: "" else ""
}

fun isVoice(c: JsonObject) = (c["org.matrix.msc3245.voice"] ?: c["m.voice"]) != null && !isFalsy(c["org.matrix.msc3245.voice"] ?: c["m.voice"])

private fun isFalsy(e: kotlinx.serialization.json.JsonElement?) = e == null || e is kotlinx.serialization.json.JsonNull || (e is JsonPrimitive && !e.isString && e.content == "false")

/** ms; 0 if unknown */
fun audioDuration(c: JsonObject): Long =
    ((c["info"] as? JsonObject)?.get("duration") as? JsonPrimitive)?.doubleOrNull?.toLong()
        ?: (((c["org.matrix.msc1767.audio"] as? JsonObject)?.get("duration")) as? JsonPrimitive)?.doubleOrNull?.toLong() ?: 0L

private fun JsonObject.info() = this["info"] as? JsonObject
private fun JsonObject.isNote() = !isFalsy(this["app.panbeh.video_note"])

/** One-line text for list previews and reply quotes. [senderName] is used for emotes. */
fun previewText(type: String, c: JsonObject, senderName: String, decryption: Decryption = Decryption.OK): String {
    if (decryption == Decryption.FAILED) return "🔒 پیام رمزنگاری‌شده"
    if (decryption == Decryption.DECRYPTING || type == "m.room.encrypted") return "🔒 در حال رمزگشایی…"
    val cap = captionOf(c).ifEmpty { null }
    when (c.string("msgtype")) {
        "m.image" -> return "🖼 " + (cap ?: "عکس")
        "m.video" -> return if (c.isNote()) "📹 پیام ویدیویی " + fmtDuration(c.info()?.get("duration")?.let { (it as? JsonPrimitive)?.doubleOrNull?.toLong() } ?: 0L)
        else if (isGif(c.string("msgtype"), c.info())) "🎞 " + (cap ?: "گیف") else "🎬 " + (cap ?: "ویدیو")
        "m.audio" -> return if (isVoice(c)) "🎤 پیام صوتی " + fmtDuration(audioDuration(c)) else "🎵 صدا"
        "m.location" -> return "📍 موقعیت مکانی"
        "m.file" -> return "📎 " + (cap ?: c.string("filename") ?: c.string("body") ?: "فایل")
        "m.emote" -> return "* $senderName ${c.string("body") ?: ""}"
        VERIFY_REQUEST -> return "🔐 درخواست تأیید هویت"
    }
    if (type == "m.sticker") return "استیکر"
    if (isPollStart(type)) return "📊 " + (parsePoll(c)?.question ?: "نظرسنجی")
    return stripReplyFallback(c.string("body") ?: (c["body"]?.toString() ?: ""))
}

/** A 1:1 call's line / a group call's alert, decided by the caller from the events around it. */
data class CallLine(val video: Boolean, val group: Boolean, val outcome: ir.panbeh.core.calls.CallOutcome?)

private fun num(o: JsonObject, k: String): Double? = (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

/** m.room.power_levels JSON as core's [PowerLevels] (for [levelChanges]). */
fun powerLevelsOf(o: JsonObject): PowerLevels {
    fun map(k: String) = (o[k] as? JsonObject)?.mapNotNull { (id, v) -> (v as? JsonPrimitive)?.doubleOrNull?.let { id to it } }?.toMap()
    return PowerLevels(
        users = map("users"), usersDefault = num(o, "users_default"), events = map("events"), eventsDefault = num(o, "events_default"),
        stateDefault = num(o, "state_default"), invite = num(o, "invite"), kick = num(o, "kick"), ban = num(o, "ban"), redact = num(o, "redact"),
        notifications = map("notifications"),
    )
}

/**
 * Text for state events (and the like) worth showing as a centered pill; null = hide.
 * [who] is the bidi-isolated sender name, [nameOf] a user's current display name, [prev] the state's previous content.
 */
fun noticeText(
    type: String, c: JsonObject, prev: JsonObject, stateKey: String?, sender: String, who: String,
    nameOf: (String) -> String? = { null }, call: CallLine? = null, mine: Boolean = false,
): String? {
    fun s(o: JsonObject, k: String) = o.string(k)
    when (type) {
        "m.room.message" -> return if (s(c, "msgtype") == VERIFY_REQUEST) "$who درخواست تأیید هویت فرستاد" else null
        "m.room.create" -> return if (!s(c["predecessor"] as? JsonObject ?: JsonObject(emptyMap()), "room_id").isNullOrEmpty()) "ادامه‌ی گروه قبلی؛ این گروه ارتقا یافته است"
        else "$who ${if (s(c, "type") == "m.space") "فضا" else "گفتگو"} را ایجاد کرد"
        "m.room.name" -> return s(c, "name").takeUnless { it.isNullOrEmpty() }?.let { "$who نام گفتگو را به «${bdi(it)}» تغییر داد" } ?: "$who نام گفتگو را حذف کرد"
        "m.room.topic" -> {
            val t = s(c, "topic") ?: (((c["m.topic"] as? JsonObject)?.get("m.text") as? JsonArray)?.firstOrNull() as? JsonObject)?.string("body") ?: ""
            return if (t.isNotEmpty()) "$who توضیحات را به «${bdi(if (t.length > 80) t.take(80) + "…" else t)}» تغییر داد" else "$who توضیحات را حذف کرد"
        }
        "m.room.avatar" -> return if (!s(c, "url").isNullOrEmpty()) "$who عکس گفتگو را تغییر داد" else "$who عکس گفتگو را حذف کرد"
        "m.room.canonical_alias" -> {
            if (c["alias"] == prev["alias"]) return null // alt_aliases only
            val a = s(c, "alias")
            return if (!a.isNullOrEmpty()) "$who نشانی را به ${bdi(a)} تغییر داد" else "$who نشانی ${bdi(s(prev, "alias") ?: "")} را حذف کرد"
        }
        "m.room.server_acl" -> return "$who دسترسی سرورها به این گفتگو را تغییر داد"
        "m.room.guest_access" -> {
            val p = s(prev, "guest_access")
            return if (p != null && s(c, "guest_access") != p) (if (s(c, "guest_access") == "can_join") "$who پیوستن مهمان‌ها را آزاد کرد" else "$who پیوستن مهمان‌ها را بست") else null
        }
        "m.room.encryption" -> return "پیام‌ها رمزنگاری سرتاسری شده‌اند"
        "m.room.pinned_events" -> {
            val n = (c["pinned"] as? JsonArray)?.size ?: 0
            val was = (prev["pinned"] as? JsonArray)?.size ?: 0
            return if (n > was) "$who پیامی را سنجاق کرد" else if (n < was) "$who سنجاق پیامی را برداشت" else null
        }
        "m.room.member" -> {
            val key = stateKey ?: ""
            val target = bdi(s(c, "displayname") ?: key)
            val prevName = bdi(s(prev, "displayname") ?: s(c, "displayname") ?: key)
            val pm = s(prev, "membership")
            val m = s(c, "membership")
            if (m == pm) return null // profile changes are noise
            val reason = s(c, "reason")?.takeIf { it.isNotEmpty() }?.let { " (دلیل: ${bdi(it)})" } ?: ""
            return when (m) {
                "join" -> "$target پیوست"
                "invite" -> "$who $target را دعوت کرد"
                "knock" -> "$target درخواست عضویت داد"
                "leave" ->
                    if (sender == key) when (pm) { "knock" -> "$prevName درخواست عضویتش را پس گرفت"; "invite" -> "$prevName دعوت را نپذیرفت"; else -> "$prevName خارج شد" }
                    else when (pm) {
                        "ban" -> "$who مسدودیت $prevName را برداشت"
                        "knock" -> "$who درخواست عضویت $prevName را رد کرد"
                        "invite" -> "$who دعوت $prevName را لغو کرد"
                        else -> "$who $prevName را بیرون کرد$reason"
                    }
                "ban" -> "$who $prevName را مسدود کرد$reason"
                else -> null
            }
        }
        "m.room.power_levels" -> {
            if (prev.isEmpty()) return null // room creation
            val (c1, more) = levelChanges(powerLevelsOf(prev), powerLevelsOf(c)).let { it.firstOrNull() to it.drop(1) }
            if (c1 == null) return if (prev == c) null else "$who دسترسی‌های گروه را تغییر داد"
            val name = bdi(nameOf(c1.id) ?: c1.id)
            return "$who نقش $name را به «${roleLabel(c1.to) ?: "عضو"}» تغییر داد" + if (more.isNotEmpty()) " (و ${num(more.size)} نفر دیگر)" else ""
        }
        "m.room.join_rules" -> {
            val p = s(prev, "join_rule")
            val n = s(c, "join_rule")
            return if (!p.isNullOrEmpty() && n != p) "$who شیوه‌ی پیوستن را به «${JOIN_RULES[n] ?: n}» تغییر داد" else null
        }
        "m.room.history_visibility" -> {
            val p = s(prev, "history_visibility")
            val n = s(c, "history_visibility")
            return if (!p.isNullOrEmpty() && n != p) "$who دسترسی به تاریخچه را به «${HISTORY[n] ?: n}» تغییر داد" else null
        }
        "m.room.tombstone" -> return "$who این گروه را ارتقا داد"
        "org.matrix.msc4075.rtc.notification", "m.rtc.notification", "m.call.invite" -> {
            if (call == null || call.group) return "$who ${if (call?.video == true || s(c, "m.call.intent") == "video") "تماس تصویری" else "تماس صوتی"} گروهی را شروع کرد"
            return callLine(who, if (call.video) "تماس تصویری" else "تماس صوتی", call.outcome, mine)
        }
    }
    return null
}

private fun callLine(who: String, kind: String, o: ir.panbeh.core.calls.CallOutcome?, mine: Boolean): String = when (o?.state) {
    ir.panbeh.core.calls.CallState.ENDED -> "$who $kind گرفت · ${fmtDuration(o.duration ?: 0)}"
    ir.panbeh.core.calls.CallState.MISSED -> if (mine) "$kind بی‌پاسخ" else "$kind از دست رفته از $who"
    ir.panbeh.core.calls.CallState.DECLINED -> if (mine) "$kind رد شد" else "$kind $who را رد کردید"
    else -> "$who $kind گرفت"
}
