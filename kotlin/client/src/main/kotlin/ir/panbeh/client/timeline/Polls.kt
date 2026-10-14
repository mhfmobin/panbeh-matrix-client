package ir.panbeh.client.timeline

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.flatten
import de.connect2x.trixnity.client.room
import de.connect2x.trixnity.client.room.getState
import de.connect2x.trixnity.client.store.originTimestamp
import de.connect2x.trixnity.client.store.sender
import de.connect2x.trixnity.client.user
import de.connect2x.trixnity.client.user.PowerLevel
import de.connect2x.trixnity.core.model.EventId
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.UserId
import de.connect2x.trixnity.core.model.events.m.RelationType
import de.connect2x.trixnity.core.model.events.m.room.PowerLevelsEventContent
import ir.panbeh.client.events.PollEnd
import ir.panbeh.client.events.PollEndStable
import ir.panbeh.client.events.PollResponse
import ir.panbeh.client.events.PollResponseStable
import ir.panbeh.client.events.Types
import ir.panbeh.core.rooms.PollTally
import ir.panbeh.core.rooms.Vote
import ir.panbeh.core.rooms.tallyPoll
import ir.panbeh.core.util.string
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

data class PollAnswer(val id: String, val text: String)

/** A poll start (MSC3381) as read from its content; [kind] is the raw kind string. */
data class Poll(val question: String, val answers: List<PollAnswer>, val maxSelections: Int, val kind: String) {
    val undisclosed get() = kind.endsWith("poll.undisclosed")
    val multi get() = maxSelections > 1
}

private const val MAX_ANSWERS = 20
private const val DISCLOSED = "org.matrix.msc3381.poll.disclosed"
private const val UNDISCLOSED = "org.matrix.msc3381.poll.undisclosed"

/** The text of an extensible-events text field (message / text, unstable or stable names). */
private fun textOf(o: JsonObject): String? {
    (o["org.matrix.msc1767.message"] ?: o["m.message"])?.let { m ->
        val arr = (m as? JsonArray)?.mapNotNull { it as? JsonObject }
        (arr?.firstOrNull { it.string("mimetype") in listOf(null, "text/plain") } ?: arr?.firstOrNull())?.string("body")?.let { return it }
    }
    return o.string("org.matrix.msc1767.text") ?: o.string("m.text") ?: o.string("body")
}

/** Poll start content (edit-aware, since [c] is the effective content; stable or unstable names); null if malformed. */
fun parsePoll(c: JsonObject): Poll? {
    val p = (c[Types.POLL_START] ?: c["m.poll.start"]) as? JsonObject ?: return null
    val q = textOf(p["question"] as? JsonObject ?: return null) ?: return null
    val answers = (p["answers"] as? JsonArray)?.take(MAX_ANSWERS)?.mapNotNull { a ->
        val o = a as? JsonObject ?: return@mapNotNull null
        val id = o.string("id") ?: return@mapNotNull null
        PollAnswer(id, textOf(o) ?: return@mapNotNull null)
    } ?: return null
    if (answers.isEmpty()) return null
    val max = ((p["max_selections"] as? JsonPrimitive)?.doubleOrNull)?.toInt()?.takeIf { it > 0 } ?: 1
    return Poll(q, answers, max, p.string("kind") ?: DISCLOSED)
}

/** Poll start content as js-sdk's PollStartEvent.from(question, answers, kind, max).serialize().content. */
fun pollStartContent(question: String, answers: List<String>, multi: Boolean, undisclosed: Boolean): JsonObject {
    require(question.isNotBlank() && answers.size in 2..MAX_ANSWERS && answers.all { it.isNotBlank() }) { "poll needs a question and 2-$MAX_ANSWERS answers" }
    val a = answers.map { it.trim() }
    val fallback = question.trim() + "\n" + a.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n")
    return buildJsonObject {
        put("org.matrix.msc1767.text", fallback)
        putJsonObject(Types.POLL_START) {
            putJsonObject("question") { put("org.matrix.msc1767.text", question.trim()) }
            put("kind", if (undisclosed) UNDISCLOSED else DISCLOSED)
            put("max_selections", if (multi) a.size else 1)
            putJsonArray("answers") {
                a.forEach { t -> add(buildJsonObject { put("id", UUID.randomUUID().toString().replace("-", "").take(16)); put("org.matrix.msc1767.text", t) }) }
            }
        }
    }
}

/** What a poll shows now. */
data class PollState(
    val poll: Poll?,
    val tally: PollTally,
    /** number of voters */
    val total: Int,
    /** answers I picked; my vote shows at once from the outbox */
    val mine: List<String>,
    val ended: Boolean,
    /** the author, or anyone allowed to redact, may end it */
    val canEnd: Boolean,
    /** only the author, only before any vote, only while running */
    val canEdit: Boolean,
    val author: String,
) {
    /** results are shown once ended, or after I voted unless the poll hides them until the end */
    val showResults get() = ended || (poll?.undisclosed == false && mine.isNotEmpty())
}

/** Polls (MSC3381, the unstable types every client reads): create, edit, vote, end, and a live tally. */
class Polls(private val client: MatrixClient, private val sender: Sender = Sender(client)) {
    private val me get() = client.userId.full

    /** Sends a new poll (2-20 answers). Returns the outbox transaction id. */
    suspend fun create(roomId: String, question: String, answers: List<String>, multi: Boolean = false, undisclosed: Boolean = false, thread: String? = null, replyTo: ReplyTarget? = null): String =
        sender.sendEvent(roomId, Types.POLL_START, pollStartContent(question, answers, multi, undisclosed), thread, replyTo)

    /** Replaces a poll nobody voted on yet. */
    suspend fun edit(roomId: String, pollEventId: String, question: String, answers: List<String>, multi: Boolean = false, undisclosed: Boolean = false): String {
        val c = pollStartContent(question, answers, multi, undisclosed)
        val edit = JsonObject(c + ("m.new_content" to c) + ("m.relates_to" to buildJsonObject { put("rel_type", "m.replace"); put("event_id", pollEventId) }))
        return sender.sendEvent(roomId, Types.POLL_START, edit)
    }

    /** Votes for [answers] (answer ids); an empty list is a spoiled vote, which is how a multi-choice voter takes their vote back. */
    suspend fun vote(roomId: String, pollEventId: String, answers: List<String>): String =
        sender.sendEvent(roomId, Types.POLL_RESPONSE, buildJsonObject {
            putJsonObject("m.relates_to") { put("rel_type", "m.reference"); put("event_id", pollEventId) }
            putJsonObject(Types.POLL_RESPONSE) { putJsonArray("answers") { answers.forEach { add(JsonPrimitive(it)) } } }
        })

    /** Ends a poll (the author, or anyone allowed to redact its event). */
    suspend fun end(roomId: String, pollEventId: String): String =
        sender.sendEvent(roomId, Types.POLL_END, buildJsonObject {
            putJsonObject("m.relates_to") { put("rel_type", "m.reference"); put("event_id", pollEventId) }
            putJsonObject(Types.POLL_END) {}
            put("org.matrix.msc1767.text", "The poll has ended")
        })

    /** The poll's tally as it changes: latest vote of each voter, unknown answers spoil, votes after the end don't count. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun state(roomId: String, pollEventId: String): Flow<PollState> {
        val room = RoomId(roomId)
        val id = EventId(pollEventId)
        val start = client.room.getTimelineEvent(room, id) { decryptionTimeout = 10.seconds }.filterNotNull()
        val refs = client.related(room, id, RelationType.Reference)
        val outbox = client.room.getOutbox(room).flatten()
        val canRedact = client.user.canRedactEvent(room, id)
        val codec = sender.codec
        return combine(start, refs, outbox, canRedact) { st, rs, ob, redact ->
            val author = st.sender.full
            val poll = (st.content?.getOrNull())?.let { parsePoll(codec.jsonOf(it)) }
            val levelNeeded = client.room.getState<PowerLevelsEventContent>(room).first()?.content?.redact ?: 50L
            suspend fun mayEnd(u: String) = u == author || when (val l = client.user.getPowerLevel(room, UserId(u)).first()) {
                is PowerLevel.Creator -> true
                is PowerLevel.User -> l.level >= levelNeeded
            }
            val endTs = rs.filter { it.content?.getOrNull().let { c -> c is PollEnd || c is PollEndStable } && mayEnd(it.sender.full) }.minOfOrNull { it.originTimestamp }
            val votes = rs.filter { e -> e.content?.getOrNull().let { it is PollResponse || it is PollResponseStable } && (endTs == null || e.originTimestamp <= endTs) }.map { e ->
                val raw = codec.jsonOf(e.content!!.getOrThrow())
                val body = (raw[Types.POLL_RESPONSE] ?: raw["m.poll.response"]) as? JsonObject
                Vote(e.sender.full, e.originTimestamp, body?.get("answers"))
            }
            val tally = tallyPoll(votes, poll?.answers?.map { it.id } ?: emptyList(), poll?.maxSelections ?: 1)
            val pending = ob.filter { o -> o.sendError == null && o.content.let { it is PollResponse || it is PollResponseStable } && o.content.relatesTo?.eventId == id }.maxByOrNull { it.createdAt }
            val mine = pending?.let { o ->
                val raw = codec.jsonOf(o.content)
                ((raw[Types.POLL_RESPONSE] ?: raw["m.poll.response"]) as? JsonObject)?.get("answers").let { a -> (a as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } }
            }?.takeIf { pending.sentAt == null } ?: tally.picks[me] ?: emptyList()
            val ended = endTs != null
            PollState(poll, tally, tally.picks.size, mine, ended, !ended && redact || !ended && author == me, !ended && author == me && tally.picks.isEmpty() && votes.isEmpty(), author)
        }
    }
}
