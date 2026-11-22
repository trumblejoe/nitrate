package dev.nitrate.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncWatchTest {
    @Test fun aFewUnderrunsInAMinuteWarnButTheFirstMomentsAreLeftAlone() {
        val w = SyncWatch()
        w.settling(0)
        // Right after starting: ignored.
        repeat(5) { w.underrun(1_000L + it) }
        assertNull(w.warning)
        w.underrun(10_000); w.underrun(20_000)
        assertNull(w.warning)
        w.underrun(30_000)
        assertEquals(SyncTrouble.SOUND_GAPS, w.warning?.trouble)
    }

    @Test fun underrunsFarApartDontAddUp() {
        val w = SyncWatch()
        w.settling(0)
        w.underrun(10_000); w.underrun(80_000); w.underrun(150_000)
        assertNull(w.warning)
    }

    @Test fun framesLateAgainAndAgainMeanThePictureIsBehind() {
        val w = SyncWatch()
        w.settling(0)
        w.frameTiming(-60_000L * 100, 100, 10_000)
        w.frameTiming(-60_000L * 100, 100, 20_000)
        assertNull(w.warning)
        // A good batch in between starts the count again.
        w.frameTiming(5_000L * 100, 100, 25_000)
        w.frameTiming(-60_000L * 100, 100, 30_000)
        w.frameTiming(-50_000L * 100, 100, 40_000)
        w.frameTiming(-70_000L * 100, 100, 50_000)
        val warning = w.warning!!
        assertEquals(SyncTrouble.PICTURE_LATE, warning.trouble)
        assertTrue("about 60 ms" in warning.text, warning.text)
        assertTrue(warning.convertHelps)
    }

    @Test fun theMoreSeriousWarningStaysAndAReloadClearsIt() {
        val w = SyncWatch()
        w.settling(0)
        repeat(3) { w.frameTiming(-80_000L, 1, 10_000L + it) }
        w.soundRestarted(20_000)
        assertEquals(SyncTrouble.PICTURE_LATE, w.warning?.trouble)
        w.reset(30_000)
        assertNull(w.warning)
        w.timingJump(31_000) // still settling
        assertNull(w.warning)
        w.timingJump(40_000)
        assertEquals(SyncTrouble.TIMING_JUMP, w.warning?.trouble)
    }
}
