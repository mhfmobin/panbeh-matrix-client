package ir.panbeh.client.admin

/** A member in the member list. [level] is Infinity for a room v12 creator. */
data class MemberRow(val userId: String, val name: String, val avatarUrl: String?, val invited: Boolean, val level: Double, val role: String?)

/** What I may do to a member I outrank; [roles] are the levels I may grant (value, label). */
data class MemberOps(
    val membership: String, val level: Double, val mayKick: Boolean, val mayBan: Boolean, val mayRole: Boolean, val roles: List<Pair<Double, String>>,
)

/** Someone asking to join. */
data class KnockRequest(val userId: String, val name: String, val avatarUrl: String?, val reason: String?, val mayDeny: Boolean)

data class BannedMember(val userId: String, val name: String, val avatarUrl: String?, val reason: String?)

/** What I may change in the room (the web app's `can(...)` / canManage). */
data class RoomCan(
    val name: Boolean, val topic: Boolean, val avatar: Boolean, val joinRules: Boolean, val history: Boolean, val encryption: Boolean,
    val canonicalAlias: Boolean, val powerLevels: Boolean, val invite: Boolean, val tombstone: Boolean, val packs: Boolean,
) {
    fun manage(isSpace: Boolean, encrypted: Boolean) = joinRules || canonicalAlias || powerLevels || (!isSpace && (history || (encryption && !encrypted)))
}

/** The space a "space members" join rule points at. */
data class ParentSpace(val roomId: String, val name: String)

/** Everything the group-settings screen needs. [name] is the explicit name ("" when none). */
data class RoomSettings(
    val isSpace: Boolean, val name: String, val topic: String, val avatarUrl: String?,
    val joinRule: String, val joinRules: List<Pair<String, String>>, val parent: ParentSpace?,
    val history: String, val encrypted: Boolean, val canonicalAlias: String?, val can: RoomCan, val canManage: Boolean,
)

/** One row of the permissions screen: a role select for [keys] (levelAt keys that move together). */
data class PermissionRow(val label: String, val keys: List<String>, val level: Double, val locked: Boolean, val options: List<Pair<Double, String>>)

/** Technical details. [upgradeTo] is the server's default version when this room should move to it and I may do that. */
data class RoomDetails(
    val roomId: String, val version: String, val versionUnstable: Boolean, val creators: List<String>, val createdTs: Long?,
    val encryption: String?, val isSpace: Boolean, val predecessor: String?, val upgradeTo: String?,
)
