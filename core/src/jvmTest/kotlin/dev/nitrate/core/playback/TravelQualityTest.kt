package dev.nitrate.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TravelQualityTest {
    private val defaults = PlaybackSettings()

    @Test
    fun placeComesFromMobileDataThenTheServersNetwork() {
        assertEquals(NetworkPlace.MOBILE_DATA, TravelQuality.place(inServerNetwork = true, mobileData = true))
        assertEquals(NetworkPlace.AWAY, TravelQuality.place(inServerNetwork = false, mobileData = false))
        assertEquals(NetworkPlace.HOME, TravelQuality.place(inServerNetwork = true, mobileData = false))
        // The server didn't say: no reason to hold back.
        assertEquals(NetworkPlace.HOME, TravelQuality.place(inServerNetwork = null, mobileData = false))
    }

    @Test
    fun homeKeepsTheHomeLimitAndNeverMeasures() {
        assertFalse(TravelQuality.needsMeasuring(defaults, NetworkPlace.HOME))
        val d = TravelQuality.decide(defaults, NetworkPlace.HOME, measuredBps = 5_000_000)
        assertEquals(PlaybackSettings.NO_LIMIT, d.limitBps)
        assertFalse(d.auto)
    }

    @Test
    fun awayOnAutoAsksForSeventyPercentOfTheMeasuredSpeed() {
        assertTrue(TravelQuality.needsMeasuring(defaults, NetworkPlace.AWAY))
        val d = TravelQuality.decide(defaults, NetworkPlace.AWAY, measuredBps = 18_000_000)
        assertEquals(12_600_000, d.limitBps)
        assertEquals("Away · measured 18 Mbps · limit Auto → 12 Mbps", d.describe())
    }

    @Test
    fun autoStaysBetweenItsFloorAndCeiling() {
        assertEquals(TravelQuality.MAX_AUTO, TravelQuality.autoLimit(900_000_000))
        assertEquals(TravelQuality.MIN, TravelQuality.autoLimit(1_000_000))
        assertEquals(TravelQuality.UNMEASURED, TravelQuality.autoLimit(null))
    }

    @Test
    fun aSetLimitIsUsedAsItIs() {
        val s = defaults.copy(awayMaxBitrate = 20_000_000, mobileMaxBitrate = 4_000_000)
        assertFalse(TravelQuality.needsMeasuring(s, NetworkPlace.AWAY))
        assertEquals(20_000_000, TravelQuality.decide(s, NetworkPlace.AWAY, null).limitBps)
        assertEquals("On mobile data · limit 4 Mbps", TravelQuality.decide(s, NetworkPlace.MOBILE_DATA, null).describe())
    }

    @Test
    fun steppingDownHalvesWhatsPlaying() {
        // No limit on a 30 Mbps file: half the file.
        assertEquals(15_000_000, TravelQuality.stepDown(PlaybackSettings.NO_LIMIT, 30_000_000))
        assertEquals(6_000_000, TravelQuality.stepDown(12_000_000, 40_000_000))
        assertEquals(TravelQuality.MIN, TravelQuality.stepDown(2_000_000, null))
        assertNull(TravelQuality.stepDown(TravelQuality.MIN, null))
        val lowered = QualityDecision(NetworkPlace.AWAY, 6_000_000, lowered = true)
        assertEquals("Away · limit 6 Mbps (lowered: it kept buffering)", lowered.describe())
    }

    @Test
    fun twoStallsWithinAMinuteCount() {
        val c = StallCounter()
        assertFalse(c.stall(0))
        assertFalse(c.stall(70_000)) // the first is too long ago
        assertTrue(c.stall(100_000))
        assertFalse(c.stall(110_000)) // counting afresh
    }

    @Test
    fun bitratesReadPlainly() {
        assertEquals("Auto", formatBitrate(PlaybackSettings.AUTO_BITRATE))
        assertEquals("No limit", formatBitrate(PlaybackSettings.NO_LIMIT))
        assertEquals("1.5 Mbps", formatBitrate(1_500_000))
        assertEquals("12 Mbps", formatBitrate(12_600_000))
    }
}

class BufferingWatchTest {
    @Test
    fun theStartAndSeeksDontCount() {
        val w = BufferingWatch()
        // Loading before the first picture.
        assertFalse(w.update(buffering = true, playing = false, atMs = 0))
        assertFalse(w.update(buffering = false, playing = true, atMs = 1_000))
        assertFalse(w.update(buffering = true, playing = false, atMs = 5_000)) // the first stall
        assertFalse(w.update(buffering = false, playing = true, atMs = 6_000))
        w.seeked(9_000)
        assertFalse(w.update(buffering = true, playing = false, atMs = 10_000)) // just after a seek
        assertFalse(w.update(buffering = false, playing = true, atMs = 11_000))
        assertTrue(w.update(buffering = true, playing = false, atMs = 20_000)) // the second that counts
    }

    @Test
    fun aNewStreamStartsAfresh() {
        val w = BufferingWatch()
        w.update(false, true, 0)
        assertFalse(w.update(true, false, 5_000))
        w.restart()
        assertFalse(w.update(true, false, 6_000))
        w.update(false, true, 7_000)
        assertFalse(w.update(true, false, 8_000))
    }
}
