package dev.nitrate.mobile

import dev.nitrate.mobile.ui.ArtworkPalette
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkPaletteTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xff shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `picks the vivid hue over a dark background`() {
        val pixels = IntArray(1000) { if (it % 4 == 0) rgb(60, 20, 140) else rgb(8, 8, 10) }
        val c = ArtworkPalette.accent(pixels)!!
        val r = c shr 16 and 0xff; val g = c shr 8 and 0xff; val b = c and 0xff
        assertTrue("violet expected, got $r,$g,$b", b > r && r > g)
        // Lifted so it reads on black.
        assertTrue(maxOf(r, g, b) > 220)
    }

    @Test
    fun `grey artwork has no accent`() {
        assertNull(ArtworkPalette.accent(IntArray(500) { rgb(120, 120, 122) }))
    }
}
