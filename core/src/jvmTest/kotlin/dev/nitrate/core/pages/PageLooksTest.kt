package dev.nitrate.core.pages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PageLooksTest {
    private val base = DisplayPrefs(tvArrangement = PageArrangement.ROWS)

    @Test fun pagesFollowEveryPageUntilChanged() {
        val p = PageLooks.forPage(base, PageKeys.library("movies"))
        assertEquals(base, p)
        assertFalse(PageLooks.hasOwn(base, PageKeys.HOME))
    }

    @Test fun aPageKeepsOnlyWhatDiffers() {
        val p = PageLooks.change(base, PageKeys.library("movies")) { it.copy(arrangement = PageArrangement.STACKED, captionSize = CaptionSize.MINIMAL) }
        assertEquals(PageLook(arrangement = PageArrangement.STACKED, captionSize = CaptionSize.MINIMAL), p.pageLooks.getValue("library/movies"))
        // Its tabs go with it; other pages don't.
        val tab = PageLooks.forPage(p, "library/movies/tab/genres")
        assertEquals(PageArrangement.STACKED, tab.tvArrangement)
        assertEquals(CaptionSize.MINIMAL, tab.tvCaptionSize)
        assertEquals(PageArrangement.ROWS, PageLooks.forPage(p, PageKeys.HOME).tvArrangement)
        // Changing it back to every page's leaves nothing of its own.
        val back = PageLooks.change(p, PageKeys.library("movies")) { it.copy(arrangement = PageArrangement.ROWS, captionSize = CaptionSize.NORMAL) }
        assertTrue(back.pageLooks.isEmpty())
    }

    @Test fun arrangementPerPage() {
        val p = PageLooks.change(base, PageKeys.HOME) { it.copy(arrangement = PageArrangement.FULL_ART) }
        assertEquals(PageArrangement.FULL_ART, PageLooks.forPage(p, PageKeys.HOME).tvArrangement)
        assertEquals(PageArrangement.ROWS, PageLooks.forPage(p, PageKeys.library("x")).tvArrangement)
        // The "Library pages" choice applies to libraries without their own layout.
        val lib = base.copy(tvLibraryArrangement = PageArrangement.FULL_ART)
        assertEquals(PageArrangement.FULL_ART, PageLooks.forPage(lib, PageKeys.library("x")).tvArrangement)
    }

    @Test fun useEverywhereMakesEveryPageMatch() {
        val p = PageLooks.change(base, PageKeys.HOME) { it.copy(rowPlace = RowPlace.TOP) }
            .let { PageLooks.change(it, PageKeys.library("x")) { l -> l.copy(arrangement = PageArrangement.STACKED) } }
        val all = PageLooks.useEverywhere(p, PageKeys.HOME)
        assertTrue(all.pageLooks.isEmpty())
        assertEquals(RowPlace.TOP, all.tvRowPlace)
        assertEquals(PageArrangement.ROWS, PageLooks.forPage(all, PageKeys.library("x")).tvArrangement)
        assertEquals(base, PageLooks.reset(base.copy(pageLooks = mapOf("home" to PageLook(arrangement = PageArrangement.STACKED))), PageKeys.HOME))
    }

    @Test fun oldSavedLayoutsReadOnStackedFallingBackToRows() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString(
            DisplayPrefs.serializer(),
            """{"tvHome":"FULL_ART","tvRowsStacked":true,"tvLibraryLayout":"FULL_ART","pageLooks":{"home":{"stacked":true,"captionSize":"MINIMAL"},"library/x":{"arrangement":"FULL_ART"}}}""",
        )
        // Full art saved under the old names still reads.
        assertEquals(PageArrangement.FULL_ART, old.tvArrangement)
        assertEquals(PageArrangement.FULL_ART, old.tvLibraryArrangement)
        // Home's own "stacked" is gone: it falls back to Rows one at a time.
        val rows = json.decodeFromString(DisplayPrefs.serializer(), """{"tvHome":"ROWS","tvRowsStacked":true,"pageLooks":{"home":{"stacked":true,"captionSize":"MINIMAL"}}}""")
        assertEquals(PageArrangement.ROWS, PageLooks.forPage(rows, PageKeys.HOME).tvArrangement)
        assertEquals(CaptionSize.MINIMAL, PageLooks.forPage(rows, PageKeys.HOME).tvCaptionSize)
        assertEquals("ROWS", json.encodeToJsonElement(DisplayPrefs.serializer(), rows).let { (it as kotlinx.serialization.json.JsonObject)["tvHome"].toString().trim('"') })
        // A page's own full art still reads.
        assertEquals(PageArrangement.FULL_ART, PageLooks.forPage(old, PageKeys.library("x")).tvArrangement)
    }

    @Test fun arrangementCyclesThroughAllThree() {
        assertEquals(PageArrangement.STACKED, PageArrangement.ROWS.next())
        assertEquals(PageArrangement.FULL_ART, PageArrangement.STACKED.next())
        assertEquals(PageArrangement.ROWS, PageArrangement.FULL_ART.next())
    }

    @Test fun aPageCanHaveItsOwnTabStyle() {
        val prefs = PageLooks.change(DisplayPrefs(), "library/films") { it.copy(tabStyle = TabStyle.NEON) }
        assertEquals(TabStyle.NEON, PageLooks.forPage(prefs, "library/films/genres").tvTabStyle)
        assertEquals(TabStyle.CHIPS, PageLooks.forPage(prefs, PageKeys.HOME).tvTabStyle)
        val everywhere = PageLooks.useEverywhere(prefs, "library/films")
        assertEquals(TabStyle.NEON, everywhere.tvTabStyle)
        assertTrue(everywhere.pageLooks.isEmpty())
    }

    @Test fun aPageCanHaveItsOwnFocusStyleAndUpDown() {
        val prefs = PageLooks.change(DisplayPrefs(), PageKeys.HOME) { it.copy(focusStyle = FocusStyle.GLOW, upDownAlong = true) }
        assertEquals(FocusStyle.GLOW, PageLooks.forPage(prefs, PageKeys.HOME).tvFocusStyle)
        assertTrue(PageLooks.forPage(prefs, PageKeys.HOME).tvUpDownAlong)
        assertEquals(FocusStyle.GROW, PageLooks.forPage(prefs, "library/films").tvFocusStyle)
        // Set back to every page's: nothing of its own is kept.
        val back = PageLooks.change(prefs, PageKeys.HOME) { it.copy(focusStyle = FocusStyle.GROW, upDownAlong = false) }
        assertTrue(back.pageLooks.isEmpty())
    }

    @Test fun aPageCanHaveItsOwnCardShapeAndSpacing() {
        val prefs = PageLooks.change(DisplayPrefs(), "library/films") { it.copy(cardCorners = CardCorners.SQUARE, cardSpacing = CardSpacing.TIGHT) }
        assertEquals(CardCorners.SQUARE, PageLooks.forPage(prefs, "library/films/genres").tvCardCorners)
        assertEquals(CardSpacing.TIGHT, PageLooks.forPage(prefs, "library/films").tvCardSpacing)
        assertEquals(CardSpacing.NORMAL, PageLooks.forPage(prefs, PageKeys.HOME).tvCardSpacing)
        assertEquals(CardCorners.SQUARE, PageLooks.useEverywhere(prefs, "library/films").tvCardCorners)
    }
}
