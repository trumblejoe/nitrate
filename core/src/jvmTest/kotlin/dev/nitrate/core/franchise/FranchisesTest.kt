package dev.nitrate.core.franchise

import dev.nitrate.core.effects.PageTheme
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.UserState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FranchisesTest {
    private val day = 86_400_000L
    private fun show(id: String, name: String, start: Long) = MediaItem(id, name, ItemKind.SERIES, premiereDateMs = start * day)
    private fun ep(series: MediaItem, s: Int, e: Int, name: String, aired: Long, played: Boolean = false) =
        MediaItem("${series.id}-$s-$e", name, ItemKind.EPISODE, seriesId = series.id, seriesName = series.name, seasonNumber = s, indexNumber = e, premiereDateMs = aired * day, user = UserState(played = played))

    private val arrow = show("arrow", "Arrow", 100)
    private val flash = show("flash", "The Flash", 500)
    private val eps = mapOf(
        "arrow" to listOf(ep(arrow, 1, 1, "Pilot", 100, true), ep(arrow, 3, 8, "The Brave and the Bold", 507, true), ep(arrow, 3, 9, "The Climb", 514)),
        "flash" to listOf(ep(flash, 1, 1, "Pilot", 500, true), ep(flash, 1, 8, "Flash vs. Arrow", 506), ep(flash, 0, 1, "A special", 600)),
    )

    @Test fun airedOrderInterleavesShows() {
        val order = Franchises.entries(listOf(flash, arrow), eps, WatchOrder.AIRED).map { it.item.id }
        assertEquals(listOf("arrow-1-1", "flash-1-1", "flash-1-8", "arrow-3-8", "arrow-3-9"), order)
        // Release order keeps each show's episodes together, shows by when they began; specials left out.
        val release = Franchises.entries(listOf(flash, arrow), eps, WatchOrder.RELEASE).map { it.item.id }
        assertEquals(listOf("arrow-1-1", "arrow-3-8", "arrow-3-9", "flash-1-1", "flash-1-8"), release)
        assertEquals(WatchOrder.AIRED, Franchises.defaultOrder(listOf(flash, arrow)))
    }

    @Test fun crossoversByStoryAndDate() {
        val a = show("a", "Supergirl", 0)
        val b = show("b", "Legends", 0)
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
        val entries = Franchises.entries(listOf(flash, arrow), eps, WatchOrder.AIRED)
        val p = Franchises.progress(entries)
        assertEquals(3, p.watched)
        assertEquals(5, p.total)
        // After the furthest watched (Arrow S3:E8), the next unwatched.
        assertEquals("arrow-3-9", p.next?.id)
        assertFalse(p.resuming)
        assertEquals("3 of 5 watched", p.line)
        val around = Franchises.around(entries, "flash-1-8")!!
        assertEquals("flash-1-1", around.second?.id)
        assertEquals("arrow-3-8", around.third?.id)
        assertNull(Franchises.around(entries, "nope"))
    }

    @Test fun moodsFromTagsAndGenres() {
        assertEquals(PageTheme.LIGHTNING_RUN, Franchises.suggestedMood(listOf(MediaItem("1", "x", ItemKind.SERIES, tags = listOf("speedster")))))
        assertEquals(PageTheme.HIGH_KINGDOM, Franchises.suggestedMood(listOf(MediaItem("1", "x", ItemKind.MOVIE, genres = listOf("Fantasy")))))
        assertEquals(PageTheme.ARENA_DUSK, Franchises.suggestedMood(listOf(MediaItem("1", "x", ItemKind.MOVIE, tags = listOf("dystopia")))))
        assertTrue(Franchises.isFranchise(listOf(flash, arrow)))
        assertFalse(Franchises.isFranchise(listOf(flash)))
    }
}
