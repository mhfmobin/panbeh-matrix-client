package ir.panbeh.client.timeline

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.room
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.events.InitialStateEvent
import de.connect2x.trixnity.core.model.events.m.room.EncryptionEventContent
import de.connect2x.trixnity.core.model.events.m.room.Membership
import ir.panbeh.client.Accounts
import ir.panbeh.client.register
import ir.panbeh.client.testPlatform
import ir.panbeh.client.testServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** Two users in a room on the test homeserver. */
class Duo private constructor(val a: MatrixClient, val b: MatrixClient, val roomId: String) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val sa = Sender(a)
    val sb = Sender(b)
    val aName = a.userId.full

    fun timelineA(thread: String? = null, opts: TimelineOptions = TimelineOptions()) = RoomTimeline(a, roomId, scope, thread, opts)
    fun timelineB(thread: String? = null, opts: TimelineOptions = TimelineOptions()) = RoomTimeline(b, roomId, scope, thread, opts)

    suspend fun close() {
        scope.cancel()
        a.closeSuspending(); b.closeSuspending()
    }

    companion object {
        suspend fun login(prefix: String): MatrixClient {
            val (u, p) = register(prefix)
            return Accounts(testPlatform()).loginPassword(testServer!!, u, p).also { it.startSync(); withTimeout(30_000) { it.initialSyncDone.first { d -> d } } }
        }

        /** [encrypted]: an end-to-end encrypted room (the default); b has joined. */
        suspend fun create(encrypted: Boolean = true, prefix: String = "t"): Duo {
            val a = login(prefix + "a")
            val b = login(prefix + "b")
            val roomId = a.api.room.createRoom(
                invite = setOf(b.userId),
                initialState = if (encrypted) listOf(InitialStateEvent(EncryptionEventContent(), "")) else null,
            ).getOrThrow()
            withTimeout(30_000) { b.room.getById(roomId).filterNotNull().first { it.membership == Membership.INVITE } }
            b.api.room.joinRoom(roomId).getOrThrow()
            withTimeout(30_000) { a.room.getById(roomId).filterNotNull().first { true } }
            return Duo(a, b, roomId.full)
        }
    }
}

/** Polls [cond] until it holds (the sync loops need a moment), failing with [what] after [ms]. */
suspend fun <T : Any> eventually(what: String, ms: Long = 60_000, cond: suspend () -> T?): T {
    val end = System.currentTimeMillis() + ms
    var last: Throwable? = null
    while (System.currentTimeMillis() < end) {
        try { cond()?.let { return it } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { last = e }
        delay(300)
    }
    throw AssertionError("timed out waiting for $what" + (last?.let { ": $it" } ?: ""), last)
}

/** The first item of [t] matching [p]. */
suspend fun RoomTimeline.item(what: String = "item", ms: Long = 60_000, p: (TimelineItem) -> Boolean) = eventually(what, ms) { state.value.items.firstOrNull(p) }

@Suppress("unused") private val keep = RoomId("!x:y")
