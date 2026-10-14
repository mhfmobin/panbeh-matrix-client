package ir.panbeh.client.timeline

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.room
import de.connect2x.trixnity.client.roomEventEncryptionServices
import de.connect2x.trixnity.client.room.encrypt
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.serialization.events.contentSerializer
import ir.panbeh.client.KeyValueStore
import ir.panbeh.client.errcode
import ir.panbeh.core.text.formatMessage
import ir.panbeh.core.time.fmtSpan
import ir.panbeh.core.util.number
import ir.panbeh.core.util.string
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** A message waiting on the server to be sent at [sendAt]. [content] is null when it is encrypted and wasn't scheduled from this device. */
data class ScheduledMessage(val id: String, val roomId: String, val sendAt: Long, val type: String, val content: JsonObject?)

/** The server refuses a delay (too far ahead); the message is Persian. */
class ScheduleException(message: String) : Exception(message)

private const val FEATURE = "org.matrix.msc4140"
private val LIST_TYPES = listOf("m.room.message", "m.room.encrypted", "org.matrix.msc3381.poll.start")

/**
 * Scheduled messages (MSC4140 delayed events). The server holds an event and sends it later; in encrypted rooms we encrypt it now
 * with the room's current session (the server only holds ciphertext), and keep the plain content on this device, keyed by delay id,
 * so the list can show it. [store] keeps that in `panbeh.scheduled:<userId>`.
 */
class Scheduler(private val client: MatrixClient, private val store: KeyValueStore, private val sender: Sender = Sender(client)) {
    private val json = Json { ignoreUnknownKeys = true }
    private val codec = sender.codec
    private val key get() = "panbeh.scheduled:${client.userId.full}"
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    @Volatile private var supported: Boolean? = null

    /** Whether the server keeps messages to send later. Synapse does once its admin sets max_event_delay_duration. */
    suspend fun supported(): Boolean {
        supported?.let { return it }
        val v = client.serverData.value?.versions?.unstableFeatures?.get(FEATURE)
            ?: runCatching { client.api.server.getVersions().getOrThrow().unstableFeatures?.get(FEATURE) }.getOrNull() ?: return false
        return v.also { supported = it }
    }

    private fun kept(): JsonObject = runCatching { json.parseToJsonElement(store[key] ?: "{}") as JsonObject }.getOrDefault(JsonObject(emptyMap()))
    private fun keep(o: JsonObject) { store[key] = o.toString() }

    /**
     * Sends [content] (an m.room.message, or [type]) into the room at [sendAt] (ms). Returns the delay id.
     * Throws [ScheduleException] (Persian) when the server's delay limit is exceeded.
     */
    suspend fun schedule(roomId: String, content: JsonObject, sendAt: Long, type: String = "m.room.message"): String {
        val out = codec.outgoing(type, content)
        var wireType = type
        var wire: String = codec.json.encodeToString(codec.mappings.message.contentSerializer(type, out), out)
        if (client.room.getById(RoomId(roomId)).first()?.encrypted == true) {
            val enc = client.roomEventEncryptionServices.encrypt(out, RoomId(roomId))?.getOrThrow() ?: throw ScheduleException("زمان‌بندی در گفتگوهای رمزنگاری‌شده با این نسخه ممکن نیست")
            wireType = "m.room.encrypted"
            wire = codec.json.encodeToString(codec.mappings.message.contentSerializer(wireType, enc), enc)
        }
        val id = try {
            client.sendDelayed(roomId, wireType, UUID.randomUUID().toString().replace("-", ""), maxOf(1000, sendAt - System.currentTimeMillis()), wire)
        } catch (e: Exception) {
            if (e.errcode?.let { Regex("MAX_DELAY_EXCEEDED|DELAY_TOO_LARGE", RegexOption.IGNORE_CASE).containsMatchIn(it) } == true) {
                // the MSC: M_MAX_DELAY_EXCEEDED with max_delay; Synapse: the maximum only in the message text
                val max = Regex("maximum \\((\\d+)ms\\)").find(e.message ?: "")?.groupValues?.get(1)?.toLongOrNull()
                    ?: Regex("maximum \\((\\d+)ms\\)").find((e as? de.connect2x.trixnity.core.MatrixServerException)?.errorResponse?.error ?: "")?.groupValues?.get(1)?.toLongOrNull()
                throw ScheduleException(if (max != null && max > 0) "سرور فقط تا ${fmtSpan(max)} بعد را زمان‌بندی می‌کند" else "این زمان برای سرور خیلی دور است")
            }
            throw e
        }
        keep(JsonObject(kept() + (id to buildJsonObject { put("roomId", roomId); put("type", type); put("content", content) })))
        changes.tryEmit(Unit)
        return id
    }

    /** The room's scheduled messages, soonest first (only messages and polls; Element Call schedules state events this way too). */
    suspend fun list(roomId: String): List<ScheduledMessage> {
        // newer servers answer { scheduled }, Synapse { delayed_events }
        val r = client.listDelayed()
        val all = ((r["scheduled"] ?: r["delayed_events"]) as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        val k = kept()
        val live = all.mapNotNull { it.string("delay_id") }.toSet()
        if (k.keys.any { it !in live }) keep(JsonObject(k.filterKeys { it in live })) // sent or cancelled
        return all.filter { it.string("room_id") == roomId && "state_key" !in it && it.string("type") in LIST_TYPES }.mapNotNull { d ->
            val id = d.string("delay_id") ?: return@mapNotNull null
            val keptItem = k[id] as? JsonObject
            val type = d.string("type")!!
            ScheduledMessage(
                id, roomId, ((d.number("running_since") ?: 0.0) + (d.number("delay") ?: 0.0)).toLong(), keptItem?.string("type") ?: type,
                (keptItem?.get("content") as? JsonObject) ?: if (type == "m.room.encrypted") null else d["content"] as? JsonObject,
            )
        }.sortedBy { it.sendAt }
    }

    /** The room's scheduled messages, kept fresh: re-read when ours change and when the next one is due to go out. */
    fun observe(roomId: String): Flow<List<ScheduledMessage>> = flow {
        while (true) {
            val l = runCatching { list(roomId) }.getOrDefault(emptyList())
            emit(l)
            val wait = l.firstOrNull()?.let { (it.sendAt - System.currentTimeMillis() + 3000).coerceIn(1000L, 3_600_000L) } ?: 3_600_000L
            withTimeoutOrNull(wait) { changes.first() }
        }
    }

    suspend fun cancel(id: String) { client.delayedAction(id, "cancel"); changes.tryEmit(Unit) }

    suspend fun sendNow(id: String) { client.delayedAction(id, "send"); changes.tryEmit(Unit) }

    /**
     * Changes a scheduled message: the text (text messages; null keeps it) and the time. The new one is scheduled before the old one goes.
     * Returns the new delay id.
     */
    suspend fun edit(roomId: String, s: ScheduledMessage, newText: String?, sendAt: Long): String {
        val c = s.content ?: error("encrypted on another device")
        var next = c
        val body = newText?.trim()
        if (body != null && body != c.string("body") && c.string("msgtype") in listOf("m.text", "m.emote", "m.notice")) {
            val html = formatMessage(body, emptyList())
            next = JsonObject(c - "format" - "formatted_body" + ("body" to JsonPrimitive(body)) +
                (if (html != null) mapOf("format" to JsonPrimitive("org.matrix.custom.html"), "formatted_body" to JsonPrimitive(html.html)) else emptyMap()))
        }
        val id = schedule(roomId, next, sendAt, s.type)
        cancel(s.id)
        return id
    }
}

/** Schedules a text message built like [Sender.sendText]. */
suspend fun Scheduler.scheduleText(sender: Sender, roomId: String, text: String, sendAt: Long, o: TextOptions = TextOptions()): String =
    schedule(roomId, sender.composeText(roomId, text, o), sendAt)
