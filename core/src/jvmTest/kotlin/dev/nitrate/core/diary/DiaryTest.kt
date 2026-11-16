package dev.nitrate.core.diary

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.UserState
import dev.nitrate.core.stats.WatchStatsBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

class DiaryTest {
    private val day = 86_400_000L
    private val jan1 = 1_767_225_600_000L // 2026-01-01 UTC

    private fun film(id: String, at: Long, rating: Int? = null, note: String? = null, name: String = "Film $id") =
        DiaryEntry("d$id$at", id, ItemKind.MOVIE, name, null, null, null, null, 2020, 6_000_000, listOf("Drama"), at, rating, note, false)

    @Test fun stars() {
        assertEquals("★★★½", Diary.stars(7))
        assertEquals("★★★★★", Diary.stars(10))
        assertEquals("½", Diary.stars(1))
        assertEquals("", Diary.stars(null))
    }

    @Test fun letterboxd() {
        val entries = listOf(
            film("a", jan1 + 3 * day, rating = 7, note = "Tense, \"brilliant\""),
            film("a", jan1 + 40 * day, rating = 8),
            film("b", jan1, name = "Comma, The"),
        )
        val csv = Diary.letterboxdCsv(entries).lines()
        assertEquals("Title,Year,WatchedDate,Rating,Rewatch,Review", csv[0])
        assertEquals("\"Comma, The\",2020,2026-01-01,,false,", csv[1])
        assertEquals("Film a,2020,2026-01-04,3.5,false,\"Tense, \"\"brilliant\"\"\"", csv[2])
        assertEquals("Film a,2020,2026-02-10,4,true,", csv[3])
    }

    @Test fun yourYearCountsEveryWatch() {
        // Jellyfin's own record: watched twice, last in February. The diary knows January too.
        val history = listOf(
            MediaItem("a", "Film a", ItemKind.MOVIE, runtimeMs = 6_000_000, user = UserState(played = true, lastPlayedMs = jan1 + 40 * day, playCount = 2)),
            // Before the diary started: still counted from Jellyfin's record.
            MediaItem("old", "Old one", ItemKind.MOVIE, runtimeMs = 6_000_000, user = UserState(played = true, lastPlayedMs = jan1 - 30 * day, playCount = 1)),
            MediaItem("song", "Song", ItemKind.AUDIO, runtimeMs = 180_000, user = UserState(played = true, lastPlayedMs = jan1 + day, playCount = 3)),
        )
        val diary = listOf(film("a", jan1 + 3 * day), film("a", jan1 + 40 * day))
        val merged = Diary.asHistory(diary, history)
        assertEquals(setOf("a", "old", "song"), merged.map { it.id }.toSet())
        val year = WatchStatsBuilder.build(merged, 2026)
        assertEquals(1, year.films)
        assertEquals(1, year.songs)
        assertEquals(listOf("Film a"), year.rewatched.map { it.name })
        assertEquals(1, WatchStatsBuilder.build(merged, 2025).films)
    }
}
