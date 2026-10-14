package ir.panbeh.client.calls

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.room
import de.connect2x.trixnity.core.model.RoomId
import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock

/** The call we are in, for the call screen. */
sealed interface ActiveCall {
    val roomId: RoomId
    val video: Boolean
    /** MatrixRTC: connect LiveKit with [connection], E2EE keys from `connection.keys`, membership state from [session]. */
    data class Rtc(override val roomId: RoomId, override val video: Boolean, val session: CallSession, val connection: SfuConnection) : ActiveCall
    /** Legacy 1:1: [call] drives the signalling; the platform's [LegacyPeer] carries the media. */
    data class Legacy(override val roomId: RoomId, override val video: Boolean, val call: LegacyCall) : ActiveCall
}

/** Why the facade ended the call by itself (see [Calls.ended]). */
enum class CallEndReason { NO_ANSWER, OTHER_SIDE_LEFT, DECLINED, LEGACY_ENDED }

/** What the room header may offer. */
data class CallAvailability(
    /** our homeserver has an SFU (or the room's call already uses one): MatrixRTC works */
    val rtc: Boolean,
    /** a legacy call can be placed: DMs with developer options on (it needs no SFU) */
    val legacy: Boolean,
) { val any get() = rtc || legacy }

/**
 * Platform-independent call signalling of one client: MatrixRTC sessions, legacy 1:1 calls, ringing, history, power.
 * The platform supplies the media: LiveKit for [ActiveCall.Rtc], a [LegacyPeerFactory] for [ActiveCall.Legacy].
 *
 * Call [start] once the client syncs. Starting a call ends the current one first; with [autoHangup] the facade also hangs up
 * by itself when a DM call is not answered (90 s), the other side leaves or declines, or a legacy call ends.
 */
class Calls(
    val client: MatrixClient,
    private val scope: CoroutineScope,
    sfuHttp: HttpClient = HttpClient(),
    peers: LegacyPeerFactory? = null,
    /** the `legacyCalls` pref: receive and answer legacy calls */
    private val legacyEnabled: () -> Boolean = { false },
    /** developer options: legacy calls may be placed in DMs */
    private val devOptions: () -> Boolean = { false },
    rtcConfig: RtcConfig = RtcConfig(),
    legacyConfig: LegacyConfig = LegacyConfig(),
    private val autoHangup: Boolean = true,
    keyTransports: (MatrixClient) -> List<KeyTransport> = { listOf(ToDeviceKeyTransport(it), RoomKeyTransport(it)) },
) {
    /** All live call-related events of the account, shared. */
    val events: SharedFlow<RoomCallEvent> = client.callEvents().shareIn(scope, SharingStarted.Eagerly, 0)
    val rtc = MatrixRtc(client, scope, events, SfuClient(sfuHttp), rtcConfig, keyTransports)
    val legacy: LegacyCalls? = peers?.let { LegacyCalls(client, scope, events, it, legacyEnabled, legacyConfig) }
    val power = CallPower(client)

    private val _active = MutableStateFlow<ActiveCall?>(null)
    /** The call we are in (one at a time). */
    val active: StateFlow<ActiveCall?> = _active.asStateFlow()

    val ringer = Ringer(client, scope, rtc, legacy, events, RingPolicy.forClient(client, { _active.value?.roomId }, legacyEnabled))
    /** The call ringing now, call-waiting included ([IncomingCall.waiting]). */
    val incoming: StateFlow<IncomingCall?> get() = ringer.incoming
    /** Quiet notifications and missed calls. */
    val notices: SharedFlow<CallNotice> get() = ringer.notices

    private val _ended = MutableSharedFlow<CallEndReason>(extraBufferCapacity = 8)
    /** Emitted when the call was ended by [autoHangup]. */
    val ended: SharedFlow<CallEndReason> = _ended.asSharedFlow()

    private val lock = kotlinx.coroutines.sync.Mutex()
    private var watchers = mutableListOf<Job>()

    fun start(): Job = scope.launch {
        ringer.start()
        legacy?.start()
        awaitCancellation()
    }

    /** SFU present? Evaluates what the room header's call buttons need. */
    suspend fun availability(roomId: RoomId): CallAvailability {
        val sfu = runCatching { rtc.discovery.ours() }.getOrNull() != null
        val dm = client.room.getById(roomId).first()?.isDirect == true
        return CallAvailability(rtc = sfu, legacy = dm && devOptions() && legacy != null)
    }

    /**
     * Starts (and rings) a call in [roomId]; ends the current call first. [legacy] null = MatrixRTC, or legacy in a DM when our server
     * has no SFU and developer options are on. Throws [CallPermissionException] when the room doesn't let us join calls
     * (for [CallAccess.ASK_ENABLE] ask the admin, [CallPower.allowCalls], and call again), else the failure with a Persian message.
     */
    suspend fun startCall(roomId: RoomId, video: Boolean, legacy: Boolean? = null): ActiveCall = lock.withLock {
        hangupNow()
        val dm = client.room.getById(roomId).first()?.isDirect == true
        val useLegacy = legacy ?: (devOptions() && dm && rtc.discovery.ours() == null)
        if (useLegacy) return@withLock startLegacy(roomId, video)
        val access = power.access(roomId)
        if (access != CallAccess.ALLOWED) throw CallPermissionException(access)
        ringer.answered()
        joinRtc(roomId, video, ring = true)
    }

    private suspend fun joinRtc(roomId: RoomId, video: Boolean, ring: Boolean): ActiveCall {
        val s = rtc.session(roomId)
        val conn = s.join(if (video) CallIntent.VIDEO else CallIntent.AUDIO, ring)
        val a = ActiveCall.Rtc(roomId, video, s, conn)
        _active.value = a
        watch(a)
        return a
    }

    private suspend fun startLegacy(roomId: RoomId, video: Boolean): ActiveCall {
        val l = legacy ?: throw IllegalStateException("این دستگاه از تماس پشتیبانی نمی‌کند")
        val c = l.place(roomId, video)
        val a = ActiveCall.Legacy(roomId, video, c)
        _active.value = a
        watch(a)
        return a
    }

    /** Answers the ringing call (ending the current one, as the web app does). [video] answers with video when the call has it. */
    suspend fun answer(video: Boolean = false): ActiveCall = lock.withLock {
        val i = incoming.value ?: throw IllegalStateException("no call is ringing")
        hangupNow()
        if (i.legacy != null) {
            ringer.answered()
            i.legacy.answer(video)
            ActiveCall.Legacy(i.roomId, video, i.legacy).also { _active.value = it; watch(it) }
        } else {
            val access = power.access(i.roomId)
            if (access != CallAccess.ALLOWED) throw CallPermissionException(access)
            val a = joinRtc(i.roomId, video, ring = false)
            ringer.answered()
            a
        }
    }

    /** Declines the ringing call. */
    suspend fun decline() = ringer.decline()

    /** Joins a call that is going on in the room without ringing anybody (the "join" bar). */
    suspend fun join(roomId: RoomId, video: Boolean): ActiveCall = lock.withLock {
        hangupNow()
        val access = power.access(roomId)
        if (access != CallAccess.ALLOWED) throw CallPermissionException(access)
        joinRtc(roomId, video, ring = false)
    }

    /** Ends the current call. */
    suspend fun hangup() = lock.withLock { hangupNow() }

    private suspend fun hangupNow() {
        val a = _active.value ?: return
        _active.value = null
        watchers.forEach { it.cancel() }; watchers = mutableListOf()
        when (a) {
            is ActiveCall.Rtc -> a.session.leave()
            is ActiveCall.Legacy -> a.call.hangup()
        }
    }

    private fun watch(a: ActiveCall) {
        if (!autoHangup) return
        fun end(r: CallEndReason) = scope.launch { hangup(); _ended.emit(r) }
        when (a) {
            is ActiveCall.Rtc -> watchers += scope.launch {
                a.session.signals.collect { s ->
                    when (s) {
                        CallSignal.NoAnswer -> end(CallEndReason.NO_ANSWER)
                        CallSignal.OtherSideLeft -> end(CallEndReason.OTHER_SIDE_LEFT)
                        is CallSignal.Declined -> end(CallEndReason.DECLINED)
                        else -> Unit
                    }
                }
            }
            is ActiveCall.Legacy -> watchers += scope.launch {
                a.call.state.first { it.phase == LegacyPhase.ENDED }
                if (_active.value === a) end(CallEndReason.LEGACY_ENDED)
            }
        }
    }
}
