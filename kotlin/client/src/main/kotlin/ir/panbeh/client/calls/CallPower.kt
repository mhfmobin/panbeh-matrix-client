package ir.panbeh.client.calls

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.user
import de.connect2x.trixnity.clientserverapi.client.getStateEventContent
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.events.EventType
import de.connect2x.trixnity.core.model.events.m.room.PowerLevelsEventContent
import ir.panbeh.client.events.CallMember
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Whether we may start a MatrixRTC call in a room. */
enum class CallAccess {
    /** we can send org.matrix.msc3401.call.member */
    ALLOWED,
    /** we can't, but we can change the power levels: offer to enable calls for members ([CallPower.allowCalls]) */
    ASK_ENABLE,
    /** we can't and can't change it: "مدیر گروه هنوز تماس را برای اعضا فعال نکرده است" */
    DENIED,
}

const val CALLS_NOT_ENABLED = "مدیر گروه هنوز تماس را برای اعضا فعال نکرده است"

/** Thrown by [Calls.startCall] when the room doesn't let us join calls. */
class CallPermissionException(val access: CallAccess) : RuntimeException(CALLS_NOT_ENABLED)

/** The power check for calls: joining needs power to send org.matrix.msc3401.call.member. */
class CallPower(private val client: MatrixClient) {
    /** Whether we can send org.matrix.msc3401.call.member in the room right now (live). */
    fun canJoin(roomId: RoomId): Flow<Boolean> = client.user.canSendEvent(roomId, CallMember::class)

    suspend fun access(roomId: RoomId): CallAccess = when {
        canJoin(roomId).first() -> CallAccess.ALLOWED
        client.user.canSendEvent(roomId, PowerLevelsEventContent::class).first() -> CallAccess.ASK_ENABLE
        else -> CallAccess.DENIED
    }

    /** Lets ordinary members join calls: their call.member state needs power 0 (what Element's rooms set too). Needs admin. */
    suspend fun allowCalls(roomId: RoomId) {
        val pl = client.api.room.getStateEventContent<PowerLevelsEventContent>(roomId).getOrThrow()
        client.api.room.sendStateEvent(roomId, pl.copy(events = pl.events.filterKeys { it.name != CallTypes.MEMBER } + (EventType(null, CallTypes.MEMBER) to 0L))).getOrThrow()
    }
}
