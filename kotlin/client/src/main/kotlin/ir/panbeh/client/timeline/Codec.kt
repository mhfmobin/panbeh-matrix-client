package ir.panbeh.client.timeline

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.core.model.events.EventContent
import de.connect2x.trixnity.core.model.events.MessageEventContent
import de.connect2x.trixnity.core.model.events.RedactedEventContent
import de.connect2x.trixnity.core.model.events.RoomEventContent
import de.connect2x.trixnity.core.model.events.StateEventContent
import de.connect2x.trixnity.core.model.events.UnknownEventContent
import de.connect2x.trixnity.core.model.events.m.Mentions
import de.connect2x.trixnity.core.model.events.m.RelatesTo
import de.connect2x.trixnity.core.model.events.m.room.EncryptedMessageEventContent
import de.connect2x.trixnity.core.model.events.m.room.RoomMessageEventContent
import de.connect2x.trixnity.core.serialization.events.contentSerializer
import de.connect2x.trixnity.core.serialization.events.contentType
import ir.panbeh.client.events.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

private val MESSAGE_TYPES = setOf("m.room.message", Types.STICKER, Types.POLL_START, "m.poll.start")

/** Event types the timeline shows as messages (bubbles). */
fun isMessageType(type: String) = type in MESSAGE_TYPES || type == "m.room.encrypted"

fun isPollStart(type: String) = type == Types.POLL_START || type == "m.poll.start"

/** JSON ⇄ Trixnity content, keeping every field of Panbeh's raw message types. */
class Codec(val json: Json, val mappings: de.connect2x.trixnity.core.serialization.events.EventContentSerializerMappings) {
    constructor(client: MatrixClient) : this(client.api.json, client.api.eventContentSerializerMappings)

    /** The event type of a content. */
    fun typeOf(c: RoomEventContent): String = when (c) {
        is RoomMessageEventContent -> "m.room.message"
        is Sticker -> Types.STICKER
        is PollStart -> Types.POLL_START
        is PollStartStable -> "m.poll.start"
        is PollResponse -> Types.POLL_RESPONSE
        is PollResponseStable -> "m.poll.response"
        is PollEnd -> Types.POLL_END
        is PollEndStable -> "m.poll.end"
        is Reaction -> "m.reaction"
        is RtcNotification -> Types.RTC_NOTIFICATION
        is RtcNotificationStable -> "m.rtc.notification"
        is RtcDecline -> Types.RTC_DECLINE
        is RtcDeclineStable -> "m.rtc.decline"
        is CallReaction -> Types.CALL_REACTION
        is CallMember -> Types.CALL_MEMBER
        is RoomEmotes -> Types.ROOM_EMOTES
        is EncryptedMessageEventContent -> "m.room.encrypted"
        is RedactedEventContent -> c.eventType
        is UnknownEventContent -> c.eventType
        else -> (mappings.message + mappings.state).contentType(c)
    }

    /** The JSON of a content (without edit plumbing keys Trixnity adds). */
    fun jsonOf(c: RoomEventContent): JsonObject = when (c) {
        is RoomMessageEventContent.Unknown -> c.raw.clean()
        is Raw -> c.raw.clean()
        is RedactedEventContent -> JsonObject(emptyMap())
        is UnknownEventContent -> c.raw
        is MessageEventContent -> json.encodeToJsonElement(mappings.message.contentSerializer(c), c) as JsonObject
        is StateEventContent -> json.encodeToJsonElement(mappings.state.contentSerializer(c), c) as JsonObject
        else -> JsonObject(emptyMap())
    }

    fun relatesOf(o: JsonObject): RelatesTo? =
        (o["m.relates_to"] as? JsonObject)?.let { runCatching { json.decodeFromJsonElement<RelatesTo>(it) }.getOrNull() }

    private fun mentionsOf(o: JsonObject): Mentions? =
        (o["m.mentions"] as? JsonObject)?.let { runCatching { json.decodeFromJsonElement<Mentions>(it) }.getOrNull() }

    /** Outgoing content of event [type] from JSON. Relations and mentions are typed too, so Trixnity keeps them in clear text on encrypted events. */
    fun outgoing(type: String, o: JsonObject): MessageEventContent {
        val rel = relatesOf(o)
        return when (type) {
            "m.room.message" -> RoomMessageEventContent.Unknown(
                type = (o["msgtype"] as? JsonPrimitive)?.contentOrNull ?: "m.text",
                body = (o["body"] as? JsonPrimitive)?.contentOrNull ?: "",
                raw = o,
                format = (o["format"] as? JsonPrimitive)?.contentOrNull,
                formattedBody = (o["formatted_body"] as? JsonPrimitive)?.contentOrNull,
                relatesTo = rel,
                mentions = mentionsOf(o),
            )
            Types.STICKER -> Sticker(o, rel)
            Types.POLL_START -> PollStart(o, rel)
            "m.poll.start" -> PollStartStable(o, rel)
            Types.POLL_RESPONSE -> PollResponse(o, rel)
            "m.poll.response" -> PollResponseStable(o, rel)
            Types.POLL_END -> PollEnd(o, rel)
            "m.poll.end" -> PollEndStable(o, rel)
            "m.reaction" -> Reaction(o, rel)
            else -> throw IllegalArgumentException("can't send $type")
        }
    }

    /** `{user_ids:[...] , room?}` */
    fun mentionsJson(ids: Collection<String>, room: Boolean = false) = buildJsonObject {
        put("user_ids", JsonArray(ids.map { JsonPrimitive(it) }))
        if (room) put("room", true)
    }
}

/** Trixnity adds "type" to a replacement content (m.new_content) to pick its deserializer; no message content has such a key. */
private fun JsonObject.clean(): JsonObject {
    val t = (this["type"] as? JsonPrimitive)?.contentOrNull
    return if (t != null && (t.startsWith("m.") || t.startsWith("org.matrix."))) JsonObject(this - "type") else this
}
