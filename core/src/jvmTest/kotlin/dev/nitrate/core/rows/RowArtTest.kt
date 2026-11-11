package dev.nitrate.core.rows

import kotlin.test.Test
import kotlin.test.assertEquals

class RowArtTest {
    @Test
    fun `bolt crossing follows its corners`() {
        assertEquals(0.60f, RowArt.boltX(0f), 0.001f)
        assertEquals(0.43f, RowArt.boltX(0.34f), 0.001f)
        assertEquals(0.515f, RowArt.boltX(0.17f), 0.001f)
        assertEquals(0.41f, RowArt.boltX(1f), 0.001f)
    }
}
