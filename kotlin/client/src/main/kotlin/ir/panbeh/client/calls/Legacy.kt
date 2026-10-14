package ir.panbeh.client.calls

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.room
import de.connect2x.trixnity.core.model.EventId
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.events.m.call.CallEventContent
import de.connect2x.trixnity.core.model.events.m.call.Purpose
import de.connect2x.trixnity.core.model.events.m.call.StreamMetadata
import ir.panbeh.core.calls.isLegacyRing
import ir.panbeh.core.calls.isVideoOffer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

// Legacy 1:1 calls (m.call.invite/answer/candidates/hangup/reject/select_answer/negotiate, version "1"): the signalling is
// here, the media (a WebRTC peer connection) is the platform's: it implements [LegacyPeer].

data class IceCandidate(val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int?)

enum class PeerConnectionState { NEW, CONNECTING, CONNECTED, DISCONNECTED, FAILED, CLOSED }

/** What a peer reports back to its call. Called from any thread. */
interface LegacyPeerEvents {
    /** a local ICE candidate was found; null = gathering is done */
    fun onLocalCandidate(c: IceCandidate?)
    fun onConnectionState(state: PeerConnectionState)
    /** tracks were added or removed after the first negotiation (e.g. voice upgraded to video) */
    fun onNegotiationNeeded()
}

/** The WebRTC side of a legacy call, implemented on Android with a PeerConnection. */
interface LegacyPeer {
    /** id of the local media stream, named in `sdp_stream_metadata` (Element requires it); null = not announced */
    val localStreamId: String?
    /** gets the mic (and camera), adds the tracks and returns the local offer SDP; throws when media is refused */
    suspend fun createOffer(video: Boolean): String
    /** a remote offer (incoming invite or renegotiation) */
    suspend fun setRemoteOffer(sdp: String)
    /** gets the mic (and camera) when needed and answers the last remote offer; returns the answer SDP */
    suspend fun createAnswer(video: Boolean): String
    suspend fun setRemoteAnswer(sdp: String)
    /** a new local offer for a renegotiation */
    suspend fun createRenegotiationOffer(): String
    suspend fun addIceCandidate(c: IceCandidate)
    /** restarts ICE after the network changed */
    suspend fun restartIce()
    /** "TURN: yes | local: host, srflx | remote: relay | ICE: checking" for the failure message */
    suspend fun iceReport(): String
    fun close()
}

/** Makes the peer connection of a call with these ICE servers; [events] must be called by it. */
fun interface LegacyPeerFactory {
    suspend fun create(iceServers: List<IceServer>, events: LegacyPeerEvents): LegacyPeer
}

enum class LegacyPhase {
    /** outgoing: getting TURN, media and the offer ready ("در حال اتصال…") */
    PREPARING,
    /** outgoing: invite sent, the other side rings ("در حال زنگ زدن…") */
    CALLING,
    /** incoming: ringing here */
    RINGING_IN,
    /** answered, ICE connecting */
    CONNECTING,
    CONNECTED,
    ENDED,
}

enum class LegacyEnd {
    HANGUP_LOCAL, HANGUP_REMOTE, REJECTED_BY_ME, REJECTED_BY_REMOTE,
    /** the invite's lifetime ran out */
    NO_ANSWER,
    ANSWERED_ELSEWHERE, REJECTED_ELSEWHERE,
    ICE_FAILED, MEDIA_FAILED,
    /** glare: this call was dropped for the other one */
    REPLACED,
    /** didn't connect in time, see [LegacyCall.stuck] */
    STUCK,
    ERROR,
}

data class LegacyCallState(
    val phase: LegacyPhase,
    val end: LegacyEnd? = null,
    /** when it connected (ms), for the duration */
    val connectedAt: Long? = null,
    /** the network dropped after it connected; ICE is being restarted */
    val reconnecting: Boolean = false,
    val video: Boolean = false,
    val error: Throwable? = null,
)

/** The 30-second "stuck" report: [mediaFailed] = still waiting for mic/camera, else ICE found no way through ([report]). */
data class StuckReport(val mediaFailed: Boolean, val report: String) {
    /** the message the web app shows */
    val text: String get() = if (mediaFailed) "میکروفون یا دوربین در دسترس نیست. اجازه‌ی دسترسی پنبه به آن‌ها را بررسی کنید."
    else "اتصال تماس برقرار نشد. احتمالاً سرور ماتریکس شما سرور TURN ندارد یا به آن دسترسی نیست؛ با مدیر سرور در میان بگذارید.\n\n$report"
}

data class LegacyConfig(
    /** how long our invites ring */
    val lifetimeMs: Long = 60_000,
    val stuckMs: Long = 30_000,
    /** local candidates are sent in batches this long after the first one */
    val candidateDelayMs: Long = 200,
    /** ICE stays failed this long after it connected before we hang up */
    val iceFailedGraceMs: Long = 15_000,
    val turnTimeoutMs: Long = 10_000,
    val now: () -> Long = System::currentTimeMillis,
)

/** One legacy call. Obtained from [LegacyCalls]. */
class LegacyCall internal constructor(
    private val owner: LegacyCalls,
    val roomId: RoomId,
    val callId: String,
    /** the other user (the invite's sender, or whom we called) */
    val peerUserId: String,
    val incoming: Boolean,
    /** the invite's event id (incoming) */
    val inviteEventId: String?,
    private val remoteOffer: String?,
    private val inviteVersion: String,
    video: Boolean,
    private val inviteLifetimeMs: Long,
    private val inviteTs: Long,
) {
    private val client get() = owner.client
    private val cfg get() = owner.config
    private val scope get() = owner.scope
    private val me = owner.client.userId.full
    private val party = owner.client.deviceId
    private val lock = Mutex()

    private val _state = MutableStateFlow(LegacyCallState(if (incoming) LegacyPhase.RINGING_IN else LegacyPhase.PREPARING, video = video))
    /** UI-ready state. */
    val state: StateFlow<LegacyCallState> = _state.asStateFlow()

    private val _stuck = MutableSharedFlow<StuckReport>(extraBufferCapacity = 4)
    /** Emitted once when the call sits in media/ICE setup for too long; the call is hung up right after. */
    val stuck: SharedFlow<StuckReport> = _stuck.asSharedFlow()

    private var peer: LegacyPeer? = null
    private var remoteParty: String? = null
    private var selectedParty: String? = null
    private var remoteDescriptionSet = false
    private val pendingRemote = ArrayList<IceCandidate>()
    private val pendingLocal = ArrayList<IceCandidate>()
    private var candidateJob: Job? = null
    private var inviteTimer: Job? = null
    private var stuckTimer: Job? = null
    private var iceTimer: Job? = null
    private var answeredByMe = false

    val ended get() = _state.value.phase == LegacyPhase.ENDED
    val video get() = _state.value.video

    private fun set(f: (LegacyCallState) -> LegacyCallState) {
        val old = _state.value.phase
        _state.update(f)
        val p = _state.value.phase
        if (p != old) watch(p)
    }

    /** 30 s in PREPARING or CONNECTING without ever connecting = stuck. */
    private fun watch(p: LegacyPhase) {
        stuckTimer?.cancel()
        if (p != LegacyPhase.PREPARING && p != LegacyPhase.CONNECTING) return
        stuckTimer = scope.launch {
            delay(cfg.stuckMs)
            val st = _state.value
            if (st.phase != p || st.connectedAt != null) return@launch
            val report = runCatching { peer?.iceReport() }.getOrNull() ?: "-"
            _stuck.emit(StuckReport(mediaFailed = p == LegacyPhase.PREPARING, report = report))
            finish(LegacyEnd.STUCK, send = true, reason = CallEventContent.Hangup.Reason.ICE_TIMEOUT)
        }
    }

    // ----- sending -----

    private fun partyMeta(): Map<String, StreamMetadata>? =
        peer?.localStreamId?.let { mapOf(it to StreamMetadata(Purpose.USERMEDIA, audioMuted = false, videoMuted = !_state.value.video)) }

    private suspend fun send(c: CallEventContent) { client.sendRoomEvent(roomId, c) }

    private val peerEvents = object : LegacyPeerEvents {
        override fun onLocalCandidate(c: IceCandidate?) {
            if (c != null) synchronized(pendingLocal) { pendingLocal += c }
            if (candidateJob?.isActive == true && c != null) return
            candidateJob = scope.launch { if (c != null) delay(cfg.candidateDelayMs); flushCandidates() }
        }
        override fun onConnectionState(state: PeerConnectionState) { scope.launch { onPeerState(state) } }
        override fun onNegotiationNeeded() { scope.launch { renegotiate() } }
    }

    /** Local candidates go out only once the invite/answer they belong to is out. */
    private var candidatesOpen = false
    private suspend fun flushCandidates() {
        if (!candidatesOpen || ended) return
        val batch = synchronized(pendingLocal) { pendingLocal.toList().also { pendingLocal.clear() } }
        if (batch.isEmpty()) return
        send(CallEventContent.Candidates("1", callId, party, batch.map { CallEventContent.Candidates.Candidate(it.candidate, it.sdpMLineIndex?.toLong(), it.sdpMid) }))
    }

    // ----- placing and answering -----

    internal suspend fun place(video: Boolean, invitee: String?) = lock.withLock {
        try {
            val ice = owner.turn()
            peer = owner.factory.create(ice, peerEvents)
            val sdp = peer!!.createOffer(video)
            send(CallEventContent.Invite("1", callId, party, invitee, cfg.lifetimeMs, CallEventContent.Invite.Offer(sdp, CallEventContent.Invite.OfferType.OFFER), partyMeta()))
            candidatesOpen = true
            set { it.copy(phase = LegacyPhase.CALLING) }
            inviteTimer = scope.launch { delay(cfg.lifetimeMs); if (_state.value.phase == LegacyPhase.CALLING) finish(LegacyEnd.NO_ANSWER, send = true, reason = CallEventContent.Hangup.Reason.INVITE_TIMEOUT) }
            flushCandidates()
        } catch (e: Throwable) {
            finish(if (e is CancellationException) LegacyEnd.HANGUP_LOCAL else LegacyEnd.MEDIA_FAILED, send = false, error = e)
            throw e
        }
    }

    /** Answers a ringing incoming call (with video if [video] and the call has one). Throws when media or TURN fail; the call is then ended. */
    suspend fun answer(video: Boolean = this.video) = lock.withLock {
        check(incoming && _state.value.phase == LegacyPhase.RINGING_IN) { "not ringing" }
        answeredByMe = true
        owner.adopt(this)
        inviteTimer?.cancel()
        set { it.copy(phase = LegacyPhase.CONNECTING, video = video) }
        try {
            val ice = owner.turn()
            peer = owner.factory.create(ice, peerEvents)
            peer!!.setRemoteOffer(remoteOffer ?: error("no offer"))
            remoteDescriptionSet = true
            val sdp = peer!!.createAnswer(video)
            send(CallEventContent.Answer("1", callId, party, CallEventContent.Answer.Answer(sdp, CallEventContent.Answer.AnswerType.ANSWER), partyMeta()))
            candidatesOpen = true
            drainRemote()
            flushCandidates()
        } catch (e: Throwable) {
            finish(LegacyEnd.MEDIA_FAILED, send = true, reason = CallEventContent.Hangup.Reason.USER_MEDIA_FAILED, error = e)
            throw e
        }
    }

    /** Declines an incoming ring: m.call.reject for a version 1 invite, else a hangup. Hangs up when it's not ringing any more. */
    suspend fun reject() {
        if (incoming && _state.value.phase == LegacyPhase.RINGING_IN) finish(LegacyEnd.REJECTED_BY_ME, send = true)
        else hangup()
    }

    /** Ends the call from here. */
    suspend fun hangup() { finish(LegacyEnd.HANGUP_LOCAL, send = true, reason = CallEventContent.Hangup.Reason.USER_HANGUP) }

    private suspend fun finish(end: LegacyEnd, send: Boolean, reason: CallEventContent.Hangup.Reason? = null, error: Throwable? = null) {
        if (ended) return
        set { it.copy(phase = LegacyPhase.ENDED, end = end, reconnecting = false, error = error) }
        inviteTimer?.cancel(); stuckTimer?.cancel(); iceTimer?.cancel(); candidateJob?.cancel()
        runCatching { peer?.close() }
        peer = null
        if (send) runCatching {
            withContext(NonCancellable) {
                if (end == LegacyEnd.REJECTED_BY_ME && inviteVersion == "1") send(CallEventContent.Reject("1", callId, party))
                else send(CallEventContent.Hangup(if (end == LegacyEnd.REJECTED_BY_ME) "0" else "1", callId, party, reason))
            }
        }
        owner.ended(this)
    }

    // ----- what arrives -----

    private suspend fun drainRemote() {
        val p = peer ?: return
        val list = synchronized(pendingRemote) { pendingRemote.toList().also { pendingRemote.clear() } }
        for (c in list) runCatching { p.addIceCandidate(c) }
    }

    internal suspend fun onEvent(ev: RoomCallEvent) = lock.withLock {
        if (ended) return@withLock
        val c = ev.typed as? CallEventContent ?: return@withLock
        val mine = ev.sender == me
        if (mine && c.partyId == party) return@withLock // our own echo
        val st = _state.value.phase
        when (c) {
            is CallEventContent.Answer -> when {
                mine -> if (st == LegacyPhase.RINGING_IN) finish(LegacyEnd.ANSWERED_ELSEWHERE, send = false)
                !incoming && (st == LegacyPhase.CALLING || st == LegacyPhase.PREPARING) && selectedParty == null -> {
                    inviteTimer?.cancel()
                    selectedParty = c.partyId; remoteParty = c.partyId
                    if (c.version == "1") runCatching { send(CallEventContent.SelectAnswer("1", callId, party, c.partyId ?: "")) }
                    runCatching { peer?.setRemoteAnswer(c.answer.sdp); remoteDescriptionSet = true; set { it.copy(phase = LegacyPhase.CONNECTING) }; drainRemote() }
                        .onFailure { finish(LegacyEnd.ERROR, send = true, reason = CallEventContent.Hangup.Reason.UNKNOWN_ERROR, error = it) }
                }
            }
            is CallEventContent.SelectAnswer ->
                if (incoming && answeredByMe && c.selectedPartyId != party) finish(LegacyEnd.ANSWERED_ELSEWHERE, send = false)
            is CallEventContent.Candidates -> if (!mine && matchesRemote(c.partyId)) {
                val l = c.candidates.filter { it.candidate.isNotEmpty() }.map { IceCandidate(it.candidate, it.sdpMid, it.sdpMLineIndex?.toInt()) }
                if (remoteDescriptionSet && peer != null) l.forEach { runCatching { peer!!.addIceCandidate(it) } } else synchronized(pendingRemote) { pendingRemote += l }
            }
            is CallEventContent.Hangup ->
                if (mine) { if (st == LegacyPhase.RINGING_IN) finish(LegacyEnd.REJECTED_ELSEWHERE, send = false) }
                else if (matchesRemote(c.partyId)) finish(LegacyEnd.HANGUP_REMOTE, send = false)
            is CallEventContent.Reject ->
                if (mine) { if (st == LegacyPhase.RINGING_IN) finish(LegacyEnd.REJECTED_ELSEWHERE, send = false) }
                else if (!incoming && selectedParty == null) finish(LegacyEnd.REJECTED_BY_REMOTE, send = false)
            is CallEventContent.Negotiate -> if (!mine && matchesRemote(c.partyId)) negotiate(c)
            else -> Unit
        }
    }

    /** Events from a callee other than the one we settled on (the invite's sender, or the selected answerer) are ignored. */
    private fun matchesRemote(p: String?): Boolean {
        val want = selectedParty ?: remoteParty
        return want == null || p == null || p == want
    }

    internal fun learnRemoteParty(p: String?) { if (incoming && remoteParty == null) remoteParty = p }

    /** The invite's own candidates that arrived before we listened (cold start). */
    internal fun bufferRemote(c: CallEventContent.Candidates) {
        synchronized(pendingRemote) { pendingRemote += c.candidates.filter { it.candidate.isNotEmpty() }.map { IceCandidate(it.candidate, it.sdpMid, it.sdpMLineIndex?.toInt()) } }
    }

    private suspend fun negotiate(c: CallEventContent.Negotiate) {
        val p = peer ?: return
        runCatching {
            when (c.description.type) {
                CallEventContent.Negotiate.DescriptionType.OFFER -> {
                    p.setRemoteOffer(c.description.sdp)
                    val sdp = p.createAnswer(_state.value.video)
                    send(CallEventContent.Negotiate("1", callId, party, CallEventContent.Negotiate.Description(sdp, CallEventContent.Negotiate.DescriptionType.ANSWER), 30_000, partyMeta()))
                }
                CallEventContent.Negotiate.DescriptionType.ANSWER -> p.setRemoteAnswer(c.description.sdp)
            }
        }
    }

    private suspend fun renegotiate() {
        val p = peer ?: return
        if (_state.value.phase != LegacyPhase.CONNECTED) return
        runCatching {
            val sdp = p.createRenegotiationOffer()
            send(CallEventContent.Negotiate("1", callId, party, CallEventContent.Negotiate.Description(sdp, CallEventContent.Negotiate.DescriptionType.OFFER), 30_000, partyMeta()))
        }
    }

    private suspend fun onPeerState(s: PeerConnectionState) {
        if (ended) return
        when (s) {
            PeerConnectionState.CONNECTED -> {
                iceTimer?.cancel()
                set { it.copy(phase = LegacyPhase.CONNECTED, connectedAt = it.connectedAt ?: cfg.now(), reconnecting = false) }
            }
            PeerConnectionState.DISCONNECTED, PeerConnectionState.FAILED -> {
                if (_state.value.connectedAt == null) {
                    if (s == PeerConnectionState.FAILED) finish(LegacyEnd.ICE_FAILED, send = true, reason = CallEventContent.Hangup.Reason.ICE_FAILED)
                } else {
                    set { it.copy(reconnecting = true) }
                    if (s == PeerConnectionState.FAILED) runCatching { peer?.restartIce() }
                    iceTimer?.cancel()
                    iceTimer = scope.launch { delay(cfg.iceFailedGraceMs); if (_state.value.reconnecting) finish(LegacyEnd.ICE_FAILED, send = true, reason = CallEventContent.Hangup.Reason.ICE_FAILED) }
                }
            }
            else -> Unit
        }
    }

    internal fun startInviteTimer() {
        val left = inviteTs + inviteLifetimeMs - cfg.now()
        inviteTimer = scope.launch { delay(maxOf(0, left)); if (_state.value.phase == LegacyPhase.RINGING_IN) finish(LegacyEnd.NO_ANSWER, send = false) }
    }

    internal suspend fun dropForGlare() = finish(LegacyEnd.REPLACED, send = false)
}

/** Caches the homeserver's TURN credentials (waits for them like the web app's checkTurnServers; empty when there are none). */
class TurnProvider(
    private val fetch: suspend () -> List<IceServer>,
    private val timeoutMs: Long = 10_000,
    private val now: () -> Long = System::currentTimeMillis,
    private val cacheMs: Long = 10 * 60_000,
) {
    constructor(client: MatrixClient, timeoutMs: Long = 10_000, now: () -> Long = System::currentTimeMillis) : this({ client.api.turnServers() }, timeoutMs, now)

    private var cached: List<IceServer>? = null
    private var fetchedAt = 0L

    suspend fun get(): List<IceServer> {
        cached?.let { if (now() - fetchedAt < cacheMs) return it }
        val l = withTimeoutOrNull(timeoutMs) { runCatching { fetch() }.getOrNull() } ?: return cached.orEmpty()
        cached = l; fetchedAt = now()
        return l
    }
}

/**
 * Legacy call signalling for one client. Feed it [events]; it creates a [LegacyCall] for every ring-worthy incoming invite
 * (when [enabled]), and places outgoing ones. One call at a time is "current".
 */
class LegacyCalls(
    internal val client: MatrixClient,
    internal val scope: CoroutineScope,
    private val events: Flow<RoomCallEvent>,
    internal val factory: LegacyPeerFactory,
    /** the `legacyCalls` pref (developer options on and the switch on) */
    private val enabled: () -> Boolean,
    internal val config: LegacyConfig = LegacyConfig(),
    private val turnProvider: TurnProvider = TurnProvider(client, config.turnTimeoutMs, config.now),
) {
    private val me = client.userId.full
    private val calls = java.util.concurrent.ConcurrentHashMap<String, LegacyCall>()
    internal suspend fun turn() = turnProvider.get()

    private val _incoming = MutableSharedFlow<LegacyCall>(extraBufferCapacity = 16)
    /** A new incoming call that rings (not for glare-resolved or disabled ones). */
    val incoming: SharedFlow<LegacyCall> = _incoming.asSharedFlow()

    private val _current = MutableStateFlow<LegacyCall?>(null)
    /** The call placed or answered here. */
    val current: StateFlow<LegacyCall?> = _current.asStateFlow()

    private val _replaced = MutableSharedFlow<Pair<LegacyCall, LegacyCall>>(extraBufferCapacity = 4)
    /** (old, new): glare, our outgoing call was dropped and the other one is being answered. */
    val replaced: SharedFlow<Pair<LegacyCall, LegacyCall>> = _replaced.asSharedFlow()

    fun call(callId: String): LegacyCall? = calls[callId]

    internal fun ended(c: LegacyCall) { calls.remove(c.callId, c); _current.compareAndSet(c, null) }

    fun start(): Job = scope.launch { events.collect { runCatching { onEvent(it) } } }

    /** Calls the other person in a DM. Throws with the reason (no mic, no TURN…) when it can't start; the call is then ended. */
    suspend fun place(roomId: RoomId, video: Boolean): LegacyCall = placing.withLock {
        check(_current.value == null) { "already in a call" }
        val peerUser = client.api.room.getJoinedMembers(roomId).getOrThrow().joined.keys.firstOrNull { it.full != me }?.full
        val c = LegacyCall(this, roomId, newCallId(), peerUser ?: "", false, null, null, "1", video, config.lifetimeMs, config.now())
        calls[c.callId] = c; _current.value = c
        c.place(video, peerUser)
        c
    }

    /** held while a call is being placed, so an invite that crosses ours is seen as glare and not as a second call */
    private val placing = Mutex()

    private fun newCallId() = java.util.UUID.randomUUID().toString().replace("-", "").take(16)

    private fun invite(ev: RoomCallEvent) = ev.typed as? CallEventContent.Invite

    /** Makes the call object for a ring-worthy invite; null when it isn't one (expired, mine, aimed at someone else, disabled). */
    fun fromInvite(ev: RoomCallEvent): LegacyCall? {
        val inv = invite(ev) ?: return null
        calls[inv.callId]?.let { return it }
        if (ev.sender == me || !isLegacyRing(ev.content, ev.ts, me, config.now()) || !enabled()) return null
        val c = LegacyCall(this, ev.roomId, inv.callId, ev.sender, true, ev.eventId, inv.offer.sdp, inv.version, isVideoOffer(ev.content), inv.lifetime, ev.ts)
        c.learnRemoteParty(inv.partyId)
        calls[inv.callId] = c
        c.startInviteTimer()
        return c
    }

    /**
     * Cold start (the app was woken by a push): builds the call for an invite that was synced before we listened, and replays
     * the candidates after it. Null when it is over already (hung up, rejected or answered) or not worth ringing.
     */
    suspend fun restoreIncoming(roomId: RoomId, inviteEventId: String): LegacyCall? {
        calls.values.firstOrNull { it.inviteEventId == inviteEventId }?.let { return it }
        val te = client.room.getTimelineEvent(roomId, EventId(inviteEventId)).filterNotNull().first()
        val ev = te.toCallEvent() ?: return null
        val inv = invite(ev) ?: return null
        val after = client.recentCallEvents(roomId).filter { it.ts >= ev.ts && (it.typed as? CallEventContent)?.callId == inv.callId }
        val over = after.any { x ->
            x.typed is CallEventContent.Hangup || x.typed is CallEventContent.Reject || (x.typed is CallEventContent.Answer && x.sender == me)
        }
        if (over) return null
        val c = fromInvite(ev) ?: return null
        after.mapNotNull { it.typed as? CallEventContent.Candidates }.filter { (it.partyId == inv.partyId) }.forEach(c::bufferRemote)
        return c
    }

    /** Declines an invite we have no [LegacyCall] for (the app isn't running calls): m.call.reject for v1, else a hangup. */
    suspend fun rejectInvite(roomId: RoomId, ev: RoomCallEvent) {
        val inv = invite(ev) ?: return
        runCatching {
            client.sendRoomEvent(roomId,
                if (inv.version == "1") CallEventContent.Reject("1", inv.callId, client.deviceId) else CallEventContent.Hangup("0", inv.callId, client.deviceId))
        }
    }

    private suspend fun onEvent(ev: RoomCallEvent) {
        val c = ev.typed as? CallEventContent ?: return
        if (c is CallEventContent.Invite) return onInvite(ev, c)
        calls[c.callId]?.onEvent(ev)
    }

    private suspend fun onInvite(ev: RoomCallEvent, inv: CallEventContent.Invite) {
        placing.withLock { }
        if (ev.sender == me && inv.partyId == client.deviceId) return
        val mine = _current.value
        // glare: both sides called at once in this room. The call with the larger id gives way (and answers the other).
        if (mine != null && mine.roomId == ev.roomId && !mine.incoming && mine.state.value.phase.let { it == LegacyPhase.PREPARING || it == LegacyPhase.CALLING }) {
            if (ev.sender == me) return
            if (mine.callId > inv.callId) {
                val n = fromInvite(ev) ?: return
                mine.dropForGlare()
                _current.value = n
                _replaced.emit(mine to n)
                runCatching { n.answer(mine.video) }
            }
            return
        }
        val c = fromInvite(ev) ?: return
        _incoming.emit(c)
    }

    /** Marks an answered incoming call as the current one (call after [LegacyCall.answer]). */
    fun adopt(c: LegacyCall) { _current.value = c }
}
