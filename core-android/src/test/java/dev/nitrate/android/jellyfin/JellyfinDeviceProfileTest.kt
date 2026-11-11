package dev.nitrate.android.jellyfin

import dev.nitrate.core.playback.DeviceCapabilities
import dev.nitrate.core.playback.LearnedFailure
import dev.nitrate.core.playback.Override
import dev.nitrate.core.playback.PlaybackSettings
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JellyfinDeviceProfileTest {
    private fun videoCodecs(settings: PlaybackSettings, c: DeviceCapabilities = caps()) =
        JellyfinDeviceProfile.create(c, settings).directPlayProfiles!!
            .first { it.type == DlnaProfileType.VIDEO }.videoCodec!!.split(",")

    private fun condition(settings: PlaybackSettings, codec: String, property: ProfileConditionValue) =
        JellyfinDeviceProfile.create(caps(), settings).codecProfiles!!
            .first { it.codec == codec }.conditions.first { it.property == property }.value.orEmpty()

    @Test
    fun `reported codecs are direct played`() {
        assertEquals(setOf("h264", "hevc"), videoCodecs(PlaybackSettings()).toSet())
    }

    @Test
    fun `Never removes a reported codec and Always adds an unreported one`() {
        val s = PlaybackSettings(overrides = mapOf("hevc" to Override.NEVER, "av1" to Override.ALWAYS))
        assertEquals(setOf("h264", "av1"), videoCodecs(s).toSet())
    }

    @Test
    fun `learned failures are skipped unless forced on or disabled`() {
        val failure = LearnedFailure(1, "DECODING_FAILED", 0)
        assertFalse("hevc" in videoCodecs(PlaybackSettings(learnedFailures = mapOf("hevc" to failure))))
        assertTrue("hevc" in videoCodecs(PlaybackSettings(learnedFailures = mapOf("hevc" to failure), overrides = mapOf("hevc" to Override.ALWAYS))))
        assertTrue("hevc" in videoCodecs(PlaybackSettings(learnedFailures = mapOf("hevc" to failure), useLearnedFailures = false)))
    }

    @Test
    fun `per-codec limits cap height and bit depth`() {
        val s = PlaybackSettings(codecMaxHeight = mapOf("h264" to 1080), eightBitOnly = setOf("h264"))
        assertEquals("1080", condition(s, "h264", ProfileConditionValue.HEIGHT))
        assertEquals("8", condition(s, "h264", ProfileConditionValue.VIDEO_BIT_DEPTH))
        assertEquals("2160", condition(s, "hevc", ProfileConditionValue.HEIGHT))
    }

    @Test
    fun `disabling HDR10 leaves SDR and HLG but drops HDR10 and its Dolby Vision fallback`() {
        val ranges = condition(PlaybackSettings(overrides = mapOf("range:HDR10" to Override.NEVER)), "hevc", ProfileConditionValue.VIDEO_RANGE_TYPE).split("|")
        assertTrue("SDR" in ranges && "HLG" in ranges)
        assertFalse("HDR10" in ranges || "DOVIWithHDR10" in ranges)
    }

    @Test
    fun `Dolby Vision with an HDR10 base layer plays as HDR10 when DV is not supported`() {
        val ranges = condition(PlaybackSettings(), "hevc", ProfileConditionValue.VIDEO_RANGE_TYPE).split("|")
        assertTrue("DOVIWithHDR10" in ranges)
        assertFalse("DOVI" in ranges)
    }
}
