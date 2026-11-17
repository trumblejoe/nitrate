package dev.nitrate.core.playback

import dev.nitrate.core.model.ExternalSubtitle
import dev.nitrate.core.model.MediaSource
import dev.nitrate.core.model.MediaStream
import dev.nitrate.core.model.PlayMethod
import dev.nitrate.core.model.PlaybackPlan
import dev.nitrate.core.model.StreamKind
import kotlin.test.Test
import kotlin.test.assertEquals

class MpvTracksTest {
    private val streams = listOf(
        MediaStream(0, StreamKind.VIDEO),
        MediaStream(1, StreamKind.AUDIO, language = "jpn"),
        MediaStream(2, StreamKind.AUDIO, language = "eng"),
        MediaStream(3, StreamKind.SUBTITLE, codec = "ass", language = "eng"),
        MediaStream(4, StreamKind.SUBTITLE, codec = "ass", language = "eng", title = "Signs"),
        MediaStream(5, StreamKind.SUBTITLE, codec = "srt", language = "spa", isExternal = true),
    )

    private fun plan(method: PlayMethod) = PlaybackPlan(
        itemId = "i", url = "u", method = method, playSessionId = null,
        source = MediaSource("s", "mkv", bitrate = null, streams = streams), isHls = method != PlayMethod.DIRECT_PLAY,
        audioIndex = 2, subtitleIndex = 4,
        externalSubtitles = listOf(ExternalSubtitle(5, "http://x/sub.srt", "application/x-subrip", "spa", "Spanish")),
        transcodeReasons = emptyList(),
    )

    @Test
    fun `a file played as it is maps by position among its own tracks, added files after`() {
        val p = plan(PlayMethod.DIRECT_PLAY)
        assertEquals(2, MpvTracks.audioId(p, 2))
        assertEquals(2, MpvTracks.subtitleId(p, 4))
        assertEquals(3, MpvTracks.subtitleId(p, 5))
        assertEquals(0, MpvTracks.subtitleId(p, -1))
    }

    @Test
    fun `a repackaged stream has its one audio track and only the added files`() {
        val p = plan(PlayMethod.DIRECT_STREAM)
        assertEquals(null, MpvTracks.audioId(p, 2))
        assertEquals(1, MpvTracks.subtitleId(p, 5))
        assertEquals(0, MpvTracks.subtitleId(p, 4))
    }
}
