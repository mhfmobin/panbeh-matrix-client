package ir.panbeh.client.timeline

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.room
import de.connect2x.trixnity.client.roomEventEncryptionServices
import de.connect2x.trixnity.client.room.decrypt
import de.connect2x.trixnity.client.store.TimelineEvent
import de.connect2x.trixnity.client.user
import de.connect2x.trixnity.core.model.EventId
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.UserId
import de.connect2x.trixnity.core.model.events.ClientEvent.RoomEvent
import de.connect2x.trixnity.core.model.events.m.room.EncryptedMessageEventContent
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** An event by id: from the local store, else fetched from the server and decrypted (reply quotes, pins, search results). Cached. */
class EventLoader(private val client: MatrixClient, private val roomId: String) {
    private val codec = Codec(client)
    private val asm = Assembler(codec, roomId, client.userId.full)
    private val lock = Mutex()
    private val cache = HashMap<String, TimelineItem?>()
    private val raws = HashMap<String, TimelineEvent>()

    /** The event as Trixnity has it (content null while it can't be decrypted); null if it can't be found. */
    suspend fun raw(eventId: String): TimelineEvent? {
        raws[eventId]?.let { return it }
        val room = RoomId(roomId)
        val id = EventId(eventId)
        val flow = client.room.getTimelineEvent(room, id) { fetchTimeout = Duration.ZERO; decryptionTimeout = 8.seconds }
        val local = flow.first()
        val te = if (local != null) {
            if (local.content != null) local
            else withTimeoutOrNull(10.seconds) { flow.filterNotNull().first { it.content != null } } ?: local
        } else {
            val ev = client.api.room.getEvent(room, id).getOrNull() ?: return null
            val content = (ev as? RoomEvent.MessageEvent<*>)?.takeIf { it.content is EncryptedMessageEventContent }?.let { m ->
                withTimeoutOrNull(8.seconds) { client.roomEventEncryptionServices.decrypt(m) }?.map { it as de.connect2x.trixnity.core.model.events.RoomEventContent }
                    ?: Result.failure(TimelineEvent.TimelineEventContentError.DecryptionTimeout)
            } ?: Result.success(ev.content)
            TimelineEvent(ev, content)
        }
        if (te.content != null) raws[eventId] = te
        return te
    }

    /** A [TimelineItem] for the event (no reactions or receipts); null if it can't be found. */
    suspend fun load(eventId: String): TimelineItem? = lock.withLock {
        if (eventId in cache) return cache[eventId]
        val te = raw(eventId) ?: return null
        val sender = te.event.sender
        val member = client.user.getById(RoomId(roomId), sender).first()
        val ctx = Ctx(members = listOfNotNull(member).associateBy { it.userId })
        val item = asm.build(listOf(Entry(te)), ctx, null, true, all = true).items.firstOrNull()
        if (item != null && item.decryption == Decryption.OK) cache[eventId] = item
        item
    }
}
