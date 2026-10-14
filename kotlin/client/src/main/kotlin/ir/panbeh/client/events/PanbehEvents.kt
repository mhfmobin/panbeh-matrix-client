package ir.panbeh.client.events

import de.connect2x.trixnity.core.model.events.GlobalAccountDataEventContent
import de.connect2x.trixnity.core.model.events.MessageEventContent
import de.connect2x.trixnity.core.model.events.RoomAccountDataEventContent
import de.connect2x.trixnity.core.model.events.StateEventContent
import de.connect2x.trixnity.core.model.events.m.Mentions
import de.connect2x.trixnity.core.model.events.m.RelatesTo
import de.connect2x.trixnity.core.model.events.m.room.RoomMessageEventContent
import de.connect2x.trixnity.core.serialization.events.EventContentSerializerMappings
import de.connect2x.trixnity.core.serialization.events.default
import de.connect2x.trixnity.core.serialization.events.globalAccountDataOf
import de.connect2x.trixnity.core.serialization.events.invoke
import de.connect2x.trixnity.core.serialization.events.messageOf
import de.connect2x.trixnity.core.serialization.events.roomAccountDataOf
import de.connect2x.trixnity.core.serialization.events.stateOf
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/*
 * Trixnity parses event content into typed classes and drops every field it doesn't know. Panbeh's messages carry
 * plenty of those (blurhash, albums, voice waveforms, GIF flags, forwarded-from, …), and it uses event types Trixnity
 * doesn't register at all (sync never delivers unregistered types). So:
 * - every m.room.message decodes to RoomMessageEventContent.Unknown, whose `raw` is the full JSON;
 * - Panbeh's other event types get small classes that also keep their raw JSON.
 * Use [panbehMappings] in MatrixClientConfiguration (see Accounts).
 */

/** The full JSON of an m.room.message, whatever Trixnity made of it. */
val RoomMessageEventContent.json: JsonObject
    get() = when (this) {
        is RoomMessageEventContent.Unknown -> raw
        else -> error("m.room.message must be decoded with RawMessageSerializer")
    }

/** Builds an m.room.message from raw JSON (msgtype and body are required by the spec). */
fun rawMessage(json: JsonObject): RoomMessageEventContent.Unknown = RoomMessageEventContent.Unknown(
    type = (json["msgtype"] as? JsonPrimitive)?.contentOrNull ?: "m.text",
    body = (json["body"] as? JsonPrimitive)?.contentOrNull ?: "",
    raw = json,
    format = (json["format"] as? JsonPrimitive)?.contentOrNull,
    formattedBody = (json["formatted_body"] as? JsonPrimitive)?.contentOrNull,
    relatesTo = null,
    mentions = null,
)

/** m.room.message ⇄ RoomMessageEventContent.Unknown with the whole JSON; relatesTo/mentions are kept in sync. */
object RawMessageSerializer : KSerializer<RoomMessageEventContent> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("PanbehRoomMessage")

    override fun deserialize(decoder: Decoder): RoomMessageEventContent {
        decoder as JsonDecoder
        val o = decoder.decodeJsonElement().jsonObject
        return RoomMessageEventContent.Unknown(
            type = (o["msgtype"] as? JsonPrimitive)?.contentOrNull ?: "m.text",
            body = (o["body"] as? JsonPrimitive)?.contentOrNull ?: "",
            raw = o,
            format = (o["format"] as? JsonPrimitive)?.contentOrNull,
            formattedBody = (o["formatted_body"] as? JsonPrimitive)?.contentOrNull,
            relatesTo = (o["m.relates_to"] as? JsonObject)?.let { runCatching { decoder.json.decodeFromJsonElement<RelatesTo>(it) }.getOrNull() },
            mentions = (o["m.mentions"] as? JsonObject)?.let { runCatching { decoder.json.decodeFromJsonElement<Mentions>(it) }.getOrNull() },
            externalUrl = (o["external_url"] as? JsonPrimitive)?.contentOrNull,
        )
    }

    override fun serialize(encoder: Encoder, value: RoomMessageEventContent) {
        encoder as JsonEncoder
        val base: JsonObject = when (value) {
            is RoomMessageEventContent.Unknown -> value.raw
            // typed contents built by Trixnity helpers: the default serializer, then our own fields stay as given
            else -> encoder.json.encodeToJsonElement(RoomMessageEventContent.Serializer, value).jsonObject
        }
        // Trixnity's builders (reply, thread, replace) set relations through copyWith: those win over raw
        val out = base.toMutableMap()
        value.relatesTo?.let { out["m.relates_to"] = encoder.json.encodeToJsonElement(it) }
        value.mentions?.let { out["m.mentions"] = encoder.json.encodeToJsonElement(it) }
        encoder.encodeJsonElement(JsonObject(out))
    }
}

/** Content of a type Trixnity doesn't know, kept as raw JSON. Relations are read from it so Trixnity indexes them. */
abstract class Raw(val raw: JsonObject)

private fun relatesOf(json: kotlinx.serialization.json.Json, raw: JsonObject) =
    (raw["m.relates_to"] as? JsonObject)?.let { runCatching { json.decodeFromJsonElement<RelatesTo>(it) }.getOrNull() }

/** Serializer for a [Raw] subclass: JSON in, JSON out. */
class RawSerializer<T : Raw>(name: String, private val make: (JsonObject, RelatesTo?) -> T) : KSerializer<T> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(name)
    override fun deserialize(decoder: Decoder): T {
        decoder as JsonDecoder
        val o = decoder.decodeJsonElement().jsonObject
        return make(o, relatesOf(decoder.json, o))
    }
    override fun serialize(encoder: Encoder, value: T) {
        encoder as JsonEncoder
        val out = value.raw.toMutableMap()
        (value as? MessageEventContent)?.relatesTo?.let { out["m.relates_to"] = encoder.json.encodeToJsonElement(it) }
        encoder.encodeJsonElement(JsonObject(out))
    }
}

/** A message event of a custom type. Each type needs its own class (Trixnity maps classes to types). */
abstract class RawMessage(raw: JsonObject, final override val relatesTo: RelatesTo?) : Raw(raw), MessageEventContent {
    override val mentions: Mentions? get() = null
    override val externalUrl: String? get() = null
}

class Reaction(raw: JsonObject, relatesTo: RelatesTo?) : RawMessage(raw, relatesTo) {
    override fun copyWith(relatesTo: RelatesTo?) = Reaction(raw, relatesTo)
    /** the reaction key: an emoji, or an mxc:// URI for a custom emoji */
    val key: String? get() = (relatesTo as? RelatesTo.Annotation)?.key
    /** com.beeper.reaction.shortcode of a custom emoji */
    val shortcode: String? get() = (raw["com.beeper.reaction.shortcode"] as? JsonPrimitive)?.contentOrNull
}
class Sticker(raw: JsonObject, relatesTo: RelatesTo?) : RawMessage(raw, relatesTo) { override fun copyWith(relatesTo: RelatesTo?) = Sticker(raw, relatesTo) }
class PollStart(raw: JsonObject, relatesTo: RelatesTo?) : RawMessage(raw, relatesTo) { override fun copyWith(relatesTo: RelatesTo?) = PollStart(raw, relatesTo) }
class PollResponse(raw: JsonObject, relatesTo: RelatesTo?) : RawMessage(raw, relatesTo) { override fun copyWith(relatesTo: RelatesTo?) = PollResponse(raw, relatesTo) }
class PollEnd(raw: JsonObject, relatesTo: RelatesTo?) : RawMessage(raw, relatesTo) { override fun copyWith(relatesTo: RelatesTo?) = PollEnd(raw, relatesTo) }
class PollStartStable(raw: JsonObject, relatesTo: RelatesTo?) : RawMessage(raw, relatesTo) { override fun copyWith(relatesTo: RelatesTo?) = PollStartStable(raw, relatesTo) }
class PollResponseStable(raw: JsonObject, relatesTo: RelatesTo?) : RawMessage(raw, relatesTo) { override fun copyWith(relatesTo: RelatesTo?) = PollResponseStable(raw, relatesTo) }
class PollEndStable(raw: JsonObject, relatesTo: RelatesTo?) : RawMessage(raw, relatesTo) { override fun copyWith(relatesTo: RelatesTo?) = PollEndStable(raw, relatesTo) }
/** MSC4075 ring / group call notification */
class RtcNotification(raw: JsonObject, relatesTo: RelatesTo?) : RawMessage(raw, relatesTo) { override fun copyWith(relatesTo: RelatesTo?) = RtcNotification(raw, relatesTo) }
class RtcNotificationStable(raw: JsonObject, relatesTo: RelatesTo?) : RawMessage(raw, relatesTo) { override fun copyWith(relatesTo: RelatesTo?) = RtcNotificationStable(raw, relatesTo) }
/** MSC4310 decline of a ring */
class RtcDecline(raw: JsonObject, relatesTo: RelatesTo?) : RawMessage(raw, relatesTo) { override fun copyWith(relatesTo: RelatesTo?) = RtcDecline(raw, relatesTo) }
class RtcDeclineStable(raw: JsonObject, relatesTo: RelatesTo?) : RawMessage(raw, relatesTo) { override fun copyWith(relatesTo: RelatesTo?) = RtcDeclineStable(raw, relatesTo) }
/** Element Call's in-call reactions */
class CallReaction(raw: JsonObject, relatesTo: RelatesTo?) : RawMessage(raw, relatesTo) { override fun copyWith(relatesTo: RelatesTo?) = CallReaction(raw, relatesTo) }

/** A state event of a custom type. */
abstract class RawState(raw: JsonObject) : Raw(raw), StateEventContent { override val externalUrl: String? get() = null }
/** org.matrix.msc3401.call.member (MatrixRTC membership, state key `_@user:server_DEVICE…`) */
class CallMember(raw: JsonObject) : RawState(raw)
/** im.ponies.room_emotes (MSC2545 room emoji/sticker pack, state key = pack id) */
class RoomEmotes(raw: JsonObject) : RawState(raw)

abstract class RawGlobal(raw: JsonObject) : Raw(raw), GlobalAccountDataEventContent
abstract class RawRoomData(raw: JsonObject) : Raw(raw), RoomAccountDataEventContent
class UserEmotes(raw: JsonObject) : RawGlobal(raw)
class EmoteRooms(raw: JsonObject) : RawGlobal(raw)
class SavedGifs(raw: JsonObject) : RawGlobal(raw)
class FolderOrder(raw: JsonObject) : RawGlobal(raw)
class MuteUntil(raw: JsonObject) : RawRoomData(raw)
class FamedlyMarkedUnread(raw: JsonObject) : RawRoomData(raw)

object Types {
    const val STICKER = "m.sticker"
    const val POLL_START = "org.matrix.msc3381.poll.start"
    const val POLL_RESPONSE = "org.matrix.msc3381.poll.response"
    const val POLL_END = "org.matrix.msc3381.poll.end"
    const val RTC_NOTIFICATION = "org.matrix.msc4075.rtc.notification"
    const val RTC_DECLINE = "org.matrix.msc4310.rtc.decline"
    const val CALL_REACTION = "io.element.call.reaction"
    const val CALL_MEMBER = "org.matrix.msc3401.call.member"
    const val ROOM_EMOTES = "im.ponies.room_emotes"
    const val USER_EMOTES = "im.ponies.user_emotes"
    const val EMOTE_ROOMS = "im.ponies.emote_rooms"
    const val GIFS = "app.panbeh.gifs"
    const val FOLDER_ORDER = "app.panbeh.folder_order"
    const val MUTE_UNTIL = "ir.panbeh.mute_until"
    const val FAMEDLY_UNREAD = "com.famedly.marked_unread"
}

private inline fun <reified T : RawMessage> msg(noinline f: (JsonObject, RelatesTo?) -> T) = RawSerializer(T::class.simpleName!!, f)
private inline fun <reified T : Raw> plain(noinline f: (JsonObject) -> T) = RawSerializer(T::class.simpleName!!) { o, _ -> f(o) }

/** Trixnity's default mappings plus Panbeh's. */
val panbehMappings: EventContentSerializerMappings = EventContentSerializerMappings.default(EventContentSerializerMappings {
    messageOf<RoomMessageEventContent>("m.room.message", RawMessageSerializer)
    messageOf<Reaction>("m.reaction", msg(::Reaction))
    messageOf<Sticker>(Types.STICKER, msg(::Sticker))
    messageOf<PollStart>(Types.POLL_START, msg(::PollStart))
    messageOf<PollResponse>(Types.POLL_RESPONSE, msg(::PollResponse))
    messageOf<PollEnd>(Types.POLL_END, msg(::PollEnd))
    messageOf<PollStartStable>("m.poll.start", msg(::PollStartStable))
    messageOf<PollResponseStable>("m.poll.response", msg(::PollResponseStable))
    messageOf<PollEndStable>("m.poll.end", msg(::PollEndStable))
    messageOf<RtcNotification>(Types.RTC_NOTIFICATION, msg(::RtcNotification))
    messageOf<RtcNotificationStable>("m.rtc.notification", msg(::RtcNotificationStable))
    messageOf<RtcDecline>(Types.RTC_DECLINE, msg(::RtcDecline))
    messageOf<RtcDeclineStable>("m.rtc.decline", msg(::RtcDeclineStable))
    messageOf<CallReaction>(Types.CALL_REACTION, msg(::CallReaction))
    stateOf<CallMember>(Types.CALL_MEMBER, plain(::CallMember))
    stateOf<RoomEmotes>(Types.ROOM_EMOTES, plain(::RoomEmotes))
    globalAccountDataOf<UserEmotes>(Types.USER_EMOTES, plain(::UserEmotes))
    globalAccountDataOf<EmoteRooms>(Types.EMOTE_ROOMS, plain(::EmoteRooms))
    globalAccountDataOf<SavedGifs>(Types.GIFS, plain(::SavedGifs))
    globalAccountDataOf<FolderOrder>(Types.FOLDER_ORDER, plain(::FolderOrder))
    roomAccountDataOf<MuteUntil>(Types.MUTE_UNTIL, plain(::MuteUntil))
    roomAccountDataOf<FamedlyMarkedUnread>(Types.FAMEDLY_UNREAD, plain(::FamedlyMarkedUnread))
})
