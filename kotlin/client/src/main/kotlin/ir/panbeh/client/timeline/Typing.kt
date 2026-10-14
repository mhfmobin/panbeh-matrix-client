package ir.panbeh.client.timeline

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.room
import de.connect2x.trixnity.client.user
import de.connect2x.trixnity.core.model.RoomId
import ir.panbeh.core.time.num
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform

/** Who else is typing in a room, as it changes (names are the room's display names). */
fun MatrixClient.typingIn(roomId: String): Flow<List<TypingUser>> {
    val room = RoomId(roomId)
    return this.room.usersTyping.map { it[room]?.users?.filter { u -> u != userId }?.map { u -> u.full } ?: emptyList() }.distinctUntilChanged().transform { ids ->
        emit(ids.map { id -> TypingUser(id, user.getById(room, de.connect2x.trixnity.core.model.UserId(id)).first()?.name ?: id) })
    }
}

/** "A و B در حال نوشتن" / "A، B و ۲ نفر دیگر در حال نوشتن" (first names); null when nobody types. */
fun typingText(names: List<String>): String? {
    if (names.isEmpty()) return null
    val first = names.map { bdi(it.split(" ").first()) }
    return (if (first.size > 2) "${first.take(2).joinToString("، ")} و ${num(first.size - 2)} نفر دیگر" else first.joinToString(" و ")) + " در حال نوشتن"
}
