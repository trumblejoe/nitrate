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
import dev.nitrate.testing.FakeServer
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
import dev.nitrate.mobile.ui.ManageItemScreen
import dev.nitrate.mobile.ui.ServerAdminScreen
import dev.nitrate.android.viewmodel.RowsViewModel
import dev.nitrate.core.pages.AccentMode
import dev.nitrate.core.pages.CustomPage
import dev.nitrate.core.pages.DisplayPrefs
import dev.nitrate.core.pages.ItemQuery
import dev.nitrate.core.pages.PageKeys
import dev.nitrate.core.pages.PageLayout
import dev.nitrate.core.pages.QuerySort
import dev.nitrate.core.pages.RowEntry
import dev.nitrate.core.pages.RowSource
import dev.nitrate.core.pages.WatchFilter
import dev.nitrate.mobile.ui.PageEditor
import dev.nitrate.mobile.ui.ProvideDisplayPrefs
import dev.nitrate.mobile.ui.RowBuilderDialog
import dev.nitrate.mobile.ui.SettingsScreen
import dev.nitrate.mobile.ui.PlayerLayout
import dev.nitrate.mobile.ui.WritingScreen
import dev.nitrate.mobile.ui.TransferDialog
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
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
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

    private fun shoot(
        name: String,
        downloads: DownloadsData = DownloadsData(),
        setup: suspend (AppContainer) -> Unit = {},
        content: @Composable (AppActions) -> Unit,
    ) {
        val container = AppContainer(compose.activity, "test", "0", serverOverride = FakeServer(), downloadStoreOverride = MemoryDownloadStore(downloads))
        kotlinx.coroutines.runBlocking {
            // Stored settings outlive a test in the same process: start each one clean.
            container.displaySettings.import("user-1", emptyMap())
            setup(container)
        }
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
                ProvideDisplayPrefs(container, "user-1") {
                    NitrateTheme {
                        Surface(color = MaterialTheme.colorScheme.background) {
                            val nav = rememberNavController()
                            content(AppActions(container, nav, play = { _, _ -> }))
                        }
                    }
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
        // Leaving any group stops its clock-sync loop, which would otherwise keep the next test busy.
        compose.runOnUiThread { container.syncPlay.handle(dev.nitrate.core.events.ServerEvent.SyncPlayGroupUpdate("", dev.nitrate.core.syncplay.SyncPlayUpdate.Left)) }
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

    /** The player's More sheet: speed, sleep timer, night mode, subtitle timing. */
    @Test
    fun playerMore() = shoot("phone-player-more") { actions ->
        val context = androidx.compose.ui.platform.LocalContext.current
        val session = androidx.compose.runtime.remember {
            dev.nitrate.android.playback.PlaybackSession(context, actions.server, actions.container.playbackSettings, deviceNoun = "This device", onEnded = {}, trackMemory = actions.container.trackMemory)
        }
        dev.nitrate.mobile.ui.PlayerMoreSheet(actions, session, sleepAt = null, sleepAtEnd = true, onSleep = { _, _ -> }, onPictureInPicture = {}, onPlayedElsewhere = {}, onDismiss = {})
    }

    /** The panel without its dialog: a dialog focuses the name field, whose blinking cursor never lets the test settle. */
    @Test fun watchTogether() = shoot("phone-watch-together") {
        androidx.compose.foundation.layout.Box(Modifier.padding(24.dp)) { dev.nitrate.mobile.ui.WatchTogetherPanel(it.container.syncPlay, "Joe's group") }
    }

    @Test fun watchTogetherInGroup() = shoot("phone-watch-together-group", setup = { c ->
        c.syncPlay.handle(dev.nitrate.core.events.ServerEvent.SyncPlayGroupUpdate("g1", dev.nitrate.core.syncplay.SyncPlayUpdate.Joined(dev.nitrate.core.syncplay.SyncPlayGroup("g1", "Friday movie night", dev.nitrate.core.syncplay.GroupState.PLAYING, listOf("Joe", "Sam", "Alex", "Alex")))))
    }) { dev.nitrate.mobile.ui.WatchTogetherDialog(it) {} }

    @Test fun profiles() = shoot("phone-profiles", setup = { c -> c.profiles.setPin("home|kids", null); c.profiles.setPin("home|sam", "1234") }) {
        dev.nitrate.mobile.ui.ProfilesScreen(it, onOpened = {}, onSignInAgain = {}, onAdd = {})
    }

    private val moviesLibrary = dev.nitrate.core.model.MediaItem("lib-movies", "Movies", dev.nitrate.core.model.ItemKind.LIBRARY, collection = dev.nitrate.core.model.CollectionKind.MOVIES)

    @Test fun browseGrid() = shoot("phone-browse-grid") { dev.nitrate.mobile.ui.BrowseLibrary(it, moviesLibrary) }

    @Test fun browseList() = shoot("phone-browse-list", setup = { c ->
        c.displaySettings.saveView("user-1", "browse/lib-movies", dev.nitrate.core.pages.BrowseOptions(view = dev.nitrate.core.pages.BrowseView.LIST, watched = dev.nitrate.core.pages.WatchFilter.UNWATCHED, genres = listOf("Drama")))
    }) { dev.nitrate.mobile.ui.BrowseLibrary(it, moviesLibrary) }

    @Test fun addToList() = shoot("phone-add-to-list", setup = { c -> c.refreshAccount() }) { a ->
        val film = androidx.compose.runtime.produceState<dev.nitrate.core.model.MediaItem?>(null) { value = a.server.getItem("m1") }.value
        if (film != null) dev.nitrate.mobile.ui.AddToListDialog(a, film) {}
    }

    @Test fun album() = shoot("phone-album") { DetailsScreen(it, "al1", null) }

    @Test fun remote() = shoot("phone-remote") { dev.nitrate.mobile.ui.RemoteScreen(it, "s1") }

    @Test
    fun transfer() = shoot("phone-writing-transfer") {
        TransferDialog(dev.nitrate.core.writing.TransferServer.State(running = true, address = "http://192.168.1.42:8765", pin = "482913"), onToggle = {}, onDismiss = {})
    }

    /** A Home the user has edited: a renamed row, a hidden one, and a row of their own. */
    private suspend fun customizeHome(c: AppContainer) {
        val horror = RowEntry("h1", RowSource.Query(ItemQuery(genres = listOf("Horror"), watched = WatchFilter.UNWATCHED, sort = QuerySort.RANDOM)), title = "Unwatched horror")
        c.displaySettings.saveLayout(
            "user-1", PageKeys.HOME,
            PageLayout(
                listOf(
                    RowEntry.builtIn("home/next_up"),
                    horror,
                    RowEntry.builtIn("home/continue_watching").copy(title = "Pick up where you left off"),
                    RowEntry.builtIn("home/latest/lib-movies").copy(hidden = true),
                ),
            ),
        )
        c.displaySettings.saveCustomPages("user-1", listOf(CustomPage("pg1", "Friday night"), CustomPage("pg2", "Kids", pinned = false)))
    }

    @Test fun homeEdited() = shoot("phone-home-edited", setup = ::customizeHome) { MainScreen(it) {} }

    @Test fun pageEditor() = shoot("phone-page-editor", setup = ::customizeHome) {
        val c = it.container
        val vm = androidx.lifecycle.viewmodel.compose.viewModel { RowsViewModel(PageKeys.HOME, c.server::homeRows, c.displaySettings, c.server) }
        PageEditor("Home", vm, it, null) {}
    }

    @Test fun rowBuilder() = shoot("phone-row-builder") { RowBuilderDialog(it, null, initial = null, initialTitle = null, onSave = { _, _ -> }, onDismiss = {}) }

    @Test fun displaySettings() = shoot("phone-display-settings", setup = { c ->
        c.displaySettings.savePrefs("user-1", DisplayPrefs(accent = AccentMode.TEAL, hideSpoilers = true, criticRating = false))
    }) { SettingsScreen(it, {}) }

    @Test fun spoilerFree() = shoot("phone-series-spoilers", setup = { c ->
        c.displaySettings.savePrefs("user-1", DisplayPrefs(hideSpoilers = true, communityRating = false, overview = false))
    }) { DetailsScreen(it, "series-1", null) }

    @Test fun manageDetails() = shoot("phone-manage-details") { ManageItemScreen(it, "m1") }

    @Test fun serverAdmin() = shoot("phone-server-admin") { ServerAdminScreen(it) }

    /** Writing beside the video: side by side when wide, stacked when upright. */
    @Test
    @Config(qualifiers = "w891dp-h411dp-land-night-xxhdpi")
    fun writingBesideVideo() = shoot("phone-player-writing-wide") { WritingBesideStill(it) }

    @Test
    fun writingBesideVideoUpright() = shoot("phone-player-writing-upright") { WritingBesideStill(it) }

    @Composable
    private fun WritingBesideStill(actions: AppActions) {
        PlayerLayout(writing = true, modifier = Modifier, pane = { WritingScreen(actions, onBack = {}, pane = true) }) {
            AsyncImage(actions.server.backdropUrl(MediaItem("m3", "", ItemKind.MOVIE)), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-night-mdpi")
    fun tabletHome() = shoot("tablet-home") { MainScreen(it) {} }

    @Test
    @Config(qualifiers = "w411dp-h891dp-notnight-xxhdpi")
    fun lightDetails() = shoot("phone-movie-light") { DetailsScreen(it, "m1", null) }
}
