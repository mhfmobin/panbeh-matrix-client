package ir.panbeh.client

import de.connect2x.trixnity.client.CryptoDriverModule
import de.connect2x.trixnity.client.MediaStoreModule
import de.connect2x.trixnity.client.RepositoriesModule
import io.ktor.client.engine.HttpClientEngine

/** Small persistent string store (SharedPreferences on Android, a map in tests). */
interface KeyValueStore {
    operator fun get(key: String): String?
    operator fun set(key: String, value: String?)
}

class MemoryStore : KeyValueStore {
    private val map = mutableMapOf<String, String>()
    override fun get(key: String) = synchronized(map) { map[key] }
    override fun set(key: String, value: String?) {
        synchronized(map) { if (value == null) map.remove(key) else map[key] = value }
    }
}

/** What the platform supplies per account: Trixnity's stores and the crypto driver. */
interface ClientStores {
    fun repositories(userKey: String): RepositoriesModule
    fun media(userKey: String): MediaStoreModule
    fun crypto(): CryptoDriverModule
    /** Removes everything stored for the account (databases, media). */
    suspend fun delete(userKey: String)
}

class Platform(
    val store: KeyValueStore,
    val stores: ClientStores,
    val httpEngine: HttpClientEngine? = null,
    /** "Panbeh Android" */
    val deviceName: String = "Panbeh Android",
    /** OAuth redirect, e.g. ir.panbeh.app.kotlin:/oauth */
    val oauthRedirect: String = "ir.panbeh.app.kotlin:/oauth",
    val oauthClientUri: String = "https://kotlin.app.panbeh.ir/",
)
