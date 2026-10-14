package ir.panbeh.client.calls

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.create
import de.connect2x.trixnity.client.room
import de.connect2x.trixnity.clientserverapi.client.MatrixClientAuthProviderData
import de.connect2x.trixnity.clientserverapi.client.classicLoginWithPassword
import de.connect2x.trixnity.clientserverapi.model.authentication.IdentifierType
import de.connect2x.trixnity.clientserverapi.model.room.CreateRoom
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.events.InitialStateEvent
import de.connect2x.trixnity.core.model.events.m.DirectEventContent
import de.connect2x.trixnity.core.model.events.m.room.EncryptionEventContent
import de.connect2x.trixnity.core.model.events.m.room.Membership
import de.connect2x.trixnity.core.serialization.events.EventContentSerializerMappings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import ir.panbeh.client.events.panbehMappings
import ir.panbeh.client.register
import ir.panbeh.client.testPlatform
import ir.panbeh.client.testServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.SharingStarted
import org.koin.dsl.module

/** One user of a two-user call test: a syncing client plus its MatrixRTC. */
class Peer(val client: MatrixClient, val scope: CoroutineScope, val rtc: MatrixRtc) {
    val events get() = rtc.events
    suspend fun close() { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel(); client.closeSuspending() }
}

val mockSfu = HttpClient(MockEngine { req ->
    val json = headersOf(HttpHeaders.ContentType, "application/json")
    when {
        req.url.encodedPath.endsWith("/sfu/get") -> respond("""{"url":"wss://lk.example/livekit","jwt":"jwt-for-test"}""", HttpStatusCode.OK, json)
        req.url.encodedPath.endsWith("/.well-known/matrix/client") -> respond("""{"org.matrix.msc4143.rtc_foci":[{"type":"livekit","livekit_service_url":"https://jwt.example"}]}""", HttpStatusCode.OK, json)
        else -> respond("{}", HttpStatusCode.NotFound, json)
    }
})

suspend fun login(prefix: String): MatrixClient = register(prefix).let { (u, p) -> loginAs(u, p) }

/** A new device for an existing user. */
suspend fun loginAs(u: String, p: String): MatrixClient {
    val platform = testPlatform()
    val auth = MatrixClientAuthProviderData.classicLoginWithPassword(
        baseUrl = Url(testServer!!), identifier = IdentifierType.User(u), password = p,
        initialDeviceDisplayName = "Panbeh Test", httpClientEngine = platform.httpEngine,
    ).getOrThrow()
    val c = MatrixClient.create(
        repositoriesModule = platform.stores.repositories(u), mediaStoreModule = platform.stores.media(u),
        cryptoDriverModule = platform.stores.crypto(), authProviderData = auth,
    ) {
        name = u
        httpClientEngine = platform.httpEngine
        modulesFactories += { module { single<EventContentSerializerMappings> { panbehMappings + callMappings } } }
    }.getOrThrow()
    c.startSync()
    withTimeout(30_000) { c.initialSyncDone.first { it } }
    return c
}

fun peer(client: MatrixClient, config: RtcConfig = RtcConfig(), sfu: HttpClient = mockSfu): Peer {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val events = client.callEvents().shareIn(scope, SharingStarted.Eagerly, 0)
    return Peer(client, scope, MatrixRtc(client, scope, events, SfuClient(sfu), config))
}

/** A room both users are in (invite + join), direct or a group, encrypted or not. */
suspend fun roomOf(a: MatrixClient, b: MatrixClient, direct: Boolean = true, encrypted: Boolean = false): RoomId {
    val id = a.api.room.createRoom(
        invite = setOf(b.userId), isDirect = direct,
        preset = if (direct) CreateRoom.Request.Preset.TRUSTED_PRIVATE else CreateRoom.Request.Preset.PRIVATE,
        initialState = if (encrypted) listOf(InitialStateEvent(EncryptionEventContent(), "")) else emptyList(),
    ).getOrThrow()
    withTimeout(30_000) { b.room.getById(id).filterNotNull().first { it.membership == Membership.INVITE } }
    b.api.room.joinRoom(id).getOrThrow()
    withTimeout(30_000) { a.room.getById(id).filterNotNull().first { true } }
    withTimeout(30_000) { b.room.getById(id).filterNotNull().first { it.membership == Membership.JOIN } }
    if (direct) {
        // m.direct is what makes a room a DM; Trixnity fills it in a moment after the invite, so don't wait for that
        a.api.user.setAccountData(DirectEventContent(mapOf(b.userId to setOf(id))), a.userId)
        b.api.user.setAccountData(DirectEventContent(mapOf(a.userId to setOf(id))), b.userId)
        withTimeout(30_000) { a.room.getById(id).filterNotNull().first { it.isDirect }; b.room.getById(id).filterNotNull().first { it.isDirect } }
    }
    return id
}

/** Polls until [cond] holds (the flows these tests watch are updated by sync). */
suspend fun until(timeoutMs: Long = 30_000, cond: suspend () -> Boolean) {
    withTimeout(timeoutMs) { while (!cond()) kotlinx.coroutines.delay(25) }
}

class FakePeer(val events: LegacyPeerEvents, val video: Boolean) : LegacyPeer {
    override val localStreamId: String? = "stream-${System.nanoTime()}"
    var remoteOffer: String? = null
    var remoteAnswer: String? = null
    val remoteCandidates = java.util.concurrent.CopyOnWriteArrayList<IceCandidate>()
    var closed = false
    var failMedia = false
    override suspend fun createOffer(video: Boolean): String { if (failMedia) error("no mic"); return "v=0\r\nm=audio 9\r\n" + (if (video) "m=video 9\r\n" else "") + "a=offer" }
    override suspend fun setRemoteOffer(sdp: String) { remoteOffer = sdp }
    override suspend fun createAnswer(video: Boolean): String { if (failMedia) error("no mic"); return "v=0\r\nm=audio 9\r\na=answer" }
    override suspend fun setRemoteAnswer(sdp: String) { remoteAnswer = sdp }
    override suspend fun createRenegotiationOffer() = "v=0\r\na=reneg"
    override suspend fun addIceCandidate(c: IceCandidate) { remoteCandidates += c }
    override suspend fun restartIce() {}
    override suspend fun iceReport() = "TURN: no | local: host | remote: - | ICE: checking"
    override fun close() { closed = true }
}

class FakePeers : LegacyPeerFactory {
    val peers = java.util.concurrent.CopyOnWriteArrayList<FakePeer>()
    var failMedia = false
    var lastIce: List<IceServer>? = null
    override suspend fun create(iceServers: List<IceServer>, events: LegacyPeerEvents): LegacyPeer {
        lastIce = iceServers
        return FakePeer(events, false).also { it.failMedia = failMedia; peers += it }
    }
}

fun legacyOf(p: Peer, peers: FakePeers, enabled: Boolean = true, config: LegacyConfig = LegacyConfig(candidateDelayMs = 20)) =
    LegacyCalls(p.client, p.scope, p.events, peers, { enabled }, config).also { it.start() }
