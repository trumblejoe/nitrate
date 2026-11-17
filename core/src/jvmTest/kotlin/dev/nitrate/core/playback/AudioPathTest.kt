package dev.nitrate.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioPathTest {
    private fun rows(p: AudioPath) = p.rows().toMap()

    @Test fun hiResFlacStereoWarnsAboutUpmixing() {
        val p = AudioPath("flac", 2, 96_000, 24, AudioDelivery.DECODED, "PCM float", 2, 96_000, 96_000, listOf("dialogue boost"))
        val r = rows(p)
        assertEquals("FLAC · stereo · 96 kHz · 24-bit", r["Sound in file"])
        assertEquals("Decoded on this device · dialogue boost", r["Handling"])
        assertEquals("PCM float · stereo · 96 kHz", r["Sound out"])
        assertTrue(r["Note"]!!.contains("won't upmix"))
    }

    @Test fun mixerRateIsShownWhenItDiffers() {
        val p = AudioPath("flac", 2, 96_000, 24, AudioDelivery.DECODED, "PCM float", 2, 96_000, 48_000)
        val r = rows(p)
        assertEquals("48 kHz (converted to this unless the device sends audio directly)", r["Android mixer"])
        assertTrue(r["Note"]!!.contains("won't upmix"))
    }

    @Test fun stereoAt48SuggestsTheReceiversUpmixer() {
        val r = rows(AudioPath("flac", 2, 48_000, 24, AudioDelivery.DECODED, "PCM 16-bit", 2, 48_000, 48_000))
        assertTrue(r["Note"]!!.contains("Dolby Surround"))
        assertNull(r["Android mixer"])
    }

    @Test fun bitstream() {
        val r = rows(AudioPath("truehd", 8, 48_000, null, AudioDelivery.BITSTREAM, "Dolby TrueHD", 8, 192_000))
        assertEquals("Sent untouched for the TV or receiver to decode", r["Handling"])
        assertEquals("Dolby TrueHD · 7.1 · 192 kHz", r["Sound out"])
        assertNull(r["Note"])
    }

    @Test fun nothingKnownYet() = assertTrue(AudioPath().rows().isEmpty())
}
