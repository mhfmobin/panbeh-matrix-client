package ir.panbeh.client.admin

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.UserId
import ir.panbeh.client.crypto.newUser
import ir.panbeh.client.errcode
import ir.panbeh.client.testServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal suspend fun <T> Flow<T>.until(ms: Long = 30_000, p: (T) -> Boolean): T = withTimeout(ms) { first(p) }

private fun id(u: String) = "@$u:localhost"

class AdminTest {
    private suspend fun room(c: MatrixClient, version: String? = null, invite: Set<UserId> = emptySet()) =
        c.api.room.createRoom(name = "Test room", roomVersion = version, invite = invite.ifEmpty { null }).getOrThrow()

    private suspend fun join(c: MatrixClient, room: RoomId) = c.api.room.joinRoom(room).getOrThrow()

    @Test
    fun `levels, kick, ban and unban`() = runBlocking {
        val hs = testServer ?: return@runBlocking
        val (a, _, ca) = newUser(hs, "a")
        val (b, _, cb) = newUser(hs, "b")
        val r = room(ca, invite = setOf(UserId(id(b))))
        val admin = RoomAdmin(ca, r.full)
        // invited: shown last, can be cancelled and re-invited
        val inv = admin.members().until { l -> l.any { it.userId == id(b) } }
        assertTrue(inv.first { it.userId == id(b) }.invited)
        admin.cancelInvite(id(b))
        admin.members().until { l -> l.none { it.userId == id(b) } }
        admin.invite(id(b))
        join(cb, r)
        admin.loadMembers()
        val rows = admin.members().until { l -> l.any { it.userId == id(b) && !it.invited } }
        assertEquals(listOf(id(a), id(b)), rows.map { it.userId })
        assertNotNull(rows[0].role) // creator: admin
        assertNull(rows[1].role)
        assertEquals(listOf(id(b)), admin.members("B").until { it.size == 1 }.map { it.userId })
        assertEquals(emptyList(), admin.members("zzzz-nobody").first())

        val ops = admin.memberOps(id(b)).until { it != null }!!
        assertTrue(ops.mayKick && ops.mayBan && ops.mayRole)
        assertEquals(listOf(0.0, 50.0, 100.0), ops.roles.map { it.first })
        assertNull(admin.memberOps(id(a)).first(), "no ops on myself")

        admin.setMemberLevel(id(b), 50.0)
        val mod = admin.members().until { l -> l.any { it.userId == id(b) && it.level == 50.0 } }
        assertEquals("ناظر", mod.first { it.userId == id(b) }.role)
        // a moderator can't act on the room's creator
        assertNull(RoomAdmin(cb, r.full).memberOps(id(a)).until { true })

        admin.kick(id(b), "bye")
        admin.members().until { l -> l.none { it.userId == id(b) } }
    }

    @Test
    fun `kick then ban and unban with the banned list`() = runBlocking {
        val hs = testServer ?: return@runBlocking
        val (_, _, ca) = newUser(hs, "a")
        val (b, _, cb) = newUser(hs, "b")
        val r = room(ca, invite = setOf(UserId(id(b))))
        join(cb, r)
        val admin = RoomAdmin(ca, r.full)
        admin.members().until { l -> l.any { it.userId == id(b) && !it.invited } }
        admin.ban(id(b), "spam")
        val banned = admin.banned().until { it.isNotEmpty() }
        assertEquals(id(b), banned.single().userId)
        assertEquals("spam", banned.single().reason)
        admin.members().until { l -> l.none { it.userId == id(b) } }
        assertEquals("ban", admin.memberOps(id(b)).until { it != null }!!.membership)
        assertFalse(admin.memberOps(id(b)).first()!!.mayKick)
        assertFailsWith<Exception> { join(cb, r) }
        admin.unban(id(b))
        admin.banned().until { it.isEmpty() }
        admin.invite(id(b))
        join(cb, r)
    }

    @Test
    fun `permissions, join rule and history`() = runBlocking {
        val hs = testServer ?: return@runBlocking
        val (_, _, ca) = newUser(hs, "a")
        val r = room(ca)
        val admin = RoomAdmin(ca, r.full)
        val rows = admin.permissions().until { it.isNotEmpty() }
        assertEquals(9, rows.size)
        assertEquals("ارسال پیام", rows[0].label)
        assertEquals(50.0, rows.first { it.label == "بیرون کردن" }.level)
        admin.setPermission(listOf("kick"), 100.0)
        admin.setPermission(rows.first { it.label.startsWith("تغییر نام") }.keys, 50.0)
        admin.setPermission(listOf("events_default"), 50.0)
        val after = admin.permissions().until { l -> l.first { it.label == "بیرون کردن" }.level == 100.0 }
        assertEquals(100.0, after.first { it.label == "بیرون کردن" }.level)
        assertEquals(50.0, admin.permissions().until { l -> l.first { it.label == "ارسال پیام" }.level == 50.0 }.first().level)
        assertEquals(50.0, after.first { it.label.startsWith("تغییر نام") }.level)
        assertEquals(listOf(0.0, 50.0, 100.0), after[0].options.map { it.first })

        val s = admin.settings.until { it.name == "Test room" }
        assertEquals("invite", s.joinRule)
        assertTrue(s.canManage && s.can.joinRules)
        assertEquals(listOf("public", "invite", "knock"), s.joinRules.map { it.first }.take(3))
        admin.setJoinRule("public")
        assertEquals("public", admin.settings.until { it.joinRule == "public" }.joinRule)
        admin.setJoinRule("knock")
        assertEquals("knock", admin.settings.until { it.joinRule == "knock" }.joinRule)
        admin.setHistory("joined")
        assertEquals("joined", admin.settings.until { it.history == "joined" }.history)
        admin.setHistory("world_readable")
        assertEquals("world_readable", admin.settings.until { it.history == "world_readable" }.history)
        assertFailsWith<IllegalArgumentException> { admin.setHistory("nope") }

        admin.setName("New name")
        admin.setTopic("About things")
        val t = admin.settings.until { it.name == "New name" && it.topic == "About things" }
        assertEquals("About things", t.topic)
        assertFalse(t.encrypted)
        admin.enableEncryption()
        assertTrue(admin.settings.until { it.encrypted }.encrypted)
        assertTrue(admin.details().encryption!!.contains("megolm"))
    }

    @Test
    fun `knock requests can be accepted or denied`() = runBlocking {
        val hs = testServer ?: return@runBlocking
        val (_, _, ca) = newUser(hs, "a")
        val (b, _, cb) = newUser(hs, "b")
        val (c, _, cc) = newUser(hs, "c")
        val r = room(ca, version = "10")
        val admin = RoomAdmin(ca, r.full)
        admin.setJoinRule("knock")
        admin.settings.until { it.joinRule == "knock" }
        cb.api.room.knockRoom(r, reason = "let me in").getOrThrow()
        cc.api.room.knockRoom(r).getOrThrow()
        val ks = admin.knocks().until { it.size == 2 }
        assertEquals("let me in", ks.first { it.userId == id(b) }.reason)
        assertTrue(ks.all { it.mayDeny })
        admin.acceptKnock(id(b))
        admin.denyKnock(id(c))
        admin.knocks().until { it.isEmpty() }
        join(cb, r)
        admin.members().until { l -> l.any { it.userId == id(b) && !it.invited } }
    }

    @Test
    fun `alias and public directory`() = runBlocking {
        val hs = testServer ?: return@runBlocking
        val (_, _, ca) = newUser(hs, "a")
        val r = room(ca)
        val admin = RoomAdmin(ca, r.full)
        val lp = "test-" + System.nanoTime()
        assertNull(admin.settings.first().canonicalAlias)
        admin.createAlias(lp)
        assertEquals("#$lp:localhost", admin.settings.until { it.canonicalAlias != null }.canonicalAlias)
        assertEquals(r, ca.api.room.getRoomAlias(de.connect2x.trixnity.core.model.RoomAliasId("#$lp:localhost")).getOrThrow().roomId)
        val e = assertFailsWith<Exception> { RoomAdmin(ca, room(ca).full).createAlias(lp) }
        assertEquals("M_ROOM_IN_USE", e.errcode)

        assertEquals(false, admin.isListed())
        admin.setListed(true)
        assertEquals(true, admin.isListed())
        assertTrue(ca.api.room.getPublicRooms(limit = 50).getOrThrow().chunk.any { it.roomId == r })
        admin.setListed(false)
        assertEquals(false, admin.isListed())
        admin.deleteAlias()
        assertNull(admin.settings.until { it.canonicalAlias == null }.canonicalAlias)
        assertTrue(ca.api.room.getRoomAlias(de.connect2x.trixnity.core.model.RoomAliasId("#$lp:localhost")).isFailure)
    }

    @Test
    fun `details and room upgrade`() = runBlocking {
        val hs = testServer ?: return@runBlocking
        val (a, _, ca) = newUser(hs, "a")
        val (b, _, cb) = newUser(hs, "b")
        val r = room(ca, version = "6", invite = setOf(UserId(id(b))))
        join(cb, r)
        val admin = RoomAdmin(ca, r.full)
        admin.members().until { l -> l.any { it.userId == id(b) && !it.invited } }
        val space = ca.api.room.createRoom(
            name = "Space", creationContent = de.connect2x.trixnity.core.model.events.m.room.CreateEventContent(type = de.connect2x.trixnity.core.model.events.m.room.CreateEventContent.RoomType.Space),
        ).getOrThrow()
        ca.api.room.sendStateEvent(space, de.connect2x.trixnity.core.model.events.m.space.ChildEventContent(via = setOf("localhost")), r.full).getOrThrow()
        RoomAdmin(ca, space.full).settings.until { it.isSpace }

        val d = admin.details()
        assertEquals(r.full, d.roomId)
        assertEquals("6", d.version)
        assertEquals(listOf(id(a)), d.creators)
        assertNotNull(d.createdTs)
        assertNull(d.encryption)
        assertNull(d.predecessor)
        val target = d.upgradeTo
        assertNotNull(target)
        assertTrue(target.toInt() > 6)

        val to = admin.upgrade()
        val nd = RoomAdmin(ca, to).details()
        assertEquals(target, nd.version)
        assertNull(nd.upgradeTo)
        assertEquals(r.full, nd.predecessor)
        // private room: the joined member was re-invited
        val inv = withTimeout(30_000) { RoomAdmin(ca, to).members().first { l -> l.any { it.userId == id(b) } } }
        assertTrue(inv.first { it.userId == id(b) }.invited)
        // the space lists the new room instead of the old one
        val kids = withTimeout(30_000) {
            var out = emptySet<String>()
            while (to !in out || r.full in out) {
                out = ca.api.room.getState(space).getOrThrow().filter { it.content is de.connect2x.trixnity.core.model.events.m.space.ChildEventContent }
                    .map { it.stateKey }.toSet()
                kotlinx.coroutines.delay(300)
            }
            out
        }
        assertContains(kids, to)
        // an up-to-date room has nothing to upgrade, and upgrade() says so
        assertFailsWith<IllegalStateException> { RoomAdmin(ca, to).upgrade() }
    }

    @Test
    fun `avatar upload and removal`() = runBlocking {
        val hs = testServer ?: return@runBlocking
        val (_, _, ca) = newUser(hs, "a")
        val r = room(ca)
        val admin = RoomAdmin(ca, r.full)
        val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        admin.setAvatar(png, "image/png")
        assertTrue(admin.settings.until { it.avatarUrl != null }.avatarUrl!!.startsWith("mxc://"))
        admin.removeAvatar()
        assertNull(admin.settings.until { it.avatarUrl == null }.avatarUrl)
    }
}
