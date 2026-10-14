package ir.panbeh.client.timeline

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.room
import de.connect2x.trixnity.client.room.getState
import de.connect2x.trixnity.client.user
import de.connect2x.trixnity.client.user.canSendEvent
import de.connect2x.trixnity.core.model.EventId
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.events.m.room.PinnedEventsEventContent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Pinned messages of a room (m.room.pinned_events): the ids oldest pin first, pin / unpin, and the pinned bar's cycling. */
class Pins(private val client: MatrixClient, val roomId: String) {
    private val room = RoomId(roomId)
    private val loader = EventLoader(client, roomId)

    /** Event ids of the pinned messages, oldest pin first; the bar shows the newest. */
    val ids: Flow<List<String>> = client.room.getState<PinnedEventsEventContent>(room).map { s -> s?.content?.pinned?.map { it.full } ?: emptyList() }.distinctUntilChanged()

    /** Whether I may pin and unpin here. */
    val canPin: Flow<Boolean> = client.user.canSendEvent<PinnedEventsEventContent>(room)

    /** Pins or unpins [eventId] (adds it at the end / removes it). Returns true when it is pinned now. */
    suspend fun toggle(eventId: String): Boolean {
        val cur = ids.first()
        val next = if (eventId in cur) cur - eventId else cur + eventId
        client.api.room.sendStateEvent(room, PinnedEventsEventContent(next.map { EventId(it) })).getOrThrow()
        return eventId in next
    }

    suspend fun pin(eventId: String) { if (eventId !in ids.first()) toggle(eventId) }

    suspend fun unpin(eventId: String) { if (eventId in ids.first()) toggle(eventId) }

    /** The pinned message itself (preview in the bar and in the list): from the store, else fetched and decrypted. */
    suspend fun load(eventId: String): TimelineItem? = loader.load(eventId)

    companion object {
        /** The pin the bar shows: [index] (null = the newest), kept in range as pins come and go. */
        fun current(ids: List<String>, index: Int?): Int = if (index == null || index >= ids.size) ids.size - 1 else index

        /** After a tap on the bar's pin at [current] it jumps there, and the bar moves on to the next older one. */
        fun next(ids: List<String>, current: Int): Int = (current - 1 + ids.size) % ids.size

        /** "پیام سنجاق‌شده ۲ از ۳" */
        fun label(ids: List<String>, current: Int): String =
            "پیام سنجاق‌شده" + if (ids.size > 1) " ${ir.panbeh.core.time.num(current + 1)} از ${ir.panbeh.core.time.num(ids.size)}" else ""
    }
}
