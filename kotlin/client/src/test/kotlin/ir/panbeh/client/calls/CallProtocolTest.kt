package ir.panbeh.client.calls

import de.connect2x.trixnity.core.model.EventId
import kotlinx.serialization.json.*
import kotlin.test.*

class CallProtocolTest {
    private val me = "@me:localhost"

    @Test
    fun `own membership content has the shape of the MatrixRTC session`() {
        val c = memberContent("DEV", CallIntent.VIDEO, LivekitFocus("https://jwt.example"), "!r:localhost", 1000)
        assertEquals("m.call", c["application"]!!.jsonPrimitive.content)
        assertEquals("", c["call_id"]!!.jsonPrimitive.content)
        assertEquals("m.room", c["scope"]!!.jsonPrimitive.content)
        assertEquals("DEV", c["device_id"]!!.jsonPrimitive.content)
        assertEquals("video", c["m.call.intent"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive("oldest_membership"), c["focus_active"]!!.jsonObject["focus_selection"])
        assertEquals("livekit", c["focus_active"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val f = c["foci_preferred"]!!.jsonArray.single().jsonObject
        assertEquals("livekit", f["type"]!!.jsonPrimitive.content)
        assertEquals("https://jwt.example", f["livekit_service_url"]!!.jsonPrimitive.content)
        assertEquals("!r:localhost", f["livekit_alias"]!!.jsonPrimitive.content)
        assertEquals("_@me:localhost_DEV", memberStateKey(me, "DEV"))
    }

    @Test
    fun `memberships parse from per-device and array forms and reject spoofing`() {
        val c = memberContent("DEV", CallIntent.AUDIO, LivekitFocus("https://jwt.example"), "!r", 5000, expiresMs = 1000)
        val m = parseMemberships(memberStateKey(me, "DEV"), me, "\$e1", 1, c).single()
        assertEquals(me, m.userId); assertEquals("DEV", m.deviceId); assertEquals(5000, m.createdTs)
        assertEquals(6000L, m.expiresAt); assertTrue(m.expired(6000)); assertTrue(!m.expired(5999))
        assertEquals("$me:DEV", m.identity)
        assertEquals("https://jwt.example", m.focus?.serviceUrl)
        // someone else's state key
        assertTrue(parseMemberships(memberStateKey("@other:localhost", "DEV"), me, "\$e", 1, c).isEmpty())
        // a leave
        assertTrue(parseMemberships(memberStateKey(me, "DEV"), me, "\$e", 1, JsonObject(emptyMap())).isEmpty())
        // another call (call_id) is not the room call
        val other = JsonObject(c + ("call_id" to JsonPrimitive("x")))
        assertTrue(parseMemberships(memberStateKey(me, "DEV"), me, "\$e", 1, other).isEmpty())
        // the old array form: state key = user id, created_ts defaults to the event time
        val old = buildJsonObject {
            putJsonArray("memberships") {
                addJsonObject { put("application", "m.call"); put("call_id", ""); put("scope", "m.room"); put("device_id", "OLD"); put("expires", 3600000)
                    putJsonArray("foci_active") { add(buildJsonObject { put("type", "livekit"); put("livekit_service_url", "https://x") }) } }
                addJsonObject { put("application", "m.other"); put("device_id", "Z") }
            }
        }
        val o = parseMemberships(me, me, "\$o", 42, old).single()
        assertEquals("OLD", o.deviceId); assertEquals(42, o.createdTs); assertEquals("https://x", o.focus?.serviceUrl)
    }

    @Test
    fun `the oldest membership picks the SFU`() {
        fun mem(dev: String, ts: Long, url: String) = CallMembership(me, dev, "k$dev", null, ts, null, null, listOf(LivekitFocus(url)))
        val l = listOf(mem("B", 200, "https://b"), mem("A", 100, "https://a"), mem("C", 300, "https://c"))
        assertEquals("A", oldestMembership(l)?.deviceId)
        assertEquals("https://a", sfuOfOldest(l)?.serviceUrl)
        assertNull(sfuOfOldest(emptyList()))
    }

    @Test
    fun `ring, decline, hand and reaction contents`() {
        val ring = ringContent(EventId("\$m"), direct = true, others = listOf("@b:localhost"), intent = CallIntent.VIDEO, now = 1000)
        assertEquals("ring", ring.raw["notification_type"]!!.jsonPrimitive.content)
        assertEquals("video", ring.raw["m.call.intent"]!!.jsonPrimitive.content)
        assertEquals(JsonArray(listOf(JsonPrimitive("@b:localhost"))), ring.raw["m.mentions"]!!.jsonObject["user_ids"])
        assertEquals(90_000L, ring.raw["lifetime"]!!.jsonPrimitive.long)
        val group = ringContent(EventId("\$m"), direct = false, others = emptyList(), intent = CallIntent.AUDIO, now = 1000)
        assertEquals("notification", group.raw["notification_type"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), group.raw["m.mentions"]!!.jsonObject["room"])
        assertEquals("🖐️", CallTypes.HAND)
        assertEquals("party", reactionName("🎉")); assertEquals("generic", reactionName("🦄"))
        assertEquals("👍🏽", firstEmoji("👍🏽x"))
        assertEquals("👨‍👩‍👧", firstEmoji("👨‍👩‍👧 family"))
        assertEquals("", firstEmoji("  "))
    }

    @Test
    fun `notification expiry follows sender_ts within 20 seconds of the server time`() {
        fun c(lifetime: Number?, sender: Number?) = buildJsonObject { lifetime?.let { put("lifetime", it) }; sender?.let { put("sender_ts", it) } }
        assertEquals(31_000.0, notificationExpiry(c(30_000, 1000), 1500))
        assertEquals(31_500.0, notificationExpiry(c(30_000, 99_000), 1500)) // skewed clock: server time wins
        assertEquals(31_500.0, notificationExpiry(c(30_000, null), 1500))
        assertTrue(notificationExpiry(c(null, 1000), 1500).isNaN())
    }
}
