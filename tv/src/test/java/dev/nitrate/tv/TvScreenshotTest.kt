package dev.nitrate.tv

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.MaterialTheme
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.imageDecoderEnabled
import com.github.takahirom.roborazzi.captureRoboImage
import dev.nitrate.android.AppContainer
import dev.nitrate.android.viewmodel.RowsViewModel
import dev.nitrate.core.pages.CustomPage
import dev.nitrate.core.pages.DisplayPrefs
import dev.nitrate.core.pages.ItemQuery
import dev.nitrate.core.pages.PageKeys
import dev.nitrate.core.pages.PageLayout
import dev.nitrate.core.pages.QuerySort
import dev.nitrate.core.pages.RowEntry
import dev.nitrate.core.pages.RowSource
import dev.nitrate.core.pages.WatchFilter
import dev.nitrate.testing.FakeServer
import dev.nitrate.tv.ui.components.ProvideDisplayPrefs
import dev.nitrate.tv.ui.details.DetailsScreen
import dev.nitrate.tv.ui.home.HomeScreen
import dev.nitrate.tv.ui.pages.DisplaySettingsScreen
import dev.nitrate.tv.ui.rows.PageEditor
import dev.nitrate.tv.ui.rows.RowBuilder
import dev.nitrate.tv.ui.theme.NitrateTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** TV screens against [FakeServer]; PNGs land in tv/build/outputs/roborazzi. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi", application = android.app.Application::class)
class TvScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shoot(name: String, setup: suspend (AppContainer) -> Unit = {}, content: @Composable (AppContainer) -> Unit) {
        val container = AppContainer(compose.activity, "test", "0", serverOverride = FakeServer())
        runBlocking {
            // Stored settings outlive a test in the same process: start each one clean.
            container.displaySettings.import("user-1", emptyMap())
            setup(container)
        }
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(compose.activity)
                .coroutineContext(Dispatchers.Unconfined)
                .fetcherCoroutineContext(Dispatchers.Unconfined)
                .decoderCoroutineContext(Dispatchers.Unconfined)
                .imageDecoderEnabled(false)
                .build(),
        )
        compose.setContent {
            ProvideDisplayPrefs(container, "user-1") {
                NitrateTheme {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content(container) }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_500)
        Thread.sleep(300)
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private suspend fun customize(c: AppContainer) {
        c.displaySettings.saveLayout(
            "user-1", PageKeys.HOME,
            PageLayout(
                listOf(
                    RowEntry.builtIn("home/next_up"),
                    RowEntry("h1", RowSource.Query(ItemQuery(genres = listOf("Horror"), watched = WatchFilter.UNWATCHED, sort = QuerySort.RANDOM)), title = "Unwatched horror"),
                    RowEntry.builtIn("home/latest/lib-movies").copy(hidden = true),
                ),
            ),
        )
        c.displaySettings.saveCustomPages("user-1", listOf(CustomPage("pg1", "Friday night")))
    }

    @Test fun home() = shoot("tv-home", ::customize) { c ->
        HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}, pinnedPages = listOf("Friday night" to {}))
    }

    @Test fun pageEditor() = shoot("tv-page-editor", ::customize) { c ->
        val vm = viewModel { RowsViewModel(PageKeys.HOME, c.server::homeRows, c.displaySettings, c.server) }
        PageEditor("Home", vm, c.server, null) {}
    }

    @Test fun rowBuilder() = shoot("tv-row-builder") { c -> RowBuilder(c.server, null, null, onSave = { _, _ -> }, onDismiss = {}) }

    @Test fun displaySettings() = shoot("tv-display-settings", { c -> c.displaySettings.savePrefs("user-1", DisplayPrefs(criticRating = false, hideSpoilers = true)) }) { c ->
        DisplaySettingsScreen(c)
    }

    @Test fun playerMore() = shoot("tv-player-more") { c ->
        val context = androidx.compose.ui.platform.LocalContext.current
        val session = androidx.compose.runtime.remember {
            dev.nitrate.android.playback.PlaybackSession(context, c.server, c.playbackSettings, deviceNoun = "This TV", onEnded = {}, trackMemory = c.trackMemory)
        }
        dev.nitrate.tv.ui.player.PlayerMoreDialog(c.server, c.playbackSettings, session, sleepAt = null, sleepAtEnd = false, onSleep = { _, _ -> }, onDismiss = {})
    }

    @Test fun watchTogether() = shoot("tv-watch-together") { c -> dev.nitrate.tv.ui.together.WatchTogetherScreen(c.syncPlay, "Joe's group") }

    @Test fun profiles() = shoot("tv-profiles", { c -> c.profiles.setPin("home|sam", "1234") }) { c ->
        dev.nitrate.tv.ui.profiles.ProfilesScreen(c, onOpened = {}, onSignInAgain = {}, onAdd = {})
    }

    @Test fun pinPad() = shoot("tv-pin-pad") { dev.nitrate.tv.ui.profiles.PinPad("Enter Sam's PIN", check = { false }, onDone = {}, onDismiss = {}) }

    private val moviesLibrary = dev.nitrate.core.model.MediaItem("lib-movies", "Movies", dev.nitrate.core.model.ItemKind.LIBRARY, collection = dev.nitrate.core.model.CollectionKind.MOVIES)

    @Test fun browse() = shoot("tv-browse") { c ->
        dev.nitrate.tv.ui.library.BrowseLibrary(c.server, c.displaySettings, moviesLibrary, header = { androidx.tv.material3.Text("Movies", style = androidx.tv.material3.MaterialTheme.typography.headlineMedium) }, onOpen = {}, onPlay = {})
    }

    @Test fun browseList() = shoot("tv-browse-list", { c ->
        c.displaySettings.saveView("user-1", "browse/lib-movies", dev.nitrate.core.pages.BrowseOptions(view = dev.nitrate.core.pages.BrowseView.LIST, sort = dev.nitrate.core.pages.QuerySort.DATE_ADDED, descending = true, decade = 1990))
    }) { c ->
        dev.nitrate.tv.ui.library.BrowseLibrary(c.server, c.displaySettings, moviesLibrary, header = { androidx.tv.material3.Text("Movies", style = androidx.tv.material3.MaterialTheme.typography.headlineMedium) }, onOpen = {}, onPlay = {})
    }

    @Test fun addToList() = shoot("tv-add-to-list") { c ->
        dev.nitrate.tv.ui.lists.AddToListDialog(c.server, dev.nitrate.core.model.MediaItem("m1", "Nitrate", dev.nitrate.core.model.ItemKind.MOVIE), canCollect = true) {}
    }

    @Test fun manageItem() = shoot("tv-manage-item") { c -> dev.nitrate.tv.ui.admin.ManageItemScreen(c.server, "m1") }

    @Test fun serverAdmin() = shoot("tv-server-admin") { c -> dev.nitrate.tv.ui.admin.ServerAdminScreen(c.server) }

    @Test fun seriesNoRatings() = shoot("tv-series-trimmed", { c ->
        c.displaySettings.savePrefs("user-1", DisplayPrefs(communityRating = false, overview = false, officialRating = false))
    }) { c -> DetailsScreen(c.server, "series-1", null, onPlay = { _, _ -> }, onOpen = {}) }
}
