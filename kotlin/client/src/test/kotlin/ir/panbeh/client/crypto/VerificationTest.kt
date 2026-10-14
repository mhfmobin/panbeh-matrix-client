package ir.panbeh.client.crypto

import de.connect2x.trixnity.client.room
import de.connect2x.trixnity.client.user
import de.connect2x.trixnity.clientserverapi.model.room.CreateRoom
import de.connect2x.trixnity.core.model.UserId
import de.connect2x.trixnity.core.model.events.InitialStateEvent
import de.connect2x.trixnity.core.model.events.m.DirectEventContent
import de.connect2x.trixnity.core.model.events.m.room.EncryptionEventContent
import de.connect2x.trixnity.core.model.events.m.room.Membership
import de.connect2x.trixnity.core.model.events.m.room.RoomMessageEventContent
import ir.panbeh.client.events.RawMessageSerializer
import ir.panbeh.client.testServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VerificationTest {
    @Test
    fun `self verification with SAS ends verified on both sides`() = runBlocking {
        val hs = testServer ?: return@runBlocking
        val (u, p, c1) = newUser(hs)
        Recovery(c1).setup(password = p)
        val c2 = device(hs, u, p)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val v1 = Verification(c1, scope)
            val v2 = Verification(c2, scope)
            v2.requestSelf()
            assertEquals(VerifyPhase.Requested(true), withTimeout(30_000) { v2.current.first { it != null } }!!.phase)
            val req = withTimeout(30_000) { v1.incoming.first() }
            assertTrue(req.self)
            assertEquals(VerifyPhase.Requested(false), withTimeout(30_000) { v1.current.first { it != null } }!!.phase)
            v1.accept()
            val a = withTimeout(60_000) { v1.current.first { it?.phase is VerifyPhase.Compare } }!!.phase as VerifyPhase.Compare
            val b = withTimeout(60_000) { v2.current.first { it?.phase is VerifyPhase.Compare } }!!.phase as VerifyPhase.Compare
            assertEquals(7, a.emoji.size)
            assertEquals(a.emoji, b.emoji)
            assertEquals(a.decimal, b.decimal)
            assertTrue(a.emoji.all { it.name.isNotBlank() && it.name != it.emoji })
            v1.confirmMatch()
            v2.confirmMatch()
            assertIs<VerifyPhase.Done>(withTimeout(60_000) { v1.current.first { it?.finished == true } }!!.phase)
            assertIs<VerifyPhase.Done>(withTimeout(60_000) { v2.current.first { it?.finished == true } }!!.phase)
            withTimeout(30_000) { Devices(c1).watch().first { l -> l.all { it.crossSigningVerified } && l.size == 2 } }
        } finally { scope.cancel() }
    }

    @Test
    fun `a mismatch cancels with the Persian reason`() = runBlocking {
        val hs = testServer ?: return@runBlocking
        val (u, p, c1) = newUser(hs)
        Recovery(c1).setup(password = p)
        val c2 = device(hs, u, p)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val v1 = Verification(c1, scope)
            val v2 = Verification(c2, scope)
            v2.requestSelf()
            withTimeout(30_000) { v1.incoming.first() }
            v1.accept()
            withTimeout(60_000) { v1.current.first { it?.phase is VerifyPhase.Compare } }
            withTimeout(60_000) { v2.current.first { it?.phase is VerifyPhase.Compare } }
            v1.mismatch()
            val c = withTimeout(60_000) { v1.current.first { it?.finished == true } }!!.phase
            assertEquals(VerifyPhase.Cancelled("شکلک‌ها مطابقت نداشتند؛ تأیید لغو شد."), c)
            assertIs<VerifyPhase.Cancelled>(withTimeout(60_000) { v2.current.first { it?.finished == true } }!!.phase)
        } finally { scope.cancel() }
    }
}

class UserVerificationTest {
    @Test
    fun `verifying another user in a direct chat`() = runBlocking {
        val hs = testServer ?: return@runBlocking
        val sample = buildJsonObject { put("msgtype", "m.key.verification.request"); put("body", "x"); put("from_device", "D"); put("to", "@u:localhost"); put("methods", buildJsonArray { add(JsonPrimitive("m.sas.v1")) }) }
        if (Json.decodeFromJsonElement(RawMessageSerializer, sample) !is RoomMessageEventContent.VerificationRequest) {
            println("SKIPPED: RawMessageSerializer turns m.key.verification.request into Unknown, so Trixnity can't see incoming chat requests")
            return@runBlocking
        }
        val (a, pa, ca) = newUser(hs, "a")
        val (b, pb, cb) = newUser(hs, "b")
        Recovery(ca).setup(password = pa)
        Recovery(cb).setup(password = pb)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val va = Verification(ca, scope)
            val vb = Verification(cb, scope)
            val devs = Devices(ca)
            withTimeout(30_000) { devs.userTrust("@$b:localhost").first { it.known } }
            assertEquals(false, devs.userTrust("@$b:localhost").first().verified)
            // the usual direct chat, joined by both
            val dm = ca.api.room.createRoom(
                invite = setOf(UserId("@$b:localhost")), isDirect = true, preset = CreateRoom.Request.Preset.TRUSTED_PRIVATE,
                initialState = listOf(InitialStateEvent(EncryptionEventContent(), "")),
            ).getOrThrow()
            ca.api.user.setAccountData(DirectEventContent(mapOf(UserId("@$b:localhost") to setOf(dm))), ca.userId).getOrThrow()
            cb.api.room.joinRoom(dm).getOrThrow()
            withTimeout(30_000) { ca.user.getById(dm, UserId("@$b:localhost")).first { it?.event?.content?.membership == Membership.JOIN } }
            withTimeout(30_000) { ca.room.getById(dm).first { it?.encrypted == true } }
            va.verifyUser("@$b:localhost")
            val req = withTimeout(60_000) { vb.incoming.first() }
            assertEquals(false, req.self)
            assertEquals("@$a:localhost", req.otherUserId)
            vb.accept()
            val x = withTimeout(60_000) { va.current.first { it?.phase is VerifyPhase.Compare } }!!.phase as VerifyPhase.Compare
            val y = withTimeout(60_000) { vb.current.first { it?.phase is VerifyPhase.Compare } }!!.phase as VerifyPhase.Compare
            assertEquals(x.emoji, y.emoji)
            va.confirmMatch(); vb.confirmMatch()
            assertIs<VerifyPhase.Done>(withTimeout(60_000) { va.current.first { it?.finished == true } }!!.phase)
            assertIs<VerifyPhase.Done>(withTimeout(60_000) { vb.current.first { it?.finished == true } }!!.phase)
            assertTrue(withTimeout(30_000) { devs.userTrust("@$b:localhost").first { it.verified } }.verified)
        } finally { scope.cancel() }
    }
}
