package dev.nitrate.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 10-bit HEVC on boxes like the Fire TV Stick, whose software decoder outsizes its hardware one. */
class TenBitTest {
    private val hardware = VideoCodecCaps("hevc", "video/hevc", "OMX.MTK.VIDEO.DECODER.HEVC", hardware = true, maxWidth = 3840, maxHeight = 2160, tenBit = true)
    private val software = VideoCodecCaps("hevc", "video/hevc", "c2.android.hevc.decoder", hardware = false, maxWidth = 4096, maxHeight = 2304, tenBit = false)

    @Test fun hardwareSpeaksForTheDevice() {
        val combined = VideoCodecCaps.combine(listOf(software, hardware))!!
        assertEquals("OMX.MTK.VIDEO.DECODER.HEVC", combined.decoderName)
        assertTrue(combined.tenBit)
    }

    @Test fun tenBitFromAnyHardwareDecoder() {
        // Two hardware decoders: the biggest reports Main only, a smaller one Main10.
        val plain = hardware.copy(tenBit = false)
        val other = hardware.copy(decoderName = "c2.mtk.hevc.decoder", maxWidth = 1920, maxHeight = 1080)
        assertTrue(VideoCodecCaps.combine(listOf(plain, other))!!.tenBit)
        assertEquals("OMX.MTK.VIDEO.DECODER.HEVC", VideoCodecCaps.combine(listOf(plain, other))!!.decoderName)
    }

    @Test fun softwareOnlyCodecsStopAt1080p() {
        val av1Software = VideoCodecCaps("av1", "video/av01", "c2.android.av1.decoder", hardware = false, maxWidth = 4096, maxHeight = 2176, tenBit = true)
        val tv = caps(video = listOf(video("hevc"), av1Software))
        val limits = CapabilityRules.videoLimits(tv, PlaybackSettings()).associateBy { it.codec }
        assertEquals(1080, limits.getValue("av1").maxHeight)
        assertEquals(1920, limits.getValue("av1").maxWidth)
        assertEquals(2160, limits.getValue("hevc").maxHeight)
        // "Always" lifts it.
        val always = CapabilityRules.videoLimits(tv, PlaybackSettings(overrides = mapOf("av1" to Override.ALWAYS))).first { it.codec == "av1" }
        assertEquals(2176, always.maxHeight)
    }

    @Test fun softwareOnlyStaysSoftware() {
        assertEquals(software, VideoCodecCaps.combine(listOf(software)))
    }

    @Test fun passingTenBitClipAllowsTenBit() {
        val eightBitTv = caps(video = listOf(video("h264"), hardware.copy(tenBit = false)))
        val start = PlaybackSettings()
        assertFalse(CapabilityRules.videoLimits(eightBitTv, start).first { it.codec == "hevc" }.tenBit)
        val s = PlaybackTest.suggestions(listOf(pass("HEVC 1080p"), pass("HEVC 4K 10-bit")), eightBitTv, start).fold(start) { acc, x -> x.apply(acc) }
        assertTrue("hevc" in s.tenBitAllowed)
        assertTrue(CapabilityRules.videoLimits(eightBitTv, s).first { it.codec == "hevc" }.tenBit)
    }

    @Test fun smoothSoftware4kLiftsTheCap() {
        val av1Software = VideoCodecCaps("av1", "video/av01", "c2.android.av1.decoder", hardware = false, maxWidth = 4096, maxHeight = 2176, tenBit = true)
        val tv = caps(video = listOf(video("hevc"), av1Software))
        val s = PlaybackTest.suggestions(listOf(pass("AV1 1080p"), pass("AV1 4K 10-bit")), tv, PlaybackSettings()).fold(PlaybackSettings()) { acc, x -> x.apply(acc) }
        assertEquals(Override.ALWAYS, s.overrides["av1"])
        // A stuttering 4K clip leaves the cap alone.
        val slowRun = PlaybackTest.suggestions(listOf(pass("AV1 1080p"), slow("AV1 4K 10-bit")), tv, PlaybackSettings())
        assertTrue(slowRun.none { it.description.startsWith("Allow AV1 above") })
    }
}
