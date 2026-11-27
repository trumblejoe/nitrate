package dev.nitrate.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTestTest {
    private fun applyAll(results: List<ClipResult>, caps: DeviceCapabilities = caps(), start: PlaybackSettings = PlaybackSettings()) =
        PlaybackTest.suggestions(results, caps, start).fold(start) { s, suggestion -> suggestion.apply(s) }

    @Test
    fun `4K failure with 1080p passing limits resolution, not the codec`() {
        val s = applyAll(listOf(pass("H.264 1080p"), slow("H.264 4K")))
        assertEquals(1080, s.codecMaxHeight["h264"])
        assertTrue("h264" !in s.overrides)
    }

    @Test
    fun `10-bit failure at the same height limits bit depth`() {
        val s = applyAll(listOf(pass("H.264 1080p"), fail("H.264 1080p 10-bit")))
        assertTrue("h264" in s.eightBitOnly)
        assertTrue("h264" !in s.codecMaxHeight)
    }

    @Test
    fun `failing base clip disables the codec and nothing narrower`() {
        val s = applyAll(listOf(fail("HEVC 1080p"), fail("HEVC 4K 10-bit")))
        assertEquals(Override.NEVER, s.overrides["hevc"])
        assertTrue(s.codecMaxHeight.isEmpty() && s.eightBitOnly.isEmpty())
    }

    @Test
    fun `HDR failure with a passing SDR twin disables only that HDR format`() {
        val s = applyAll(listOf(pass("HEVC 1080p"), pass("HEVC 4K 10-bit"), fail("HEVC 4K HDR10")))
        assertEquals(Override.NEVER, s.overrides[PlaybackSettings.hdrId("HDR10")])
        assertTrue("hevc" !in s.overrides)
    }

    @Test
    fun `HDR failure without a passing twin is blamed on resolution instead`() {
        val s = applyAll(listOf(pass("HEVC 1080p"), pass("HEVC 1080p HLG"), fail("HEVC 4K 10-bit"), fail("HEVC 4K HDR10")))
        assertEquals(1080, s.codecMaxHeight["hevc"])
        assertTrue(PlaybackSettings.hdrId("HDR10") !in s.overrides)
    }

    @Test
    fun `codec that works but is not reported gets forced on`() {
        val s = applyAll(listOf(pass("AV1 1080p")), caps = caps(video = listOf(video("h264"))))
        assertEquals(Override.ALWAYS, s.overrides["av1"])
    }

    @Test
    fun `audio claimed but failing is disabled, unclaimed but working is enabled`() {
        val s = applyAll(listOf(fail("Dolby Digital 5.1"), pass("DTS 5.1")), caps = caps(audio = mapOf("ac3" to true, "dts" to false)))
        assertEquals(Override.NEVER, s.overrides["ac3"])
        assertEquals(Override.ALWAYS, s.overrides["dts"])
    }

    @Test
    fun `nothing is suggested once applied`() {
        val results = listOf(pass("H.264 1080p"), slow("H.264 4K"), fail("Dolby Digital 5.1"))
        val applied = applyAll(results)
        assertTrue(PlaybackTest.suggestions(results, caps(), applied).isEmpty())
    }

    @Test
    fun `claimed reflects resolution, bit depth and HDR`() {
        val c = caps(video = listOf(video("hevc", maxHeight = 1080, tenBit = false)), hdr = emptySet())
        assertTrue(clip("HEVC 1080p").claimed(c))
        assertTrue(!clip("HEVC 4K 10-bit").claimed(c))
        assertTrue(!clip("HEVC 1080p HLG").claimed(c))
    }

    @Test
    fun `the library's hardest file of a software-only format decides whether the server converts it`() {
        val file = HeaviestFile("vc1", "item-1", 1920, 1080, 23.976, 24_000_000, runTimeMs = 6_000_000)
        assertEquals("1080p, 24 Mbps, 23.976 fps", file.describe)
        val sample = PlaybackTest.librarySample(file)
        assertEquals("library:vc1", sample.key)
        assertEquals(2_000_000L, sample.startMs)
        val software = caps(video = listOf(VideoCodecCaps("h264", "video/avc", "c2.h264", true, 3840, 2160, true), VideoCodecCaps("vc1", "video/wvc1", "nitrate.ffmpeg.vc1", false, 1920, 1080, false)))
        assertEquals(listOf("vc1"), PlaybackTest.softwareOnly(software))

        val slow = ClipResult(sample, Outcome.SLOW, "nitrate.ffmpeg.vc1", "Dropped 90 of 480 frames", 390, 90)
        val s = applyAll(listOf(slow), software)
        assertEquals(Override.NEVER, s.overrides["vc1"])
        // Smooth: Automatic stays (no suggestion), and nothing narrower is guessed from it.
        val smooth = ClipResult(sample, Outcome.PASSED, "nitrate.ffmpeg.vc1", "Showed 480 frames", 480, 0)
        assertTrue(PlaybackTest.suggestions(listOf(smooth), software, PlaybackSettings()).isEmpty())

        val check = DeviceCheck(0, "Stick", clips = mapOf(sample.key to CheckedClip(Outcome.SLOW, shown = 390, dropped = 90)))
        assertEquals("Device check: your hardest VC-1 file was too much for this device (90 of 480 frames dropped); Server converts suits it", PlaybackTest.measured(check, "vc1"))
        assertEquals(null, PlaybackTest.measured(check, "mpeg2video"))
    }
}

class DolbyVisionAdviceTest {
    private val dvCaps = caps().copy(hdr = setOf("HDR10", "HLG", "DOVI"), dolbyVisionProfiles = setOf(5, 8))
    private fun result(label: String, outcome: Outcome) = ClipResult(clip(label), outcome, null, "")
    private fun advice(vararg results: ClipResult, c: DeviceCapabilities = dvCaps) =
        PlaybackTest.suggestions(listOf(pass("HEVC 1080p"), pass("HEVC 4K 10-bit")) + results, c, PlaybackSettings())
            .fold(PlaybackSettings()) { s, x -> x.apply(s) }

    @Test
    fun `claimed Dolby Vision that falls back to HDR10 gets disabled`() {
        val s = advice(result("Dolby Vision 8.1 4K", Outcome.FALLBACK), result("Dolby Vision 5 4K", Outcome.FAILED))
        assertEquals(Override.NEVER, s.overrides[PlaybackSettings.hdrId("DOVI")])
        assertTrue("hevc" !in s.overrides)
    }

    @Test
    fun `Dolby Vision that really plays needs no change`() {
        val s = advice(result("Dolby Vision 8.1 4K", Outcome.PASSED), result("Dolby Vision 5 4K", Outcome.PASSED))
        assertTrue(s.overrides.isEmpty())
    }

    @Test
    fun `falling back on a TV without Dolby Vision is expected, not a mismatch`() {
        val noDv = caps()
        assertFalse(clip("Dolby Vision 8.1 4K").claimed(noDv))
        val s = advice(result("Dolby Vision 8.1 4K", Outcome.FALLBACK), result("Dolby Vision 5 4K", Outcome.FAILED), c = noDv)
        assertTrue(s.overrides.isEmpty())
    }

    @Test
    fun `claim needs the decoder profile as well as the display`() {
        val displayOnly = dvCaps.copy(dolbyVisionProfiles = setOf(8))
        assertTrue(clip("Dolby Vision 8.1 4K").claimed(displayOnly))
        assertFalse(clip("Dolby Vision 5 4K").claimed(displayOnly))
    }
}
