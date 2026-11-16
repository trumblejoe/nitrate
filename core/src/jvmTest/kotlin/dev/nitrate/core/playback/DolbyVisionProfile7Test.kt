package dev.nitrate.core.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dual-layer Dolby Vision (profile 7, "DOVIWithEL"): Dolby Vision where it can be, else its HDR10 base layer. */
class DolbyVisionProfile7Test {
    private val el = listOf("DOVIWithEL", "DOVIWithELHDR10Plus")

    @Test
    fun `an HDR10 screen direct plays profile 7 as its base layer`() {
        val c = caps(hdr = setOf("HDR10"))
        assertTrue(CapabilityRules.allowedRangeTypes(c, PlaybackSettings()).containsAll(el))
        assertFalse(CapabilityRules.playsDolbyVisionProfile7(c, PlaybackSettings()))
    }

    @Test
    fun `a screen without HDR leaves it to the server to tone map`() {
        val c = caps(hdr = emptySet())
        assertTrue(CapabilityRules.allowedRangeTypes(c, PlaybackSettings()).none { it in el })
    }

    @Test
    fun `a device that decodes profile 7 plays it as Dolby Vision unless Dolby Vision is turned off`() {
        val c = caps(hdr = setOf("HDR10", "DOVI")).copy(dolbyVisionProfiles = setOf(5, 7, 8))
        assertTrue(CapabilityRules.playsDolbyVisionProfile7(c, PlaybackSettings()))
        val off = PlaybackSettings(overrides = mapOf(PlaybackSettings.hdrId("DOVI") to Override.NEVER))
        assertFalse(CapabilityRules.playsDolbyVisionProfile7(c, off))
        // Still direct played, as HDR10.
        assertTrue(CapabilityRules.allowedRangeTypes(c, off).containsAll(el))
    }

    @Test
    fun `profile 8 decoders alone don't count`() {
        val c = caps(hdr = setOf("HDR10", "DOVI")).copy(dolbyVisionProfiles = setOf(8))
        assertFalse(CapabilityRules.playsDolbyVisionProfile7(c, PlaybackSettings()))
    }
}
