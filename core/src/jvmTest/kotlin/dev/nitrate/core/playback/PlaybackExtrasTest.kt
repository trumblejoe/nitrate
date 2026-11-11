package dev.nitrate.core.playback

import dev.nitrate.core.events.ServerEvent
import dev.nitrate.core.jellyfin.parseEvent
import dev.nitrate.core.model.MediaStream
import dev.nitrate.core.model.StreamKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackExtrasTest {
    @Test
    fun `subtitle timestamps shift both ways and never below zero`() {
        val srt = "1\n00:00:01,500 --> 00:00:03,000\nHello\n\n2\n01:59:59,900 --> 02:00:00,100\nBye\n"
        assertEquals("1\n00:00:02,750 --> 00:00:04,250\nHello\n\n2\n02:00:01,150 --> 02:00:01,350\nBye\n", SubtitleShift.shift(srt, 1_250))
        assertEquals("1\n00:00:00,000 --> 00:00:01,000\nHello\n\n2\n01:59:57,900 --> 01:59:58,100\nBye\n", SubtitleShift.shift(srt, -2_000))
        val vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHi\n"
        assertEquals("WEBVTT\n\n00:00:01.500 --> 00:00:02.500\nHi\n", SubtitleShift.shift(vtt, 500))
        // Dialogue that merely looks like a time is left alone.
        assertEquals("00:00:01,000 --> 00:00:02,000\nMeet at 10:00:00,000", SubtitleShift.shift("00:00:00,000 --> 00:00:01,000\nMeet at 10:00:00,000", 1_000))
    }

    private val streams = listOf(
        MediaStream(1, StreamKind.AUDIO, codec = "aac", language = "eng", channels = 2),
        MediaStream(2, StreamKind.AUDIO, codec = "eac3", language = "jpn", channels = 6),
        MediaStream(3, StreamKind.AUDIO, codec = "aac", language = "jpn", channels = 2),
        MediaStream(4, StreamKind.SUBTITLE, codec = "srt", language = "eng", isForced = true),
        MediaStream(5, StreamKind.SUBTITLE, codec = "srt", language = "eng"),
    )

    @Test
    fun `language memory picks the same languages in the next episode`() {
        val choice = TrackMemory.remember(streams, audioIndex = 3, subtitleIndex = 5, previous = null)
        assertEquals(LanguageChoice("jpn", "eng"), choice)
        // Best audio in that language (most channels), full subtitles rather than forced.
        assertEquals(2 to 5, TrackMemory.pick(streams, choice))
        assertEquals(1 to -1, TrackMemory.pick(streams, TrackMemory.remember(streams, 1, -1, choice)))
        assertEquals(null to 4, TrackMemory.pick(streams, LanguageChoice(subtitleLanguage = "eng", subtitleForced = true)))
        assertEquals(null to null, TrackMemory.pick(streams, LanguageChoice(audioLanguage = "fre")))
    }

    private fun event(json: String) = Json.parseToJsonElement(json).jsonObject.let { parseEvent(it["MessageType"].toString().trim('"'), it["Data"]) }

    @Test
    fun `socket messages become events`() {
        assertEquals(
            ServerEvent.Play(listOf("01234567-89ab-cdef-0123-456789abcdef"), 60_000, "PlayNow", null, 3),
            event("""{"MessageType":"Play","Data":{"ItemIds":["0123456789abcdef0123456789abcdef"],"StartPositionTicks":600000000,"PlayCommand":"PlayNow","SubtitleStreamIndex":3}}"""),
        )
        assertEquals(ServerEvent.Playstate("Seek", 1_000), event("""{"MessageType":"Playstate","Data":{"Command":"Seek","SeekPositionTicks":10000000}}"""))
        assertEquals(
            ServerEvent.General("DisplayMessage", mapOf("Header" to "Hi", "Text" to "Dinner")),
            event("""{"MessageType":"GeneralCommand","Data":{"Name":"DisplayMessage","Arguments":{"Header":"Hi","Text":"Dinner"}}}"""),
        )
        assertEquals(
            ServerEvent.UserDataChanged(listOf("01234567-89ab-cdef-0123-456789abcdef")),
            event("""{"MessageType":"UserDataChanged","Data":{"UserId":"x","UserDataList":[{"ItemId":"0123456789abcdef0123456789abcdef","Played":true}]}}"""),
        )
        val sync = event("""{"MessageType":"SyncPlayCommand","Data":{"GroupId":"g","Command":"Unpause","When":"2026-11-11T20:00:00.5Z","PositionTicks":20000000,"PlaylistItemId":"p"}}""") as ServerEvent.SyncPlayCommand
        assertEquals(2_000L, sync.positionMs)
        assertEquals(java.time.Instant.parse("2026-11-11T20:00:00.5Z").toEpochMilli(), sync.whenMs)
        assertEquals(null, event("""{"MessageType":"KeepAlive"}"""))
    }
}
