package dev.nitrate.core.playback

import dev.nitrate.core.jellyfin.ProfileCheck
import dev.nitrate.core.model.MediaSource
import dev.nitrate.core.model.MediaStream
import dev.nitrate.core.model.StreamKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackPredictionTest {
    private val tv = caps().copy(
        audio = mapOf(
            "aac" to AudioCodecCaps("aac", "c2.aac", passthrough = false),
            "ac3" to AudioCodecCaps("ac3", "c2.ac3", passthrough = true),
            "eac3" to AudioCodecCaps("eac3", "c2.eac3", passthrough = true),
        ),
    )

    private fun film(
        video: MediaStream = MediaStream(0, StreamKind.VIDEO, codec = "hevc", width = 3840, height = 2160, bitDepth = 10, rangeType = "HDR10", frameRate = 23.976f),
        audio: MediaStream = MediaStream(1, StreamKind.AUDIO, codec = "eac3", channels = 6, isDefault = true),
        subtitles: List<MediaStream> = emptyList(),
        container: String = "mkv",
        bitrate: Int = 60_000_000,
    ) = MediaSource("s", container, bitrate, listOf(video, audio) + subtitles)

    private val display = DisplaySnapshot(
        DisplayMode(1, 3840, 2160, 59.94f),
        listOf(DisplayMode(1, 3840, 2160, 59.94f), DisplayMode(2, 3840, 2160, 23.976f), DisplayMode(3, 3840, 2160, 24f)),
    )

    @Test fun aUhdRemuxPlaysAsItIsWithItsSoundUntouched() {
        val p = ProfileCheck.predict(tv, PlaybackSettings(), film(bitrate = 110_000_000), display = display)
        assertEquals(PredictedDelivery.DIRECT, p.delivery)
        assertEquals(DecoderTier.HARDWARE, p.decoder)
        assertEquals(SoundRoute.PASSTHROUGH, p.sound)
        assertEquals("HDR10 as it is", p.hdr)
        assertTrue(p.picture!!.startsWith("HEVC 4K 10-bit HDR10 23.976 fps"), p.picture)
    }

    @Test fun soundTheDeviceCantPlayIsConvertedAloneAndTheReasonSaysWhy() {
        val p = ProfileCheck.predict(tv, PlaybackSettings(), film(audio = MediaStream(1, StreamKind.AUDIO, codec = "truehd", channels = 8)))
        assertEquals(PredictedDelivery.SOUND_CONVERTED, p.delivery)
        assertEquals(SoundRoute.CONVERTED, p.sound)
        assertTrue(p.reasons.single().startsWith("Dolby TrueHD sound isn't something this device plays"), p.reasons.toString())
    }

    @Test fun hdrOnAnSdrDisplayIsToneMappedByTheServer() {
        val p = ProfileCheck.predict(tv.copy(hdr = emptySet()), PlaybackSettings(), film())
        assertEquals(PredictedDelivery.CONVERTED, p.delivery)
        assertTrue(p.hdr!!.startsWith("Tone-mapped to SDR"), p.hdr)
        assertTrue(p.reasons.any { "tone-maps" in it })
    }

    @Test fun profilesNoDecoderTakesAreConvertedUpFront() {
        val hevc = video("hevc").copy(missingProfiles = setOf("rext"))
        val p = ProfileCheck.predict(tv.copy(video = mapOf("hevc" to hevc, "h264" to video("h264"))), PlaybackSettings(),
            film(video = MediaStream(0, StreamKind.VIDEO, codec = "hevc", profile = "Rext", width = 1920, height = 1080, bitDepth = 10)))
        assertEquals(PredictedDelivery.CONVERTED, p.delivery)
        assertTrue(p.reasons.any { "4:2:2/4:4:4" in it }, p.reasons.toString())
    }

    @Test fun anAviPlaysAsItIsNow() {
        val p = ProfileCheck.predict(tv, PlaybackSettings(), film(container = "avi", video = MediaStream(0, StreamKind.VIDEO, codec = "h264", width = 1280, height = 720), audio = MediaStream(1, StreamKind.AUDIO, codec = "ac3", channels = 6)))
        assertEquals(PredictedDelivery.DIRECT, p.delivery)
    }

    @Test fun pictureSubtitlesInTheirOwnFileAreDrawnInByTheServer() {
        val sub = MediaStream(3, StreamKind.SUBTITLE, codec = "pgssub", isExternal = true)
        val p = ProfileCheck.predict(tv, PlaybackSettings(), film(subtitles = listOf(sub)), subtitleIndex = 3)
        assertEquals(PredictedDelivery.CONVERTED, p.delivery)
        assertEquals("Drawn into the picture by the server", p.subtitles)
    }

    @Test fun nightModeDecodesSurroundAndSaysSo() {
        val p = ProfileCheck.predict(tv, PlaybackSettings(nightMode = true), film())
        assertEquals(SoundRoute.DECODED, p.sound)
        assertTrue("Night mode is on" in p.soundDetail!!, p.soundDetail)
    }

    @Test fun aTitleThatFailedHereIsConverted() {
        val settings = PlaybackSettings(failedTitles = mapOf("film-a" to TitleFailure("hevc", "DECODING_FAILED", 0)))
        val p = ProfileCheck.predict(tv, settings, film(), itemId = "film-a")
        assertEquals(PredictedDelivery.CONVERTED, p.delivery)
        assertTrue(p.reasons.any { "failed to play here" in it })
    }

    @Test fun refreshRatePlans() {
        assertEquals("Switches to 23.976 Hz before it starts (a moment of black)", PlaybackPredictor.refreshPlan(23.976f, display, PlaybackSettings(frameRateMatch = FrameRateMatch.ALWAYS)))
        assertEquals("Stays at 59.94 Hz: slight judder for 23.976 fps (refresh-rate matching is off)", PlaybackPredictor.refreshPlan(23.976f, display, PlaybackSettings(frameRateMatch = FrameRateMatch.OFF)))
        assertEquals("Stays at 59.94 Hz, which suits 29.97 fps", PlaybackPredictor.refreshPlan(29.97f, display, PlaybackSettings(frameRateMatch = FrameRateMatch.ALWAYS)))
        val measured = RefreshCheck(59.94f, 23.976f, switched = true, blackMs = 2_300)
        assertEquals("Switches to 23.976 Hz before it starts (about 2.3 s of black on this TV)", PlaybackPredictor.refreshPlan(23.976f, display, PlaybackSettings(frameRateMatch = FrameRateMatch.ALWAYS), measured))
    }

    @Test fun theDeviceCheckIsQuoted() {
        val check = DeviceCheck(0, "Test TV", clips = mapOf(
            "hevc_2160p_hdr10.mp4" to CheckedClip(Outcome.PASSED, "c2.vendor.hevc"),
            "eac3_51.mka" to CheckedClip(Outcome.PASSED, null, passthrough = true),
        ))
        val p = ProfileCheck.predict(tv, PlaybackSettings(deviceCheck = check), film())
        assertEquals(listOf("HEVC 4K HDR10: plays (c2.vendor.hevc)", "Dolby Digital Plus 5.1 went out untouched"), p.checked)
    }

    @Test fun theOverviewCountsAndReportsWhatIsConverted() {
        val items = listOf(
            dev.nitrate.core.model.MediaItem(id = "a", name = "Plays", kind = dev.nitrate.core.model.ItemKind.MOVIE, productionYear = 2020) to film(),
            dev.nitrate.core.model.MediaItem(id = "b", name = "Loud", kind = dev.nitrate.core.model.ItemKind.MOVIE) to
                film(audio = MediaStream(1, StreamKind.AUDIO, codec = "truehd", channels = 8)),
        )
        val entries = PlaybackOverview.build(items) { item, source -> ProfileCheck.predict(tv, PlaybackSettings(), source, itemId = item.id) }
        val o = PlaybackOverview(entries, "Nitrate", "Test TV", "today", PlaybackOverview.settingsSummary(PlaybackSettings()), "Films")
        assertEquals(1, o.count(OverviewFilter.DIRECT))
        assertEquals(1, o.count(OverviewFilter.SOUND))
        assertEquals("Plays (2020)", entries[0].name)
        val report = o.report()
        assertTrue("== Sound converted (1) ==" in report, report)
        assertTrue("- Loud:" in report, report)
        assertTrue("converted to AAC converted" !in report, report)
        assertTrue("Bitrate limit: No limit" in report, report)
    }
}
