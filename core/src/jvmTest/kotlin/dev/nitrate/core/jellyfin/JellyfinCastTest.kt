package dev.nitrate.core.jellyfin

import dev.nitrate.core.cast.CastCommand
import dev.nitrate.core.cast.CastPlay
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JellyfinCastTest {
    private val signIn = CastSignIn("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "dev-1", "tok", "http://192.168.1.20:8096/", "srv")
    private val film = MediaItem("11111111-2222-3333-4444-555555555555", "Nitrate", ItemKind.MOVIE, runtimeMs = 6_000_000)

    @Test fun playNowCarriesTheItemsTheStartAndTheSignIn() {
        val m = JellyfinJson.parseToJsonElement(JellyfinCast.play(signIn, CastPlay(listOf(film), startMs = 90_000, audioIndex = 2, subtitleIndex = -1))).jsonObject
        assertEquals("PlayNow", m["command"]!!.jsonPrimitive.content)
        assertEquals("aaaaaaaabbbbccccddddeeeeeeeeeeee", m["userId"]!!.jsonPrimitive.content)
        assertEquals("tok", m["accessToken"]!!.jsonPrimitive.content)
        assertEquals("http://192.168.1.20:8096", m["serverAddress"]!!.jsonPrimitive.content)
        val o = m["options"]!!.jsonObject
        assertEquals(900_000_000L, o["startPositionTicks"]!!.jsonPrimitive.long)
        assertEquals(2, o["audioStreamIndex"]!!.jsonPrimitive.int)
        assertEquals(-1, o["subtitleStreamIndex"]!!.jsonPrimitive.int)
        val item = o["items"]!!.jsonArray.single().jsonObject
        assertEquals("11111111222233334444555555555555", item["Id"]!!.jsonPrimitive.content)
        assertEquals("Movie", item["Type"]!!.jsonPrimitive.content)
        assertEquals("Video", item["MediaType"]!!.jsonPrimitive.content)
    }

    @Test fun songsAreAudioAndSeeksAreInTicks() {
        val song = MediaItem("s1", "Song", ItemKind.AUDIO)
        val m = JellyfinJson.parseToJsonElement(JellyfinCast.play(signIn, CastPlay(listOf(song, song.copy(id = "s2")), startIndex = 1))).jsonObject
        val o = m["options"]!!.jsonObject
        assertEquals(1, o["startIndex"]!!.jsonPrimitive.int)
        assertEquals("Audio", o["items"]!!.jsonArray[0].jsonObject["MediaType"]!!.jsonPrimitive.content)
        val seek = JellyfinJson.parseToJsonElement(JellyfinCast.command(signIn, CastCommand.SEEK, 1_500)).jsonObject
        assertEquals("Seek", seek["command"]!!.jsonPrimitive.content)
        assertEquals(15_000_000L, seek["options"]!!.jsonObject["position"]!!.jsonPrimitive.long)
    }

    @Test fun theReceiversProgressIsRead() {
        val s = JellyfinCast.status(
            """{"type":"playbackprogress","data":{"ItemId":"11111111222233334444555555555555","PlayState":{"PositionTicks":600000000,"IsPaused":true},
               "NowPlayingItem":{"Id":"11111111222233334444555555555555","Name":"Pilot","SeriesName":"The Night Reel","RunTimeTicks":27000000000}}}""",
        )!!
        assertEquals(film.id, s.itemId)
        assertEquals("The Night Reel · Pilot", s.title)
        assertEquals(60_000, s.positionMs)
        assertEquals(2_700_000, s.durationMs)
        assertTrue(s.paused)
        assertEquals(60_000, s.positionAt(5_000)) // paused: it stays put
        assertTrue(JellyfinCast.status("""{"type":"playbackstop","data":{}}""")!!.stopped)
        assertNull(JellyfinCast.status("""{"type":"volumechange","data":{}}"""))
        assertNull(JellyfinCast.status("not json"))
    }
}
