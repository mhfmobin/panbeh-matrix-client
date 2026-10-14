package ir.panbeh.client.calls

import de.connect2x.trixnity.clientserverapi.client.MatrixClientServerApiClientImpl
import de.connect2x.trixnity.core.model.UserId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import ir.panbeh.client.errcode
import kotlin.test.*

class SfuTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun `sfu get posts room, openid token and device and returns url and jwt`() = runBlocking {
        var body: JsonObject? = null
        var path = ""
        val http = HttpClient(MockEngine { req ->
            path = req.url.toString()
            body = Json.parseToJsonElement((req.body as TextContent).text).jsonObject
            respond("""{"url":"wss://lk/x","jwt":"J"}""", HttpStatusCode.OK, json)
        })
        val openId = buildJsonObject { put("access_token", "tok"); put("matrix_server_name", "hs") }
        val a = SfuClient(http).token("https://jwt.example/", "!r:hs", openId, "DEV")
        assertEquals("https://jwt.example/sfu/get", path)
        assertEquals("!r:hs", body!!["room"]!!.jsonPrimitive.content)
        assertEquals("DEV", body!!["device_id"]!!.jsonPrimitive.content)
        assertEquals(openId, body!!["openid_token"])
        assertEquals(SfuAccess("wss://lk/x", "J"), a)
    }

    @Test
    fun `sfu get failure has the web app's message`() = runBlocking {
        val http = HttpClient(MockEngine { respond("nope", HttpStatusCode.InternalServerError) })
        val e = assertFailsWith<SfuException> { SfuClient(http).token("https://jwt.example", "!r", JsonObject(emptyMap()), "D") }
        assertEquals("سرور تماس در دسترس نیست (500)", e.message)
    }

    @Test
    fun `well-known rtc_foci`() = runBlocking {
        val http = HttpClient(MockEngine { req ->
            if (req.url.host == "hs.example") respond("""{"m.homeserver":{"base_url":"x"},"org.matrix.msc4143.rtc_foci":[{"type":"other"},{"type":"livekit","livekit_service_url":"https://lk-jwt.example"}]}""", HttpStatusCode.OK, json)
            else respond("{}", HttpStatusCode.NotFound)
        })
        assertEquals(listOf(LivekitFocus("https://lk-jwt.example")), SfuClient(http).wellKnownFoci("hs.example"))
        assertTrue(SfuClient(http).wellKnownFoci("nobody.example").isEmpty())
        // falls back to the homeserver's own base url
        assertEquals(1, SfuClient(http).wellKnownFoci("nobody.example", "https://hs.example/").size)
    }

    private fun api(h: MockRequestHandler) = MatrixClientServerApiClientImpl(Url("https://hs.example"), httpClientEngine = MockEngine(h))

    @Test
    fun `discovery prefers the homeserver's transports, then v1, then well-known`() = runBlocking {
        val seen = mutableListOf<String>()
        val api = api { req ->
            seen += req.url.encodedPath
            when {
                req.url.encodedPath.endsWith("org.matrix.msc4143/rtc/transports") -> respond("""{"rtc_transports":[{"type":"livekit","livekit_service_url":"https://from-hs"}]}""", HttpStatusCode.OK, json)
                else -> respond("{}", HttpStatusCode.NotFound, json)
            }
        }
        val d = SfuDiscovery(api, SfuClient(HttpClient(MockEngine { respond("{}", HttpStatusCode.NotFound) })), "hs.example")
        assertEquals("https://from-hs", d.ours()?.serviceUrl)
        d.ours()
        assertEquals(1, seen.size) // cached

        val v1 = api { req -> if (req.url.encodedPath == "/_matrix/client/v1/rtc/transports") respond("""{"transports":[{"type":"m.livekit","url":"https://v1"}]}""", HttpStatusCode.OK, json) else respond("{}", HttpStatusCode.NotFound, json) }
        assertEquals("https://v1", SfuDiscovery(v1, SfuClient(HttpClient(MockEngine { respond("{}", HttpStatusCode.NotFound) })), "hs.example").ours()?.serviceUrl)

        val none = api { respond("{}", HttpStatusCode.NotFound, json) }
        val wk = HttpClient(MockEngine { respond("""{"org.matrix.msc4143.rtc_foci":[{"type":"livekit","livekit_service_url":"https://wk"}]}""", HttpStatusCode.OK, json) })
        assertEquals("https://wk", SfuDiscovery(none, SfuClient(wk), "hs.example").ours()?.serviceUrl)
        assertNull(SfuDiscovery(none, SfuClient(HttpClient(MockEngine { respond("{}", HttpStatusCode.NotFound) })), "hs.example").ours())
    }

    @Test
    fun `openid token and turn servers`() = runBlocking {
        var openidBody = ""
        val api = api { req ->
            when {
                req.url.encodedPath == "/_matrix/client/v3/user/@u:hs.example/openid/request_token" -> { openidBody = (req.body as TextContent).text; respond("""{"access_token":"T","token_type":"Bearer","matrix_server_name":"hs.example","expires_in":3600}""", HttpStatusCode.OK, json) }
                req.url.encodedPath == "/_matrix/client/v3/voip/turnServer" -> respond("""{"username":"u","password":"p","uris":["turn:t.example:3478?transport=udp"],"ttl":86400}""", HttpStatusCode.OK, json)
                else -> respond("""{"errcode":"M_NOT_FOUND","error":"x"}""", HttpStatusCode.NotFound, json)
            }
        }
        val t = api.openIdToken(UserId("@u:hs.example"))
        assertEquals("T", t["access_token"]!!.jsonPrimitive.content)
        assertEquals("{}", openidBody)
        assertEquals(listOf(IceServer(listOf("turn:t.example:3478?transport=udp"), "u", "p")), api.turnServers())
        val none = api { respond("""{"errcode":"M_NOT_FOUND","error":"nope"}""", HttpStatusCode.NotFound, json) }
        val e = assertFailsWith<de.connect2x.trixnity.core.MatrixServerException> { none.turnServers() }
        assertEquals("M_NOT_FOUND", e.errcode)
    }
}

class TurnProviderTest {
    @Test
    fun `waits for the credentials, caches them, and gives none when the server has none or is slow`() = runBlocking {
        var calls = 0
        var clock = 0L
        val turn = IceServer(listOf("turn:t:3478"), "u", "p")
        val p = TurnProvider({ calls++; kotlinx.coroutines.delay(100); listOf(turn) }, timeoutMs = 2000, now = { clock }, cacheMs = 1000)
        assertEquals(listOf(turn), p.get()); assertEquals(listOf(turn), p.get()); assertEquals(1, calls)
        clock = 5000
        p.get(); assertEquals(2, calls) // expired
        val slow = TurnProvider({ kotlinx.coroutines.delay(10_000); listOf(turn) }, timeoutMs = 200)
        assertTrue(slow.get().isEmpty())
        val broken = TurnProvider({ error("no turn") }, timeoutMs = 200)
        assertTrue(broken.get().isEmpty())
    }
}
