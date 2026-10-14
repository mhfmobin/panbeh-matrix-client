package ir.panbeh.client.rooms

import ir.panbeh.core.rooms.Folder
import ir.panbeh.core.rooms.ListOrdered
import ir.panbeh.core.rooms.RoomInfo
import ir.panbeh.core.rooms.inFolder as coreInFolder
import ir.panbeh.core.rooms.isUnread as coreIsUnread

/** The newest message-like event of a room, ready to show in the list. */
data class LastMessage(
    val eventId: String,
    val sender: String,
    /** the sender's first name as the list shows it ("" when unknown) */
    val senderName: String,
    val ts: Long,
    /** previewText of the event (no sender prefix) */
    val text: String,
    val mine: Boolean,
    val call: Boolean,
)

/** One chat-list row (joined room or invite; joined spaces are folders, see [SpaceSummary]). */
data class RoomSummary(
    val id: String,
    /** Persian computed name (core roomName) when the room has no explicit name */
    val name: String,
    val avatar: String?,
    val isDM: Boolean,
    /** the other person of a DM */
    val peer: String?,
    override val invite: Boolean,
    val inviter: String?,
    val inviterName: String?,
    val unread: Int,
    /** unread notifications that mention me */
    val highlight: Int,
    /** manually marked as unread */
    val marked: Boolean,
    override val pinned: Boolean,
    val archived: Boolean,
    val muted: Boolean,
    /** when a timed mute ends (ms), if one is set */
    val muteUntil: Long?,
    val encrypted: Boolean,
    val last: LastMessage?,
    /** "شما: …" / "نام: …" / text, "دعوت‌نامه" for invites; typing is the UI's business */
    val preview: String,
    override val ts: Long,
    /** top-level joined spaces that contain this room (directly or in sub-spaces) */
    val spaces: List<String>,
    val memberCount: Long?,
) : ListOrdered {
    /** what the core folder logic needs */
    val info = RoomInfo(id, isDM, unread, spaces, marked && !invite, archived, muted)
    val isUnread get() = !invite && coreIsUnread(info)
}

/** A joined space (shown as a folder when top-level). */
data class SpaceSummary(
    val id: String,
    val name: String,
    val avatar: String?,
    val memberCount: Long?,
    /** direct children (joined or not) */
    val children: Set<String>,
    val topLevel: Boolean,
)

/** Everything the chat list shows, in one consistent snapshot. */
data class RoomList(
    val rows: List<RoomSummary> = emptyList(),
    /** all joined spaces; those with `topLevel` are folders */
    val spaces: List<SpaceSummary> = emptyList(),
    /** false until the first snapshot is built */
    val ready: Boolean = false,
) {
    val folderSpaces get() = spaces.filter { it.topLevel }
    /** rows (without invites' bogus unread) of a folder id: "all", "unread", "dms", "groups", "archive" or a space id */
    fun inFolder(folder: String) = rows.filter { coreInFolder(it.info, folder) }
    /** chats counted by a folder's tab badge (marked or with unread, as core isUnread) */
    fun unreadIn(folder: String) = rows.count { it.isUnread && coreInFolder(it.info, folder) }
    /** unmuted unread chats: the app badge / title */
    val unreadChats get() = rows.count { it.isUnread && !it.muted }
    val archived get() = rows.filter { coreInFolder(it.info, "archive") }
}

/** A tab of the chat list with its unread badge. */
data class FolderTab(val folder: Folder, val unread: Int, val space: SpaceSummary? = null)
