package ir.panbeh.client

import de.connect2x.trixnity.client.CryptoDriverModule
import de.connect2x.trixnity.client.MediaStoreModule
import de.connect2x.trixnity.client.RepositoriesModule
import de.connect2x.trixnity.client.cryptodriver.vodozemac.vodozemac
import de.connect2x.trixnity.client.media.inMemory
import de.connect2x.trixnity.client.store.repository.inMemory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

/** The homeserver integration tests talk to, or null when PANBEH_TEST_HS isn't set. */
val testServer: String? = System.getenv("PANBEH_TEST_HS")?.takeIf { it.isNotBlank() }

/** Registers a fresh user (Synapse with open registration) and returns (localpart, password). */
fun register(prefix: String = "u"): Pair<String, String> {
    val user = prefix + UUID.randomUUID().toString().take(8)
    val pass = "pass-$user"
    val body = """{"username":"$user","password":"$pass","auth":{"type":"m.login.dummy"},"inhibit_login":true}"""
    val res = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI("$testServer/_matrix/client/v3/register"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString(),
    )
    check(res.statusCode() == 200) { "register failed: ${res.statusCode()} ${res.body()}" }
    return user to pass
}

/** In-memory Trixnity stores that survive closing a client (so restore() can reopen them). */
class TestStores : ClientStores {
    private val repos = mutableMapOf<String, RepositoriesModule>()
    private val medias = mutableMapOf<String, MediaStoreModule>()
    override fun repositories(userKey: String) = repos.getOrPut(userKey) { sticky(RepositoriesModule.inMemory()) }
    override fun media(userKey: String) = medias.getOrPut(userKey) { MediaStoreModule.inMemory() }
    override fun crypto(): CryptoDriverModule = CryptoDriverModule.vodozemac()
    override suspend fun delete(userKey: String) { repos.remove(userKey); medias.remove(userKey) }

    // the in-memory module builds new, empty repositories on every create(); keep the first ones
    private fun sticky(m: RepositoriesModule): RepositoriesModule {
        var cached: org.koin.core.module.Module? = null
        return RepositoriesModule { cached ?: m.create().also { cached = it } }
    }
}

fun testPlatform() = Platform(MemoryStore(), TestStores(), deviceName = "Panbeh Test")
