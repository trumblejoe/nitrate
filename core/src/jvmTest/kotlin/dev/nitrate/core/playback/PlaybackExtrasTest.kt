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
        val audio = TrackMemory.remember(StreamKind.AUDIO, streams, 3, previous = null)
        val choice = TrackMemory.remember(StreamKind.SUBTITLE, streams, 5, audio)
        assertEquals(LanguageChoice("jpn", "eng", version = TrackMemory.VERSION), choice)
        // Best audio in that language (most channels), full subtitles rather than forced.
        assertEquals(2 to 5, TrackMemory.pick(streams, choice))
        assertEquals(2 to -1, TrackMemory.pick(streams, TrackMemory.remember(StreamKind.SUBTITLE, streams, -1, choice)))
        assertEquals(null to 4, TrackMemory.pick(streams, LanguageChoice(subtitleLanguage = "eng", subtitleForced = true, version = TrackMemory.VERSION)))
        assertEquals(null to null, TrackMemory.pick(streams, LanguageChoice(audioLanguage = "fre", version = TrackMemory.VERSION)))
    }

    @Test
    fun `the same kind of track is found again by its title`() {
        val episode1 = listOf(
            MediaStream(1, StreamKind.AUDIO, language = "eng", title = "English 5.1", channels = 6, isDefault = true),
            MediaStream(2, StreamKind.AUDIO, language = "eng", title = "Director's Commentary", channels = 2),
            MediaStream(3, StreamKind.SUBTITLE, language = "eng", title = "Full"),
            MediaStream(4, StreamKind.SUBTITLE, language = "eng", title = "Signs & Songs"),
        )
        // Next episode: same tracks in another order, titled a little differently.
        val episode2 = listOf(
            MediaStream(1, StreamKind.AUDIO, language = "eng", title = "Commentary with the cast", channels = 2),
            MediaStream(2, StreamKind.AUDIO, language = "eng", title = "English 5.1", channels = 6, isDefault = true),
            MediaStream(3, StreamKind.SUBTITLE, language = "eng", title = "Signs and Songs"),
            MediaStream(4, StreamKind.SUBTITLE, language = "eng", title = "Full"),
        )
        val picked = TrackMemory.remember(StreamKind.SUBTITLE, episode1, 4, TrackMemory.remember(StreamKind.AUDIO, episode1, 2, null))
        assertEquals(1 to 3, TrackMemory.pick(episode2, picked))
        // No commentary in an episode: the best English track, not nothing.
        assertEquals(2 to 3, TrackMemory.pick(episode2.filterNot { it.index == 1 }, picked))
    }

    @Test
    fun `a title starts with the pick, then the server's, then the file's`() {
        val source = dev.nitrate.core.model.MediaSource("s", "mkv", null, streams, defaultAudioIndex = 1, defaultSubtitleIndex = -1, defaultsKnown = true)
        assertEquals(1 to -1, TrackSelection.initial(source, null))
        val jpn = LanguageChoice(audioLanguage = "jpn", version = TrackMemory.VERSION)
        // Audio picked, subtitles left to the server.
        assertEquals(2 to -1, TrackSelection.initial(source, jpn))
        assertEquals("series-1", TrackSelection.memoryKey(dev.nitrate.core.model.MediaItem("e", "Ep", dev.nitrate.core.model.ItemKind.EPISODE, seriesId = "series-1")))
        assertEquals("m", TrackSelection.memoryKey(dev.nitrate.core.model.MediaItem("m", "Film", dev.nitrate.core.model.ItemKind.MOVIE)))
    }

    @Test
    fun `changing one kind of track leaves the other to the server`() {
        // Picking audio with subtitles off (the server's choice) doesn't pin subtitles off.
        assertEquals(2 to null, TrackMemory.pick(streams, TrackMemory.remember(StreamKind.AUDIO, streams, 2, previous = null)))
        // Picking subtitles doesn't pin the audio that happened to be playing.
        assertEquals(null to 5, TrackMemory.pick(streams, TrackMemory.remember(StreamKind.SUBTITLE, streams, 5, previous = null)))
        // Choices saved before this pinned both kinds, so they're left to the server.
        assertEquals(null to null, TrackMemory.pick(streams, LanguageChoice("jpn", subtitlesOff = true)))
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
            ServerEvent.UserDataChanged(listOf("01234567-89ab-cdef-0123-456789abcdef"), mapOf("01234567-89ab-cdef-0123-456789abcdef" to dev.nitrate.core.model.UserState(played = true))),
            event("""{"MessageType":"UserDataChanged","Data":{"UserId":"x","UserDataList":[{"ItemId":"0123456789abcdef0123456789abcdef","Played":true}]}}"""),
        )
        val sync = event("""{"MessageType":"SyncPlayCommand","Data":{"GroupId":"g","Command":"Unpause","When":"2026-11-11T20:00:00.5Z","PositionTicks":20000000,"PlaylistItemId":"p"}}""") as ServerEvent.SyncPlayCommand
        assertEquals(2_000L, sync.command.positionMs)
        assertEquals(java.time.Instant.parse("2026-11-11T20:00:00.5Z").toEpochMilli(), sync.command.whenMs)
        val queue = event("""{"MessageType":"SyncPlayGroupUpdate","Data":{"GroupId":"0123456789abcdef0123456789abcdef","Type":"PlayQueue","Data":{"Reason":"NewPlaylist","Playlist":[{"ItemId":"0123456789abcdef0123456789abcdef","PlaylistItemId":"p1"}],"PlayingItemIndex":0,"StartPositionTicks":50000000,"IsPlaying":true}}}""") as ServerEvent.SyncPlayGroupUpdate
        val q = (queue.update as dev.nitrate.core.syncplay.SyncPlayUpdate.Queue).queue
        assertEquals("01234567-89ab-cdef-0123-456789abcdef", q.currentItemId)
        assertEquals(5_000L, q.startMs)
        assertEquals(true, q.isPlaying && q.changesItem)
        assertEquals(
            dev.nitrate.core.syncplay.SyncPlayUpdate.UserJoined("Sam"),
            (event("""{"MessageType":"SyncPlayGroupUpdate","Data":{"GroupId":"g","Type":"UserJoined","Data":"Sam"}}""") as ServerEvent.SyncPlayGroupUpdate).update,
        )
        assertEquals(null, event("""{"MessageType":"KeepAlive"}"""))
    }
}
