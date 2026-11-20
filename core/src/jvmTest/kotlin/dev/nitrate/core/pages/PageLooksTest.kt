package dev.nitrate.core.pages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PageLooksTest {
    private val base = DisplayPrefs(tvHome = TvHomeLayout.ROWS)

    @Test fun pagesFollowEveryPageUntilChanged() {
        val p = PageLooks.forPage(base, PageKeys.library("movies"))
        assertEquals(base, p)
        assertFalse(PageLooks.hasOwn(base, PageKeys.HOME))
    }

    @Test fun aPageKeepsOnlyWhatDiffers() {
        val p = PageLooks.change(base, PageKeys.library("movies")) { it.copy(stacked = true, captionSize = CaptionSize.MINIMAL) }
        assertEquals(PageLook(stacked = true, captionSize = CaptionSize.MINIMAL), p.pageLooks.getValue("library/movies"))
        // Its tabs go with it; other pages don't.
        val tab = PageLooks.forPage(p, "library/movies/tab/genres")
        assertTrue(tab.tvRowsStacked)
        assertEquals(CaptionSize.MINIMAL, tab.tvCaptionSize)
        assertFalse(PageLooks.forPage(p, PageKeys.HOME).tvRowsStacked)
        // Changing it back to every page's leaves nothing of its own.
        val back = PageLooks.change(p, PageKeys.library("movies")) { it.copy(stacked = false, captionSize = CaptionSize.NORMAL) }
        assertTrue(back.pageLooks.isEmpty())
    }

    @Test fun arrangementPerPage() {
        val p = PageLooks.change(base, PageKeys.HOME) { it.copy(arrangement = TvHomeLayout.FULL_ART) }
        assertEquals(TvHomeLayout.FULL_ART, PageLooks.forPage(p, PageKeys.HOME).tvHome)
        assertEquals(TvHomeLayout.ROWS, PageLooks.forPage(p, PageKeys.library("x")).tvHome)
        // The old "Library pages" choice still applies to libraries without their own layout.
        val lib = base.copy(tvLibraryLayout = TvHomeLayout.FULL_ART)
        assertEquals(TvHomeLayout.FULL_ART, PageLooks.forPage(lib, PageKeys.library("x")).tvHome)
    }

    @Test fun useEverywhereMakesEveryPageMatch() {
        val p = PageLooks.change(base, PageKeys.HOME) { it.copy(rowPlace = RowPlace.TOP) }
            .let { PageLooks.change(it, PageKeys.library("x")) { l -> l.copy(stacked = true) } }
        val all = PageLooks.useEverywhere(p, PageKeys.HOME)
        assertTrue(all.pageLooks.isEmpty())
        assertEquals(RowPlace.TOP, all.tvRowPlace)
        assertFalse(PageLooks.forPage(all, PageKeys.library("x")).tvRowsStacked)
        assertEquals(base, PageLooks.reset(base.copy(pageLooks = mapOf("home" to PageLook(stacked = true))), PageKeys.HOME))
    }
}
