package dev.nitrate.mobile

import dev.nitrate.android.viewmodel.GridMarks
import dev.nitrate.core.MediaServer
import dev.nitrate.core.events.ServerEvent
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.UserState
import dev.nitrate.testing.FakeServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Library pages put changed watch marks on the titles they hold, without reloading. */
class GridMarksTest {
    private val asked = mutableListOf<String>()
    private val server = object : MediaServer by FakeServer() {
        override suspend fun userStates(ids: List<String>): Map<String, UserState> {
            asked += "states:" + ids.sorted().joinToString(",")
            return ids.associateWith { UserState(played = false, unplayedCount = 3) }
        }
        override suspend fun parentIds(ids: List<String>): Map<String, List<String>> {
            asked += "parents:" + ids.sorted().joinToString(",")
            return ids.associateWith { listOf("show", "season-1") }
        }
    }
    private val film = MediaItem("film", "A film", ItemKind.MOVIE)
    private val show = MediaItem("show", "A show", ItemKind.SERIES, user = UserState(unplayedCount = 4))
    private val shown = listOf(film, show)

    @Test fun marksSentByTheServerAreUsedAsTheyAre() = runBlocking {
        val states = GridMarks.of(server, listOf(film), ServerEvent.UserDataChanged(listOf("film"), mapOf("film" to UserState(played = true))))
        assertEquals(true, GridMarks.apply(listOf(film), states).single().user.played)
        assertTrue(asked.isEmpty())
    }

    @Test fun anEpisodeElsewhereUpdatesItsShowsPoster() = runBlocking {
        val states = GridMarks.of(server, shown, ServerEvent.UserDataChanged(listOf("ep5"), mapOf("ep5" to UserState(played = true))))
        assertEquals(listOf("parents:ep5", "states:show"), asked)
        assertEquals(3, GridMarks.apply(shown, states).first { it.id == "show" }.user.unplayedCount)
    }

    @Test fun nothingOnThePageAsksNothing() = runBlocking {
        val states = GridMarks.of(server, listOf(film), ServerEvent.UserDataChanged(listOf("ep5"), emptyMap()))
        assertTrue(states.isEmpty())
        // No shows or seasons here: whose episode it was doesn't matter.
        assertTrue(asked.isEmpty())
        assertSame(shown, GridMarks.apply(shown, states))
    }
}
