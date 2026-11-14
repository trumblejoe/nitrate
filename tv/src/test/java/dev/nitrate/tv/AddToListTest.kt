package dev.nitrate.tv

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.testing.FakeServer
import dev.nitrate.tv.ui.details.DetailsScreen
import dev.nitrate.tv.ui.theme.NitrateTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Opening "Add to…" with the remote's OK must not add the item anywhere by itself. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi", application = android.app.Application::class)
class AddToListTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val added = mutableListOf<String>()
    private val episode = MediaItem("ep", "Episode title 4", ItemKind.EPISODE, seriesId = "series-1", seriesName = "The Night Reel", seasonId = "season-2", seasonNumber = 2, indexNumber = 4)
    private val fake = FakeServer()
    private val server = object : MediaServer by fake {
        override suspend fun getItem(id: String) = if (id == "ep") episode else fake.getItem(id)
        override suspend fun addToPlaylist(playlistId: String, itemIds: List<String>) { added += "playlist:$playlistId" }
        override suspend fun addToCollection(collectionId: String, itemIds: List<String>) { added += "collection:$collectionId" }
        override suspend fun createPlaylist(name: String, itemIds: List<String>, audio: Boolean): String { added += "new:$name"; return "x" }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun okOnAddToOnlyOpensTheDialog() {
        compose.setContent { NitrateTheme { DetailsScreen(server, "ep", null, onPlay = { _, _ -> }, onOpen = {}, canCollect = true) } }
        compose.waitForIdle()
        val button = compose.onNodeWithText("Add to…")
        button.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        button.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(5_000)
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodesWithText("Rainy day").fetchSemanticsNodes().size)
        assertEquals(emptyList<String>(), added)
    }

    @Test
    fun ticksSeveralReviewsThenAddsToEach() {
        compose.setContent { NitrateTheme { dev.nitrate.tv.ui.lists.AddToListDialog(server, episode, canCollect = true) {} } }
        compose.waitForIdle()
        // Both columns are there at once.
        compose.onNodeWithText("Rainy day").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Midnight Movies").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Road trip").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Road trip").performSemanticsAction(SemanticsActions.OnClick) // untick
        assertEquals(emptyList<String>(), added)
        compose.onNodeWithText("Review (2)").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText("•  Rainy day").assertExists()
        compose.onNodeWithText("•  Midnight Movies").assertExists()
        assertEquals(emptyList<String>(), added)
        compose.onNodeWithText("Add").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(listOf("playlist:p1", "collection:c2"), added)
        compose.onNodeWithText("Done").assertExists()
    }
}
