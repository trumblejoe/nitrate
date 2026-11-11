package dev.nitrate.core.rows

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InkPaletteTest {
    private fun image(w: Int, h: Int, colour: (x: Int, y: Int) -> Long) = IntArray(w * h) { i -> colour(i % w, i / w).toInt() }
    private fun distance(a: Double, b: Double) = abs(((a - b + 540) % 360) - 180)

    @Test
    fun `ink keeps away from the artwork's colours`() {
        // An orange picture: the ink should be far from orange (around blue/teal).
        val orange = image(40, 30) { _, _ -> 0xFFD2691EL }
        val ink = InkPalette.smart(orange, 40)
        assertTrue(distance(InkPalette.hue(ink), InkPalette.hue(0xFFD2691EL)) > 90, "ink hue ${InkPalette.hue(ink)}")
        // Two colours (blue sky, red ground): the ink avoids both.
        val split = image(40, 30) { _, y -> if (y < 15) 0xFF2E5FD0L else 0xFFC0302AL }
        val ink2 = InkPalette.hue(InkPalette.smart(split, 40))
        assertTrue(distance(ink2, 220.0) > 60 && distance(ink2, 2.0) > 60, "ink hue $ink2")
    }

    @Test
    fun `grey art gets gold, and a bright marked side gets deep ink`() {
        assertEquals(InkPalette.GOLD, InkPalette.smart(image(20, 20) { _, _ -> 0xFF555555L }, 20))
        val brightLeft = image(40, 20) { x, _ -> if (x < 20) 0xFFF0F0E8L else 0xFF2E5FD0L }
        val a = InkPalette.analyse(brightLeft, 40)
        assertTrue(a.markedLuminance > 0.62)
        val ink = InkPalette.smart(a)
        val v = maxOf(ink shr 16 and 0xff, ink shr 8 and 0xff, ink and 0xff) / 255.0
        assertTrue(v < 0.5, "value $v")
    }
}
