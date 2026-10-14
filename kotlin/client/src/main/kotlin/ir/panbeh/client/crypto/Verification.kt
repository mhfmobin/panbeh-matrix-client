package ir.panbeh.client.crypto

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.verification
import de.connect2x.trixnity.client.verification.ActiveSasVerificationMethod
import de.connect2x.trixnity.client.verification.ActiveSasVerificationState
import de.connect2x.trixnity.client.verification.ActiveVerification
import de.connect2x.trixnity.client.verification.ActiveVerificationState
import de.connect2x.trixnity.core.model.EventId
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.UserId
import de.connect2x.trixnity.core.model.events.m.key.verification.VerificationMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One emoji of the comparison with its Persian name. */
data class SasEmoji(val emoji: String, val name: String)

/** What the verification sheet shows. */
sealed interface VerifyPhase {
    /** [byMe]: we asked and wait for them; otherwise they asked and we can [Verification.accept] or [Verification.cancel]. */
    data class Requested(val byMe: Boolean) : VerifyPhase
    /** The request is accepted and nobody started a comparison yet: offer [Verification.startSas]. */
    data object Ready : VerifyPhase
    /** Waiting for the other side's comparison data. */
    data object Starting : VerifyPhase
    /** Show [emoji] (or [decimal] when the other side only does numbers) and ask [Verification.confirmMatch] / [Verification.mismatch]. */
    data class Compare(val emoji: List<SasEmoji>, val decimal: List<Int>) : VerifyPhase
    /** We confirmed; waiting for them. */
    data object WaitingForOther : VerifyPhase
    data object Done : VerifyPhase
    /** [reason] is Persian. */
    data class Cancelled(val reason: String) : VerifyPhase
}

/** The verification on screen. [self]: verifying one of my own devices. */
data class VerifyUi(val id: String, val self: Boolean, val otherUserId: String, val otherDeviceId: String?, val byMe: Boolean, val phase: VerifyPhase) {
    val finished get() = phase is VerifyPhase.Done || phase is VerifyPhase.Cancelled
}

/**
 * Device and user verification with SAS (emoji and decimal) on top of Trixnity's VerificationService; one verification at a time,
 * like the web app. There is no QR-code verification: Trixnity only implements m.sas.v1, so only SAS is offered.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Verification(private val client: MatrixClient, private val scope: CoroutineScope) {
    private val service = client.verification
    private class Entry(val v: ActiveVerification, val byMe: Boolean)
    private val active = MutableStateFlow<Entry?>(null)
    private var driver: Job? = null
    private val _incoming = MutableSharedFlow<VerifyUi>(extraBufferCapacity = 8)

    /** The verification being shown, null when none. */
    val current: StateFlow<VerifyUi?> = active.flatMapLatest { e -> if (e == null) flowOf(null) else ui(e) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Requests other devices or users sent us. Each is also shown as [current] unless another verification is still going. */
    val incoming: SharedFlow<VerifyUi> get() = _incoming

    init {
        val seen = HashSet<Any>()
        suspend fun offer(v: ActiveVerification) {
            if (v.state.value !is ActiveVerificationState.TheirRequest) return
            if (!seen.add(v.transactionId ?: v.relatesTo ?: return)) return
            val e = Entry(v, false)
            _incoming.emit(mk(e, VerifyPhase.Requested(false)))
            val cur = active.value
            if (cur == null || cur.v.state.value.isFinished()) show(e)
        }
        scope.launch { service.activeDeviceVerification.collect { it?.let { v -> offer(v) } } }
        scope.launch { service.activeUserVerifications.collect { l -> l.forEach { offer(it) } } }
    }

    private fun mk(e: Entry, p: VerifyPhase) = VerifyUi(
        e.v.transactionId ?: e.v.relatesTo?.eventId?.full ?: "", e.v.theirUserId == client.userId, e.v.theirUserId.full, e.v.theirDeviceId, e.byMe, p,
    )

    private fun ActiveVerificationState.isFinished() =
        this is ActiveVerificationState.Done || this is ActiveVerificationState.Cancel || this is ActiveVerificationState.AcceptedByOtherDevice

    private fun show(e: Entry?) {
        driver?.cancel()
        active.value = e
        driver = e?.let { en -> scope.launch { autoDrive(en) } }
    }

    /** What the web app does without asking: start SAS when we asked, accept the other side's SAS start. */
    private suspend fun autoDrive(e: Entry) {
        e.v.state.flatMapLatest { s ->
            if (s is ActiveVerificationState.Start) (s.method as? ActiveSasVerificationMethod)?.state?.map { s to it } ?: flowOf(s to null) else flowOf(s to null)
        }.collect { (s, sas) ->
            runCatching {
                if (s is ActiveVerificationState.Ready && e.byMe && VerificationMethod.Sas in s.methods) s.start(VerificationMethod.Sas)
                if (sas is ActiveSasVerificationState.TheirSasStart) sas.accept()
            }
        }
    }

    private fun ui(e: Entry): Flow<VerifyUi> {
        return e.v.state.flatMapLatest { s ->
            val sas = (s as? ActiveVerificationState.Start)?.method as? ActiveSasVerificationMethod
            if (sas != null) sas.state.map { mk(e, sasPhase(it)) } else flowOf(mk(e, phase(s, e.byMe)))
        }
    }

    private fun phase(s: ActiveVerificationState, byMe: Boolean): VerifyPhase = when (s) {
        is ActiveVerificationState.OwnRequest -> VerifyPhase.Requested(true)
        is ActiveVerificationState.TheirRequest -> VerifyPhase.Requested(false)
        is ActiveVerificationState.Ready -> if (byMe) VerifyPhase.Starting else VerifyPhase.Ready
        is ActiveVerificationState.Start -> VerifyPhase.Starting
        is ActiveVerificationState.WaitForDone -> VerifyPhase.WaitingForOther
        ActiveVerificationState.Done -> VerifyPhase.Done
        is ActiveVerificationState.Cancel -> VerifyPhase.Cancelled(cancelReason(s.content.code.value))
        ActiveVerificationState.AcceptedByOtherDevice -> VerifyPhase.Cancelled(cancelReason("m.accepted"))
        ActiveVerificationState.Undefined -> VerifyPhase.Cancelled(cancelReason(null))
    }

    private fun sasPhase(s: ActiveSasVerificationState): VerifyPhase = when (s) {
        is ActiveSasVerificationState.ComparisonByUser ->
            VerifyPhase.Compare(s.emojis.map { (i, e) -> SasEmoji(e, SAS_EMOJI_FA.getOrElse(i) { e }) }, s.decimal)
        ActiveSasVerificationState.WaitForMacs -> VerifyPhase.WaitingForOther
        else -> VerifyPhase.Starting
    }

    /** Asks my other devices to verify this one (or each other). */
    suspend fun requestSelf() {
        val others = client.api.device.getDevices().getOrThrow().map { it.deviceId }.filter { it != client.deviceId }.toSet()
        if (others.isEmpty()) throw IllegalStateException("دستگاه دیگری برای تأیید نیست")
        start(service.createDeviceVerificationRequest(client.userId, others).getOrThrow())
    }

    /** Asks one of my other devices. */
    suspend fun verifyDevice(deviceId: String) = start(service.createDeviceVerificationRequest(client.userId, setOf(deviceId)).getOrThrow())

    /** Verifies another user in a direct chat with them (created when missing). */
    suspend fun verifyUser(userId: String) = start(service.createUserVerificationRequest(UserId(userId)).getOrThrow())

    private fun start(v: ActiveVerification) = show(Entry(v, true))

    /** Shows a request found in a chat (an `m.key.verification.request` event). */
    suspend fun openFromEvent(roomId: RoomId, eventId: EventId): Boolean {
        val v = service.getActiveUserVerification(roomId, eventId) ?: return false
        show(Entry(v, v.state.value is ActiveVerificationState.OwnRequest))
        return true
    }

    private fun state() = active.value?.v?.state?.value
    private fun sasState() = ((state() as? ActiveVerificationState.Start)?.method as? ActiveSasVerificationMethod)?.state?.value

    /** Accepts an incoming request. */
    suspend fun accept() { (state() as? ActiveVerificationState.TheirRequest)?.ready() }

    /** After the other side accepted: starts the emoji comparison. */
    suspend fun startSas() { (state() as? ActiveVerificationState.Ready)?.start(VerificationMethod.Sas) }

    /** The emoji (or numbers) are the same on both devices. */
    suspend fun confirmMatch() { (sasState() as? ActiveSasVerificationState.ComparisonByUser)?.match() }

    /** They differ: cancels the verification. */
    suspend fun mismatch() { (sasState() as? ActiveSasVerificationState.ComparisonByUser)?.noMatch() }

    /** Declines or aborts, then clears the sheet. */
    suspend fun cancel() {
        val e = active.value ?: return
        if (!e.v.state.value.isFinished()) runCatching { e.v.cancel() }
        show(null)
    }

    /** Clears the sheet (cancels first when not finished). */
    suspend fun close() = cancel()
}
