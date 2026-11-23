package dev.nitrate.core.pages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LayoutPresetTest {
    @Test fun `a look everywhere sets every page and drops pages' own`() {
        val own = PageLooks.change(DisplayPrefs(), PageKeys.HOME) { it.copy(captionCorner = CaptionCorner.TOP_RIGHT) }
        val prefs = PageLooks.applyEverywhere(own, LayoutPreset.COMPACT)
        assertEquals(emptyMap(), prefs.pageLooks)
        assertEquals(LayoutPreset.COMPACT, LayoutPreset.matching(PageLooks.resolved(prefs, PageKeys.HOME)))
        assertEquals(LayoutPreset.COMPACT, LayoutPreset.matching(PageLooks.resolved(prefs, PageKeys.library("lib1"))))
    }

    @Test fun `a look on one page leaves the others`() {
        val everywhere = PageLooks.applyEverywhere(DisplayPrefs(), LayoutPreset.CLASSIC)
        val prefs = PageLooks.applyToPage(everywhere, PageKeys.library("lib1"), LayoutPreset.CINEMATIC)
        assertEquals(LayoutPreset.CINEMATIC, LayoutPreset.matching(PageLooks.resolved(prefs, PageKeys.library("lib1"))))
        assertEquals(LayoutPreset.CLASSIC, LayoutPreset.matching(PageLooks.resolved(prefs, PageKeys.HOME)))
    }

    @Test fun `fine-tuned is no preset`() {
        val prefs = PageLooks.applyEverywhere(DisplayPrefs(), LayoutPreset.MINIMAL).copy(tvCardSpacing = CardSpacing.ROOMY)
        assertNull(LayoutPreset.matching(PageLooks.resolved(prefs, PageKeys.HOME)))
    }
}
