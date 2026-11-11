package dev.nitrate.core.notifications

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.UserState
import kotlin.test.Test
import kotlin.test.assertEquals

class NewEpisodesTest {
    private fun ep(id: String, series: String, added: Long, s: Int = 1, e: Int = 1, played: Boolean = false) =
        MediaItem(id, "Episode $e", ItemKind.EPISODE, seriesId = series, seriesName = series.uppercase(), seasonNumber = s, indexNumber = e, dateAddedMs = added, user = UserState(played = played))

    @Test
    fun `tells about unwatched new episodes of followed shows, per show`() {
        val recent = listOf(
            ep("a2", "a", 300, e = 2),
            ep("b1", "b", 250),
            ep("a1", "a", 200, e = 1),
            ep("c1", "c", 280), // Not followed.
            ep("a0", "a", 100, e = 0), // Before the last check.
            ep("b0", "b", 260, e = 0, played = true), // Already watched elsewhere.
        )
        val alerts = NewEpisodes.alerts(recent, following = setOf("a", "b"), sinceMs = 150)
        assertEquals(listOf("a", "b"), alerts.map { it.seriesId })
        assertEquals("2 new episodes of A", alerts[0].title)
        assertEquals("S1E1 · Episode 1, S1E2 · Episode 2", alerts[0].text)
        assertEquals("a", alerts[0].openItemId)
        assertEquals("New episode of B", alerts[1].title)
        assertEquals("b1", alerts[1].openItemId)
        assertEquals(300L, NewEpisodes.newestAdded(recent, 150))
    }
}
