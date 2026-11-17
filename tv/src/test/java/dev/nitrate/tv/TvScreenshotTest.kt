package dev.nitrate.tv

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
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
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.width
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
            WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) }
        }

    /** The app's menu, as the nav host provides it. */
    @Composable
    private fun WithMenu(c: AppContainer, content: @Composable () -> Unit) {
        val libraries by androidx.compose.runtime.produceState(emptyList<dev.nitrate.core.model.MediaItem>()) { value = c.server.views() }
        val catalog = dev.nitrate.tv.ui.home.menuCatalog(libraries, emptyList(), dev.nitrate.tv.ui.home.MenuActions())
        androidx.compose.runtime.CompositionLocalProvider(dev.nitrate.tv.ui.home.LocalMenuCatalog provides catalog, content = content)
    }

    private fun prefsShot(name: String, prefs: dev.nitrate.core.pages.DisplayPrefs, check: () -> Unit = {}, content: @Composable (AppContainer) -> Unit) =
        shoot(name, { c -> c.displaySettings.savePrefs("user-1", prefs) }, act = check, content = content)

    /** Right from the open rail goes back to the page (the open drawer covers it, so focus can't find it alone). */
    @Test fun railRightExits() = prefsShot("tv-rail-exit", dev.nitrate.core.pages.DisplayPrefs(), {
        press("left")
        org.junit.Assert.assertTrue("Left reaches the rail", focusedHasText("Home"))
        press("right")
        org.junit.Assert.assertFalse("Right leaves the rail", focusedHasText("Home"))
    }) { c -> WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) } }

    /** Fade in on the Top/Bottom bar: nothing over the art until you move up. */
    @Test fun fadeInBar() = prefsShot("tv-home-fade-hidden", dev.nitrate.core.pages.DisplayPrefs(tvMenu = dev.nitrate.core.pages.TvMenuStyle.TOP, tvMenuAutoHide = true), {
        press("up")
        org.junit.Assert.assertTrue(focusedHasText("Home"))
        press("down")
    }) { c -> WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) } }

    /** Library pages carry the menu too, with their library underlined. */
    @Test fun libraryMenu() = prefsShot("tv-library-menu", dev.nitrate.core.pages.DisplayPrefs(tvMenu = dev.nitrate.core.pages.TvMenuStyle.TOP)) { c ->
        WithMenu(c) {
            dev.nitrate.tv.ui.home.MainMenu(dev.nitrate.core.pages.MenuStops.library("lib-movies"), overlay = false) {
                dev.nitrate.tv.ui.library.LibraryScreen(c.server, c.displaySettings, "lib-movies", onOpen = {}, onPlay = {})
            }
        }
    }

    /** The search field waits as a button; once typing, Down puts the keyboard away and moves on. */
    @Test fun textFieldLetsGo() = shoot("tv-text-field", act = {
        compose.onNodeWithText("Search titles").assertExists()
        press("down")
        org.junit.Assert.assertTrue("Down from the field reaches the button", focusedHasText("Below"))
        press("up")
        org.junit.Assert.assertTrue("Up returns to the field: " + compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().joinToString { it.config.toString() }, focusedHasText("Search titles"))
        compose.onRoot().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionCenter) }
        compose.mainClock.advanceTimeBy(400); compose.waitForIdle()
        org.junit.Assert.assertTrue("OK starts typing", compose.onAllNodes(androidx.compose.ui.test.isFocused() and androidx.compose.ui.test.hasSetTextAction()).fetchSemanticsNodes().isNotEmpty())
        press("down")
        org.junit.Assert.assertTrue("Down while typing moves on", focusedHasText("Below"))
    }) { _ ->
        var text by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
        val first = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
        androidx.compose.foundation.layout.Column(Modifier.padding(40.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
            dev.nitrate.tv.ui.components.TvTextField(text, { text = it }, "Search titles", Modifier.width(500.dp).focusRequester(first))
            androidx.tv.material3.Button(onClick = {}) { androidx.tv.material3.Text("Below") }
        }
        androidx.compose.runtime.LaunchedEffect(Unit) { first.requestFocus() }
    }

    @Test fun singleSignOn() = shoot("tv-sign-in-sso") { c ->
        dev.nitrate.tv.ui.login.SsoPanel(c.server, "http://192.168.1.20:8096", dev.nitrate.core.model.SsoProvider("Home ID"), {}, {})
    }

    @Test fun channels() = shoot("tv-channels") { c ->
        dev.nitrate.tv.ui.channels.ChannelsScreen(c.server, admin = true, onPlayQueue = {}, onEdit = {})
    }

    @Test fun channelEditor() = shoot("tv-channel-editor") { c ->
        dev.nitrate.tv.ui.channels.ChannelEditorScreen(c.server, "c1") {}
    }

    @Test fun chat() = shoot("tv-chat") { c ->
        val chat = androidx.compose.runtime.remember {
            dev.nitrate.core.companion.GroupChat(c.server, kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined)) { "user-1" }.also { it.follow("g1") }
        }
        androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxSize().background(androidx.compose.ui.graphics.Color.Black))
            dev.nitrate.tv.ui.together.ChatPanel(chat, "Movie night", positionMs = { 0L })
        }
    }

    @Test fun shareScript() = shoot("tv-share-script", setup = { c -> c.writing.account = c.server.currentAccountKey; c.refreshCompanionForTests() }) { c ->
        val id = androidx.compose.runtime.produceState<String?>(null) { value = c.writing.create("Title: Night Shift\n\nINT. DINER - NIGHT") }.value
        if (id != null) dev.nitrate.tv.plugins.writing.SharePanel(c.sharedWriting, id, onMessage = {}, onDone = {}, modifier = Modifier.fillMaxSize().padding(40.dp))
    }

    private fun storyContext() = dev.nitrate.android.writing.StoryContext(
        text = dev.nitrate.testing.SampleStory.text, scenes = dev.nitrate.testing.SampleStory.scenes, story = dev.nitrate.testing.SampleStory.story,
        selfId = "user-1", selfName = "joe", people = listOf(dev.nitrate.core.writing.UserRef("user-1", "joe"), dev.nitrate.core.writing.UserRef("user-2", "sam")),
        changeStory = {}, applyText = {}, openInScript = {}, history = null,
    )

    @Test fun storyScenes() = shoot("tv-story-scenes") {
        dev.nitrate.tv.plugins.writing.StoryTvPanel(storyContext(), dev.nitrate.tv.plugins.writing.StoryTvTab.SCENES, onBack = {}, modifier = Modifier.fillMaxSize().padding(40.dp))
    }

    @Test fun storyMap() = shoot("tv-story-map") {
        dev.nitrate.tv.plugins.writing.StoryTvPanel(storyContext(), dev.nitrate.tv.plugins.writing.StoryTvTab.MAP, onBack = {}, modifier = Modifier.fillMaxSize().padding(40.dp))
    }

    @Test fun storyEntry() = shoot("tv-story-entry", act = {
        compose.onNodeWithText("SAM").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
    }) {
        dev.nitrate.tv.plugins.writing.StoryTvPanel(storyContext(), dev.nitrate.tv.plugins.writing.StoryTvTab.LISTS, onBack = {}, modifier = Modifier.fillMaxSize().padding(40.dp))
    }

    @Test fun libraryHealth() = shoot("tv-library-health", setup = { c -> c.checkLibraryHealth(null) }) { c ->
        dev.nitrate.tv.ui.settings.LibraryHealthScreen(c, onOpen = {}, onManage = {})
    }

    @Test fun licenses() = shoot("tv-licenses") { _ -> dev.nitrate.tv.ui.settings.LicensesScreen() }

    @Test fun servers() = shoot("tv-servers", act = {
        // Test the current server's local address: the result shows under it.
        press("down", "right")
        compose.onRoot().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionCenter) }
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        compose.onNodeWithText("Connected to Home in 18 ms", substring = true).assertExists()
        press("up", "left", "up")
    }) { c -> dev.nitrate.tv.ui.settings.ServersScreen(c, onSwitch = {}, onAdd = {}) }

    /** The player's controls: every button alike until focused; the focused one is plain to see. */
    private fun playerControls(name: String, vararg keys: String, check: () -> Unit = {}) = shoot(name, act = { press(*keys); check() }) { c ->
        val item by androidx.compose.runtime.produceState<dev.nitrate.core.model.MediaItem?>(null) { value = c.server.getItem("s1e2") }
        val focus = androidx.compose.runtime.remember { dev.nitrate.tv.ui.player.ControlFocus() }
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF2A3440))) {
            Box(Modifier.align(androidx.compose.ui.Alignment.BottomStart)) {
                dev.nitrate.tv.ui.player.PlayerControls(
                    item, null, position = 1_260_000, duration = 2_700_000, playing = true, focus = focus,
                    onPlayPause = {}, onSeek = {},
                    buttons = listOf("Audio", "Subtitles", "Info", "More").map { dev.nitrate.tv.ui.player.PlayerAction(it) {} },
                    // Pictures for some, placeholders for the rest.
                    chapters = listOf("Opening", "The letter", "Night train", "Crossing", "The reel", "Morning", "Credits")
                        .mapIndexed { i, n -> dev.nitrate.core.model.Chapter(i * 400_000L, n) },
                    chapterImage = { i -> item?.takeIf { i % 3 != 2 }?.let { c.server.imageUrl(it, dev.nitrate.core.model.ImageKind.BACKDROP, 320) } },
                    cast = sampleCast,
                    titleStrips = item?.let { current -> sampleStrips(c, current) }.orEmpty(),
                    personImage = { p -> item?.takeIf { p.imageTag != null }?.let { c.server.imageUrl(it, dev.nitrate.core.model.ImageKind.PRIMARY, 300) } },
                )
            }
        }
        androidx.compose.runtime.LaunchedEffect(Unit) { focus.playPause() }
    }

    @Test fun playerControlsPlay() = playerControls("tv-player-controls-play")
    @Test fun playerControlsButton() = playerControls("tv-player-controls-audio", "right") { check(focusedHasText("Audio")) }
    @Test fun playerControlsSeek() = playerControls("tv-player-controls-seek", "up")
    @Test fun playerControlsChaptersFolded() = playerControls("tv-player-controls-chapters-folded")
    @Test fun playerControlsChaptersFoldBack() = playerControls("tv-player-controls-chapters-fold-back", "down", "up") {
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("Now"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        check(compose.onAllNodes(androidx.compose.ui.test.isFocused() and androidx.compose.ui.test.hasContentDescription("Pause")).fetchSemanticsNodes().isNotEmpty())
    }
    private val sampleCast = listOf("Ada Marsh" to "Iris Vale", "Tom Okafor" to "The Projectionist", "Lena Brook" to "Night Clerk", "Sam Ito" to "Conductor", "Rui Costa" to "Ferryman", "June Hale" to "Archivist")
        .mapIndexed { i, (n, r) -> dev.nitrate.core.model.Person("p$i", n, r, imageTag = "t".takeIf { i % 3 != 1 }) }

    @Test fun playerControlsCast() = playerControls("tv-player-controls-cast", "down", "down") { check(compose.onAllNodes(androidx.compose.ui.test.hasText("▴  Cast")).fetchSemanticsNodes().isNotEmpty()) }
    /** One strip open at a time: opening the cast folds the chapters. */
    @Test fun playerControlsOneStrip() = playerControls("tv-player-controls-one-strip", "down", "down") {
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("▾  Chapters")).fetchSemanticsNodes().isNotEmpty())
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("▴  Chapters")).fetchSemanticsNodes().isEmpty())
    }
    @Test fun playerControlsCastUp() = playerControls("tv-player-controls-cast-up", "down", "down", "up") { check(focusedHasText("Now")) }
    @Test fun personDialog() = shoot("tv-person-dialog") { c ->
        dev.nitrate.tv.ui.player.PersonDialog(c.server, sampleCast[1], null, onDismiss = {})
    }
    private fun sampleStrips(c: AppContainer, current: dev.nitrate.core.model.MediaItem): List<dev.nitrate.tv.ui.player.TitleStrip> {
        val art = { i: dev.nitrate.core.model.MediaItem -> c.server.imageUrl(current, dev.nitrate.core.model.ImageKind.BACKDROP, 320).takeIf { i.id.last() != '3' } }
        val extras = listOf("Trailer", "Making the reel", "Deleted scenes").mapIndexed { i, n -> dev.nitrate.core.model.MediaItem("x$i", n, dev.nitrate.core.model.ItemKind.MOVIE, extraType = if (i == 0) "Trailer" else "Behind the scenes", runtimeMs = 120_000L * (i + 1)) }
        val episodes = (1..6).map { n ->
            dev.nitrate.core.model.MediaItem("e$n", "Episode title $n", dev.nitrate.core.model.ItemKind.EPISODE, indexNumber = n, runtimeMs = 2_700_000,
                user = dev.nitrate.core.model.UserState(played = n < 2, positionMs = if (n == 4) 900_000 else 0))
        }
        return listOf(
            dev.nitrate.tv.ui.player.TitleStrip("Extras", extras.map { dev.nitrate.tv.ui.player.TitleCard(it, it.name, it.extraType, art(it)) }) {},
            dev.nitrate.tv.ui.player.TitleStrip("Season 1", episodes.map { dev.nitrate.tv.ui.player.TitleCard(it, it.name, "Episode ${it.indexNumber}", art(it), now = it.id == "e2") }, start = 1) {},
        )
    }

    @Test fun playerControlsExtras() = playerControls("tv-player-controls-extras", "down", "down", "down") { check(compose.onAllNodes(androidx.compose.ui.test.hasText("▴  Extras")).fetchSemanticsNodes().isNotEmpty()) }
    @Test fun playerControlsEpisodes() = playerControls("tv-player-controls-episodes", "down", "down", "down", "down") { check(focusedHasText("Now")) } 
    private fun morePanel(name: String, tab: String) = shoot(name) { c ->
        val item by androidx.compose.runtime.produceState<dev.nitrate.core.model.MediaItem?>(null) { value = c.server.getItem("s1e2") }
        item?.let { current ->
            MorePanelMock(c.server.imageUrl(current, dev.nitrate.core.model.ImageKind.BACKDROP, 1920), tab, sampleStrips(c, current)[1].cards)
        }
    }

    @Test fun morePanelEpisodes() = morePanel("tv-more-panel-episodes", "Episodes")
    @Test fun morePanelSettings() = morePanel("tv-more-panel-settings", "Settings")
    @Test fun playerControlsChapters() = playerControls("tv-player-controls-chapters", "down") { check(focusedHasText("Now")) }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    private fun press(vararg keys: String) {
        keys.forEach { name ->
            val k = when (name) { "ok" -> androidx.compose.ui.input.key.Key.DirectionCenter; "menu" -> androidx.compose.ui.input.key.Key.Menu; "up" -> androidx.compose.ui.input.key.Key.DirectionUp; "down" -> androidx.compose.ui.input.key.Key.DirectionDown; "left" -> androidx.compose.ui.input.key.Key.DirectionLeft; else -> androidx.compose.ui.input.key.Key.DirectionRight }
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
            WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) }
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
    }) { c -> WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) } }

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
        WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) }
    }

    /** Rows of different styles one under another, on a tall canvas so the whole page shows. */
    @Test
    @Config(qualifiers = "w960dp-h1500dp-land-television-night-xhdpi")
    fun homeMixedRows() = shoot("tv-home-mixed-rows", { c ->
        c.displaySettings.savePrefs("user-1", rowsHome)
        val rows = c.server.homeRows()
        c.displaySettings.save("user-1", rows[1], dev.nitrate.core.rows.RowDisplay(dev.nitrate.core.rows.RowStyle.FILM_STRIP))
        c.displaySettings.save("user-1", rows[3], dev.nitrate.core.rows.RowDisplay(dev.nitrate.core.rows.RowStyle.CRESSIDA))
    }) { c ->
        WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) }
    }

    @Test
    @Config(qualifiers = "w960dp-h1500dp-land-television-night-xhdpi")
    fun homeMixedRows2() = shoot("tv-home-mixed-rows-2", { c ->
        c.displaySettings.savePrefs("user-1", rowsHome)
        val rows = c.server.homeRows()
        c.displaySettings.save("user-1", rows[0], dev.nitrate.core.rows.RowDisplay(dev.nitrate.core.rows.RowStyle.POSTER, size = dev.nitrate.core.rows.RowSize.SMALL))
        c.displaySettings.save("user-1", rows[1], dev.nitrate.core.rows.RowDisplay(dev.nitrate.core.rows.RowStyle.LIGHTNING))
        c.displaySettings.save("user-1", rows[2], dev.nitrate.core.rows.RowDisplay(dev.nitrate.core.rows.RowStyle.WIDE, size = dev.nitrate.core.rows.RowSize.LARGE))
    }) { c ->
        WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) }
    }

    /** A Full Showcase row lower on the page fills the screen when scrolled to, before it has focus. */
    @Test fun homeFullShowcase() = shoot("tv-home-full-showcase", { c ->
        c.displaySettings.savePrefs("user-1", rowsHome)
        val rows = c.server.homeRows()
        c.displaySettings.save("user-1", rows[0], dev.nitrate.core.rows.RowDisplay(dev.nitrate.core.rows.RowStyle.FULL_SHOWCASE))
    }) { c ->
        WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) }
    }

    @Test fun settingsHub() = shoot("tv-settings-hub") { _ ->
        fun links(vararg l: Pair<String, String>) = l.map { (label, detail) -> dev.nitrate.tv.ui.settings.SettingsLink(label, detail) {} }
        dev.nitrate.tv.ui.settings.SettingsHubScreen(
            listOf(
                dev.nitrate.tv.ui.settings.SettingsSection(null, links("Picture & sound" to "Sound, speakers, audio sync, frame rate; Advanced for formats and fixes", "Subtitles" to "Size, colour, background and styled subtitles", "Display" to "Home and menu, colours, screensaver, what's shown about titles", "While watching" to "Scenes in the credits, recaps, spoilers, audio description, theme music")),
                dev.nitrate.tv.ui.settings.SettingsSection("Account & servers", links("Switch user" to "Other users and servers, profile PINs", "Sign out" to "http://jellyfin.local:8096")),
                dev.nitrate.tv.ui.settings.SettingsSection("About", links("Updates" to "Nitrate 0.0.0-dev.18")),
            ),
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

    @Test fun whileWatchingSettings() = shoot("tv-while-watching-settings", { c -> c.displaySettings.savePrefs("user-1", DisplayPrefs(hideSpoilers = true)) }) { c ->
        DisplaySettingsScreen(c, dev.nitrate.tv.ui.pages.DisplayPage.WATCHING)
    }

    @Test fun pictureAndSound() = shoot("tv-picture-and-sound") { c ->
        dev.nitrate.tv.ui.settings.PlaybackSettingsScreen(c.playbackSettings, c.capabilities)
    }

    @Test fun advancedPlayback() = shoot("tv-picture-and-sound-advanced") { c ->
        dev.nitrate.tv.ui.settings.PlaybackSettingsScreen(c.playbackSettings, c.capabilities, dev.nitrate.tv.ui.settings.PlaybackPage.ADVANCED)
    }

    /** Up Next on TV: the next episode with its countdown, and the next film in a collection without. */
    @Test fun upNext() = shoot("tv-up-next") { c ->
        androidx.compose.foundation.layout.Column(
            Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).padding(48.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(24.dp),
        ) {
            dev.nitrate.tv.ui.player.UpNextCard(
                MediaItem("s2e5", "Episode title 5", ItemKind.EPISODE, seriesId = "series-1", seriesName = "The Night Reel", seasonNumber = 2, indexNumber = 5),
                c.server, seconds = 12, onPlay = {}, onHide = {},
            )
            dev.nitrate.tv.ui.player.UpNextCard(MediaItem("m3", "Gate Weave", ItemKind.MOVIE, productionYear = 2024), c.server, seconds = null, onPlay = {}, onHide = {})
        }
    }

    /** Scenes in the credits: skipped to (with the way back), and a heads-up when only the tags know. */
    @Test fun creditScene() = shoot("tv-credit-scene") { c ->
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
            coil3.compose.AsyncImage(c.server.backdropUrl(MediaItem("series-1", "", ItemKind.SERIES)), null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
            androidx.compose.foundation.layout.Column(Modifier.align(androidx.compose.ui.Alignment.BottomStart).padding(48.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
                dev.nitrate.tv.ui.player.CreditSceneBanner(dev.nitrate.core.playback.CreditSkipper.Action.HeadsUp(during = false, after = true), onAct = {}, onDone = {})
                dev.nitrate.tv.ui.player.CreditSceneBanner(dev.nitrate.core.playback.CreditSkipper.Action.Skip(450_000, 360_000, afterCredits = true), onAct = {}, onDone = {})
            }
        }
    }

    /** The watch diary on TV. */
    @Test fun diary() = shoot("tv-diary") { c -> dev.nitrate.tv.ui.settings.DiaryScreen(c.server) }

    /** Search finds what people say too. */
    @Test fun saidIn() = shoot("tv-search-said-in") { c ->
        dev.nitrate.tv.ui.search.SearchScreen(c.server, c.searchHistory, initialQuery = "need a bigger boat", onOpen = {}, onPerson = {}, linesOn = true)
    }

    /** Playback info with the sound's path (hi-res stereo FLAC going to a receiver). */
    @Test fun playbackInfoAudio() = shoot("tv-playback-info-audio") { c ->
        val plan = dev.nitrate.core.model.PlaybackPlan(
            itemId = "m1", url = "", method = dev.nitrate.core.model.PlayMethod.DIRECT_PLAY, playSessionId = null,
            source = dev.nitrate.core.model.MediaSource(
                "s1", "mkv", null,
                listOf(
                    dev.nitrate.core.model.MediaStream(0, dev.nitrate.core.model.StreamKind.VIDEO, codec = "hevc", width = 1920, height = 1080, bitDepth = 10),
                    dev.nitrate.core.model.MediaStream(1, dev.nitrate.core.model.StreamKind.AUDIO, codec = "flac", language = "jpn", channels = 2, channelLayout = "stereo", sampleRate = 96_000, bitDepth = 24),
                ),
            ),
            isHls = false, audioIndex = 1, subtitleIndex = -1, externalSubtitles = emptyList(), transcodeReasons = emptyList(),
        )
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
            dev.nitrate.tv.ui.player.PlaybackInfoPanel(
                plan, "c2.android.hevc.decoder", "c2.android.flac.decoder",
                Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(32.dp),
                audio = dev.nitrate.core.playback.AudioPath("flac", 2, 96_000, 24, dev.nitrate.core.playback.AudioDelivery.DECODED, "PCM float", 2, 96_000, 96_000),
                video = dev.nitrate.core.playback.VideoPath(
                    inCodec = "hevc", inWidth = 1920, inHeight = 1080, inFps = 23.976f, inRange = "SDR",
                    displayWidth = 3840, displayHeight = 2160, displayHz = 60f, displayHdr = setOf("HDR10", "HLG"),
                    droppedFrames = 0, bufferedMs = 31_000, networkBps = 220_000_000, fileBps = 9_400_000,
                    subtitles = "Styled (ASS), drawn by libass as the file styles them", secondSubtitle = "English",
                ),
            )
        }
    }

    /** Asked once per new output with more than two speakers, while upmixing is on. */
    @Test fun speakersQuestion() = shoot("tv-speakers-question") { _ ->
        dev.nitrate.tv.ui.player.SpeakersQuestionDialog(dev.nitrate.core.playback.SoundOutput(dev.nitrate.core.playback.OutputKind.HDMI, "DENON-AVR", 8), onAnswer = {}, onLater = {}, title = "Your sound", laterLabel = "Skip")
    }

    /** A new output while stereo plays: a quiet note in a corner. */
    @Test fun speakersBanner() = shoot("tv-speakers-banner") { c ->
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
            coil3.compose.AsyncImage(c.server.backdropUrl(MediaItem("series-1", "", ItemKind.SERIES)), null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
            with(dev.nitrate.tv.ui.player.SpeakersUiPreview) { banner() }
        }
    }

    /** The ambient screensaver. */
    @Test fun ambient() = shoot("tv-ambient") { c ->
        dev.nitrate.tv.ui.ambient.AmbientScreen(
            listOf(dev.nitrate.core.ambient.AmbientSlide(MediaItem("series-1", "The Night Reel", ItemKind.SERIES), "Up next", "The Night Reel", "S2 · E5")),
            c.server, onOpen = {}, onDismiss = {},
        )
    }

    /** "Your year" on TV. */
    @Test fun stats() = shoot("tv-stats") { c -> dev.nitrate.tv.ui.settings.StatsScreen(c.server) }

    /** Timed lyrics beside a song's cover. */
    @Test fun lyrics() = shoot("tv-lyrics") { c ->
        val lyrics = dev.nitrate.core.music.Lyrics(
            listOf("Lights go down", "The reel begins to turn", "Silver on the screen", "We hold our breath", "And the city fades to grain").mapIndexed { i, t ->
                dev.nitrate.core.music.LyricLine(t, i * 4_000L)
            },
            synced = true,
        )
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
            coil3.compose.AsyncImage(c.server.backdropUrl(MediaItem("series-1", "", ItemKind.SERIES)), null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
            dev.nitrate.tv.ui.player.LyricsView(lyrics, positionMs = 9_000, modifier = Modifier.align(androidx.compose.ui.Alignment.CenterEnd))
        }
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

    @Test fun movieSpoilerFree() = shoot("tv-movie-spoilers", setup = { c ->
        c.displaySettings.savePrefs("user-1", dev.nitrate.core.pages.DisplayPrefs(hideSpoilers = true))
    }) { c -> dev.nitrate.tv.ui.details.DetailsScreen(c.server, "m1", null, onPlay = { _, _ -> }, onOpen = {}, onManage = {}) }

    @Test fun movieTracks() = shoot("tv-movie-tracks") { c ->
        dev.nitrate.tv.ui.details.DetailsScreen(
            c.server, "m1", null, onPlay = { _, _ -> }, onOpen = {},
            trackSetup = { c.trackSetup(it) }, pickTrack = { item, setup, kind, index -> c.pickTrack(item, setup, kind, index) },
        )
    }

    @Test fun serverSettings() = shoot("tv-server-settings") { c -> dev.nitrate.tv.ui.settings.ServerSettingsScreen(c) }

    @Test fun watchPartners() = shoot("tv-watch-partners", setup = { c -> c.refreshCompanionForTests() }) { c ->
        dev.nitrate.tv.ui.partners.WatchPartnersScreen(c) {}
    }

    @Test fun partnerRequest() = shoot("tv-partner-request", setup = { c -> c.refreshCompanionForTests() }) { c ->
        dev.nitrate.tv.ui.partners.PartnerRequestPrompt(c)
    }

    @Test fun recap() = shoot("tv-recap", setup = { c -> c.pendingRecap = (c.server as dev.nitrate.testing.FakeServer).sampleRecap() }) { c ->
        dev.nitrate.tv.ui.player.RecapScreen(c, {}, {}, {})
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

    @Test fun backupPrompt() = shoot("tv-backup-prompt") { c ->
        dev.nitrate.tv.ui.settings.RestoreChooser("Set this TV up like…", "Your other devices' settings are saved on your server. Choose one to copy its playback and plugin settings here; you'll see what changes first.",
            dev.nitrate.testing.SampleBackups.list, c.backups, "Not now", {}, {})
    }

    @Test fun backupDifferences() = shoot("tv-backup-differences") { dev.nitrate.tv.ui.settings.DifferencesDialog(dev.nitrate.testing.SampleBackups.preview, {}, {}) }

    @Test fun displayProfiles() = shoot("tv-display-profiles", { c ->
        c.displaySettings.useProfile("user-1", "tv")
        c.displaySettings.createProfile("user-1", "Bedroom TV", from = "tv")
        c.displaySettings.createProfile("user-1", "Living room", from = "tv")
    }) { c -> dev.nitrate.tv.ui.settings.DisplayProfilesScreen(c) }

    @Test fun dialImportDifferences() = shoot("tv-dial-import") {
        dev.nitrate.tv.ui.settings.DifferencesPanel(
            "The radial menu from Living room. Nothing else in this profile changes.",
            listOf(
                dev.nitrate.core.backup.Difference("Radial menu", "Ring 1, up", "Home", "Search"),
                dev.nitrate.core.backup.Difference("Radial menu", "Ring 1, right", "Search", "Movies"),
                dev.nitrate.core.backup.Difference("Radial menu", "Ring 2, up", "Empty", "Live TV"),
            ),
            {}, {},
        )
    }

    @Test fun cressidaWesternDragonRow() = shoot("tv-row-cressida-western-dragon") { c ->
        val rows = androidx.compose.runtime.produceState<List<dev.nitrate.core.rows.MediaRow>>(emptyList()) { value = c.server.homeRows() }.value
        val row = rows.firstOrNull { it.key.startsWith("home/latest/") } ?: return@shoot
        androidx.compose.foundation.layout.Box(Modifier.padding(top = 48.dp)) {
            dev.nitrate.tv.ui.rows.CressidaRow(row, c.server, dev.nitrate.tv.ui.rows.RowActions({}, {}, {}, {}), dev.nitrate.core.rows.RowDisplay(dev.nitrate.core.rows.RowStyle.CRESSIDA, ink = dev.nitrate.core.rows.CressidaInk.WESTERN_DRAGON))
        }
    }

    @Test fun cressidaPhoenixRow() = shoot("tv-row-cressida-phoenix") { c ->
        val rows = androidx.compose.runtime.produceState<List<dev.nitrate.core.rows.MediaRow>>(emptyList()) { value = c.server.homeRows() }.value
        val row = rows.firstOrNull { it.key.startsWith("home/latest/") } ?: return@shoot
        androidx.compose.foundation.layout.Box(Modifier.padding(top = 48.dp)) {
            dev.nitrate.tv.ui.rows.CressidaRow(row, c.server, dev.nitrate.tv.ui.rows.RowActions({}, {}, {}, {}), dev.nitrate.core.rows.RowDisplay(dev.nitrate.core.rows.RowStyle.CRESSIDA, ink = dev.nitrate.core.rows.CressidaInk.PHOENIX))
        }
    }

    @Test fun addToLists() = shoot("tv-add-to-lists") { c ->
        dev.nitrate.tv.ui.lists.AddToListDialog(c.server, dev.nitrate.core.model.MediaItem("m1", "Nitrate", dev.nitrate.core.model.ItemKind.MOVIE), canCollect = true) {}
    }

    @Test fun speedCheck() = shoot("tv-speed-check") { c -> dev.nitrate.tv.ui.settings.SpeedCheckScreen(c, startNow = true) }
}
