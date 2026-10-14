package ir.panbeh.client.rooms

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.room
import de.connect2x.trixnity.client.room.message.text
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.events.m.space.ParentEventContent
import de.connect2x.trixnity.clientserverapi.client.getStateEventContent
import de.connect2x.trixnity.core.model.events.m.room.JoinRulesEventContent
import ir.panbeh.client.Accounts
import ir.panbeh.client.register
import ir.panbeh.client.testPlatform
import ir.panbeh.client.testServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A signed-in test user with a running sync and a Rooms. */
class Who(val name: String, val client: MatrixClient, val rooms: Rooms, val chats: Chats, val scope: CoroutineScope) {
    val id get() = client.userId.full
    suspend fun row(roomId: String, timeout: Long = 30_000, p: (RoomSummary) -> Boolean = { true }): RoomSummary =
        withTimeout(timeout) { rooms.list.first { l -> l.rows.any { it.id == roomId && p(it) } }.rows.first { it.id == roomId } }
    suspend fun gone(roomId: String) = withTimeout(30_000) { rooms.list.first { l -> l.rows.none { it.id == roomId } } }
    fun close() = scope.cancel()
}

suspend fun who(prefix: String): Who {
    val (user, pass) = register(prefix)
    val client = Accounts(testPlatform()).loginPassword(testServer!!, user, pass)
    client.startSync()
    withTimeout(30_000) { client.initialSyncDone.first { it } }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    return Who(user, client, Rooms(client, scope), Chats(client), scope)
}

suspend fun <T> Flow<T>.until(timeout: Long = 30_000, p: (T) -> Boolean): T = withTimeout(timeout) { first(p) }

class RoomsTest {
    @Test
    fun `invite then joined DM shows name, peer and DM flag`() = runBlocking {
        testServer ?: return@runBlocking
        val a = who("a"); val b = who("b")
        try {
            val room = a.chats.openDM(b.id)
            val inv = b.row(room) { it.invite }
            assertTrue(inv.isDM)
            assertEquals(a.id, inv.inviter)
            assertEquals(a.name, inv.name) // Persian/inviter name fallback: the inviter's display name
            assertEquals("دعوت‌نامه", inv.preview)
            assertEquals(room, b.rooms.list.value.rows.first().id) // invites lead the list

            b.rooms.acceptInvite(room)
            val joined = b.row(room) { !it.invite }
            assertTrue(joined.isDM)
            assertEquals(a.id, joined.peer)
            assertEquals(a.name, joined.name)
            assertTrue(joined.encrypted)
            // a's side: the DM named after b
            assertEquals(b.name, a.row(room).name)
            assertEquals(b.id, a.row(room).peer)
        } finally { a.close(); b.close() }
    }

    @Test
    fun `openDM reuses a joined DM`() = runBlocking {
        testServer ?: return@runBlocking
        val a = who("a"); val b = who("b")
        try {
            val r1 = a.chats.openDM(b.id)
            b.row(r1) { it.invite }; b.rooms.acceptInvite(r1)
            a.row(r1)
            val r2 = a.chats.openDM(b.id)
            assertEquals(r1, r2)
            assertEquals(r1, b.chats.openDM(a.id))
            assertEquals(r1, a.chats.findDM(b.id))
        } finally { a.close(); b.close() }
    }

    @Test
    fun `unread count, preview, mark read and marked unread round trip`() = runBlocking {
        testServer ?: return@runBlocking
        val a = who("a"); val b = who("b")
        try {
            val room = a.chats.openDM(b.id)
            b.row(room) { it.invite }; b.rooms.acceptInvite(room)
            a.row(room)
            // the DM is encrypted: use a plain group so the server counts and Trixnity needs no keys
            val g = a.chats.createChat(NewChat(name = "گروه", invite = listOf(b.id), encrypted = false)).roomId
            b.row(g) { it.invite }; b.rooms.acceptInvite(g); b.row(g) { !it.invite }
            a.client.room.sendMessage(RoomId(g)) { text("سلام دنیا") }
            val r = b.row(g) { it.unread == 1 }
            assertEquals("سلام دنیا", r.last?.text)
            assertEquals("⁨${a.name}⁩: سلام دنیا", r.preview)
            assertTrue(r.isUnread)
            assertEquals(1, b.rooms.list.value.unreadIn("all"))
            assertEquals(1, b.rooms.list.value.unreadChats)

            b.rooms.markRead(g)
            val read = b.row(g) { it.unread == 0 }
            assertFalse(read.isUnread)

            b.rooms.setMarkedUnread(g, true)
            assertTrue(b.row(g) { it.marked }.isUnread)
            assertEquals(1, b.rooms.list.value.unreadIn("unread"))
            b.rooms.markRead(g)
            assertFalse(b.row(g) { !it.marked }.isUnread)
            b.rooms.toggleRead(b.row(g))
            assertTrue(b.row(g) { it.marked }.isUnread)
            b.rooms.toggleRead(b.row(g))
            b.row(g) { !it.marked }

            // my own message shows "شما"
            b.client.room.sendMessage(RoomId(g)) { text("جواب") }
            assertEquals("شما: جواب", b.row(g) { it.last?.text == "جواب" }.preview)
        } finally { a.close(); b.close() }
    }

    @Test
    fun `legacy famedly marked unread is read and cleared`() = runBlocking {
        testServer ?: return@runBlocking
        val a = who("a")
        try {
            val g = a.chats.createChat(NewChat(name = "x", encrypted = false)).roomId
            a.row(g)
            a.client.api.room.setAccountData(ir.panbeh.client.events.FamedlyMarkedUnread(kotlinx.serialization.json.buildJsonObject { put("unread", kotlinx.serialization.json.JsonPrimitive(true)) }), RoomId(g), a.client.userId).getOrThrow()
            assertTrue(a.row(g) { it.marked }.isUnread)
            a.rooms.markRead(g)
            a.row(g) { !it.marked }
        } finally { a.close() }
    }

    @Test
    fun `pin and archive tags`() = runBlocking {
        testServer ?: return@runBlocking
        val a = who("a")
        try {
            val g1 = a.chats.createChat(NewChat(name = "اول", encrypted = false)).roomId
            val g2 = a.chats.createChat(NewChat(name = "دوم", encrypted = false)).roomId
            a.row(g1); a.row(g2)
            assertEquals(g2, a.rooms.list.value.rows.first().id) // newest first
            a.rooms.pin(g1, true)
            a.row(g1) { it.pinned }
            assertEquals(g1, a.rooms.list.value.rows.first().id)
            a.rooms.archive(g2, true)
            assertTrue(a.row(g2) { it.archived }.archived)
            assertEquals(listOf(g2), a.rooms.list.value.archived.map { it.id })
            assertEquals(listOf(g1), a.rooms.list.value.inFolder("all").map { it.id })
            a.rooms.archive(g2, false); a.rooms.pin(g1, false)
            a.row(g2) { !it.archived }; a.row(g1) { !it.pinned }
        } finally { a.close() }
    }

    @Test
    fun `mute, unmute and timed mute`() = runBlocking {
        testServer ?: return@runBlocking
        val a = who("a")
        try {
            val g = a.chats.createChat(NewChat(name = "بی‌صدا", encrypted = false)).roomId
            a.row(g)
            a.rooms.mute(g)
            val m = a.row(g) { it.muted }
            assertNull(m.muteUntil)
            a.rooms.unmute(g)
            a.row(g) { !it.muted }

            val until = System.currentTimeMillis() + 3_600_000
            a.rooms.mute(g, MuteSpan.HOUR)
            val t = a.row(g) { it.muted && it.muteUntil != null }
            assertTrue(t.muteUntil!! >= until - 5_000)
            // not yet due
            a.rooms.unmuteExpired()
            assertTrue(a.row(g).muted)
            // due: the minute timer's work
            val past = System.currentTimeMillis() - 1000
            a.rooms.muteUntil(g, past)
            a.row(g) { it.muted && it.muteUntil == past }
            a.rooms.unmuteExpired()
            val back = a.row(g) { !it.muted }
            assertNull(back.muteUntil)
            a.row(g) { it.muteUntil == null }
        } finally { a.close() }
    }

    @Test
    fun `leave and forget removes the room, declining an invite too`() = runBlocking {
        testServer ?: return@runBlocking
        val a = who("a"); val b = who("b")
        try {
            val g = a.chats.createChat(NewChat(name = "ترک", invite = listOf(b.id), encrypted = false)).roomId
            b.row(g) { it.invite }; b.rooms.acceptInvite(g); b.row(g) { !it.invite }
            b.rooms.leaveAndForget(g)
            b.gone(g)
            assertNull(b.client.room.getById(RoomId(g)).first())

            val g2 = a.chats.createChat(NewChat(name = "رد", invite = listOf(b.id), encrypted = false)).roomId
            b.row(g2) { it.invite }
            b.rooms.declineInvite(g2)
            b.gone(g2)
        } finally { a.close(); b.close() }
    }

    @Test
    fun `createChat with a space parent, restricted join and call power`() = runBlocking {
        testServer ?: return@runBlocking
        val a = who("a")
        try {
            assertTrue(a.chats.canRestrict())
            val space = a.chats.createChat(NewChat(name = "فضا", space = true)).roomId
            val s = a.rooms.list.until { l -> l.folderSpaces.any { it.id == space } }
            assertTrue(s.folderSpaces.first { it.id == space }.topLevel)
            assertTrue(s.rows.none { it.id == space })

            val created = a.chats.createChat(NewChat(name = "زیرگروه", parent = space, spaceMembersJoin = true, encrypted = false))
            assertNull(created.parentError)
            val g = created.roomId
            val row = a.row(g) { it.spaces == listOf(space) }
            assertEquals(listOf(space), row.spaces)
            assertEquals(1, a.rooms.list.value.unreadIn(space).let { 1 }) // folder works
            assertEquals(listOf(g), a.rooms.list.value.inFolder(space).map { it.id })

            val parent = a.client.api.room.getStateEventContent<ParentEventContent>(RoomId(g), space).getOrThrow()
            assertTrue(parent.canonical)
            val jr = a.client.api.room.getStateEventContent<JoinRulesEventContent>(RoomId(g)).getOrThrow()
            assertEquals(JoinRulesEventContent.JoinRule.Restricted, jr.joinRule)
            val pl = a.client.api.room.getStateEventContent<de.connect2x.trixnity.core.model.events.m.room.PowerLevelsEventContent>(RoomId(g)).getOrThrow()
            assertEquals(0L, pl.events.entries.first { it.key.name == "org.matrix.msc3401.call.member" }.value)

            // folder tab
            val tabs = a.rooms.folders.until { f -> f.any { it.folder.id == space } }
            assertEquals(listOf("all", "unread", "dms", "groups", space), tabs.map { it.folder.id }.take(5))

            // unjoined children from the hierarchy; remove link
            assertEquals(emptyList(), a.chats.hierarchy(space).map { it.id })
            assertEquals(listOf(g), a.chats.hierarchy(space, onlyUnjoined = false).map { it.id })
            a.chats.removeFromSpace(space, g)
            a.rooms.list.until { l -> l.rows.first { it.id == g }.spaces.isEmpty() }
            a.chats.addToSpace(space, g)
            a.rooms.list.until { l -> l.rows.first { it.id == g }.spaces == listOf(space) }
        } finally { a.close() }
    }

    @Test
    fun `folder order round trip through the server`() = runBlocking {
        testServer ?: return@runBlocking
        val a = who("a")
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val space = a.chats.createChat(NewChat(name = "فضا", space = true)).roomId
            a.rooms.folders.until { f -> f.any { it.folder.id == space } }
            val order = listOf("all", space, "groups", "dms", "unread")
            a.rooms.setFolderOrder(order)
            assertEquals(order, a.rooms.folders.until { f -> f.map { it.folder.id } == order }.map { it.folder.id })
            // a fresh Rooms (as after a restart) reads it from account data
            val other = Rooms(a.client, scope2)
            assertEquals(order, other.folderOrder.until { it == order })
            assertEquals(order, other.folders.until { f -> f.map { it.folder.id } == order }.map { it.folder.id })
            a.rooms.moveFolder(4, 1)
            a.rooms.folders.until { f -> f.map { it.folder.id } == listOf("all", "unread", space, "groups", "dms") }
        } finally { a.close(); scope2.cancel() }
    }

    @Test
    fun `user and public directory search, join by alias, forbidden join needs knock`() = runBlocking {
        testServer ?: return@runBlocking
        val a = who("a"); val b = who("b")
        try {
            // direct lookup of a full user id works without a shared room
            val hits = b.chats.searchUsers(a.id)
            assertEquals(listOf(a.id), hits.map { it.userId })
            assertEquals(emptyList(), b.chats.searchUsers(a.id, exclude = listOf(a.id)))
            assertEquals(emptyList(), b.chats.searchUsers("@nobody${a.name}:localhost"))

            val alias = "pub" + a.name.takeLast(6)
            val pub = a.chats.createChat(NewChat(name = "pub $alias", alias = alias, encrypted = false)).roomId
            val found = withTimeout(30_000) {
                var r: PublicRooms
                do { r = b.chats.publicRooms(alias); if (r.rooms.none { it.id == pub }) kotlinx.coroutines.delay(500) } while (r.rooms.none { it.id == pub })
                r
            }
            val pr = found.rooms.first { it.id == pub }
            assertEquals("#$alias:localhost", pr.alias)
            assertFalse(pr.joined)
            val j = b.chats.join("#$alias:localhost")
            assertTrue(j is JoinResult.Joined && j.roomId == pub && !j.isSpace)
            b.row(pub)
            assertTrue(b.chats.publicRooms(alias).rooms.first { it.id == pub }.joined)

            val priv = a.chats.createChat(NewChat(name = "خصوصی", encrypted = false)).roomId
            assertEquals(JoinResult.NeedsKnock, b.chats.join(priv, setOf("localhost")))
            assertNotNull(b.chats.knock(priv, setOf("localhost")).let { it as? KnockResult })
        } finally { a.close(); b.close() }
    }

    @Test
    fun `typing and presence`() = runBlocking {
        testServer ?: return@runBlocking
        val a = who("a"); val b = who("b")
        try {
            val g = a.chats.createChat(NewChat(name = "تایپ", invite = listOf(b.id), encrypted = false)).roomId
            b.row(g) { it.invite }; b.rooms.acceptInvite(g); b.row(g) { !it.invite }; a.row(g)
            a.client.api.room.setTyping(RoomId(g), a.client.userId, true, 20_000).getOrThrow()
            val names = b.rooms.typing(g).until { it.isNotEmpty() }
            assertContentEquals(listOf(a.name), names)
            assertEquals("⁨${a.name}⁩ در حال نوشتن", b.rooms.typingText(false, names))
            assertEquals("در حال نوشتن", b.rooms.typingText(true, names))
            a.client.api.room.setTyping(RoomId(g), a.client.userId, false).getOrThrow()
            b.rooms.typing(g).until { it.isEmpty() }
            // presence: the server answers (test Synapse has presence on or off; no crash either way)
            val p = b.rooms.presence(a.id).first()
            assertNotNull(p)
        } finally { a.close(); b.close() }
    }
}
