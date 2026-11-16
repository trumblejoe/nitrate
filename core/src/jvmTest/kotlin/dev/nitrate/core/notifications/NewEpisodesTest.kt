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

    @Test
    fun `tells about the first episode of a new season of shows followed or watched`() {
        val recent = listOf(
            ep("a3e1", "a", 300, s = 3, e = 1).copy(seasonId = "a-s3"),
            ep("a3e2", "a", 300, s = 3, e = 2),
            ep("b2e2", "b", 290, s = 2, e = 2), // Season already started.
            ep("c2e1", "c", 280, s = 2, e = 1), // Neither followed nor watched.
            ep("d0e1", "d", 270, s = 0, e = 1), // Specials aren't a season.
            ep("e2e1", "e", 100, s = 2, e = 1), // Before the last check.
            ep("f4e1", "f", 260, s = 4, e = 1, played = true), // Already seen.
            ep("g1e1", "g", 250, s = 1, e = 1), // A new show's first season counts too.
        )
        val alerts = NewSeasons.alerts(recent, shows = setOf("a", "b", "d", "e", "f", "g"), sinceMs = 150)
        assertEquals(listOf("a" to 3, "g" to 1), alerts.map { it.seriesId to it.seasonNumber })
        val a = alerts.first()
        assertEquals("New season of A", a.title)
        // No episode names or counts: they can give the season away.
        assertEquals("Season 3 is here", a.text)
        assertEquals("a-s3", a.openItemId)
        assertEquals("g", alerts[1].openItemId)
    }
}
