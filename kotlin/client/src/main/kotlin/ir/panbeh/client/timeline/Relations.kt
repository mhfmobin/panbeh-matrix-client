package ir.panbeh.client.timeline

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.room
import de.connect2x.trixnity.client.store.TimelineEvent
import de.connect2x.trixnity.core.model.EventId
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.events.m.RelationType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The decrypted events that relate to [eventId] with [type] (reactions, edits, thread replies, poll responses...), as they change. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun MatrixClient.related(roomId: RoomId, eventId: EventId, type: RelationType, decrypt: Duration = 10.seconds): Flow<List<TimelineEvent>> =
    room.getTimelineEventRelations(roomId, eventId, type)
        .flatMapLatest { m -> if (m.isNullOrEmpty()) flowOf(emptyList()) else combine(m.values) { rels -> rels.filterNotNull().map { it.eventId } } }
        .flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyList())
            else combine(ids.map { id ->
                room.getTimelineEvent(roomId, id) {
                    decryptionTimeout = decrypt
                    fetchTimeout = Duration.ZERO
                    allowReplaceContent = false
                }
            }) { evs -> evs.filterNotNull() }
        }
