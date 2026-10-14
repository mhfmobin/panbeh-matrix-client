package ir.panbeh.app.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import de.connect2x.trixnity.client.CryptoDriverModule
import de.connect2x.trixnity.client.MediaStoreModule
import de.connect2x.trixnity.client.RepositoriesModule
import de.connect2x.trixnity.client.cryptodriver.vodozemac.vodozemac
import de.connect2x.trixnity.client.media.okio.okio
import de.connect2x.trixnity.client.store.repository.room.TrixnityRoomDatabase
import de.connect2x.trixnity.client.store.repository.room.room
import ir.panbeh.client.ClientStores
import ir.panbeh.client.KeyValueStore
import kotlinx.coroutines.Dispatchers
import okio.Path.Companion.toOkioPath
import java.io.File

/** Per-account Trixnity database (Room) and media folder, keyed by a file-safe form of the user id. */
class AndroidStores(private val context: Context) : ClientStores {
    private fun key(userKey: String) = userKey.replace(Regex("[^A-Za-z0-9._-]"), "_")
    private fun dbFile(userKey: String) = context.getDatabasePath("trixnity-${key(userKey)}.db")
    private fun mediaDir(userKey: String) = File(context.filesDir, "media/${key(userKey)}")

    override fun repositories(userKey: String) = RepositoriesModule.room(
        Room.databaseBuilder<TrixnityRoomDatabase>(context, dbFile(userKey).absolutePath)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
    )

    override fun media(userKey: String) = MediaStoreModule.okio(mediaDir(userKey).also { it.mkdirs() }.toOkioPath())

    override fun crypto() = CryptoDriverModule.vodozemac()

    override suspend fun delete(userKey: String) {
        context.deleteDatabase(dbFile(userKey).name)
        mediaDir(userKey).deleteRecursively()
    }
}

/** SharedPreferences "panbeh" as the client's key-value store. */
class PrefsStore(context: Context) : KeyValueStore {
    private val sp = context.getSharedPreferences("panbeh", Context.MODE_PRIVATE)
    override fun get(key: String): String? = sp.getString(key, null)
    override fun set(key: String, value: String?) {
        sp.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}
