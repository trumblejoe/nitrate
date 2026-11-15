package dev.nitrate.core.recap

import dev.nitrate.core.model.Chapter
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.MediaSegment
import dev.nitrate.core.model.SegmentKind
import dev.nitrate.core.model.UserState
import dev.nitrate.core.pages.DisplayPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecapTest {
    private val day = 24 * 60 * 60_000L
    private val now = 1_000 * day
    private val prefs = DisplayPrefs(recapAfterDays = 14)

    private fun ep(s: Int, e: Int, playedDaysAgo: Int? = null) = MediaItem(
        "s$s-e$e", "Episode $e", ItemKind.EPISODE, seriesId = "show", seasonNumber = s, indexNumber = e, runtimeMs = 45 * 60_000L,
        user = UserState(played = playedDaysAgo != null, lastPlayedMs = playedDaysAgo?.let { now - it * day }),
    )

    private val show = MediaItem("show", "The Night Reel", ItemKind.SERIES)

    @Test fun aRecapAfterABreakShowsOnlyWhatWasWatched() {
        val episodes = listOf(ep(1, 1, 40), ep(1, 2, 30), ep(1, 3, 21), ep(1, 4, 20), ep(1, 5), ep(1, 6))
        val recap = Recaps.forSeries(show, episodes, episodes[4], prefs, now)!!
        assertEquals(listOf("s1-e2", "s1-e3", "s1-e4"), recap.cards.map { it.id })
        assertEquals("You're on S1 E5", recap.where)
        assertEquals("2 weeks ago", recap.since)
        assertEquals("s1-e4", recap.clip?.item?.id)
    }

    @Test fun noRecapTooSoonNeverOrWithNothingWatched() {
        val episodes = listOf(ep(1, 1, 3), ep(1, 2))
        assertNull(Recaps.forSeries(show, episodes, episodes[1], prefs, now))
        assertTrue(Recaps.forSeries(show, episodes, episodes[1], prefs.copy(recapAfterDays = 3), now) != null)
        // Exactly the break counts.
        assertTrue(Recaps.forSeries(show, listOf(ep(1, 1, 14), ep(1, 2)), ep(1, 2), prefs, now) != null)
        assertNull(Recaps.forSeries(show, episodes, episodes[1], prefs.copy(recapAfterDays = 0), now))
        assertNull(Recaps.forSeries(show, listOf(ep(1, 1), ep(1, 2)), ep(1, 1), prefs, now))
    }

    @Test fun theClipEndsWhereTheCreditsStart() {
        val e = ep(1, 1, 30)
        // The server's credits marker.
        assertEquals(RecapClip(e, 40 * 60_000L - Recaps.CLIP_MS, 40 * 60_000L), Recaps.clip(e, listOf(MediaSegment(SegmentKind.OUTRO, 40 * 60_000L, 45 * 60_000L))))
        // A chapter named like credits.
        val credits = Recaps.clip(e, emptyList(), listOf(Chapter(0, "Opening"), Chapter(43 * 60_000L, "End Credits")))
        assertEquals(43 * 60_000L, credits?.endMs)
        // Neither: the last minute is taken to be credits.
        assertEquals(44 * 60_000L, Recaps.clip(e, emptyList(), emptyList())?.endMs)
        // No length, no clip.
        assertNull(Recaps.clip(e.copy(runtimeMs = null), emptyList()))
    }

    @Test fun playlistsRecapOnlyWhenMarkedInStoryOrder() {
        val list = MediaItem("pl", "In order", ItemKind.PLAYLIST)
        val items = listOf(ep(1, 1, 60), ep(1, 2, 50), ep(1, 3), ep(1, 4))
        assertNull(Recaps.forPlaylist(list, items, prefs, now))
        val recap = Recaps.forPlaylist(list, items, prefs.copy(recapPlaylists = setOf("pl")), now)!!
        assertEquals("Next: S1 E3 · Episode 3", recap.where)
        assertEquals("s1-e3", recap.next.id)
        assertEquals(listOf("s1-e4"), recap.then)
        assertEquals("7 weeks ago", recap.since)
    }

    @Test fun dueAtExactlyTheBreakOrLonger() {
        val p = DisplayPrefs(recapAfterDays = 8)
        assertTrue(Recaps.due(p, now - 8 * day, now))
        assertTrue(Recaps.due(p, now - 9 * day, now))
        org.junit.Assert.assertFalse(Recaps.due(p, now - 8 * day + 1, now))
        org.junit.Assert.assertFalse(Recaps.due(p.copy(recapAfterDays = 0), now - 400 * day, now))
        assertEquals(listOf("Never", "8 days", "2 weeks", "1 month", "6 months", "1 year"), RECAP_CHOICES.map { it.second })
        assertEquals(14, DisplayPrefs().recapAfterDays)
    }

    @Test fun sinceReadsNaturally() {
        assertEquals("yesterday", Recaps.since(now - day, now))
        assertEquals("5 days ago", Recaps.since(now - 5 * day, now))
        assertEquals("3 months ago", Recaps.since(now - 95 * day, now))
    }
}
