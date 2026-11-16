package dev.nitrate.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StillWatchingTest {
    private var clock = 0L
    private val w = StillWatching { clock }

    @Test fun asksBeforeTheThirdEpisodeRollsOn() {
        w.started("e1", 0, wasPlayed = false)
        clock += 10 * 60_000
        w.input("e1", 600_000, wasPlayed = false)
        assertFalse(w.shouldAsk())
        w.rollingOn("e2"); w.started("e2", 0, false)
        assertFalse(w.shouldAsk())
        w.rollingOn("e3"); w.started("e3", 0, false)
        assertTrue(w.shouldAsk())
        val r = w.rewind()!!
        assertEquals("e1", r.back.itemId)
        assertEquals(570_000, r.back.positionMs)
        assertEquals(listOf("e2", "e3"), r.unwatch)
    }

    @Test fun anyPressStartsOver() {
        w.started("e1", 0, false)
        w.rollingOn("e2"); w.started("e2", 0, false)
        w.input("e2", 5_000, false)
        w.rollingOn("e3"); w.started("e3", 0, false)
        assertFalse(w.shouldAsk())
        assertEquals(listOf("e3"), w.rewind()!!.unwatch)
    }

    @Test fun choosingSomethingIsAPress() {
        w.started("e1", 0, false)
        w.rollingOn("e2"); w.started("e2", 0, false)
        w.started("m1", 0, false) // picked from a details page
        assertNull(w.rewind())
    }

    @Test fun longIdleAsksSooner() {
        w.started("e1", 0, false)
        w.rollingOn("e2"); w.started("e2", 0, false)
        assertFalse(w.shouldAsk())
        clock += StillWatching.IDLE_MS
        assertTrue(w.shouldAsk())
    }

    @Test fun episodesAlreadySeenStaySeen() {
        w.started("e1", 0, false)
        w.rollingOn("e2"); w.started("e2", 0, wasPlayed = true)
        w.rollingOn("e3"); w.started("e3", 0, false)
        assertEquals(listOf("e3"), w.rewind()!!.unwatch)
    }
}
