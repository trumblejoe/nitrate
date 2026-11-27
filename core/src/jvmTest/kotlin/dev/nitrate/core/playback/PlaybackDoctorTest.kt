package dev.nitrate.core.playback

import dev.nitrate.core.model.MediaSource
import dev.nitrate.core.model.MediaStream
import dev.nitrate.core.model.PlayMethod
import dev.nitrate.core.model.PlaybackPlan
import dev.nitrate.core.model.StreamKind
import dev.nitrate.core.model.SubtitleDelivery
import dev.nitrate.core.model.VersionOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackDoctorTest {
    private fun plan(
        method: PlayMethod = PlayMethod.TRANSCODE,
        reasons: List<String> = emptyList(),
        streams: List<MediaStream> = listOf(
            MediaStream(0, StreamKind.VIDEO, codec = "hevc", profile = "Main 10", width = 3840, height = 2160, bitDepth = 10, rangeType = "SDR"),
            MediaStream(1, StreamKind.AUDIO, codec = "truehd", channels = 8, channelLayout = "7.1", language = "eng", title = "English TrueHD 7.1"),
            MediaStream(2, StreamKind.AUDIO, codec = "ac3", channels = 6, language = "eng", title = "English AC3 5.1"),
            MediaStream(3, StreamKind.SUBTITLE, codec = "PGSSUB", language = "eng", delivery = SubtitleDelivery.ENCODE),
            MediaStream(4, StreamKind.SUBTITLE, codec = "subrip", language = "eng", title = "English SRT", delivery = SubtitleDelivery.EXTERNAL),
        ),
        audioIndex: Int = 1,
        subtitleIndex: Int = -1,
        bitrate: Int = 60_000_000,
        versions: List<VersionOption> = emptyList(),
    ) = PlaybackPlan(
        itemId = "m1", url = "http://x", method = method, playSessionId = "ps",
        source = MediaSource(id = "s1", container = "mkv", bitrate = bitrate, streams = streams),
        isHls = method != PlayMethod.DIRECT_PLAY, audioIndex = audioIndex, subtitleIndex = subtitleIndex,
        externalSubtitles = emptyList(), transcodeReasons = reasons, versions = versions,
    )

    @Test fun directPlayHasNothingToFix() {
        val d = PlaybackDoctor.diagnose(plan(method = PlayMethod.DIRECT_PLAY), caps(), PlaybackSettings())
        assertEquals(PlaybackQuality.ORIGINAL, d.quality)
        assertFalse(d.hasAdvice)
    }

    @Test fun soundOnlyOffersAnotherTrackAndSaysThePictureIsUntouched() {
        val d = PlaybackDoctor.diagnose(plan(reasons = listOf("AudioCodecNotSupported")), caps(), PlaybackSettings())
        assertEquals(PlaybackQuality.SOUND_CONVERTED, d.quality)
        val f = d.findings.single()
        assertTrue(f.text, "Dolby TrueHD" in f.text && "picture isn't touched" in f.text)
        assertEquals(PlaybackFix.UseAudio(2, "Switch to English AC3 5.1", "This track plays as it is here."), f.fixes.single())
    }

    @Test fun burnedInSubtitlesOfferTheTextTrackAndOff() {
        val d = PlaybackDoctor.diagnose(plan(reasons = listOf("SubtitleCodecNotSupported"), subtitleIndex = 3), caps(), PlaybackSettings())
        assertEquals(PlaybackQuality.PICTURE_CONVERTED, d.quality)
        val f = d.findings.single()
        assertTrue(f.text, "PGS subtitles are pictures" in f.text)
        assertEquals(listOf(4, -1), f.fixes.map { (it as PlaybackFix.UseSubtitle).index })
        assertEquals(null, f.adminTip)
    }

    @Test
    fun awayTheTravelLimitIsNamedAndTheHomeLimitIsLeftAlone() {
        val away = QualityDecision(NetworkPlace.AWAY, 12_000_000, auto = true, measuredBps = 18_000_000)
        val d = PlaybackDoctor.diagnose(plan(reasons = listOf("ContainerBitrateExceedsLimit")), caps(), PlaybackSettings(), quality = away)
        val f = d.findings.single { it.title == "The file is bigger than the limit" }
        assertTrue(f.text, "Quality away from home" in f.text && "Auto: 12 Mbps" in f.text)
        assertTrue(f.fixes.none { it is PlaybackFix.Change })
    }

    @Test fun bitrateOverTheLimitOffersTheNextStep() {
        val d = PlaybackDoctor.diagnose(plan(reasons = listOf("ContainerBitrateExceedsLimit")), caps(), PlaybackSettings(maxBitrate = 20_000_000))
        val fix = d.findings.single().fixes.first() as PlaybackFix.Change
        assertEquals(SettingsChange.Bitrate(80_000_000), fix.change)
        assertEquals(80_000_000, fix.change.apply(PlaybackSettings()).maxBitrate)
    }

    @Test fun aFormatTheUserHasConvertedCanBePlayedOriginalAgain() {
        val never = PlaybackDoctor.diagnose(plan(reasons = listOf("VideoCodecNotSupported")), caps(), PlaybackSettings(overrides = mapOf("hevc" to Override.NEVER)))
        val fix = never.findings.single().fixes.first() as PlaybackFix.Change
        assertEquals("Play original", fix.label)
        assertEquals(SettingsChange.ClearOverride("hevc"), fix.change)
    }

    @Test fun aPartConvertedThisTimeOffersPlayOriginalOrAlways() {
        val d = PlaybackDoctor.diagnose(plan(reasons = listOf("VideoCodecNotSupported")), caps(), PlaybackSettings(), converted = setOf("hevc"))
        val f = d.findings.single()
        assertEquals("HEVC converted this time", f.title)
        assertTrue(f.fixes[0] is PlaybackFix.PlayOriginal)
        val always = (f.fixes[1] as PlaybackFix.Change).change
        assertEquals(SettingsChange.ConvertHere("hevc"), always)
        assertEquals(Override.NEVER, always.apply(PlaybackSettings()).overrides["hevc"])
    }

    @Test fun softwareDecodingOffersConvertThisTimeOrAlways() {
        val vc1 = listOf(MediaStream(0, StreamKind.VIDEO, codec = "vc1", width = 1920, height = 1080))
        val software = caps().let { c -> c.copy(video = c.video + ("vc1" to VideoCodecCaps("vc1", "video/wvc1", "nitrate.ffmpeg.vc1", false, 1920, 1080, false))) }
        val p = plan(method = PlayMethod.DIRECT_PLAY, streams = vc1, audioIndex = -1)
        assertTrue(PlaybackDoctor.softwarePicture(p, software))
        val f = PlaybackDoctor.diagnose(p, software, PlaybackSettings()).findings.single()
        assertEquals("VC-1 is decoded in software here", f.title)
        assertEquals(PlaybackFix.ConvertPart::class, f.fixes[0]::class)
        assertEquals(SettingsChange.ConvertHere("vc1"), (f.fixes[1] as PlaybackFix.Change).change)
        // Once always converted, the finding isn't offered while it plays as it is.
        assertTrue(PlaybackDoctor.diagnose(p, software, PlaybackSettings(overrides = mapOf("vc1" to Override.NEVER))).findings.isEmpty())
    }

    @Test fun missingDecoderSuggestsADirectVersionFirst() {
        val av1 = listOf(MediaStream(0, StreamKind.VIDEO, codec = "av1", width = 1920, height = 1080))
        val d = PlaybackDoctor.diagnose(
            plan(reasons = listOf("VideoCodecNotSupported"), streams = av1, audioIndex = -1,
                versions = listOf(VersionOption("s1", "AV1", false, false), VersionOption("s2", "1080p H.264", true, true))),
            caps(), PlaybackSettings(),
        )
        val f = d.findings.single()
        assertTrue(f.title, "can't decode AV1" in f.title)
        assertEquals("s2", (f.fixes[0] as PlaybackFix.UseVersion).versionId)
        assertEquals(SettingsChange.ForceAllow("av1"), (f.fixes[1] as PlaybackFix.Change).change)
    }

    @Test fun resolutionLimitsAreNamed() {
        val d = PlaybackDoctor.diagnose(
            plan(reasons = listOf("VideoResolutionNotSupported")), caps(),
            PlaybackSettings(maxResolution = MaxResolution.FHD, codecMaxHeight = mapOf("hevc" to 1080)),
        )
        val changes = d.findings.single().fixes.map { (it as PlaybackFix.Change).change }
        assertEquals(listOf(SettingsChange.ResolutionAuto, SettingsChange.LiftHeightLimit("hevc")), changes)
    }

    @Test fun eightBitOnlyCanBeLifted() {
        val s = PlaybackSettings(eightBitOnly = setOf("hevc"))
        val d = PlaybackDoctor.diagnose(plan(reasons = listOf("VideoBitDepthNotSupported")), caps(), s)
        val change = (d.findings.single().fixes.single() as PlaybackFix.Change).change
        val after = change.apply(s)
        assertTrue("hevc" !in after.eightBitOnly && "hevc" in after.tenBitAllowed)
    }

    @Test fun forcedTranscodeOffersPlayOriginalAndRemuxIsReassuring() {
        val forced = PlaybackDoctor.diagnose(plan(), caps(), PlaybackSettings(), forcedTranscode = true)
        assertTrue(forced.findings.single().fixes.single() is PlaybackFix.PlayOriginal)

        val remux = PlaybackDoctor.diagnose(plan(method = PlayMethod.DIRECT_STREAM, reasons = listOf("ContainerNotSupported")), caps(), PlaybackSettings())
        assertEquals(PlaybackQuality.REPACKAGED, remux.quality)
        assertTrue("Nothing is re-encoded" in remux.findings.single().text)
    }

    @Test fun unknownReasonsStillShow() {
        val d = PlaybackDoctor.diagnose(plan(reasons = listOf("SomethingNew")), caps(), PlaybackSettings())
        assertEquals("Something new", d.findings.single().title)
    }
}
