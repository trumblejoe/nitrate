package dev.nitrate.tv

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.nitrate.android.AppContainer
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.CollectionKind
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.pages.ItemQuery
import dev.nitrate.testing.FakeServer
import dev.nitrate.tv.ui.library.BrowseLibrary
import dev.nitrate.tv.ui.theme.NitrateTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Pressing a letter in a library's A–Z row lands on that letter's first title, even when the server miscounts. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi", application = android.app.Application::class)
class LetterJumpTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // Eight titles for each letter, named so their order is obvious.
    private val titles = ('A'..'Z').flatMap { l -> (1..80).map { n -> MediaItem("$l$n", "$l title $n", ItemKind.MOVIE) } }
    private val fake = FakeServer()
    private val server = object : MediaServer by fake {
        override suspend fun queryPage(query: ItemQuery, startIndex: Int, limit: Int): Pair<List<MediaItem>, Int> {
            kotlinx.coroutines.delay(300)
            // Counts like a server that disagrees with its own order: through the end of the
            // letter instead of up to its start (and the "before" count still pages normally).
            val matching = titles.filter { t -> query.nameBefore?.let { t.name.first().lowercaseChar() <= it.lowercase().first() } ?: true }
            return matching.drop(startIndex).take(limit) to matching.size
        }
    }

    private fun focusedText(): String = compose.onAllNodes(isFocused()).fetchSemanticsNodes().joinToString { n ->
        n.config.getOrElseNullable(SemanticsProperties.Text) { null }?.joinToString() ?: n.config.toString()
    }

    @Test fun grid() = jumps(dev.nitrate.core.pages.BrowseView.GRID, "M")
    @Test fun gridFar() = jumps(dev.nitrate.core.pages.BrowseView.GRID, "W")
    @Test fun artwork() = jumps(dev.nitrate.core.pages.BrowseView.ARTWORK, "T")
    @Test fun list() = jumps(dev.nitrate.core.pages.BrowseView.LIST, "C")

    @OptIn(ExperimentalTestApi::class)
    private fun jumps(view: dev.nitrate.core.pages.BrowseView, letter: String) {
        val container = AppContainer(compose.activity, "test", "0", serverOverride = server)
        runBlocking { container.displaySettings.import("user-1", emptyMap()); container.displaySettings.saveView("user-1", "browse/lib-movies", dev.nitrate.core.pages.BrowseOptions(view = view)) }
        val library = MediaItem("lib-movies", "Movies", ItemKind.LIBRARY, collection = CollectionKind.MOVIES)
        compose.setContent {
            NitrateTheme { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                BrowseLibrary(server, container.displaySettings, library, header = { Text("Movies") }, onOpen = {}, onPlay = {})
            } }
        }
        compose.waitForIdle()
        run {
            val button = compose.onNode(androidx.compose.ui.test.hasText(letter) and androidx.compose.ui.test.hasClickAction())
            button.performSemanticsAction(SemanticsActions.RequestFocus)
            compose.waitForIdle()
            compose.onAllNodes(isFocused()).fetchSemanticsNodes()
            compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
            compose.waitForIdle()
            repeat(30) { org.robolectric.shadows.ShadowLooper.idleMainLooper(200, java.util.concurrent.TimeUnit.MILLISECONDS); compose.mainClock.advanceTimeBy(200); compose.waitForIdle() }
            compose.waitForIdle()
            println("after $letter: focused=" + focusedText())
            assertEquals("$letter title 1", focusedText().substringBefore(","))
        }
    }
}
