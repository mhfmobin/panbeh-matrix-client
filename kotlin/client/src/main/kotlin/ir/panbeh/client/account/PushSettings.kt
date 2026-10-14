package ir.panbeh.client.account

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.user
import de.connect2x.trixnity.client.user.getAccountData
import de.connect2x.trixnity.clientserverapi.model.push.SetPushRule
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.events.m.PushRulesEventContent
import de.connect2x.trixnity.core.model.push.PushAction
import de.connect2x.trixnity.core.model.push.PushRuleKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** A keyword that notifies like a mention. */
data class Keyword(val ruleId: String, val pattern: String)

private const val AT_ROOM = ".m.rule.roomnotif"
private const val IS_ROOM_MENTION = ".m.rule.is_room_mention"
private val KEYWORD_ACTIONS = setOf(PushAction.Notify, PushAction.SetSoundTweak("default"), PushAction.SetHighlightTweak())

/** Muted = a room or override rule for this room that doesn't notify (ours, or Element's). */
fun isMuted(rules: PushRulesEventContent?, roomId: String): Boolean {
    val g = rules?.global ?: return false
    fun mutes(enabled: Boolean, actions: Set<PushAction>) = enabled && PushAction.Notify !in actions
    return g.room.orEmpty().any { it.ruleId == roomId && mutes(it.enabled, it.actions) } ||
        g.override.orEmpty().any { it.ruleId == roomId && mutes(it.enabled, it.actions) }
}

/** Server push rules shared with the user's other apps: @room mentions, keywords, muted rooms. */
class PushSettings(private val client: MatrixClient) {
    private val rules: Flow<PushRulesEventContent?> = client.user.getAccountData<PushRulesEventContent>()

    /** Whether @room notifies me; null when the server has neither rule (the switch is then hidden). */
    val atRoom: Flow<Boolean?> = rules.map { r ->
        val l = r?.global?.override.orEmpty().filter { it.ruleId == AT_ROOM || it.ruleId == IS_ROOM_MENTION }
        if (l.isEmpty()) null else l.any { it.enabled }
    }

    suspend fun setAtRoom(on: Boolean) {
        val ids = (client.user.getAccountData<PushRulesEventContent>().first()?.global?.override.orEmpty()).map { it.ruleId }.filter { it == AT_ROOM || it == IS_ROOM_MENTION }
        for (id in ids) client.api.push.setPushRuleEnabled("global", PushRuleKind.OVERRIDE, id, on).getOrThrow()
    }

    /** The user's own keyword rules (not the built-in `.m.` ones). */
    val keywords: Flow<List<Keyword>> = rules.map { r ->
        r?.global?.content.orEmpty().filter { !it.ruleId.startsWith(".") && it.pattern.isNotEmpty() }.map { Keyword(it.ruleId, it.pattern) }
    }

    suspend fun addKeyword(word: String) {
        val w = word.trim()
        if (w.isEmpty()) return
        client.api.push.setPushRule("global", PushRuleKind.CONTENT, w, SetPushRule.Request(KEYWORD_ACTIONS, pattern = w)).getOrThrow()
    }

    suspend fun removeKeyword(ruleId: String) = client.api.push.deletePushRule("global", PushRuleKind.CONTENT, ruleId).getOrThrow()

    /** Whether [roomId] is muted, live. */
    fun isMuted(roomId: RoomId): Flow<Boolean> = rules.map { isMuted(it, roomId.full) }
}
