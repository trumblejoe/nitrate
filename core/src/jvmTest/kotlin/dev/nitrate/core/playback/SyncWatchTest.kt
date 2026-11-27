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

    /** Plays from [fromMs] for [seconds] with the player's sound clock running at [rate] of real time; answers the re-syncs asked for. */
    private fun SyncWatch.play(fromMs: Long, seconds: Int, rate: Double, startPos: Long = 0L): List<Pair<Long, Long>> {
        val asked = mutableListOf<Pair<Long, Long>>()
        var t = fromMs
        while (t <= fromMs + seconds * 1000L) {
            val pos = startPos + ((t - fromMs) * rate).toLong()
            clock(pos, t, steady = true)?.let { asked += t to it }
            t += SyncWatch.CLOCK_EVERY_MS
        }
        return asked
    }

    @Test fun inStepAsksNothing() {
        val w = SyncWatch()
        w.settling(0)
        assertTrue(w.play(5_000, 3_600, 1.0).isEmpty())
        assertNull(w.warning)
    }

    @Test fun aClockCreepingAwayIsDriftAndAskedToResync() {
        val w = SyncWatch()
        w.settling(0)
        // The sound clock 0.5% slow: 120 ms behind after 24 s, so re-sync soon after (3 looks past the line).
        val asked = w.play(5_000, 60, 0.995)
        assertTrue(asked.isNotEmpty())
        val (at, offset) = asked.first()
        assertTrue(offset <= -SyncWatch.DRIFT_MS, "offset $offset")
        assertTrue(at in 25_000..40_000, "asked at $at")
        // One re-sync isn't a warning yet.
        assertNull(w.warning)
    }

    @Test fun clocksDifferingByNatureAreNotDrift() {
        val w = SyncWatch()
        w.settling(0)
        // 0.005% (a quartz crystal's difference): 0.36 s over two hours, never fast enough to count.
        assertTrue(w.play(5_000, 7_200, 0.99995).isEmpty())
    }

    @Test fun aPauseKeepsWhatWasMeasuredAndASeekStartsAfresh() {
        val w = SyncWatch()
        w.settling(0)
        // 0.5% slow for 16 s: about 80 ms behind, not yet past the line.
        assertTrue(w.play(5_000, 16, 0.995).isEmpty())
        // Paused for a minute: the player's position stands still, which isn't drift.
        repeat(30) { w.clock(15_920, 21_000L + it * 2_000L, steady = false) }
        // Playing on, still 0.5% slow: the 80 ms carried over gets past the line sooner than from nothing.
        val afterPause = w.play(81_000, 30, 0.995, startPos = 15_920)
        assertTrue(afterPause.isNotEmpty() && afterPause.first().first < 81_000 + 24_000)
        // A seek: everything measured goes.
        w.settling(200_000)
        assertTrue(w.play(205_000, 20, 0.995).isEmpty())
    }

    @Test fun repeatedDriftWarnsWithTheLastingFix() {
        val w = SyncWatch()
        w.soundPath("DTS passed through to the receiver")
        w.settling(0)
        // Drifting again after every re-sync (which the player does by seeking: settling again).
        var t = 5_000L
        repeat(2) {
            val asked = w.play(t, 100, 0.995)
            assertTrue(asked.isNotEmpty())
            t = asked.first().first + SyncWatch.FIX_EVERY_MS
            w.settling(t)
            t += SyncWatch.SETTLE_MS
        }
        val warning = w.warning!!
        assertEquals(SyncTrouble.DRIFT, warning.trouble)
        assertTrue("DTS passed through to the receiver" in warning.text, warning.text)
        assertEquals(2, w.driftFixes)
    }

    /**
     * The sound held up for a moment (the device busy) puts the clock 141 ms behind at once: not
     * drift (sound and picture wait together), so no re-sync, whose seek would cost seconds of buffering.
     */
    @Test fun aStallIsNotDrift() {
        val w = SyncWatch()
        w.settling(0)
        // In step for 20 s, then the clock falls 141 ms behind between two looks, then in step again.
        var t = 5_000L
        var pos = 0L
        val asked = mutableListOf<Long>()
        while (t < 120_000) {
            if (t == 25_000L) pos -= 141
            w.clock(pos, t, steady = true)?.let { asked += it }
            t += SyncWatch.CLOCK_EVERY_MS
            pos += SyncWatch.CLOCK_EVERY_MS
        }
        assertTrue(asked.isEmpty(), "asked to re-sync: $asked")
        assertEquals(1, w.stalls)
        assertEquals(-141L, w.lastStallMs)
        // Real drift after a stall is still found (what was measured before it is kept).
        val later = w.play(t, 60, 0.995, startPos = pos)
        assertTrue(later.isNotEmpty())
    }

    /** Drift with the sound decoded here: the advice is converting just the sound, not decoding it here (it already is). */
    @Test fun driftWithSoundDecodedHereAdvisesConvertingTheSound() {
        val w = SyncWatch()
        w.settling(0)
        var t = 5_000L
        repeat(2) {
            val asked = w.play(t, 100, 0.995)
            assertTrue(asked.isNotEmpty())
            t = asked.first().first + SyncWatch.FIX_EVERY_MS
            w.settling(t)
            t += SyncWatch.SETTLE_MS
        }
        val warning = w.warning!!
        assertTrue("convert just the sound" in warning.text, warning.text)
        assertTrue("decode the sound instead" !in warning.text, warning.text)
        assertTrue(warning.convertHelps)
    }

    @Test fun theSoundRunningDryIsNotDrift() {
        val w = SyncWatch()
        w.settling(0)
        // Behind by 100 ms, then the sound runs dry (the picture held for it): measuring starts again.
        w.play(5_000, 20, 0.995)
        w.underrun(26_000)
        assertTrue(w.play(27_000, 20, 0.995, startPos = 21_900).isEmpty())
    }
}
