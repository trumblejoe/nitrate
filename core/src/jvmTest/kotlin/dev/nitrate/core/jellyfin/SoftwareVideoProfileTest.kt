package dev.nitrate.core.jellyfin

import dev.nitrate.core.playback.CapabilityRules
import dev.nitrate.core.playback.DeviceCapabilities
import dev.nitrate.core.playback.PlaybackSettings
import dev.nitrate.core.playback.VideoCodecCaps
import dev.nitrate.core.playback.caps
import dev.nitrate.core.playback.video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nitrate's software video decoder (FFmpeg) in the server profile. */
class SoftwareVideoProfileTest {
    private fun software(codec: String, mime: String, tenBit: Boolean = false) =
        VideoCodecCaps(codec, mime, DeviceCapabilities.SOFTWARE_VIDEO_DECODER, hardware = false, maxWidth = 1920, maxHeight = 1080, tenBit = tenBit)

    private val device = caps(video = listOf(video("h264", tenBit = false).copy(softwareTenBit = true), video("hevc"), software("vc1", "video/wvc1"), software("mpeg2video", "video/mpeg2")))
    private val profile = JellyfinDeviceProfile.create(device, PlaybackSettings())

    @Test
    fun `VC-1 is direct played from Matroska only, everything else from every container`() {
        val video = profile.directPlayProfiles.filter { it.type == "Video" }
        val main = video.first { "ts" in it.container.split(",") }
        assertFalse("vc1" in main.videoCodec!!.split(","))
        assertTrue("mpeg2video" in main.videoCodec!!.split(","))
        val mkv = video.single { it.container == "mkv,webm" }
        assertTrue("vc1" in mkv.videoCodec!!.split(","))
    }

    @Test
    fun `software decoders keep to 1080p and leave interlaced video to the server`() {
        val vc1 = profile.codecProfiles.single { it.codec == "vc1" }.conditions.associate { it.property to (it.condition to it.value) }
        assertEquals("LessThanEqual" to "1080", vc1["Height"])
        assertEquals("NotEquals" to "true", vc1["IsInterlaced"])
        // Hardware decoders deinterlace themselves.
        val hevc = profile.codecProfiles.filter { it.codec == "hevc" }.flatMap { it.conditions }
        assertFalse(hevc.any { it.property == "IsInterlaced" })
    }

    @Test
    fun `10-bit H_264 plays up to 1080p through the software decoder when the hardware is 8-bit only`() {
        val h264 = profile.codecProfiles.filter { it.codec == "h264" }
        val main = h264.single { it.applyConditions.isEmpty() }
        // 4K 8-bit stays with the hardware.
        assertEquals("2160", main.conditions.first { it.property == "Height" }.value)
        assertEquals("10", main.conditions.first { it.property == "VideoBitDepth" }.value)
        val tenBit = h264.single { it.applyConditions.isNotEmpty() }
        assertEquals("GreaterThanEqual", tenBit.applyConditions.single().condition)
        assertEquals("VideoBitDepth", tenBit.applyConditions.single().property)
        assertEquals("1080", tenBit.conditions.first { it.property == "Height" }.value)
        assertEquals("NotEquals", tenBit.conditions.first { it.property == "IsInterlaced" }.condition)
    }

    @Test
    fun `turning 10-bit off for the codec also turns off the software 10-bit`() {
        val limits = CapabilityRules.videoLimits(device, PlaybackSettings(eightBitOnly = setOf("h264")))
        val h264 = limits.single { it.codec == "h264" }
        assertFalse(h264.tenBit)
        assertNull(h264.tenBitMaxHeight)
    }

    @Test
    fun `hardware that decodes 10-bit itself needs no extra limit`() {
        val limits = CapabilityRules.videoLimits(caps(video = listOf(video("h264", tenBit = true).copy(softwareTenBit = true))), PlaybackSettings())
        assertNull(limits.single().tenBitMaxHeight)
    }
}
