package dev.nitrate.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The playback test's profile 7 clips choose how profile 7 plays on the device, by themselves. */
class Profile7ChoiceTest {
    private val clips = PlaybackTest.profile7Clips(heavy = true)

    private fun check(vararg outcomes: Pair<String, Outcome>) =
        DeviceCheck(atMs = 1, model = "TV", clips = outcomes.associate { (key, o) -> key to CheckedClip(o) })

    private fun key(asset: String, way: Profile7Playback) = clips.first { it.asset == asset && it.profile7 == way }.key

    private val melC = key("dv_p7_mel_2160p.mkv", Profile7Playback.AS_PROFILE_8)
    private val melB = key("dv_p7_mel_2160p.mkv", Profile7Playback.BASE_LAYER)
    private val felC = key("dv_p7_fel_2160p.mkv", Profile7Playback.AS_PROFILE_8)
    private val felB = key("dv_p7_fel_2160p.mkv", Profile7Playback.BASE_LAYER)
    private val heavyC = key("dolby_vision_7_fel_heavy.mkv", Profile7Playback.AS_PROFILE_8)
    private val heavyB = key("dolby_vision_7_fel_heavy.mkv", Profile7Playback.BASE_LAYER)

    @Test fun eachKindBothWaysWithTheHeavyClipFromTheServer() {
        assertEquals(6, clips.size)
        assertEquals(4, PlaybackTest.profile7Clips(heavy = false).size)
        assertEquals(PlaybackTest.HEAVY_FEL_CLIP, clips.last().fromServer)
        assertEquals("dv_p7_fel_2160p.mkv#BASE_LAYER", felB)
        // Results of the other clips are kept under their file as before.
        assertEquals("hevc_1080p.mp4", PlaybackTest.CLIPS.first { it.asset == "hevc_1080p.mp4" }.key)
    }

    @Test fun aKindThatShowsNoPictureConvertedPlaysAsItsBaseLayer() {
        val c = check(melC to Outcome.FALLBACK, melB to Outcome.PASSED, felC to Outcome.FAILED, felB to Outcome.PASSED)
        assertEquals(Profile7Playback.BASE_LAYER, Profile7Choice.of(Profile7Playback.AS_PROFILE_8, c, DolbyVisionLayers.FEL).way)
        assertTrue(Profile7Choice.of(Profile7Playback.AS_PROFILE_8, c, DolbyVisionLayers.FEL).because!!.contains("FEL"))
        // MEL played converted (as HDR10 on a TV that shows it so): it stays converted.
        assertEquals(Profile7Playback.AS_PROFILE_8, Profile7Choice.of(Profile7Playback.AS_PROFILE_8, c, DolbyVisionLayers.MEL).way)
        // A file not played here yet: the safe way, since one kind needed it.
        assertEquals(Profile7Playback.BASE_LAYER, Profile7Choice.of(Profile7Playback.AS_PROFILE_8, c, null).way)
    }

    @Test fun theDiscLikeClipCountsForFel() {
        val c = check(felC to Outcome.PASSED, felB to Outcome.PASSED, heavyC to Outcome.FAILED, heavyB to Outcome.PASSED)
        assertTrue(Profile7Choice.needsBaseLayer(DolbyVisionLayers.FEL, c))
        assertFalse(Profile7Choice.needsBaseLayer(DolbyVisionLayers.MEL, c))
    }

    @Test fun nothingChangesWithoutAReasonOrAWayOut() {
        // No test yet, or everything played converted.
        assertNull(Profile7Choice.of(Profile7Playback.AS_PROFILE_8, null, null).because)
        val fine = check(melC to Outcome.PASSED, melB to Outcome.PASSED, felC to Outcome.PASSED, felB to Outcome.PASSED)
        assertEquals(Profile7Playback.AS_PROFILE_8, Profile7Choice.of(Profile7Playback.AS_PROFILE_8, fine, null).way)
        // Converted fails but so does the base layer: nothing better to choose.
        val both = check(felC to Outcome.FAILED, felB to Outcome.FAILED)
        assertEquals(Profile7Playback.AS_PROFILE_8, Profile7Choice.of(Profile7Playback.AS_PROFILE_8, both, DolbyVisionLayers.FEL).way)
        // A device that plays profile 7 itself isn't changed by the other ways' results alone.
        val failing = check(felC to Outcome.FAILED, felB to Outcome.PASSED)
        assertEquals(Profile7Playback.DOLBY_VISION, Profile7Choice.of(Profile7Playback.DOLBY_VISION, failing, DolbyVisionLayers.FEL).way)
    }

    private val natives = PlaybackTest.profile7Clips(heavy = true, native = true)
    private fun nativeKey(asset: String) = natives.first { it.asset == asset && it.profile7 == Profile7Playback.DOLBY_VISION }.key
    private val melN = nativeKey("dv_p7_mel_2160p.mkv")
    private val felN = nativeKey("dv_p7_fel_2160p.mkv")

    @Test fun aDeviceThatClaimsProfile7AlsoPlaysEachClipAsDolbyVision7() {
        assertEquals(9, natives.size)
        assertEquals(6, PlaybackTest.profile7Clips(heavy = false, native = true).size)
        assertEquals("dv_p7_fel_2160p.mkv#DOLBY_VISION", felN)
    }

    @Test fun aKindThatShowsNoPictureAsDolbyVision7StepsDown() {
        // FEL is black as Dolby Vision 7 but plays converted: converted, where the device can convert.
        val c = check(melN to Outcome.PASSED, felN to Outcome.FAILED, felC to Outcome.PASSED, felB to Outcome.PASSED)
        val fel = Profile7Choice.of(Profile7Playback.DOLBY_VISION, c, DolbyVisionLayers.FEL, canConvert = true)
        assertEquals(Profile7Playback.AS_PROFILE_8, fel.way)
        assertTrue(fel.because!!.contains("as Dolby Vision 7"))
        assertEquals(Profile7Playback.DOLBY_VISION, Profile7Choice.of(Profile7Playback.DOLBY_VISION, c, DolbyVisionLayers.MEL).way)
        // It can't convert: its base layer.
        assertEquals(Profile7Playback.BASE_LAYER, Profile7Choice.of(Profile7Playback.DOLBY_VISION, c, DolbyVisionLayers.FEL, canConvert = false).way)
        // Converted is black too: straight to its base layer.
        val worse = check(felN to Outcome.FAILED, felC to Outcome.FAILED, felB to Outcome.PASSED)
        assertEquals(Profile7Playback.BASE_LAYER, Profile7Choice.of(Profile7Playback.DOLBY_VISION, worse, DolbyVisionLayers.FEL).way)
        // Nothing else played: it stays.
        val nothing = check(felN to Outcome.FAILED, felC to Outcome.FAILED, felB to Outcome.FAILED)
        assertEquals(Profile7Playback.DOLBY_VISION, Profile7Choice.of(Profile7Playback.DOLBY_VISION, nothing, DolbyVisionLayers.FEL).way)
    }

    @Test fun profile7ClipsNeverBecomeSuggestedSettings() {
        val caps = caps()
        val failed = PlaybackTest.profile7Clips(false).map { ClipResult(it, Outcome.FAILED, "OMX.MTK.VIDEO.DECODER.HEVC", "No frames shown") }
        assertTrue(PlaybackTest.suggestions(failed, caps, PlaybackSettings()).isEmpty())
    }
}
