package dev.nitrate.core.playback

import dev.nitrate.core.downloads.DownloadTracks
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.MediaSource
import dev.nitrate.core.model.MediaStream
import dev.nitrate.core.model.StreamKind
import dev.nitrate.core.model.SubtitleDelivery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaInfoTest {
    private val streams = listOf(
        MediaStream(0, StreamKind.VIDEO, codec = "h264", width = 1920, height = 1080, bitDepth = 8, rangeType = "SDR"),
        MediaStream(1, StreamKind.AUDIO, codec = "aac", language = "jpn", title = "Japanese", channels = 2),
        MediaStream(2, StreamKind.AUDIO, codec = "aac", language = "eng", title = "English", channels = 2, isDefault = true),
        MediaStream(3, StreamKind.SUBTITLE, codec = "ass", language = "eng", isExternal = true, delivery = SubtitleDelivery.EXTERNAL),
        MediaStream(4, StreamKind.SUBTITLE, codec = "ass", language = "eng", title = "Signs", isForced = true, isExternal = true, delivery = SubtitleDelivery.EXTERNAL),
    )

    @Test fun `a download starts with the server's picks for the user`() {
        // Japanese audio and full English subtitles, as the account's language settings say.
        assertEquals(1 to 3, DownloadTracks.pick(MediaSource("s", "mkv", null, streams, defaultAudioIndex = 1, defaultSubtitleIndex = 3, defaultsKnown = true)))
        // The server said subtitles off: off, not the forced track.
        assertEquals(1 to -1, DownloadTracks.pick(MediaSource("s", "mkv", null, streams, defaultAudioIndex = 1, defaultSubtitleIndex = -1, defaultsKnown = true)))
        // Saved before picks were kept: the default audio and any forced subtitle, as before.
        assertEquals(2 to 4, DownloadTracks.pick(MediaSource("s", "mkv", null, streams)))
        // A pick that isn't in the file (dropped by a conversion) falls back.
        assertEquals(2 to 4, DownloadTracks.pick(MediaSource("s", "mkv", null, streams, defaultAudioIndex = 9, defaultSubtitleIndex = 8, defaultsKnown = true)))
    }

    @Test fun `media info lists every stream and the picks`() {
        val source = MediaSource("s", "mkv", 4_000_000, streams, size = 1_500_000_000, defaultAudioIndex = 1, defaultSubtitleIndex = 3, defaultsKnown = true)
        val text = MediaInfo.render(MediaItem("m", "Film", ItemKind.MOVIE), source)
        println(text)
        assertTrue("#1 Audio: aac · 2 ch · jpn · \"Japanese\"  ← server's pick" in text, text)
        assertTrue("#3 Subtitle: ass · eng · external file · delivered external  ← server's pick" in text, text)
        assertTrue("Subtitles: #3" in text)
        assertTrue("Size: 1.3 GB" in text)
    }

    @Test fun `what an ASS file holds`() {
        val about = MediaInfo.aboutAss("﻿[Script Info]\nScriptType: v4.00+\n\n[V4+ Styles]\nStyle: Default,Arial,28,&H0000FFFF\nStyle: Sign,Arial,40,&H00FFFFFF\n\n[Events]\nDialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hi\n")
        assertEquals("Starts with: [Script Info] (after a byte-order mark)", about[0])
        assertEquals("Styles: 2, lines of dialogue: 1", about[1])
        assertTrue(about[2].contains("Style: Default"))
    }
}
