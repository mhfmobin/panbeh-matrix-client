package ir.panbeh.client.timeline

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.flatten
import de.connect2x.trixnity.client.flattenNotNull
import de.connect2x.trixnity.client.room
import de.connect2x.trixnity.client.room.getAccountData
import de.connect2x.trixnity.client.room.getState
import de.connect2x.trixnity.client.room.getTimeline
import de.connect2x.trixnity.client.room.getTimelineEventReplaceAggregation
import de.connect2x.trixnity.client.room.GetTimelineEventConfig
import de.connect2x.trixnity.client.room.GetTimelineEventsConfig
import de.connect2x.trixnity.client.room.SimpleTimeline
import de.connect2x.trixnity.client.roomEventEncryptionServices
import de.connect2x.trixnity.client.room.decrypt
import de.connect2x.trixnity.client.store.RoomOutboxMessage
import de.connect2x.trixnity.client.store.TimelineEvent
import de.connect2x.trixnity.client.store.originTimestamp
import de.connect2x.trixnity.client.user
import de.connect2x.trixnity.client.user.getAccountData
import de.connect2x.trixnity.core.model.events.m.IgnoredUserListEventContent
import de.connect2x.trixnity.core.model.EventId
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.events.ClientEvent.RoomEvent
import de.connect2x.trixnity.core.model.events.MessageEventContent
import de.connect2x.trixnity.core.model.events.RoomEventContent
import de.connect2x.trixnity.core.model.events.m.FullyReadEventContent
import de.connect2x.trixnity.core.model.events.m.ReceiptType
import de.connect2x.trixnity.core.model.events.m.RelatesTo
import de.connect2x.trixnity.core.model.events.m.RelationType
import de.connect2x.trixnity.core.model.events.m.room.EncryptedMessageEventContent
import de.connect2x.trixnity.core.model.events.m.room.PinnedEventsEventContent
import de.connect2x.trixnity.core.model.events.m.room.RedactionEventContent
import ir.panbeh.client.events.Reaction
import ir.panbeh.core.timeline.Msg
import ir.panbeh.core.timeline.MsgKind
import ir.panbeh.core.timeline.buildRows
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import de.connect2x.trixnity.client.room.TimelineState as TxState

/** Tuning of a [RoomTimeline]. */
data class TimelineOptions(
    /** how long an encrypted event waits for its keys before showing as undecryptable */
    val decryptionTimeout: Duration = 10.seconds,
    /** an older/newer page is held back until decrypted, but at most this long */
    val holdTimeout: Duration = 5.seconds,
    /** events per page */
    val pageSize: Long = 40,
    /** deleted messages stay in the list as [TimelineItem.redacted] items (the web app drops them) */
    val showRedacted: Boolean = false,
    /** how long finding an old event for a jump may take (it pages the history back to it) */
    val jumpTimeout: Duration = 60.seconds,
)

/** Where [RoomTimeline.jump] ended up. */
sealed interface JumpResult {
    /** the event is in the list now ([TimelineState.focus]) */
    data object Found : JumpResult
    data object NotFound : JumpResult
    /** the event is a reply in a thread: open that thread ([root] is the thread's root) instead */
    data class InThread(val root: String) : JumpResult
}

private class Snapshot(val entries: List<Entry>, val st: TxState<*>)
private class Hold(val older: EventId? = null, val newer: EventId? = null, val blockAll: Boolean = false)
private class Inputs(val snap: Snapshot, val hold: Hold, val ctx: Ctx, val unread: String?, val follow: Boolean, val busy: Pair<Boolean, Boolean>)

/**
 * A paged view of one room's timeline (or of one thread's, with [thread] = the root event id): the newest messages at the live end
 * with new ones following, older pages on demand, and windows around any event (jumps to replies, pins, search results, the unread
 * marker) that extend both ways until they meet the live end. Older and newer pages appear only once decrypted.
 *
 * Collect [state]; call [open] first. [close] stops it. The UI is expected to call [loadOlder] when the reader nears the top.
 */
class RoomTimeline(
    private val client: MatrixClient,
    val roomId: String,
    private val scope: CoroutineScope,
    val thread: String? = null,
    private val options: TimelineOptions = TimelineOptions(),
) {
    private val room = RoomId(roomId)
    private val me = client.userId.full
    private val codec = Codec(client)
    private val asm = Assembler(codec, roomId, me)
    private val tl: SimpleTimeline = client.room.getTimeline()
    private val loader = EventLoader(client, roomId)
    private val sender = Sender(client)

    private val _state = MutableStateFlow(TimelineState())
    /** What to show. */
    val state: StateFlow<TimelineState> = _state.asStateFlow()

    private val hold = MutableStateFlow(Hold())
    private val unread = MutableStateFlow<String?>(null)
    private val follow = MutableStateFlow(false)
    private val busyOlder = MutableStateFlow(false)
    private val busyNewer = MutableStateFlow(false)
    private var jobs = emptyList<Job>()
    @Volatile private var started = false
    @Volatile private var lastBuilt: Built? = null
    @Volatile private var lastCtx: Ctx = Ctx()
    private var focusSeq = 0
    // Trixnity keeps offering a page at the very start of a room (a gap marker nothing clears): a page that adds nothing means we're there
    @Volatile private var olderExhausted = false
    @Volatile private var newerExhausted = false
    private var windowSeq = 0
    private var focus: String? = null
    private val receiptTsCache = ConcurrentHashMap<String, Long>()

    // thread mode: events fetched from /relations that the store may not have
    private val fetched = MutableStateFlow<Map<String, TimelineEvent>>(emptyMap())
    private var threadToken: String? = null
    private val threadMore = MutableStateFlow(true)

    private val startCfg: GetTimelineEventConfig.() -> Unit = { fetchTimeout = 30.seconds; decryptionTimeout = options.decryptionTimeout }
    private val beforeCfg: GetTimelineEventsConfig.() -> Unit = { maxSize = options.pageSize; fetchSize = 100; decryptionTimeout = options.decryptionTimeout }
    private val afterCfg: GetTimelineEventsConfig.() -> Unit = { maxSize = options.pageSize; fetchSize = 100; decryptionTimeout = options.decryptionTimeout }

    // ---------- opening ----------

    /** Starts the timeline at the read point: the unread divider is placed once, from m.fully_read / my receipt / my last event. */
    suspend fun open() {
        if (started) return
        started = true
        client.user.loadMembers(room, wait = false)
        if (thread != null) {
            startPipeline()
            loadOlder()
            return
        }
        val last = withTimeout(30.seconds) { client.room.getById(room).filterNotNull().first { it.lastEventId != null } }.lastEventId!!
        startPipeline()
        tl.init(room, last, startCfg, beforeCfg, afterCfg)
        follow.value = true
        _state.first { it.initialized }
        // a handful of messages doesn't fill the screen (and without overflow nothing asks for more): page back
        var tries = 0
        while (tries++ < 5 && _state.value.canLoadOlder && (lastBuilt?.items?.count { it.kind == ItemKind.MESSAGE } ?: 0) < 20) loadOlder()
        placeUnreadMarker()
    }

    private suspend fun placeUnreadMarker() {
        val marker = client.room.getAccountData<FullyReadEventContent>(room).first()?.eventId?.full
        val receipt = lastCtx.receipts[client.userId]?.receipts?.let { r -> r[ReceiptType.Read] ?: r[ReceiptType.PrivateRead] }?.eventId?.full
        val b = lastBuilt ?: return
        val read = readIndex(b, marker, receipt)
        if (read >= 0) { unread.value = anchor(b, read); return }
        // the read point is older than what's loaded and there are messages from others: open the history around it
        val target = receipt ?: marker ?: return
        if (b.slim.none { it.isMessage && it.sender != me }) return
        if (jump(target) is JumpResult.Found) {
            focus = null
            _state.value = _state.value.copy(focus = null)
            val nb = lastBuilt ?: return
            val r = readIndex(nb, marker, receipt)
            if (r >= 0) unread.value = anchor(nb, r)
        }
    }

    private fun readIndex(b: Built, marker: String?, receipt: String?): Int {
        var read = -1
        b.slim.forEachIndexed { i, e -> if (e.id == marker || e.id == receipt || e.sender == me) read = i }
        return read
    }

    private fun anchor(b: Built, read: Int): String? {
        if (b.slim.drop(read + 1).none { it.isMessage && it.sender != me }) return null
        return b.slim.take(read + 1).lastOrNull { it.key != null }?.key
    }

    // ---------- the pipeline: events + what derives from them → items → state ----------

    private fun startPipeline() {
        val j1 = scope.launch {
            combine(snapshots(), hold, context(), combine(unread, follow, busyOlder, busyNewer) { u, f, o, n -> Triple(u, f, o to n) }) { s, h, c, x -> Inputs(s, h, c, x.first, x.second, x.third) }
                .conflate().collect { publish(it) }
        }
        val j2 = scope.launch { followLive() }
        jobs = listOf(j1, j2)
    }

    /** Stops the timeline. */
    fun close() { jobs.forEach { it.cancel() } }

    private suspend fun followLive() {
        if (thread != null) return
        while (scope.isActive) {
            follow.first { it }
            try {
                tl.loadAfter { fetchTimeout = Duration.ZERO; decryptionTimeout = options.decryptionTimeout }
            } catch (e: CancellationException) {
                if (!scope.isActive) throw e
            } catch (_: Exception) { delay(1000) }
        }
    }

    private fun context(): Flow<Ctx> = combine(
        client.user.getAll(room).flattenNotNull(),
        client.user.getAllReceipts(room).flattenNotNull(),
        client.room.getOutbox(room).flatten(),
        client.room.getState<PinnedEventsEventContent>(room).map { s -> s?.content?.pinned?.map { it.full }?.toSet() ?: emptySet() },
        client.user.getAccountData<IgnoredUserListEventContent>().map { c -> c?.ignoredUsers?.keys?.map { it.full }?.toSet() ?: emptySet() },
    ) { m, r, o, p, i -> Ctx(m, r, o.filterIsInstance<RoomOutboxMessage<*>>(), p, i) }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun snapshots(): Flow<Snapshot> =
        if (thread != null) threadSnapshots()
        else tl.state.flatMapLatest { st ->
            if (!st.isInitialized || st.elements.isEmpty()) flowOf(Snapshot(emptyList(), st))
            else flow {
                val withId = st.elements.map { f -> f to f.first().event.id }
                emitAll(combine(withId.map { (f, id) -> entryFlow(f, id) }) { arr -> Snapshot(arr.toList(), st) })
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun threadSnapshots(): Flow<Snapshot> {
        val root = EventId(thread!!)
        val ids = client.room.getTimelineEventRelations(room, root, RelationType.Thread).map { it?.keys ?: emptySet() }.distinctUntilChanged()
        val st = TxState<Flow<TimelineEvent>>(isInitialized = true)
        return combine(ids, fetched) { a, b -> a to b }.flatMapLatest { (stored, fromServer) ->
            val all = linkedSetOf(root).also { it.addAll(stored) }
            val flows = all.map { id -> id to client.room.getTimelineEvent(room, id) { decryptionTimeout = options.decryptionTimeout; fetchTimeout = Duration.ZERO }.map { it } }
            val extra = fromServer.filterKeys { EventId(it) !in all }.values.map { te -> te.event.id to flowOf<TimelineEvent?>(te) }
            val parts = flows + extra
            combine(parts.map { (id, f) -> flow { emitAll(entryFlow(f.filterNotNull(), id)) } }) { arr ->
                val sorted = arr.sortedWith(compareBy<Entry> { if (it.te.event.id == root) 0 else 1 }.thenBy { it.te.originTimestamp }.thenBy { it.te.event.id.full })
                Snapshot(sorted, st)
            }
        }
    }

    /** One event with what derives from other events: reactions, edited, thread replies. */
    private fun entryFlow(f: Flow<TimelineEvent>, id: EventId): Flow<Entry> = flow {
        val first = f.first()
        val c = first.event.content
        val rel = (c as? MessageEventContent)?.relatesTo
        val skip = first.event !is RoomEvent.MessageEvent<*> || rel is RelatesTo.Annotation || rel is RelatesTo.Replace ||
            c is Reaction || c is RedactionEventContent || (thread == null && rel is RelatesTo.Thread)
        if (skip) emitAll(f.map { Entry(it) })
        else emitAll(
            combine(
                f,
                client.related(room, id, RelationType.Annotation, options.decryptionTimeout),
                client.room.getTimelineEventReplaceAggregation(room, id).map { it.replacedBy != null }.distinctUntilChanged(),
                client.room.getTimelineEventRelations(room, id, RelationType.Thread).map { it?.size ?: 0 }.distinctUntilChanged(),
            ) { te, r, ed, th -> Entry(te, r, ed, th) },
        )
    }

    private suspend fun publish(inp: Inputs) {
        if (inp.hold.blockAll) return
        var entries = inp.snap.entries
        inp.hold.older?.let { b -> entries.indexOfFirst { it.te.event.id == b }.takeIf { it > 0 }?.let { entries = entries.drop(it) } }
        inp.hold.newer?.let { b -> entries.indexOfFirst { it.te.event.id == b }.takeIf { it >= 0 }?.let { entries = entries.take(it + 1) } }
        lastCtx = inp.ctx
        val built = asm.build(entries, inp.ctx, thread, options.showRedacted, receiptTs = ::receiptTs)
        lastBuilt = built
        val items = built.items
        val shown = items.map { Msg(it.key, it.sender, it.ts, if (it.kind == ItemKind.MESSAGE) MsgKind.MSG else MsgKind.NOTICE, if (it.kind == ItemKind.MESSAGE) it.album else null) }
        val rows = buildRows(shown, System.currentTimeMillis(), inp.unread)
        val st = inp.snap.st
        val cur = _state.value
        _state.value = cur.copy(
            items = items, rows = rows, unreadAfter = inp.unread, initialized = thread != null || st.isInitialized,
            loadingOlder = inp.busy.first, loadingNewer = inp.busy.second,
            canLoadOlder = if (thread != null) threadMore.value else st.canLoadBefore && !olderExhausted,
            canLoadNewer = thread == null && st.canLoadAfter && !inp.follow && !newerExhausted,
            atLive = thread != null || !st.canLoadAfter || inp.follow,
            focus = focus, focusSeq = focusSeq, windowSeq = windowSeq,
        )
    }

    private fun refresh(f: TimelineState.() -> TimelineState) { _state.value = _state.value.f() }

    /** Origin time of an event outside the loaded range (a receipt can point anywhere), if the store has it. */
    private suspend fun receiptTs(eventId: String): Long? {
        receiptTsCache[eventId]?.let { return it }
        val te = client.room.getTimelineEvent(room, EventId(eventId)) { fetchTimeout = Duration.ZERO; decryptionTimeout = Duration.ZERO; allowReplaceContent = false }.first() ?: return null
        return te.originTimestamp.also { receiptTsCache[eventId] = it }
    }

    // ---------- paging ----------

    private suspend fun awaitDecrypted(added: List<Flow<TimelineEvent>>) {
        withTimeoutOrNull(options.holdTimeout) { coroutineScope { added.map { f -> async { f.first { it.content != null } } }.awaitAll() } }
    }

    /** Loads the next older page (decrypted before it shows). No-op while loading or at the start of the room. */
    suspend fun loadOlder() {
        if (thread != null) return loadOlderThread()
        val st = _state.value
        if (!st.initialized || !st.canLoadOlder || !busyOlder.compareAndSet(false, true)) return
        refresh { copy(loadingOlder = true) }
        var released: String? = null
        try {
            val t0 = System.currentTimeMillis()
            val first = lastBuilt?.slim?.firstOrNull()?.id
            if (first != null) hold.value = Hold(older = EventId(first), newer = hold.value.newer)
            val ch = tl.loadBefore { fetchSize = options.pageSize; maxSize = options.pageSize; fetchTimeout = 30.seconds; decryptionTimeout = options.decryptionTimeout }
            awaitDecrypted(ch.addedElements)
            if (ch.addedElements.isEmpty() && System.currentTimeMillis() - t0 < 25_000) olderExhausted = true // (not a timeout)
            released = ch.addedElements.firstOrNull()?.first()?.event?.id?.full
        } finally {
            hold.value = Hold(newer = hold.value.newer)
            busyOlder.value = false
            refresh { copy(loadingOlder = false) }
        }
        released?.let { awaitLoaded(it) }
    }

    /** In a window around an old event: loads the next newer page, until the window meets the live end. */
    suspend fun loadNewer() {
        if (thread != null) return
        val st = _state.value
        if (!st.initialized || !st.canLoadNewer || !busyNewer.compareAndSet(false, true)) return
        refresh { copy(loadingNewer = true) }
        var released: String? = null
        try {
            val t0 = System.currentTimeMillis()
            val last = lastBuilt?.slim?.lastOrNull()?.id
            if (last != null) hold.value = Hold(older = hold.value.older, newer = EventId(last))
            val ch = tl.loadAfter { fetchSize = options.pageSize; maxSize = options.pageSize; fetchTimeout = 30.seconds; decryptionTimeout = options.decryptionTimeout }
            awaitDecrypted(ch.addedElements)
            if (ch.addedElements.isEmpty() && System.currentTimeMillis() - t0 < 25_000) newerExhausted = true
            released = ch.addedElements.lastOrNull()?.first()?.event?.id?.full
        } finally {
            hold.value = Hold(older = hold.value.older)
            busyNewer.value = false
            if (!tl.state.first().canLoadAfter) follow.value = true
            refresh { copy(loadingNewer = false) }
        }
        released?.let { awaitLoaded(it) }
    }

    /** Drops a window and shows the newest messages again. */
    suspend fun jumpToLive() {
        if (thread != null) return
        val last = client.room.getById(room).filterNotNull().first { it.lastEventId != null }.lastEventId!!
        if (reinit(last, null)) awaitLoaded(last.full)
    }

    private suspend fun loadOlderThread() {
        if (!threadMore.value || !busyOlder.compareAndSet(false, true)) return
        refresh { copy(loadingOlder = true) }
        try {
            val r = client.api.room.getRelations(room, EventId(thread!!), RelationType.Thread, from = threadToken, limit = options.pageSize).getOrThrow()
            val add = r.chunk.associate { ev ->
                val m = ev as? RoomEvent.MessageEvent<*>
                val content: Result<RoomEventContent>? = if (m != null && m.content is EncryptedMessageEventContent)
                    withTimeoutOrNull(options.decryptionTimeout) { client.roomEventEncryptionServices.decrypt(m) }?.map { it as RoomEventContent }
                        ?: Result.failure(TimelineEvent.TimelineEventContentError.DecryptionTimeout)
                else Result.success(ev.content)
                ev.id.full to TimelineEvent(ev, content)
            }
            fetched.value = fetched.value + add
            threadToken = r.end
            threadMore.value = r.end != null && r.chunk.isNotEmpty()
        } finally {
            busyOlder.value = false
            refresh { copy(loadingOlder = false, canLoadOlder = threadMore.value) }
        }
    }

    // ---------- jumping ----------

    /**
     * Scrolls to an event: sets [TimelineState.focus]. If it isn't loaded, opens the history around it (a window that extends both
     * ways); finding a very old event pages the history back to it. A thread reply isn't in the main timeline: [JumpResult.InThread].
     */
    suspend fun jump(eventId: String): JumpResult {
        lastBuilt?.slim?.firstOrNull { it.id == eventId }?.let { e ->
            if (thread == null && e.thread != null) return JumpResult.InThread(e.thread)
            setFocus(eventId)
            return JumpResult.Found
        }
        val te = loader.raw(eventId) ?: return JumpResult.NotFound
        val rel = ((te.content?.getOrNull() ?: te.event.content) as? MessageEventContent)?.relatesTo ?: (te.event.content as? MessageEventContent)?.relatesTo
        if (thread == null && rel is RelatesTo.Thread) return JumpResult.InThread(rel.eventId.full)
        if (thread != null) {
            // thread: page back until it's there
            var pages = 0
            while (lastBuilt?.slim?.any { it.id == eventId } != true) { if (pages++ >= 10 || !threadMore.value) return JumpResult.NotFound; loadOlderThread(); delay(100) }
            setFocus(eventId)
            return JumpResult.Found
        }
        if (!reinit(EventId(eventId), eventId)) return JumpResult.NotFound
        awaitLoaded(eventId)
        return JumpResult.Found
    }

    /** Waits (a few seconds at most) until the pipeline has built a state that contains [eventId]. */
    private suspend fun awaitLoaded(eventId: String) {
        withTimeoutOrNull(10.seconds) { while (lastBuilt?.slim?.any { it.id == eventId } != true) delay(50) }
    }

    private fun setFocus(id: String?) {
        focus = id
        focusSeq++
        refresh { copy(focus = id, focusSeq = focusSeq) }
    }

    private suspend fun reinit(start: EventId, focusOn: String?): Boolean {
        olderExhausted = false
        newerExhausted = false
        follow.value = false
        hold.value = Hold(blockAll = true)
        try {
            val ok = withTimeoutOrNull(options.jumpTimeout) {
                tl.init(room, start, { fetchSize = 100; fetchTimeout = options.jumpTimeout; decryptionTimeout = options.decryptionTimeout }, beforeCfg, afterCfg)
                true
            } ?: false
            if (!ok) return false
            val st = tl.state.first { it.isInitialized }
            awaitDecrypted(st.elements)
            windowSeq++
            follow.value = !st.canLoadAfter
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        } finally {
            hold.value = Hold()
            if (tl.state.first().let { it.isInitialized && !it.canLoadAfter }) follow.value = true
            if (focusOn != null) setFocus(focusOn) else refresh { copy(windowSeq = windowSeq) }
        }
    }

    // ---------- other ----------

    /** An event that may not be loaded (the target of a reply): from the store, else fetched and decrypted. Null if not found. */
    suspend fun loadEvent(eventId: String): TimelineItem? =
        state.value.items.firstOrNull { it.eventId == eventId } ?: loader.load(eventId)

    /** Read receipt (and fully-read marker in the main timeline) for [eventId]. Call only while the user is attentive. Skips what is already read or mine. */
    suspend fun markRead(eventId: String) {
        val item = state.value.items.firstOrNull { it.eventId == eventId }
        if (item?.isMine == true) return
        val have = lastCtx.receipts[client.userId]?.receipts?.get(ReceiptType.Read)?.eventId?.full
        if (have == eventId) return
        sender.markRead(roomId, eventId, thread)
    }

    /** Sends a failed message again. */
    suspend fun retry(item: TimelineItem) { item.txnId?.let { sender.retry(roomId, it) } }

    /** Removes an unsent or failed message. */
    suspend fun cancel(item: TimelineItem) { item.txnId?.let { sender.cancel(roomId, it) } }
}
