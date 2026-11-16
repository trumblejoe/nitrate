package dev.nitrate.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DisplayModesTest {
    private val m60 = DisplayMode(1, 3840, 2160, 60f)
    private val m5994 = DisplayMode(2, 3840, 2160, 59.94f)
    private val m24 = DisplayMode(3, 3840, 2160, 24f)
    private val m23976 = DisplayMode(4, 3840, 2160, 23.976f)
    private val m50 = DisplayMode(5, 3840, 2160, 50f)
    private val hd24 = DisplayMode(6, 1920, 1080, 24f)
    private val all = listOf(m60, m5994, m24, m23976, m50, hd24)

    @Test fun `films get their exact rate at the same resolution`() {
        assertEquals(m23976, DisplayModes.best(all, m60, 23.976f))
        assertEquals(m24, DisplayModes.best(all, m60, 24f))
        assertEquals(m50, DisplayModes.best(all, m60, 25f))
        assertEquals(m5994, DisplayModes.best(all, m60, 29.97f))
    }

    @Test fun `no switch when the current mode already fits or nothing does`() {
        assertNull(DisplayModes.best(all, m60, 30f))
        assertNull(DisplayModes.best(all, m60, 60f))
        // Only 24 at 1080p, not at the TV's 4K: stay.
        assertNull(DisplayModes.best(listOf(m60, hd24), m60, 24f))
        assertNull(DisplayModes.best(all, m60, 0f))
    }

    @Test fun `23_976 is not 24`() {
        assertNull(DisplayModes.best(listOf(m60, m24), m60, 23.976f))
    }
}
