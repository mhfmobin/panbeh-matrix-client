package ir.panbeh.client.account

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.user
import de.connect2x.trixnity.client.user.getAccountData
import de.connect2x.trixnity.clientserverapi.client.getAccountData as serverAccountData
import de.connect2x.trixnity.core.model.UserId
import de.connect2x.trixnity.core.model.events.m.IgnoredUserListEventContent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject

/** Blocked users (m.ignored_user_list: the server stops sending their events and invites). */
class Blocking(private val client: MatrixClient) {
    /** The blocked user ids, in the order they were blocked. */
    val blocked: Flow<List<String>> = client.user.getAccountData<IgnoredUserListEventContent>().map { c -> c?.ignoredUsers?.keys?.map { it.full }.orEmpty() }

    suspend fun isBlocked(userId: String) = userId in blocked.first()

    suspend fun setBlocked(userId: String, blocked: Boolean) {
        val ids = LinkedHashMap<UserId, JsonObject>(client.api.user.serverAccountData<IgnoredUserListEventContent>(client.userId).getOrNull()?.ignoredUsers.orEmpty())
        ids.remove(UserId(userId))
        if (blocked) ids[UserId(userId)] = JsonObject(emptyMap())
        client.api.user.setAccountData(IgnoredUserListEventContent(ids), client.userId).getOrThrow()
    }
}
