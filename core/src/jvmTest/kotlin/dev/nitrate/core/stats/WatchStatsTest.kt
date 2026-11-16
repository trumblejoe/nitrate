package dev.nitrate.core.stats

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.UserState
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchStatsTest {
    private fun at(date: String) = java.time.Instant.parse("${date}T20:00:00Z").toEpochMilli()

    private fun film(id: String, date: String, plays: Int = 1, genres: List<String> = listOf("Drama")) =
        MediaItem(id, "Film $id", ItemKind.MOVIE, runtimeMs = 120 * 60_000, genres = genres, user = UserState(played = true, lastPlayedMs = at(date), playCount = plays))

    private fun episode(id: String, show: String, date: String) =
        MediaItem(id, "Ep $id", ItemKind.EPISODE, runtimeMs = 45 * 60_000, seriesId = show, seriesName = "Show $show", genres = listOf("Comedy"), user = UserState(played = true, lastPlayedMs = at(date), playCount = 1))

    private fun song(id: String, artist: String, date: String, plays: Int) =
        MediaItem(id, "Song $id", ItemKind.AUDIO, runtimeMs = 4 * 60_000, artists = listOf(artist), user = UserState(played = true, lastPlayedMs = at(date), playCount = plays))

    private val history = listOf(
        film("a", "2026-02-03", plays = 3, genres = listOf("Drama", "Mystery")), film("b", "2026-02-20"), film("old", "2024-07-01"),
        episode("1", "x", "2026-03-01"), episode("2", "x", "2026-03-02"), episode("3", "y", "2026-11-01"),
        song("s1", "Iris Out", "2026-05-05", plays = 10), song("s2", "The Projectionists", "2026-05-06", plays = 2),
    )

    @Test fun `a year counts what was last watched in it, time counts every play`() {
        val s = WatchStatsBuilder.build(history, 2026)
        assertEquals(2, s.films)
        assertEquals(3, s.episodes)
        assertEquals(2, s.shows)
        assertEquals(2, s.songs)
        assertEquals(120L * 3 + 120 + 45 * 3, s.watchMinutes)
        assertEquals(listOf("Show x", "Show y"), s.topShows.map { it.name })
        assertEquals("Drama", s.topGenres.first().name)
        assertEquals(listOf("Iris Out" to 10, "The Projectionists" to 2), s.topArtists.map { it.name to it.count })
        assertEquals(listOf("Film a" to 3), s.rewatched.map { it.name to it.count })
        assertEquals(listOf(0, 2, 2, 0, 2, 0, 0, 0, 0, 0, 1, 0), s.byMonth)
    }

    @Test fun `all time, the years, and readable durations`() {
        assertEquals(3, WatchStatsBuilder.build(history, null).films)
        assertEquals(listOf(2026, 2024), WatchStatsBuilder.years(history))
        assertEquals("3 days, 4 hours", WatchStatsBuilder.duration(3 * 1440 + 4 * 60 + 10))
        assertEquals("18 hours", WatchStatsBuilder.duration(18 * 60))
        // New Year's Eve evening in New York is already the next year in UTC.
        val nye = listOf(film("n", "2027-01-01").copy(user = UserState(played = true, lastPlayedMs = java.time.Instant.parse("2027-01-01T03:00:00Z").toEpochMilli())))
        assertEquals(1, WatchStatsBuilder.build(nye, 2026, utcOffsetMs = -5 * 3_600_000L).films)
    }
}
