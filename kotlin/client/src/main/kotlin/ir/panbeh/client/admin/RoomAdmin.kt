package ir.panbeh.client.admin

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.flattenValues
import de.connect2x.trixnity.client.room
import de.connect2x.trixnity.client.room.getAllState
import de.connect2x.trixnity.client.room.getState
import de.connect2x.trixnity.client.user
import de.connect2x.trixnity.client.user.PowerLevel
import de.connect2x.trixnity.clientserverapi.model.room.DirectoryVisibility
import de.connect2x.trixnity.clientserverapi.model.server.Capability
import de.connect2x.trixnity.core.model.RoomAliasId
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.UserId
import de.connect2x.trixnity.core.model.events.EventType
import de.connect2x.trixnity.core.model.events.StateEventContent
import de.connect2x.trixnity.core.model.events.UnknownEventContent
import de.connect2x.trixnity.core.model.events.block.EventContentBlocks
import de.connect2x.trixnity.core.model.events.m.room.AvatarEventContent
import de.connect2x.trixnity.core.model.events.m.room.CanonicalAliasEventContent
import de.connect2x.trixnity.core.model.events.m.room.CreateEventContent
import de.connect2x.trixnity.core.model.events.m.room.EncryptionEventContent
import de.connect2x.trixnity.core.model.events.m.room.HistoryVisibilityEventContent
import de.connect2x.trixnity.core.model.events.m.room.HistoryVisibilityEventContent.HistoryVisibility
import de.connect2x.trixnity.core.model.events.m.room.JoinRulesEventContent
import de.connect2x.trixnity.core.model.events.m.room.JoinRulesEventContent.AllowCondition
import de.connect2x.trixnity.core.model.events.m.room.JoinRulesEventContent.JoinRule
import de.connect2x.trixnity.core.model.events.m.room.Membership
import de.connect2x.trixnity.core.model.events.m.room.NameEventContent
import de.connect2x.trixnity.core.model.events.m.room.PowerLevelsEventContent
import de.connect2x.trixnity.core.model.events.m.room.TombstoneEventContent
import de.connect2x.trixnity.core.model.events.m.room.TopicEventContent
import de.connect2x.trixnity.core.model.events.m.space.ChildEventContent
import de.connect2x.trixnity.core.model.events.m.space.ParentEventContent
import ir.panbeh.client.account.uploadPlain
import ir.panbeh.core.admin.HISTORY
import ir.panbeh.core.admin.JOIN_RULES
import ir.panbeh.core.admin.PowerLevels
import ir.panbeh.core.admin.RoomVersions
import ir.panbeh.core.admin.levelAt
import ir.panbeh.core.admin.levelOptions
import ir.panbeh.core.admin.roleLabel
import ir.panbeh.core.admin.supportsKnock
import ir.panbeh.core.admin.supportsKnockRestricted
import ir.panbeh.core.admin.supportsRestricted
import ir.panbeh.core.admin.upgradeTarget
import ir.panbeh.core.text.normalize
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.serialization.json.JsonObject

/** Role presets the member menu offers (level, label). */
val ROLES: List<Pair<Double, String>> = listOf(0.0 to "عضو", 50.0 to "ناظر", 100.0 to "مدیر")
/** Who-may presets of the permissions screen. */
val WHO: List<Pair<Double, String>> = listOf(0.0 to "همه‌ی اعضا", 50.0 to "ناظران و مدیران", 100.0 to "فقط مدیران")

private val PERMS: List<Pair<String, List<String>>> = listOf(
    "ارسال پیام" to listOf("events_default"),
    "دعوت اعضا" to listOf("invite"),
    "بیرون کردن" to listOf("kick"),
    "مسدود کردن" to listOf("ban"),
    "حذف پیام دیگران" to listOf("redact"),
    "تغییر نام، عکس و توضیحات" to listOf("events.m.room.name", "events.m.room.avatar", "events.m.room.topic"),
    "سنجاق پیام" to listOf("events.m.room.pinned_events"),
    "نام بردن از همه (‎@room)" to listOf("notifications.room"),
    "تماس گروهی" to listOf("events.org.matrix.msc3401.call.member"),
)
private val SPACE_PERMS: List<Pair<String, List<String>>> = listOf(
    "افزودن گفتگو به فضا" to listOf("events.m.space.child"),
    "دعوت اعضا" to listOf("invite"),
    "بیرون کردن" to listOf("kick"),
    "مسدود کردن" to listOf("ban"),
    "تغییر نام، عکس و توضیحات" to listOf("events.m.room.name", "events.m.room.avatar", "events.m.room.topic"),
)

internal fun PowerLevelsEventContent?.core() = PowerLevels(
    users = this?.users?.entries?.associate { it.key.full to it.value.toDouble() },
    usersDefault = this?.usersDefault?.toDouble(),
    events = this?.events?.entries?.associate { it.key.name to it.value.toDouble() },
    eventsDefault = this?.eventsDefault?.toDouble(), stateDefault = this?.stateDefault?.toDouble(),
    invite = this?.invite?.toDouble(), kick = this?.kick?.toDouble(), ban = this?.ban?.toDouble(), redact = this?.redact?.toDouble(),
    notifications = this?.notifications?.mapValues { it.value.toDouble() },
)

/** core's withLevel on the typed content. */
internal fun PowerLevelsEventContent.withKey(key: String, level: Long): PowerLevelsEventContent = when {
    key.startsWith("events.") -> key.substring(7).let { n -> copy(events = events.filterKeys { it.name != n } + (EventType(null, n) to level)) }
    key.startsWith("notifications.") -> copy(notifications = (notifications ?: mapOf("room" to 50L)) + (key.substring(14) to level))
    else -> when (key) {
        "events_default" -> copy(eventsDefault = level, events = events.filterKeys { it.name != "m.room.message" })
        "invite" -> copy(invite = level)
        "kick" -> copy(kick = level)
        "ban" -> copy(ban = level)
        "redact" -> copy(redact = level)
        "state_default" -> copy(stateDefault = level)
        "users_default" -> copy(usersDefault = level)
        else -> this
    }
}

private fun PowerLevel.toDouble() = if (this is PowerLevel.User) level.toDouble() else Double.POSITIVE_INFINITY

private fun joinRuleOf(name: String): JoinRule = when (name) {
    "public" -> JoinRule.Public; "invite" -> JoinRule.Invite; "knock" -> JoinRule.Knock
    "restricted" -> JoinRule.Restricted; "knock_restricted" -> JoinRule.KnockRestricted; "private" -> JoinRule.Private
    else -> JoinRule.Unknown(name)
}

/** A state event with arbitrary JSON content (Trixnity's typed classes can't express `{}`, e.g. to remove an m.space.child). */
internal fun rawState(type: String, json: JsonObject = JsonObject(emptyMap())): StateEventContent = UnknownEventContent(json, EventContentBlocks(), type)

/** Everything a room admin does in one room: members and roles, permissions, join rules, address, details and upgrade. */
class RoomAdmin(private val client: MatrixClient, roomId: String) {
    private val room = RoomId(roomId)
    private val me = client.userId
    private val api = client.api.room

    private inline fun <reified C : StateEventContent> state(key: String = ""): Flow<C?> = client.room.getState<C>(room, key).map { it?.content }
    private suspend inline fun <reified C : StateEventContent> now(key: String = ""): C? = state<C>(key).first()

    private val pl: Flow<PowerLevelsEventContent?> = state()
    private val myLevel: Flow<Double> = client.user.getPowerLevel(room, me).map { it.toDouble() }
    private val myMembership: Flow<Membership?> = client.room.getById(room).map { it?.membership }

    /** Fires whenever something the settings screens show changes. */
    private fun changes(): Flow<Unit> = merge(
        pl.map {}, state<JoinRulesEventContent>().map {}, state<HistoryVisibilityEventContent>().map {}, state<EncryptionEventContent>().map {},
        state<NameEventContent>().map {}, state<TopicEventContent>().map {}, state<AvatarEventContent>().map {},
        state<CanonicalAliasEventContent>().map {}, state<TombstoneEventContent>().map {}, myMembership.map {},
    )

    private suspend fun isSpace() = now<CreateEventContent>()?.type == CreateEventContent.RoomType.Space

    // ---------- members ----------

    /** Loads the full member list (members are lazy-loaded); call before [members] when opening the list. */
    suspend fun loadMembers() = client.user.loadMembers(room)

    private fun levelOf(userId: String) = client.user.getPowerLevel(room, UserId(userId)).map { it.toDouble() }

    /** Joined and invited members, invited last, then by level and name; [search] filters by name or id like the web app. */
    fun members(search: String = ""): Flow<List<MemberRow>> =
        combine(client.user.getAll(room).flattenValues(), pl, state<CreateEventContent>()) { users, pl, _ ->
            val q = normalize(search.trim())
            users.mapNotNull { u ->
                val m = u.event.content.membership
                if (m != Membership.JOIN && m != Membership.INVITE) return@mapNotNull null
                if (q.isNotEmpty() && q !in normalize(u.name + " " + u.userId.full)) return@mapNotNull null
                val lvl = client.user.getPowerLevel(u.userId, createEvent(), pl).toDouble()
                MemberRow(u.userId.full, u.name, u.event.content.avatarUrl, m == Membership.INVITE, lvl, roleLabel(lvl))
            }.sortedWith(compareBy<MemberRow> { it.invited }.thenByDescending { it.level }.thenBy { it.name })
        }

    private suspend fun createEvent() = client.room.getState<CreateEventContent>(room).first() ?: error("no create event")

    /** What I may do to [userId] (null: nothing, it's me, or they're my rank or above). */
    fun memberOps(userId: String): Flow<MemberOps?> =
        combine(pl, myLevel, levelOf(userId), client.user.getById(room, UserId(userId)).map { it?.event?.content?.membership }, myMembership) { pl, mine, theirs, membership, joined ->
            if (userId == me.full || joined != Membership.JOIN || theirs >= mine) return@combine null
            val c = pl.core()
            val mayKick = mine >= levelAt(c, "kick") && (membership == Membership.JOIN || membership == Membership.INVITE)
            val mayBan = mine >= levelAt(c, "ban")
            val mayRole = membership != Membership.BAN && mine >= levelAt(c, "events.m.room.power_levels")
            if (!mayKick && !mayBan && !mayRole) null
            else MemberOps(membership?.name?.lowercase() ?: "leave", theirs, mayKick, mayBan, mayRole, levelOptions(ROLES, mine, theirs))
        }.distinctUntilChanged()

    /** Sets a member's level. The "this makes them as high as me" confirmation is the UI's job. */
    suspend fun setMemberLevel(userId: String, level: Double) {
        val cur = now<PowerLevelsEventContent>() ?: PowerLevelsEventContent()
        api.sendStateEvent(room, cur.copy(users = cur.users + (UserId(userId) to level.toLong()))).getOrThrow()
    }

    suspend fun kick(userId: String, reason: String? = null) = api.kickUser(room, UserId(userId), reason?.ifEmpty { null }).getOrThrow()
    suspend fun ban(userId: String, reason: String? = null) = api.banUser(room, UserId(userId), reason?.ifEmpty { null }).getOrThrow()
    suspend fun unban(userId: String) = api.unbanUser(room, UserId(userId)).getOrThrow()

    /** Withdraws an invitation (a kick of an invited member). */
    suspend fun cancelInvite(userId: String) = kick(userId)

    suspend fun invite(userId: String) = api.inviteUser(room, UserId(userId)).getOrThrow()

    /** Pending knocks, shown only to someone who may invite. */
    fun knocks(): Flow<List<KnockRequest>> = combine(client.user.getAll(room).flattenValues(), pl, myLevel, myMembership) { users, pl, mine, joined ->
        val c = pl.core()
        if (joined != Membership.JOIN || mine < levelAt(c, "invite")) return@combine emptyList()
        val mayDeny = mine >= levelAt(c, "kick")
        users.filter { it.event.content.membership == Membership.KNOCK }.map { KnockRequest(it.userId.full, it.name, it.event.content.avatarUrl, it.event.content.reason, mayDeny) }
    }

    suspend fun acceptKnock(userId: String) = invite(userId)
    suspend fun denyKnock(userId: String) = kick(userId)

    fun banned(): Flow<List<BannedMember>> = client.user.getAll(room).flattenValues().map { users ->
        users.filter { it.event.content.membership == Membership.BAN }.map { BannedMember(it.userId.full, it.name, it.event.content.avatarUrl, it.event.content.reason) }
    }

    // ---------- permissions ----------

    /** The permissions screen's rows (spaces get their own shorter list). */
    fun permissions(): Flow<List<PermissionRow>> = combine(pl, myLevel, state<CreateEventContent>()) { pl, mine, create ->
        val c = pl.core()
        (if (create?.type == CreateEventContent.RoomType.Space) SPACE_PERMS else PERMS).map { (label, keys) ->
            val cur = levelAt(c, keys[0])
            PermissionRow(label, keys, cur, keys.any { levelAt(c, it) > mine }, levelOptions(WHO, mine, cur))
        }
    }

    /** Moves all [keys] of a permission row to [level]. */
    suspend fun setPermission(keys: List<String>, level: Double) {
        val cur = now<PowerLevelsEventContent>() ?: PowerLevelsEventContent()
        api.sendStateEvent(room, keys.fold(cur) { c, k -> c.withKey(k, level.toLong()) }).getOrThrow()
    }

    // ---------- settings ----------

    private suspend fun parentSpace(allowed: String?): ParentSpace? {
        val spaces = client.room.getAll().first().values.mapNotNull { it.first() }
            .filter { it.createEventContent?.type == CreateEventContent.RoomType.Space && it.membership == Membership.JOIN && it.roomId != room }
        val s = spaces.firstOrNull { it.roomId.full == allowed } ?: spaces.firstOrNull { room.full in spaceChildren(it.roomId) } ?: return null
        return ParentSpace(s.roomId.full, s.name?.explicitName ?: s.roomId.full)
    }

    private suspend fun spaceChildren(space: RoomId): Set<String> =
        client.room.getAllState<ChildEventContent>(space).first().orEmpty().filterValues { it.first()?.content != null }.keys

    /** The settings screen's state; recomputed on any relevant change. */
    val settings: Flow<RoomSettings> = changes().conflate().map { snapshot() }.distinctUntilChanged()

    private suspend fun snapshot(): RoomSettings {
        val create = now<CreateEventContent>()
        val space = create?.type == CreateEventContent.RoomType.Space
        val c = now<PowerLevelsEventContent>().core()
        val mine = client.user.getPowerLevel(room, me).first().toDouble()
        val joined = client.room.getById(room).first()?.membership == Membership.JOIN
        fun may(type: String) = mine >= levelAt(c, "events.$type")
        val encrypted = now<EncryptionEventContent>() != null
        val jr = now<JoinRulesEventContent>()
        val rule = jr?.joinRule?.name ?: "invite"
        val v = create?.roomVersion
        val parent = parentSpace(jr?.allow?.firstOrNull { it.type == AllowCondition.AllowConditionType.RoomMembership }?.roomId?.full)
        val rules = buildList {
            add("public"); add("invite")
            if (supportsKnock(v)) add("knock")
            if (parent != null && supportsRestricted(v)) add("restricted")
            if (parent != null && supportsKnockRestricted(v)) add("knock_restricted")
            if (rule !in this) add(rule)
        }
        val labels = rules.map { r ->
            r to if (parent == null || !r.endsWith("restricted")) JOIN_RULES[r] ?: r
            else if (r == "restricted") "اعضای فضای «${parent.name}»" else "اعضای فضای «${parent.name}»، یا با درخواست عضویت"
        }
        val can = RoomCan(
            name = may("m.room.name"), topic = may("m.room.topic"), avatar = may("m.room.avatar"), joinRules = may("m.room.join_rules"),
            history = may("m.room.history_visibility"), encryption = may("m.room.encryption"), canonicalAlias = may("m.room.canonical_alias"),
            powerLevels = may("m.room.power_levels"), invite = joined && mine >= levelAt(c, "invite"), tombstone = may("m.room.tombstone"),
            packs = may("im.ponies.room_emotes"),
        )
        val topic = now<TopicEventContent>()
        return RoomSettings(
            isSpace = space, name = now<NameEventContent>()?.name.orEmpty(), topic = topic?.topic?.text?.plain ?: topic?.legacy?.topic.orEmpty(),
            avatarUrl = now<AvatarEventContent>()?.url?.takeIf { it.isNotEmpty() }, joinRule = rule, joinRules = labels, parent = parent,
            history = (now<HistoryVisibilityEventContent>()?.historyVisibility ?: HistoryVisibility.SHARED).name.lowercase(),
            encrypted = encrypted, canonicalAlias = now<CanonicalAliasEventContent>()?.alias?.full, can = can, canManage = can.manage(space, encrypted),
        )
    }

    /** Sets the join rule; the restricted ones allow members of the room's parent space. */
    suspend fun setJoinRule(rule: String) {
        val allow = if (rule.endsWith("restricted")) snapshot().parent?.let { setOf(AllowCondition(RoomId(it.roomId), AllowCondition.AllowConditionType.RoomMembership)) } else null
        api.sendStateEvent(room, JoinRulesEventContent(joinRuleOf(rule), allow)).getOrThrow()
    }

    suspend fun setHistory(visibility: String) {
        require(visibility in HISTORY)
        api.sendStateEvent(room, HistoryVisibilityEventContent(HistoryVisibility.valueOf(visibility.uppercase()))).getOrThrow()
    }

    /** Turns on end-to-end encryption; it can't be turned off again. */
    suspend fun enableEncryption() = api.sendStateEvent(room, EncryptionEventContent()).getOrThrow()

    /** "" removes the name. */
    suspend fun setName(name: String) = api.sendStateEvent(room, NameEventContent(name.trim())).getOrThrow()
    suspend fun setTopic(topic: String) = api.sendStateEvent(room, TopicEventContent(topic.trim())).getOrThrow()

    /** Uploads already-scaled bytes as the room photo. */
    suspend fun setAvatar(bytes: ByteArray, contentType: String) {
        api.sendStateEvent(room, AvatarEventContent(url = client.uploadPlain(bytes, contentType))).getOrThrow()
    }
    suspend fun removeAvatar() = api.sendStateEvent(room, AvatarEventContent()).getOrThrow()

    // ---------- address ----------

    /** Registers `#localpart:server` and makes it the canonical alias. Throws M_ROOM_IN_USE etc. */
    suspend fun createAlias(localpart: String) {
        val alias = RoomAliasId("#$localpart:${me.domain}")
        api.setRoomAlias(room, alias).getOrThrow()
        setCanonical(alias)
    }

    /** Removes the canonical alias and tries to delete it on the server (it may belong to someone else). */
    suspend fun deleteAlias() {
        val alias = now<CanonicalAliasEventContent>()?.alias
        setCanonical(null)
        if (alias != null) api.deleteRoomAlias(alias)
    }

    private suspend fun setCanonical(alias: RoomAliasId?) {
        val cur = now<CanonicalAliasEventContent>() ?: CanonicalAliasEventContent()
        api.sendStateEvent(room, cur.copy(alias = alias)).getOrThrow()
    }

    /** Whether the room is in the server's public directory; null when the server won't say. */
    suspend fun isListed(): Boolean? = api.getDirectoryVisibility(room).getOrNull()?.let { it == DirectoryVisibility.PUBLIC }

    suspend fun setListed(listed: Boolean) = api.setDirectoryVisibility(room, if (listed) DirectoryVisibility.PUBLIC else DirectoryVisibility.PRIVATE).getOrThrow()

    // ---------- details and upgrade ----------

    private suspend fun roomVersions(): RoomVersions? =
        client.api.server.getCapabilities().getOrNull()?.capabilities?.filterIsInstance<Capability.RoomVersions>()?.firstOrNull()?.let { rv ->
            RoomVersions(rv.default, rv.available.mapValues { it.value.name.lowercase() })
        }

    suspend fun details(): RoomDetails {
        val create = client.room.getState<CreateEventContent>(room).first()
        val cc = create?.content
        val v = cc?.roomVersion ?: "1"
        val rv = roomVersions()
        val space = cc?.type == CreateEventContent.RoomType.Space
        val c = now<PowerLevelsEventContent>().core()
        val mine = client.user.getPowerLevel(room, me).first().toDouble()
        val old = cc?.predecessor?.roomId?.takeIf { client.room.getById(it).first()?.membership == Membership.JOIN }
        val target = if (!space && now<TombstoneEventContent>() == null && mine >= levelAt(c, "events.m.room.tombstone")) upgradeTarget(v, rv) else null
        return RoomDetails(
            roomId = room.full, version = v, versionUnstable = rv?.available?.get(v) == "unstable",
            creators = listOfNotNull(create?.sender?.full) + cc?.additionalCreators.orEmpty().map { it.full },
            createdTs = create?.originTimestamp, encryption = now<EncryptionEventContent>()?.algorithm?.name, isSpace = space,
            predecessor = old?.full, upgradeTo = target,
        )
    }

    /**
     * Upgrades the room to the server's default version and returns the new room id: re-invites the joined members of a
     * non-public room (best effort, one by one) and swaps the room in the spaces I manage.
     */
    suspend fun upgrade(): String {
        val d = details()
        val target = d.upgradeTo ?: throw IllegalStateException("نسخه‌ی این گفتگو نیازی به ارتقا ندارد")
        val public = (now<JoinRulesEventContent>()?.joinRule) == JoinRule.Public
        val members = client.user.getAll(room).flattenValues().first().filter { it.event.content.membership == Membership.JOIN && it.userId != me }
        val to = api.upgradeRoom(room, target).getOrThrow()
        if (!public) for (m in members) runCatching { client.api.room.inviteUser(to, m.userId).getOrThrow() }
        val via = setOf(me.domain)
        for (s in client.room.getAll().first().values.mapNotNull { it.first() }) {
            if (s.createEventContent?.type != CreateEventContent.RoomType.Space || s.membership != Membership.JOIN || s.roomId == room) continue
            if (room.full !in spaceChildren(s.roomId)) continue
            val pls = client.room.getState<PowerLevelsEventContent>(s.roomId).first()?.content
            if (client.user.getPowerLevel(s.roomId, me).first().toDouble() < levelAt(pls.core(), "events.m.space.child")) continue
            runCatching {
                client.api.room.sendStateEvent(s.roomId, ChildEventContent(via = via), to.full).getOrThrow()
                runCatching { client.api.room.sendStateEvent(to, ParentEventContent(canonical = true, via = via), s.roomId.full).getOrThrow() }
                client.api.room.sendStateEvent(s.roomId, rawState("m.space.child"), room.full).getOrThrow()
            }
        }
        return to.full
    }
}
