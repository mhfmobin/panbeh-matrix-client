package ir.panbeh.client.calls

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.room
import de.connect2x.trixnity.client.room.getAllState
import de.connect2x.trixnity.client.room.getState
import de.connect2x.trixnity.client.store.RoomOutboxMessage
import de.connect2x.trixnity.core.model.EventId
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.events.ClientEvent
import de.connect2x.trixnity.core.model.events.MessageEventContent
import de.connect2x.trixnity.core.model.events.m.RelationType
import de.connect2x.trixnity.core.model.events.m.room.EncryptionEventContent
import ir.panbeh.core.util.string
import ir.panbeh.client.events.CallMember
import ir.panbeh.client.events.Reaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration.Companion.seconds

/** Timings and sizes of the MatrixRTC session, as matrix-js-sdk's. */
data class RtcConfig(
    val membershipExpiresMs: Long = 4 * 3600_000L,
    /** the delayed leave event (MSC4140) fires this long after the last restart */
    val delayedLeaveMs: Long = 8_000,
    val delayRestartMs: Long = 5_000,
    /** a DM ring nobody answers is given up on after this long */
    val noAnswerMs: Long = 90_000,
    val ringLifetimeMs: Long = RING_LIFETIME_MS,
    /** reactions stay on screen this long */
    val reactionShowMs: Long = 3_000,
    val now: () -> Long = System::currentTimeMillis,
)

/** What the media side needs to connect to the SFU for a call we joined. */
class SfuConnection(
    /** LiveKit websocket url */
    val url: String,
    /** access token for it */
    val jwt: String,
    /** our LiveKit identity "@user:server:DEVICE" */
    val identity: String,
    /** the SFU's lk-jwt-service (it gave [url] and [jwt]) */
    val focus: LivekitFocus,
    /** frame-encryption keys; null in unencrypted rooms */
    val keys: CallKeyManager?,
)

/** Things the UI or the Android side should react to during a call we are in. */
sealed interface CallSignal {
    /** a DM we rang wasn't picked up in time: hang up */
    data object NoAnswer : CallSignal
    /** the other side of a DM call left after it connected: hang up */
    data object OtherSideLeft : CallSignal
    /** the other side declined our ring: hang up */
    data class Declined(val userId: String) : CallSignal
    data class MemberJoined(val userId: String, val deviceId: String) : CallSignal
    data class MemberLeft(val userId: String, val deviceId: String) : CallSignal
}

/** A reaction someone sent during the call. */
data class CallReactionEvent(val membershipEventId: String, val userId: String?, val emoji: String, val name: String)

/** What a client needs of the room to use MatrixRTC in it; separate from the session so tests can see the pieces. */
class MatrixRtc(
    val client: MatrixClient,
    val scope: CoroutineScope,
    val events: Flow<RoomCallEvent>,
    val sfu: SfuClient,
    val config: RtcConfig = RtcConfig(),
    val transports: (MatrixClient) -> List<KeyTransport> = { listOf(ToDeviceKeyTransport(it), RoomKeyTransport(it)) },
) {
    val discovery = SfuDiscovery(client.api, sfu, client.userId.domain)
    private val sessions = java.util.concurrent.ConcurrentHashMap<RoomId, CallSession>()

    /** The room's call. One object per room; it lives as long as this. */
    fun session(roomId: RoomId): CallSession = sessions.getOrPut(roomId) { CallSession(this, roomId) }
}

class CallSession internal constructor(private val rtc: MatrixRtc, val roomId: RoomId) {
    private val client = rtc.client
    private val scope = rtc.scope
    private val cfg = rtc.config
    private val me = client.userId.full
    private val device = client.deviceId
    private val lock = kotlinx.coroutines.sync.Mutex()

    private val ticker = flow { while (true) { emit(Unit); delay(15.seconds) } }

    /** Everyone in the call (all devices, unexpired), oldest first. */
    val memberships: StateFlow<List<CallMembership>> = combine(allMemberships(), ticker) { l, _ -> l }
        .map { l -> val now = cfg.now(); l.filter { !it.expired(now) }.sortedWith(compareBy({ it.createdTs }, { it.identity })) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Whether somebody is in the call. */
    val active: Flow<Boolean> get() = memberships.map { it.isNotEmpty() }.distinctUntilChanged()

    private fun allMemberships(): Flow<List<CallMembership>> = client.room.getAllState<CallMember>(roomId).flatMapLatest { map ->
        if (map.isNullOrEmpty()) flowOf(emptyList())
        else combine(map.values) { evs ->
            evs.filterNotNull().flatMap { e ->
                parseMemberships(e.stateKey, e.sender.full, e.id?.full, e.originTimestamp ?: 0, e.content.raw)
            }
        }
    }

    // ----- our own membership -----

    private val _joined = MutableStateFlow<Join?>(null)
    private class Join(val intent: CallIntent, val focus: LivekitFocus, val createdTs: Long, val jobs: MutableList<Job>, val keys: CallKeyManager?) {
        var delayId: String? = null
        var eventId: EventId? = null
        var refreshAt = 0L
        var ringEventId: EventId? = null
        val since = MutableStateFlow<Long?>(null)
    }

    /** Whether we are in the call (joined on this device). */
    val joined: StateFlow<Boolean> = _joined.map { it != null }.stateIn(scope, SharingStarted.Eagerly, false)

    /** Our membership as the room state shows it (null until it syncs back). */
    val mine: Flow<CallMembership?> get() = memberships.map { l -> l.firstOrNull { it.userId == me && it.deviceId == device } }.distinctUntilChanged()

    /** First moment someone else was in the call we are in (the call "started"). */
    val since: Flow<Long?> get() = _joined.flatMapLatest { it?.since ?: flowOf(null) }

    private val _signals = MutableSharedFlow<CallSignal>(extraBufferCapacity = 32)
    /** Hang-up hints and who joined or left; see [CallSignal]. */
    val signals: SharedFlow<CallSignal> = _signals.asSharedFlow()

    private suspend fun isDirect() = client.room.getById(roomId).first()?.isDirect == true
    private suspend fun isEncrypted() = client.room.getState<EncryptionEventContent>(roomId).first() != null

    /**
     * Joins the call on this device and returns what LiveKit needs. The SFU is the oldest membership's, else ours (discovery).
     * [ring] rings the others if we are the first one in (a "ring" in DMs, a quiet "notification" in groups).
     * Throws with a Persian message (see [errText]) when the server has no SFU or won't hand out a token.
     */
    suspend fun join(intent: CallIntent, ring: Boolean = true): SfuConnection = lock.withLock {
        check(_joined.value == null) { "already in this call" }
        val others = memberships.value.filter { !(it.userId == me && it.deviceId == device) }
        val mine = rtc.discovery.ours()
        val sfu = sfuOfOldest(others) ?: mine ?: throw SfuException("سرور شما از تماس پشتیبانی نمی‌کند")
        val token = rtc.sfu.token(sfu.serviceUrl, roomId.full, client.api.openIdToken(client.userId), device)
        val encrypted = isEncrypted()
        val createdTs = cfg.now()
        val keys = if (encrypted) CallKeyManager(scope, roomId, me, device, memberships, rtc.transports(client)) else null
        val j = Join(intent, mine ?: sfu, createdTs, mutableListOf(), keys)
        _joined.value = j
        try {
            j.delayId = startDelayedLeave()
            send(j)
            keys?.start()
            if (ring && others.isEmpty()) sendRing(j, others = emptyList())
        } catch (e: Throwable) {
            leaveNow(j)
            throw e
        }
        j.jobs += scope.launch { keepAlive(j) }
        j.jobs += scope.launch { watch(j, ring) }
        SfuConnection(token.url, token.jwt, "$me:$device", sfu, keys)
    }

    // expires is relative to created_ts, so a refresh later on extends it by the time passed since joining
    private fun content(j: Join) = CallMember(memberContent(device, j.intent, j.focus, roomId.full, j.createdTs, cfg.now() - j.createdTs + cfg.membershipExpiresMs))

    private suspend fun send(j: Join) {
        j.eventId = client.api.room.sendStateEvent(roomId, content(j), memberStateKey(me, device)).getOrThrow()
        j.refreshAt = cfg.now() + cfg.membershipExpiresMs * 4 / 5
    }

    private suspend fun startDelayedLeave(): String? =
        client.api.sendDelayedState(roomId, CallTypes.MEMBER, memberStateKey(me, device), JsonObject(emptyMap()), cfg.delayedLeaveMs)

    private suspend fun keepAlive(j: Join) {
        while (currentCoroutineContext().isActive) {
            delay(cfg.delayRestartMs)
            val id = j.delayId
            if (id != null && runCatching { client.api.delayedAction(id, "restart") }.isFailure) {
                j.delayId = startDelayedLeave()
                runCatching { send(j) } // the delayed leave may have fired: rejoin
            }
            if (cfg.now() >= j.refreshAt) runCatching { send(j) }
        }
    }

    private suspend fun sendRing(j: Join, others: List<String>) {
        val direct = isDirect()
        val to = if (direct) client.api.room.getJoinedMembers(roomId).getOrNull()?.joined?.keys?.map { it.full }.orEmpty().filter { it != me } else emptyList()
        val c = ringContent(j.eventId ?: return, direct, to.ifEmpty { others }, j.intent, cfg.now(), cfg.ringLifetimeMs)
        j.ringEventId = sendEvent(c)
    }

    private suspend fun watch(j: Join, ring: Boolean) = coroutineScope {
        val direct = isDirect()
        launch {
            var known = emptySet<Pair<String, String>>()
            memberships.collect { list ->
                val others = list.filter { !(it.userId == me && it.deviceId == device) }
                val now = others.map { it.userId to it.deviceId }.toSet()
                for ((u, d) in now - known) _signals.tryEmit(CallSignal.MemberJoined(u, d))
                for ((u, d) in known - now) _signals.tryEmit(CallSignal.MemberLeft(u, d))
                known = now
                if (others.isNotEmpty() && j.since.value == null) j.since.value = cfg.now()
                else if (direct && j.since.value != null && others.isEmpty()) _signals.tryEmit(CallSignal.OtherSideLeft)
            }
        }
        if (ring && direct) launch {
            delay(cfg.noAnswerMs)
            if (j.since.value == null) _signals.tryEmit(CallSignal.NoAnswer)
        }
        if (direct) launch {
            rtc.events.filter { it.roomId == roomId && (it.type == CallTypes.RTC_DECLINE || it.type == CallTypes.RTC_DECLINE_STABLE) && it.sender != me }
                .collect { if (j.since.value == null) _signals.tryEmit(CallSignal.Declined(it.sender)) }
        }
    }

    /** Leaves the call: stops the keys, sends the empty membership (our delayed leave is cancelled). Safe when not joined. */
    suspend fun leave() = lock.withLock { _joined.value?.let { leaveNow(it) } }

    private suspend fun leaveNow(j: Join) {
        _joined.value = null
        j.jobs.forEach { it.cancel() }
        j.keys?.stop()
        withContext(NonCancellable) {
            client.api.room.sendStateEvent(roomId, CallMember(JsonObject(emptyMap())), memberStateKey(me, device))
            j.delayId?.let { runCatching { client.api.delayedAction(it, "cancel") } }
        }
        hands.value = emptyMap()
    }

    // ----- sending events that must be encrypted like messages -----

    /** Sends [c] through the outbox (encrypted in encrypted rooms) and returns its event id once the server has it. */
    suspend fun sendEvent(c: MessageEventContent): EventId? = client.sendRoomEvent(roomId, c)

    // ----- ringing others, declining -----

    /** Declines a ring (MSC4310). [ringEventId] is the m.rtc.notification event. */
    suspend fun decline(ringEventId: String) { sendEvent(declineContent(EventId(ringEventId))) }

    // ----- raised hands and reactions -----

    private val hands = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Raised hands: membership event id to the 🖐️ reaction on it. */
    val raisedHands: Flow<Map<String, String>> = hands.asStateFlow()

    private val _reactions = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Reactions on screen: membership event id to its emoji (they clear themselves after a few seconds). */
    val reactions: StateFlow<Map<String, String>> = _reactions.asStateFlow()

    private val _reactionEvents = MutableSharedFlow<CallReactionEvent>(extraBufferCapacity = 32)
    /** Every reaction from others as it arrives (for sounds). */
    val reactionEvents: SharedFlow<CallReactionEvent> = _reactionEvents.asSharedFlow()

    private fun showReaction(mid: String, emoji: String) {
        if (emoji.isBlank()) return
        _reactions.update { it + (mid to emoji) }
        scope.launch { delay(cfg.reactionShowMs); _reactions.update { if (it[mid] == emoji) it - mid else it } }
    }

    /** Hands up on membership events: from the room's relations, plus live hands and redactions while we are in the call. */
    internal fun startTracking(): Job = scope.launch {
        launch { handsFromRelations().collect { hands.value = it } }
        launch {
            rtc.events.filter { it.roomId == roomId }.collect { ev ->
                when {
                    ev.type == CallTypes.CALL_REACTION && ev.sender != me -> {
                        val mid = ev.relatesTo ?: return@collect
                        val emoji = firstEmoji((ev.content["emoji"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "")
                        if (emoji.isEmpty()) return@collect
                        showReaction(mid, emoji)
                        _reactionEvents.tryEmit(CallReactionEvent(mid, ev.sender, emoji, ev.content.string("name") ?: "generic"))
                    }
                }
            }
        }
    }

    private fun handsFromRelations(): Flow<Map<String, String>> = memberships.flatMapLatest { ms ->
        val withId = ms.filter { it.eventId != null }
        if (withId.isEmpty()) flowOf(emptyMap())
        else combine(withId.map { m -> handOn(m) }) { arr -> arr.filterNotNull().toMap() }
    }

    private fun handOn(m: CallMembership): Flow<Pair<String, String>?> =
        client.room.getTimelineEventRelations(roomId, EventId(m.eventId!!), RelationType.Annotation).flatMapLatest { rel ->
            val ids = rel?.keys.orEmpty()
            if (ids.isEmpty()) flowOf(null)
            else combine(ids.map { id -> client.room.getTimelineEvent(roomId, id) }) { evs ->
                evs.filterNotNull().firstOrNull { te ->
                    te.event.sender.full == m.userId && (te.content?.getOrNull() as? Reaction)?.key == CallTypes.HAND
                }?.let { m.eventId!! to it.event.id.full }
            }
        }

    init { startTracking() }

    /** Raises our hand, or lowers it when it is up. */
    suspend fun toggleHand() {
        val mid = mine.first()?.eventId ?: return
        val up = hands.value[mid]
        if (up != null) {
            hands.update { it - mid }
            client.api.room.redactEvent(roomId, EventId(up)).getOrThrow()
        } else {
            val id = sendEvent(handContent(EventId(mid)))
            if (id != null) hands.update { it + (mid to id.full) }
        }
    }

    /** Sends an Element Call reaction (any emoji; Element shows names it doesn't know as the emoji) and shows it at once. */
    suspend fun react(emoji: String, name: String = reactionName(emoji)) {
        val mid = mine.first()?.eventId ?: return
        showReaction(mid, firstEmoji(emoji))
        sendEvent(callReactionContent(EventId(mid), emoji, name))
    }
}
