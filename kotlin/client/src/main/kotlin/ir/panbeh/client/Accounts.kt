package ir.panbeh.client

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.create
import de.connect2x.trixnity.clientserverapi.client.MatrixClientAuthProviderData
import de.connect2x.trixnity.clientserverapi.client.MatrixClientServerApiClientImpl
import de.connect2x.trixnity.clientserverapi.client.classicLoginWithPassword
import de.connect2x.trixnity.clientserverapi.client.useApi
import de.connect2x.trixnity.clientserverapi.client.oauth2.OAuth2AuthorizationCodeLoginFlow
import de.connect2x.trixnity.clientserverapi.client.oauth2.OAuth2AuthorizationCodeLoginFlowImpl
import de.connect2x.trixnity.clientserverapi.model.authentication.IdentifierType
import de.connect2x.trixnity.clientserverapi.client.oauth2.ApplicationType
import de.connect2x.trixnity.clientserverapi.model.authentication.oauth2.ResponseMode
import io.ktor.http.Url
import ir.panbeh.core.text.normalizeServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A signed-in account. Tokens live in Trixnity's own store; this is only the index. */
@Serializable
data class SavedAccount(val userId: String, val baseUrl: String, val oauth: Boolean = false)

enum class LoginMode { OAUTH, PASSWORD }

private const val ACCOUNTS = "panbeh.sessions"
private const val ACTIVE = "panbeh.active"
private const val OAUTH_PENDING = "panbeh.oauthPending"

@Serializable
private data class PendingOAuth(val baseUrl: String, val clientId: String, val state: String, val codeVerifier: String)

/**
 * Several accounts, one running client at a time (like the web app): switching closes the current client and opens
 * the other one from its store.
 */
class Accounts(private val platform: Platform) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private val _client = MutableStateFlow<MatrixClient?>(null)
    /** The running client, null while signed out or switching. */
    val client: StateFlow<MatrixClient?> = _client.asStateFlow()

    val accounts: List<SavedAccount>
        get() = platform.store[ACCOUNTS]?.let { runCatching { json.decodeFromString<List<SavedAccount>>(it) }.getOrNull() } ?: emptyList()
    val active: SavedAccount? get() = accounts.firstOrNull { it.userId == platform.store[ACTIVE] } ?: accounts.firstOrNull()

    private fun saveAccounts(l: List<SavedAccount>) { platform.store[ACCOUNTS] = json.encodeToString(l) }

    private fun upsert(a: SavedAccount) {
        saveAccounts(accounts.filter { it.userId != a.userId } + a)
        platform.store[ACTIVE] = a.userId
    }

    /** Opens the active account from its store, if any. */
    suspend fun restore(): MatrixClient? = lock.withLock {
        _client.value?.let { return it }
        val a = active ?: return null
        open(a.userId, null).getOrThrow().also { _client.value = it }
    }

    private suspend fun open(userKey: String, auth: MatrixClientAuthProviderData?) = MatrixClient.create(
        repositoriesModule = platform.stores.repositories(userKey),
        mediaStoreModule = platform.stores.media(userKey),
        cryptoDriverModule = platform.stores.crypto(),
        authProviderData = auth,
    ) {
        name = userKey
        httpClientEngine = platform.httpEngine
    }

    /** "oauth" when the server has next-gen auth (MAS), else "password". Throws when the server can't be reached. */
    suspend fun loginMode(server: String): LoginMode {
        val base = Url(normalizeServer(server))
        return MatrixClientServerApiClientImpl(baseUrl = base, httpClientEngine = platform.httpEngine).use { api ->
            if (api.authentication.getOAuth2ServerMetadata().isSuccess) LoginMode.OAUTH
            else { api.server.getVersions().getOrThrow(); LoginMode.PASSWORD }
        }
    }

    suspend fun loginPassword(server: String, user: String, password: String): MatrixClient = lock.withLock {
        val base = Url(normalizeServer(server))
        val auth = MatrixClientAuthProviderData.classicLoginWithPassword(
            baseUrl = base,
            identifier = IdentifierType.User(user),
            password = password,
            initialDeviceDisplayName = platform.deviceName,
            httpClientEngine = platform.httpEngine,
        ).getOrThrow()
        addClient(base, auth, oauth = false)
    }

    private suspend fun addClient(base: Url, auth: MatrixClientAuthProviderData, oauth: Boolean): MatrixClient {
        val userId = auth.useApi(httpClientEngine = platform.httpEngine) { it.authentication.whoAmI() }.getOrThrow().userId.full
        closeCurrent()
        if (accounts.any { it.userId == userId }) {
            // already signed in here: end the new session, keep the old one and its keys
            runCatching { auth.useApi(httpClientEngine = platform.httpEngine) { it.authentication.logout() } }
            platform.store[ACTIVE] = userId
            return open(userId, null).getOrThrow().also { _client.value = it }
        }
        platform.stores.delete(userId) // leftovers of an earlier, unclean logout
        val c = open(userId, auth).getOrThrow()
        upsert(SavedAccount(userId, base.toString(), oauth))
        return c.also { _client.value = it }
    }

    /** Starts an OAuth login: returns the URL to open in the browser. */
    suspend fun startOAuth(server: String): String {
        val base = Url(normalizeServer(server))
        val req = flow(base, null).createAuthRequest().getOrThrow()
        platform.store[OAUTH_PENDING] = json.encodeToString(PendingOAuth(base.toString(), req.state.clientId, req.state.state, req.state.codeVerifier))
        return req.url
    }

    /** Finishes an OAuth login from the redirect URI (ir.panbeh.app.kotlin:/oauth?code=…&state=…). */
    suspend fun finishOAuth(redirect: String): MatrixClient = lock.withLock {
        val p = platform.store[OAUTH_PENDING]?.let { json.decodeFromString<PendingOAuth>(it) } ?: error("ورود ناتمام ماند")
        platform.store[OAUTH_PENDING] = null
        val base = Url(p.baseUrl)
        val auth = flow(base, OAuth2AuthorizationCodeLoginFlow.AuthRequestData.State(p.clientId, p.state, p.codeVerifier))
            .onCallback(Url(redirect)).getOrThrow()
        addClient(base, auth, oauth = true)
    }

    private fun flow(base: Url, state: OAuth2AuthorizationCodeLoginFlow.AuthRequestData.State?) = OAuth2AuthorizationCodeLoginFlowImpl(
        baseUrl = base,
        applicationType = ApplicationType.Native,
        clientUri = platform.oauthClientUri,
        redirectUri = platform.oauthRedirect,
        responseMode = ResponseMode.Query,
        initialState = state,
        httpClientEngine = platform.httpEngine,
    )

    suspend fun switchTo(userId: String): MatrixClient = lock.withLock {
        closeCurrent()
        platform.store[ACTIVE] = userId
        open(userId, null).getOrThrow().also { _client.value = it }
    }

    private suspend fun closeCurrent() {
        _client.value?.let { it.stopSync(); it.closeSuspending() }
        _client.value = null
    }

    /** Signs the active account out (server and local data). Returns the next account's client, if any. */
    suspend fun logout(): MatrixClient? = lock.withLock {
        val c = _client.value ?: return null
        val id = c.userId.full
        runCatching { c.logout() }
        closeCurrent()
        forget(id)
        accounts.firstOrNull()?.let { a -> platform.store[ACTIVE] = a.userId; open(a.userId, null).getOrThrow().also { _client.value = it } }
    }

    /** Local cleanup after a logout or when the server said our token is gone. */
    suspend fun forget(userId: String) {
        saveAccounts(accounts.filter { it.userId != userId })
        if (platform.store[ACTIVE] == userId) platform.store[ACTIVE] = accounts.firstOrNull()?.userId
        platform.stores.delete(userId)
    }

    suspend fun logoutAll() {
        while (_client.value != null || active != null) {
            if (_client.value == null) restore()
            logout()
        }
    }
}
