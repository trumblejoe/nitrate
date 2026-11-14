package dev.nitrate.core.playback

import dev.nitrate.core.model.Chapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChaptersTest {
    private val c = listOf(Chapter(0, "Opening"), Chapter(60_000, ""), Chapter(120_000, "Finale"))

    @Test fun findsWhereYouAre() {
        assertEquals(0, Chapters.current(c, 10_000))
        assertEquals(1, Chapters.current(c, 60_000))
        assertEquals(2, Chapters.current(c, 500_000))
    }

    @Test fun nextAndPreviousLikeACdPlayer() {
        assertEquals(60_000, Chapters.next(c, 10_000))
        assertNull(Chapters.next(c, 130_000))
        assertEquals(60_000, Chapters.previous(c, 70_000)) // 10 s in: back to this chapter's start
        assertEquals(0, Chapters.previous(c, 61_000)) // just started: the one before
        assertEquals("Chapter 2", Chapters.title(c, 1))
    }
}

class AirDatesTest {
    @Test fun shortDates() {
        kotlin.test.assertEquals("Fri 21 Nov", dev.nitrate.core.model.AirDates.short(1_763_683_200_000)) // 2025-11-21
        kotlin.test.assertEquals("Thu 1 Jan", dev.nitrate.core.model.AirDates.short(0))
        kotlin.test.assertEquals("Thu 29 Feb", dev.nitrate.core.model.AirDates.short(1_709_164_800_000)) // 2024-02-29
    }

    @Test fun status() {
        val ep = dev.nitrate.core.model.MediaItem("e", "E", dev.nitrate.core.model.ItemKind.EPISODE, isMissing = true, premiereDateMs = 1_763_683_200_000)
        kotlin.test.assertEquals("Airs Fri 21 Nov", dev.nitrate.core.model.AirDates.status(ep, 1_763_000_000_000))
        kotlin.test.assertEquals("Missing", dev.nitrate.core.model.AirDates.status(ep, 1_800_000_000_000))
        kotlin.test.assertEquals(null, dev.nitrate.core.model.AirDates.status(ep.copy(isMissing = false), 0))
        kotlin.test.assertEquals(false, ep.isPlayable)
    }
}

class BrowseFiltersTest {
    @Test fun decadesFromTheLibrarysYears() {
        kotlin.test.assertEquals(listOf(2020, 1990, 1980), dev.nitrate.core.pages.Browse.decadesFor(listOf(1985, 1999, 2021, 1991)))
        kotlin.test.assertEquals(dev.nitrate.core.pages.Browse.decades(), dev.nitrate.core.pages.Browse.decadesFor(emptyList()))
    }

    @Test fun ratingsAndTagsReachTheQuery() {
        val q = dev.nitrate.core.pages.BrowseOptions(officialRatings = listOf("PG-13"), tags = listOf("noir")).query("lib", null)
        kotlin.test.assertEquals(listOf("PG-13"), q.officialRatings)
        kotlin.test.assertEquals(listOf("noir"), q.tags)
        kotlin.test.assertEquals(2, dev.nitrate.core.pages.BrowseOptions(officialRatings = listOf("R"), tags = listOf("x")).filterCount)
    }
}
