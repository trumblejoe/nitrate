package dev.nitrate.core.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

class DialogueBoostTest {
    private val rate = 48_000

    /** Level of a tone after the boost, centred in stereo (in both sides) or only on one side. */
    private fun level(hz: Double, centred: Boolean): Float {
        val boost = DialogueBoost(rate, 2)
        var peak = 0f
        for (i in 0 until rate / 2) {
            val s = 0.3f * sin(2 * PI * hz * i / rate).toFloat()
            val frame = if (centred) floatArrayOf(s, s) else floatArrayOf(s, -s)
            boost.process(frame)
            if (i > rate / 4) peak = maxOf(peak, abs(frame[0]))
        }
        return peak / 0.3f
    }

    @Test fun voicesComeForward() {
        val voice = level(1_500.0, centred = true)
        val bass = level(80.0, centred = true)
        val wide = level(1_500.0, centred = false)
        println("voice ×$voice, bass ×$bass, wide ×$wide")
        assertTrue(voice > 1.3f, "speech in the middle gets louder")
        assertTrue(voice > bass * 1.5f, "more than the low end")
        assertTrue(wide < 0.6f, "sound only at the sides is turned down")
    }

    @Test fun surroundRaisesTheCentre() {
        val frame = floatArrayOf(0.2f, 0.2f, 0.2f, 0.2f, 0.2f, 0.2f)
        DialogueBoost(rate, 6).process(frame)
        assertTrue(frame[2] > 0.3f && frame[0] < 0.2f && frame[4] < 0.2f)
    }

    @Test fun neverClips() {
        val boost = DialogueBoost(rate, 2)
        repeat(10_000) { i ->
            val s = if (i % 2 == 0) 1f else -1f
            val frame = floatArrayOf(s, s)
            boost.process(frame)
            assertTrue(abs(frame[0]) <= DialogueBoost.LIMIT && abs(frame[1]) <= DialogueBoost.LIMIT)
        }
    }
}
