package dev.nitrate.core.playback

import dev.nitrate.core.jellyfin.ProfileCheck
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.MediaSource
import dev.nitrate.core.model.MediaStream
import dev.nitrate.core.model.PlayMethod
import dev.nitrate.core.model.PlaybackPlan
import dev.nitrate.core.model.StreamKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackTrailTest {
    private val tv = caps().copy(
        audio = mapOf("eac3" to AudioCodecCaps("eac3", "c2.dolby.eac3", passthrough = true), "truehd" to AudioCodecCaps("truehd", "ffmpeg", passthrough = true)),
        hdr = emptySet(),
    )
    private val film = MediaSource(
        "s", "mkv", 60_000_000,
        listOf(
            MediaStream(0, StreamKind.VIDEO, codec = "hevc", width = 3840, height = 2160, bitDepth = 10, rangeType = "DOVIWithHDR10", frameRate = 23.976f),
            MediaStream(1, StreamKind.AUDIO, codec = "truehd", channels = 8, isDefault = true),
        ),
    )
    private fun plan(method: PlayMethod, reasons: List<String> = emptyList()) =
        PlaybackPlan("a", "/Videos/a/stream", method, "p", film, isHls = false, audioIndex = 1, subtitleIndex = -1, externalSubtitles = emptyList(), transcodeReasons = reasons)

    @Test fun theAvatarCaseReadsStepByStep() {
        // HDR10 and Dolby Vision set to Always; the display reports no HDR; the server direct streams.
        val settings = PlaybackSettings(overrides = mapOf(PlaybackSettings.hdrId("HDR10") to Override.ALWAYS, PlaybackSettings.hdrId("DOVI") to Override.ALWAYS))
        val trail = PlaybackTrail.build(
            TrailInput(
                title = "Avatar (2009)", source = film, plan = plan(PlayMethod.DIRECT_STREAM), caps = tv, settings = settings,
                prediction = ProfileCheck.predict(tv, settings, film),
                video = VideoPath(inCodec = "hevc", inWidth = 3840, inHeight = 2160, inFps = 23.976f, inRange = "Dolby Vision", displayWidth = 3840, displayHeight = 2160, displayHz = 23.976f, displayHdr = emptySet()),
                audio = AudioPath(inCodec = "truehd", inChannels = 8, delivery = AudioDelivery.BITSTREAM, outFormat = "Dolby TrueHD", outChannels = 8),
                arrivedVideoCodec = "video/dolby-vision", arrivedAudioCodec = "audio/true-hd",
                videoDecoder = "OMX.MTK.VIDEO.DECODER.HEVC", displayHzBefore = 59.94f,
            ),
        )
        val picture = trail.trails.first { it.title == "Picture" }.steps.associateBy { it.step }
        assertEquals("Repackaged it (direct stream): the picture copied untouched", picture.getValue("Server").what)
        assertTrue("Dolby Vision allowed (set to Always)" in picture.getValue("Asked for").why!!, picture.getValue("Asked for").why)
        assertEquals("HEVC (Dolby Vision) · 3840×2160 · 23.976 fps · Dolby Vision", picture.getValue("Arrived").what)
        assertTrue("converted to SDR by this device" in picture.getValue("Went out").what, picture.getValue("Went out").what)
        assertEquals("Expect SDR (converted by this device)", picture.getValue("At the TV").what)
        val refresh = trail.trails.first { it.title == "Refresh rate" }.steps.last()
        assertEquals("Switched the TV from 59.94 Hz to 23.976 Hz", refresh.what)
        val sound = trail.trails.first { it.title == "Sound" }.steps.associateBy { it.step }
        assertEquals("Not decoded: sent on untouched", sound.getValue("Decoded by").what)
        // The prediction was "repackaged" (MKV direct plays here, so as it is): only the server step may differ.
        assertTrue(trail.render().contains("Playback trail: Avatar (2009)"))
    }

    @Test fun whatTurnedOutOtherwiseIsMarked() {
        // Predicted: plays as it is, sound untouched. Measured: converted by the server, sound decoded here.
        val trail = PlaybackTrail.build(
            TrailInput(
                title = "Film", source = film, plan = plan(PlayMethod.TRANSCODE, listOf("VideoRangeTypeNotSupported")), caps = tv.copy(hdr = setOf("HDR10")), settings = PlaybackSettings(),
                prediction = ProfileCheck.predict(tv.copy(hdr = setOf("HDR10")), PlaybackSettings(), film),
                audio = AudioPath(inCodec = "truehd", inChannels = 8, delivery = AudioDelivery.DECODED, outFormat = "PCM 16-bit", outChannels = 8),
            ),
        )
        val d = trail.differences
        assertEquals(2, d.size, d.toString())
        assertTrue(d[0].startsWith("Picture, server: Converted the picture") && "predicted: plays as it is" in d[0], d[0])
        assertTrue("predicted: sent untouched" in d[1], d[1])
        assertTrue("why: the HDR format isn't allowed for this device" in trail.render())
    }

    @Test fun aSampleTakesOneTitleOfEachKind() {
        fun entry(id: String, source: MediaSource) = OverviewEntry(MediaItem(id, id, ItemKind.MOVIE), ProfileCheck.predict(tv, PlaybackSettings(), source), source)
        val sd = film.copy(streams = listOf(MediaStream(0, StreamKind.VIDEO, codec = "h264", width = 720, height = 480), MediaStream(1, StreamKind.AUDIO, codec = "ac3", channels = 6, isDefault = true)))
        val sample = TestPlay.sample(listOf(entry("a", film), entry("b", film), entry("c", sd), entry("d", sd)))
        assertEquals(2, sample.size)
        assertEquals("MKV · HEVC 4K 10-bit Dolby Vision · Dolby TrueHD 7.1", TestPlay.describe(film))
    }

    @Test fun answersLeadToTheRightAdvice() {
        val trail = PlaybackTrail.build(
            TrailInput(
                title = "Avatar", source = film, plan = plan(PlayMethod.DIRECT_STREAM), caps = tv, settings = PlaybackSettings(),
                audio = AudioPath(inCodec = "truehd", inChannels = 8, delivery = AudioDelivery.BITSTREAM, outFormat = "Dolby TrueHD", outChannels = 8),
            ),
        )
        val result = TestPlayResult("a", "Avatar", 0, trail)
        assertEquals(listOf(TestPlay.TV, TestPlay.RECEIVER), TestPlay.questions(result).map { it.id })
        // HDR10 set to Never: offered back to Automatic.
        val settings = PlaybackSettings(overrides = mapOf(PlaybackSettings.hdrId("HDR10") to Override.NEVER))
        assertEquals(listOf("Set HDR10 back to Automatic"), TestPlay.advice(result, settings, tv.copy(hdr = setOf("HDR10"))).mapNotNull { it.changeLabel })
        // The TV showed HDR though Android reports none: both offered Always (Never included).
        val advice = TestPlay.advice(result.copy(answers = mapOf(TestPlay.TV to Answer.YES)), settings, tv)
        assertEquals(listOf("Set Dolby Vision to Always", "Set HDR10 to Always"), advice.mapNotNull { it.changeLabel })
        // The TV didn't switch: advice about the HDMI chain, no setting.
        val no = TestPlay.advice(result.copy(answers = mapOf(TestPlay.TV to Answer.NO)), PlaybackSettings(), tv)
        assertTrue(no.single().text.startsWith("The TV didn't switch to HDR, and Android reports no HDR here"), no.toString())
    }

    @Test fun smoothnessAndTheServersOwnReportAreInTheTrail() {
        val trail = PlaybackTrail.build(
            TrailInput(
                title = "Film", source = film, plan = plan(PlayMethod.TRANSCODE, listOf("VideoRangeTypeNotSupported")), caps = tv, settings = PlaybackSettings(),
                serverReport = ServerConversion(videoDirect = false, audioDirect = true, videoCodec = "hevc", hardware = "none", fps = 14f),
                smoothness = Smoothness(startMs = 4_200, stalls = 2, stalledMs = 6_300, networkBps = 30_000_000, streamBps = 60_000_000, seekResumeMs = 2_100, passthroughAfterSeek = false),
            ),
        )
        val d = trail.differences.joinToString("\n")
        assertTrue("too slow for real time" in d, d)
        assertTrue("stopped to buffer" in d, d)
        assertTrue("lost untouched surround after seeking" in d, d)
        val text = trail.render()
        assertTrue("Picture converted to HEVC on the processor at 14 fps (the film is 23.976 fps: can't keep up, expect pauses)" in text, text)
        assertTrue("2 stalls, 6.3 s in all" in text, text)
    }

    @Test fun theTvCheckLeadsToSettingsAndIsQuoted() {
        val seen = mapOf(
            "hdr10" to SeenClip(Answer.YES, "HDR10", "HDR10 · 4K", 1),
            "dolby_vision_81" to SeenClip(Answer.NO, "DOVI", "Dolby Vision 8.1 · 4K", 1),
            "dolby_truehd_51" to SeenClip(Answer.NO, "truehd", "Dolby TrueHD 5.1", 1, passthrough = true),
        )
        val advice = TvCheck.advice(seen, tv, PlaybackSettings())
        // HDR10 seen though Android reports none: Always. TrueHD sent untouched but not shown: advice only.
        assertEquals(listOf("Set HDR10 to Always"), advice.mapNotNull { it.changeLabel })
        assertTrue(advice.any { it.text.startsWith("Dolby TrueHD 5.1 went out untouched, but the receiver didn't show it") }, advice.toString())
        val p = ProfileCheck.predict(tv, PlaybackSettings(deviceCheck = DeviceCheck(0, "TV", seen = seen)), film.copy(streams = listOf(film.streams[0].copy(rangeType = "HDR10"), film.streams[1])))
        assertTrue("You saw the TV show HDR10 in the TV check" in p.checked, p.checked.toString())
    }

    @Test fun aDeviceSummaryAndWhatTheHouseholdConvertsMost() {
        val entries = PlaybackOverview.build(listOf(MediaItem("m1", "Loud", ItemKind.MOVIE) to film)) { _, source -> ProfileCheck.predict(caps(), PlaybackSettings(), source) }
        val overview = PlaybackOverview(entries, "Nitrate", "TV", "now", emptyList(), "Films")
        val summary = DeviceSummary.build("TV", "AFTKRT", "Nitrate", 0, caps(), PlaybackSettings(), overview, emptyList())
        assertEquals(1, summary.converted.size)
        // The same title converted on two of three devices comes first.
        fun device(id: String, titles: List<SummaryTitle>) = HouseholdDevice("u", "joe", id, id, 0, summary.copy(converted = titles))
        val t = summary.converted.single()
        val other = SummaryTitle("m2", "Other", "Picture converted by the server")
        val most = Household.mostConverted(listOf(device("a", listOf(t, other)), device("b", listOf(t)), device("c", emptyList())))
        assertEquals(listOf(t.name to 2), most.map { it.first.name to it.second })
    }
}
