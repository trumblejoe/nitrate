package dev.nitrate.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeLevelTest {
    @Test fun `swipes start only away from the edges`() {
        // A flick up from the phone's bottom bar, or in from the side, never starts one.
        assertFalse(SwipeLevel.canStart(500f, 980f, 1000f, 1000f))
        assertFalse(SwipeLevel.canStart(500f, 50f, 1000f, 1000f))
        assertFalse(SwipeLevel.canStart(20f, 500f, 1000f, 1000f))
        assertTrue(SwipeLevel.canStart(200f, 500f, 1000f, 1000f))
    }

    @Test fun `only steady upright moves start a swipe`() {
        assertEquals("a quick flick", SwipeLevel.Start.NEVER, SwipeLevel.judge(0f, -200f, 1000f, 80))
        assertEquals("not far yet", SwipeLevel.Start.NOT_YET, SwipeLevel.judge(0f, -30f, 1000f, 400))
        assertEquals("too sideways", SwipeLevel.Start.NEVER, SwipeLevel.judge(70f, -100f, 1000f, 400))
        assertEquals(SwipeLevel.Start.SWIPE, SwipeLevel.judge(10f, -100f, 1000f, 400))
    }

    @Test fun `the level rests at 50 percent and still reaches both ends`() {
        assertEquals(0f, SwipeLevel.level(0f), 0.0001f)
        assertEquals(1f, SwipeLevel.level(1f), 0.0001f)
        assertEquals(0.5f, SwipeLevel.level(0.45f), 0.0001f)
        assertEquals(0.5f, SwipeLevel.level(0.55f), 0.0001f)
        for (l in listOf(0f, 0.2f, 0.5f, 0.7f, 1f)) assertEquals(l, SwipeLevel.level(SwipeLevel.raw(l)), 0.0001f)
    }

    @Test fun `a drag through the middle stops there and says so once`() {
        val drag = LevelDrag(0.3f)
        var landed = 0
        var level = 0.3f
        // 0.3 of the height: into the hold band, where it rests at 50% (tick once).
        repeat(60) { level = drag.move(0.005f); if (drag.reachedMiddle) landed++ }
        assertEquals(1, landed)
        assertEquals(0.5f, level, 0.0001f)
        // On through the band, it moves on above 50%.
        repeat(40) { level = drag.move(0.005f) }
        assertTrue(level > 0.55f)
        // From 50%, a small wobble stays at 50%.
        val still = LevelDrag(0.5f)
        assertEquals(0.5f, still.move(0.03f), 0.0001f)
        assertEquals(0.5f, still.move(-0.06f), 0.0001f)
    }
}
