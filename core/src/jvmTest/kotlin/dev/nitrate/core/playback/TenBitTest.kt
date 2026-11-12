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
        // The plain decoder reports Main only; its secure twin reports Main10.
        val plain = hardware.copy(tenBit = false)
        val secure = hardware.copy(decoderName = "OMX.MTK.VIDEO.DECODER.HEVC.secure", maxWidth = 1920, maxHeight = 1080)
        assertTrue(VideoCodecCaps.combine(listOf(plain, secure))!!.tenBit)
        assertEquals("OMX.MTK.VIDEO.DECODER.HEVC", VideoCodecCaps.combine(listOf(plain, secure))!!.decoderName)
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
}
