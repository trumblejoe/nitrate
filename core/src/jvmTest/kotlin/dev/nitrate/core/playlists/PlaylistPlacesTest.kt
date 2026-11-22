package dev.nitrate.core.playlists

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.MediaKind
import dev.nitrate.core.model.UserState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistPlacesTest {
    private fun e(id: String, played: Boolean = false, series: String? = null, kind: ItemKind = ItemKind.EPISODE, lastPlayed: Long? = null, season: Int? = null, missing: Boolean = false) =
        MediaItem(id, id, kind, seriesId = series, seasonNumber = season, isMissing = missing, user = UserState(played = played, lastPlayedMs = lastPlayed))

    private val entries = listOf(e("a", true, lastPlayed = 1), e("b", true, lastPlayed = 2), e("c", lastPlayed = 5), e("d", true, lastPlayed = 3), e("e"), e("f"), e("g"), e("h"), e("i"), e("j"))

    @Test fun playlistOrderIsKeptAndWholeShowsBecomeTheirEpisodes() {
        val show = MediaItem("arrow", "Arrow", ItemKind.SERIES)
        val items = listOf(e("movie", kind = ItemKind.MOVIE), show, e("z"))
        val eps = mapOf("arrow" to listOf(e("special", series = "arrow", season = 0), e("s1e1", series = "arrow", season = 1), e("gone", series = "arrow", season = 1, missing = true), e("s1e2", series = "arrow", season = 1)))
        assertEquals(listOf("arrow"), PlaylistPlaces.wholeShows(items))
        assertEquals(listOf("movie", "s1e1", "s1e2", "z"), PlaylistPlaces.entries(items, eps).map { it.id })
    }

    @Test fun progressFindsWhatsNext() {
        val list = listOf(e("a", true), e("b", kind = ItemKind.MOVIE), e("c", true), e("d"), e("e"))
        val p = PlaylistPlaces.progress(list)
        assertEquals(2, p.watched)
        assertEquals(5, p.total)
        assertEquals("d", p.next?.id)
        assertFalse(p.resuming)
        assertEquals("2 of 5 watched", p.line)
    }

    @Test fun musicPlaylistsAreLeftOut() {
        val lists = listOf(MediaItem("v", "Saga", ItemKind.PLAYLIST, mediaKind = MediaKind.VIDEO), MediaItem("m", "Gym", ItemKind.PLAYLIST, mediaKind = MediaKind.AUDIO), MediaItem("u", "Unknown", ItemKind.PLAYLIST))
        assertEquals(listOf("v", "u"), PlaylistPlaces.candidates(lists).map { it.id })
    }

    @Test fun whereItWasLeftComesFromPlayDates() {
        // c was played most recently (it's part way), even though d was finished earlier.
        assertEquals(2, PlaylistPlaces.lastPlayed(entries))
        assertEquals(5L, PlaylistPlaces.lastPlayedMs(entries))
        assertNull(PlaylistPlaces.lastPlayed(listOf(e("x"), e("y"))))
        assertEquals("c", PlaylistPlaces.nextUp(entries)?.id)
    }

    @Test fun carriesOnNearThePlace() {
        // Left at c: c itself, b (gone back) and a few after count; far ahead doesn't.
        assertEquals(2, PlaylistPlaces.indexNearPlace(entries, 2, "c"))
        assertEquals(1, PlaylistPlaces.indexNearPlace(entries, 2, "b"))
        assertEquals(5, PlaylistPlaces.indexNearPlace(entries, 2, "f"))
        assertNull(PlaylistPlaces.indexNearPlace(entries, 2, "j"))
        assertNull(PlaylistPlaces.indexNearPlace(entries, 2, "a"))
        assertNull(PlaylistPlaces.indexNearPlace(entries, 99, "c"))
    }

    @Test fun watchedOnesAfterThePlaceAreSkipped() {
        val list = listOf(e("a"), e("b", true), e("c", true), e("d", true), e("e", true), e("f"), e("g"))
        assertEquals("f", PlaylistPlaces.continueFrom(list, 1)?.id)
        // f is past four watched ones, still the playlist's.
        assertEquals(5, PlaylistPlaces.indexNearPlace(list, 1, "f"))
    }

    @Test fun wholeShowsAsStartingPoints() {
        val list = listOf(e("m", kind = ItemKind.MOVIE), e("s1e1", true, "show"), e("s1e2", series = "show"), e("s1e3", series = "show"))
        assertEquals(2, PlaylistPlaces.placeIndex(list, "show"))
        assertEquals(0, PlaylistPlaces.placeIndex(list, "m"))
        assertEquals(-1, PlaylistPlaces.placeIndex(list, "nope"))
    }
}
