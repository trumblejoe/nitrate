package dev.nitrate.tv

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import okio.Path.Companion.toPath
import coil3.request.crossfade
import dev.nitrate.android.AppContainer

class NitrateApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this, clientName = "Nitrate", version = BuildConfig.VERSION_NAME)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .crossfade(true)
            // Keep artwork across launches and screens: a quarter of the app's memory for decoded
            // pictures, and up to 512 MB on disk (Jellyfin's tagged image URLs never go stale).
            .memoryCache { coil3.memory.MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
            .diskCache {
                coil3.disk.DiskCache.Builder()
                    .directory(context.cacheDir.resolve("artwork").absolutePath.toPath())
                    .maxSizeBytes(512L * 1024 * 1024)
                    .build()
            }
            .build()
}
