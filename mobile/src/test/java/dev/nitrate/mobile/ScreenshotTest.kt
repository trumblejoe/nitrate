package dev.nitrate.mobile

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.nitrate.android.AppContainer
import dev.nitrate.mobile.ui.AppActions
import dev.nitrate.mobile.ui.ConnectScreen
import dev.nitrate.core.downloads.DownloadQuality
import dev.nitrate.core.downloads.DownloadRecord
import dev.nitrate.core.downloads.DownloadState
import dev.nitrate.core.downloads.DownloadsData
import dev.nitrate.core.downloads.MemoryDownloadStore
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.UserState
import dev.nitrate.mobile.ui.DetailsScreen
import dev.nitrate.mobile.ui.DownloadsScreen
import dev.nitrate.mobile.ui.SearchScreen
import dev.nitrate.mobile.ui.GestureFeedback
import dev.nitrate.mobile.ui.GestureFeedbackView
import dev.nitrate.mobile.ui.TrickplayPreview
import dev.nitrate.mobile.ui.UpNextCard
import dev.nitrate.core.model.TrickplayInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.imageDecoderEnabled
import kotlinx.coroutines.Dispatchers
import dev.nitrate.mobile.ui.LoginScreen
import dev.nitrate.mobile.ui.MainScreen
import dev.nitrate.mobile.ui.NitrateTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the main phone/tablet screens against [FakeServer] so layouts can be checked without a
 * device. PNGs land in build/outputs/roborazzi. Images are blank placeholders (no network).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-night-xxhdpi", application = android.app.Application::class)
class ScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shoot(name: String, downloads: DownloadsData = DownloadsData(), content: @Composable (AppActions) -> Unit) {
        val container = AppContainer(compose.activity, "test", "0", serverOverride = FakeServer(), downloadStoreOverride = MemoryDownloadStore(downloads))
        // Load artwork without background threads so it's on screen when the picture is taken.
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(compose.activity)
                .coroutineContext(Dispatchers.Unconfined)
                .fetcherCoroutineContext(Dispatchers.Unconfined)
                .decoderCoroutineContext(Dispatchers.Unconfined)
                // Robolectric has no ImageDecoder; BitmapFactory works.
                .imageDecoderEnabled(false)
                .build(),
        )
        compose.setContent {
            NitrateTheme {
                // Same root as the app: background and text colours come from this Surface.
                Surface(color = MaterialTheme.colorScheme.background) {
                    val nav = rememberNavController()
                    content(AppActions(container, nav, play = { _, _ -> }))
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_500)
        // Artwork colours are worked out off the main thread; let them land, then settle animations.
        Thread.sleep(400)
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun connect() = shoot("phone-connect") { ConnectScreen(it) { _, _ -> } }

    @Test fun login() = shoot("phone-login") { LoginScreen(it, "http://192.168.1.20:8096", "Home") {} }

    @Test fun home() = shoot("phone-home") { MainScreen(it) {} }

    @Test fun details() = shoot("phone-series") { DetailsScreen(it, "series-1", null) }

    @Test fun movie() = shoot("phone-movie") { DetailsScreen(it, "m1", null) }

    @Test fun search() = shoot("phone-search") { SearchScreen(it, initialQuery = "re", autoFocus = false) }

    @Test
    fun downloads() = shoot("phone-downloads", sampleDownloads()) { DownloadsScreen(it) }

    private fun sampleDownloads(): DownloadsData {
        val server = FakeServer()
        fun record(item: MediaItem, quality: DownloadQuality, state: DownloadState, bytes: Long, total: Long, error: String? = null) =
            DownloadRecord(item.id, server.serverUrl, server.userId, item, quality, state = state, bytesDownloaded = bytes, totalBytes = total, error = error, createdAtMs = bytes)
        val runtime = 42 * 60_000L
        fun episode(n: Int, watched: Boolean = false, positionMs: Long = 0) = MediaItem(
            "s1e$n", "Episode title $n", ItemKind.EPISODE, seriesName = "The Night Reel", seasonNumber = 1, indexNumber = n, runtimeMs = runtime,
            user = UserState(played = watched, positionMs = positionMs),
        )
        return DownloadsData(
            records = listOf(
                record(episode(4), DownloadQuality.MEDIUM, DownloadState.DOWNLOADING, 412_000_000, 1_310_000_000),
                record(episode(5), DownloadQuality.MEDIUM, DownloadState.FAILED, 0, 1_310_000_000, "Your account isn't allowed to download from this server"),
                record(MediaItem("m1", "Nitrate", ItemKind.MOVIE, runtimeMs = 7_000_000), DownloadQuality.ORIGINAL, DownloadState.COMPLETED, 6_400_000_000, 6_400_000_000),
                record(episode(1, watched = true), DownloadQuality.LOW, DownloadState.COMPLETED, 480_000_000, 480_000_000),
                record(episode(2, positionMs = 754_000).let { it }, DownloadQuality.LOW, DownloadState.COMPLETED, 470_000_000, 470_000_000).copy(pendingSync = true),
            ),
        )
    }

    /** The player's overlays over a still: seek feedback, scrubbing preview and Up Next. */
    @Test
    @Config(qualifiers = "w891dp-h411dp-land-night-xxhdpi")
    fun playerOverlays() = shoot("phone-player-overlays") { actions ->
        val server = actions.server
        val next = MediaItem("s2e5", "Episode title 5", ItemKind.EPISODE, seriesId = "series-1", seriesName = "The Night Reel", seasonNumber = 2, indexNumber = 5)
        val info = TrickplayInfo(320, 180, 2, 2, 4, 10_000)
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(server.backdropUrl(MediaItem("series-1", "", ItemKind.SERIES)), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            GestureFeedbackView(GestureFeedback.Seek(-20_000))
            TrickplayPreview(server, "s2e4", info, positionMs = 21_000, x = 900f, barTop = 1000f)
            UpNextCard(next, server, seconds = 10, onPlay = {}, onHide = {})
        }
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-night-mdpi")
    fun tabletHome() = shoot("tablet-home") { MainScreen(it) {} }

    @Test
    @Config(qualifiers = "w411dp-h891dp-notnight-xxhdpi")
    fun lightDetails() = shoot("phone-movie-light") { DetailsScreen(it, "m1", null) }
}
