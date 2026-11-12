package dev.nitrate.core.playback

import dev.nitrate.core.model.MediaSource
import dev.nitrate.core.model.MediaStream
import dev.nitrate.core.model.PlayMethod
import dev.nitrate.core.model.PlaybackPlan
import dev.nitrate.core.model.StreamKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackReportTest {
    private val plan = PlaybackPlan(
        itemId = "m1",
        url = "http://jf:8096/videos/m1/master.m3u8?MediaSourceId=s1&api_key=SECRET123&VideoCodec=h264",
        method = PlayMethod.TRANSCODE,
        playSessionId = "ps",
        source = MediaSource(
            id = "s1", container = "mkv", bitrate = 1_500_000,
            streams = listOf(
                MediaStream(0, StreamKind.VIDEO, codec = "hevc", profile = "Main 10", width = 720, height = 480, bitDepth = 10, rangeType = "SDR"),
                MediaStream(1, StreamKind.AUDIO, codec = "aac", channels = 2, language = "eng", isDefault = true),
            ),
        ),
        isHls = true,
        audioIndex = 1,
        subtitleIndex = -1,
        externalSubtitles = emptyList(),
        transcodeReasons = listOf("VideoBitDepthNotSupported"),
    )

    @Test fun reportsTheDecisionAndHidesTokens() {
        val eightBitHevc = caps(video = listOf(video("h264"), video("hevc", tenBit = false)))
        val text = PlaybackReport(
            app = "Nitrate", version = "dev.20", createdAt = "2026-11-12 10:00", caps = eightBitHevc, settings = PlaybackSettings(),
            plan = plan, videoDecoder = "OMX.MTK.VIDEO.DECODER.AVC", audioDecoder = "c2.android.aac.decoder",
            events = listOf("10:00:00.000  Server: TRANSCODE because VideoBitDepthNotSupported"),
        ).render()
        assertTrue(text, "Method: TRANSCODE" in text)
        assertTrue("Reasons: VideoBitDepthNotSupported" in text)
        assertTrue("#0 VIDEO: hevc, Main 10, 720x480, 10-bit, SDR" in text)
        assertTrue("Video hevc: up to" in text && "8-bit" in text)
        assertTrue("Video decoder" in text || "Video: OMX.MTK.VIDEO.DECODER.AVC" in text)
        assertTrue("Player events" in text)
        assertFalse("The access token never leaves in a report", "SECRET123" in text)
        assertTrue("api_key=<removed>" in text)
    }

    @Test fun redactsOtherTokenNames() {
        val url = PlaybackReport.redact("http://x/a?ApiKey=one&b=2&X-Emby-Token=two")
        assertFalse("one" in url.substringAfter("?").split("&").map { it.substringAfter("=") })
        assertFalse("two" in url)
        assertTrue("b=2" in url)
    }
}
