package dev.nitrate.ios

import androidx.compose.ui.window.ComposeUIViewController
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import dev.nitrate.mobile.ui.MobileApp
import okio.Path.Companion.toPath
import platform.UIKit.UIViewController

/** What the Swift app hosts: the phone app's shared screens (Compose Multiplatform). */
fun MainViewController(): UIViewController = ComposeUIViewController {
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components { add(KtorNetworkFetcherFactory()) }
            // Like Android's: a 512 MB disk cache, so artwork seen once is there next time.
            .diskCache { DiskCache.Builder().directory("${IosApp.container.platform.cacheDir}/images".toPath()).maxSizeBytes(512L * 1024 * 1024).build() }
            .crossfade(true)
            .build()
    }
    MobileApp(IosApp.container)
}
