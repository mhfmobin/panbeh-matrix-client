package ir.panbeh.client.rooms

import ir.panbeh.core.media.isGif
import ir.panbeh.core.time.fmtDuration
import ir.panbeh.core.util.string
import ir.panbeh.core.util.truthy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

private val REPLY_FALLBACK = Regex("^(> .*\\n)+\\n?")

/** The body without the "> quoted" reply fallback lines. */
fun stripReplyFallback(body: String) = body.replace(REPLY_FALLBACK, "")

private fun JsonObject.obj(key: String) = this[key] as? JsonObject
private fun JsonObject.ms(key: String): Long = ((this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: 0.0).toLong()

private fun cap(c: JsonObject): String? {
    val file = c.string("filename")
    return if (!file.isNullOrEmpty() && c.string("body") != file) c.string("body") else null
}

private fun isVoice(c: JsonObject) = (c["org.matrix.msc3245.voice"].takeIf { it != null && it !is kotlinx.serialization.json.JsonNull } ?: c["m.voice"]).truthy()

private fun audioDuration(c: JsonObject): Long =
    c.obj("info")?.get("duration")?.let { c.obj("info")!!.ms("duration") }
        ?: c.obj("org.matrix.msc1767.audio")?.get("duration")?.let { c.obj("org.matrix.msc1767.audio")!!.ms("duration") } ?: 0

/** The question of a poll start (MSC3381 unstable or stable), or null. */
fun pollQuestion(c: JsonObject): String? {
    val q = (c.obj("org.matrix.msc3381.poll.start") ?: c.obj("m.poll"))?.obj("question") ?: return null
    fun text(e: JsonElement?): String? = when (e) {
        is JsonPrimitive -> e.content
        is kotlinx.serialization.json.JsonArray -> e.firstOrNull()?.jsonObject?.string("body")
        else -> null
    }
    return (text(q["org.matrix.msc1767.text"]) ?: text(q["m.text"]) ?: q.string("body"))?.takeIf { it.isNotEmpty() }
}

/**
 * One-line text for list previews, as the web app's previewText. `type` is the event type, `c` its content (for
 * m.room.message the full raw JSON); `sender` names the author of an emote.
 */
fun previewText(type: String, c: JsonObject, sender: String = ""): String {
    when (c.string("msgtype")) {
        "m.image" -> return "🖼 " + (cap(c) ?: "عکس")
        "m.video" -> return if (c["app.panbeh.video_note"].truthy()) "📹 پیام ویدیویی " + fmtDuration(c.obj("info")?.ms("duration") ?: 0)
        else if (isGif("m.video", c.obj("info"))) "🎞 " + (cap(c) ?: "گیف") else "🎬 " + (cap(c) ?: "ویدیو")
        "m.audio" -> return if (isVoice(c)) "🎤 پیام صوتی " + fmtDuration(audioDuration(c)) else "🎵 صدا"
        "m.location" -> return "📍 موقعیت مکانی"
        "m.file" -> return "📎 " + (cap(c) ?: c.string("filename") ?: c.string("body") ?: "فایل")
        "m.emote" -> return "* $sender ${c.string("body") ?: ""}"
        "m.key.verification.request" -> return "🔐 درخواست تأیید هویت"
    }
    if (type == "m.sticker") return "استیکر"
    if (type == "org.matrix.msc3381.poll.start" || type == "m.poll.start") return "📊 " + (pollQuestion(c) ?: "نظرسنجی")
    return stripReplyFallback(c.string("body") ?: "")
}

/** Text of a call start (ring or group call notification) in the list. */
fun callPreview(c: JsonObject): String {
    val video = c.string("m.call.intent") == "video"
    return "📞 " + if (c.string("notification_type") == "ring") (if (video) "تماس تصویری" else "تماس صوتی")
    else if (video) "تماس تصویری گروهی" else "تماس صوتی گروهی"
}
