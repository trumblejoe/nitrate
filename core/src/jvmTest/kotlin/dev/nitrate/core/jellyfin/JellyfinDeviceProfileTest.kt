package dev.nitrate.core.jellyfin

import dev.nitrate.core.playback.DeviceCapabilities
import dev.nitrate.core.playback.caps
import dev.nitrate.core.playback.LearnedFailure
import dev.nitrate.core.playback.Override
import dev.nitrate.core.playback.PlaybackSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JellyfinDeviceProfileTest {
    private fun videoCodecs(settings: PlaybackSettings, c: DeviceCapabilities = caps()) =
        JellyfinDeviceProfile.create(c, settings).directPlayProfiles
            .first { it.type == "Video" }.videoCodec!!.split(",")

    private fun condition(settings: PlaybackSettings, codec: String, property: String) =
        JellyfinDeviceProfile.create(caps(), settings).codecProfiles
            .first { it.codec == codec }.conditions.first { it.property == property }.value

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
        assertEquals("1080", condition(s, "h264", "Height"))
        assertEquals("8", condition(s, "h264", "VideoBitDepth"))
        assertEquals("2160", condition(s, "hevc", "Height"))
    }

    @Test
    fun `disabling HDR10 leaves SDR and HLG but drops HDR10 and its Dolby Vision fallback`() {
        val ranges = condition(PlaybackSettings(overrides = mapOf("range:HDR10" to Override.NEVER)), "hevc", "VideoRangeType").split("|")
        assertTrue("SDR" in ranges && "HLG" in ranges)
        assertFalse("HDR10" in ranges || "DOVIWithHDR10" in ranges)
    }

    @Test
    fun `Dolby Vision with an HDR10 base layer plays as HDR10 when DV is not supported`() {
        val ranges = condition(PlaybackSettings(), "hevc", "VideoRangeType").split("|")
        assertTrue("DOVIWithHDR10" in ranges)
        assertFalse("DOVI" in ranges)
    }

    @Test
    fun `styled subtitles shown exactly are drawn by the server`() {
        fun methods(styled: dev.nitrate.core.playback.StyledSubtitles, format: String) =
            JellyfinDeviceProfile.create(caps(), PlaybackSettings(subtitleStyle = dev.nitrate.core.playback.SubtitleStyle(styled = styled)))
                .subtitleProfiles.filter { it.format == format }.map { it.method }.toSet()
        assertEquals(setOf("External", "Embed"), methods(dev.nitrate.core.playback.StyledSubtitles.FILE, "ass"))
        assertEquals(setOf("Encode"), methods(dev.nitrate.core.playback.StyledSubtitles.EXACT, "ass"))
        assertEquals(setOf("Encode"), methods(dev.nitrate.core.playback.StyledSubtitles.EXACT, "ssa"))
        // Plain subtitles are untouched.
        assertEquals(setOf("External", "Embed"), methods(dev.nitrate.core.playback.StyledSubtitles.EXACT, "srt"))
    }

    @Test
    fun `Apple's player gets MP4 and MOV, fragmented MP4 HLS and subtitles in HLS or burned in`() {
        val p = JellyfinDeviceProfile.create(caps().copy(engine = dev.nitrate.core.playback.PlayerEngine.APPLE), PlaybackSettings())
        val video = p.directPlayProfiles.first { it.type == "Video" }
        assertEquals("mp4,m4v,mov", video.container)
        assertFalse(p.directPlayProfiles.any { "mkv" in it.container })
        assertEquals("mp4", p.transcodingProfiles.first { it.type == "Video" }.container)
        assertEquals("hls", p.transcodingProfiles.first { it.type == "Video" }.protocol)
        assertEquals(setOf("Hls", "Encode"), p.subtitleProfiles.filter { it.format == "srt" }.map { it.method }.toSet())
        assertEquals(listOf("Encode"), p.subtitleProfiles.filter { it.format == "ass" }.map { it.method })
        assertEquals(listOf("Encode"), p.subtitleProfiles.filter { it.format == "pgssub" }.map { it.method })
        // Media3 keeps MKV and MPEG-TS.
        assertTrue(JellyfinDeviceProfile.create(caps(), PlaybackSettings()).directPlayProfiles.any { "mkv" in it.container })
    }

    @Test
    fun `mpv takes the file as it is, styled and image subtitles included`() {
        val p = JellyfinDeviceProfile.create(caps().copy(engine = dev.nitrate.core.playback.PlayerEngine.MPV), PlaybackSettings())
        assertTrue("avi" in p.directPlayProfiles.first { it.type == "Video" }.container)
        assertEquals(setOf("External", "Embed"), p.subtitleProfiles.filter { it.format == "ass" }.map { it.method }.toSet())
        assertTrue(p.subtitleProfiles.any { it.format == "pgssub" && it.method == "Embed" })
    }
}
