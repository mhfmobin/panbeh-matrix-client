package ir.panbeh.client.calls

import de.connect2x.trixnity.clientserverapi.client.MatrixClientServerApiClient
import de.connect2x.trixnity.core.model.UserId
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.appendPathSegments
import io.ktor.http.isSuccess
import io.ktor.client.request.request as ktorRequest
import kotlinx.serialization.json.*
import ir.panbeh.core.util.string

// Trixnity has no openid / turnServer endpoints, and its /rtc/transports model doesn't match what servers send: raw requests
// on its authenticated HTTP client (it resolves relative paths against the homeserver and refreshes tokens).

/** A homeserver answered a call-related request with an error. */
class CallHttpException(val status: Int, val errcode: String?, message: String) : RuntimeException(message)

private val rawJson = Json { ignoreUnknownKeys = true }

internal suspend fun MatrixClientServerApiClient.rawGet(vararg path: String) = raw(io.ktor.http.HttpMethod.Get, *path)

private suspend fun MatrixClientServerApiClient.raw(method: io.ktor.http.HttpMethod, vararg path: String, body: JsonObject? = null): JsonObject {
    val r = baseClient.baseClient.ktorRequest {
        this.method = method
        url { appendPathSegments(*path) }
        if (body != null) { header(HttpHeaders.ContentType, ContentType.Application.Json.toString()); setBody(body.toString()) }
    }
    val o = runCatching { rawJson.parseToJsonElement(r.bodyAsText()).jsonObject }.getOrDefault(JsonObject(emptyMap()))
    if (!r.status.isSuccess()) throw CallHttpException(r.status.value, o.string("errcode"), o.string("error") ?: "HTTP ${r.status.value}")
    return o
}

/** An OpenID token (user/{id}/openid/request_token), passed as is to lk-jwt-service. */
suspend fun MatrixClientServerApiClient.openIdToken(userId: UserId): JsonObject =
    raw(io.ktor.http.HttpMethod.Post, "_matrix", "client", "v3", "user", userId.full, "openid", "request_token", body = JsonObject(emptyMap()))

/** One ICE server for the legacy peer connection. */
data class IceServer(val urls: List<String>, val username: String? = null, val credential: String? = null)

/** TURN credentials from the homeserver (/voip/turnServer); empty when it has none. */
suspend fun MatrixClientServerApiClient.turnServers(): List<IceServer> {
    val o = raw(io.ktor.http.HttpMethod.Get, "_matrix", "client", "v3", "voip", "turnServer")
    val urls = (o["uris"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
    if (urls.isEmpty()) return emptyList()
    return listOf(IceServer(urls, o.string("username"), o.string("password")))
}

/** The LiveKit entries of an MSC4143 transport list (`livekit` / `m.livekit`, `livekit_service_url` / `url`). */
fun livekitTransports(list: JsonElement?): List<LivekitFocus> = (list as? JsonArray)?.mapNotNull(LivekitFocus::parse).orEmpty()

/** The SFU access lk-jwt-service hands out: the LiveKit websocket [url] and the [jwt] to connect with. */
data class SfuAccess(val url: String, val jwt: String)

/**
 * Where our homeserver's SFU is, and how to get a token for it. [http] must not add Matrix auth (it talks to lk-jwt-service
 * and to .well-known on other hosts).
 */
class SfuClient(private val http: HttpClient, private val json: Json = Json { ignoreUnknownKeys = true }) {
    /** `.well-known/matrix/client` of [domain] (https), then of [homeserver] as a fallback. */
    suspend fun wellKnownFoci(domain: String, homeserver: String? = null): List<LivekitFocus> {
        for (base in listOfNotNull("https://$domain", homeserver?.trimEnd('/'))) {
            val body = runCatching {
                val r = http.get("$base/.well-known/matrix/client")
                if (r.status.isSuccess()) json.parseToJsonElement(r.bodyAsText()).jsonObject else null
            }.getOrNull() ?: continue
            val foci = livekitTransports(body["org.matrix.msc4143.rtc_foci"])
            if (foci.isNotEmpty()) return foci
        }
        return emptyList()
    }

    /** POST {serviceUrl}/sfu/get: names us "@user:server:DEVICE" and returns the LiveKit url and jwt for [roomId]. */
    suspend fun token(serviceUrl: String, roomId: String, openId: JsonObject, deviceId: String): SfuAccess {
        val r = http.post(serviceUrl.trimEnd('/') + "/sfu/get") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(buildJsonObject { put("room", roomId); put("openid_token", openId); put("device_id", deviceId) }.toString())
        }
        if (!r.status.isSuccess()) throw SfuException("سرور تماس در دسترس نیست (${r.status.value})")
        val o = json.parseToJsonElement(r.bodyAsText()).jsonObject
        return SfuAccess(o.string("url") ?: throw SfuException("سرور تماس در دسترس نیست"), o.string("jwt") ?: throw SfuException("سرور تماس در دسترس نیست"))
    }
}

class SfuException(message: String) : RuntimeException(message)

/** Our homeserver's LiveKit: MSC4143 /rtc/transports, else .well-known rtc_foci (any server). Cached after the first answer. */
class SfuDiscovery(private val api: MatrixClientServerApiClient, private val sfu: SfuClient, private val domain: String) {
    @Volatile private var cached: LivekitFocus? = null

    suspend fun ours(): LivekitFocus? {
        cached?.let { return it }
        val fromHs = fromHomeserver()
        val found = fromHs.firstOrNull() ?: sfu.wellKnownFoci(domain, api.baseUrl.toString()).firstOrNull()
        if (found != null) cached = found
        return found
    }

    private suspend fun fromHomeserver(): List<LivekitFocus> {
        for (path in listOf(listOf("unstable", "org.matrix.msc4143"), listOf("v1"))) {
            val o = runCatching { api.rawGet("_matrix", "client", *path.toTypedArray(), "rtc", "transports") }.getOrNull() ?: continue
            val l = livekitTransports(o["rtc_transports"] ?: o["transports"])
            if (l.isNotEmpty()) return l
        }
        return emptyList()
    }
}
