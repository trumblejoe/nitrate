package dev.nitrate.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThreeDTest {
    @Test
    fun `Jellyfin's 3D formats are read`() {
        assertEquals(Video3D.HALF_SIDE_BY_SIDE, Video3D.fromJellyfin("HalfSideBySide"))
        assertEquals(Video3D.FULL_TOP_AND_BOTTOM, Video3D.fromJellyfin("FullTopAndBottom"))
        assertEquals(Video3D.MVC, Video3D.fromJellyfin("MVC"))
        assertNull(Video3D.fromJellyfin(null))
    }

    @Test
    fun `2D shows the left or top view at its proper shape`() {
        // Half side by side: the left half, stretched back to full width.
        assertEquals(ThreeDView(-1f, 0f, -1f, 1f, 2f, 1f), ThreeD.view(Video3D.HALF_SIDE_BY_SIDE, ThreeDMode.TWO_D))
        // Full side by side: each half is already the right shape.
        assertEquals(ThreeDView(-1f, 0f, -1f, 1f, 1f, 1f), ThreeD.view(Video3D.FULL_SIDE_BY_SIDE, ThreeDMode.TWO_D))
        // Top and bottom: the top half (y is up), stretched back to full height when halved.
        assertEquals(ThreeDView(-1f, 1f, 0f, 1f, 1f, 2f), ThreeD.view(Video3D.HALF_TOP_AND_BOTTOM, ThreeDMode.TWO_D))
    }

    @Test
    fun `nothing changes for 3D screens, MVC or 2D films`() {
        assertNull(ThreeD.view(Video3D.HALF_SIDE_BY_SIDE, ThreeDMode.AS_FILE))
        assertNull(ThreeD.view(Video3D.MVC, ThreeDMode.TWO_D))
        assertNull(ThreeD.view(null, ThreeDMode.TWO_D))
    }
}
