package dev.nitrate.mobile

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTouchInput
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nitrate.android.AppContainer
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.CollectionKind
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.pages.ItemQuery
import dev.nitrate.mobile.ui.AppActions
import dev.nitrate.mobile.ui.BrowseLibrary
import dev.nitrate.mobile.ui.NitrateTheme
import dev.nitrate.mobile.ui.ProvideDisplayPrefs
import dev.nitrate.testing.FakeServer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Tapping a letter on the phone's A–Z strip brings that letter's first title to the top row. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-night-xxhdpi", application = android.app.Application::class)
class LetterJumpTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // Like Jellyfin: sorted by sort name (lowercase, leading article dropped), counted the same way.
    private fun sortName(name: String) = name.lowercase().removePrefix("the ").removePrefix("an ").removePrefix("a ")
    private val names = ('A'..'Z').flatMap { l -> (1..30).map { n -> "$l title %02d".format(n) } } +
        listOf("The Thing", "The Avengers", "Tenet", "The Terminal", "A Tale of Two", "Titanic", "Toy Story", "The Truman Show", "Up", "Tár")
    private val titles = names.map { MediaItem(it.hashCode().toString(), it, ItemKind.MOVIE, sortName = sortName(it)) }.sortedBy { it.sortName }
    private val fake = FakeServer()
    private val server = object : MediaServer by fake {
        override suspend fun queryPage(query: ItemQuery, startIndex: Int, limit: Int): Pair<List<MediaItem>, Int> {
            val matching = titles.filter { t -> query.nameBefore?.let { t.sortName!! < it.lowercase() } ?: true }
            return matching.drop(startIndex).take(limit) to matching.size
        }
    }

    private fun jump(letter: String, view: dev.nitrate.core.pages.BrowseView) {
        val container = AppContainer(compose.activity, "test", "0", serverOverride = server)
        kotlinx.coroutines.runBlocking {
            container.displaySettings.import("user-1", emptyMap())
            container.displaySettings.saveView("user-1", "browse/lib-movies", dev.nitrate.core.pages.BrowseOptions(view = view))
        }
        val library = MediaItem("lib-movies", "Movies", ItemKind.LIBRARY, collection = CollectionKind.MOVIES)
        compose.setContent {
            ProvideDisplayPrefs(container, "user-1") { NitrateTheme { Surface(color = MaterialTheme.colorScheme.background) {
                BrowseLibrary(AppActions(container, rememberNavController(), play = { _, _ -> }), library)
            } } }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        // The strip's letter, tapped where it's drawn.
        val strip = compose.onAllNodes(hasText(letter), useUnmergedTree = true).fetchSemanticsNodes().maxBy { it.boundsInRoot.left }
        compose.onRoot().performTouchInput { click(strip.boundsInRoot.center) }
        repeat(10) { compose.mainClock.advanceTimeBy(300); compose.waitForIdle() }
        // The letter's first title is in the top row (in a grid, possibly after the previous letter's last).
        val first = titles.first { dev.nitrate.core.pages.Browse.letterOf(it) == letter }.name
        val shownTitles = compose.onAllNodes(hasText(" title ", substring = true) or hasText("The ", substring = true))
            .fetchSemanticsNodes().filter { it.boundsInRoot.height > 0 }
        val top = shownTitles.minOf { it.boundsInRoot.top }
        val firstNode = compose.onAllNodes(hasText(first)).fetchSemanticsNodes().single()
        assertEquals("$first is in the top row", top, firstNode.boundsInRoot.top)
    }

    @Test fun tGrid() = jump("T", dev.nitrate.core.pages.BrowseView.GRID)
    @Test fun tList() = jump("T", dev.nitrate.core.pages.BrowseView.LIST)
    @Test fun mGrid() = jump("M", dev.nitrate.core.pages.BrowseView.GRID)
    @Test fun sList() = jump("S", dev.nitrate.core.pages.BrowseView.LIST)
}
