package ir.panbeh.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.video.VideoFrameDecoder
import ir.panbeh.app.media.MxcFetcher
import ir.panbeh.app.media.MxcKeyer
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
class PanbehApp : Application(), SingletonImageLoader.Factory {
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
            oauthRedirect = "${MainActivity.OAUTH_SCHEME}:/oauth",
            oauthClientUri = "https://kotlin.app.panbeh.ir/",
        )
        accounts = Accounts(platform)
        state = AppState(this)
        state.boot()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(MxcKeyer())
            add(MxcFetcher.Factory())
            add(AnimatedImageDecoder.Factory())
            add(VideoFrameDecoder.Factory())
        }
        .build()

    companion object {
        lateinit var instance: PanbehApp
            private set
    }
}
