package dev.nitrate.mobile

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.crossfade
import dev.nitrate.android.AppContainer

class NitrateApp : Application(), SingletonImageLoader.Factory, AppContainer.Owner {
    override lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this, clientName = "Nitrate Mobile", version = BuildConfig.VERSION_NAME)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context).crossfade(true).build()
}
