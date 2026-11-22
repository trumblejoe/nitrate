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

class PlaylistPlacesTest {
    private val day = 86_400_000L
    private fun e(id: String, played: Boolean = false, series: String? = null, kind: ItemKind = ItemKind.EPISODE) =
        FranchiseEntry(MediaItem(id, id, kind, seriesId = series, user = UserState(played = played)))

    private val entries = listOf(e("a", true), e("b", true), e("c"), e("d", true), e("e"), e("f"), e("g"), e("h"), e("i"), e("j"))

    @Test fun carriesOnNearThePlace() {
        // Left at c: c itself, b (gone back) and a few after count; far ahead doesn't.
        assertEquals(2, Franchises.indexNearPlace(entries, "c", "c"))
        assertEquals(1, Franchises.indexNearPlace(entries, "c", "b"))
        assertEquals(5, Franchises.indexNearPlace(entries, "c", "f"))
        assertNull(Franchises.indexNearPlace(entries, "c", "j"))
        assertNull(Franchises.indexNearPlace(entries, "c", "a"))
        assertNull(Franchises.indexNearPlace(entries, "nope", "c"))
    }

    @Test fun watchedOnesAfterThePlaceAreSkipped() {
        val list = listOf(e("a"), e("b", true), e("c", true), e("d", true), e("e", true), e("f"), e("g"))
        assertEquals("f", Franchises.continueFrom(list, "b")?.id)
        // f is past four watched ones, still the playlist's.
        assertEquals(5, Franchises.indexNearPlace(list, "b", "f"))
    }

    @Test fun wholeShowsAsPlaces() {
        val list = listOf(e("m", kind = ItemKind.MOVIE), e("s1e1", true, "show"), e("s1e2", series = "show"), e("s1e3", series = "show"))
        assertEquals(2, Franchises.placeIndex(list, "show"))
        assertEquals("s1e2", Franchises.continueFrom(list, "show")?.id)
    }

    @Test fun placesKeepTheNewestLast() {
        var p = emptyMap<String, String>()
        p = Franchises.place(p, "x", "1")
        p = Franchises.place(p, "y", "2")
        p = Franchises.place(p, "x", "3")
        assertEquals(listOf("y" to "2", "x" to "3"), p.toList())
        val many = (1..60).fold(emptyMap<String, String>()) { m, i -> Franchises.place(m, "p$i", "i") }
        assertEquals(Franchises.MAX_PLACES, many.size)
        assertEquals("p60", many.keys.last())
    }

    @Test fun crossoversBySharedWordsAndTags() {
        val a = MediaItem("a", "Legends of Yesterday", ItemKind.EPISODE, seriesId = "flash", seriesName = "The Flash", seasonNumber = 2, indexNumber = 8, premiereDateMs = 1000 * day)
        val b = MediaItem("b", "Legends of Today", ItemKind.EPISODE, seriesId = "arrow", seriesName = "Arrow", seasonNumber = 4, indexNumber = 8, premiereDateMs = 1001 * day)
        val c = MediaItem("c", "Unrelated", ItemKind.EPISODE, seriesId = "arrow", premiereDateMs = 1001 * day)
        val x = MediaItem("x", "One", ItemKind.EPISODE, seriesId = "s1", premiereDateMs = 2000 * day, tags = listOf("Crossover"))
        val y = MediaItem("y", "Two", ItemKind.EPISODE, seriesId = "s2", premiereDateMs = 2001 * day, tags = listOf("crossover episode"))
        val found = Franchises.crossovers(listOf(a, b, c, x, y))
        assertEquals(setOf("a", "b", "x", "y"), found.keys)
        assertEquals("Crossover with Arrow S4:E8", found.getValue("a").label)
    }
}
