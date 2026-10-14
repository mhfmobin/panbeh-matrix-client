package ir.panbeh.app

import android.app.Application
import io.ktor.client.engine.okhttp.OkHttp
import ir.panbeh.app.data.AndroidStores
import ir.panbeh.app.data.AppState
import ir.panbeh.app.data.PrefsStore
import ir.panbeh.client.Accounts
import ir.panbeh.client.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** App-wide singletons (hand-rolled DI). */
class PanbehApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var platform: Platform
        private set
    lateinit var accounts: Accounts
        private set
    lateinit var state: AppState
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        platform = Platform(
            store = PrefsStore(this),
            stores = AndroidStores(this),
            httpEngine = OkHttp.create(),
            deviceName = "Panbeh Android",
        )
        accounts = Accounts(platform)
        state = AppState(this)
        state.boot()
    }

    companion object {
        lateinit var instance: PanbehApp
            private set
    }
}
