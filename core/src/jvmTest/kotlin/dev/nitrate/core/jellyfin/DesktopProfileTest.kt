package dev.nitrate.core.jellyfin

import dev.nitrate.core.playback.CapabilityRules
import dev.nitrate.core.playback.DesktopPlayback
import dev.nitrate.core.playback.PlaybackSettings
import dev.nitrate.core.playback.Profile7Playback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The desktop app's profile (mpv): the server sends files as they are and converts nothing a PC shows. */
class DesktopProfileTest {
    private val caps = DesktopPlayback.capabilities("Test PC (Windows 11, mpv)", 2560, 1440)
    private val profile = JellyfinDeviceProfile.create(caps, PlaybackSettings())

    @Test
    fun `every container and codec plays as it is`() {
        val video = profile.directPlayProfiles.filter { it.type == "Video" }
        // One profile: mpv reads VC-1 and the rest from any container.
        val main = video.single()
        val containers = main.container.split(",")
        for (c in listOf("mkv", "mp4", "avi", "ts", "m2ts", "wmv", "mpg", "vob", "webm")) assertTrue("$c plays as it is", c in containers)
        val codecs = main.videoCodec!!.split(",")
        for (c in listOf("h264", "hevc", "av1", "vp9", "vc1", "mpeg2video", "mpeg4")) assertTrue("$c plays as it is", c in codecs)
        val audio = main.audioCodec!!.split(",")
        for (c in listOf("aac", "ac3", "eac3", "dts", "truehd", "flac", "opus", "mp3", "alac")) assertTrue("$c plays as it is", c in audio)
    }

    @Test
    fun `no 1080p or progressive-only limits, Hi10P and 4K HDR included`() {
        for (limit in CapabilityRules.videoLimits(caps, PlaybackSettings())) {
            assertEquals(limit.codec, DesktopPlayback.MAX_HEIGHT, limit.maxHeight)
            assertFalse(limit.codec, limit.progressiveOnly)
        }
        val h264 = profile.codecProfiles.single { it.codec == "h264" }.conditions.associate { it.property to it.value }
        assertEquals("10", h264["VideoBitDepth"])
        assertFalse(profile.codecProfiles.flatMap { it.conditions }.any { it.property == "IsInterlaced" })
        // HDR is shown or tone-mapped by mpv: the server never tone-maps for the desktop.
        val ranges = h264.getValue("VideoRangeType").split("|")
        for (r in listOf("SDR", "HDR10", "HDR10Plus", "HLG", "DOVI", "DOVIWithHDR10", "DOVIWithEL")) assertTrue(r, r in ranges)
        assertEquals(Profile7Playback.BASE_LAYER, CapabilityRules.profile7(caps, PlaybackSettings()))
    }

    @Test
    fun `styled and image subtitles arrive as they are`() {
        val subs = profile.subtitleProfiles.groupBy({ it.format }, { it.method })
        assertTrue("Embed" in subs.getValue("ass"))
        assertTrue("External" in subs.getValue("ass"))
        assertTrue("Embed" in subs.getValue("pgssub"))
        // Even with "styled subtitles exactly" chosen, mpv draws them itself (libass).
        val exact = PlaybackSettings().let { it.copy(subtitleStyle = it.subtitleStyle.copy(styled = dev.nitrate.core.playback.StyledSubtitles.EXACT)) }
        assertFalse(JellyfinDeviceProfile.create(caps, exact).subtitleProfiles.any { it.format == "ass" && it.method == "Encode" })
    }

    @Test
    fun `bitstreaming is off unless chosen, and becomes mpv's audio-spdif`() {
        assertTrue(caps.audio.values.none { it.passthrough })
        val receiver = DesktopPlayback.capabilities("PC", 1920, 1080, bitstream = setOf("ac3", "dts", "truehd"))
        assertTrue(receiver.audio.getValue("dts").passthrough)
        assertFalse(receiver.audio.getValue("eac3").passthrough)
        assertEquals("ac3,dts-hd,truehd", DesktopPlayback.spdifOption(setOf("truehd", "dts", "ac3")))
        assertEquals("", DesktopPlayback.spdifOption(emptySet()))
    }
}
