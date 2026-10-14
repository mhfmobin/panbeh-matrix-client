package ir.panbeh.app.notify

import ir.panbeh.client.events.PollStart
import ir.panbeh.client.events.PollStartStable
import ir.panbeh.client.events.Sticker
import de.connect2x.trixnity.core.model.events.m.room.RoomMessageEventContent
import ir.panbeh.core.media.isGif
import ir.panbeh.core.time.fmtDuration
import ir.panbeh.core.util.number
import ir.panbeh.core.util.string
import ir.panbeh.core.util.truthy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val REPLY_FALLBACK = Regex("^(> .*\\n)+\\n?")

/** The body without the quoted-reply fallback lines. */
fun stripReplyFallback(body: String) = REPLY_FALLBACK.replace(body, "")

/** A file's caption: its body, unless that is just the file name. */
private fun JsonObject.caption(): String? = string("filename")?.takeIf { string("body") != it }?.let { string("body") }

private fun JsonObject.audioDuration(): Long =
    (this["info"] as? JsonObject)?.number("duration")?.toLong()
        ?: (this["org.matrix.msc1767.audio"] as? JsonObject)?.number("duration")?.toLong() ?: 0L

private fun JsonObject.isVoice() = (this["org.matrix.msc3245.voice"] ?: this["m.voice"]).truthy()

/** A poll's question from a start event, stable or unstable names. */
private fun pollQuestion(raw: JsonObject): String? {
    val poll = (raw["m.poll"] ?: raw["org.matrix.msc3381.poll.start"]) as? JsonObject ?: return null
    val q = poll["question"] as? JsonObject ?: return null
    for (k in listOf("m.text", "org.matrix.msc1767.text", "body")) {
        when (val v = q[k]) {
            is JsonPrimitive -> if (v.isString) return v.content
            is JsonArray -> (v.firstOrNull() as? JsonObject)?.string("body")?.let { return it }
            else -> {}
        }
    }
    return null
}

/**
 * One line for a notification, like the web app's previewText. [content] is an event's content as decoded by
 * Panbeh's mappings; null for events that never notify. [sender] is the sender's display name (for emotes).
 */
fun previewText(content: Any, sender: String): String? = when (content) {
    is RoomMessageEventContent.Unknown -> {
        val c = content.raw
        val cap = c.caption()
        when (c.string("msgtype")) {
            "m.image" -> "🖼 " + (cap ?: "عکس")
            "m.video" ->
                if (c["app.panbeh.video_note"].truthy()) "📹 پیام ویدیویی " + fmtDuration((c["info"] as? JsonObject)?.number("duration")?.toLong() ?: 0L)
                else if (isGif("m.video", c["info"] as? JsonObject)) "🎞 " + (cap ?: "گیف")
                else "🎬 " + (cap ?: "ویدیو")
            "m.audio" -> if (c.isVoice()) "🎤 پیام صوتی " + fmtDuration(c.audioDuration()) else "🎵 صدا"
            "m.location" -> "📍 موقعیت مکانی"
            "m.file" -> "📎 " + (cap ?: c.string("filename") ?: c.string("body") ?: "فایل")
            "m.emote" -> "* $sender ${c.string("body") ?: ""}"
            "m.key.verification.request" -> "🔐 درخواست تأیید هویت"
            else -> stripReplyFallback(c.string("body") ?: "")
        }
    }
    is Sticker -> "استیکر"
    is PollStart -> "📊 " + (pollQuestion(content.raw) ?: "نظرسنجی")
    is PollStartStable -> "📊 " + (pollQuestion(content.raw) ?: "نظرسنجی")
    else -> null
}

/** True for an edit (m.replace): the original was notified already. */
fun isEdit(content: Any): Boolean {
    val raw = (content as? RoomMessageEventContent.Unknown)?.raw ?: return false
    return (raw["m.relates_to"] as? JsonObject)?.string("rel_type") == "m.replace"
}
