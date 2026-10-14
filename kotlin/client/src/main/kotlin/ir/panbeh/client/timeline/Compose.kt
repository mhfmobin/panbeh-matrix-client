package ir.panbeh.client.timeline

import ir.panbeh.core.media.osmUrl
import ir.panbeh.core.text.Mention
import ir.panbeh.core.text.escRe
import ir.panbeh.core.text.formatMessage
import ir.panbeh.core.util.jsNum
import ir.panbeh.core.util.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Content builders: pure functions from what the user typed to event content JSON, as the web composer does. */
object Compose {
    /** A person who can be "@mentioned" in a room (joined, not me). */
    data class Candidate(val name: String, val id: String)

    private fun ids(a: Collection<String>) = JsonArray(a.map { JsonPrimitive(it) })

    /**
     * Text content: body, HTML when anything got formatted (markdown subset, mention links, custom emoji), and m.mentions.
     * "@Name" / "@user:server" typed out without picking from the list counts as a mention too.
     * [replySender]: the author of the replied-to event, who is mentioned (unless it's me). [canPingRoom]: "@room" in the text pings everyone.
     */
    fun text(
        body: String, msgtype: String = "m.text", picked: List<Mention> = emptyList(), members: List<Candidate> = emptyList(),
        emotes: Map<String, String> = emptyMap(), replySender: String? = null, me: String? = null, canPingRoom: Boolean = false,
    ): JsonObject {
        val typed = members.filter { it.id != me }
            .flatMap { listOf(Mention(it.name, it.id), Mention(it.id.drop(1), it.id)) }
            .filter { Regex("@" + escRe(it.name) + "(?![\\p{L}\\p{N}_])").containsMatchIn(body) }
        val html = formatMessage(body, picked + typed, emotes)
        val pinged = LinkedHashSet<String>().also { s -> html?.ids?.let(s::addAll) }
        if (replySender != null && replySender != me) pinged.add(replySender)
        val everyone = Regex("(^|\\s)@room\\b").containsMatchIn(body) && canPingRoom
        return buildJsonObject {
            put("msgtype", msgtype)
            put("body", body)
            if (html != null) { put("format", "org.matrix.custom.html"); put("formatted_body", html.html) }
            putJsonObject("m.mentions") { put("user_ids", ids(pinged)); if (everyone) put("room", true) }
        }
    }

    /** m.relates_to for a reply, a thread message, or a thread reply (js-sdk's addThreadRelationIfNeeded). */
    fun relation(replyTo: String?, threadRoot: String?, threadLatest: String?): JsonObject? = when {
        threadRoot != null -> buildJsonObject {
            put("rel_type", "m.thread")
            put("event_id", threadRoot)
            putJsonObject("m.in_reply_to") { put("event_id", replyTo ?: threadLatest ?: threadRoot) }
            put("is_falling_back", replyTo == null)
        }
        replyTo != null -> buildJsonObject { putJsonObject("m.in_reply_to") { put("event_id", replyTo) } }
        else -> null
    }

    fun JsonObject.withRelation(rel: JsonObject?) = if (rel == null) this else JsonObject(this + ("m.relates_to" to rel))

    /**
     * An edit of [old] (its current content, edits applied): m.replace + m.new_content + "* " body. Only newly added mentions are
     * pinged. A media message's caption edit replaces the whole content keeping the file (an empty caption = just the file name).
     * [next] is the new text content (see [text]). Null when the text didn't change.
     */
    fun edit(originalId: String, old: JsonObject, next: JsonObject, newText: String): JsonObject? {
        val media = old.string("msgtype") in listOf("m.image", "m.video", "m.file")
        val oldText = if (media) captionOf(old) else stripReplyFallback(old.string("body") ?: "")
        if (newText == oldText) return null
        val before = ((old["m.mentions"] as? JsonObject)?.get("user_ids") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet() ?: emptySet()
        val content: JsonObject
        if (media) { // the replacement is the whole content: keep the file, swap the caption
            val name = old.string("filename") ?: old.string("body") ?: ""
            val base = JsonObject(old - "format" - "formatted_body" - "m.relates_to" - "m.mentions")
            val cap = if (newText.isNotEmpty()) JsonObject(next - "msgtype") else buildJsonObject { put("body", name); put("m.mentions", buildJsonObject { put("user_ids", JsonArray(emptyList())) }) }
            content = JsonObject(base + cap + ("filename" to JsonPrimitive(name)))
        } else content = JsonObject(next + ("msgtype" to JsonPrimitive(old.string("msgtype") ?: "m.text")))
        val nowIds = ((content["m.mentions"] as? JsonObject)?.get("user_ids") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
        return buildJsonObject {
            if (media) content.forEach { (k, v) -> put(k, v) } else put("msgtype", content["msgtype"]!!)
            put("body", "* " + (content.string("body") ?: ""))
            put("m.new_content", content)
            put("m.mentions", buildJsonObject { put("user_ids", ids(nowIds.filter { it !in before })) })
            putJsonObject("m.relates_to") { put("rel_type", "m.replace"); put("event_id", originalId) }
        }
    }

    /** A copy of [c] for another room; keeps the original author across re-forwards (app.panbeh.forwarded), never pings anyone. */
    fun forward(c: JsonObject, sender: String, senderName: String): JsonObject {
        val m = (c - "m.relates_to" - "m.new_content").toMutableMap()
        m["m.mentions"] = JsonObject(emptyMap())
        c.string("body")?.let { m["body"] = JsonPrimitive(stripReplyFallback(it)) }
        c.string("formatted_body")?.let { m["formatted_body"] = JsonPrimitive(it.replace(Regex("<mx-reply>[\\s\\S]*?</mx-reply>"), "")) }
        m["app.panbeh.forwarded"] = c["app.panbeh.forwarded"] ?: buildJsonObject { put("sender", sender); put("name", senderName) }
        return JsonObject(m)
    }

    /** m.location content like js-sdk's makeLocationContent; body keeps a map link for clients that don't show locations. */
    fun location(lat: Double, lon: Double, accuracy: Int, ts: Long = System.currentTimeMillis()): JsonObject {
        val la = Math.round(lat * 1e6) / 1e6
        val lo = Math.round(lon * 1e6) / 1e6
        val text = "موقعیت مکانی ${osmUrl(la, lo)}"
        val uri = "geo:${jsNum(la)},${jsNum(lo)};u=$accuracy"
        return buildJsonObject {
            put("msgtype", "m.location")
            put("body", text)
            put("geo_uri", uri)
            putJsonObject("org.matrix.msc3488.location") { put("description", JsonNull); put("uri", uri) }
            putJsonObject("org.matrix.msc3488.asset") { put("type", "m.self") }
            put("org.matrix.msc1767.text", text)
            put("org.matrix.msc3488.ts", ts)
        }
    }

    fun sticker(body: String, url: String, info: JsonObject? = null) = buildJsonObject {
        put("body", body); put("url", url); put("info", info ?: JsonObject(emptyMap()))
    }

    fun reaction(target: String, key: String, shortcode: String? = null) = buildJsonObject {
        putJsonObject("m.relates_to") { put("rel_type", "m.annotation"); put("event_id", target); put("key", key) }
        if (!shortcode.isNullOrEmpty()) put("com.beeper.reaction.shortcode", shortcode)
    }
}
