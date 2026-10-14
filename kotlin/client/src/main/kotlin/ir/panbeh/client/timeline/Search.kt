package ir.panbeh.client.timeline

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.room
import de.connect2x.trixnity.client.roomEventEncryptionServices
import de.connect2x.trixnity.client.room.decrypt
import de.connect2x.trixnity.client.store.TimelineEvent
import de.connect2x.trixnity.client.user
import de.connect2x.trixnity.clientserverapi.model.room.GetEvents
import de.connect2x.trixnity.clientserverapi.model.server.Search
import de.connect2x.trixnity.clientserverapi.model.user.Filters
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.events.ClientEvent.RoomEvent
import de.connect2x.trixnity.core.model.events.RoomEventContent
import de.connect2x.trixnity.core.model.events.m.RelatesTo
import de.connect2x.trixnity.core.model.events.m.room.EncryptedMessageEventContent
import de.connect2x.trixnity.core.model.events.m.room.Membership
import de.connect2x.trixnity.core.model.events.m.room.RedactionEventContent
import de.connect2x.trixnity.core.model.events.m.room.RoomMessageEventContent
import ir.panbeh.core.text.normalize
import ir.panbeh.core.util.string
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration.Companion.seconds

/** What the local index keeps of a message. */
data class IndexedMessage(val id: String, val roomId: String, val ts: Long, val sender: String, val text: String)

/** Where [SearchIndexer.backfill] goes on in a room: the /messages token of the next older page, and whether history is exhausted. */
data class BackfillMeta(val roomId: String, val token: String?, val done: Boolean)

/**
 * A local index of decrypted messages, so encrypted rooms can be searched beyond what's loaded (the server can't search them).
 * The Android side implements this with a database (writes should be batched; this API is per message); [InMemorySearchIndex] is the
 * reference implementation.
 */
interface SearchIndex {
    suspend fun put(m: IndexedMessage)
    /** Replaces the text of message [id], but only if [sender] is its author. */
    suspend fun edit(id: String, sender: String, text: String)
    suspend fun remove(id: String)
    /** Messages whose text contains [term] (both folded with core's normalize), newest first; in one room or all. */
    suspend fun search(term: String, roomId: String?, limit: Int): List<IndexedMessage>
    suspend fun meta(roomId: String): BackfillMeta?
    suspend fun setMeta(m: BackfillMeta)
    /** Drops everything (the pref was turned off, or signing out). */
    suspend fun clear()
}

/** A simple linear scan over a map; fine for tens of thousands of messages. */
class InMemorySearchIndex : SearchIndex {
    private val lock = Mutex()
    private val msgs = HashMap<String, IndexedMessage>()
    private val metas = HashMap<String, BackfillMeta>()
    override suspend fun put(m: IndexedMessage) = lock.withLock { msgs[m.id] = m }
    override suspend fun edit(id: String, sender: String, text: String) = lock.withLock { msgs[id]?.takeIf { it.sender == sender }?.let { msgs[id] = it.copy(text = text) }; Unit }
    override suspend fun remove(id: String) = lock.withLock { msgs.remove(id); Unit }
    override suspend fun search(term: String, roomId: String?, limit: Int): List<IndexedMessage> = lock.withLock {
        val t = normalize(term.trim())
        msgs.values.filter { (roomId == null || it.roomId == roomId) && normalize(it.text).contains(t) }.sortedByDescending { it.ts }.take(limit)
    }
    override suspend fun meta(roomId: String) = lock.withLock { metas[roomId] }
    override suspend fun setMeta(m: BackfillMeta) = lock.withLock { metas[m.roomId] = m; Unit }
    override suspend fun clear() = lock.withLock { msgs.clear(); metas.clear() }
    suspend fun size() = lock.withLock { msgs.size }
}

/** What a message is found by: its text (no reply quote), a caption, or a file's name. */
fun indexText(c: JsonObject): String {
    val body = stripReplyFallback(c.string("body") ?: "")
    val f = c.string("filename")
    return if (f != null && f != body) "$body $f" else body
}

/**
 * Feeds a [SearchIndex]: messages of encrypted rooms as they decrypt (only those are worth indexing: the server searches the others),
 * edits by their author, redactions, and [backfill] paging a room's history. [enabled] is the user's `searchIndex` preference.
 */
class SearchIndexer(private val client: MatrixClient, private val index: SearchIndex, private val scope: CoroutineScope, private val enabled: () -> Boolean = { true }) {
    private val codec = Codec(client)
    private var job: Job? = null
    private var useFilter = true // Conduit rejected some /messages filters; dropped after the first refusal
    private val pendingEdits = HashMap<String, Triple<String, String, Long>>() // original id → (sender, text, ts) of an edit that came before it

    /** Starts indexing new messages as they arrive and decrypt. */
    fun start() {
        if (job != null) return
        job = scope.launch {
            client.room.getTimelineEventsFromNowOn().collect { te -> runCatching { add(te) } }
        }
    }

    fun stop() { job?.cancel(); job = null }

    /** Takes in what's already loaded and decrypted (turning the index on): [items] of an encrypted room's timeline. */
    suspend fun indexLoaded(items: List<TimelineItem>) {
        if (!enabled()) return
        for (i in items) {
            val id = i.eventId ?: continue
            if (i.type != "m.room.message" || i.redacted || i.decryption != Decryption.OK || i.sendState != null) continue
            val text = indexText(i.content)
            if (text.isNotBlank()) index.put(IndexedMessage(id, i.roomId, i.ts, i.sender, text))
        }
    }

    private suspend fun add(te: TimelineEvent) {
        if (!enabled()) return
        val ev = te.event as? RoomEvent.MessageEvent<*> ?: return
        val c = te.content?.getOrNull() ?: ev.content
        if (c is RedactionEventContent) { index.remove(c.redacts.full); return }
        if (ev.content !is EncryptedMessageEventContent) return // only encrypted rooms: the server searches the others
        add(ev, c)
    }

    private suspend fun add(ev: RoomEvent<*>, c: RoomEventContent) {
        if (c !is RoomMessageEventContent) return
        val rel = c.relatesTo
        val sender = ev.sender.full
        if (rel is RelatesTo.Replace) {
            val nc = rel.newContent ?: return
            val text = indexText(codec.jsonOf(nc))
            index.edit(rel.eventId.full, sender, text)
            pendingEdits[rel.eventId.full] = Triple(sender, text, ev.originTimestamp ?: 0)
            return
        }
        val json = codec.jsonOf(c)
        var text = indexText(json)
        pendingEdits.remove(ev.id.full)?.let { (s, t) -> if (s == sender) text = t }
        if (text.isBlank()) return
        index.put(IndexedMessage(ev.id.full, ev.roomId.full, ev.originTimestamp ?: 0, sender, text))
    }

    /** One page (newest first) of /messages, decrypted, from [token] (null = the latest). Returns the events and the next older token. */
    private suspend fun page(roomId: String, token: String?): Pair<List<Pair<RoomEvent<*>, RoomEventContent>>, String?> {
        val room = RoomId(roomId)
        val from = token ?: client.room.getById(room).first()?.lastEventId?.let { client.api.room.getEventContext(room, it, limit = 0).getOrThrow().start }
            ?: return emptyList<Pair<RoomEvent<*>, RoomEventContent>>() to null
        val filter = if (useFilter) """{"types":["m.room.message","m.room.encrypted"]}""" else null
        val r = try {
            client.api.room.getEvents(room, from, GetEvents.Direction.BACKWARDS, limit = 100, filter = filter).getOrThrow()
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (!useFilter) throw e
            useFilter = false
            return page(roomId, token)
        }
        val out = ArrayList<Pair<RoomEvent<*>, RoomEventContent>>()
        for (ev in r.chunk.orEmpty()) {
            val m = ev as? RoomEvent.MessageEvent<*> ?: continue
            if (m.content is EncryptedMessageEventContent) {
                val dec = withTimeoutOrNull(10.seconds) { client.roomEventEncryptionServices.decrypt(m) }?.getOrNull() ?: continue
                out.add(ev to dec)
            } else out.add(ev to ev.content)
        }
        return out to if (r.chunk.isNullOrEmpty()) null else r.end
    }

    /**
     * Pages a room's history further back ([pages] × 100 events, from where the last call stopped) so it's indexed.
     * Resolves to whether more history remains.
     */
    suspend fun backfill(roomId: String, pages: Int = 5): Boolean {
        var meta = index.meta(roomId) ?: BackfillMeta(roomId, null, false)
        var i = 0
        while (i++ < pages && !meta.done) {
            val (events, end) = page(roomId, meta.token)
            // only encrypted rooms are indexed (the others are searched on the server)
            val encrypted = client.room.getById(RoomId(roomId)).first()?.encrypted == true
            if (encrypted) for ((ev, c) in events) if (enabled()) add(ev, c)
            meta = BackfillMeta(roomId, end, end == null)
            index.setMeta(meta)
        }
        return !meta.done
    }

    /** Whether a room's history is indexed all the way back. */
    suspend fun backfillDone(roomId: String) = index.meta(roomId)?.done == true
}

/** Search results; [serverFailed] when the server couldn't search (only loaded / indexed messages were searched). */
data class SearchResult(val items: List<TimelineItem>, val serverFailed: Boolean = false)

/** Search terms need at least this many characters. */
const val MIN_SEARCH = 2

/** In-room and global message search, merging the server (unencrypted rooms), the local index and what's loaded. */
class MessageSearch(private val client: MatrixClient, private val ix: SearchIndex? = null, private val indexOn: () -> Boolean = { ix != null }) {
    private val codec = Codec(client)
    private fun byNewest(l: List<TimelineItem>): List<TimelineItem> {
        val seen = HashSet<String>()
        return l.filter { seen.add(it.eventId ?: it.key) }.sortedByDescending { it.ts }
    }

    /** Loaded messages containing the term, newest first. */
    fun scanLoaded(items: List<TimelineItem>, term: String): List<TimelineItem> {
        val t = normalize(term.trim())
        return items.filter { it.kind == ItemKind.MESSAGE && !it.redacted && it.eventId != null && normalize(it.preview).contains(t) }.sortedByDescending { it.ts }
    }

    private suspend fun fromIndex(m: IndexedMessage): TimelineItem {
        val name = client.user.getById(RoomId(m.roomId), de.connect2x.trixnity.core.model.UserId(m.sender)).first()?.name ?: m.sender
        val c = JsonObject(mapOf("msgtype" to kotlinx.serialization.json.JsonPrimitive("m.text"), "body" to kotlinx.serialization.json.JsonPrimitive(m.text)))
        return TimelineItem(m.id, m.id, null, m.roomId, m.sender, name, null, m.ts, "m.room.message", "m.text", c, m.sender == client.userId.full, ItemKind.MESSAGE, preview = m.text)
    }

    private suspend fun server(term: String, roomId: String?): List<TimelineItem> {
        val req = Search.Request(Search.Request.Categories(Search.Request.Categories.RoomEventsCriteria(
            searchTerm = term,
            orderBy = Search.Request.Categories.RoomEventsCriteria.Ordering.RECENT,
            // Conduit rejects a missing filter
            filter = Filters.RoomFilter.RoomEventFilter(rooms = roomId?.let { setOf(it) }),
        )))
        val r = client.api.server.search(req).getOrThrow()
        return r.searchCategories.roomEvents?.results.orEmpty().mapNotNull { it.result }.mapNotNull { ev ->
            EventLoader(client, ev.roomId.full).item(TimelineEvent(ev, Result.success(ev.content)))
        }
    }

    /** Search in one room. [loaded]: the room's timeline items. Encrypted rooms use the local index (if on) plus what's loaded. */
    suspend fun inRoom(roomId: String, term: String, loaded: List<TimelineItem>): SearchResult {
        if (term.trim().length < MIN_SEARCH) return SearchResult(emptyList())
        val enc = client.room.getById(RoomId(roomId)).first()?.encrypted == true
        val scan = scanLoaded(loaded, term)
        return when {
            enc && ix != null && indexOn() -> SearchResult(byNewest(runCatching { ix!!.search(term, roomId, 50).map { fromIndex(it) } }.getOrDefault(emptyList()) + scan))
            enc -> SearchResult(scan)
            else -> {
                var failed = false
                // the server doesn't fold ي/ك or ZWNJ; the local scan catches loaded messages it misses
                val s = runCatching { server(term, roomId) }.getOrElse { failed = true; emptyList() }
                SearchResult(byNewest(s + scan), failed)
            }
        }
    }

    /** Search across all joined rooms: server results, loaded messages of encrypted rooms ([loaded] by room id), and the local index. */
    suspend fun everywhere(term: String, loaded: Map<String, List<TimelineItem>> = emptyMap()): SearchResult {
        if (term.trim().length < MIN_SEARCH) return SearchResult(emptyList())
        val joined = client.room.getAll().first().filter { (_, f) -> f.first()?.membership == Membership.JOIN }.keys.map { it.full }.toSet()
        var failed = false
        val s = runCatching { server(term, null) }.getOrElse { failed = true; emptyList() }.filter { it.roomId in joined }
        val local = loaded.filterKeys { it in joined }.flatMap { (_, items) -> scanLoaded(items, term) }
        val fromIx = if (ix != null && indexOn()) runCatching { ix!!.search(term, null, 50).filter { it.roomId in joined }.map { fromIndex(it) } }.getOrDefault(emptyList()) else emptyList()
        return SearchResult(byNewest(s + local + fromIx).take(50), failed)
    }
}
