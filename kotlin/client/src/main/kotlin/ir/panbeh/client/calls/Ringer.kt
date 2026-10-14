package ir.panbeh.client.calls

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.user
import de.connect2x.trixnity.client.user.getAccountData
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.events.m.IgnoredUserListEventContent
import de.connect2x.trixnity.core.model.events.m.PushRulesEventContent
import de.connect2x.trixnity.core.model.push.PushAction
import ir.panbeh.core.calls.isGroupCallAlert
import ir.panbeh.core.calls.isLegacyRing
import ir.panbeh.core.calls.isRing
import ir.panbeh.core.calls.isVideoOffer
import ir.panbeh.core.util.string
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Someone is calling. [until] is when it stops ringing (ms); [legacy] is set for an m.call.invite. */
data class IncomingCall(
    val roomId: RoomId,
    /** the m.rtc.notification or m.call.invite event */
    val eventId: String,
    val callerId: String,
    val video: Boolean,
    val until: Long,
    val legacy: LegacyCall?,
    /** we were in another call when it came: show call waiting (banner and beep) instead of the full ring screen */
    val waiting: Boolean,
)

/** Things that should show up as a notification instead of a ring. */
sealed interface CallNotice {
    val roomId: RoomId
    /** a group call started and it mentions us: a quiet notification */
    data class GroupCallStarted(override val roomId: RoomId, val senderId: String, val video: Boolean) : CallNotice
    /** a legacy invite arrived while legacy calls are off */
    data class LegacyUnsupported(override val roomId: RoomId, val senderId: String, val eventId: String) : CallNotice
    /** a ring stopped without any of our devices answering or declining */
    data class Missed(override val roomId: RoomId, val callerId: String, val video: Boolean) : CallNotice
}

fun missedCallText(video: Boolean) = if (video) "تماس تصویری از دست رفته" else "تماس صوتی از دست رفته"
fun groupCallText(senderName: String, video: Boolean) = "$senderName ${if (video) "تماس تصویری" else "تماس صوتی"} گروهی را شروع کرد"
fun legacyUnsupportedText(senderName: String) =
    "$senderName با روش قدیمی تماس گرفت که پشتیبانی نمی‌شود. برای دریافت این تماس‌ها، از تنظیمات › گزینه‌های توسعه‌دهنده «دریافت تماس‌های قدیمی» را روشن کنید."

/** What the decision to ring depends on besides the event. */
data class RingContext(
    /** another device of ours (or we) already are in that room's call */
    val inCall: Boolean = false,
    val muted: Boolean = false,
    val ignored: Boolean = false,
    /** something else is ringing already */
    val alreadyRinging: Boolean = false,
    /** the room of the call we are in now; a ring from another room rings as call waiting */
    val activeRoom: RoomId? = null,
    val legacyEnabled: Boolean = false,
)

sealed interface RingDecision {
    data object None : RingDecision
    data class Ring(val until: Long, val video: Boolean, val waiting: Boolean) : RingDecision
    data class GroupAlert(val video: Boolean) : RingDecision
    data object LegacyUnsupported : RingDecision
}

/**
 * Whether a live call event should ring (as src/call.ts `ring` and `ringLegacy` decide): not from me, not muted or ignored, not
 * already in that call, still fresh, nothing else ringing, not the room of the call we're in. [ev] is an m.rtc.notification
 * (either spelling) or a legacy m.call.invite; anything else is [RingDecision.None].
 */
fun decideRing(ev: RoomCallEvent, me: String, ctx: RingContext, now: Long = System.currentTimeMillis()): RingDecision {
    when (ev.type) {
        CallTypes.RTC_NOTIFICATION, CallTypes.RTC_NOTIFICATION_STABLE -> {
            if (ev.sender == me || ctx.inCall || ctx.muted || ctx.ignored) return RingDecision.None
            val until = notificationExpiry(ev.content, ev.ts)
            val video = ev.content.string("m.call.intent") == "video"
            if (isGroupCallAlert(ev.content, until, me, now)) return RingDecision.GroupAlert(video)
            if (!isRing(ev.content, until, me, now) || ctx.alreadyRinging || ctx.activeRoom == ev.roomId) return RingDecision.None
            return RingDecision.Ring(until.toLong(), video, ctx.activeRoom != null)
        }
        "m.call.invite" -> {
            if (!isLegacyRing(ev.content, ev.ts, me, now) || ev.sender == me) return RingDecision.None
            if (ctx.alreadyRinging || ctx.activeRoom == ev.roomId || ctx.muted || ctx.ignored) return RingDecision.None
            if (!ctx.legacyEnabled) return RingDecision.LegacyUnsupported
            val lifetime = (ev.content["lifetime"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
            return RingDecision.Ring(ev.ts + lifetime.toLong(), isVideoOffer(ev.content), ctx.activeRoom != null)
        }
    }
    return RingDecision.None
}

/** Where the ringer asks about mute and ignore; the default reads the account's push rules and ignored-user list. */
class RingPolicy(
    val isMuted: suspend (RoomId) -> Boolean,
    val isIgnored: suspend (String) -> Boolean,
    /** the room of the call we are in (any kind), if any */
    val activeRoom: () -> RoomId?,
    val legacyEnabled: () -> Boolean,
) {
    companion object {
        /** Muted = a room or override push rule for the room that doesn't notify (ours or Element's). */
        fun forClient(client: MatrixClient, activeRoom: () -> RoomId?, legacyEnabled: () -> Boolean) = RingPolicy(
            isMuted = { room ->
                val g = client.user.getAccountData<PushRulesEventContent>().first()?.global
                fun mutes(r: de.connect2x.trixnity.core.model.push.PushRule?) = r != null && r.enabled && PushAction.Notify !in r.actions
                mutes(g?.room?.firstOrNull { it.roomId == room }) || mutes(g?.override?.firstOrNull { it.ruleId == room.full })
            },
            isIgnored = { u -> client.user.getAccountData<IgnoredUserListEventContent>().first()?.ignoredUsers?.keys?.any { it.full == u } == true },
            activeRoom = activeRoom, legacyEnabled = legacyEnabled,
        )
    }
}

/**
 * Turns call events into one [incoming] ring, call-waiting, quiet [notices] and missed-call notices. It does not play sounds
 * or show anything; and it ends a ring by itself when the caller gives up, one of our devices answers or declines, or time runs out.
 */
class Ringer(
    private val client: MatrixClient,
    private val scope: CoroutineScope,
    private val rtc: MatrixRtc,
    private val legacy: LegacyCalls?,
    private val events: Flow<RoomCallEvent>,
    private val policy: RingPolicy,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val me = client.userId.full
    private val _incoming = MutableStateFlow<IncomingCall?>(null)
    /** The call ringing now (one at a time). */
    val incoming: StateFlow<IncomingCall?> = _incoming.asStateFlow()
    private val _notices = MutableSharedFlow<CallNotice>(extraBufferCapacity = 16)
    val notices: SharedFlow<CallNotice> = _notices.asSharedFlow()
    private var guards = mutableListOf<Job>()

    fun start(): Job = scope.launch {
        events.collect { runCatching { onEvent(it) } }
    }

    private suspend fun onEvent(ev: RoomCallEvent) {
        when (ev.type) {
            CallTypes.RTC_NOTIFICATION, CallTypes.RTC_NOTIFICATION_STABLE, "m.call.invite" -> ring(ev)
            CallTypes.RTC_DECLINE, CallTypes.RTC_DECLINE_STABLE ->
                // declined on another of our devices
                if (ev.sender == me && _incoming.value?.eventId == ev.relatesTo) stop(missed = false)
        }
    }

    /** Decides for [ev] (also used to ring from a tapped push notification). */
    suspend fun ring(ev: RoomCallEvent) {
        val sess = rtc.session(ev.roomId)
        val ctx = RingContext(
            inCall = ev.type != "m.call.invite" && sess.memberships.value.any { it.userId == me },
            muted = policy.isMuted(ev.roomId), ignored = policy.isIgnored(ev.sender),
            alreadyRinging = _incoming.value != null, activeRoom = policy.activeRoom(), legacyEnabled = policy.legacyEnabled(),
        )
        when (val d = decideRing(ev, me, ctx, now())) {
            RingDecision.None -> Unit
            is RingDecision.GroupAlert -> _notices.emit(CallNotice.GroupCallStarted(ev.roomId, ev.sender, d.video))
            RingDecision.LegacyUnsupported -> _notices.emit(CallNotice.LegacyUnsupported(ev.roomId, ev.sender, ev.eventId ?: ""))
            is RingDecision.Ring -> startRinging(ev, d, sess)
        }
    }

    private suspend fun startRinging(ev: RoomCallEvent, d: RingDecision.Ring, sess: CallSession) {
        val isLegacy = ev.type == "m.call.invite"
        val call = if (isLegacy) legacy?.fromInvite(ev) ?: legacy?.call((ev.typed as? de.connect2x.trixnity.core.model.events.m.call.CallEventContent)?.callId ?: "") else null
        if (isLegacy && legacy != null && call == null) return
        val i = IncomingCall(ev.roomId, ev.eventId ?: "", ev.sender, d.video, d.until, call, d.waiting)
        if (!_incoming.compareAndSet(null, i)) return
        guards += scope.launch { delay(maxOf(0, d.until - now())); stop(missed = true, only = i) }
        if (call != null) guards += scope.launch {
            call.state.first { it.phase != LegacyPhase.RINGING_IN }.let { st ->
                val silent = st.end == null || st.end in setOf(LegacyEnd.ANSWERED_ELSEWHERE, LegacyEnd.REJECTED_ELSEWHERE, LegacyEnd.REJECTED_BY_ME, LegacyEnd.REPLACED)
                stop(missed = !silent, only = i)
            }
        } else if (!isLegacy) guards += scope.launch {
            // answered on one of our devices, or the caller left: memberships change after the ring started
            sess.memberships.drop(1).collect { l ->
                if (l.any { it.userId == me }) stop(missed = false, only = i)
                else if (l.isEmpty()) stop(missed = true, only = i)
            }
        }
    }

    /** Ends the ring. missed = it ended without any of our devices answering or declining. */
    private suspend fun stop(missed: Boolean, only: IncomingCall? = null) {
        val i = _incoming.value ?: return
        if (only != null && only !== i) return
        if (!_incoming.compareAndSet(i, null)) return
        val g = guards; guards = mutableListOf()
        g.forEach { if (it != currentCoroutineContext()[Job]) it.cancel() }
        if (missed) _notices.emit(CallNotice.Missed(i.roomId, i.callerId, i.video))
    }

    /** Stops the ring because we answered it. */
    suspend fun answered() = stop(missed = false)

    /** Declines the ringing call, whichever kind it is (m.call.reject / hangup for legacy, MSC4310 for MatrixRTC). */
    suspend fun decline() {
        val i = _incoming.value ?: return
        stop(missed = false)
        if (i.legacy != null) runCatching { i.legacy.reject() }
        else rtc.session(i.roomId).decline(i.eventId)
    }
}
