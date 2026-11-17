package dev.nitrate.core.playback

import dev.nitrate.core.model.Chapter
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.MediaSegment
import dev.nitrate.core.model.SegmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextInCollectionTest {
    private fun film(id: String, year: Int) = MediaItem(id, "Film $id", ItemKind.MOVIE, productionYear = year)

    private val trilogy = listOf(film("c", 2005), film("a", 1999), film("b", 2002))
    private val bigList = (1..50).map { film("x$it", 1950 + it) } + film("a", 1999)

    @Test fun `the film after this one, in release order, from its most specific collection`() {
        assertEquals("b", NextInCollection.next(film("a", 1999), listOf(bigList, trilogy))?.id)
        assertEquals("c", NextInCollection.next(film("b", 2002), listOf(trilogy))?.id)
        // The last one, or a film in no saga: nothing.
        assertNull(NextInCollection.next(film("c", 2005), listOf(trilogy)))
        assertNull(NextInCollection.next(film("a", 1999), listOf(bigList)))
        // A collection with shows in it isn't a film series.
        assertNull(NextInCollection.next(film("a", 1999), listOf(trilogy + MediaItem("s", "Show", ItemKind.SERIES))))
    }

    @Test fun `the whole saga in release order, for the player's strip`() {
        assertEquals(listOf("a", "b", "c"), NextInCollection.saga(film("b", 2002), listOf(bigList, trilogy)).map { it.id })
        assertEquals(emptyList<String>(), NextInCollection.saga(film("a", 1999), listOf(bigList)).map { it.id })
    }

    @Test fun `offered at the credits, never far from the end`() {
        val twoHours = 7_200_000L
        assertEquals(6_900_000L, NextInCollection.offerAtMs(twoHours, listOf(MediaSegment(SegmentKind.OUTRO, 6_900_000, twoHours)), emptyList()))
        assertEquals(6_700_000L, NextInCollection.offerAtMs(twoHours, emptyList(), listOf(Chapter(0, "Opening", null), Chapter(6_700_000, "End Credits", null))))
        assertEquals(twoHours - NextInCollection.CREDITS_GUESS_MS, NextInCollection.offerAtMs(twoHours, emptyList(), emptyList()))
    }
}
