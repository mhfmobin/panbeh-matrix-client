package ir.panbeh.core.rooms

import ir.panbeh.core.time.num

/** What the chat list knows about a room. marked = "mark as unread". */
data class RoomInfo(
    val id: String, val isDM: Boolean, val unread: Int, val spaces: List<String>,
    val marked: Boolean = false, val archived: Boolean = false, val muted: Boolean = false,
)

/** A tab of the chat list. */
data class Folder(val id: String, val label: String)

val BASE_FOLDERS = listOf(
    Folder("all", "همه"),
    Folder("unread", "خوانده‌نشده"),
    Folder("dms", "شخصی"),
    Folder("groups", "گروه‌ها"),
)

/** Folders in the user's saved order. "all" always leads; folders the order doesn't know (a newly joined space)
 *  follow in their default order, and ids that no longer exist are ignored. */
fun applyFolderOrder(folders: List<Folder>, order: List<String>): List<Folder> {
    val rank = HashMap<String, Int>().also { m -> order.forEachIndexed { i, id -> m[id] = i } }
    val rest = folders.filter { it.id != "all" }
    val known = rest.filter { it.id in rank }.sortedBy { rank[it.id]!! }
    return folders.filter { it.id == "all" } + known + rest.filter { it.id !in rank }
}

/** `ids` with the one at `from` moved to `to`. The first (the "all" tab) neither moves nor gets displaced. */
fun moveFolder(ids: List<String>, from: Int, to: Int): List<String> {
    if (from < 1 || to < 1 || from >= ids.size || to >= ids.size || from == to) return ids
    val next = ids.toMutableList()
    next.add(to, next.removeAt(from))
    return next
}

fun isUnread(r: RoomInfo) = r.unread > 0 || r.marked

/** An unmuted archived chat with new messages shows in the main list until it's read again. */
fun inArchive(r: RoomInfo) = r.archived && !(r.unread > 0 && !r.muted)

/** Archived chats live in the "archive" pseudo-folder and nowhere else. */
fun inFolder(r: RoomInfo, folder: String): Boolean {
    if ((folder == "archive") != inArchive(r)) return false
    return when (folder) {
        "all", "archive" -> true
        "unread" -> isUnread(r)
        "dms" -> r.isDM
        "groups" -> !r.isDM
        else -> folder in r.spaces // space id
    }
}

/** All rooms reachable from a space, following sub-spaces (cycle-safe). */
fun spaceRooms(spaceId: String, children: (String) -> List<String>): Set<String> {
    val seen = LinkedHashSet<String>()
    fun walk(id: String) { for (c in children(id)) if (seen.add(c)) walk(c) }
    walk(spaceId)
    return seen
}

/** The SDK's RoomNameState, structurally (count includes us). */
sealed interface NameState {
    /** The room has an actual name. */
    data class Named(val name: String) : NameState
    /** Named after its members; `inviting` = the SDK's "Inviting" subtype. */
    data class Names(val names: List<String>, val count: Int, val inviting: Boolean = false) : NameState
    /** No name and no members known. */
    data class Empty(val oldName: String? = null) : NameState
}

/** Farsi room names for the SDK's roomNameGenerator; null keeps an actual name. `inviter` names an invite with no members known. */
fun roomName(s: NameState, inviter: String? = null): String? {
    if (s is NameState.Named) return null
    if (s is NameState.Names && s.names.isNotEmpty()) {
        val a = s.names[0]
        val others = s.count - 1
        val names = if (s.names.size == 1 && others <= 1) a
        else if (s.names.size == 2 && others <= 2) "$a و ${s.names[1]}"
        else "$a و ${num(others - 1)} نفر دیگر"
        return if (s.inviting) "در حال دعوت از $names" else names
    }
    val old = (s as? NameState.Empty)?.oldName
    return inviter ?: if (!old.isNullOrEmpty()) "گفتگوی خالی (قبلاً $old)" else "گفتگوی خالی"
}

/** A chat-list entry as the sort sees it. */
interface ListOrdered {
    val invite: Boolean
    val pinned: Boolean
    val ts: Long
}

/** Chat list order: invites, then pinned, then newest. */
val byListOrder = Comparator<ListOrdered> { a, b ->
    compareValues(b.invite, a.invite).takeIf { it != 0 } ?: compareValues(b.pinned, a.pinned).takeIf { it != 0 } ?: b.ts.compareTo(a.ts)
}
