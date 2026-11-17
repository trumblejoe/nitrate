package dev.nitrate.core.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpmixerTest {
    private val rate = 48_000

    /** Peak level per output channel for a tone, centred (same in both) or wide (opposite). */
    private fun levels(layout: SpeakerLayout, hz: Double, centred: Boolean): FloatArray {
        val up = Upmixer(rate, layout)
        val out = FloatArray(up.outChannels)
        val peak = FloatArray(up.outChannels)
        for (i in 0 until rate) {
            val s = 0.4f * sin(2 * PI * hz * i / rate).toFloat()
            up.process(s, if (centred) s else -s, out)
            if (i > rate / 2) for (c in out.indices) peak[c] = maxOf(peak[c], abs(out[c]))
        }
        return peak
    }

    @Test fun dialogueGoesToTheCentre() {
        val p = levels(SpeakerLayout.FIVE_ONE, 1_000.0, centred = true)
        assertTrue(p[2] > 0.25f, "centre carries it: ${p.toList()}")
        assertTrue(p[2] > p[0], "more than each front")
        assertTrue(p[4] < 0.02f && p[5] < 0.02f, "nothing in the surrounds")
    }

    @Test fun wideSoundGoesAround() {
        val p = levels(SpeakerLayout.FIVE_ONE, 1_000.0, centred = false)
        assertTrue(p[2] < 0.02f, "nothing in the centre: ${p.toList()}")
        assertTrue(p[4] > 0.15f && p[5] > 0.15f, "surrounds carry it")
    }

    @Test fun bassReachesTheSub() {
        val low = levels(SpeakerLayout.FIVE_ONE, 50.0, centred = true)
        val high = levels(SpeakerLayout.FIVE_ONE, 3_000.0, centred = true)
        assertTrue(low[3] > 0.1f, "LFE: ${low.toList()}")
        assertTrue(high[3] < low[3] / 4, "only bass")
    }

    @Test fun threeOneHasNoSurrounds() {
        val p = levels(SpeakerLayout.THREE_ONE, 1_000.0, centred = false)
        assertEquals(6, p.size)
        assertEquals(0f, p[4])
        assertEquals(0f, p[5])
    }

    @Test fun sevenOneUsesBothSurroundPairs() {
        val p = levels(SpeakerLayout.SEVEN_ONE, 1_000.0, centred = false)
        assertEquals(8, p.size)
        assertTrue(p.drop(4).all { it > 0.1f }, p.toList().toString())
    }

    @Test fun neverClips() {
        val up = Upmixer(rate, SpeakerLayout.SEVEN_ONE)
        val out = FloatArray(8)
        repeat(20_000) { i ->
            val s = if (i % 2 == 0) 1f else -1f
            up.process(s, s, out)
            assertTrue(out.all { abs(it) <= Upmixer.LIMIT })
        }
    }

    @Test fun whenToUpmix() {
        val denon = SoundOutput(OutputKind.HDMI, "DENON-AVR", 8)
        val tv = SoundOutput(OutputKind.HDMI, "LG TV", 2)
        val known = mapOf(denon.key to SpeakerLayout.FIVE_ONE)
        assertEquals(6, Speakers.upmixTo(true, 2, true, denon, known))
        assertNull(Speakers.upmixTo(false, 2, true, denon, known), "off")
        assertNull(Speakers.upmixTo(true, 6, true, denon, known), "already surround")
        assertNull(Speakers.upmixTo(true, 2, false, denon, known), "music stays stereo")
        assertNull(Speakers.upmixTo(true, 2, true, tv, known), "the soundbar didn't come on: TV speakers")
        assertNull(Speakers.upmixTo(true, 2, true, SoundOutput(OutputKind.HDMI, "Other AVR", 8), known), "not asked yet")
        assertNull(Speakers.upmixTo(true, 2, true, denon, mapOf(denon.key to SpeakerLayout.STEREO_SUB)), "2.1")
        assertEquals(SpeakerLayout.STEREO, Speakers.layout(tv, known))
        assertTrue(Speakers.shouldAsk(SoundOutput(OutputKind.HDMI, "Other AVR", 8), known))
        assertTrue(!Speakers.shouldAsk(tv, known) && !Speakers.shouldAsk(denon, known))
        assertTrue(!Speakers.shouldAsk(SoundOutput(OutputKind.BLUETOOTH, "Buds", null), known))
    }
}
