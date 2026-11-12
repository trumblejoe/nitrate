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
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.runtime.getValue
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

    private fun shoot(name: String, setup: suspend (AppContainer) -> Unit = {}, act: () -> Unit = {}, content: @Composable (AppContainer) -> Unit) {
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
        act()
        compose.mainClock.advanceTimeBy(1_000)
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

    private val rowsHome = dev.nitrate.core.pages.DisplayPrefs(tvHome = dev.nitrate.core.pages.TvHomeLayout.ROWS)

    private fun fullArt(menu: dev.nitrate.core.pages.TvMenuStyle) =
        shoot("tv-home-full-art-${menu.name.lowercase()}", { c -> c.displaySettings.savePrefs("user-1", dev.nitrate.core.pages.DisplayPrefs(tvMenu = menu)) }) { c ->
            HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {})
        }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    private fun press(vararg keys: String) {
        keys.forEach { name ->
            val k = when (name) { "menu" -> androidx.compose.ui.input.key.Key.Menu; "up" -> androidx.compose.ui.input.key.Key.DirectionUp; "down" -> androidx.compose.ui.input.key.Key.DirectionDown; "left" -> androidx.compose.ui.input.key.Key.DirectionLeft; else -> androidx.compose.ui.input.key.Key.DirectionRight }
            compose.onRoot().performKeyInput { pressKey(k) }
            compose.mainClock.advanceTimeBy(400)
            compose.waitForIdle()
        }
    }

    private fun focusedHasText(text: String) =
        compose.onAllNodes(androidx.compose.ui.test.isFocused() and androidx.compose.ui.test.hasText(text, substring = true, ignoreCase = false), useUnmergedTree = false)
            .fetchSemanticsNodes().isNotEmpty()

    private fun menuShot(name: String, menu: dev.nitrate.core.pages.TvMenuStyle, check: () -> Unit) =
        shoot(name, { c -> c.displaySettings.savePrefs("user-1", dev.nitrate.core.pages.DisplayPrefs(tvMenu = menu)) }, act = check) { c ->
            HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {})
        }

    /** Top/Bottom: the bar is at the top on the first row and at the bottom on the last; towards the page goes back. */
    @Test fun topBottomBar() = menuShot("tv-home-bottom-bar", dev.nitrate.core.pages.TvMenuStyle.TOP) {
        press("up")
        org.junit.Assert.assertTrue("Up from the first row reaches the bar\n" + compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().joinToString { it.config.toString() } + "\n" + compose.onAllNodes(androidx.compose.ui.test.hasText("Home")).fetchSemanticsNodes().joinToString { it.config.toString() }, focusedHasText("Home"))
        press("down")
        org.junit.Assert.assertFalse("Down goes back to the page", focusedHasText("Home"))
        // Middle rows have no bar; the last row has it at the bottom.
        press("down")
        compose.onNodeWithText("Search").assertDoesNotExist()
        press("down", "down", "down")
        org.junit.Assert.assertTrue("Down from the last row reaches the bottom bar", focusedHasText("Home"))
        press("left")
    }

    /** Menu on a middle row brings the bar in at the top. */
    @Test fun menuKeyMidRow() = menuShot("tv-home-menu-key", dev.nitrate.core.pages.TvMenuStyle.TOP) {
        press("down", "menu")
        org.junit.Assert.assertTrue("Menu on a middle row focuses the bar", focusedHasText("Home"))
        press("menu")
        org.junit.Assert.assertFalse("Menu again goes back", focusedHasText("Home"))
    }

    /** A dial arranged by the user: empty spots are skipped when stepping out along a spoke. */
    @Test fun dialArranged() = shoot("tv-home-dial-arranged", { c ->
        c.displaySettings.savePrefs("user-1", dev.nitrate.core.pages.DisplayPrefs(
            tvMenu = dev.nitrate.core.pages.TvMenuStyle.RADIAL,
            tvDialStops = listOf("home", null, null, "settings", null, null, null, null, "search"),
        ))
    }, act = {
        press("up", "up")
        compose.onNodeWithText("Home").assertExists()
        press("up")
        compose.onNodeWithText("Search").assertExists()
    }) { c -> HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) }

    private fun stopsEditor(name: String, prefs: dev.nitrate.core.pages.DisplayPrefs) = shoot(name) { c ->
        val libraries by androidx.compose.runtime.produceState(emptyList<dev.nitrate.core.model.MediaItem>()) { value = c.server.views() }
        val catalog = dev.nitrate.tv.ui.home.menuCatalog(libraries, listOf(dev.nitrate.core.pages.CustomPage("pg1", "Friday night")), dev.nitrate.tv.ui.home.MenuActions(), current = "")
        dev.nitrate.tv.ui.settings.MenuStopsEditor(prefs, catalog) {}
    }

    @Test fun menuStopsDial() = stopsEditor("tv-menu-stops-dial", dev.nitrate.core.pages.DisplayPrefs(tvMenu = dev.nitrate.core.pages.TvMenuStyle.RADIAL))
    @Test fun menuStopsList() = stopsEditor("tv-menu-stops-list", dev.nitrate.core.pages.DisplayPrefs(tvMenu = dev.nitrate.core.pages.TvMenuStyle.LEFT))

    /** The fade-in bar appears when you move up past the top (Back is left alone). */
    @Test fun fadeOnEdge() = menuShot("tv-home-full-art-fade", dev.nitrate.core.pages.TvMenuStyle.FADE) {
        press("up")
        org.junit.Assert.assertTrue(focusedHasText("Home"))
    }

    /** Up opens the dial; Up, Up walks out the top spoke to the second ring; Right swings to its right stop. */
    @Test fun dialRings() = menuShot("tv-home-full-art-radial", dev.nitrate.core.pages.TvMenuStyle.RADIAL) {
        press("up")
        compose.onNodeWithText("▲ ▶ ▼ ◀").assertExists()
        press("up")
        compose.onNodeWithText("Search").assertExists()
        press("up", "right")
        // Ring two by default: Your pages up, Watch together right.
        compose.onNodeWithText("Watch together").assertExists()
    }

    @Test fun homeFullArtLeft() = fullArt(dev.nitrate.core.pages.TvMenuStyle.LEFT)
    @Test fun homeFullArtRight() = fullArt(dev.nitrate.core.pages.TvMenuStyle.RIGHT)
    @Test fun homeFullArtTop() = fullArt(dev.nitrate.core.pages.TvMenuStyle.TOP)

    @Test fun home() = shoot("tv-home", { c -> customize(c); c.displaySettings.savePrefs("user-1", rowsHome) }) { c ->
        HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}, customPages = listOf(dev.nitrate.core.pages.CustomPage("pg1", "Friday night")))
    }

    @Test fun homeArtRows() = shoot("tv-home-art-rows", { c ->
        c.displaySettings.savePrefs("user-1", rowsHome)
        val rows = c.server.homeRows()
        c.displaySettings.save("user-1", rows[0], dev.nitrate.core.rows.RowDisplay(dev.nitrate.core.rows.RowStyle.CRESSIDA))
        c.displaySettings.save("user-1", rows[1], dev.nitrate.core.rows.RowDisplay(dev.nitrate.core.rows.RowStyle.LIGHTNING))
    }) { c ->
        HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {})
    }

    @Test fun settingsHub() = shoot("tv-settings-hub") { _ ->
        dev.nitrate.tv.ui.settings.SettingsHubScreen(
            listOf("Playback" to "Formats, quality, tests and updates for this TV", "Display" to "What's shown about items; follows your account", "Check for updates" to "Nitrate 0.0.0-dev.18", "Plugins" to "Built-in extras, on or off", "Switch user" to "Other users and servers, profile PINs", "Sign out" to "http://jellyfin.local:8096")
                .map { (l, d) -> dev.nitrate.tv.ui.settings.SettingsLink(l, d) {} },
        )
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

    @Test fun search() = shoot("tv-search") { c ->
        dev.nitrate.tv.ui.search.SearchScreen(c.server, c.searchHistory, initialQuery = "re", onOpen = {}, onPerson = {})
    }

    @Test fun album() = shoot("tv-album") { c -> dev.nitrate.tv.ui.details.DetailsScreen(c.server, "al1", null, onPlay = { _, _ -> }, onOpen = {}) }

    @Test fun liveTv() = shoot("tv-live-tv") { c ->
        dev.nitrate.tv.ui.livetv.LiveTvScreen(c.server, header = { androidx.tv.material3.Text("Live TV", style = androidx.tv.material3.MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 58.dp, top = 24.dp)) }, onPlay = {})
    }

    /** One of the decorative row styles, on its own, with the first item focused. */
    private fun artRow(name: String, draw: @Composable (dev.nitrate.core.rows.MediaRow, dev.nitrate.core.MediaServer, dev.nitrate.tv.ui.rows.RowActions) -> Unit) = shoot(name) { c ->
        val rows = androidx.compose.runtime.produceState<List<dev.nitrate.core.rows.MediaRow>>(emptyList()) { value = c.server.homeRows() }.value
        val row = rows.firstOrNull { it.key == "home/latest/lib-movies" } ?: return@shoot
        val actions = androidx.compose.runtime.remember { dev.nitrate.tv.ui.rows.RowActions({}, {}, {}, {}) }
        val focus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
        androidx.compose.foundation.layout.Box(Modifier.padding(top = 48.dp).focusRequester(focus).focusGroup()) { draw(row, c.server, actions) }
        androidx.compose.runtime.LaunchedEffect(row) { runCatching { focus.requestFocus() } }
    }

    @Test fun filmStripRow() = artRow("tv-row-film-strip") { r, s, a -> dev.nitrate.tv.ui.rows.FilmStripRow(r, s, a) }
    @Test fun lightningRow() = artRow("tv-row-lightning") { r, s, a -> dev.nitrate.tv.ui.rows.LightningRow(r, s, a) }
    @Test fun cressidaRow() = artRow("tv-row-cressida") { r, s, a -> dev.nitrate.tv.ui.rows.CressidaRow(r, s, a) }

    @Test fun cressidaDragonRow() = shoot("tv-row-cressida-dragon") { c ->
        val rows = androidx.compose.runtime.produceState<List<dev.nitrate.core.rows.MediaRow>>(emptyList()) { value = c.server.homeRows() }.value
        val row = rows.firstOrNull { it.key == "home/latest/lib-movies" } ?: return@shoot
        androidx.compose.foundation.layout.Box(Modifier.padding(top = 48.dp)) {
            dev.nitrate.tv.ui.rows.CressidaRow(row, c.server, dev.nitrate.tv.ui.rows.RowActions({}, {}, {}, {}), dev.nitrate.core.rows.RowDisplay(dev.nitrate.core.rows.RowStyle.CRESSIDA, ink = dev.nitrate.core.rows.CressidaInk.DRAGON))
        }
    }

    @Test fun manageItem() = shoot("tv-manage-item") { c -> dev.nitrate.tv.ui.admin.ManageItemScreen(c.server, "m1") }

    @Test fun serverAdmin() = shoot("tv-server-admin") { c -> dev.nitrate.tv.ui.admin.ServerAdminScreen(c.server) }

    @Test fun seriesNoRatings() = shoot("tv-series-trimmed", { c ->
        c.displaySettings.savePrefs("user-1", DisplayPrefs(communityRating = false, overview = false, officialRating = false))
    }) { c -> DetailsScreen(c.server, "series-1", null, onPlay = { _, _ -> }, onOpen = {}) }
}
