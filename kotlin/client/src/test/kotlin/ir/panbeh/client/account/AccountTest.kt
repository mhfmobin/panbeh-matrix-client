package ir.panbeh.client.account

import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.events.m.Presence
import de.connect2x.trixnity.core.model.events.m.PushRulesEventContent
import de.connect2x.trixnity.core.model.push.PushAction
import de.connect2x.trixnity.core.model.push.PushRule
import de.connect2x.trixnity.core.model.push.PushRuleSet
import ir.panbeh.client.Accounts
import ir.panbeh.client.crypto.device
import ir.panbeh.client.crypto.newUser
import ir.panbeh.client.testPlatform
import ir.panbeh.client.testServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountTest {
    private val png = byteArrayOf(
        -119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0, 31, 21, -60, -119, 0, 0, 0, 13, 73, 68, 65, 84,
        120, -38, 99, 100, 96, 96, 96, 0, 0, 0, 5, 0, 1, 90, -52, 10, 53, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126,
    )

    @Test
    fun `password change keeps this device signed in and the new password works`() = runBlocking {
        val hs = testServer ?: return@runBlocking
        val (u, p, c1) = newUser(hs)
        val c2 = device(hs, u, p)
        val sec = AccountSecurity(c1)
        assertFailsWith<WrongPassword> { sec.changePassword("not my password", "new-password-123") }
        sec.changePassword(p, "new-password-123")
        // logout_devices=false: both sessions still work
        c1.api.authentication.whoAmI().getOrThrow()
        c2.api.authentication.whoAmI().getOrThrow()
        Accounts(testPlatform()).loginPassword(hs, u, "new-password-123")
        assertFailsWith<Exception> { Accounts(testPlatform()).loginPassword(hs, u, p) }
    }

    @Test
    fun `deactivate needs the password and ends the account`() = runBlocking {
        val hs = testServer ?: return@runBlocking
        val (u, p, c1) = newUser(hs)
        assertFailsWith<WrongPassword> { AccountSecurity(c1).deactivate("wrong") }
        AccountSecurity(c1).deactivate(p)
        assertFailsWith<Exception> { Accounts(testPlatform()).loginPassword(hs, u, p) }
    }

    @Test
    fun `block and unblock a user`() = runBlocking {
        val hs = testServer ?: return@runBlocking
        val (_, _, c1) = newUser(hs)
        val (v, _, _) = newUser(hs)
        val id = "@$v:localhost"
        val b = Blocking(c1)
        assertEquals(emptyList(), withTimeout(30_000) { b.blocked.first() })
        b.setBlocked(id, true)
        assertEquals(listOf(id), withTimeout(30_000) { b.blocked.first { it.isNotEmpty() } })
        assertTrue(b.isBlocked(id))
        b.setBlocked(id, true)
        assertEquals(1, b.blocked.first().size)
        b.setBlocked(id, false)
        assertEquals(emptyList(), withTimeout(30_000) { b.blocked.first { it.isEmpty() } })
    }

    @Test
    fun `keywords and the at-room switch are server push rules`() = runBlocking {
        val hs = testServer ?: return@runBlocking
        val (_, _, c1) = newUser(hs)
        val ps = PushSettings(c1)
        ps.addKeyword("  پنبه ")
        ps.addKeyword("")
        val kw = withTimeout(30_000) { ps.keywords.first { it.isNotEmpty() } }
        assertEquals(listOf(Keyword("پنبه", "پنبه")), kw)
        val rule = c1.api.push.getPushRules().getOrThrow().global.content!!.first { it.ruleId == "پنبه" }
        assertEquals(setOf(PushAction.Notify, PushAction.SetSoundTweak("default"), PushAction.SetHighlightTweak()), rule.actions)
        assertTrue(rule.enabled)
        ps.removeKeyword("پنبه")
        assertEquals(emptyList(), withTimeout(30_000) { ps.keywords.first { it.isEmpty() } })

        val at = withTimeout(30_000) { ps.atRoom.first { it != null } }
        assertEquals(true, at)
        ps.setAtRoom(false)
        assertEquals(false, withTimeout(30_000) { ps.atRoom.first { it == false } })
        ps.setAtRoom(true)
        assertEquals(true, withTimeout(30_000) { ps.atRoom.first { it == true } })
    }

    @Test
    fun `muted rooms come from room and override rules that do not notify`() {
        val room = PushRule.Room(RoomId("!a:localhost"), enabled = true, actions = emptySet())
        val loud = PushRule.Room(RoomId("!b:localhost"), enabled = true, actions = setOf(PushAction.Notify))
        val off = PushRule.Room(RoomId("!c:localhost"), enabled = false, actions = emptySet())
        val ov = PushRule.Override("!d:localhost", enabled = true, actions = emptySet())
        val rules = PushRulesEventContent(PushRuleSet(room = listOf(room, loud, off), override = listOf(ov)))
        assertTrue(isMuted(rules, "!a:localhost"))
        assertFalse(isMuted(rules, "!b:localhost"))
        assertFalse(isMuted(rules, "!c:localhost"))
        assertTrue(isMuted(rules, "!d:localhost"))
        assertFalse(isMuted(null, "!a:localhost"))
    }

    @Test
    fun `profile name and photo`() = runBlocking {
        val hs = testServer ?: return@runBlocking
        val (u, _, c1) = newUser(hs)
        val me = MyProfile(c1)
        me.setDisplayName("  علی ")
        assertEquals("علی", withTimeout(30_000) { me.profile.first { it.displayName == "علی" } }.displayName)
        val mxc = me.setAvatar(png, "image/png")
        assertTrue(mxc.startsWith("mxc://"))
        assertEquals(mxc, withTimeout(30_000) { me.profile.first { it.avatarUrl == mxc } }.avatarUrl)
        assertEquals(ProfileInfo("علی", mxc), me.of("@$u:localhost"))
        me.removeAvatar()
        assertNull(withTimeout(30_000) { me.profile.first { it.avatarUrl == null } }.avatarUrl)
        assertNull(me.of("@nobody-here:localhost")?.displayName)
    }

    @Test
    fun `presence follows the privacy setting`() {
        assertEquals(Presence.ONLINE, PresencePrivacy.syncPresence(true))
        assertEquals(Presence.UNAVAILABLE, PresencePrivacy.syncPresence(true, background = true))
        assertEquals(Presence.OFFLINE, PresencePrivacy.syncPresence(false))
        assertEquals(Presence.OFFLINE, PresencePrivacy.syncPresence(false, background = true))
    }

    @Test
    fun `account page links like the web app`() {
        val uri = "https://account.example.org/account/"
        val all = setOf("org.matrix.profile", "org.matrix.device_delete", "org.matrix.devices_list", "org.matrix.account_deactivate")
        assertEquals("$uri?action=org.matrix.profile", AccountUrls.account(uri, all, AccountUrls.PROFILE))
        assertEquals(uri, AccountUrls.account(uri, emptySet(), AccountUrls.PROFILE))
        assertNull(AccountUrls.account(null, all))
        assertEquals("$uri?action=org.matrix.device_delete&device_id=ABC", AccountUrls.device(uri, all, "ABC"))
        assertEquals("$uri?action=org.matrix.session_end&device_id=ABC", AccountUrls.device(uri, setOf("org.matrix.session_end"), "ABC"))
        assertEquals("$uri?action=org.matrix.devices_list", AccountUrls.device(uri, all))
        assertEquals("$uri?action=org.matrix.sessions_list", AccountUrls.device(uri, setOf("org.matrix.sessions_list")))
        assertEquals("$uri?action=org.matrix.devices_list", AccountUrls.device(uri, emptySet()))
    }
}
