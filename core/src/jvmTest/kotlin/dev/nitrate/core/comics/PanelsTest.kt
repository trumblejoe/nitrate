package dev.nitrate.core.comics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PanelsTest {
    /** A white page [w]×[h] with dark boxes drawn at the given pixel rectangles (x0, y0, x1, y1). */
    private fun page(w: Int, h: Int, vararg boxes: IntArray) = IntArray(w * h) { 250 }.also { p ->
        boxes.forEach { (x0, y0, x1, y1) -> for (y in y0 until y1) for (x in x0 until x1) p[y * w + x] = if ((x + y) % 7 == 0) 200 else 40 }
    }

    @Test fun tiersThenPanelsInReadingOrder() {
        // Two tiers: the top one with two panels side by side, the bottom one a single wide panel.
        val luma = page(100, 150, intArrayOf(5, 5, 47, 70), intArrayOf(53, 5, 95, 70), intArrayOf(5, 78, 95, 145))
        val panels = Panels.find(luma, 100, 150)
        assertEquals(3, panels.size)
        assertTrue(panels[0].x < panels[1].x, "left panel first")
        assertTrue(panels[2].y > panels[0].y, "bottom tier last")
        assertEquals(0.05f, panels[0].x, 0.02f)
        // Manga: right panel first.
        val rtl = Panels.find(luma, 100, 150, rightToLeft = true)
        assertTrue(rtl[0].x > rtl[1].x)
    }

    @Test fun aSplashPageIsWhole() {
        assertEquals(listOf(PanelRect.WHOLE), Panels.find(page(100, 150, intArrayOf(3, 3, 97, 147)), 100, 150))
        assertEquals(listOf(PanelRect.WHOLE), Panels.find(IntArray(10), 2, 5))
    }

    @Test fun blackGuttersWorkToo() {
        val luma = IntArray(100 * 100) { 10 }
        for (y in 5 until 45) for (x in 5 until 95) luma[y * 100 + x] = 220
        for (y in 55 until 95) for (x in 5 until 95) luma[y * 100 + x] = 220
        assertEquals(2, Panels.find(luma, 100, 100).size)
    }

    @Test fun fittingAPanelCentresIt() {
        // A square page in a square box: the top-left quarter panel doubles (less the margin) into the middle.
        val (scale, tx, ty) = Panels.fit(PanelRect(0f, 0f, 0.5f, 0.5f), 1f, 100f, 100f, margin = 0f)
        assertEquals(2f, scale, 0.001f)
        assertEquals(0f, tx, 0.001f)
        assertEquals(0f, ty, 0.001f)
    }
}
