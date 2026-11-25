package dev.nitrate.tv

import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
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
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.width
import org.junit.Rule
import org.junit.Test
import dev.nitrate.core.pages.applyEverywhere
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

    private fun shoot(name: String, setup: suspend (AppContainer) -> Unit = {}, act: () -> Unit = {}, settleMs: Long = 1_000, content: @Composable (AppContainer) -> Unit) {
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
        compose.mainClock.advanceTimeBy(settleMs)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
        // Everything that can be focused or pressed says what it is.
        val silent = dev.nitrate.testing.A11y.silent(compose)
        check(silent.isEmpty()) { "$name: a screen reader would say nothing for:\n" + silent.joinToString("\n") }
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

    private val rowsHome = dev.nitrate.core.pages.DisplayPrefs(tvArrangement = dev.nitrate.core.pages.PageArrangement.ROWS)

    private fun fullArt(menu: dev.nitrate.core.pages.TvMenuStyle) =
        shoot("tv-home-full-art-${menu.name.lowercase()}", { c -> c.displaySettings.savePrefs("user-1", dev.nitrate.core.pages.DisplayPrefs(tvMenu = menu)) }) { c ->
            WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) }
        }

    /** Set by the menu's Sign out (the account panel's), as the nav host's would sign out. */
    private var signedOut = false

    /** The app's menu, as the nav host provides it. */
    @Composable
    private fun WithMenu(c: AppContainer, content: @Composable () -> Unit) {
        val libraries by androidx.compose.runtime.produceState(emptyList<dev.nitrate.core.model.MediaItem>()) { value = c.server.views() }
        val catalog = dev.nitrate.tv.ui.home.menuCatalog(libraries, emptyList(), dev.nitrate.tv.ui.home.MenuActions(onSignOut = { signedOut = true }))
        androidx.compose.runtime.CompositionLocalProvider(dev.nitrate.tv.ui.home.LocalMenuCatalog provides catalog, dev.nitrate.tv.ui.pages.LocalTvContainer provides c, content = content)
    }

    private fun prefsShot(name: String, prefs: dev.nitrate.core.pages.DisplayPrefs, check: () -> Unit = {}, content: @Composable (AppContainer) -> Unit) =
        shoot(name, { c -> c.displaySettings.savePrefs("user-1", prefs) }, act = check, content = content)

    /** Left from the page opens the overlay (the default menu) on Home; Right goes back to the page. */
    /** Pinned pages are tabs beside Home; Up from the rows reaches them, and choosing one keeps focus on its tab. */
    @Test fun homeTabs() = shoot("tv-home-tabs", act = {
        press("up", "right", "ok")
        compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
        check(focusedHasText("Kids")) { "focus should stay on the tab chosen" }
    }) { c ->
        val pages = listOf(dev.nitrate.core.pages.CustomPage("kids", "Kids", pinned = true), dev.nitrate.core.pages.CustomPage("uhd", "4K films", pinned = true))
        HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}, customPages = pages)
    }

    /** A theme far down the list is focused when the picker opens on it, so another can be chosen straight from it. */
    @Test fun themePickerFromLastTheme() {
        var picked: dev.nitrate.core.effects.PageTheme? = null
        shoot("tv-theme-picker-from-poetry", act = {
            check(focusedHasText("Poetry")) { "the theme in use should have focus" }
            press("up", "ok")
            compose.waitForIdle()
            check(picked == dev.nitrate.core.effects.PageTheme.FLOWERS) { "Up then OK from Poetry should pick Flower garden, got $picked" }
        }) { _ ->
            dev.nitrate.tv.ui.effects.PageThemeChoices("Movies", dev.nitrate.core.effects.PageTheme.POETRY, onPick = { picked = it })
        }
    }

    /** A drawn page theme shows over Home itself, not only in the picker's preview. */
    @Test fun homeDrawnTheme() = shoot("tv-home-theme-roses", { c ->
        c.displaySettings.savePrefs("user-1", dev.nitrate.core.pages.DisplayPrefs(pageThemes = mapOf(PageKeys.HOME to dev.nitrate.core.effects.PageTheme.ROSES)))
    }) { c ->
        WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) }
    }

    /** Black menu: the overlay's pop-up card on solid black, the page's theme kept off it. */
    @Test fun blackMenuOverTheme() = shoot("tv-home-black-menu", { c ->
        c.displaySettings.savePrefs("user-1", dev.nitrate.core.pages.DisplayPrefs(tvMenuBlack = true, pageThemes = mapOf(PageKeys.HOME to dev.nitrate.core.effects.PageTheme.ROSES)))
    }, act = {
        press("menu")
        check(menuOpen()) { "Menu opens the pop-up" }
    }) { c ->
        WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) }
    }

    @Test fun blackBarOverTheme() = shoot("tv-home-black-bar", { c ->
        c.displaySettings.savePrefs("user-1", rowsHome.copy(tvMenu = dev.nitrate.core.pages.TvMenuStyle.TOP, tvMenuBlack = true, pageThemes = mapOf(PageKeys.HOME to dev.nitrate.core.effects.PageTheme.FLOWERS)))
    }) { c ->
        WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) }
    }

    /** One row at a time: Down moves the next row up to the bottom; the others stay hidden. */
    @Test fun rowsOneAtATimeDown() = shoot("tv-home-rows-one-at-a-time-down", { c ->
        c.displaySettings.savePrefs("user-1", rowsHome)
    }, act = { press("down") }) { c ->
        WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) }
    }

    /** Rows one at a time in each place, with the caption in another corner or beside them. */
    private fun rowLayout(name: String, prefs: dev.nitrate.core.pages.DisplayPrefs, down: Boolean = false) = shoot(name, { c ->
        c.displaySettings.savePrefs("user-1", prefs)
    }, act = { if (down) press("down") }) { c ->
        WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) }
    }

    @Test fun rowsAtTopCaptionBottom() = rowLayout("tv-rows-top-caption-bottom-right", rowsHome.copy(
        tvRowPlace = dev.nitrate.core.pages.RowPlace.TOP, tvCaptionCorner = dev.nitrate.core.pages.CaptionCorner.BOTTOM_RIGHT, tvRowExtent = dev.nitrate.core.pages.RowExtent.PARTIAL))

    @Test fun rowsBottomCompactTopLeft() = rowLayout("tv-rows-bottom-compact-top-left", rowsHome.copy(
        tvCaptionCorner = dev.nitrate.core.pages.CaptionCorner.TOP_LEFT, tvCaptionSize = dev.nitrate.core.pages.CaptionSize.COMPACT))

    /** The Minimal caption: the title's logo and facts, no description. */
    @Test fun rowsMinimalCaption() = rowLayout("tv-rows-minimal-caption", rowsHome.copy(tvCaptionSize = dev.nitrate.core.pages.CaptionSize.MINIMAL))

    @Test fun rowsDownTheRight() = rowLayout("tv-rows-right-caption-bottom-left", rowsHome.copy(
        tvRowPlace = dev.nitrate.core.pages.RowPlace.RIGHT, tvCaptionCorner = dev.nitrate.core.pages.CaptionCorner.BOTTOM_LEFT), down = true)

    @Test fun rowsDownTheLeftSameSide() = rowLayout("tv-rows-left-caption-top-left", rowsHome.copy(
        tvRowPlace = dev.nitrate.core.pages.RowPlace.LEFT, tvCaptionCorner = dev.nitrate.core.pages.CaptionCorner.TOP_LEFT, tvCaptionSize = dev.nitrate.core.pages.CaptionSize.COMPACT))

    /** Artwork with its cards hidden, reached with a theme on: the title's picture is the page's backdrop, the theme over it. */
    @Test fun cardsHiddenOverTheme() = shoot("tv-cards-hidden-over-theme", { c ->
        c.displaySettings.savePrefs("user-1", rowsHome.copy(pageThemes = mapOf(PageKeys.HOME to dev.nitrate.core.effects.PageTheme.ROSES)))
        val rows = c.server.homeRows()
        c.displaySettings.save("user-1", rows[1], dev.nitrate.core.rows.RowDisplay(dev.nitrate.core.rows.RowStyle.WIDE, cards = false))
    }, act = { press("down") }) { c ->
        WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) }
    }

    /** Full art: the caption alternates corners title by title, as the screensaver's does. */
    @Test fun fullArtCaptionRight() = shoot("tv-full-art-caption-right", act = { press("right") }) { c ->
        WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) }
    }

    @Test fun groupStandingsOnCards() = shoot("tv-group-standings") { c ->
        fun ep(n: Int, series: String, played: Boolean = false) = dev.nitrate.core.model.MediaItem("$series-$n", "Episode $n", dev.nitrate.core.model.ItemKind.EPISODE,
            seriesId = series, seriesName = series.replaceFirstChar { it.uppercase() }, seasonNumber = 1, indexNumber = n, user = dev.nitrate.core.model.UserState(played = played, positionMs = if (n == 3) 600_000 else 0))
        val bear = (1..6).map { ep(it, "bear", played = it <= 2) }
        val dune = (1..6).map { ep(it, "dune", played = it <= 4) }
        val keep = mapOf(
            "bear" to dev.nitrate.core.partners.KeepUp.of("bear", bear, listOf(dev.nitrate.core.partners.PartnerProgress("g", "will", "Will", (1..5).map { "bear-$it" }.toSet(), "S1 E5", "bear-6"))),
            "dune" to dev.nitrate.core.partners.KeepUp.of("dune", dune, listOf(dev.nitrate.core.partners.PartnerProgress("h", "kim", "Kim", (1..4).map { "dune-$it" }.toSet(), "S1 E4", "dune-5"))),
        )
        androidx.compose.runtime.CompositionLocalProvider(dev.nitrate.tv.ui.components.LocalGroupStandings provides keep) {
            androidx.compose.foundation.layout.Column(Modifier.padding(top = 40.dp)) {
                dev.nitrate.tv.ui.rows.CardRow(
                    dev.nitrate.core.rows.MediaRow("home/continue_watching", "Continue Watching", listOf(bear[2], dune[4]), dev.nitrate.core.rows.RowDisplay(dev.nitrate.core.rows.RowStyle.WIDE)),
                    dev.nitrate.core.rows.RowDisplay(dev.nitrate.core.rows.RowStyle.WIDE), c.server,
                    dev.nitrate.tv.ui.rows.RowActions({}, {}, {}, {}),
                )
            }
        }
    }

    // ---- Effects: each scene held at a moment, over a stand-in page ----
    private fun effectShot(name: String, theme: dev.nitrate.core.effects.PageTheme? = null, occasion: dev.nitrate.core.effects.Occasion? = null, at: Float = 3.1f) =
        shoot(name) { dev.nitrate.tv.ui.effects.EffectStage(theme, occasion, dev.nitrate.core.effects.EffectsPrefs(), onDismiss = {}, at = at) }

    @Test fun fxThunderstorm() = effectShot("tv-fx-thunderstorm", theme = dev.nitrate.core.effects.PageTheme.THUNDERSTORM, at = 7.53f)
    @Test fun fxSafari() = effectShot("tv-fx-safari", theme = dev.nitrate.core.effects.PageTheme.SAFARI, at = 12f)
    @Test fun fxDeepSpace() = effectShot("tv-fx-deep-space", theme = dev.nitrate.core.effects.PageTheme.DEEP_SPACE, at = 4f)
    @Test fun fxUnderwater() = effectShot("tv-fx-underwater", theme = dev.nitrate.core.effects.PageTheme.UNDERWATER, at = 6f)
    @Test fun fxHaunted() = effectShot("tv-fx-haunted", theme = dev.nitrate.core.effects.PageTheme.HAUNTED)
    @Test fun fxDesert() = effectShot("tv-fx-desert", theme = dev.nitrate.core.effects.PageTheme.DESERT, at = 4f)
    @Test fun fxSnowyForest() = effectShot("tv-fx-snowy-forest", theme = dev.nitrate.core.effects.PageTheme.SNOWY_FOREST)
    @Test fun fxSuperhero() = effectShot("tv-fx-superhero", theme = dev.nitrate.core.effects.PageTheme.SUPERHERO_CITY, at = 5.3f)
    @Test fun fxAlienAgents() = effectShot("tv-fx-alien-agents", theme = dev.nitrate.core.effects.PageTheme.ALIEN_AGENTS, at = 5f)
    @Test fun fxNeonArcade() = effectShot("tv-fx-neon-arcade", theme = dev.nitrate.core.effects.PageTheme.NEON_ARCADE)
    @Test fun fxOccasions() {
        // One stage per occasion, small, in a grid.
        shoot("tv-fx-occasions") {
            androidx.compose.foundation.layout.Column {
                dev.nitrate.core.effects.Occasion.entries.chunked(4).forEach { line ->
                    androidx.compose.foundation.layout.Row(Modifier.weight(1f)) {
                        line.forEach { o ->
                            Box(Modifier.weight(1f).fillMaxSize().padding(2.dp)) {
                                dev.nitrate.tv.ui.effects.EffectsLayer(null, o, dev.nitrate.core.effects.EffectsPrefs(), at = 4.2f)
                                androidx.tv.material3.Text(o.label, Modifier.align(androidx.compose.ui.Alignment.Center))
                            }
                        }
                    }
                }
            }
        }
    }
    @Test fun fxLaborDay() = effectShot("tv-fx-labor-day", occasion = dev.nitrate.core.effects.Occasion.LABOR_DAY)
    @Test fun fxMarines() = effectShot("tv-fx-marine-corps", occasion = dev.nitrate.core.effects.Occasion.MARINE_CORPS_BIRTHDAY)
    @Test fun fxAirForce() = effectShot("tv-fx-air-force", occasion = dev.nitrate.core.effects.Occasion.AIR_FORCE_BIRTHDAY, at = 3.6f)
    @Test fun fxJuly4() = effectShot("tv-fx-independence-day", occasion = dev.nitrate.core.effects.Occasion.INDEPENDENCE_DAY, at = 9.2f)
    @Test fun fxJuly4b() = effectShot("tv-fx-independence-day-b", occasion = dev.nitrate.core.effects.Occasion.INDEPENDENCE_DAY, at = 13.7f)
    @Test fun fxChristmas() = effectShot("tv-fx-christmas", occasion = dev.nitrate.core.effects.Occasion.CHRISTMAS, at = 5f)
    @Test fun fxFestivus() = effectShot("tv-fx-festivus", occasion = dev.nitrate.core.effects.Occasion.FESTIVUS, at = 2.5f)
    @Test fun fxHalloween() = effectShot("tv-fx-halloween", occasion = dev.nitrate.core.effects.Occasion.HALLOWEEN, at = 6f)
    @Test fun fxNewYear() = effectShot("tv-fx-new-year", occasion = dev.nitrate.core.effects.Occasion.NEW_YEAR, at = 9.2f)
    @Test fun fxValentines() = effectShot("tv-fx-valentines", occasion = dev.nitrate.core.effects.Occasion.VALENTINES, at = 12f)
    @Test fun fxThanksgiving() = effectShot("tv-fx-thanksgiving", occasion = dev.nitrate.core.effects.Occasion.THANKSGIVING, at = 12f)
    @Test fun fxStPatricks() = effectShot("tv-fx-st-patricks", occasion = dev.nitrate.core.effects.Occasion.ST_PATRICKS, at = 12f)
    @Test fun fxRoses() = effectShot("tv-fx-roses", theme = dev.nitrate.core.effects.PageTheme.ROSES, at = 8f)
    @Test fun fxFlowers() = effectShot("tv-fx-flowers", theme = dev.nitrate.core.effects.PageTheme.FLOWERS, at = 8f)
    @Test fun fxPoetry() = effectShot("tv-fx-poetry", theme = dev.nitrate.core.effects.PageTheme.POETRY, at = 12f)
    @Test fun prerollVerse() = shoot("tv-preroll-verse") {
        dev.nitrate.tv.ui.preroll.PrerollScreen(listOf(dev.nitrate.core.preroll.PrerollStep.Verse(dev.nitrate.core.poetry.Poems.all[33])), dev.nitrate.core.preroll.PrerollPrefs(verseSeconds = 20)) {}
    }
    @Test fun prerollOcean() = shoot("tv-preroll-ocean-floor") { dev.nitrate.tv.ui.preroll.SceneView(dev.nitrate.core.preroll.PrerollScene.OCEAN_FLOOR, at = 6f) }
    @Test fun prerollAurora() = shoot("tv-preroll-northern-lights") { dev.nitrate.tv.ui.preroll.SceneView(dev.nitrate.core.preroll.PrerollScene.NORTHERN_LIGHTS, at = 6f) }
    @Test fun prerollStars() = shoot("tv-preroll-starfield") { dev.nitrate.tv.ui.preroll.SceneView(dev.nitrate.core.preroll.PrerollScene.STARFIELD, at = 6f) }
    @Test fun prerollJellyfish() = shoot("tv-preroll-jellyfish") { dev.nitrate.tv.ui.preroll.SceneView(dev.nitrate.core.preroll.PrerollScene.JELLYFISH, at = 3f) }
    @Test fun prerollAnimals() = shoot("tv-preroll-animals") { dev.nitrate.tv.ui.preroll.SceneView(dev.nitrate.core.preroll.PrerollScene.WILD_ANIMALS, at = 8.5f) }
    @Test fun prerollSettings() = shoot("tv-preroll-settings") { c -> dev.nitrate.tv.ui.preroll.PrerollSettingsScreen(c) }
    /** The first-run setup, one step at a time (big text, Skip and Back on each, "Step n of N"). */
    private fun setupShot(name: String, step: dev.nitrate.core.setup.SetupStep, check: () -> Unit = {}) = shoot(name, act = { compose.mainClock.advanceTimeBy(500); compose.waitForIdle(); check() }) { c ->
        val libraries by androidx.compose.runtime.produceState(emptyList<dev.nitrate.core.model.MediaItem>()) { value = c.server.views() }
        val catalog = dev.nitrate.tv.ui.home.menuCatalog(
            libraries + listOf(
                dev.nitrate.core.model.MediaItem("anime-movies", "Anime Movies", dev.nitrate.core.model.ItemKind.LIBRARY),
                dev.nitrate.core.model.MediaItem("anime-shows", "Anime TV Shows", dev.nitrate.core.model.ItemKind.LIBRARY),
            ),
            emptyList(), dev.nitrate.tv.ui.home.MenuActions(),
        )
        val data = dev.nitrate.tv.ui.setup.SetupData(
            steps = dev.nitrate.core.setup.FirstRunSetup.steps(dev.nitrate.core.setup.SetupFacts(tv = true, profilesToAsk = true, backupsOffered = true, askSound = true, menuSuggestions = true)),
            accounts = listOf(
                dev.nitrate.core.model.SavedAccount(c.server.currentAccountKey ?: "me", "http://demo", "Home server", "user-1", "Alex", null),
                dev.nitrate.core.model.SavedAccount("other", "http://demo", "Home server", "user-2", "Sam", null),
            ),
            backups = listOf(
                dev.nitrate.core.backup.BackupSummary("living-room", "Living room TV", "SHIELD Android TV", "tv", System.currentTimeMillis() - 3 * 86_400_000L),
                dev.nitrate.core.backup.BackupSummary("phone", "Pixel 9", "Pixel 9", "mobile", System.currentTimeMillis() - 86_400_000L),
            ),
            output = dev.nitrate.core.playback.SoundOutput(dev.nitrate.core.playback.OutputKind.HDMI, "Living room soundbar", 6),
            suggestions = listOf(dev.nitrate.core.pages.GroupSuggestion("Anime", listOf("library:anime-movies", "library:anime-shows"))),
        )
        androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF0B0D12)).padding(horizontal = 72.dp, vertical = 48.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
        ) { dev.nitrate.tv.ui.setup.SetupSteps(c, data, catalog, onSignInAgain = {}, startAt = step) }
    }

    @Test fun setupProfile() = setupShot("tv-setup-profile", dev.nitrate.core.setup.SetupStep.PROFILE) { check(focusedHasText("Continue as")) { "Continue should take focus" } }
    @Test fun setupCopy() = setupShot("tv-setup-copy", dev.nitrate.core.setup.SetupStep.COPY_SETTINGS) {
        compose.onAllNodesWithText("Start fresh").onFirst().assertExists(); compose.onAllNodesWithText("Step 2 of 4").onFirst().assertExists()
    }
    @Test fun setupSound() = setupShot("tv-setup-sound", dev.nitrate.core.setup.SetupStep.SOUND)
    @Test fun setupMenu() = setupShot("tv-setup-menu", dev.nitrate.core.setup.SetupStep.MENU) { compose.onAllNodesWithText("Your menu:", substring = true).onFirst().assertExists() }
    @Test fun setupDone() = setupShot("tv-setup-done", dev.nitrate.core.setup.SetupStep.DONE) { check(focusedHasText("Start watching")) { "Start watching should take focus" } }

    /** Settings search: a setting found by the words around it, with where it lives. */
    @Test fun settingsSearch() = shoot("tv-settings-search") {
        dev.nitrate.tv.ui.settings.SettingsHubScreen(emptyList(), initialQuery = "subtitle")
    }

    /** The layout's Look: named presets over the choices that fine-tune them. */
    @Test fun layoutLook() = shoot("tv-layout-look", setup = { c ->
        c.displaySettings.savePrefs("user-1", dev.nitrate.core.pages.PageLooks.applyEverywhere(dev.nitrate.core.pages.DisplayPrefs(), dev.nitrate.core.pages.LayoutPreset.SHOWCASE))
    }) { c -> dev.nitrate.tv.ui.pages.DisplaySettingsScreen(c) }

    @Test fun fxEffectsSettings() = shoot("tv-fx-settings") { c -> dev.nitrate.tv.ui.effects.EffectsSettingsScreen(c) }

    /** An occasion's dates: the usual ones stretched, shown as the days it'll cover. */
    @Test fun occasionDates() = shoot("tv-occasion-dates") {
        dev.nitrate.tv.ui.effects.OccasionDatesDialog(
            dev.nitrate.core.effects.Occasion.EASTER,
            dev.nitrate.core.effects.OccasionDates(earlier = 5, later = 1),
            dev.nitrate.core.effects.Day(2026, 3, 1),
            onSave = {}, onDismiss = {},
        )
    }

    /** Own dates running on past New Year. */
    @Test fun occasionOwnDates() = shoot("tv-occasion-own-dates") {
        dev.nitrate.tv.ui.effects.OccasionDatesDialog(
            dev.nitrate.core.effects.Occasion.CHRISTMAS,
            dev.nitrate.core.effects.OccasionDates(dev.nitrate.core.effects.MonthDay(12, 20), dev.nitrate.core.effects.MonthDay(1, 6)),
            dev.nitrate.core.effects.Day(2026, 11, 22),
            onSave = {}, onDismiss = {},
        )
    }

    @Test fun railRightExits() = prefsShot("tv-rail-exit", dev.nitrate.core.pages.DisplayPrefs(tvMenu = dev.nitrate.core.pages.TvMenuStyle.LEFT), {
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
    /** A library page with a title focused: summary, tabs and more than one row on screen. */
    @Test fun libraryRows() = prefsShot("tv-library-rows", dev.nitrate.core.pages.DisplayPrefs(tvMenu = dev.nitrate.core.pages.TvMenuStyle.LEFT), { press("down", "down") }) { c ->
        WithMenu(c) { dev.nitrate.tv.ui.library.LibraryScreen(c.server, c.displaySettings, "lib-movies", onOpen = {}, onPlay = {}) }
    }
    private fun libraryShot(name: String, prefs: dev.nitrate.core.pages.DisplayPrefs, check: () -> Unit) = prefsShot(name, prefs, check) { c ->
        WithMenu(c) {
            val p = dev.nitrate.tv.ui.components.LocalDisplayPrefs.current
            val fullArt = (p.tvLibraryArrangement ?: p.tvArrangement).fullArt
            dev.nitrate.tv.ui.home.MainMenu("lib-movies", overlay = fullArt) { insets ->
                dev.nitrate.tv.ui.library.LibraryScreen(c.server, c.displaySettings, "lib-movies", onOpen = {}, onPlay = {}, fullArt = fullArt, insets = insets)
            }
        }
    }
    private val rowsLibrary = dev.nitrate.core.pages.DisplayPrefs(tvLibraryArrangement = dev.nitrate.core.pages.PageArrangement.ROWS)

    /** The Movies library in its menu frame (so the menu's Customize stop and the panel are there). */
    @Composable
    private fun MoviesWithMenu(c: AppContainer) = WithMenu(c) {
        dev.nitrate.tv.ui.home.MainMenu(dev.nitrate.core.pages.MenuStops.library("lib-movies"), overlay = false) { insets ->
            dev.nitrate.tv.ui.library.LibraryScreen(c.server, c.displaySettings, "lib-movies", onOpen = {}, onPlay = {}, insets = insets)
        }
    }

    /** The menu's "Customize this page" stop: the Customize panel on the page (on the tab showing). */
    private fun openCustomize() {
        press("menu")
        compose.onAllNodes(androidx.compose.ui.test.hasClickAction() and (androidx.compose.ui.test.hasAnyDescendant(androidx.compose.ui.test.hasContentDescription("Customize this page")) or androidx.compose.ui.test.hasAnyDescendant(androidx.compose.ui.test.hasText("Customize this page"))), useUnmergedTree = true)
            .onFirst().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        // The panel asks for its first choice over a few frames: step through them.
        repeat(10) { compose.mainClock.advanceTimeBy(200); compose.waitForIdle() }
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("Customize")).fetchSemanticsNodes().isNotEmpty()) { "the Customize panel should be open" }
    }

    /** Steps the clock (and real time, for the settings store) until [done], 5 s at most. */
    private fun waitFor(done: () -> Boolean) {
        repeat(50) { if (done()) return; Thread.sleep(50); compose.mainClock.advanceTimeBy(200); compose.waitForIdle() }
    }

    /** From the first row back up to the tabs: the row stays whole in its place under them. */
    @Test fun libraryRowUpToTabs() = libraryShot("tv-library-row-up-to-tabs", rowsLibrary) {
        press("down")
        compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
        press("up")
        compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
    }

    /** Genres shown as its titles in a view (Artwork, 5 across) instead of rows; Customize → View says so. */
    @Test fun libraryGenresAsView() = shoot("tv-library-genres-as-view", setup = { c ->
        c.displaySettings.savePrefs("user-1", rowsLibrary)
        c.displaySettings.saveLibraryTabs("user-1", "lib-movies", listOf(
            dev.nitrate.core.pages.LibraryTab("genres", dev.nitrate.core.pages.LibraryTabKind.GENRES),
            dev.nitrate.core.pages.LibraryTab("recommended", dev.nitrate.core.pages.LibraryTabKind.RECOMMENDED),
            dev.nitrate.core.pages.LibraryTab("all", dev.nitrate.core.pages.LibraryTabKind.ALL),
        ))
        c.displaySettings.saveView("user-1", "rows/" + dev.nitrate.core.pages.LibraryTabs.pageKey("lib-movies", dev.nitrate.core.pages.LibraryTab("genres", dev.nitrate.core.pages.LibraryTabKind.GENRES)),
            dev.nitrate.core.pages.TabView(rows = false, view = dev.nitrate.core.pages.BrowseView.ARTWORK, columns = mapOf(dev.nitrate.core.pages.BrowseView.ARTWORK to 5)))
    }, act = {
        check(!shows("View:")) { "the tabs bar has no View button any more" }
        // Nothing has focus when the test starts: the first ▼ gives it to the page.
        press("down")
        openCustomize()
        check(focusedHasText("Artwork")) { "Customize → View should open on the view in use; focused: ${focusedNodes()}" }
        check(compose.onAllNodes(androidx.compose.ui.test.isSelected() and androidx.compose.ui.test.hasText("5")).fetchSemanticsNodes().isNotEmpty()) { "the columns in use should be chosen" }
        check(shows("Rows")) { "a tab of rows offers Rows" }
        // The panel itself (a window of its own), with the page beside it.
        compose.onAllNodes(androidx.compose.ui.test.isRoot()).let { it[it.fetchSemanticsNodes().size - 1] }.captureRoboImage("build/outputs/roborazzi/tv-customize-view-genres.png")
        press("back")
    }) { c -> MoviesWithMenu(c) }

    /** The Collections tab looks like All (Fixed grid): the focused collection's details at the top, ▲ from the first row to its tab. */
    @Test fun libraryCollectionsTab() = shoot("tv-library-collections", setup = { c ->
        c.displaySettings.savePrefs("user-1", rowsLibrary)
        c.displaySettings.saveLibraryTabs("user-1", "lib-movies", listOf(
            dev.nitrate.core.pages.LibraryTab("collections", dev.nitrate.core.pages.LibraryTabKind.COLLECTIONS),
            dev.nitrate.core.pages.LibraryTab("recommended", dev.nitrate.core.pages.LibraryTabKind.RECOMMENDED),
        ))
    }, act = {
        press("down")
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        press("up")
        compose.mainClock.advanceTimeBy(300); compose.waitForIdle()
        check(focusedHasText("Collections")) { "Up from the first row should reach the Collections tab; focused: ${focusedNodes()}" }
        check(!shows("View:")) { "no View button on the bar" }
        press("down")
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
    }) { c -> MoviesWithMenu(c) }

    /** A library's main page (Recommended) is always its rows: no View button, and no View in its Customize. */
    @Test fun libraryRecommendedNoViewButton() = shoot("tv-library-recommended-no-view", setup = { c -> c.displaySettings.savePrefs("user-1", rowsLibrary) }, act = {
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("View", substring = true)).fetchSemanticsNodes().isEmpty()) { "Recommended shouldn't have a View button" }
        openCustomize()
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("View")).fetchSemanticsNodes().isEmpty()) { "Recommended's Customize shouldn't offer a view" }
        press("back")
    }) { c -> MoviesWithMenu(c) }

    /**
     * Genres as a view: ▲ from the first title goes to the Genres tab; Customize → View → Rows puts it
     * back to rows (saved where it was), and closing the panel leaves focus on the tab.
     */
    @Test fun libraryViewBackToRows() {
        lateinit var container: AppContainer
        val genres = "rows/" + dev.nitrate.core.pages.LibraryTabs.pageKey("lib-movies", dev.nitrate.core.pages.LibraryTab("genres", dev.nitrate.core.pages.LibraryTabKind.GENRES))
        shoot("tv-library-view-back-to-rows", setup = { c ->
            container = c
            c.displaySettings.savePrefs("user-1", rowsLibrary)
            c.displaySettings.saveLibraryTabs("user-1", "lib-movies", listOf(
                dev.nitrate.core.pages.LibraryTab("genres", dev.nitrate.core.pages.LibraryTabKind.GENRES),
                dev.nitrate.core.pages.LibraryTab("recommended", dev.nitrate.core.pages.LibraryTabKind.RECOMMENDED),
                dev.nitrate.core.pages.LibraryTab("all", dev.nitrate.core.pages.LibraryTabKind.ALL),
            ))
            c.displaySettings.saveView("user-1", genres, dev.nitrate.core.pages.TabView(rows = false))
        }, act = {
            // Down from the tabs into the titles, along to the first, Up to the Genres tab above it.
            compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
            press("down")
            if (compose.onAllNodes(androidx.compose.ui.test.isFocused() and androidx.compose.ui.test.hasText("Genres")).fetchSemanticsNodes().isNotEmpty()) press("down")
            repeat(4) { if (!focusedHasText("Nitrate")) press("left") }
            press("up")
            compose.mainClock.advanceTimeBy(300); compose.waitForIdle()
            check(focusedHasText("Genres")) { "Up from the first title should reach the Genres tab; focused: ${focusedNodes()}" }
            openCustomize()
            check(focusedHasText("Posters")) { "Customize → View should open on the current view; focused: ${focusedNodes()}" }
            press("left")
            check(focusedHasText("Rows")) { "Rows should be first in View" }
            press("ok")
            fun stored() = runBlocking { container.displaySettings.view("user-1", genres, dev.nitrate.core.pages.TabView()).first() }
            waitFor { stored().rows }
            check(stored().rows) { "Rows should be saved for the tab: ${stored()}" }
            press("back")
            waitFor { compose.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().size == 1 && compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().isNotEmpty() }
            // Back on the page as rows, focus on it (the rows put it on their first card), never lost or in the menu.
            check(compose.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().size == 1) { "Back should close the panel" }
            check(compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().isNotEmpty()) { "something on the page should have focus" }
            check(!menuOpen()) { "the menu should be closed" }
        }) { c -> MoviesWithMenu(c) }
    }

    /** Choosing All keeps focus on the tabs (it used to fall through to the menu). */
    @Test fun libraryTabSwitch() = libraryShot("tv-library-tab-switch", rowsLibrary) {
        press("right", "ok")
        compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
        check(focusedHasText("All")) { "focus should stay on the All tab" }
    }

    /** The All view: the A–Z bar down the right (away from the menu rail on the left). */
    @Test fun libraryLettersRight() = libraryShot("tv-library-letters-right", rowsLibrary) {
        press("right", "ok")
        compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
    }

    /** Left from the first title in the All view stays on it (focus never lost; the overlay opens with Menu or Up past the top). */
    @Test fun libraryLeftToMenu() = libraryShot("tv-library-left-to-menu", rowsLibrary) {
        press("right", "ok")
        compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
        // Down from the tabs bar (Sort, Filters and View at its end) goes straight into the grid.
        press("down")
        // Left along the first row to its first title.
        repeat(6) { if (!focusedHasText("Arclight")) press("left") }
        check(focusedHasText("Arclight")) { "Left within the grid should move to the first title" }
        press("left")
        check(focusedHasText("Arclight")) { "Left from the first title stays on it; focused: ${focusedNodes()}" }
    }

    /** The All tab: Back deep in the grid goes to the first title, and ▲ from the options bar to the chosen tab. */
    @Test fun allTabMovesAroundEasily() = libraryShot("tv-library-all-moving", rowsLibrary) {
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        repeat(4) { if (!focusedHasText("All")) press("right") }
        check(focusedHasText("All")) { "should reach the All tab" }
        press("ok")
        compose.mainClock.advanceTimeBy(1500); compose.waitForIdle()
        press("down", "down", "down", "down")
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        check(!focusedHasText("Arclight")) { "should be deep in the grid" }
        press("back")
        compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
        check(focusedHasText("Arclight")) { "Back deep in the grid should go to the first title; focused: ${focusedNodes()} ${compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().map { it.boundsInRoot }}" }
        press("up", "up")
        compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
        check(focusedHasText("All")) { "▲ from the options bar should go to the chosen tab" }
    }

    /**
     * The All tab laid out as [layout] (Fixed: details held at the top; Scrolling: posters fill the
     * page): Up from the first row reaches Sort at the end of the tabs bar, then the All tab; then a
     * title with a picture is focused for the shot.
     */
    private fun browseLayoutShot(name: String, layout: dev.nitrate.core.pages.GridLayout) = shoot(name, setup = { c ->
        c.displaySettings.savePrefs("user-1", rowsLibrary.copy(tvGridLayout = layout))
    }, act = {
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        repeat(4) { if (!focusedHasText("All")) press("right") }
        check(focusedHasText("All")) { "should reach the All tab" }
        press("ok")
        compose.mainClock.advanceTimeBy(1500); compose.waitForIdle()
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("16 items")).fetchSemanticsNodes().isNotEmpty()) { "the count should sit in the bar" }
        press("down")
        val afterDown = focusedNodes() + compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().joinToString { it.boundsInRoot.toString() }
        repeat(8) { if (!focusedHasText("Arclight")) press("left") }
        check(focusedHasText("Arclight")) { "Down from the tabs should reach the grid; after Down: $afterDown; now: ${focusedNodes()}" }
        press("up")
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        check(focusedHasText("Sort")) { "Up from the first row should reach Sort; focused: ${focusedNodes()}" }
        press("up")
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        check(focusedHasText("All")) { "Up from the bar should reach the chosen tab" }
        press("down")
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        repeat(8) { if (!focusedHasText("Arclight")) press("left") }
        press("down", "right", "right", "right")
        compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
        val card = compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().first().boundsInRoot
        check(card.bottom <= 1080f && card.top >= 0f) { "the focused title should be scrolled into sight, at $card" }
    }) { c ->
        WithMenu(c) {
            dev.nitrate.tv.ui.home.MainMenu(dev.nitrate.core.pages.MenuStops.library("lib-movies"), overlay = false) { insets ->
                dev.nitrate.tv.ui.library.LibraryScreen(c.server, c.displaySettings, "lib-movies", onOpen = {}, onPlay = {}, insets = insets)
            }
        }
    }
    @Test fun browseFixed() = browseLayoutShot("tv-browse-fixed", dev.nitrate.core.pages.GridLayout.FIXED)
    @Test fun browseScrolling() = browseLayoutShot("tv-browse-scrolling", dev.nitrate.core.pages.GridLayout.SCROLLING)

    /** Library pages can be full art like Home, with their tabs top right. */
    @Test fun libraryFullArt() = libraryShot("tv-library-full-art", dev.nitrate.core.pages.DisplayPrefs()) { press("up") }

    /** Fast-forward on a row: its next artwork style there and then, named for a moment. */
    @Test fun rowStyleFastForward() = libraryShot("tv-row-style-ff", rowsLibrary) {
        press("down", "ff")
        compose.mainClock.advanceTimeBy(300); compose.waitForIdle()
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("◀◀ ▶▶", substring = true)).fetchSemanticsNodes().isNotEmpty()) { "the new style should be named" }
        compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
        // Focus stays in the row, on a card: never handed to the menu.
        val focused = compose.onAllNodes(androidx.compose.ui.test.isFocused(), useUnmergedTree = true).fetchSemanticsNodes()
        check(focused.isNotEmpty()) { "something should keep focus" }
        check(!focusedHasText("Home") && !focusedHasText("Settings") && !focusedHasText("Search")) { "focus went to the menu" }
        // And again, through a style of a different height.
        press("ff", "ff")
        // Focus waits on a holder while the cards are swapped: give it time on a slow machine.
        var waited = 0
        do {
            compose.mainClock.advanceTimeBy(100); compose.waitForIdle()
            waited += 100
        } while (waited < 4000 && compose.onAllNodes(androidx.compose.ui.test.isFocused(), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        // Part of the swap runs on real time: under a busy machine, keep stepping until it settles.
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.mainClock.advanceTimeBy(100)
            compose.onAllNodes(androidx.compose.ui.test.isFocused(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        check(!focusedHasText("Home") && !focusedHasText("Settings") && !focusedHasText("Search")) { "focus went to the menu after more steps" }
        // Never left with nothing focused (on a TV that hands focus to the menu), never on the rail at the left edge.
        val after = compose.onAllNodes(androidx.compose.ui.test.isFocused(), useUnmergedTree = true).fetchSemanticsNodes()
        check(after.isNotEmpty()) { "focus was lost while the style changed" }
        // (The overlay menu takes no room, so the row's holders start at the screen's edge: what
        // matters is that the menu stays closed and its edge isn't where focus went.)
        check(!shows("Customize this page")) { "focus went to the menu" }
        check(!focusedSays("Menu") && !focusedSays("account")) { "focus went to the menu's edge or the corner" }
    }

    /** Settings → Display → Pages → a library: its tabs, shown or not, and adding your own. */
    @Test fun libraryTabsSetup() = shoot("tv-library-tabs-setup") { c -> dev.nitrate.tv.ui.pages.LibraryTabsScreen(c, "lib-movies") }

    /** Settings → Display → Pages. */
    @Test fun pageSetup() = shoot("tv-page-setup") { c -> dev.nitrate.tv.ui.pages.PageSetupScreen(c, onHome = {}) {} }

    @Test fun homeSetup() = shoot("tv-home-setup", { c -> c.displaySettings.saveCustomPages("user-1", listOf(dev.nitrate.core.pages.CustomPage("kids", "Kids"), dev.nitrate.core.pages.CustomPage("uhd", "4K films", pinned = false))) }) { c -> dev.nitrate.tv.ui.pages.HomeSetupScreen(c) }

    /** Up from a cards-hidden row to a library's tabs. */
    @Test fun libraryCardsHiddenUpToTabs() = shoot("tv-library-cards-hidden-up-to-tabs", setup = { c ->
        c.displaySettings.savePrefs("user-1", rowsLibrary)
        val lib = c.server.views().first { it.id == "lib-movies" }
        val rows = c.server.libraryRows(lib)
        c.displaySettings.save("user-1", rows[0], dev.nitrate.core.rows.RowDisplay(dev.nitrate.core.rows.RowStyle.WIDE, cards = false))
    }, act = {
        press("down", "up")
        check(focusedHasText("Recommended") || focusedHasText("All")) { "Up from the row should reach the tabs" }
    }) { c -> WithMenu(c) { dev.nitrate.tv.ui.library.LibraryScreen(c.server, c.displaySettings, "lib-movies", onOpen = {}, onPlay = {}) } }

    /** The All view as a bouncing box: posters flying round it; Down from the bar catches one. */
    @Test fun libraryBounceView() = shoot("tv-library-bounce", setup = { c ->
        c.displaySettings.savePrefs("user-1", rowsLibrary)
        c.displaySettings.saveLibraryTabs("user-1", "lib-movies", listOf(
            dev.nitrate.core.pages.LibraryTab("all", dev.nitrate.core.pages.LibraryTabKind.ALL),
            dev.nitrate.core.pages.LibraryTab("recommended", dev.nitrate.core.pages.LibraryTabKind.RECOMMENDED),
        ))
        c.displaySettings.saveView("user-1", "browse/lib-movies", dev.nitrate.core.pages.BrowseOptions(view = dev.nitrate.core.pages.BrowseView.BOUNCE))
    }, act = {
        compose.mainClock.advanceTimeBy(2_000); compose.waitForIdle()
        press("down", "down", "down")
        val focused = compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes()
        check(focused.isNotEmpty() && kotlin.math.abs(focused.first().boundsInRoot.width - 80f) < 2f) { "Down past the set buttons should catch a poster in the box, got ${focused.map { it.boundsInRoot }}" }
    }) { c -> WithMenu(c) { dev.nitrate.tv.ui.library.LibraryScreen(c.server, c.displaySettings, "lib-movies", onOpen = {}, onPlay = {}) } }

    /** A library's All tab in [view]: Down from the bar into it, then Right twice; something in it keeps focus. */
    private fun showViewShot(view: dev.nitrate.core.pages.BrowseView, downs: Int = 2, rights: Int = 2) = shoot("tv-library-view-${view.name.lowercase()}", setup = { c ->
        c.displaySettings.savePrefs("user-1", rowsLibrary)
        c.displaySettings.saveLibraryTabs("user-1", "lib-movies", listOf(
            dev.nitrate.core.pages.LibraryTab("all", dev.nitrate.core.pages.LibraryTabKind.ALL),
            dev.nitrate.core.pages.LibraryTab("recommended", dev.nitrate.core.pages.LibraryTabKind.RECOMMENDED),
        ))
        c.displaySettings.saveView("user-1", "browse/lib-movies", dev.nitrate.core.pages.BrowseOptions(view = view))
    }, act = {
        repeat(downs) { press("down") }
        repeat(rights) { press("right") }
        compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
        val focused = compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes()
        // The view starts just under the tabs bar (Sort and Filters sit at its end).
        check(focused.isNotEmpty() && focused.first().boundsInRoot.top > 110f) { "focus should be in the ${view.label} view, got ${focused.map { it.boundsInRoot }}" }
    }) { c -> WithMenu(c) { dev.nitrate.tv.ui.library.LibraryScreen(c.server, c.displaySettings, "lib-movies", onOpen = {}, onPlay = {}) } }

    @Test fun libraryCoverFlow() = showViewShot(dev.nitrate.core.pages.BrowseView.COVERFLOW)
    @Test fun libraryBookshelf() = showViewShot(dev.nitrate.core.pages.BrowseView.SHELF)
    @Test fun libraryTimeline() = showViewShot(dev.nitrate.core.pages.BrowseView.TIMELINE)
    @Test fun libraryWall() = showViewShot(dev.nitrate.core.pages.BrowseView.WALL)
    @Test fun libraryRolodex() = showViewShot(dev.nitrate.core.pages.BrowseView.ROLODEX)
    @Test fun libraryStarfield() = showViewShot(dev.nitrate.core.pages.BrowseView.STARFIELD, downs = 3)
    /** The deck after five deals: the dealt cards lie scattered on the table behind. */
    @Test fun libraryDeck() = showViewShot(dev.nitrate.core.pages.BrowseView.DECK, downs = 3, rights = 5)

    /**
     * Choosing a show view in Customize (the box, which isn't a grid) rebuilds the All tab behind the
     * panel; closing it leaves focus on Sort (switching to the box used to drop it to the menu).
     */
    @Test fun libraryViewSwitchKeepsFocus() {
        lateinit var container: AppContainer
        shoot("tv-library-view-switch", setup = { c ->
            container = c
            c.displaySettings.savePrefs("user-1", rowsLibrary)
            c.displaySettings.saveLibraryTabs("user-1", "lib-movies", listOf(dev.nitrate.core.pages.LibraryTab("all", dev.nitrate.core.pages.LibraryTabKind.ALL)))
        }, act = {
            check(!shows("View:")) { "the bar has no View button any more" }
            openCustomize()
            check(focusedHasText("Posters")) { "Customize → View should open on Posters; focused: ${focusedNodes()}" }
            repeat(3) { press("right") }
            check(focusedHasText("Bouncing box")) { "should reach Bouncing box; focused: ${focusedNodes()}" }
            press("ok")
            fun stored() = runBlocking { container.displaySettings.view("user-1", "browse/lib-movies", dev.nitrate.core.pages.BrowseOptions()).first() }
            waitFor { stored().view == dev.nitrate.core.pages.BrowseView.BOUNCE }
            check(stored().view == dev.nitrate.core.pages.BrowseView.BOUNCE) { "the view should be saved for All: ${stored()}" }
            check(focusedHasText("Bouncing box")) { "focus stays on the choice in the panel" }
            press("back")
            waitFor { focusedHasText("Sort") }
            check(focusedHasText("Sort")) { "closing the panel should leave focus on Sort; focused: ${focusedNodes()}" }
        }) { c -> MoviesWithMenu(c) }
    }

    /** Customize on the All tab → View → Artwork: the grid shows artwork (wide cards with their names), columns offered for it. */
    @Test fun customizeAllTabArtwork() {
        lateinit var container: AppContainer
        shoot("tv-customize-all-artwork", setup = { c ->
            container = c
            c.displaySettings.savePrefs("user-1", rowsLibrary)
            c.displaySettings.saveLibraryTabs("user-1", "lib-movies", listOf(dev.nitrate.core.pages.LibraryTab("all", dev.nitrate.core.pages.LibraryTabKind.ALL)))
        }, act = {
            openCustomize()
            check(shows("Columns")) { "Posters offers its columns" }
            press("right")
            check(focusedHasText("Artwork")) { "Artwork is next to Posters; focused: ${focusedNodes()}" }
            press("ok")
            fun stored() = runBlocking { container.displaySettings.view("user-1", "browse/lib-movies", dev.nitrate.core.pages.BrowseOptions()).first() }
            waitFor { stored().view == dev.nitrate.core.pages.BrowseView.ARTWORK }
            check(stored().view == dev.nitrate.core.pages.BrowseView.ARTWORK) { "Artwork should be saved for All: ${stored()}" }
            // Artwork's own column choices (2 to 6), 4 across by default.
            check(compose.onAllNodes(androidx.compose.ui.test.isSelected() and androidx.compose.ui.test.hasText("4")).fetchSemanticsNodes().isNotEmpty()) { "Artwork's 4 across should be chosen" }
            check(shows("Columns") && compose.onAllNodes(androidx.compose.ui.test.hasText("2")).fetchSemanticsNodes().isNotEmpty()) { "Artwork's columns should be offered" }
            press("back")
            waitFor { focusedHasText("Sort") }
            check(focusedHasText("Sort")) { "closing the panel should leave focus on Sort; focused: ${focusedNodes()}" }
            // Into the grid: its cards are artwork now (wide).
            press("down")
            compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
            val card = compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().first().boundsInRoot
            check(card.width > card.height) { "the grid should show artwork (wide cards), focused card at $card" }
            press("up")
            compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
            check(focusedHasText("Sort")) { "Up from the first row should reach Sort; focused: ${focusedNodes()}" }
            press("down")
            compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
        }) { c -> MoviesWithMenu(c) }
    }

    /** Home rows over a smart gradient: the page's colour taken from the focused title. */
    @Test fun homeSmartGradient() = shoot("tv-home-smart-gradient", { c ->
        c.displaySettings.savePrefs("user-1", rowsHome.copy(pageBackdrops = mapOf(PageKeys.HOME to dev.nitrate.core.effects.PageBackdrop.SMART_GRADIENT)))
    }) { c ->
        WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) }
    }

    /** A More from page: one row as Home's are, over the background chosen for these pages. */
    @Test fun moreFromPage() = shoot("tv-more-from", { c ->
        c.displaySettings.savePrefs("user-1", rowsHome.copy(pageBackdrops = mapOf(PageKeys.MORE_FROM to dev.nitrate.core.effects.PageBackdrop.SUNSET)))
    }, act = { press("down") }) { c ->
        WithMenu(c) { dev.nitrate.tv.ui.search.PersonScreen(c.server, dev.nitrate.core.model.Person("", "More from 2023"), onOpen = {}, year = 2023, displaySettings = c.displaySettings) }
    }

    /**
     * Home with tabs (pinned pages), rows one at a time: Right past a row's last card stays in the row (it
     * used to jump to the tabs); Left off its first card opens the menu; Back from the menu comes back
     * to that card, not the tabs.
     */
    @Test fun homeMenuAndBack() = shoot("tv-home-menu-and-back", setup = { c ->
        c.displaySettings.savePrefs("user-1", rowsHome)
    }, act = {
        fun at() = compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().first().boundsInRoot
        press("down")
        repeat(6) { press("right") }
        check(at().top > 300f) { "Right past the end of a row should stay in it, got ${at()}" }
        fun name() = compose.onAllNodes(androidx.compose.ui.test.isFocused(), useUnmergedTree = false).fetchSemanticsNodes().first().config.toString().substringAfter("{").replace(Regex("@[0-9a-f]+"), "")
        // Left card by card to the menu, remembering the last card on the way.
        var card = name()
        var place = at()
        // (The overlay is a pop-up in the middle: Left at the row's start leaves it shut; Menu opens it, Back comes back.)
        repeat(10) { press("left") }
        check(!menuOpen()) { "Left at the row's start doesn't open the menu" }
        card = name(); place = at()
        press("menu")
        check(menuOpen()) { "Menu should open the menu, got ${at()}" }
        press("back")
        check(!menuOpen()) { "Back closes the menu" }
        check(name() == card) { "Back from the menu should return to the card left, got ${at()} / was $place\n${name()}\n$card" }
        check(at() == place) { "and the row should stay put (no bounce): was $place, now ${at()}" }
    }) { c ->
        val pages = listOf(dev.nitrate.core.pages.CustomPage("kids", "Kids", pinned = true))
        WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}, customPages = pages) }
    }

    /** Left from a cards-hidden row opens the menu, and Back comes back. */
    private fun cardsHiddenToMenu(name: String, display: dev.nitrate.core.rows.RowDisplay) = shoot(name, setup = { c ->
        c.displaySettings.savePrefs("user-1", rowsHome)
        c.displaySettings.save("user-1", c.server.homeRows()[1], display)
    }, act = {
        fun at() = compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().first().boundsInRoot
        press("down")
        repeat(4) { if (!menuOpen()) press("left") }
        check(menuOpen()) { "Left from a ${display.styleLabel} row should open the menu, got ${at()}" }
        press("back")
        check(!menuOpen()) { "Back closes it" }
        // The row's layer is the whole screen (the overlay menu takes no room of its own).
        check(at().left > 100f || at().width > 1000f) { "Back from the menu should return to the row, got ${at()}" }
    }) { c -> WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) } }

    @Test fun cardsHiddenLeftToMenu() = cardsHiddenToMenu("tv-cards-hidden-left-to-menu", dev.nitrate.core.rows.RowDisplay(dev.nitrate.core.rows.RowStyle.WIDE, cards = false))
    /** A row saved as Full Showcase before it was retired shows (and behaves) as Artwork with its cards hidden. */
    @Test fun savedFullShowcaseLeftToMenu() = cardsHiddenToMenu("tv-saved-full-showcase-left-to-menu", dev.nitrate.core.rows.RowDisplay(dev.nitrate.core.rows.RowStyle.FULL_SHOWCASE))

    /** Up/Down from the tilted film strip go to the rows above and below; only Left/Right move along it. */
    @Test fun filmStripUpDownLeaveRow() = shoot("tv-film-strip-up-down", setup = { c ->
        c.displaySettings.savePrefs("user-1", rowsHome.copy(tvArrangement = dev.nitrate.core.pages.PageArrangement.STACKED))
        val rows = c.server.homeRows()
        c.displaySettings.save("user-1", rows[2], dev.nitrate.core.rows.RowDisplay(dev.nitrate.core.rows.RowStyle.FILM_STRIP))
    }, act = {
        fun at() = compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().first().boundsInRoot
        press("down", "down")
        val strip = at()
        press("right")
        val next = at()
        check(next.left > strip.right - 20f && kotlin.math.abs(next.top - strip.top) < 60f) { "Right should move along the strip, got $next from $strip" }
        // The frames either side sit only a little higher or lower on the tilted strip: Up/Down must not pick them.
        press("up")
        // Stacked rows each come to the top in turn, so the row above lands where the strip was: it's
        // told apart by its card at the row's start (the strip was on its second frame).
        check(at().left < next.left - 200f && at().width > 0f) { "Up from the strip should go to the row above, got ${at()} from $next" }
        press("down")
        check(at().width > at().height) { "Down should come back to the strip, got ${at()}" }
        press("down")
        check(at().height > at().width) { "Down from the strip should reach the poster row below, got ${at()}" }
    }) { c -> WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) } }

    /** Stacked rows: moving down the page never leaves a row's title or cards under the focused title's summary. */
    @Test fun stackedRowsClearOfSummary() = shoot("tv-stacked-rows-clear", setup = { c ->
        c.displaySettings.savePrefs("user-1", rowsHome.copy(tvArrangement = dev.nitrate.core.pages.PageArrangement.STACKED))
    }, act = {
        fun at() = compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().first().boundsInRoot
        val tops = mutableListOf<Float>()
        repeat(3) {
            press("down")
            compose.mainClock.advanceTimeBy(1000); compose.waitForIdle()
            val card = at()
            check(card.bottom <= 1080f) { "The row in use should be whole on screen, its card at $card" }
            tops += card.top
        }
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("Three seasons inside a cinema", substring = true)).fetchSemanticsNodes().isNotEmpty()) { "Normal shows the description" }
        // Each row in turn takes the same place under the summary (none left part shown above it).
        check(tops.max() - tops.min() < 40f) { "Rows should take one place under the summary, cards at $tops" }
    }) { c -> WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) } }

    /** Fast-forward through every style of a row keeps focus in the row each time, never the menu. */
    private fun everyStyleKeepsFocus(name: String, arrangement: dev.nitrate.core.pages.PageArrangement) = shoot(name, setup = { c ->
        c.displaySettings.savePrefs("user-1", rowsHome.copy(tvArrangement = arrangement))
    }, act = {
        // Along the row first: the card left must still get focus back in each style.
        press("down", "right", "right", "right", "right", "right", "right")
        val looks = mutableListOf<String>()
        repeat(dev.nitrate.core.rows.RowStyle.choices.size + 1) { step ->
            press("ff")
            compose.waitUntil(timeoutMillis = 15_000) {
                compose.mainClock.advanceTimeBy(100)
                compose.onAllNodes(androidx.compose.ui.test.isFocused(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.mainClock.advanceTimeBy(1500); compose.waitForIdle()
            val node = compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().first()
            val at = node.boundsInRoot
            looks += "$step:$at"
            // The menu is hidden until called (overlay): focus must not have opened it.
            check(!menuOpen()) { "Step $step: focus went to the menu, at $at (so far $looks)" }
        }
    }) { c -> WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) } }

    @Test fun everyStyleKeepsFocusStacked() = everyStyleKeepsFocus("tv-every-style-stacked", dev.nitrate.core.pages.PageArrangement.STACKED)
    @Test fun everyStyleKeepsFocusOneAtATime() = everyStyleKeepsFocus("tv-every-style-rows", dev.nitrate.core.pages.PageArrangement.ROWS)

    /** Stacked rows: the summary at the top follows the caption size (Compact/Minimal: no description). */
    @Test fun stackedSummaryFollowsCaptionSize() = shoot("tv-stacked-summary-compact", setup = { c ->
        c.displaySettings.savePrefs("user-1", rowsHome.copy(tvArrangement = dev.nitrate.core.pages.PageArrangement.STACKED, tvCaptionSize = dev.nitrate.core.pages.CaptionSize.COMPACT))
    }, act = {
        // Down to the show (it has a description; the Normal size shows it, see tv-stacked-rows-clear).
        press("down", "down", "down")
        compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("The Night Reel")).fetchSemanticsNodes().isNotEmpty()) { "the show should be in the summary" }
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("Three seasons inside a cinema", substring = true)).fetchSemanticsNodes().isEmpty()) { "Compact should leave the description out" }
    }) { c -> WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) } }

    /** Rows down the left or right: ▲▼ along each column and on to the next row, never the menu. */
    private fun sideRowsKeepFocus(name: String, place: dev.nitrate.core.pages.RowPlace) = shoot(name, setup = { c ->
        c.displaySettings.savePrefs("user-1", rowsHome.copy(tvRowPlace = place))
    }, act = {
        val trail = mutableListOf<String>()
        fun step(key: String) {
            press(key)
            compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
            val node = compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().firstOrNull()
            val at = node?.boundsInRoot
            val label = node?.config?.firstOrNull { it.key == androidx.compose.ui.semantics.SemanticsProperties.ContentDescription }?.value?.toString()
            trail += "$key→$label@${at?.left?.toInt()},${at?.top?.toInt()}"
            check(at != null && !menuOpen() && at.left >= 0f && at.top >= -5f && at.bottom <= 1085f) { "Focus lost or off screen: $trail" }
        }
        repeat(12) { step("down") }
        repeat(12) { step("up") }
        println("TRAIL $name: $trail")
    }) { c -> WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) } }

    @Test fun leftRowsKeepFocus() = sideRowsKeepFocus("tv-left-rows-focus", dev.nitrate.core.pages.RowPlace.LEFT)
    @Test fun rightRowsKeepFocus() = sideRowsKeepFocus("tv-right-rows-focus", dev.nitrate.core.pages.RowPlace.RIGHT)

    /** Full art with "Up/Down along the row": ▼▲ step titles, changing rows only past a row's ends. */
    @Test fun fullArtUpDownAlong() = shoot("tv-full-art-up-down-along", setup = { c ->
        c.displaySettings.savePrefs("user-1", dev.nitrate.core.pages.DisplayPrefs(tvUpDownAlong = true))
    }, act = {
        fun label(n: String) = compose.onAllNodes(androidx.compose.ui.test.hasText(n, substring = true)).fetchSemanticsNodes().isNotEmpty()
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        check(label("1 / 2")) { "should start on the first row's first title" }
        press("down"); compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        check(label("2 / 2")) { "▼ should step to the row's second title" }
        press("down"); compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        check(label("NEXT UP  ·  1 / 1")) { "▼ past the row's last title should go to the next row" }
        press("up"); compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        check(label("2 / 2")) { "▲ past the first title should go back to the row above, at its last title" }
    }) { c -> WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) } }

    /** Holds a key: a press, two repeats, then its release (cards and pages read a held key from its repeats). */
    private fun holdKey(code: Int) {
        listOf(
            android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_DOWN, code, 0),
            android.view.KeyEvent(0, 500, android.view.KeyEvent.ACTION_DOWN, code, 1),
            android.view.KeyEvent(0, 550, android.view.KeyEvent.ACTION_DOWN, code, 2),
            android.view.KeyEvent(0, 600, android.view.KeyEvent.ACTION_UP, code, 0),
        ).forEach {
            val roots = compose.onAllNodes(androidx.compose.ui.test.isRoot())
            roots[roots.fetchSemanticsNodes().size - 1].performKeyPress(androidx.compose.ui.input.key.KeyEvent(it))
            compose.mainClock.advanceTimeBy(100); compose.waitForIdle()
        }
        compose.mainClock.advanceTimeBy(1200); compose.waitForIdle()
    }

    private fun hasWords(words: String, exact: Boolean = false) =
        compose.onAllNodes(androidx.compose.ui.test.hasText(words, substring = !exact) or androidx.compose.ui.test.hasContentDescription(words, substring = !exact)).fetchSemanticsNodes().isNotEmpty()

    /** Rows one at a time say which row this is, and holding ▲ goes straight back to the first. */
    @Test fun rowMarkerAndHoldUp() = shoot("tv-row-marker", setup = { c -> c.displaySettings.savePrefs("user-1", rowsHome) }, act = {
        press("down", "down", "down")
        compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
        check(hasWords("Row 4 of")) { "the marker should say the fourth row" }
        holdKey(android.view.KeyEvent.KEYCODE_DPAD_UP)
        check(hasWords("Row 1 of")) { "holding ▲ should go to the first row" }
        check(!focusedHasText("Home") && !menuOpen() && compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().isNotEmpty()) { "focus should be on the first row's cards" }
    }) { c -> WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) } }

    /** Full art: the marker too, and holding ▲ to the first row. */
    @Test fun fullArtMarkerAndHoldUp() = shoot("tv-full-art-marker", act = {
        press("down", "down")
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        check(hasWords("Row 3 of")) { "the marker should say the third row" }
        holdKey(android.view.KeyEvent.KEYCODE_DPAD_UP)
        check(hasWords("Row 1 of")) { "holding ▲ should go to the first row" }
    }) { c -> WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) } }

    /** The focus styles other than Grow keep the card its size. */
    private fun focusStyle(name: String, style: dev.nitrate.core.pages.FocusStyle) = shoot(name, setup = { c ->
        c.displaySettings.savePrefs("user-1", rowsHome.copy(tvFocusStyle = style))
    }, act = { press("right"); compose.mainClock.advanceTimeBy(800); compose.waitForIdle() }) { c ->
        WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) }
    }
    @Test fun focusStyleOutline() = focusStyle("tv-focus-outline", dev.nitrate.core.pages.FocusStyle.OUTLINE)
    @Test fun focusStyleGlow() = focusStyle("tv-focus-glow", dev.nitrate.core.pages.FocusStyle.GLOW)
    @Test fun focusStyleLift() = focusStyle("tv-focus-lift", dev.nitrate.core.pages.FocusStyle.LIFT)

    /** The clock, top end, with full art's tabs moved aside for it. */
    @Test fun pageClock() = shoot("tv-page-clock", setup = { c ->
        c.displaySettings.savePrefs("user-1", dev.nitrate.core.pages.DisplayPrefs(tvClock = true))
    }, act = {
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        val times = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("a time") { n ->
            n.config.firstOrNull { it.key.name == "Text" }?.value.toString().contains(Regex("\\d{1,2}:\\d{2}"))
        }).fetchSemanticsNodes()
        check(times.isNotEmpty()) { "the clock should show a time" }
    }) { c ->
        val pages = listOf(dev.nitrate.core.pages.CustomPage("kids", "Kids", pinned = true))
        WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}, customPages = pages) }
    }

    /** Square, tightly spaced cards. */
    @Test fun squareTightCards() = shoot("tv-cards-square-tight", setup = { c ->
        c.displaySettings.savePrefs("user-1", rowsHome.copy(tvCardCorners = dev.nitrate.core.pages.CardCorners.SQUARE, tvCardSpacing = dev.nitrate.core.pages.CardSpacing.TIGHT))
    }, act = { press("down", "down", "right"); compose.mainClock.advanceTimeBy(800); compose.waitForIdle() }) { c ->
        WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) }
    }

    /** Channel/Page keys step through Home's tabs from the rows, without going up to the tabs. */
    @Test fun channelKeysChangeTabs() = shoot("tv-channel-keys-tabs", setup = { c -> c.displaySettings.savePrefs("user-1", rowsHome) }, act = {
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        val roots = compose.onAllNodes(androidx.compose.ui.test.isRoot())
        fun key(code: Int) {
            listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP).forEach { a ->
                roots[roots.fetchSemanticsNodes().size - 1].performKeyPress(androidx.compose.ui.input.key.KeyEvent(android.view.KeyEvent(0, 0, a, code, 0)))
            }
            compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
        }
        key(android.view.KeyEvent.KEYCODE_CHANNEL_UP)
        check(focusedHasText("Kids")) { "Channel up should open the next tab (Kids)" }
        key(android.view.KeyEvent.KEYCODE_CHANNEL_UP)
        check(focusedHasText("Kids")) { "past the last tab nothing should happen" }
        key(android.view.KeyEvent.KEYCODE_PAGE_UP)
        // Home's own page comes back with focus on the card left in its rows, its tab chosen.
        check(compose.onAllNodes(androidx.compose.ui.test.isSelected() and androidx.compose.ui.test.hasText("Home")).fetchSemanticsNodes().isNotEmpty()) { "Page up should go back to Home's tab" }
    }) { c ->
        val pages = listOf(dev.nitrate.core.pages.CustomPage("kids", "Kids", pinned = true))
        WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}, customPages = pages) }
    }

    /** Continue Watching and Next Up as one row on Home. */
    @Test fun continueAndNextUpCombined() = shoot("tv-continue-and-next", setup = { c ->
        c.displaySettings.savePrefs("user-1", rowsHome.copy(tvArrangement = dev.nitrate.core.pages.PageArrangement.STACKED, combineContinueNextUp = true))
    }, act = {
        compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
        check(hasWords(dev.nitrate.core.rows.ContinueAndNext.TITLE)) { "the combined row should show" }
        check(!hasWords("Next Up", exact = true)) { "Next Up shouldn't show on its own" }
    }) { c -> WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) } }

    /** A row set to load more grows as focus nears its end; wrap round goes from the last card back to the first. */
    @Test fun rowLoadsMoreAndWrapsRound() = shoot("tv-row-load-more", setup = { c ->
        c.displaySettings.savePrefs("user-1", rowsHome)
        val movies = c.server.homeRows().first { it.key == "home/latest/lib-movies" }
        c.displaySettings.save("user-1", movies, dev.nitrate.core.rows.RowDisplay(loadMore = true, wrapRound = true))
    }, act = {
        fun at() = compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().first().boundsInRoot
        fun label(n: String) = compose.onAllNodes(androidx.compose.ui.test.hasText(n, substring = true)).fetchSemanticsNodes().isNotEmpty()
        press("down", "down")
        val first = at()
        // Along the row: with load more it runs past the four it started with.
        repeat(6) { press("right") }
        compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
        check(label("/ 2") || label("/ 3")) { "the row should have grown past its first four titles" }
        // To the very end (all 20 loaded) and once more: back round to the first card.
        repeat(13) { press("right"); compose.mainClock.advanceTimeBy(200); compose.waitForIdle() }
        check(label("20 / 20")) { "the row should end at 20 titles (4, then 16 more)" }
        press("right")
        compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
        check(label("1 / 20")) { "past the last card should wrap to the first" }
    }) { c -> WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) } }

    /** Titles only on the focused card, and logo cards on a wide row. */
    @Test fun titlesOnFocusAndLogoCards() = shoot("tv-titles-on-focus-logos", setup = { c ->
        c.displaySettings.savePrefs("user-1", rowsHome.copy(tvArrangement = dev.nitrate.core.pages.PageArrangement.STACKED))
        val rows = c.server.homeRows()
        c.displaySettings.save("user-1", rows[0], dev.nitrate.core.rows.RowDisplay(dev.nitrate.core.rows.RowStyle.WIDE, showTitles = true, titlesOnFocus = true))
        c.displaySettings.save("user-1", rows[2], dev.nitrate.core.rows.RowDisplay(dev.nitrate.core.rows.RowStyle.WIDE, logoCards = true))
    }, act = { press("right"); compose.mainClock.advanceTimeBy(800); compose.waitForIdle() }) { c ->
        WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) }
    }

    /** Progress as a small circle in the card's corner. */
    @Test fun progressCircle() = shoot("tv-progress-circle", setup = { c ->
        c.displaySettings.savePrefs("user-1", rowsHome.withProgress(dev.nitrate.core.pages.ProgressStyle.CIRCLE))
    }, act = { compose.mainClock.advanceTimeBy(800); compose.waitForIdle() }) { c ->
        WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) }
    }

    /** The caption box: the words on a dark box, no shade along the screen's edge. */
    @Test fun captionBox() = shoot("tv-caption-box", setup = { c ->
        c.displaySettings.savePrefs("user-1", rowsHome.copy(tvCaptionBox = true))
    }, act = { compose.mainClock.advanceTimeBy(800); compose.waitForIdle() }) { c ->
        WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) }
    }

    /** Leaving a row and coming back lands on the card left in it, whatever was done in other rows meanwhile. */
    @Test fun rowsRememberTheirCard() = shoot("tv-rows-remember-card", setup = { c ->
        c.displaySettings.savePrefs("user-1", rowsHome)
        val rows = c.server.homeRows()
        c.displaySettings.save("user-1", rows[2], dev.nitrate.core.rows.RowDisplay(dev.nitrate.core.rows.RowStyle.LIGHTNING))
    }, act = {
        fun at() = steadyFocus()
        press("right")
        val first = at()
        press("down", "down", "right", "right")
        val bill = at()
        press("up")
        check(at() != bill) { "Up should leave the lightning row" }
        press("down")
        check(at() == bill) { "Back down should land on the same side of the same card: ${at()} vs $bill" }
        press("down", "up")
        check(at() == bill) { "Back up should land on it too: ${at()} vs $bill" }
        press("up", "up")
        check(at() == first) { "Continue Watching should still be on its second card: ${at()} vs $first" }
    }) { c -> WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) } }

    /** Home with tabs opens (or is come back to, e.g. Back from a library) on the first row's card, not the tabs. */
    @Test fun homeWithTabsOpensOnRows() = shoot("tv-home-tabs-opens-on-rows", setup = { c ->
        c.displaySettings.savePrefs("user-1", rowsHome)
    }, act = {
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        check(compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().isNotEmpty()) { "something should have focus" }
        check(!focusedHasText("Home") && !focusedHasText("Kids")) { "Home opened with focus on the tabs" }
        check(compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().first().boundsInRoot.top > 400f) { "focus should be on the row along the bottom" }
    }) { c ->
        val pages = listOf(dev.nitrate.core.pages.CustomPage("kids", "Kids", pinned = true))
        WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}, customPages = pages) }
    }

    /** A row's own caption size wins over the page's: Compact on this row, so no description. */
    @Test fun rowCaptionSize() = shoot("tv-row-caption-compact", setup = { c ->
        c.displaySettings.savePrefs("user-1", rowsHome)
        val rows = c.server.homeRows()
        c.displaySettings.save("user-1", rows[0], dev.nitrate.core.rows.RowDisplay(dev.nitrate.core.rows.RowStyle.WIDE, showTitles = true, captionSize = dev.nitrate.core.pages.CaptionSize.COMPACT))
    }, act = {
        compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("projectionist discovers", substring = true)).fetchSemanticsNodes().isEmpty()) { "the row's compact caption shouldn't show a description" }
        press("down")
        compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
    }) { c -> WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) } }

    /** Customize → This page → How it's laid out, over Home: a change is this page's own, every other page keeps its layout. */
    @Test fun customizeLayoutPerPage() {
        lateinit var container: AppContainer
        shoot("tv-customize-layout", setup = { c -> container = c; c.displaySettings.savePrefs("user-1", rowsHome) }, act = {
            compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
            // This page, down past what's on it and how it looks, to how it's laid out.
            repeat(8) { if (!focusedHasText("Arrangement")) { press("down"); compose.mainClock.advanceTimeBy(200); compose.waitForIdle() } }
            check(focusedHasText("Arrangement")) { "the page's layout choices should be in This page" }
            press("ok")
            // The save lands a beat later on a slow machine: wait for it rather than a fixed time.
            var stored = runBlocking { container.displaySettings.prefs("user-1").first() }
            repeat(50) {
                if (stored.pageLooks["home"]?.arrangement == dev.nitrate.core.pages.PageArrangement.STACKED) return@repeat
                compose.mainClock.advanceTimeBy(200); compose.waitForIdle(); Thread.sleep(50)
                stored = runBlocking { container.displaySettings.prefs("user-1").first() }
            }
            check(stored.pageLooks["home"]?.arrangement == dev.nitrate.core.pages.PageArrangement.STACKED) { "Home should have stacked rows of its own: ${stored.pageLooks}" }
            check(stored.tvArrangement == dev.nitrate.core.pages.PageArrangement.ROWS) { "every page's layout should be unchanged" }
            check(compose.onAllNodes(androidx.compose.ui.test.hasText("Stacked rows", substring = true)).fetchSemanticsNodes().isNotEmpty()) { "the choice should show" }
        }) { c ->
            WithMenu(c) {
                HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {})
                dev.nitrate.tv.ui.pages.CustomizePanel(c, PageKeys.HOME, dev.nitrate.tv.ui.pages.CustomizeRequest(null, null, dev.nitrate.tv.ui.pages.CustomizeSection.PAGE)) {}
            }
        }
    }

    /** Customize on a library: Library list switches its full list to the scrolling grid, for that library only. */
    @Test fun customizeLibraryList() {
        lateinit var container: AppContainer
        shoot("tv-customize-library-list", setup = { c -> container = c; c.displaySettings.savePrefs("user-1", rowsLibrary) }, act = {
            compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
            repeat(16) { if (!focusedHasText("Library list")) { press("down"); compose.mainClock.advanceTimeBy(200); compose.waitForIdle() } }
            check(focusedHasText("Library list")) { "a library's layout choices should include Library list" }
            check(focusedHasText("Fixed grid")) { "Library list should say the grid in use" }
            press("ok")
            var stored = runBlocking { container.displaySettings.prefs("user-1").first() }
            repeat(50) {
                if (stored.pageLooks["library/lib-movies"]?.gridLayout == dev.nitrate.core.pages.GridLayout.SCROLLING) return@repeat
                compose.mainClock.advanceTimeBy(200); compose.waitForIdle(); Thread.sleep(50)
                stored = runBlocking { container.displaySettings.prefs("user-1").first() }
            }
            check(stored.pageLooks["library/lib-movies"]?.gridLayout == dev.nitrate.core.pages.GridLayout.SCROLLING) { "the library should have the scrolling grid of its own: ${stored.pageLooks}" }
            check(stored.tvGridLayout == dev.nitrate.core.pages.GridLayout.FIXED) { "every page's grid should be unchanged" }
            check(compose.onAllNodes(androidx.compose.ui.test.hasText("Scrolling grid", substring = true)).fetchSemanticsNodes().isNotEmpty()) { "the choice should show" }
        }) { c ->
            WithMenu(c) {
                dev.nitrate.tv.ui.pages.CustomizePanel(c, PageKeys.library("lib-movies"), dev.nitrate.tv.ui.pages.CustomizeRequest(null, null, dev.nitrate.tv.ui.pages.CustomizeSection.PAGE)) {}
            }
        }
    }

    /** Customize → Theme: the picker closes on the press that chose, and the choice is still saved (it sometimes wasn't). */
    @Test fun customizeThemeSticks() {
        lateinit var container: AppContainer
        shoot("tv-customize-theme", setup = { c -> container = c; c.displaySettings.savePrefs("user-1", rowsHome) }, act = {
            compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
            repeat(6) { if (!focusedHasText("Theme")) { press("down"); compose.mainClock.advanceTimeBy(200); compose.waitForIdle() } }
            check(focusedHasText("Theme")) { "Theme should be in This page" }
            press("ok")
            compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
            press("down")
            compose.mainClock.advanceTimeBy(300); compose.waitForIdle()
            press("ok")
            var stored = runBlocking { container.displaySettings.prefs("user-1").first() }
            repeat(50) {
                if (stored.pageThemes["home"] != null) return@repeat
                compose.mainClock.advanceTimeBy(200); compose.waitForIdle(); Thread.sleep(50)
                stored = runBlocking { container.displaySettings.prefs("user-1").first() }
            }
            check(stored.pageThemes["home"] != null) { "the picked theme should be saved: ${stored.pageThemes}" }
        }) { c ->
            WithMenu(c) {
                HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {})
                dev.nitrate.tv.ui.pages.CustomizePanel(c, PageKeys.HOME, dev.nitrate.tv.ui.pages.CustomizeRequest(null, null, dev.nitrate.tv.ui.pages.CustomizeSection.PAGE)) {}
            }
        }
    }

    /** Customize → This page → Tabs opens Home's tabs (it crashed: a lazy list inside a scrolling dialog). */
    @Test fun customizePageTabs() = shoot("tv-customize-tabs", setup = { c -> c.displaySettings.savePrefs("user-1", rowsHome) }, act = {
        compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
        check(focusedHasText("Tabs")) { "This page should open on Tabs when there's no row list" }
        press("ok")
        compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("Its rows: order, hide", substring = true)).fetchSemanticsNodes().isNotEmpty()) { "Home's tabs should open" }
    }) { c ->
        WithMenu(c) {
            HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {})
            dev.nitrate.tv.ui.pages.CustomizePanel(c, PageKeys.HOME, dev.nitrate.tv.ui.pages.CustomizeRequest(null, null, dev.nitrate.tv.ui.pages.CustomizeSection.PAGE)) {}
        }
    }

    /** Customize on a row: the row's look, where it sits, its name and hiding it, with the page beside. */
    @Test fun customizeRow() = shoot("tv-customize-row", setup = { c -> c.displaySettings.savePrefs("user-1", rowsHome) }, act = {
        compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
        check(focusedHasText("Style")) { "the row section should open on Style" }
    }) { c ->
        val vm = androidx.lifecycle.viewmodel.compose.viewModel(key = "test-home") {
            dev.nitrate.android.viewmodel.RowsViewModel(PageKeys.HOME, c.server::homeRows, c.displaySettings, c.server)
        }
        val state by vm.state.collectAsState()
        val row = (state as? dev.nitrate.android.viewmodel.RowsState.Loaded)?.rows?.getOrNull(1)
        WithMenu(c) {
            HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {})
            if (row != null) dev.nitrate.tv.ui.pages.CustomizePanel(c, PageKeys.HOME, dev.nitrate.tv.ui.pages.CustomizeRequest(row, vm, dev.nitrate.tv.ui.pages.CustomizeSection.ROW)) {}
        }
    }

    /** The menu's "Customize this page" stop (before Settings) opens the Customize panel on the page. */
    @Test fun customizeMenuStop() = shoot("tv-customize-stop", setup = { c -> c.displaySettings.savePrefs("user-1", rowsHome) }, act = {
        // The overlay shows nothing until called.
        press("menu")
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Customize this page") or androidx.compose.ui.test.hasText("Customize this page"), useUnmergedTree = true)
            .assertExists()
        compose.onAllNodes(androidx.compose.ui.test.hasClickAction() and (androidx.compose.ui.test.hasAnyDescendant(androidx.compose.ui.test.hasContentDescription("Customize this page")) or androidx.compose.ui.test.hasAnyDescendant(androidx.compose.ui.test.hasText("Customize this page"))), useUnmergedTree = true)
            .onFirst().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(1000); compose.waitForIdle()
        compose.onAllNodesWithText("This page", substring = true).onFirst().assertExists()
    }) { c -> WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) } }

    /** Customize a row's style and close the panel: focus comes back to the row, never the menu. */
    @Test fun customizeStyleKeepsFocus() = shoot("tv-customize-style-focus", setup = { c ->
        c.displaySettings.savePrefs("user-1", rowsHome)
    }, act = {
        press("down", "right", "right")
        // Hold OK: the panel on this row.
        val ok = android.view.KeyEvent.KEYCODE_DPAD_CENTER
        listOf(
            android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_DOWN, ok, 0),
            android.view.KeyEvent(0, 500, android.view.KeyEvent.ACTION_DOWN, ok, 1),
            android.view.KeyEvent(0, 550, android.view.KeyEvent.ACTION_DOWN, ok, 2),
            android.view.KeyEvent(0, 600, android.view.KeyEvent.ACTION_UP, ok, 0),
        ).forEach {
            val roots = compose.onAllNodes(androidx.compose.ui.test.isRoot())
            roots[roots.fetchSemanticsNodes().size - 1].performKeyPress(androidx.compose.ui.input.key.KeyEvent(it))
            compose.mainClock.advanceTimeBy(100); compose.waitForIdle()
        }
        compose.mainClock.advanceTimeBy(1000); compose.waitForIdle()
        check(focusedHasText("Style")) { "the panel should open on Style" }
        // Through the styles to Posters (a different card for every title), then close.
        repeat(dev.nitrate.core.rows.RowStyle.choices.size - 1) { press("ok"); compose.mainClock.advanceTimeBy(600); compose.waitForIdle() }
        press("back")
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.mainClock.advanceTimeBy(100)
            compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.mainClock.advanceTimeBy(2000); compose.waitForIdle()
        // The menu is hidden until called, so the row's first card can sit near the edge: check the menu isn't open.
        check(!menuOpen()) { "Closing the panel should leave focus in the row, not the menu; focused: ${focusedNodes()}" }
        check(compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().isNotEmpty()) { "focus stays on the page" }
    }) { c -> WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) } }

    /** To a details page and Back: Home comes back with focus on the card left, not its start. */
    @Test fun backFromDetailsKeepsPlace() {
        var onDetails by androidx.compose.runtime.mutableStateOf(false)
        shoot("tv-back-from-details", setup = { c -> c.displaySettings.savePrefs("user-1", rowsHome) }, act = {
            fun at() = compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().first().boundsInRoot
            press("down", "down", "right")
            val left = at()
            compose.runOnIdle { onDetails = true }
            compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
            compose.runOnIdle { onDetails = false }
            runCatching { compose.waitUntil(timeoutMillis = 10_000) {
                compose.mainClock.advanceTimeBy(100)
                compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().firstOrNull()?.boundsInRoot == left
            } }
            check(compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().firstOrNull()?.boundsInRoot == left) {
                "should come back to $left, focused: " + compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().map { it.boundsInRoot }
            }
            check(!focusedHasText("Home") && !focusedHasText("Kids")) { "came back onto the tabs" }
        }) { c ->
            val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
            val pages = listOf(dev.nitrate.core.pages.CustomPage("kids", "Kids", pinned = true))
            if (onDetails) androidx.tv.material3.Text("Details") else holder.SaveableStateProvider("home") {
                WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}, customPages = pages) }
            }
        }
    }

    /** A library's title, its details page and Back: focus back on that title, not the tabs. */
    @Test fun libraryBackFromDetailsKeepsPlace() {
        var onDetails by androidx.compose.runtime.mutableStateOf(false)
        shoot("tv-library-back-from-details", setup = { c -> c.displaySettings.savePrefs("user-1", rowsLibrary) }, act = {
            fun at() = compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().first().boundsInRoot
            compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
            press("down", "right")
            val left = at()
            check(!focusedHasText("Recommended")) { "should be on a title" }
            compose.runOnIdle { onDetails = true }
            compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
            compose.runOnIdle { onDetails = false }
            runCatching { compose.waitUntil(timeoutMillis = 10_000) {
                compose.mainClock.advanceTimeBy(100)
                compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().firstOrNull()?.boundsInRoot == left
            } }
            check(at() == left) { "should come back to $left, got ${at()}" }
        }) { c ->
            val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
            if (onDetails) androidx.tv.material3.Text("Details") else holder.SaveableStateProvider("library") {
                WithMenu(c) { dev.nitrate.tv.ui.library.LibraryScreen(c.server, c.displaySettings, "lib-movies", onOpen = {}, onPlay = {}) }
            }
        }
    }

    /** In a library on a tab other than the first, Back goes to the first tab (not out of the library). */
    @Test fun libraryBackToFirstTab() = libraryShot("tv-library-back-to-first-tab", rowsLibrary) {
        press("right", "ok")
        compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
        check(focusedHasText("All")) { "should be on the All tab" }
        // The remote's Back reaches the app as the activity's back press.
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
        check(focusedHasText("Recommended")) { "Back should go to the first tab" }
    }

    /** Home with tabs: Up from the second row lands on the first row's card, not the tabs. */
    @Test fun upToFirstRowNotTabs() = shoot("tv-up-to-first-row", setup = { c ->
        c.displaySettings.savePrefs("user-1", rowsHome)
    }, act = {
        fun at() = compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().first().boundsInRoot
        press("down")
        val first = at()
        press("down")
        val second = at()
        check(second != first) { "Down should reach Next Up" }
        press("up")
        check(!focusedHasText("Home") && !focusedHasText("Kids") && !focusedHasText("View")) { "Up from Next Up went to the tabs" }
        check(at() == first) { "Up should come back to Continue Watching's card: ${at()} vs $first" }
    }) { c ->
        val pages = listOf(dev.nitrate.core.pages.CustomPage("kids", "Kids", pinned = true))
        WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}, customPages = pages) }
    }

    /** Art rows (design-size cards) stay along the bottom when moved along, not pulled up the page. */
    @Test fun cressidaStaysAtBottom() = shoot("tv-cressida-stays-at-bottom", setup = { c ->
        c.displaySettings.savePrefs("user-1", rowsHome)
        c.displaySettings.save("user-1", c.server.homeRows()[1], dev.nitrate.core.rows.RowDisplay(dev.nitrate.core.rows.RowStyle.CRESSIDA))
    }, act = {
        fun at() = compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().first().boundsInRoot
        press("down")
        val first = at()
        press("right"); press("up"); press("down")
        check(at().top == first.top) { "The row moved: was $first, now ${at()}" }
        check(at().bottom > 700f) { "The row should sit along the bottom, got ${at()}" }
    }) { c -> WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) } }

    /** A library with more tabs switched on (Genres, and your own) and Genres open. */
    @Test fun libraryGenresTab() = shoot("tv-library-genres-tab", setup = { c ->
        c.displaySettings.savePrefs("user-1", rowsLibrary)
        c.displaySettings.saveLibraryTabs("user-1", "lib-movies", listOf(
            dev.nitrate.core.pages.LibraryTab("genres", dev.nitrate.core.pages.LibraryTabKind.GENRES),
            dev.nitrate.core.pages.LibraryTab("recommended", dev.nitrate.core.pages.LibraryTabKind.RECOMMENDED),
            dev.nitrate.core.pages.LibraryTab("all", dev.nitrate.core.pages.LibraryTabKind.ALL),
            dev.nitrate.core.pages.LibraryTab("own1", dev.nitrate.core.pages.LibraryTabKind.OWN, "Friday night"),
        ))
    }) { c -> WithMenu(c) { dev.nitrate.tv.ui.library.LibraryScreen(c.server, c.displaySettings, "lib-movies", onOpen = {}, onPlay = {}) } }

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

    /** Admins reach a channel's editor from a visible button too, not only by holding OK. */
    @Test fun channelsPickToEdit() {
        shoot("tv-channels-edit-which", act = { compose.onNodeWithText("Edit a channel").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) }) { c ->
            dev.nitrate.tv.ui.channels.ChannelsScreen(c.server, admin = true, onPlayQueue = {}, onEdit = {})
        }
        compose.onNodeWithText("Edit which channel?").assertExists()
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

    /** A scene's comments each have a visible Resolve (holding OK on one does the same). */
    @Test fun storySceneComments() {
        shoot("tv-story-scene-comments", act = {
            compose.onAllNodesWithText("1. INT. DINER - NIGHT", substring = true).onFirst().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        }) {
            dev.nitrate.tv.plugins.writing.StoryTvPanel(storyContext(), dev.nitrate.tv.plugins.writing.StoryTvTab.SCENES, onBack = {}, modifier = Modifier.fillMaxSize().padding(40.dp))
        }
        compose.onNodeWithText("Can Jo's line land harder?").assertExists()
        compose.onNodeWithText("Resolve").assertExists()
    }

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

    @Test fun guide() = shoot("tv-guide") { _ -> dev.nitrate.tv.ui.settings.GuideScreen(admin = true) }

    /** The trail as a test result shows it: in a dialog 900 wide, with room for two columns. */
    @Test fun playbackTrailInDialog() = shoot("tv-playback-trail-dialog") { _ ->
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            androidx.compose.foundation.layout.Box(Modifier.width(852.dp)) { dev.nitrate.tv.ui.player.TrailView(dev.nitrate.testing.sampleTrail()) }
        }
    }

    @Test fun householdDevices() = shoot("tv-household-devices") { c -> dev.nitrate.tv.ui.settings.HouseholdDevicesScreen(c, onOpen = {}) }

    @Test fun tvCheck() = shoot("tv-tv-check", setup = { c ->
        c.playbackSettings.update { it.copy(deviceCheck = dev.nitrate.core.playback.DeviceCheck(0, "TV", seen = mapOf(
            "hdr10" to dev.nitrate.core.playback.SeenClip(dev.nitrate.core.playback.Answer.YES, "HDR10", "HDR10 · 4K", 1),
            "dolby_truehd_51" to dev.nitrate.core.playback.SeenClip(dev.nitrate.core.playback.Answer.NO, "truehd", "Dolby TrueHD 5.1", 1, passthrough = true),
        ))) }
    }) { c -> dev.nitrate.tv.ui.settings.TvCheckScreen(c) }

    @Test fun playbackTrail() = shoot("tv-playback-trail") { _ ->
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(40.dp)) { dev.nitrate.tv.ui.player.TrailView(dev.nitrate.testing.sampleTrail()) }
    }

    @Test fun playbackOverview() = shoot("tv-playback-overview") { c ->
        dev.nitrate.tv.ui.settings.PlaybackOverviewScreen(c, onOpen = {})
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
                )
            }
        }
        androidx.compose.runtime.LaunchedEffect(Unit) { focus.playPause() }
    }

    /** The real player before it's ready (buttons not yet usable): where focus is still shows plainly. */
    @Test fun playerNotReadyFocus() = shoot("tv-player-not-ready-focus", act = {
        press("right", "right", "right")
        check(focusedHasText("Info")) { "focus should be on Info" }
    }) { c ->
        dev.nitrate.tv.ui.player.PlayerScreen(c.server, c.playbackSettings, "s1e2", fromStart = true, onExit = {})
    }

    /** The remote's menu key hides the controls, and shows them again with focus on play/pause. */
    @Test fun playerMenuKey() = shoot("tv-player-menu-key", act = {
        press("right")
        press("menu")
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("Subtitles")).fetchSemanticsNodes().isEmpty()) { "Menu should hide the controls" }
        press("menu")
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("Subtitles")).fetchSemanticsNodes().isNotEmpty()) { "Menu should show the controls" }
        check(compose.onAllNodes(androidx.compose.ui.test.isFocused() and androidx.compose.ui.test.hasContentDescription("Play")).fetchSemanticsNodes().isNotEmpty()) { "focus on play/pause" }
    }) { c ->
        dev.nitrate.tv.ui.player.PlayerScreen(c.server, c.playbackSettings, "s1e2", fromStart = true, onExit = {})
    }

    /** The real player on an episode with group notes (Companion `notes`), its group chat a [chatAt] into the episode. */
    private fun groupChatPlayer(name: String, easy: Boolean = false, act: () -> Unit) = shoot(name, setup = { c ->
        (c.server as dev.nitrate.testing.FakeServer).companionFeatures += "notes"
        c.refreshCompanionForTests()
        if (easy) c.displaySettings.savePrefs("user-1", dev.nitrate.core.pages.DisplayPrefs(easyMode = true))
    }, act = act) { c ->
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val chat = androidx.compose.runtime.remember { c.timelineChat(scope, live = false) }
        dev.nitrate.tv.ui.player.PlayerScreen(c.server, c.playbackSettings, "s1e2", fromStart = true, onExit = {}, groupChat = chat)
    }

    private fun focusedNodes() = compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().joinToString { it.config.toString() }

    /**
     * The player's group button where only the chat is involved: it opens the group panel on the
     * chat line, ▼ reaches the reactions and then the text field (it used to fall out to the
     * player), focus stays in the panel, and Back closes it with focus back on the button.
     */
    @Test fun playerGroupChatFocus() = groupChatPlayer("tv-player-group-chat") {
        repeat(8) { if (!focusedHasText("Group chat")) press("right") }
        check(focusedHasText("Group chat")) { "should reach the group button; focused: ${focusedNodes()}" }
        press("ok")
        compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
        check(focusedHasText("Group chat: On")) { "the panel opens on the chat line; focused: ${focusedNodes()}" }
        press("down")
        check(dev.nitrate.core.companion.QUICK_REACTIONS.take(5).any { focusedHasText(it) }) { "▼ reaches the reactions; focused: ${focusedNodes()}" }
        press("down", "down")
        check(focusedHasText("No way!")) { "▼ from the reactions reaches the quick lines; focused: ${focusedNodes()}" }
        press("down")
        check(focusedHasText("Type a message")) { "▼ from the lines should reach the text field; focused: ${focusedNodes()}" }
        // Further down and sideways: still inside the panel.
        press("down", "down", "right", "left")
        check(compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().isNotEmpty()) { "focus must stay in the panel" }
        check(!focusedHasText("Audio") && !focusedHasText("Subtitles")) { "focus must not fall out to the controls; focused: ${focusedNodes()}" }
        press("up", "up")
        check(focusedHasText("Type a message") || focusedHasText("No way!") || dev.nitrate.core.companion.QUICK_REACTIONS.any { focusedHasText(it) }) { "▲ goes back up the panel; focused: ${focusedNodes()}" }
        press("back")
        compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("Type a message")).fetchSemanticsNodes().isEmpty()) { "Back closes the panel" }
        check(focusedHasText("Group chat")) { "focus returns to the button that opened it; focused: ${focusedNodes()}" }
    }

    /**
     * The real player on an episode of a show shared with two watch groups (Will; Frank, Tommy and
     * Jane), played from Will's card, with its group button; with [chats] the server keeps chat histories.
     */
    private fun withPlayer(name: String, easy: Boolean = false, chats: Boolean = false, favourites: Boolean = false, act: () -> Unit) = shoot(name, setup = { c ->
        if (chats) (c.server as dev.nitrate.testing.FakeServer).companionFeatures += listOf("notes", "chats", "sessions")
        if (favourites) (c.server as dev.nitrate.testing.FakeServer).companionFeatures += "chatfavourites"
        c.refreshCompanionForTests()
        // Played from Will's card on Home's Watching together row: the guards hand that on to the player.
        c.plugins.checkBeforePlay(c.server.getItem("s1e2"), dev.nitrate.core.partners.PlayOrigin.GroupCard("g1")) { dev.nitrate.core.plugins.GuardAction.PlayAnyway }
        if (easy) c.displaySettings.savePrefs("user-1", dev.nitrate.core.pages.DisplayPrefs(easyMode = true))
    }, act = act) { c ->
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val chat = androidx.compose.runtime.remember { c.timelineChat(scope, live = false) }
        dev.nitrate.tv.ui.player.PlayerScreen(c.server, c.playbackSettings, "s1e2", fromStart = true, onExit = {}, watchGroups = c, groupChat = chat)
    }

    /** A key to the open dialog's window, whichever root it is. */
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    private fun pressInDialog(name: String) {
        val k = when (name) { "ok" -> androidx.compose.ui.input.key.Key.DirectionCenter; "back" -> androidx.compose.ui.input.key.Key.Back; "up" -> androidx.compose.ui.input.key.Key.DirectionUp; else -> androidx.compose.ui.input.key.Key.DirectionDown }
        compose.onAllNodes(androidx.compose.ui.test.isDialog()).onFirst().performKeyInput { pressKey(k) }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
    }

    private fun reachGroupButton(name: String = "Will") {
        repeat(8) { if (!focusedIs(name)) press("right") }
        check(focusedIs(name)) { "the group button says the group it counts for; focused: ${focusedNodes()}" }
    }

    private fun focusedIs(text: String) =
        compose.onAllNodes(androidx.compose.ui.test.isFocused() and androidx.compose.ui.test.hasText(text, substring = false)).fetchSemanticsNodes().isNotEmpty()

    private fun chooseExact(text: String) {
        compose.onNode(androidx.compose.ui.test.hasClickAction() and androidx.compose.ui.test.hasText(text, substring = false)).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
    }

    private fun choose(text: String) {
        compose.onNode(androidx.compose.ui.test.hasClickAction() and androidx.compose.ui.test.hasText(text, substring = true)).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
    }

    /** The group button in the controls (in place of the old With… chip and chat button): the group's name, a button like the others. */
    @Test fun playerGroupButton() = withPlayer("tv-player-group-button") {
        reachGroupButton()
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("Group chat: ", substring = true)).fetchSemanticsNodes().isEmpty()) { "no separate chat button" }
    }

    /** OK on it opens the group panel beside the video: who it counts for, the chat (a new one), Start a Group Watch Session. */
    @Test fun playerGroupPanel() = withPlayer("tv-player-group-panel", chats = true) {
        reachGroupButton()
        press("ok")
        compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
        check(focusedHasText("Counting for you and Will · Change")) { "the panel opens on its top line; focused: ${focusedNodes()}" }
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("Watching on your own")).fetchSemanticsNodes().isNotEmpty()) { "it says you watch on your own" }
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("Chat: New chat")).fetchSemanticsNodes().isNotEmpty()) { "a new chat by default" }
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("The opening shot!")).fetchSemanticsNodes().isEmpty()) { "nothing earlier unasked" }
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("Start a Group Watch Session")).fetchSemanticsNodes().isNotEmpty()) { "Start a Group Watch Session is offered" }
    }

    /**
     * Chats, reached with the D-pad from the panel: New chat, then this group's earlier chats
     * (newest first, one building on another); picking one plays it along, and Back on the panel
     * returns focus to the group button.
     */
    @Test fun playerGroupChats() = withPlayer("tv-player-group-chats", chats = true) {
        reachGroupButton()
        press("ok")
        compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
        press("down")
        check(focusedHasText("Chat: New chat")) { "▼ reaches the chat line; focused: ${focusedNodes()}" }
        press("ok")
        compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
        check(focusedHasText("Group chat: On")) { "Chats opens on its first line; focused: ${focusedNodes()}" }
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("builds on", substring = true)).fetchSemanticsNodes().isNotEmpty()) { "a chat building on another says so" }
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("Frank, Tommy", substring = true)).fetchSemanticsNodes().isEmpty()) { "another group's chats aren't offered" }
        repeat(6) { if (!focusedExact("14 messages")) press("down") }
        check(focusedExact("14 messages")) { "the earlier chats are reachable; focused: ${focusedNodes()}" }
        check(textMatches(Regex("\\d{4}/\\d{2}/\\d{2} · Will")) && textMatches(Regex("14 messages"))) { "earlier chats are dated by the device's calendar" }
        check(textMatches(Regex("3 new · builds on \\d{4}/\\d{2}/\\d{2} \\(Will · 14 messages\\)"))) { "and what they build on too" }
    }

    /**
     * Each earlier chat has a heart: ▶ from its line reaches it, focused visibly, saying "Favourite"
     * (Tuesday's with Will is one) or "Not a favourite"; OK changes it there and then.
     */
    @Test fun playerGroupChatsHearts() = withPlayer("tv-player-group-chats-hearts", chats = true, favourites = true) {
        reachGroupButton()
        press("ok")
        compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
        choose("Chat: New chat")
        repeat(6) { if (!focusedExact("14 messages")) press("down") }
        check(focusedExact("14 messages")) { "on Tuesday's chat; focused: ${focusedNodes()}" }
        press("right")
        check(focusedHasDescription("Favourite")) { "▶ reaches its heart, a favourite; focused: ${focusedNodes()}" }
        press("ok")
        check(focusedHasDescription("Not a favourite")) { "OK takes it away; focused: ${focusedNodes()}" }
        press("ok")
        check(focusedHasDescription("Favourite")) { "and puts it back; focused: ${focusedNodes()}" }
        press("left")
        check(focusedExact("14 messages")) { "◀ back on the chat's line; focused: ${focusedNodes()}" }
        press("up", "right")
        check(focusedHasDescription("Not a favourite")) { "the chat building on it has its own heart; focused: ${focusedNodes()}" }
    }

    /**
     * The panel's quick replies: the title's own emojis first (its tags), then the usual ones; under
     * them its main characters' names and short lines, one press sending a line as a message.
     */
    @Test fun playerGroupQuickReplies() = withPlayer("tv-player-group-quick-replies", chats = true) {
        reachGroupButton()
        press("ok")
        compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("👻")).fetchSemanticsNodes().isNotEmpty()) { "the title's own emoji (haunted)" }
        repeat(6) { if (!focusedHasText("Iris!")) press("down") }
        check(focusedHasText("Iris!")) { "the lead character's name comes first among the lines; focused: ${focusedNodes()}" }
        press("right")
        check(focusedHasText("The Projectionist!")) { "◀▶ along the lines; focused: ${focusedNodes()}" }
        press("left", "ok")
        compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("Iris!")).fetchSemanticsNodes().size >= 2) { "one press sent it: it's in the chat" }
        press("down")
        check(focusedHasText("Type a message")) { "▼ from the lines reaches the text field; focused: ${focusedNodes()}" }
    }

    @Test fun playerGroupChatsPicked() = withPlayer("tv-player-group-chat-picked", chats = true) {
        reachGroupButton()
        press("ok")
        compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
        choose("Chat: New chat")
        chooseExact("14 messages")
        check(focusedHasText("Chat: Playing along")) { "back on the panel, focus on the chat line, which says what plays along; focused: ${focusedNodes()}" }
        press("back")
        compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("Chat: Playing along", substring = true)).fetchSemanticsNodes().isEmpty()) { "Back closes the panel" }
        check(focusedIs("Will")) { "focus returns to the group button; focused: ${focusedNodes()}" }
    }

    /** Change: choosing the other group changes the button; a page's Back goes back a page, the last closes the panel, focus back on the button. */
    @Test fun playerGroupChange() = withPlayer("tv-player-group-change") {
        reachGroupButton()
        press("ok")
        compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
        choose("Counting for you and Will · Change")
        choose("With Frank, Tommy & Jane")
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("Counting for you, Frank, Tommy & Jane · Change")).fetchSemanticsNodes().isNotEmpty()) { "the top line says the new choice" }
        choose("Counting for you, Frank, Tommy & Jane · Change")
        choose("We're in the same room")
        check(focusedHasText("Frank")) { "the room page opens on the first member; focused: ${focusedNodes()}" }
        press("back")
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("No group")).fetchSemanticsNodes().isNotEmpty()) { "Back on the room page goes back to Change" }
        press("back")
        check(focusedHasText("Counting for you, Frank, Tommy & Jane · Change")) { "then to the panel, on the line that opened Change; focused: ${focusedNodes()}" }
        press("back")
        compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
        check(focusedIs("Frank, Tommy & Jane")) { "the next Back closes it, focus back on the button with its new name; focused: ${focusedNodes()}" }
    }

    /** Easy mode: who it's watched with is said beside the buttons, never a button. */
    @Test fun playerWithEasy() = withPlayer("tv-player-with-easy", easy = true) {
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("Counting for you and Will")).fetchSemanticsNodes().isNotEmpty()) { "the chip's words show in easy mode, without Change" }
        repeat(6) { press("right") }
        check(!focusedHasText("Counting for you and Will") && !focusedIs("Will")) { "but it never takes focus" }
    }

    /** The group's notes pop up over the video as playback reaches them: the first two, a few seconds in, not the later one. */
    @Test fun groupChatPopups() = shoot("tv-group-chat-popups", setup = { c ->
        (c.server as dev.nitrate.testing.FakeServer).companionFeatures += "notes"
    }, act = {
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("The opening shot!")).fetchSemanticsNodes().isNotEmpty()) { "Will's note at 0:04 pops up" }
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("Called it.")).fetchSemanticsNodes().isEmpty()) { "never one further on" }
    }) { c ->
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val chat = androidx.compose.runtime.remember { c.timelineChat(scope, live = false) }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            chat.start("s1e2", 0) { true }
            kotlinx.coroutines.delay(100)
            for (s in 0..8L) chat.at(s * 1_000)
        }
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.DarkGray)) {
            dev.nitrate.tv.ui.together.NoteToasts(chat.popups, Modifier.align(androidx.compose.ui.Alignment.CenterStart).padding(start = 32.dp))
        }
    }

    /** Group chat off in easy mode: no chat button. */
    @Test fun playerGroupChatEasyOff() = groupChatPlayer("tv-player-group-chat-easy", easy = true) {
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("Group chat", substring = true)).fetchSemanticsNodes().isEmpty()) { "no group chat in easy mode" }
    }

    @Test fun playerControlsPlay() = playerControls("tv-player-controls-play")
    @Test fun playerControlsButton() = playerControls("tv-player-controls-audio", "right") { check(focusedHasText("Audio")) }
    @Test fun playerControlsSeek() = playerControls("tv-player-controls-seek", "up")
    private val sampleCast = listOf("Ada Marsh" to "Iris Vale", "Tom Okafor" to "The Projectionist", "Lena Brook" to "Night Clerk", "Sam Ito" to "Conductor", "Rui Costa" to "Ferryman", "June Hale" to "Archivist")
        .mapIndexed { i, (n, r) -> dev.nitrate.core.model.Person("p$i", n, r, imageTag = "t".takeIf { i % 3 != 1 }) }

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
            dev.nitrate.tv.ui.player.TitleStrip(dev.nitrate.tv.ui.player.EXTRAS, extras.map { dev.nitrate.tv.ui.player.TitleCard(it, it.name, it.extraType, art(it)) }) {},
            dev.nitrate.tv.ui.player.TitleStrip("Season 1", episodes.map { dev.nitrate.tv.ui.player.TitleCard(it, it.name, "Episode ${it.indexNumber}", art(it), now = it.id == "e2") }, start = 1) {},
        )
    }

    private val sampleChapters = listOf("Opening", "The letter", "Night train", "Crossing", "The reel", "Morning", "Credits")
        .mapIndexed { i, n -> dev.nitrate.core.model.Chapter(i * 400_000L, n) }

    private fun sampleChoices(sound: dev.nitrate.core.playback.SoundMode = dev.nitrate.core.playback.SoundMode.NIGHT, audioDelay: Int = 100) = dev.nitrate.tv.ui.player.PlayerChoices(
        speed = 1f, onSpeed = {},
        sound = sound, defaultSound = dev.nitrate.core.playback.SoundMode.CLEARER_DIALOGUE, onSound = {}, onSoundAsDefault = {},
        sleepAt = null, sleepAtEnd = false, onSleep = { _, _ -> },
        subtitleOffsetMs = 0, onSubtitleOffset = {},
        audioDelayMs = audioDelay, defaultAudioDelayMs = 0, onAudioDelay = {}, onAudioDelayAsDefault = {},
        frameRate = dev.nitrate.core.playback.FrameRateMatch.ALWAYS, defaultFrameRate = dev.nitrate.core.playback.FrameRateMatch.SEAMLESS,
        refreshNote = dev.nitrate.tv.ui.player.refreshNote(23.976f, 59.94f),
        pages = listOf("Speakers", "Second subtitles", "Find subtitles", "Start a Group Watch Session").map { it to { _: () -> Unit -> androidx.tv.material3.OutlinedButton(onClick = {}) { androidx.tv.material3.Text("$it page") } } },
    )

    /** The More panel over the video, opened on [tab]. */
    private fun moreSheet(name: String, tab: String, vararg keys: String, check: () -> Unit = {}) = shoot(name, act = { press(*keys); check() }) { c ->
        val item by androidx.compose.runtime.produceState<dev.nitrate.core.model.MediaItem?>(null) { value = c.server.getItem("s1e2") }
        item?.let { current ->
            val playing = current.copy(kind = dev.nitrate.core.model.ItemKind.EPISODE, chapters = sampleChapters, people = sampleCast)
            Box(Modifier.fillMaxSize()) {
                coil3.compose.AsyncImage(c.server.imageUrl(current, dev.nitrate.core.model.ImageKind.BACKDROP, 1920), null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                dev.nitrate.tv.ui.player.MoreSheet(
                    dev.nitrate.tv.ui.player.moreTabs(
                        current = playing,
                        titleStrips = sampleStrips(c, current),
                        cast = sampleCast,
                        chapterImage = { i -> c.server.imageUrl(current, dev.nitrate.core.model.ImageKind.BACKDROP, 320).takeIf { i % 3 != 2 } },
                        personImage = { p -> c.server.imageUrl(current, dev.nitrate.core.model.ImageKind.PRIMARY, 300).takeIf { p.imageTag != null } },
                        position = 1_260_000,
                        onChapter = {}, onPerson = {},
                        choices = sampleChoices(),
                    ),
                    initial = tab, onTab = {},
                    modifier = Modifier.align(androidx.compose.ui.Alignment.BottomStart),
                )
            }
        }
    }

    /** Settings → More → a page: focus goes into the page, and Back (one press) returns to its chip. */
    @Test fun moreSettingsPageFocus() = moreSheet("tv-more-settings-page", "settings", "down") {
        repeat(12) { if (!focusedHasText("Speakers")) press("down") }
        check(focusedHasText("Speakers")) { "should reach the Speakers chip" }
        press("ok")
        compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
        check(focusedHasText("Speakers page")) { "focus should be inside the page" }
        press("back")
        compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
        check(focusedHasText("Speakers")) { "one Back should return to the Speakers chip; focused: " + compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().joinToString { it.config.toString() } + " / page shown: " + compose.onAllNodes(androidx.compose.ui.test.hasText("Speakers page")).fetchSemanticsNodes().size }
    }

    /** One Back press from inside More reaches the player's handler (it used to take two). */
    @Test fun moreOneBack() {
        var closed = 0
        shoot("tv-more-one-back", act = {
            press("down")
            press("back")
            check(closed == 1) { "one Back should close More, closed=$closed" }
        }) { c ->
            val item by androidx.compose.runtime.produceState<dev.nitrate.core.model.MediaItem?>(null) { value = c.server.getItem("s1e2") }
            item?.let { current ->
                Box(Modifier.fillMaxSize().onKeyEvent { e ->
                    if (e.key != androidx.compose.ui.input.key.Key.Back) return@onKeyEvent false
                    if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyUp) closed++
                    true
                }) {
                    dev.nitrate.tv.ui.player.MoreSheet(
                        dev.nitrate.tv.ui.player.moreTabs(
                            current = current.copy(kind = dev.nitrate.core.model.ItemKind.EPISODE, chapters = sampleChapters), titleStrips = sampleStrips(c, current), cast = emptyList(),
                            chapterImage = { null }, personImage = { null }, position = 0, onChapter = {}, onPerson = {}, choices = sampleChoices(),
                        ),
                        initial = "siblings", onTab = {}, modifier = Modifier.align(androidx.compose.ui.Alignment.BottomStart),
                    )
                }
            }
        }
    }

    @Test fun moreEpisodes() = moreSheet("tv-more-episodes", "siblings", "down") { check(focusedHasText("Now")) }
    /** The season goes round: ◀ on the first episode lands on the last. */
    @Test fun moreEpisodesWrap() = moreSheet("tv-more-episodes-wrap", "siblings", "down", "left", "left") { check(focusedHasText("Episode title 6")) }
    @Test fun moreChapters() = moreSheet("tv-more-chapters", "chapters", "down") { check(focusedHasText("Now")) }
    @Test fun moreCast() = moreSheet("tv-more-cast", "cast", "down")
    @Test fun moreExtras() = moreSheet("tv-more-extras", "extras", "down")
    /** Lower down the Settings tab: the refresh rate for this playback, with what it looks like now. */
    @Test fun moreSettingsRefresh() = moreSheet("tv-more-settings-refresh", "settings", "down", "down", "down", "down", "down", "down", "down") {
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("23.976 fps at 59.94 Hz: slight judder")).fetchSemanticsNodes().isNotEmpty())
    }
    @Test fun refreshRateInfo() = shoot("tv-refresh-rate-info") { _ -> dev.nitrate.tv.ui.settings.RefreshRateInfo {} }
    @Test fun moreSettings() = moreSheet("tv-more-settings", "settings", "down") { check(focusedHasText("Normal")) }
    /** ◀▶ on the tabs shows each one as it's reached. */
    @Test fun moreTabsMove() = moreSheet("tv-more-tabs-move", "settings", "right") { check(compose.onAllNodes(androidx.compose.ui.test.hasText("Crossing")).fetchSemanticsNodes().isNotEmpty()) }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    private fun press(vararg keys: String) {
        keys.forEach { name ->
            val k = when (name) { "ok" -> androidx.compose.ui.input.key.Key.DirectionCenter; "ff" -> androidx.compose.ui.input.key.Key.MediaFastForward; "rw" -> androidx.compose.ui.input.key.Key.MediaRewind; "menu" -> androidx.compose.ui.input.key.Key.Menu; "back" -> androidx.compose.ui.input.key.Key.Back; "up" -> androidx.compose.ui.input.key.Key.DirectionUp; "down" -> androidx.compose.ui.input.key.Key.DirectionDown; "left" -> androidx.compose.ui.input.key.Key.DirectionLeft; else -> androidx.compose.ui.input.key.Key.DirectionRight }
            // With a dialog open, keys go to it (the topmost window).
            val roots = compose.onAllNodes(androidx.compose.ui.test.isRoot())
            (if (roots.fetchSemanticsNodes().size > 1) roots[roots.fetchSemanticsNodes().size - 1] else compose.onRoot()).performKeyInput { pressKey(k) }
            compose.mainClock.advanceTimeBy(400)
            compose.waitForIdle()
        }
    }

    /**
     * Where focus sits once it has come to rest: rows are placed (and focus asked for) over several
     * frames, some of it after work off the test's clock, so one read can catch a row mid-move.
     * Steps the clock until the focused node's bounds hold for three steps running (5 s at most).
     */
    private fun steadyFocus(): androidx.compose.ui.geometry.Rect {
        fun read() = compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().firstOrNull()?.boundsInRoot
        var last = read()
        var same = 0
        repeat(50) {
            if (same >= 3 && last != null) return last!!
            Thread.sleep(20) // lets work on other threads (settings, loads) land between steps
            compose.mainClock.advanceTimeBy(100)
            compose.waitForIdle()
            val now = read()
            if (now != null && now == last) same++ else { same = 0; last = now }
        }
        return checkNotNull(last) { "nothing has focus" }
    }

    /** Whether the menu (overlay or rail) is open: its "Customize this page" stop is on screen. */
    private fun menuOpen() = compose.onAllNodes(androidx.compose.ui.test.hasText("Customize this page")).fetchSemanticsNodes().isNotEmpty()

    private fun focusedHasDescription(text: String) =
        compose.onAllNodes(androidx.compose.ui.test.isFocused() and androidx.compose.ui.test.hasContentDescription(text, substring = false)).fetchSemanticsNodes().isNotEmpty()

    private fun textMatches(r: Regex) =
        compose.onAllNodes(androidx.compose.ui.test.hasText("", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            .any { n -> n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.any { r.matches(it.text) } == true }

    /** The focused thing has a line that reads exactly [text]. */
    private fun focusedExact(text: String) =
        compose.onAllNodes(androidx.compose.ui.test.isFocused() and androidx.compose.ui.test.hasText(text, substring = false), useUnmergedTree = false)
            .fetchSemanticsNodes().isNotEmpty()

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

    // Menu groups, More and pinned playlists.
    private fun library(id: String, name: String, kind: dev.nitrate.core.model.CollectionKind) =
        dev.nitrate.core.model.MediaItem(id, name, dev.nitrate.core.model.ItemKind.LIBRARY, collection = kind)
    private val groupLibraries = listOf(
        library("m", "Movies", dev.nitrate.core.model.CollectionKind.MOVIES),
        library("t", "TV Shows", dev.nitrate.core.model.CollectionKind.TV_SHOWS),
        library("a1", "Anime Movies", dev.nitrate.core.model.CollectionKind.MOVIES),
        library("a2", "Anime TV Shows", dev.nitrate.core.model.CollectionKind.TV_SHOWS),
        library("k1", "Kids Movies", dev.nitrate.core.model.CollectionKind.MOVIES),
        library("k2", "Kids TV Shows", dev.nitrate.core.model.CollectionKind.TV_SHOWS),
        library("c", "Collections", dev.nitrate.core.model.CollectionKind.BOX_SETS),
    )
    private val animeGroup = dev.nitrate.core.pages.MenuGroup("anime", "Anime", listOf("library:a1", "library:a2"))

    /** On a page in a group (Anime TV Shows), the rail shows the group open with that page's stop marked; focus lands on it. */
    @Test fun railGroupOpen() = shoot("tv-menu-group-open", { c ->
        c.displaySettings.savePrefs("user-1", dev.nitrate.core.pages.DisplayPrefs(tvMenu = dev.nitrate.core.pages.TvMenuStyle.LEFT, tvMenuGroups = listOf(animeGroup)))
    }, act = {
        press("left")
        check(focusedHasText("TV Shows")) { "the rail should open on the page's own stop inside its group" }
    }) { c ->
        val catalog = dev.nitrate.tv.ui.home.menuCatalog(groupLibraries, emptyList(), dev.nitrate.tv.ui.home.MenuActions(), current = "")
        androidx.compose.runtime.CompositionLocalProvider(dev.nitrate.tv.ui.home.LocalMenuCatalog provides catalog, dev.nitrate.tv.ui.pages.LocalTvContainer provides c) {
            dev.nitrate.tv.ui.home.MainMenu(dev.nitrate.core.pages.MenuStops.library("a2"), overlay = false) {
                val focus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
                androidx.tv.material3.Button(onClick = {}, modifier = Modifier.padding(200.dp).focusRequester(focus)) { androidx.tv.material3.Text("Anime TV Shows page") }
                androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            }
        }
    }

    /** OK on a group in the rail opens it in place and focus goes to its first stop, with the rail still open. */
    @Test fun railGroupOpensOnOk() = shoot("tv-menu-group-ok", { c ->
        c.displaySettings.savePrefs("user-1", dev.nitrate.core.pages.DisplayPrefs(tvMenu = dev.nitrate.core.pages.TvMenuStyle.LEFT, tvMenuGroups = listOf(animeGroup)))
    }, act = {
        press("left", "down", "down")
        check(focusedHasText("Anime")) { "Down twice from Movies should reach the Anime group" }
        press("ok")
        compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
        val anime = compose.onAllNodes(androidx.compose.ui.test.hasText("Anime", substring = true)).fetchSemanticsNodes().first().boundsInRoot
        val focused = compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes()
        check(focused.isNotEmpty()) { "focus was lost" }
        check(focusedHasText("Movies") && focused.first().boundsInRoot.top > anime.top) { "focus should be on the group's first stop, under it: ${focused.first().boundsInRoot} vs $anime" }
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("TV Shows", substring = true)).fetchSemanticsNodes().size >= 2) { "the group should still be open in the rail" }
    }) { c ->
        val catalog = dev.nitrate.tv.ui.home.menuCatalog(groupLibraries, emptyList(), dev.nitrate.tv.ui.home.MenuActions(), current = "")
        androidx.compose.runtime.CompositionLocalProvider(dev.nitrate.tv.ui.home.LocalMenuCatalog provides catalog, dev.nitrate.tv.ui.pages.LocalTvContainer provides c) {
            dev.nitrate.tv.ui.home.MainMenu(dev.nitrate.core.pages.MenuStops.library("m"), overlay = false) {
                val focus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
                androidx.tv.material3.Button(onClick = {}, modifier = Modifier.padding(200.dp).focusRequester(focus)) { androidx.tv.material3.Text("Movies page") }
                androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            }
        }
    }

    /** Admins: the server's Nitrate Companion with a newer version waiting; Restart asks first. */
    @Test fun companionUpdateWaiting() = shoot("tv-companion-update", { c ->
        (c.server as dev.nitrate.testing.FakeServer).companionFeatures += "updates"
        c.refreshCompanionForTests()
        c.refreshAccount()
    }, act = {
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("Version 0.1.0.72 is installed", substring = true)).fetchSemanticsNodes().isNotEmpty()) { "The waiting update should show" }
        // Down past the app's own rows and "Check for updates now" to the restart, then OK.
        repeat(6) { if (!focusedHasText("Restart the server now")) press("down") }
        check(focusedHasText("Restart the server now")) { "The restart line should be reachable with the D-pad" }
        press("ok")
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("Restart the server now?")).fetchSemanticsNodes().isNotEmpty()) { "Restart should ask first" }
        check(focusedHasText("Not now")) { "The question should open on Not now" }
    }) { c -> dev.nitrate.tv.ui.settings.UpdatesScreen(c.updater, c) }

    /** Your pages sits in the menu like any top-level page: it opens on its first tile and Left reaches the menu. */
    @Test fun yourPagesInMenu() = shoot("tv-your-pages", { c ->
        c.displaySettings.savePrefs("user-1", dev.nitrate.core.pages.DisplayPrefs(tvMenu = dev.nitrate.core.pages.TvMenuStyle.LEFT))
    }, act = {
        check(focusedHasText("New page")) { "Your pages should open on its first tile" }
        press("left")
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); compose.waitForIdle()
        // Your pages is kept in More by default: the menu opens on More, marked as where you are.
        check(focusedHasText("More")) { "Left from Your pages should reach the menu at More" }
    }) { c ->
        val catalog = dev.nitrate.tv.ui.home.menuCatalog(groupLibraries, emptyList(), dev.nitrate.tv.ui.home.MenuActions(onPages = {}), current = "")
        androidx.compose.runtime.CompositionLocalProvider(dev.nitrate.tv.ui.home.LocalMenuCatalog provides catalog, dev.nitrate.tv.ui.pages.LocalTvContainer provides c) {
            dev.nitrate.tv.ui.home.MainMenu(dev.nitrate.core.pages.MenuStops.PAGES, overlay = false) {
                dev.nitrate.tv.ui.pages.PagesScreen(c) { _, _ -> }
            }
        }
    }

    /** The More page: what's kept out of the menu (Collections, Your pages, Watch together, Switch user…) as large tiles. */
    @Test fun morePage() = shoot("tv-more-page", { c ->
        c.displaySettings.savePrefs("user-1", dev.nitrate.core.pages.DisplayPrefs(tvMenu = dev.nitrate.core.pages.TvMenuStyle.LEFT, tvMenuGroups = listOf(animeGroup)))
    }) { c ->
        val catalog = dev.nitrate.tv.ui.home.menuCatalog(groupLibraries, emptyList(), dev.nitrate.tv.ui.home.MenuActions(onChannels = {}), current = "")
        androidx.compose.runtime.CompositionLocalProvider(dev.nitrate.tv.ui.home.LocalMenuCatalog provides catalog, dev.nitrate.tv.ui.pages.LocalTvContainer provides c) {
            dev.nitrate.tv.ui.home.MoreScreen()
        }
    }

    /** Menu stops: suggested groups at the top, then the stops in the menu, in More and hidden. */
    @Test fun menuStopsSuggestion() = shoot("tv-menu-stops-suggestion", act = {
        check(focusedHasText("Make group")) { "the first suggestion's button should have focus; focused: " + compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().joinToString { it.config.toString() } }
    }) { _ ->
        val catalog = dev.nitrate.tv.ui.home.menuCatalog(groupLibraries, emptyList(), dev.nitrate.tv.ui.home.MenuActions(), current = "")
        dev.nitrate.tv.ui.settings.MenuStopsEditor(dev.nitrate.core.pages.DisplayPrefs(tvMenu = dev.nitrate.core.pages.TvMenuStyle.LEFT), catalog) {}
    }

    /** A playlist pinned to Home is one of its tabs: choosing it shows the playlist's page under the tabs. */
    @Test fun homePinnedPlaylist() = shoot("tv-home-pinned-playlist", { c ->
        c.displaySettings.savePrefs("user-1", dev.nitrate.core.pages.DisplayPrefs(homePlaylists = mapOf("p1" to "Rainy day")))
    }, act = {
        press("up", "right", "ok")
        compose.mainClock.advanceTimeBy(1_500); compose.waitForIdle()
        check(focusedHasText("Rainy day")) { "focus should stay on the playlist's tab" }
    }) { c ->
        HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {},
            playlistPage = { id, header ->
                dev.nitrate.tv.ui.playlist.PlaylistScreen(c.server, c.displaySettings, id, onPlayQueue = {}, onOpen = {}, onPlayFrom = { _, _ -> }, playlists = c.playlists, header = header)
            })
    }

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
        press("up")
        // Ring two up by default: More (Your pages, Watch together and the rest live there now).
        compose.onNodeWithText("More").assertExists()
        press("right")
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

    /** The first row with its cards hidden: its title's picture behind the page, the caption over it. */
    @Test fun homeCardsHidden() = shoot("tv-home-cards-hidden", { c ->
        c.displaySettings.savePrefs("user-1", rowsHome)
        val rows = c.server.homeRows()
        c.displaySettings.save("user-1", rows[0], dev.nitrate.core.rows.RowDisplay(dev.nitrate.core.rows.RowStyle.WIDE, cards = false))
    }) { c ->
        WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) }
    }

    /** A cards-hidden row takes the row's effects: the browsing effect lands on the title showing, and only on it. */
    @Test fun cardsHiddenSpark() {
        val fx = dev.nitrate.tv.ui.effects.CardFx(dev.nitrate.core.effects.ClickEffect.POP, dev.nitrate.core.effects.CardSpark.FIREWORKS, 1f).apply { holdAt = 0.35f }
        lateinit var row: dev.nitrate.core.rows.MediaRow
        shoot("tv-cards-hidden-spark", { c -> row = c.server.homeRows().first { it.items.size > 1 } }, act = {
            compose.runOnIdle {
                check(fx.shown.toList() == listOf(row.items[0].id)) { "sparks should pick only the title showing, got ${fx.shown.toList()}" }
                fx.sparking[row.items[0].id] = 7L
            }
        }) { c ->
            androidx.compose.runtime.CompositionLocalProvider(dev.nitrate.tv.ui.effects.LocalCardFx provides fx) {
                dev.nitrate.tv.ui.rows.CardlessRow(
                    row, dev.nitrate.core.rows.RowDisplay(dev.nitrate.core.rows.RowStyle.WIDE, cards = false), c.server,
                    dev.nitrate.tv.ui.rows.RowActions({}, {}, {}, {}), index = 0, onIndexChange = {}, onActiveChange = {},
                    expanded = true, onArtwork = {},
                )
            }
        }
    }

    /** Down from a cards-hidden row to the next: nothing of it may be left over the row below. */
    @Test fun cardsHiddenThenDown() = shoot("tv-cards-hidden-then-down", { c ->
        c.displaySettings.savePrefs("user-1", rowsHome)
        val rows = c.server.homeRows()
        c.displaySettings.save("user-1", rows[0], dev.nitrate.core.rows.RowDisplay(dev.nitrate.core.rows.RowStyle.WIDE, cards = false))
    }, act = { press("down") }) { c ->
        WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) }
    }

    /**
     * Rows one at a time, two cards-hidden rows: moving between them never draws either as a banner
     * (its title over a strip of its artwork), not even for a frame while the page slides.
     */
    @Test fun cardsHiddenRowsNeverBanner() {
        lateinit var titles: List<String>
        shoot("tv-cards-hidden-no-banner", { c ->
            c.displaySettings.savePrefs("user-1", rowsHome)
            val rows = c.server.homeRows().filter { it.items.isNotEmpty() }
            titles = rows.take(2).map { it.title }
            rows.take(2).forEach { c.displaySettings.save("user-1", it, dev.nitrate.core.rows.RowDisplay(dev.nitrate.core.rows.RowStyle.WIDE, cards = false)) }
        }, act = {
            compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
            fun noBanner(at: String) {
                // A banner shows the row's name on its own; the screen's caption says "<row>  ·  n / N".
                titles.forEach { t ->
                    check(compose.onAllNodes(androidx.compose.ui.test.hasText(t, substring = false), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) { "\"$t\" drawn as a banner $at" }
                }
            }
            noBanner("at the start")
            compose.mainClock.autoAdvance = false
            for ((name, key) in listOf("down" to androidx.compose.ui.input.key.Key.DirectionDown, "up" to androidx.compose.ui.input.key.Key.DirectionUp)) {
                compose.onRoot().performKeyInput { pressKey(key) }
                repeat(40) { i -> compose.mainClock.advanceTimeByFrame(); noBanner("$i frames after $name") }
            }
            compose.mainClock.autoAdvance = true
        }) { c -> WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) } }
    }

    /** Now Playing on TV: the cover beside the song, the controls (Play/Pause focused), then what's up next. */
    @Test fun nowPlaying() {
        lateinit var player: dev.nitrate.testing.FakeListening
        shoot("tv-now-playing", setup = { c ->
            player = dev.nitrate.testing.FakeListening(
                dev.nitrate.core.music.ListenState(c.server.albumTracks("al1"), index = 1, playing = true, durationMs = 197_000, repeat = dev.nitrate.core.music.Repeat.ALL),
                position = 72_000,
            )
        }, act = {
            compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
            check(focusedHasText("Pause")) { "Play/Pause should take focus" }
            // Right to Next and OK; Right again stays among the controls (to Repeat), never into the list.
            press("right", "ok")
            check("next" in player.asked) { player.asked.toString() }
        }) { c -> dev.nitrate.tv.ui.music.NowPlayingScreen(player, c.server, onBack = {}) }
    }

    /** Now Playing for an audiobook on TV: skips, speed and sleep, and its chapters. */
    @Test fun nowPlayingBook() {
        val book = dev.nitrate.core.model.MediaItem(
            "ab1", "The Long Reel", dev.nitrate.core.model.ItemKind.AUDIOBOOK, runtimeMs = 9_000_000, artists = listOf("Ada Lumen"),
            chapters = listOf("Prologue", "The Booth", "Changeover", "House Lights").mapIndexed { i, n -> dev.nitrate.core.model.Chapter(i * 1_800_000L, n) },
        )
        val player = dev.nitrate.testing.FakeListening(dev.nitrate.core.music.ListenState(listOf(book), 0, playing = true, durationMs = 9_000_000, speed = 1.25f), position = 2_400_000)
        val marks = dev.nitrate.testing.FakeBookmarks(mapOf("ab1" to listOf(dev.nitrate.core.music.Bookmark("m1", 4_300_000, "The projectionist's confession"))))
        shoot("tv-now-playing-book", act = {
            compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
            press("right", "right", "ok")
            check("skip by 30000" in player.asked) { player.asked.toString() }
            compose.onNodeWithText("Bookmark").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            compose.waitForIdle()
            check(marks.all.value["ab1"]!!.size == 2) { marks.all.value.toString() }
        }) { c -> dev.nitrate.tv.ui.music.NowPlayingScreen(player, c.server, onBack = {}, bookmarkStore = marks) }
    }

    /** Every tab style in the picker, each drawn with "Recommended" open and "All" focused. */
    @Test fun tabStyles() = shoot("tv-tab-styles") { dev.nitrate.tv.ui.components.TabStyleChoices(dev.nitrate.core.pages.TabStyle.CHIPS) {} }

    @Test fun tabStylesMore() = shoot("tv-tab-styles-more", act = { press("down", "down", "down", "down", "down", "down", "down", "down", "down") }) {
        dev.nitrate.tv.ui.components.TabStyleChoices(dev.nitrate.core.pages.TabStyle.CHIPS) {}
    }

    /** A library page with the neon tabs. */
    @Test fun libraryNeonTabs() = libraryShot("tv-library-neon-tabs", rowsLibrary.copy(tvTabStyle = dev.nitrate.core.pages.TabStyle.NEON)) {}

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

    /** Display → Menu with the overlay: no "Opens with" line (the overlay opens as the dial does: Menu, or Up past the top). */
    @Test fun menuOpensWithSetting() = shoot("tv-display-opens-with", act = {
        repeat(40) {
            check(!shows("Opens with")) { "there's no Opens with line any more" }
            press("down")
        }
    }) { c -> DisplaySettingsScreen(c) }

    @Test fun railHasNoOpensWith() = shoot("tv-display-rail-no-opens-with", { c -> c.displaySettings.savePrefs("user-1", DisplayPrefs(tvMenu = dev.nitrate.core.pages.TvMenuStyle.LEFT)) }, act = {
        repeat(40) { if (!focusedHasText("Rail side")) press("down") }
        check(focusedHasText("Rail side")) { "the rail's own line shows" }
        check(!shows("Opens with")) { "Opens with is only for the overlay" }
    }) { c -> DisplaySettingsScreen(c) }

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

    /** Skip intro / Skip recap over the video, focused so one OK skips. */
    @Test fun skipIntro() = shoot("tv-skip-intro") { c ->
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
            coil3.compose.AsyncImage(c.server.backdropUrl(MediaItem("series-1", "", ItemKind.SERIES)), null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
            androidx.compose.foundation.layout.Column(Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(48.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
                dev.nitrate.tv.ui.player.SkipSegmentButton(dev.nitrate.core.model.MediaSegment(dev.nitrate.core.model.SegmentKind.RECAP, 0, 60_000), takeFocus = false, onSkip = {}, onFocus = {})
                dev.nitrate.tv.ui.player.SkipSegmentButton(dev.nitrate.core.model.MediaSegment(dev.nitrate.core.model.SegmentKind.INTRO, 60_000, 150_000), takeFocus = true, onSkip = {}, onFocus = {})
            }
        }
    }

    /** The update offer with a long "What's new": the notes sit in their own box, the choices below. */
    @Test fun updateOffer() = shoot("tv-update-offer") { _ ->
        val release = dev.nitrate.core.update.AppRelease(
            "dev-120", "Nitrate dev-120", 1120, true, "nitrate-tv-dev-120.apk", "https://example.invalid/a.apk", null,
            (1..24).map { "Change number $it: something better about watching, browsing or settings" },
        )
        dev.nitrate.tv.ui.components.UpdateDialog(dev.nitrate.android.update.UpdateState.Available(release), onUpdate = {}, onLater = {}, onDismiss = {})
    }

    /** A video playlist's page: how much is watched, Continue from where it was left, the entry you're up to marked NEXT. */
    @Test fun videoPlaylist() {
        shoot("tv-playlist", settleMs = 2_000) { c ->
            dev.nitrate.tv.ui.playlist.PlaylistScreen(c.server, c.displaySettings, "p1", onPlayQueue = {}, onOpen = {}, onPlayFrom = { _, _ -> }, playlists = c.playlists)
        }
        compose.onAllNodesWithText("Rainy day", substring = true).onFirst().assertExists()
        compose.onAllNodesWithText("of 6 watched", substring = true).onFirst().assertExists()
        compose.onAllNodesWithText("NEXT").onFirst().assertExists()
        compose.onAllNodesWithText("Compact view").assertCountEquals(0)
    }

    /** Holding OK on an entry: play from here, open it, mark watched up to here or unwatched from here on. */
    @Test fun videoPlaylistEntryMenu() = shoot("tv-playlist-entry-menu", settleMs = 2_000, act = {
        compose.onAllNodes(androidx.compose.ui.test.hasText("The Night Reel") and androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)).onFirst().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
        compose.waitForIdle()
        compose.onNodeWithText("Mark watched up to here").assertExists()
    }) { c ->
        dev.nitrate.tv.ui.playlist.PlaylistScreen(c.server, c.displaySettings, "p1", onPlayQueue = {}, onOpen = {}, onPlayFrom = { _, _ -> }, playlists = c.playlists)
    }

    /** The watch diary on TV. */
    @Test fun diary() = shoot("tv-diary") { c -> dev.nitrate.tv.ui.settings.DiaryScreen(c.server) }

    /** Search finds what people say too. */
    @Test fun saidIn() = shoot("tv-search-said-in") { c ->
        dev.nitrate.tv.ui.search.SearchScreen(c.server, c.searchHistory, initialQuery = "need a bigger boat", onOpen = {}, onPerson = {}, linesOn = true)
    }

    /** Playback info with the sound's path (hi-res stereo FLAC going to a receiver). */
    @Test fun playbackInfoAudio() = playbackInfo("tv-playback-info-audio", notes = false)

    /** With every note on the picture's side (judder, dropped frames, a slow network): still fits the screen. */
    @Test fun playbackInfoNotes() = playbackInfo("tv-playback-info-notes", notes = true)

    private fun playbackInfo(name: String, notes: Boolean) = shoot(name) { c ->
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
                Modifier.align(androidx.compose.ui.Alignment.Center).padding(horizontal = 32.dp, vertical = 24.dp),
                audio = dev.nitrate.core.playback.AudioPath("flac", 2, 96_000, 24, dev.nitrate.core.playback.AudioDelivery.DECODED, "PCM float", 2, 96_000, 96_000),
                video = dev.nitrate.core.playback.VideoPath(
                    inCodec = "hevc", inWidth = 1920, inHeight = 1080, inFps = 23.976f, inRange = "SDR",
                    displayWidth = 3840, displayHeight = 2160, displayHz = 60f, displayHdr = setOf("HDR10", "HLG"),
                    droppedFrames = if (notes) 240 else 0, bufferedMs = if (notes) 1_200 else 31_000, networkBps = if (notes) 4_000_000 else 220_000_000, fileBps = 9_400_000,
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

    /** ▼ walks down "Your year" part by part, so the page scrolls to its end. */
    @Test fun statsScrolls() = shoot("tv-stats-scrolled", act = {
        compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
        repeat(6) { press("down") }
        compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
        check(focusedHasText("server's record") || focusedHasText("watch diary")) { "▼ should reach the note at the bottom" }
        compose.onNode(androidx.compose.ui.test.hasText("server's record", substring = true)).assertIsDisplayed()
    }) { c -> dev.nitrate.tv.ui.settings.StatsScreen(c.server) }

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
        // The Settings tab over a real session: its defaults come from this device's settings.
        val choices = dev.nitrate.tv.ui.player.rememberPlayerChoices(session, c.playbackSettings, c.server, null, false, onSleep = { _, _ -> }, onDone = {})
        Box(Modifier.fillMaxSize()) {
            dev.nitrate.tv.ui.player.MoreSheet(listOf(dev.nitrate.tv.ui.player.MoreTab("settings", "Settings") { dev.nitrate.tv.ui.player.PlayerChoicesTab(choices) }), null, {},
                Modifier.align(androidx.compose.ui.Alignment.BottomStart))
        }
    }

    /** "Will started a Group Watch Session": the join prompt off the player, Join focused (late: Join at …, Start from the beginning). */
    @Test fun sessionJoinPrompt() = shoot("tv-session-join", act = {
        compose.mainClock.advanceTimeBy(400); compose.waitForIdle()
        check(focusedHasText("Join at 2:00")) { "Join is focused; focused: ${focusedNodes()}" }
    }) { c ->
        val s = (c.server as FakeServer).sampleSession(positionMs = 120_000)
        val prompt = dev.nitrate.core.partners.SessionPrompt.of(s, s.serverTimeMs)!!
        check(prompt.choices.map { it.label } == listOf("Join at 2:00", "Start from the beginning", "Not now")) { prompt.choices.toString() }
        dev.nitrate.tv.ui.partners.SessionJoinDialog(prompt, onDismiss = {}, onChoose = {})
    }

    /** The countdown over the video, and (for whoever started it) the wait before it while the group gathers. */
    @Test fun sessionCountdown() = shoot("tv-session-countdown") {
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF203040))) {
            dev.nitrate.tv.ui.player.SessionCountdown(3, null, starter = false, modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter).padding(32.dp))
            dev.nitrate.tv.ui.player.SessionCountdown(null, "Waiting for Frank, Tommy and Jane to join", starter = true, modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(32.dp))
        }
    }

    /** The others' news over the video: words only, never focusable; the action is in the controls. */
    @Test fun sessionNotices() = shoot("tv-session-notices", act = {
        check(compose.onAllNodes(androidx.compose.ui.test.isFocusable()).fetchSemanticsNodes().isEmpty()) { "the notices never take focus" }
    }) {
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF203040))) {
            androidx.compose.foundation.layout.Column(Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(32.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
                dev.nitrate.tv.ui.player.SessionNoticeBanner(dev.nitrate.core.partners.SessionNotice.Paused("Will", 61_000), controlsUp = false)
                dev.nitrate.tv.ui.player.SessionNoticeBanner(dev.nitrate.core.partners.SessionNotice.Jumped("Will", 1_450_000), controlsUp = true)
                dev.nitrate.tv.ui.player.SessionNoticeBanner(dev.nitrate.core.partners.SessionNotice.Drift("Will", 12_000, 130_000), controlsUp = true)
            }
        }
    }

    /**
     * In a Group Watch Session, 2 minutes behind Will: the notice shows over the video without taking
     * focus, and Catch up is a button in the controls, reached with the D-pad.
     */
    @Test fun playerSessionCatchUp() = shoot("tv-player-session-catch-up", setup = { c ->
        val fake = c.server as FakeServer
        fake.watchSessionState = fake.sampleSession(positionMs = 120_000)
        c.groupSessions.choose(fake.watchSessionState!!, dev.nitrate.core.partners.SessionChoice.FromStart)
    }, act = {
        compose.mainClock.advanceTimeBy(dev.nitrate.core.partners.SessionSync.REPORT_MS + 1_000); compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("behind Will", substring = true)).fetchSemanticsNodes().isNotEmpty()) { "the drift notice shows: " + compose.onAllNodes(androidx.compose.ui.test.hasText("", substring = true)).fetchSemanticsNodes().joinToString { it.config.toString() }.take(3000) }
        check(!focusedHasText("behind Will")) { "the notice never takes focus; focused: ${focusedNodes()}" }
        repeat(10) { if (!focusedHasText("Catch up")) press("right") }
        if (!focusedHasText("Catch up")) repeat(12) { if (!focusedHasText("Catch up")) press("left") }
        check(focusedHasText("Catch up")) { "Catch up is reachable in the controls; focused: ${focusedNodes()}" }
    }) { c ->
        dev.nitrate.tv.ui.player.PlayerScreen(c.server, c.playbackSettings, "s1e2", fromStart = true, onExit = {}, watchGroups = c)
    }

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
        dev.nitrate.tv.ui.partners.WatchPartnersScreen(c) { _, _ -> }
    }

    @Test fun partnerRequest() = shoot("tv-partner-request", setup = { c -> c.refreshCompanionForTests() }) { c ->
        dev.nitrate.tv.ui.partners.InviteAnswerDialog(c, (c.server as dev.nitrate.testing.FakeServer).partnerInfo.incoming.first()) {}
    }

    /** A watch party's reminder: Join first and focused; the postponements and Not tonight beside it. */
    @Test fun watchParty() = shoot("tv-watch-party", act = {
        compose.onNode(androidx.compose.ui.test.hasText("Join") and androidx.compose.ui.test.hasClickAction()).assertIsFocusedOrHasFocusedChild()
    }) { c ->
        dev.nitrate.tv.ui.partners.WatchPartyDialog((c.server as dev.nitrate.testing.FakeServer).samplePartyPrompt(), onDismiss = {}, onChoose = {})
    }

    /** Someone's schedule change: the question with Yes focused, No and Later beside it. */
    @Test fun scheduleAsk() = shoot("tv-schedule-ask", act = {
        compose.onNode(androidx.compose.ui.test.hasText("Yes") and androidx.compose.ui.test.hasClickAction()).assertIsFocusedOrHasFocusedChild()
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("Sam asks to change the schedule of The Night Reel and Nitrate to Thursdays 7pm, together. Agree?")).fetchSemanticsNodes().isNotEmpty()) { "the question" }
    }) { c ->
        dev.nitrate.tv.ui.partners.ScheduleAskDialog((c.server as dev.nitrate.testing.FakeServer).sampleSchedulePrompt(), onLater = {}, onAnswer = {})
    }

    /** The guided setup: step 1 picks titles (focus in the search); Next goes on to Who, focus on the first person; Back steps back with the picks kept. */
    @Test fun groupSetupBack() = shoot("tv-group-setup-what", setup = { c -> c.refreshCompanionForTests() }, act = {
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("Step 1 of 5 · What to watch")).fetchSemanticsNodes().isNotEmpty()) { "the first step" }
        fun click(text: String) {
            compose.onNode(androidx.compose.ui.test.hasClickAction() and androidx.compose.ui.test.hasText(text, substring = true)).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
        }
        click("The Night Reel")
        click("Nitrate · 2023")
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("Picked: The Night Reel and Nitrate")).fetchSemanticsNodes().isNotEmpty()) { "both picked" }
        click("Next")
        compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("Step 2 of 5 · Who with")).fetchSemanticsNodes().isNotEmpty()) { "on to Who" }
        check(focusedHasText("Will")) { "focus on the first person; focused: ${focusedNodes()}" }
        pressInDialog("back")
        compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("Step 1 of 5 · What to watch")).fetchSemanticsNodes().isNotEmpty()) { "Back steps back one page" }
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("Picked: The Night Reel and Nitrate")).fetchSemanticsNodes().isNotEmpty()) { "the picks are kept" }
    }) { c -> dev.nitrate.tv.ui.partners.GroupSetupDialog(c) {} }

    /** The last step: the Together switch (focused) with what on means, what's chosen, and Send. */
    @Test fun groupSetupTogether() = shoot("tv-group-setup-together", setup = { c -> c.refreshCompanionForTests() }, act = {
        check(focusedHasText("Together: Off")) { "focus on the switch; focused: ${focusedNodes()}" }
        press("ok")
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("Together: On")).fetchSemanticsNodes().isNotEmpty()) { "OK turns it on" }
    }) { c ->
        val start = dev.nitrate.core.partners.GroupSetup(
            dev.nitrate.core.partners.GroupSetup.Step.TOGETHER,
            listOf(dev.nitrate.core.partners.GroupTitle("series-1", "The Night Reel"), dev.nitrate.core.partners.GroupTitle("m1", "Nitrate")), people = listOf("user-2"),
            schedule = dev.nitrate.core.partners.GroupSchedule(dev.nitrate.core.partners.GroupSchedule.WEEKLY, party = dev.nitrate.core.partners.PartyTime(listOf(5), 21)),
        )
        dev.nitrate.tv.ui.partners.GroupSetupDialog(c, start) {}
    }

    /** The last step with a paced schedule: "If someone plays ahead" under the switch, reached with ▼; Don't allow shows in the summary. */
    @Test fun groupSetupPlayAhead() = shoot("tv-group-setup-play-ahead", setup = { c -> c.refreshCompanionForTests() }, act = {
        check(compose.onAllNodes(androidx.compose.ui.test.hasText(dev.nitrate.core.partners.GroupSchedule.PLAY_AHEAD)).fetchSemanticsNodes().isNotEmpty()) { "the play-ahead line" }
        check(focusedHasText("Together: Off")) { "focus on the switch; focused: ${focusedNodes()}" }
        press("down")
        check(focusedHasText("Ask first") || focusedHasText("Don't allow")) { "▼ reaches the choice; focused: ${focusedNodes()}" }
        if (focusedHasText("Ask first")) press("right")
        check(focusedHasText("Don't allow")) { "Don't allow is reachable; focused: ${focusedNodes()}" }
        press("ok")
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("Thursdays 7pm, locked", substring = true)).fetchSemanticsNodes().isNotEmpty()) { "the summary says locked" }
        check(compose.onAllNodes(androidx.compose.ui.test.hasText(dev.nitrate.core.partners.GroupSchedule(dev.nitrate.core.partners.GroupSchedule.WEEKLY, locked = true).lockNote)).fetchSemanticsNodes().isNotEmpty()) { "what Don't allow means" }
    }) { c ->
        val start = dev.nitrate.core.partners.GroupSetup(
            dev.nitrate.core.partners.GroupSetup.Step.TOGETHER,
            listOf(dev.nitrate.core.partners.GroupTitle("series-1", "The Night Reel")), people = listOf("user-2"),
            schedule = dev.nitrate.core.partners.GroupSchedule(dev.nitrate.core.partners.GroupSchedule.WEEKLY),
        )
        dev.nitrate.tv.ui.partners.GroupSetupDialog(c, start) {}
    }

    /** A group whose schedule doesn't allow playing ahead, from anywhere: "Opens Thursday 7pm", why, and only OK, focused. */
    @Test fun playAheadRefused() = shoot("tv-play-ahead-refused", setup = { c ->
        (c.server as FakeServer).partnerInfo = dev.nitrate.core.partners.PartnersInfo(
            listOf(
                dev.nitrate.core.partners.WatchGroup(
                    "g1", "series-1", "The Night Reel", listOf(dev.nitrate.core.writing.UserRef("user-2", "Will")), 0,
                    dev.nitrate.core.partners.GroupSchedule(dev.nitrate.core.partners.GroupSchedule.WEEKLY, day = 4, hour = 19, locked = true),
                    lockedFromId = "s2e1", opensDay = 4,
                ),
            ),
            emptyList(), emptyList(),
        )
    }, act = {
        // The guard reads the groups and the title's parts first.
        repeat(40) {
            if (compose.onAllNodes(androidx.compose.ui.test.hasText("Opens Thursday 7pm")).fetchSemanticsNodes().isEmpty()) {
                Thread.sleep(50); compose.mainClock.advanceTimeBy(200); compose.waitForIdle()
            }
        }
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("Opens Thursday 7pm")).fetchSemanticsNodes().isNotEmpty()) { "says when it opens" }
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("doesn't allow playing ahead", substring = true)).fetchSemanticsNodes().isNotEmpty()) { "says why" }
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("Watch for myself")).fetchSemanticsNodes().isEmpty()) { "nothing to watch for oneself" }
        check(focusedHasText("OK")) { "focus on OK; focused: ${focusedNodes()}" }
    }) { c ->
        val gate = dev.nitrate.tv.plugins.core.rememberPlaybackGate(c.plugins) { _, _, _ -> }
        androidx.compose.runtime.LaunchedEffect(Unit) { gate.play(c.server.getItem("s2e1")) }
        dev.nitrate.tv.plugins.core.PlaybackGateDialog(gate)
    }

    /** After a playback from a group's card that's next in another group too: the question, "Just this group" focused. */
    @Test fun countingPrompt() = shoot("tv-counting-prompt", act = {
        check(focusedHasText("Just this group")) { "focus on Just this group; focused: ${focusedNodes()}" }
    }) { c ->
        val info = (c.server as dev.nitrate.testing.FakeServer).partnerInfo
        val counting = dev.nitrate.core.partners.Counting(dev.nitrate.core.partners.PlayOrigin.GroupCard("g1"), listOf(info.groups[0]), listOf(info.groups[1]))
        dev.nitrate.tv.ui.partners.CountingDialog(dev.nitrate.core.partners.CountAsk(listOf("s1e2"), "S1:E2 · Episode 2", counting)) {}
    }

    @Test fun togetherChoices() = shoot("tv-together-choices", setup = { c -> c.refreshCompanionForTests() }, act = {
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("We're in the same room", substring = true)).fetchSemanticsNodes().isNotEmpty()) { "the same room is offered" }
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("Add a title to the group", substring = true)).fetchSemanticsNodes().isNotEmpty()) { "adding a title is offered" }
    }) { c ->
        val info by c.partners.collectAsState()
        val episode = dev.nitrate.core.model.MediaItem("ep-together", "The Long Night", dev.nitrate.core.model.ItemKind.EPISODE, seriesId = "series-1", seriesName = "The Night Reel", seasonNumber = 1, indexNumber = 4)
        dev.nitrate.tv.ui.partners.TogetherChoicesDialog(c, episode, info, onPlay = {}, onOpenShow = { _, _ -> }, onStart = {}, onCustomize = {}) {}
    }

    @Test fun recap() = shoot("tv-recap", setup = { c -> c.pendingRecap = (c.server as dev.nitrate.testing.FakeServer).sampleRecap() }) { c ->
        dev.nitrate.tv.ui.player.RecapScreen(c, {}, {}, {})
    }

    /** An episode's page. */
    /** An episode entered from Will's group: the group's own mark beside Watched, and "✓ with Will" on the episodes the group has. */
    @Test fun episodeDetailsFromAGroup() = shoot("tv-episode-details-group", setup = { c ->
        val server = c.server as dev.nitrate.testing.FakeServer
        server.partnerInfo = server.partnerInfo.copy(groups = server.partnerInfo.groups.map { if (it.id == "g1") it.copy(ownRecord = setOf("s1e1")) else it })
        c.refreshCompanionForTests()
    }, act = {
        check(compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Not watched with Will")).fetchSemanticsNodes().isNotEmpty()) { "the group's mark is beside Watched" }
        check(shows("✓ with Will")) { "the episodes the group has say so" }
    }) { c ->
        dev.nitrate.tv.ui.partners.ProvideGroupMarks(c, "g1") {
            dev.nitrate.tv.ui.details.DetailsScreen(c.server, "s1e2", null, onPlay = { _, _ -> }, onOpen = {}, onManage = {})
        }
    }

    @Test fun episodeDetails() = shoot("tv-episode-details") { c -> dev.nitrate.tv.ui.details.DetailsScreen(c.server, "s1e2", null, onPlay = { _, _ -> }, onOpen = {}, onManage = {}) }

    @Test fun movieDetails() = shoot("tv-movie-details") { c -> dev.nitrate.tv.ui.details.DetailsScreen(c.server, "m1", null, onPlay = { _, _ -> }, onOpen = {}, onManage = {}) }

    /** A details page on its way: placeholders in its own shape, no bare "Loading…". */
    @Test fun detailsLoading() = shoot("tv-details-loading") { c ->
        val waiting = object : dev.nitrate.core.MediaServer by c.server {
            override suspend fun getItem(id: String): dev.nitrate.core.model.MediaItem = kotlinx.coroutines.awaitCancellation()
        }
        dev.nitrate.tv.ui.details.DetailsScreen(waiting, "never", null, onPlay = { _, _ -> }, onOpen = {})
    }

    /** A season's page: the show it belongs to, the season, how far through it, Play for that season. */
    @Test fun seasonDetails() {
        shoot("tv-season-details") { c -> dev.nitrate.tv.ui.details.DetailsScreen(c.server, "series-1", "season-2", onPlay = { _, _ -> }, onOpen = {}) }
        compose.onAllNodesWithText("watched", substring = true).onFirst().assertExists()
    }

    /** Choices open as a panel down the right edge, the page still in view beside it. */
    @Test fun sidePanel() {
        shoot("tv-panel-page", act = {
            compose.onNode(androidx.compose.ui.test.hasClickAction() and androidx.compose.ui.test.hasAnyDescendant(androidx.compose.ui.test.hasContentDescription("More")), useUnmergedTree = true)
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        }) { c -> dev.nitrate.tv.ui.details.DetailsScreen(c.server, "s1e2", null, onPlay = { _, _ -> }, onOpen = {}, onManage = {}) }
        compose.onAllNodes(androidx.compose.ui.test.isDialog()).onFirst().captureRoboImage("build/outputs/roborazzi/tv-panel.png")
    }

    /** The details page's More panel: everything the action row leaves out. */
    @Test fun detailsMore() {
        shoot("tv-details-actions", act = {
            compose.onNode(androidx.compose.ui.test.hasClickAction() and androidx.compose.ui.test.hasAnyDescendant(androidx.compose.ui.test.hasContentDescription("More")), useUnmergedTree = true)
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        }) { c ->
            dev.nitrate.tv.ui.details.DetailsScreen(c.server, "m1", null, onPlay = { _, _ -> }, onOpen = {}, onManage = {})
        }
        compose.onAllNodesWithText("How it plays").onFirst().assertExists()
        compose.onAllNodes(androidx.compose.ui.test.isDialog()).onFirst().captureRoboImage("build/outputs/roborazzi/tv-details-more.png")
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

    /** The query analyzer: costliest kinds of request first, with where they came from and hints. */
    @Test fun queryAnalyzer() = shoot("tv-query-analyzer") { c -> dev.nitrate.tv.ui.settings.QueryAnalyzerScreen(c) }

    // The overlay menu (the default), the side rail and the profile corner.

    private fun focusedName() = compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().firstOrNull()?.config?.toString()
        ?.substringAfter("{")?.replace(Regex("@[0-9a-f]+"), "")

    private fun focusedSays(words: String) =
        compose.onAllNodes(androidx.compose.ui.test.isFocused() and androidx.compose.ui.test.hasContentDescription(words, substring = true), useUnmergedTree = false)
            .fetchSemanticsNodes().isNotEmpty()

    private fun shows(text: String) = compose.onAllNodes(androidx.compose.ui.test.hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun menuHome(name: String, prefs: dev.nitrate.core.pages.DisplayPrefs, check: () -> Unit) =
        shoot(name, { c -> c.displaySettings.savePrefs("user-1", prefs) }, act = check) { c ->
            WithMenu(c) { HomeScreen(c.server, c.displaySettings, onOpen = {}, onPlay = {}, onSettings = {}, onSignOut = {}) }
        }

    private fun focusedBounds() = compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().first().boundsInRoot

    private fun avatars() = compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Joe, account")).fetchSemanticsNodes().size

    /**
     * The overlay pop-up: nothing shows while browsing (no profile corner either); Left off a row's
     * first card opens a card in the middle with focus on Home's tile, Search and the avatar in its
     * top row; the arrows move round the grid and stop at its edges; Back comes back to the card left.
     */
    @Test fun overlayOpensOnLeft() = menuHome("tv-overlay-open", rowsHome) {
        check(!shows("Movies")) { "the overlay shows nothing until called" }
        check(avatars() == 0) { "the overlay has no profile corner on the page" }
        press("down")
        val card = focusedName()
        repeat(8) { press("left") }
        check(!menuOpen()) { "Left at the page's edge doesn't open it" }
        press("menu")
        check(focusedHasText("Home")) { "Menu should open the pop-up on its own stop; focused: ${focusedNodes()}" }
        val home = focusedBounds()
        check(home.left > 120f && home.top > 200f) { "the pop-up sits in the middle, not down the edge: $home" }
        check(shows("Movies") && shows("Search")) { "the pop-up has the libraries and Search in words" }
        check(avatars() == 1) { "the avatar is in the pop-up's top row" }
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("Settings")).fetchSemanticsNodes().isEmpty()) { "Settings is behind the avatar, not a tile" }
        check(!shows("Switch user")) { "Switch user is behind the avatar, not a tile" }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/tv-overlay-opened.png")
        press("left")
        check(focusedHasText("Home")) { "Left past the grid's edge goes nowhere" }
        press("right")
        check(focusedHasText("Movies")) { "Right moves along the grid; focused: ${focusedNodes()}" }
        press("right", "right")
        check(focusedHasText("Customize this page")) { "Customize this page is a tile; focused: ${focusedNodes()}" }
        press("right")
        check(focusedHasText("Customize this page")) { "Right past the last tile goes nowhere" }
        press("left", "left", "left", "up")
        check(focusedHasText("Search")) { "Up from Home reaches Search in the top row; focused: ${focusedNodes()}" }
        press("up")
        check(focusedHasText("Search") && menuOpen()) { "Up past the top goes nowhere" }
        press("down")
        check(focusedHasText("Home")) { "Down from Search comes back to the grid; focused: ${focusedNodes()}" }
        press("back")
        check(!menuOpen()) { "Back closes the pop-up" }
        check(focusedName() == card) { "focus should come back to the card left: ${focusedName()} vs $card" }
    }

    /**
     * Focus landing on the edge's invisible catcher without Left being pressed (a page swapped for
     * another before it has anything to focus) doesn't open the pop-up; Left off the page still does.
     */
    @Test fun overlayStaysShutWhenFocusLandsOnTheEdgeByItself() = menuHome("tv-overlay-stays-shut", rowsHome) {
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Menu")).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); compose.waitForIdle()
        check(!menuOpen()) { "the pop-up opened with no key pressed; focused: ${focusedNodes()}" }
        repeat(8) { if (!menuOpen()) press("up") }
        check(focusedHasText("Home")) { "Up past the page's top still opens it; focused: ${focusedNodes()}" }
    }

    /** Menu opens the pop-up from mid-page and closes it again; Back closes it too, focus back on the card left. */
    @Test fun overlayMenuKeyAndBack() = menuHome("tv-overlay-menu-key", rowsHome) {
        press("down", "right")
        val card = focusedName()
        press("menu")
        check(focusedHasText("Home")) { "Menu should open the pop-up on Home; focused: ${focusedNodes()}" }
        press("menu")
        check(!shows("Movies")) { "Menu again closes it" }
        check(focusedName() == card) { "focus goes back to the card left: ${focusedName()} vs $card" }
        press("menu")
        check(focusedHasText("Home"))
        press("down")
        check(!focusedHasText("Home") && menuOpen()) { "Down moves within the grid" }
        press("back")
        check(!shows("Movies")) { "Back closes it" }
        check(focusedName() == card) { "focus goes back to the card left after Back too: ${focusedName()} vs $card" }
    }

    /** More's stops are a section of the pop-up under a "More" heading (no More tile); Down reaches them. */
    @Test fun overlayMoreSection() = menuHome("tv-overlay-more", rowsHome) {
        press("menu")
        check(focusedHasText("Home"))
        check(shows("Your pages")) { "More's stops show in the pop-up" }
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("More")).fetchSemanticsNodes().size == 1) { "one More heading, no More tile" }
        press("down")
        check(focusedHasText("Your pages") || focusedHasText("Comics")) { "Down from the main tiles reaches More's; focused: ${focusedNodes()}" }
        press("down")
        check(menuOpen()) { "Down past the bottom goes nowhere" }
    }

    /** A page kept in More (Your pages): the pop-up opens on its tile in the More section, marked. */
    @Test fun overlayOpensOnMoreStop() = shoot("tv-overlay-more-current", act = {
        check(focusedHasText("New page")) { "Your pages should open on its first tile" }
        press("menu")
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); compose.waitForIdle()
        check(focusedHasText("Your pages")) { "the pop-up opens on Your pages, in More; focused: ${focusedNodes()}" }
    }) { c ->
        val catalog = dev.nitrate.tv.ui.home.menuCatalog(groupLibraries, emptyList(), dev.nitrate.tv.ui.home.MenuActions(onPages = {}), current = "")
        androidx.compose.runtime.CompositionLocalProvider(dev.nitrate.tv.ui.home.LocalMenuCatalog provides catalog, dev.nitrate.tv.ui.pages.LocalTvContainer provides c) {
            dev.nitrate.tv.ui.home.MainMenu(dev.nitrate.core.pages.MenuStops.PAGES, overlay = false) {
                dev.nitrate.tv.ui.pages.PagesScreen(c) { _, _ -> }
            }
        }
    }

    /** A group opens as its own small grid in the pop-up, focus on its first stop; Back folds it to the group's tile, Back again closes. */
    @Test fun overlayGroupOpens() = shoot("tv-overlay-group", { c ->
        c.displaySettings.savePrefs("user-1", dev.nitrate.core.pages.DisplayPrefs(tvMenuGroups = listOf(animeGroup)))
    }, act = {
        press("menu")
        check(focusedHasText("Movies")) { "the pop-up opens on the page's stop: ${focusedNodes()}" }
        press("right", "right")
        check(focusedHasText("Anime")) { "Right twice from Movies reaches the Anime group; focused: ${focusedNodes()}" }
        check(shows("Collections")) { "Collections (kept in More) shows in the More section" }
        press("ok")
        compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
        check(focusedHasText("Movies") && !focusedHasText("Anime")) { "focus goes to the group's first stop: ${focusedNodes()}" }
        check(shows("Back returns to the menu") && shows("TV Shows") && !shows("Kids")) { "the group's own grid, under its name" }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/tv-overlay-group-open.png")
        press("right")
        check(focusedHasText("TV Shows")) { "Right moves within the group's grid" }
        press("back")
        compose.mainClock.advanceTimeBy(300); compose.waitForIdle()
        check(focusedHasText("Anime") && shows("Kids Movies")) { "Back folds the group, focus on its tile; focused: ${focusedNodes()}" }
        press("back")
        check(!shows("Kids Movies")) { "Back again closes the pop-up" }
        check(focusedHasText("Movies page")) { "focus is back on the page; focused: ${focusedNodes()}" }
    }) { c ->
        val catalog = dev.nitrate.tv.ui.home.menuCatalog(groupLibraries, emptyList(), dev.nitrate.tv.ui.home.MenuActions(), current = "")
        androidx.compose.runtime.CompositionLocalProvider(dev.nitrate.tv.ui.home.LocalMenuCatalog provides catalog, dev.nitrate.tv.ui.pages.LocalTvContainer provides c) {
            dev.nitrate.tv.ui.home.MainMenu(dev.nitrate.core.pages.MenuStops.library("m"), overlay = false) {
                val focus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
                androidx.tv.material3.Button(onClick = {}, modifier = Modifier.padding(200.dp).focusRequester(focus)) { androidx.tv.material3.Text("Movies page") }
                androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            }
        }
    }

    /** Many stops: the grid scrolls inside the card, the focused tile always brought into view. */
    @Test fun overlayScrollsManyStops() = shoot("tv-overlay-many", act = {
        press("menu")
        check(focusedHasText("Home")) { "Menu opens the pop-up on Home" }
        repeat(8) { press("down") }
        val at = compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().first().boundsInRoot
        check(focusedHasText("Comics") || focusedHasText("Your pages")) { "Down reaches the More section at the bottom; focused: ${focusedNodes()}" }
        check(at.top >= 0f && at.bottom <= 1080f) { "the focused tile is on screen: $at" }
    }) { c ->
        val many = (1..24).map { library("x$it", "Library $it", dev.nitrate.core.model.CollectionKind.MOVIES) }
        val catalog = dev.nitrate.tv.ui.home.menuCatalog(many, emptyList(), dev.nitrate.tv.ui.home.MenuActions(), current = "")
        androidx.compose.runtime.CompositionLocalProvider(dev.nitrate.tv.ui.home.LocalMenuCatalog provides catalog, dev.nitrate.tv.ui.pages.LocalTvContainer provides c) {
            dev.nitrate.tv.ui.home.MainMenu(dev.nitrate.core.pages.MenuStops.HOME, overlay = false) {
                val focus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
                androidx.tv.material3.Button(onClick = {}, modifier = Modifier.padding(200.dp).focusRequester(focus)) { androidx.tv.material3.Text("Home page") }
                androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            }
        }
    }

    /** As the dial: Up off the page's top opens the pop-up; Left at the edge doesn't. */
    @Test fun overlayOpensWithUp() = menuHome("tv-overlay-up", rowsHome) {
        repeat(6) { press("left") }
        check(!menuOpen()) { "Left doesn't open it" }
        var card = focusedName()
        repeat(4) { if (!menuOpen()) { card = focusedName(); press("up") } }
        check(menuOpen() && focusedHasText("Home")) { "Up from the top opens the pop-up on Home; focused: ${focusedNodes()}" }
        press("back")
        check(!menuOpen()) { "Back closes it" }
        check(focusedName() == card) { "focus goes back where it was: ${focusedName()} vs $card" }
    }

    /** The side rail: hidden until called, then at once a column of icons and words; Right and Back close it. */
    @Test fun railOpensWithWords() = menuHome("tv-rail-open", rowsHome.copy(tvMenu = dev.nitrate.core.pages.TvMenuStyle.LEFT)) {
        check(!shows("Movies")) { "the rail shows nothing until called" }
        press("down")
        val card = focusedName()
        repeat(8) { if (!focusedHasText("Home")) press("left") }
        check(focusedHasText("Home")) { "Left should open the rail on Home; focused: ${focusedNodes()}" }
        check(shows("Movies")) { "the rail has words, never icons alone" }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/tv-rail-opened.png")
        press("right")
        check(!shows("Movies")) { "Right closes the rail" }
        check(focusedName() == card) { "focus goes back to the card left: ${focusedName()} vs $card" }
        press("menu")
        check(focusedHasText("Home")) { "Menu opens the rail" }
        press("back")
        check(!shows("Movies")) { "Back closes it" }
        check(focusedName() == card) { "and focus is back on the card" }
    }

    /** The rail on the right opens with Right and closes with Left. */
    @Test fun railRight() = menuHome("tv-rail-right", rowsHome.copy(tvMenu = dev.nitrate.core.pages.TvMenuStyle.RIGHT)) {
        press("menu")
        check(focusedHasText("Home")) { "Menu opens the rail on Home" }
        press("left")
        check(!shows("Movies")) { "Left (towards the page) closes it" }
    }

    /** Up from the page's top reaches the profile corner; the avatar opens the account panel, and Back returns to the avatar. */
    @Test fun profileCorner() = menuHome("tv-profile-corner", rowsHome.copy(tvMenu = dev.nitrate.core.pages.TvMenuStyle.LEFT)) {
        press("down", "up")
        repeat(3) { if (!focusedSays("account")) press("up") }
        check(focusedSays("Joe, account")) { "Up from the top should reach the avatar; focused: ${focusedNodes()}" }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/tv-profile-corner-avatar.png")
        press("left")
        check(focusedSays("Search")) { "Search sits beside the avatar" }
        press("right", "ok")
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        check(shows("Switch user") && shows("Settings") && shows("Sign out")) { "the account panel offers Switch user, Settings and Sign out" }
        check(focusedHasText("Switch user")) { "the panel opens on its first choice; focused: ${focusedNodes()}" }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/tv-profile-corner-panel.png")
        press("back")
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        check(!shows("Sign out")) { "Back closes the panel" }
        check(focusedSays("Joe, account")) { "and focus is back on the avatar; focused: ${focusedNodes()}" }
        press("down")
        check(!focusedSays("account")) { "Down goes back to the page" }
        check(compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().isNotEmpty()) { "something on the page has focus" }
    }

    /**
     * Easy mode: the pop-up has no Customize; its avatar (top row) is the way to Settings, Back from
     * the account panel returns to the avatar, and Sign out asks first.
     */
    @Test fun easyModeCornerAndOverlay() = menuHome("tv-easy-corner", rowsHome.copy(easyMode = true)) {
        press("menu")
        check(focusedHasText("Home")) { "Menu opens the pop-up in easy mode too" }
        check(!shows("Customize this page")) { "no Customize in easy mode" }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/tv-easy-overlay.png")
        press("up")
        check(focusedHasText("Search")) { "Up from Home reaches Search; focused: ${focusedNodes()}" }
        press("right")
        check(focusedSays("Joe, account")) { "the avatar sits beside Search; focused: ${focusedNodes()}" }
        press("ok")
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        check(shows("Sign out") && compose.onAllNodes(androidx.compose.ui.test.hasText("Settings")).fetchSemanticsNodes().isNotEmpty()) { "Settings is in the account panel" }
        press("back")
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        check(!shows("Sign out")) { "Back closes the panel" }
        check(focusedSays("Joe, account") && shows("Movies")) { "focus is back on the avatar, the pop-up still open; focused: ${focusedNodes()}" }
        press("ok")
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        repeat(6) { if (!focusedHasText("Sign out")) press("down") }
        check(focusedHasText("Sign out"))
        press("ok")
        check(shows("Sign out?")) { "easy mode asks before signing out" }
        check(focusedHasText("Cancel")) { "the question opens on Cancel" }
        check(!signedOut)
    }

    /** Top bar: the corner sits at the bar's end, reached with Up from the bar. */
    @Test fun topBarCorner() = menuHome("tv-top-bar-corner", rowsHome.copy(tvMenu = dev.nitrate.core.pages.TvMenuStyle.TOP)) {
        press("up")
        check(focusedHasText("Home")) { "Up from the first row reaches the bar" }
        press("up")
        check(focusedSays("Joe, account")) { "Up from the bar reaches the corner; focused: ${focusedNodes()}" }
    }
}

/** Focus is on this node or inside it (a button's text sits inside the focused button). */
private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertIsFocusedOrHasFocusedChild() {
    val node = fetchSemanticsNode()
    fun focused(n: androidx.compose.ui.semantics.SemanticsNode): Boolean =
        (androidx.compose.ui.semantics.SemanticsProperties.Focused in n.config && n.config[androidx.compose.ui.semantics.SemanticsProperties.Focused]) || n.children.any(::focused)
    var p: androidx.compose.ui.semantics.SemanticsNode? = node
    while (p != null) {
        if (focused(p)) return
        p = p.parent
    }
    error("Not focused: ${node.config}")
}
