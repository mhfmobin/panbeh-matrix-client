package ir.panbeh.core.rooms

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** A poll response event; `answers` is the raw content value (should be an array of answer ids). */
data class Vote(val sender: String, val ts: Long, val answers: JsonElement?)

/** voters: answer id → senders; picks: sender → the answers they chose. */
data class PollTally(val voters: Map<String, List<String>>, val picks: Map<String, List<String>>)

/** Poll results from response events: each voter's latest vote counts. Per MSC3381 a vote with an unknown
 *  or no answer is spoiled (and still replaces that voter's earlier vote); answers past `max` are dropped. */
fun tallyPoll(votes: List<Vote>, answerIds: List<String>, max: Int): PollTally {
    val latest = LinkedHashMap<String, Vote>()
    for (v in votes) if (!((latest[v.sender]?.ts ?: Long.MIN_VALUE) > v.ts)) latest[v.sender] = v
    val voters = LinkedHashMap<String, MutableList<String>>().also { m -> answerIds.forEach { m[it] = mutableListOf() } }
    val picks = LinkedHashMap<String, List<String>>()
    for ((sender, v) in latest) {
        val a = ((v.answers as? JsonArray)?.take(maxOf(1, max)) ?: emptyList()).distinct()
        val ids = a.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        if (ids.isEmpty() || ids.any { it == null || it !in voters }) continue
        picks[sender] = ids.filterNotNull()
        ids.forEach { voters[it!!]!!.add(sender) }
    }
    return PollTally(voters, picks)
}
