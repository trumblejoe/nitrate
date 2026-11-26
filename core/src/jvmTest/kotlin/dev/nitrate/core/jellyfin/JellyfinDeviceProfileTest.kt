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
        // One title failing converts only that title; the format goes only after three different titles.
        assertTrue("hevc" in videoCodecs(PlaybackSettings(learnedFailures = mapOf("hevc" to LearnedFailure(1, "DECODING_FAILED", 0, setOf("a"))))))
        assertTrue("hevc" in videoCodecs(PlaybackSettings(learnedFailures = mapOf("hevc" to LearnedFailure(4, "DECODING_FAILED", 0, setOf("a", "b"))))))
        val failure = LearnedFailure(3, "DECODING_FAILED", 0, setOf("a", "b", "c"))
        assertFalse("hevc" in videoCodecs(PlaybackSettings(learnedFailures = mapOf("hevc" to failure))))
        // Older records kept only a count.
        assertTrue("hevc" in videoCodecs(PlaybackSettings(learnedFailures = mapOf("hevc" to LearnedFailure(1, "DECODING_FAILED", 0)))))
        assertFalse("hevc" in videoCodecs(PlaybackSettings(learnedFailures = mapOf("hevc" to LearnedFailure(3, "DECODING_FAILED", 0)))))
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

    @Test
    fun `profiles no decoder takes are converted up front, or played in software where it can`() {
        val hevc = dev.nitrate.core.playback.video("hevc").copy(missingProfiles = setOf("rext", "scc"))
        val h264Hw = dev.nitrate.core.playback.video("h264").copy(missingProfiles = setOf("high 4:2:2", "high 4:4:4 predictive"))
        fun profiles(h264: dev.nitrate.core.playback.VideoCodecCaps, settings: PlaybackSettings = PlaybackSettings()) =
            JellyfinDeviceProfile.create(caps(video = listOf(h264, hevc)), settings).codecProfiles

        // HEVC 4:2:2/4:4:4: no decoder here, straight to the server.
        val hevcMain = profiles(h264Hw).first { it.codec == "hevc" && it.applyConditions.isEmpty() }
        assertEquals(listOf("rext", "scc"), hevcMain.conditions.filter { it.property == "VideoProfile" }.map { it.value })

        // H.264 4:2:2 with no software decoder: to the server too.
        val noSoftware = profiles(h264Hw).first { it.codec == "h264" && it.applyConditions.isEmpty() }
        assertEquals(listOf("high 4:2:2", "high 4:4:4 predictive"), noSoftware.conditions.filter { it.property == "VideoProfile" }.map { it.value })

        // With Nitrate's software decoder: played here up to 1080p progressive instead.
        val withSoftware = profiles(h264Hw.copy(softwareProfiles = true)).filter { it.codec == "h264" }
        assertTrue(withSoftware.first { it.applyConditions.isEmpty() }.conditions.none { it.property == "VideoProfile" })
        val soft = withSoftware.first { it.applyConditions.any { c -> c.property == "VideoProfile" } }
        assertEquals("high 4:2:2|high 4:4:4 predictive", soft.applyConditions.single().value)
        assertEquals("1080", soft.conditions.first { it.property == "Height" }.value)
        assertEquals("-ts", soft.container)

        // "Always" for the codec: the user says it plays everything.
        val always = profiles(h264Hw, PlaybackSettings(overrides = mapOf("hevc" to Override.ALWAYS))).first { it.codec == "hevc" && it.applyConditions.isEmpty() }
        assertTrue(always.conditions.none { it.property == "VideoProfile" })
    }

    @Test
    fun `a profile is missing only when none of the decoders takes it`() {
        val a = dev.nitrate.core.playback.video("h264").copy(missingProfiles = setOf("high 4:2:2", "high 4:4:4 predictive"))
        val b = dev.nitrate.core.playback.video("h264").copy(decoderName = "c2.other", missingProfiles = setOf("high 4:4:4 predictive"))
        assertEquals(setOf("high 4:4:4 predictive"), dev.nitrate.core.playback.VideoCodecCaps.combine(listOf(a, b))!!.missingProfiles)
    }

    @Test
    fun `no bitrate cap by default`() {
        assertEquals(dev.nitrate.core.playback.PlaybackSettings.NO_LIMIT, JellyfinDeviceProfile.create(caps(), PlaybackSettings()).maxStreamingBitrate)
    }

    @Test
    fun `AVI, MPEG program streams and FLV play as they are in Media3`() {
        val containers = JellyfinDeviceProfile.create(caps(), PlaybackSettings()).directPlayProfiles.first { it.type == "Video" }.container.split(",")
        assertTrue(listOf("avi", "mpg", "mpeg", "vob", "flv").all { it in containers })
    }
}
