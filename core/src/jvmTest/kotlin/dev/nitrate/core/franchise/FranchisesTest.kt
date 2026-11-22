package dev.nitrate.core.franchise

import dev.nitrate.core.effects.PageTheme
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.MediaKind
import dev.nitrate.core.model.UserState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class FranchisesTest {
    private val day = 86_400_000L
    private fun show(id: String, name: String) = MediaItem(id, name, ItemKind.SERIES)
    private fun film(id: String, aired: Long, played: Boolean = false) = MediaItem(id, id, ItemKind.MOVIE, premiereDateMs = aired * day, user = UserState(played = played))
    private fun ep(series: MediaItem, s: Int, e: Int, name: String, aired: Long, played: Boolean = false) =
        MediaItem("${series.id}-$s-$e", name, ItemKind.EPISODE, seriesId = series.id, seriesName = series.name, seasonNumber = s, indexNumber = e, premiereDateMs = aired * day, user = UserState(played = played))

    private val arrow = show("arrow", "Arrow")
    private val flash = show("flash", "The Flash")

    /** As a franchise plugin would lay it out: by hand, not by date. */
    private val playlist = listOf(
        ep(arrow, 1, 1, "Pilot", 100, played = true),
        film("movie", 300, played = true),
        ep(flash, 1, 8, "Flash vs. Arrow", 506),
        ep(arrow, 3, 8, "The Brave and the Bold", 507),
        ep(flash, 1, 1, "Pilot", 500),
    )

    @Test fun playlistOrderIsKept() {
        val ids = Franchises.entries(playlist, emptyMap(), WatchOrder.PLAYLIST).map { it.item.id }
        assertEquals(listOf("arrow-1-1", "movie", "flash-1-8", "arrow-3-8", "flash-1-1"), ids)
    }

    @Test fun airedAndReleaseOrders() {
        assertEquals(listOf("arrow-1-1", "movie", "flash-1-1", "flash-1-8", "arrow-3-8"), Franchises.entries(playlist, emptyMap(), WatchOrder.AIRED).map { it.item.id })
        // Titles by when they began, each show's episodes together in the playlist's order.
        assertEquals(listOf("arrow-1-1", "arrow-3-8", "movie", "flash-1-8", "flash-1-1"), Franchises.entries(playlist, emptyMap(), WatchOrder.RELEASE).map { it.item.id })
    }

    @Test fun wholeShowsBecomeTheirEpisodes() {
        val items = listOf(film("movie", 1), arrow)
        val eps = mapOf("arrow" to listOf(ep(arrow, 0, 1, "Special", 2), ep(arrow, 1, 1, "Pilot", 3), ep(arrow, 1, 2, "Honor Thy Father", 4)))
        assertEquals(listOf("arrow"), Franchises.wholeShows(items))
        assertEquals(listOf("movie", "arrow-1-1", "arrow-1-2"), Franchises.entries(items, eps, WatchOrder.PLAYLIST).map { it.item.id })
        assertEquals(listOf("movie", "arrow"), Franchises.titleIds(items))
    }

    @Test fun crossoversByStoryAndDate() {
        val a = show("a", "Supergirl")
        val b = show("b", "Legends")
        val items = listOf(
            ep(a, 4, 9, "Crisis on Infinite Earths: Part One", 1000),
            ep(b, 5, 1, "Crisis on Infinite Earths: Part Two", 1001),
            ep(a, 4, 10, "Something else", 1001),
            ep(b, 5, 2, "Crisis on Infinite Earths: Part Three", 1100), // too long after
        )
        val c = Franchises.crossovers(items)
        assertEquals(setOf("a-4-9", "b-5-1"), c.keys)
        assertEquals("Crossover with Legends S5:E1", c.getValue("a-4-9").label)
        assertEquals("crisis on infinite earths", Franchises.storyName("Crisis on Infinite Earths: Part Two"))
        assertEquals("invasion", Franchises.storyName("Invasion!"))
    }

    @Test fun progressFindsWhatsNext() {
        val entries = Franchises.entries(playlist, emptyMap(), WatchOrder.PLAYLIST)
        val p = Franchises.progress(entries)
        assertEquals(2, p.watched)
        assertEquals(5, p.total)
        assertEquals("flash-1-8", p.next?.id)
        assertFalse(p.resuming)
        assertEquals("2 of 5 watched", p.line)
        val around = Franchises.around(entries, "flash-1-8")!!
        assertEquals("movie", around.second?.id)
        assertEquals("arrow-3-8", around.third?.id)
        assertNull(Franchises.around(entries, "nope"))
    }

    @Test fun musicPlaylistsAreLeftOut() {
        val lists = listOf(MediaItem("v", "Arrowverse", ItemKind.PLAYLIST, mediaKind = MediaKind.VIDEO), MediaItem("m", "Gym", ItemKind.PLAYLIST, mediaKind = MediaKind.AUDIO), MediaItem("u", "Unknown", ItemKind.PLAYLIST))
        assertEquals(listOf("v", "u"), Franchises.candidates(lists).map { it.id })
    }

    @Test fun moodsFromTagsAndGenres() {
        assertEquals(PageTheme.LIGHTNING_RUN, Franchises.suggestedMood(listOf(MediaItem("1", "x", ItemKind.EPISODE, tags = listOf("speedster")))))
        assertEquals(PageTheme.HIGH_KINGDOM, Franchises.suggestedMood(listOf(MediaItem("1", "x", ItemKind.MOVIE, genres = listOf("Fantasy")))))
        assertEquals(PageTheme.ARENA_DUSK, Franchises.suggestedMood(listOf(MediaItem("1", "x", ItemKind.MOVIE, tags = listOf("dystopia")))))
    }
}
