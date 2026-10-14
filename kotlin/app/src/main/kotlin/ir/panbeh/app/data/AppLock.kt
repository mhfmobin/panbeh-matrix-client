package ir.panbeh.app.data

import ir.panbeh.app.PanbehApp
import ir.panbeh.core.util.hashPin
import ir.panbeh.core.util.lockDelay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/** after: minutes away before it locks again (0 = as soon as the app is left). len: the PIN's length. */
@Serializable
data class LockConf(val salt: String, val hash: String, val iter: Int, val len: Int, val after: Int = 5, val bio: Boolean = false)

@Serializable
private data class Fails(val n: Int = 0, val last: Long = 0)

/**
 * App lock (lock.ts): a PIN, and optionally a fingerprint, in front of the UI. It hides the app; it doesn't encrypt
 * storage. Device-wide, not per account.
 */
object AppLock {
    private const val KEY = "panbeh.lock"
    private const val FAILS = "panbeh.lockFails"
    // a 4-digit PIN can't resist an offline guess whatever the iterations; this only has to keep unlocking quick
    private const val ITER = 100_000
    const val MIN = 4
    const val MAX = 8
    private val store get() = PanbehApp.instance.platform.store
    private val json = Json { ignoreUnknownKeys = true }

    val conf: LockConf? get() = store[KEY]?.let { runCatching { json.decodeFromString<LockConf>(it) }.getOrNull() }
    private fun save(c: LockConf?) { store[KEY] = c?.let { json.encodeToString(it) } }

    private val _locked = MutableStateFlow(conf != null) // starting up locks
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var hiddenAt = 0L
    fun onHidden() {
        val c = conf ?: return
        hiddenAt = System.currentTimeMillis()
        if (c.after == 0) _locked.value = true
    }
    fun onShown() {
        val c = conf ?: return
        if (hiddenAt != 0L && System.currentTimeMillis() - hiddenAt >= c.after * 60_000L) _locked.value = true
    }

    private val fails: Fails get() = store[FAILS]?.let { runCatching { json.decodeFromString<Fails>(it) }.getOrNull() } ?: Fails()
    /** ms until the next try is allowed */
    fun waitLeft(): Long = fails.let { maxOf(0, it.last + lockDelay(it.n) - System.currentTimeMillis()) }

    /** Whether [pin] is the PIN; wrong ones count toward the wait. */
    suspend fun checkPin(pin: String): Boolean {
        val c = conf ?: return true
        if (waitLeft() > 0) return false
        val ok = withContext(Dispatchers.Default) { hashPin(pin, c.salt, c.iter) == c.hash }
        if (ok) store[FAILS] = null
        else fails.let { store[FAILS] = json.encodeToString(Fails(it.n + 1, System.currentTimeMillis())) }
        return ok
    }

    suspend fun unlock(pin: String) = checkPin(pin).also { if (it) _locked.value = false }
    fun unlockedByBiometric() { store[FAILS] = null; _locked.value = false }

    /** Sets (or changes) the PIN; other settings stay. */
    suspend fun setPin(pin: String) {
        val c = conf
        val salt = UUID.randomUUID().toString()
        val hash = withContext(Dispatchers.Default) { hashPin(pin, salt, ITER) }
        save(LockConf(salt, hash, ITER, pin.length, c?.after ?: 5, c?.bio ?: false))
    }
    fun setOptions(after: Int? = null, bio: Boolean? = null) {
        val c = conf ?: return
        save(c.copy(after = after ?: c.after, bio = bio ?: c.bio))
    }
    /** keepLocked: the PIN is forgotten and every account is being signed out; the app stays covered meanwhile. */
    fun remove(keepLocked: Boolean = false) {
        save(null); store[FAILS] = null
        if (!keepLocked) _locked.value = false
    }
}
