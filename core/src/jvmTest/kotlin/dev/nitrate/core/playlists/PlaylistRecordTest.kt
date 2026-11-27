package dev.nitrate.core.playlists

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.UserState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaylistRecordTest {
    private fun ep(id: String, played: Boolean = false, position: Long = 0, lastPlayed: Long? = null, series: String = "s") =
        MediaItem(id, id, ItemKind.EPISODE, seriesId = series, runtimeMs = 1_000_000, user = UserState(played = played, positionMs = position, lastPlayedMs = lastPlayed))

    @Test
    fun theRecordReplacesTheUsersOwnMarks() {
        // Watched on their own (e1, e3); in the playlist only e1 and e2.
        val entries = listOf(ep("e1", played = true), ep("e2"), ep("e3", played = true, lastPlayed = 50), ep("e4", position = 300_000))
        val record = PlaylistRecord("pl", watched = setOf("e1", "e2"), resume = mapOf("e3" to 120_000), lastId = "e3", lastAtMs = 99)
        val seen = PlaylistRecords.apply(entries, record)
        assertEquals(listOf(true, true, false, false), seen.map { it.user.played })
        assertEquals(listOf(0L, 0L, 120_000L, 0L), seen.map { it.user.positionMs })
        // The playlist carries on from where it was left, by its own record.
        assertEquals(2, PlaylistPlaces.lastPlayed(seen))
        assertEquals("e3", PlaylistPlaces.continueFrom(seen, 2)?.id)
    }

    @Test
    fun seededOnceFromTheUsersOwnProgress() {
        val entries = listOf(ep("e1", played = true, lastPlayed = 10), ep("e2", played = true, lastPlayed = 20), ep("e3", position = 200_000, lastPlayed = 30), ep("e4"))
        val r = PlaylistRecords.seed("pl", entries)
        assertEquals(setOf("e1", "e2"), r.watched)
        assertEquals(mapOf("e3" to 200_000L), r.resume)
        assertEquals("e3", r.lastId)
        assertEquals(30, r.lastAtMs)
    }

    @Test
    fun aSittingStopsWatchedOrWithAResumePoint() {
        val r = PlaylistRecord("pl")
        val partway = PlaylistRecords.stopped(r, "e1", 400_000, runtimeMs = 1_000_000, ended = false, atMs = 5)
        assertFalse(partway.watched("e1"))
        assertEquals(400_000, partway.resume["e1"])
        assertEquals("e1", partway.lastId)
        val done = PlaylistRecords.stopped(partway, "e1", 950_000, runtimeMs = 1_000_000, ended = false, atMs = 6)
        assertTrue(done.watched("e1"))
        assertTrue("e1" !in done.resume)
        // A few seconds in isn't a resume point.
        assertTrue("e2" !in PlaylistRecords.stopped(r, "e2", 5_000, 1_000_000, ended = false, atMs = 7).resume)
    }

    @Test
    fun markingUpToAShowPutInWhole() {
        val entries = listOf(ep("a1", series = "a"), ep("b1", series = "b"), ep("b2", series = "b"), ep("c1", series = "c"))
        val r = PlaylistRecords.markUpTo(PlaylistRecord("pl"), entries, "b", watched = true)
        assertEquals(setOf("a1", "b1", "b2"), r.watched)
        assertEquals(setOf("a1"), PlaylistRecords.markUpTo(r, entries, "b1", watched = false).watched)
    }
}
