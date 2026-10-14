package ir.panbeh.client.rooms

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.core.MatrixServerException
import io.ktor.http.HttpStatusCode
import de.connect2x.trixnity.client.room
import de.connect2x.trixnity.client.room.getAllState
import de.connect2x.trixnity.client.store.type
import de.connect2x.trixnity.client.user
import de.connect2x.trixnity.client.user.getAccountData
import de.connect2x.trixnity.client.user.canSendEvent
import de.connect2x.trixnity.clientserverapi.client.getStateEventContent
import de.connect2x.trixnity.clientserverapi.model.room.CreateRoom
import de.connect2x.trixnity.clientserverapi.model.room.DirectoryVisibility
import de.connect2x.trixnity.clientserverapi.model.room.GetPublicRoomsWithFilter
import de.connect2x.trixnity.clientserverapi.model.server.Capability
import de.connect2x.trixnity.clientserverapi.model.user.ProfileField
import de.connect2x.trixnity.core.model.RoomAliasId
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.UserId
import de.connect2x.trixnity.core.model.events.EventType
import de.connect2x.trixnity.core.model.events.InitialStateEvent
import de.connect2x.trixnity.core.model.events.m.DirectEventContent
import de.connect2x.trixnity.core.model.events.m.room.AvatarEventContent
import de.connect2x.trixnity.core.model.events.m.room.CreateEventContent
import de.connect2x.trixnity.core.model.events.m.room.EncryptionEventContent
import de.connect2x.trixnity.core.model.events.m.room.JoinRulesEventContent
import de.connect2x.trixnity.core.model.events.m.room.Membership
import de.connect2x.trixnity.core.model.events.m.room.PowerLevelsEventContent
import de.connect2x.trixnity.core.model.events.m.space.ChildEventContent
import de.connect2x.trixnity.core.model.events.m.space.ParentEventContent
import ir.panbeh.client.errText
import ir.panbeh.client.errcode
import ir.panbeh.client.events.Types
import ir.panbeh.core.admin.supportsRestricted
import ir.panbeh.core.text.aliasLocalpart
import ir.panbeh.core.text.isUserId
import ir.panbeh.core.text.jsTrim
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** A person from the user directory (or a direct profile lookup). */
data class Person(val userId: String, val name: String, val avatar: String? = null)

/** A room of the public directory. */
data class PublicRoom(
    val id: String, val name: String, val alias: String?, val avatar: String?, val members: Long, val topic: String?,
    val isSpace: Boolean, val joined: Boolean,
)

data class PublicRooms(val rooms: List<PublicRoom>, val next: String?)

/** A child of a space from the server's hierarchy. */
data class SpaceChild(
    val id: String, val name: String, val topic: String?, val avatar: String?, val alias: String?, val members: Long,
    val isSpace: Boolean, val suggested: Boolean, val joined: Boolean,
)

/** Options of [Chats.createChat]. */
data class NewChat(
    val space: Boolean = false,
    val name: String,
    val topic: String? = null,
    /** mxc:// of an already uploaded picture */
    val avatar: String? = null,
    val invite: List<String> = emptyList(),
    val encrypted: Boolean = true,
    /** local part of a public #address; a room with one is public and listed */
    val alias: String? = null,
    /** a joined space to put the room in */
    val parent: String? = null,
    /** let members of `parent` join without an invite (needs room version 8+, not for public rooms) */
    val spaceMembersJoin: Boolean = false,
)

/** The outcome of [Chats.createChat]: the room exists; `parentError` is set when linking it to the space failed. */
data class Created(val roomId: String, val parentError: Throwable? = null)

sealed interface JoinResult {
    data class Joined(val roomId: String, val isSpace: Boolean) : JoinResult
    /** the room refuses joiners: offer [Chats.knock] with [Chats.KNOCK_OFFER] */
    data object NeedsKnock : JoinResult
    data class Failed(val message: String) : JoinResult
}

sealed interface KnockResult {
    data object Sent : KnockResult
    data class Refused(val message: String) : KnockResult
}

/** Creating, finding and joining chats, and spaces' structure. */
@OptIn(ExperimentalCoroutinesApi::class)
class Chats(private val client: MatrixClient) {
    private val me = client.userId
    private val api get() = client.api

    companion object {
        const val KNOCK_OFFER = "پیوستن به این گفتگو نیاز به تأیید مدیران دارد. درخواست عضویت فرستاده شود؟"
        const val KNOCK_SENT = "درخواست فرستاده شد. اگر پذیرفته شود، دعوت‌نامه برایتان می‌آید."
        const val KNOCK_REFUSED = "این گفتگو درخواست عضویت نمی‌پذیرد"
        const val NOT_ADDED_TO_SPACE = "ساخته شد، ولی به فضا افزوده نشد"
    }

    // ---------- users ----------

    /** Searches the user directory; a full @user:server is also looked up directly (some servers only list people you share a room with). */
    suspend fun searchUsers(term: String, exclude: Collection<String> = emptyList()): List<Person> {
        val t = jsTrim(term)
        if (t.isEmpty()) return emptyList()
        val dir = api.user.searchUsers(t, "fa", 20).map { r -> r.results.map { Person(it.userId.full, it.displayName ?: it.userId.full, it.avatarUrl) } }.getOrDefault(emptyList())
        val direct = if (isUserId(t)) {
            api.user.getProfile(UserId(t)).fold(
                { p -> Person(t, p[ProfileField.DisplayName]?.value ?: t, p[ProfileField.AvatarUrl]?.value) },
                // unknown user = drop it; other failures (profile lookups disabled…) still let you try the id
                { e -> if (e.errcode == "M_NOT_FOUND" || (e as? MatrixServerException)?.statusCode == HttpStatusCode.NotFound) null else Person(t, t) },
            )
        } else null
        val people = if (direct != null && dir.none { it.userId == direct.userId }) listOf(direct) + dir else dir
        val hide = exclude.toSet() + me.full
        return people.filter { it.userId !in hide }
    }

    // ---------- DMs and new chats ----------

    private fun encryption() = InitialStateEvent(EncryptionEventContent(), "")

    /** The joined DM with the user, if there is one. */
    suspend fun findDM(userId: String): String? {
        val ids = client.user.getAccountData<DirectEventContent>().first()?.mappings?.get(UserId(userId)).orEmpty()
        return ids.firstOrNull { client.room.getById(it).first()?.membership == Membership.JOIN }?.full
    }

    /** An existing joined DM with the user, or a new encrypted one (listed in m.direct). Returns the room id. */
    suspend fun openDM(userId: String): String {
        findDM(userId)?.let { return it }
        val id = api.room.createRoom(
            invite = setOf(UserId(userId)), isDirect = true, preset = CreateRoom.Request.Preset.TRUSTED_PRIVATE,
            initialState = listOf(encryption()),
        ).getOrThrow()
        client.addDirect(userId, id.full)
        return id.full
    }

    /** Server's default room version supports restricted joins (so a space-members rule can be offered). */
    suspend fun canRestrict(): Boolean = api.server.getCapabilities().getOrNull()?.capabilities
        ?.filterIsInstance<Capability.RoomVersions>()?.firstOrNull()?.default.let { supportsRestricted(it) }

    /** Creates a group or a space; groups let members join calls. A parent space is linked after, best effort. */
    suspend fun createChat(o: NewChat): Created {
        val state = mutableListOf<InitialStateEvent<*>>()
        o.avatar?.let { state += InitialStateEvent(AvatarEventContent(url = it), "") }
        if (o.encrypted && !o.space) state += encryption()
        val restrict = o.parent?.takeIf { o.spaceMembersJoin && o.alias.isNullOrBlank() }
        if (restrict != null)
            state += InitialStateEvent(
                JoinRulesEventContent(JoinRulesEventContent.JoinRule.Restricted, setOf(JoinRulesEventContent.AllowCondition(RoomId(restrict), JoinRulesEventContent.AllowCondition.AllowConditionType.RoomMembership))), "",
            )
        val alias = o.alias?.trim()?.takeIf { it.isNotEmpty() }
        val id = api.room.createRoom(
            visibility = if (alias != null) DirectoryVisibility.PUBLIC else DirectoryVisibility.PRIVATE,
            roomAliasId = alias?.let { RoomAliasId(it, me.domain) },
            name = o.name,
            topic = o.topic?.takeIf { it.isNotEmpty() },
            invite = o.invite.map(::UserId).toSet(),
            creationContent = if (o.space) CreateEventContent(type = CreateEventContent.RoomType.Space) else null,
            initialState = state,
            preset = if (alias != null) CreateRoom.Request.Preset.PUBLIC else CreateRoom.Request.Preset.PRIVATE,
        ).getOrThrow()
        if (!o.space) runCatching { allowCalls(id.full) }
        val parentError = o.parent?.let { p -> runCatching { addToSpace(p, id.full) }.exceptionOrNull() }
        return Created(id.full, parentError)
    }

    /** Lets ordinary members join calls: their org.matrix.msc3401.call.member state needs power 0 (as Element's rooms set too). */
    suspend fun allowCalls(roomId: String) {
        val rid = RoomId(roomId)
        val pl = api.room.getStateEventContent<PowerLevelsEventContent>(rid).getOrThrow()
        api.room.sendStateEvent(rid, pl.copy(events = pl.events + (EventType(null, Types.CALL_MEMBER) to 0L))).getOrThrow()
    }

    // ---------- spaces ----------

    /** m.space.child on the space and the room's own m.space.parent (best effort: other clients and restricted joins find the space by it). */
    suspend fun addToSpace(spaceId: String, roomId: String) {
        val via = setOf(me.domain)
        val space = RoomId(spaceId)
        val room = RoomId(roomId)
        api.room.sendStateEvent(space, ChildEventContent(via = via), room.full).getOrThrow()
        val others = runCatching {
            client.room.getAllState<ParentEventContent>(room).first().orEmpty().values.any { f -> f.first()?.let { it.stateKey != space.full && it.content.via.isNotEmpty() } == true }
        }.getOrDefault(false)
        api.room.sendStateEvent(room, ParentEventContent(canonical = !others, via = via), space.full)
    }

    /** Clears the space's child link (and the room's parent link when it points there). */
    suspend fun removeFromSpace(spaceId: String, roomId: String) {
        val space = RoomId(spaceId)
        val room = RoomId(roomId)
        api.room.sendStateEvent(space, ChildEventContent(via = emptySet()), room.full).getOrThrow()
        val linked = runCatching {
            client.room.getAllState<ParentEventContent>(room).first().orEmpty()[space.full]?.first()?.content?.via?.isNotEmpty() == true
        }.getOrDefault(false)
        if (linked) api.room.sendStateEvent(room, ParentEventContent(via = emptySet()), space.full)
    }

    /** The children of a space the server knows (one level), by default only those I haven't joined. */
    suspend fun hierarchy(spaceId: String, onlyUnjoined: Boolean = true): List<SpaceChild> {
        val space = RoomId(spaceId)
        val out = ArrayList<SpaceChild>()
        var from: String? = null
        var suggested = emptySet<String>()
        do {
            val r = api.room.getHierarchy(space, from = from, limit = 50, maxDepth = 1).getOrThrow()
            for (c in r.rooms) {
                if (c.roomId == space) {
                    suggested = c.childrenState.mapNotNull { e -> (e.content as? ChildEventContent)?.takeIf { it.suggested }?.let { e.stateKey } }.toSet()
                    continue
                }
                val joined = client.room.getById(c.roomId).first()?.membership == Membership.JOIN
                if (onlyUnjoined && joined) continue
                out += SpaceChild(
                    c.roomId.full, c.name ?: c.canonicalAlias?.full ?: c.roomId.full, c.topic, c.avatarUrl, c.canonicalAlias?.full,
                    c.joinedMembersCount, c.roomType == CreateEventContent.RoomType.Space, c.roomId.full in suggested, joined,
                )
            }
            from = r.nextBatch
        } while (from != null)
        return out
    }

    /** Joined spaces I may add rooms to (for the "inside space" picker). */
    suspend fun manageableSpaces(list: RoomList): List<SpaceSummary> = list.spaces.filter { s ->
        runCatching { client.user.canSendEvent(RoomId(s.id), ChildEventContent::class).first() }.getOrDefault(false)
    }

    // ---------- joining and the directory ----------

    private suspend fun waitJoined(id: RoomId): Boolean {
        val r = withTimeoutOrNull(15_000) { client.room.getById(id).first { it?.membership == Membership.JOIN } }
        return r?.type == CreateEventContent.RoomType.Space
    }

    /** Joins by `#alias`, `!id` (with `via` servers) or a directory entry. A refusal (M_FORBIDDEN) is [JoinResult.NeedsKnock]. */
    suspend fun join(idOrAlias: String, via: Set<String> = emptySet()): JoinResult {
        val v = via.takeIf { it.isNotEmpty() }
        val r = if (idOrAlias.startsWith("#")) api.room.joinRoom(RoomAliasId(idOrAlias), via = v) else api.room.joinRoom(RoomId(idOrAlias), via = v)
        return r.fold({ id -> JoinResult.Joined(id.full, waitJoined(id)) }, { e ->
            if (e.errcode == "M_FORBIDDEN") JoinResult.NeedsKnock else JoinResult.Failed(errText(e))
        })
    }

    /** Asks to be let in (after [JoinResult.NeedsKnock]). */
    suspend fun knock(idOrAlias: String, via: Set<String> = emptySet()): KnockResult {
        val v = via.takeIf { it.isNotEmpty() }
        val r = if (idOrAlias.startsWith("#")) api.room.knockRoom(RoomAliasId(idOrAlias), via = v) else api.room.knockRoom(RoomId(idOrAlias), via = v)
        return r.fold({ KnockResult.Sent }, { e -> KnockResult.Refused(if (e.errcode == "M_FORBIDDEN") KNOCK_REFUSED else errText(e)) })
    }

    /** Searches the public room directory (empty or `#`/`!` terms list everything). */
    suspend fun publicRooms(term: String = "", since: String? = null, limit: Long = 30): PublicRooms {
        val t = jsTrim(term)
        val filter = t.takeIf { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }?.let { GetPublicRoomsWithFilter.Request.Filter(genericSearchTerm = it) }
        val r = api.room.getPublicRooms(limit = limit, since = since, filter = filter).getOrThrow()
        val rooms = r.chunk.map { c ->
            PublicRoom(
                c.roomId.full, c.name ?: c.canonicalAlias?.full ?: c.roomId.full, c.canonicalAlias?.full, c.avatarUrl, c.joinedMembersCount, c.topic,
                c.roomType == CreateEventContent.RoomType.Space, client.room.getById(c.roomId).first()?.membership == Membership.JOIN,
            )
        }
        return PublicRooms(rooms, r.nextBatch)
    }

    /** Suggested #address localpart from a room name. */
    fun suggestAlias(name: String) = aliasLocalpart(name)
}

/** Adds a room to m.direct for a user (no-op when it is there already). */
internal suspend fun MatrixClient.addDirect(userId: String, roomId: String) {
    val u = UserId(userId)
    val r = RoomId(roomId)
    val cur = user.getAccountData<DirectEventContent>().first()?.mappings.orEmpty()
    if (cur[u]?.contains(r) == true) return
    api.user.setAccountData(DirectEventContent(cur + (u to (cur[u].orEmpty() + r))), userId.let { this.userId }).getOrThrow()
}
