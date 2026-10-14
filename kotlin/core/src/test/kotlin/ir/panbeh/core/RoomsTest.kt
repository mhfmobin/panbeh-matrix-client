package ir.panbeh.core

import ir.panbeh.core.rooms.*
import kotlinx.serialization.json.*
import kotlin.test.*

class RoomsTest {
    @Test fun folders() {
        val dm = RoomInfo("1", isDM = true, unread = 0, spaces = emptyList())
        val grp = RoomInfo("2", isDM = false, unread = 3, spaces = listOf("!s"))
        assertEquals(
            listOf(true to true, false to true, true to false, false to true, false to true),
            listOf("all", "unread", "dms", "groups", "!s").map { inFolder(dm, it) to inFolder(grp, it) },
        )
    }

    @Test fun `spaceRooms follows sub-spaces and survives cycles`() {
        val tree = mapOf("s" to listOf("a", "sub"), "sub" to listOf("b", "s"), "a" to emptyList(), "b" to emptyList())
        assertEquals(listOf("a", "b", "s", "sub"), spaceRooms("s") { tree[it] ?: emptyList() }.sorted())
    }

    @Test fun `Farsi room names, invite falls back to the inviter`() {
        assertNull(roomName(NameState.Named("Team")))
        assertEquals("علی", roomName(NameState.Names(listOf("علی"), 2)))
        assertEquals("علی و سارا", roomName(NameState.Names(listOf("علی", "سارا"), 3)))
        assertEquals("علی و ۴ نفر دیگر", roomName(NameState.Names(listOf("علی", "سارا"), 6)))
        assertEquals("در حال دعوت از علی", roomName(NameState.Names(listOf("علی"), 2, inviting = true)))
        assertEquals("گفتگوی خالی", roomName(NameState.Names(emptyList(), 3)))
        assertEquals("گفتگوی خالی (قبلاً علی)", roomName(NameState.Empty("علی")))
        assertEquals("@bob:hs", roomName(NameState.Empty(), "@bob:hs"))
    }

    private data class Row(val id: String, override val invite: Boolean, override val pinned: Boolean = false, override val ts: Long) : ListOrdered

    @Test fun `archive, marked unread and list order`() {
        val base = RoomInfo("1", isDM = true, unread = 0, spaces = emptyList())
        val arch = base.copy(archived = true)
        assertEquals(listOf(false, true, false), listOf(inFolder(arch, "all"), inFolder(arch, "archive"), inFolder(arch, "dms")))
        assertFalse(inArchive(arch.copy(unread = 2)), "unmuted chat with new messages comes back")
        assertTrue(inArchive(arch.copy(unread = 2, muted = true)), "muted ones stay archived")
        assertFalse(inFolder(base, "archive"))
        assertTrue(isUnread(base.copy(marked = true)) && inFolder(base.copy(marked = true), "unread"))
        val rows = listOf(Row("old", false, ts = 1), Row("pin", false, true, 0), Row("new", false, ts = 5), Row("inv", true, ts = 0))
        assertEquals(listOf("inv", "pin", "new", "old"), rows.sortedWith(byListOrder).map { it.id })
    }

    @Test fun `applyFolderOrder - all first, saved order, new spaces last, stale ids ignored`() {
        val folders = listOf("all", "unread", "dms", "s1", "s2").map { Folder(it, it) }
        fun ids(order: List<String>) = applyFolderOrder(folders, order).map { it.id }
        assertEquals(listOf("all", "unread", "dms", "s1", "s2"), ids(emptyList()))
        assertEquals(listOf("all", "s1", "dms", "unread", "s2"), ids(listOf("s1", "gone", "dms")))
        assertEquals(listOf("all", "dms", "unread", "s1", "s2"), ids(listOf("dms", "all")))
    }

    @Test fun `moveFolder never touches the first tab`() {
        val ids = listOf("all", "a", "b", "c")
        assertEquals(listOf("all", "c", "a", "b"), moveFolder(ids, 3, 1))
        assertEquals(listOf("all", "b", "c", "a"), moveFolder(ids, 1, 3))
        assertSame(ids, moveFolder(ids, 0, 2))
        assertSame(ids, moveFolder(ids, 2, 0))
        assertSame(ids, moveFolder(ids, 2, 9))
        assertSame(ids, moveFolder(ids, 2, 2))
    }

    @Test fun `tallyPoll - latest vote wins, spoiled votes erase, max truncates`() {
        fun a(vararg s: String) = JsonArray(s.map { JsonPrimitive(it) })
        val (voters, picks) = tallyPoll(listOf(
            Vote("@a", 1, a("x")),
            Vote("@a", 2, a("y")), // changed vote
            Vote("@b", 1, a("x")),
            Vote("@b", 3, a("nope")), // spoiled: b now has no vote
            Vote("@c", 1, a("x", "y")), // single choice: only x counts
            Vote("@d", 1, JsonPrimitive("x")), // malformed
        ), listOf("x", "y"), 1)
        assertEquals(mapOf("x" to listOf("@c"), "y" to listOf("@a")), voters)
        assertEquals(listOf("@a", "@c"), picks.keys.sorted())
    }
}
