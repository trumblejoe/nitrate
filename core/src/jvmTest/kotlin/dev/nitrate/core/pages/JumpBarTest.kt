package dev.nitrate.core.pages

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JumpBarTest {
    private val now = 1_800_000_000_000L
    private fun film(name: String, year: Int? = null, rating: Float? = null, addedDaysAgo: Int? = null) =
        MediaItem(name, name, ItemKind.MOVIE, productionYear = year, communityRating = rating, dateAddedMs = addedDaysAgo?.let { now - it * 86_400_000L })

    @Test fun stopsFollowTheSort() {
        assertEquals("#", JumpBar.stops(BrowseOptions(sort = QuerySort.NAME, descending = false)).first().label)
        assertEquals("Z", JumpBar.stops(BrowseOptions(sort = QuerySort.NAME, descending = true)).first().label)
        assertEquals("2020s", JumpBar.stops(BrowseOptions(sort = QuerySort.RELEASE_DATE, descending = true)).first().label)
        assertEquals("1920s", JumpBar.stops(BrowseOptions(sort = QuerySort.RELEASE_DATE, descending = false)).first().label)
        assertEquals(listOf("9+", "8+", "7+", "6+", "5+", "<5"), JumpBar.stops(BrowseOptions(sort = QuerySort.RATING, descending = true)).map { it.label })
        assertEquals(listOf("This week", "This month", "This year", "Earlier"), JumpBar.stops(BrowseOptions(sort = QuerySort.DATE_ADDED, descending = true)).map { it.label })
        assertTrue(JumpBar.stops(BrowseOptions(sort = QuerySort.RUNTIME)).isEmpty())
    }

    @Test fun titlesFallUnderTheirStop() {
        assertEquals("y1990", JumpBar.stopOf(BrowseOptions(sort = QuerySort.RELEASE_DATE), film("A", year = 1994), now))
        assertEquals("r7", JumpBar.stopOf(BrowseOptions(sort = QuerySort.RATING), film("A", rating = 7.8f), now))
        assertEquals("r0", JumpBar.stopOf(BrowseOptions(sort = QuerySort.RATING), film("A", rating = 4.1f), now))
        assertEquals("a7", JumpBar.stopOf(BrowseOptions(sort = QuerySort.DATE_ADDED), film("A", addedDaysAgo = 2), now))
        assertEquals("a365", JumpBar.stopOf(BrowseOptions(sort = QuerySort.DATE_ADDED), film("A", addedDaysAgo = 200), now))
        assertEquals("a0", JumpBar.stopOf(BrowseOptions(sort = QuerySort.DATE_ADDED), film("A", addedDaysAgo = 900), now))
        assertNull(JumpBar.stopOf(BrowseOptions(sort = QuerySort.RELEASE_DATE), film("A"), now))
    }

    @Test fun theFirstTitleOfAStopIsFoundFromAnEstimate() {
        val opts = BrowseOptions(sort = QuerySort.RELEASE_DATE, descending = true)
        val items = listOf(film("a", 2023), film("b", 2021), film("c", 2015), film("d", 2012), film("e", 1999), film("f"))
        assertEquals(2, JumpBar.firstOf(items, opts, "y2010", estimate = 0, nowMs = now))
        assertEquals(2, JumpBar.firstOf(items, opts, "y2010", estimate = 4, nowMs = now))
        assertEquals(4, JumpBar.firstOf(items, opts, "y1990", estimate = 1, nowMs = now))
        // Nothing from the 1950s: lands where it would be (before the undated one).
        assertEquals(5, JumpBar.firstOf(items, opts, "y1950", estimate = 0, nowMs = now))
    }

    @Test fun serverCountsWhereItCanCount() {
        val q = ItemQuery()
        assertEquals(2020, JumpBar.before(q, BrowseOptions(sort = QuerySort.RELEASE_DATE, descending = true), "y2010")?.yearFrom)
        assertEquals(1989, JumpBar.before(q, BrowseOptions(sort = QuerySort.RELEASE_DATE, descending = false), "y1990")?.yearTo)
        assertEquals(8f, JumpBar.before(q, BrowseOptions(sort = QuerySort.RATING, descending = true), "r7")?.minRating)
        assertEquals(5f, JumpBar.before(q, BrowseOptions(sort = QuerySort.RATING, descending = true), "r0")?.minRating)
        assertNull(JumpBar.before(q, BrowseOptions(sort = QuerySort.RATING, descending = true), "r9"))
        assertNull(JumpBar.before(q, BrowseOptions(sort = QuerySort.DATE_ADDED, descending = true), "a31"))
    }

    @Test fun theKeypadCyclesItsLetters() {
        assertEquals("A", JumpBar.keypad(2, 1))
        assertEquals("C", JumpBar.keypad(2, 3))
        assertEquals("A", JumpBar.keypad(2, 4))
        assertEquals("S", JumpBar.keypad(7, 4))
        assertEquals("#", JumpBar.keypad(0, 1))
    }
}
