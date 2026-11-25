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
    private val paths = mutableListOf<String>()
    @Volatile private var played = false

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        synchronized(paths) { paths += "${request.method.value} $path" }
        when {
            path == "/Sessions/Playing/Stopped" -> { delay(400); played = true; respond("", HttpStatusCode.NoContent) }
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

    @Test fun nothingOnItsWayIsNoWait() = runBlocking {
        val c = client()
        val started = System.currentTimeMillis()
        c.stopsSettled()
        assertTrue(System.currentTimeMillis() - started < 200)
    }
}
