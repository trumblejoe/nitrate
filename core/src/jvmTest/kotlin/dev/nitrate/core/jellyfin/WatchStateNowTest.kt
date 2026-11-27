package dev.nitrate.core.jellyfin

import dev.nitrate.core.events.ServerEvent
import dev.nitrate.core.model.MediaSource
import dev.nitrate.core.model.PlayMethod
import dev.nitrate.core.model.PlaybackPlan
import dev.nitrate.core.playback.PlaybackSettings
import dev.nitrate.core.playback.PlaybackSettingsRepository
import dev.nitrate.core.playback.caps
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Watch state after a playback or a Watched press: pages wait for the "stopped" report on its way,
 * and the server's own answer for the title is said at once (not its push a moment later).
 */
class WatchStateNowTest {
    private val server = "http://jf.example:8096"
    private val id = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    private val playlist = "cccccccc-cccc-cccc-cccc-cccccccccccc"
    private val paths = mutableListOf<String>()
    @Volatile private var played = false
    @Volatile private var companion = false

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        synchronized(paths) { paths += "${request.method.value} $path" }
        when {
            path == "/Sessions/Playing/Stopped" -> { delay(400); played = true; respond("", HttpStatusCode.NoContent) }
            path == "/Nitrate/Companion" -> respond(
                if (companion) """{"Version":"1","Features":["playlistrecords"]}""" else """{"Version":"1","Features":[]}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
            )
            path == "/Nitrate/Playlists/Records/cccccccccccccccccccccccccccccccc" && request.method.value == "GET" -> respond("", HttpStatusCode.NotFound)
            path == "/Nitrate/Playlists/Records/cccccccccccccccccccccccccccccccc" -> respond(
                """{"PlaylistId":"cccccccc-cccc-cccc-cccc-cccccccccccc","Watched":["bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"],"Resume":{},"LastId":"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb","LastAtMs":5}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
            )
            request.method.value == "POST" -> { played = true; respond("", HttpStatusCode.NoContent) }
            else -> respond(
                """{"Items":[{"Id":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","Name":"Ep","Type":"Episode","UserData":{"Played":$played,"PlaybackPositionTicks":0}}]}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
    }

    private val settings = object : PlaybackSettingsRepository {
        override val settings = MutableStateFlow(PlaybackSettings())
        override suspend fun current() = settings.value
        override suspend fun update(transform: (PlaybackSettings) -> PlaybackSettings) { settings.value = transform(settings.value) }
    }

    private fun client() = JellyfinClient(
        engine, ClientIdentity("Nitrate", "1.0", "dev1", "Test"),
        MemorySessionStorage(listOf(StoredSession(server, "token", "user-1", "sam"))), { caps() }, settings,
    ).also { runBlocking { it.restoreSession() } }

    private val plan = PlaybackPlan(
        itemId = id, url = "u", method = PlayMethod.DIRECT_PLAY, playSessionId = "p",
        source = MediaSource("s", "mkv", bitrate = null, streams = emptyList()), isHls = false,
        audioIndex = null, subtitleIndex = -1, externalSubtitles = emptyList(), transcodeReasons = emptyList(),
    )

    @Test fun aPageWaitsForTheStopAndHearsTheStateAfterIt() = runBlocking {
        val c = client()
        val heard = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { c.watchChanges().first() } }
        c.reportStopped(plan, 60_000)
        // As the player closes, the page reloading waits for the report rather than reading the old state.
        c.stopsSettled()
        val asked = synchronized(paths) { paths.toList() }
        assertTrue("stop reported before the page goes on: $asked", "POST /Sessions/Playing/Stopped" in asked)
        assertTrue("the title's state read after it: $asked", asked.indexOf("GET /Items") > asked.indexOf("POST /Sessions/Playing/Stopped"))
        val e: ServerEvent.UserDataChanged = heard.await()
        assertEquals(listOf(id), e.itemIds)
        assertEquals(true, e.states[id]?.played)
    }

    @Test fun watchedPressedIsSaidAtOnceFromTheServersAnswer() = runBlocking {
        val c = client()
        val heard = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { c.watchChanges().first() } }
        c.setPlayed(id, true)
        assertEquals(true, heard.await().states[id]?.played)
    }

    /** A playlist sitting: once its stop is reported, the user's own state from before is put back, and the stop is told. */
    @Test fun aPlaylistSittingPutsTheUsersOwnStateBack() = runBlocking {
        val c = client()
        val told = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { c.playStops.first() } }
        c.keepOwnState(id, playlist, dev.nitrate.core.model.UserState(played = false, positionMs = 0))
        c.reportStopped(plan, 1_500_000)
        c.stopsSettled()
        val asked = synchronized(paths) { paths.toList() }
        val stop = asked.indexOf("POST /Sessions/Playing/Stopped")
        val putBack = asked.indexOfFirst { it.startsWith("POST /UserItems/") && it.endsWith("/UserData") }
        assertTrue("own state put back after the stop: $asked", stop >= 0 && putBack > stop)
        val e = told.await()
        assertEquals(id, e.itemId)
        assertEquals(1_500_000L, e.positionMs)
        assertEquals(false, e.forGroup)
    }

    /** With the Companion, it keeps the sitting: it puts the own state back and moves the record on, not this app. */
    @Test fun withTheCompanionTheServerKeepsTheSitting() = runBlocking {
        companion = true
        val c = client()
        assertTrue(c.keepsPlaylistRecords())
        val told = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(8_000) { c.playStops.first() } }
        c.keepOwnState(id, playlist, dev.nitrate.core.model.UserState(played = false, positionMs = 0, playCount = 2))
        c.reportStopped(plan, 1_500_000)
        val e = told.await()
        assertEquals(true, e.recordedByServer)
        val asked = synchronized(paths) { paths.toList() }
        assertTrue("the sitting said to the Companion: $asked", "POST /Nitrate/Playlists/Sitting" in asked)
        assertTrue("nothing put back by the app: $asked", asked.none { it.startsWith("POST /UserItems/") })
        // Records: none yet is null; one kept comes back as the server keeps it.
        assertEquals(null, c.playlistRecord(playlist))
        val kept = c.putPlaylistRecord(dev.nitrate.core.playlists.PlaylistRecord(playlist), onlyIfNew = true)
        assertEquals(setOf(id), kept.watched)
        assertEquals(playlist, kept.playlistId)
    }

    @Test fun aStopOnItsOwnPutsNothingBack() = runBlocking {
        val c = client()
        c.reportStopped(plan, 60_000)
        c.stopsSettled()
        val asked = synchronized(paths) { paths.toList() }
        assertTrue("nothing put back: $asked", asked.none { it.endsWith("/UserData") })
    }

    @Test fun nothingOnItsWayIsNoWait() = runBlocking {
        val c = client()
        val started = System.currentTimeMillis()
        c.stopsSettled()
        assertTrue(System.currentTimeMillis() - started < 200)
    }
}
