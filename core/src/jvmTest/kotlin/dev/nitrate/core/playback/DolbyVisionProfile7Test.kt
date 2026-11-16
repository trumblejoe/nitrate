package dev.nitrate.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dual-layer Dolby Vision (profile 7, "DOVIWithEL"): as itself, converted to 8.1, or its HDR10 base layer. */
class DolbyVisionProfile7Test {
    private val el = listOf("DOVIWithEL", "DOVIWithELHDR10Plus")
    private val dolbyVisionOff = PlaybackSettings(overrides = mapOf(PlaybackSettings.hdrId("DOVI") to Override.NEVER))

    private fun dv(vararg profiles: Int, converter: Boolean = true) =
        caps(hdr = setOf("HDR10", "DOVI")).copy(dolbyVisionProfiles = profiles.toSet(), dolbyVisionConverter = converter)

    @Test
    fun `an HDR10 screen direct plays profile 7 as its base layer`() {
        val c = caps(hdr = setOf("HDR10"))
        assertTrue(CapabilityRules.allowedRangeTypes(c, PlaybackSettings()).containsAll(el))
        assertEquals(Profile7Playback.BASE_LAYER, CapabilityRules.profile7(c, PlaybackSettings()))
    }

    @Test
    fun `a screen without HDR leaves it to the server to tone map`() {
        assertTrue(CapabilityRules.allowedRangeTypes(caps(hdr = emptySet()), PlaybackSettings()).none { it in el })
    }

    @Test
    fun `a profile 7 decoder plays it as it is`() {
        assertEquals(Profile7Playback.DOLBY_VISION, CapabilityRules.profile7(dv(5, 7, 8), PlaybackSettings()))
    }

    @Test
    fun `a profile 8 decoder gets it converted to 8_1, unless the setting or the build says no`() {
        assertEquals(Profile7Playback.AS_PROFILE_8, CapabilityRules.profile7(dv(5, 8), PlaybackSettings()))
        assertEquals(Profile7Playback.BASE_LAYER, CapabilityRules.profile7(dv(5, 8), PlaybackSettings(convertProfile7 = false)))
        assertEquals(Profile7Playback.BASE_LAYER, CapabilityRules.profile7(dv(5, 8, converter = false), PlaybackSettings()))
        // Profile 5 alone can't take an HDR10 base layer's metadata.
        assertEquals(Profile7Playback.BASE_LAYER, CapabilityRules.profile7(dv(5), PlaybackSettings()))
    }

    @Test
    fun `Dolby Vision turned off means the base layer, still direct played as HDR10`() {
        assertEquals(Profile7Playback.BASE_LAYER, CapabilityRules.profile7(dv(7, 8), dolbyVisionOff))
        assertTrue(CapabilityRules.allowedRangeTypes(dv(7, 8), dolbyVisionOff).containsAll(el))
    }
}
