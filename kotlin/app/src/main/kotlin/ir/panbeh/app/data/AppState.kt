package ir.panbeh.app.data

import de.connect2x.trixnity.client.MatrixClient
import ir.panbeh.app.PanbehApp
import ir.panbeh.core.prefs.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface Phase {
    data object Boot : Phase
    /** No account (or adding one): the login screen. */
    data object Login : Phase
    data class Ready(val client: MatrixClient) : Phase
    data class Error(val message: String) : Phase
}

/** App-wide UI state: which phase we're in, the preferences, and links waiting for the first sync. */
class AppState(private val app: PanbehApp) {
    private val store get() = app.platform.store

    private val _prefs = MutableStateFlow(Prefs.decode(store["panbeh.prefs"]))
    val prefs: StateFlow<Prefs> = _prefs.asStateFlow()
    fun setPrefs(f: (Prefs) -> Prefs) {
        _prefs.update(f)
        store["panbeh.prefs"] = _prefs.value.encode()
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Boot)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** an OAuth login that came back with an error, shown on the login screen */
    val loginError = MutableStateFlow<String?>(null)

    /** true while the login screen was opened to add another account (it can be cancelled). */
    val adding = MutableStateFlow(false)

    /** matrix:/matrix.to link or room to open once the client is ready */
    val pendingLink = MutableStateFlow<String?>(null)
    val pendingRoom = MutableStateFlow<String?>(null)

    fun boot() = app.scope.launch {
        _phase.value = Phase.Boot
        _phase.value = runCatching { app.accounts.restore() }.fold(
            onSuccess = { c -> if (c == null) Phase.Login else start(c) },
            onFailure = { Phase.Error(it.message ?: it.toString()) },
        )
    }

    // SyncControl starts and stops the sync for whichever client is current
    private fun start(c: MatrixClient): Phase = Phase.Ready(c)

    /** After a successful login (or account switch). */
    fun ready(c: MatrixClient) = app.scope.launch {
        adding.value = false
        _phase.value = start(c)
    }

    fun addAccount() { adding.value = true; _phase.value = Phase.Login }
    fun cancelAdd() { adding.value = false; boot() }

    fun switchAccount(userId: String) = app.scope.launch {
        _phase.value = Phase.Boot
        _phase.value = runCatching { start(app.accounts.switchTo(userId)) }.getOrElse { Phase.Error(it.message ?: it.toString()) }
    }

    fun logout() = app.scope.launch {
        _phase.value = Phase.Boot
        ir.panbeh.app.notify.Notifier.cancelAll(app)
        val next = runCatching { app.accounts.logout() }.getOrNull()
        _phase.value = if (next == null) Phase.Login else start(next)
    }
}
