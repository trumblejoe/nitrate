package dev.nitrate.core.jellyfin

import dev.nitrate.core.companion.RestartRefused
import dev.nitrate.core.playback.PlaybackSettings
import dev.nitrate.core.playback.PlaybackSettingsRepository
import dev.nitrate.core.playback.caps
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The Companion's own updates as admins see them: what's waiting, a check now, and a restart refused while something plays. */
class CompanionUpdatesTest {
    private val server = "http://jf.example:8096"
    private var playing = 1
    private val calls = mutableListOf<String>()

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        synchronized(calls) { calls += "${request.method.value} $path" }
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        when {
            path == "/Nitrate/Updates/Restart" && request.method == HttpMethod.Post ->
                if (playing > 0) respond("\"Something is playing; restarting now would stop it.\"", HttpStatusCode.Conflict, json)
                else respond("", HttpStatusCode.Accepted)
            path.startsWith("/Nitrate/Updates") -> respond(
                """{"Running":"0.1.0.71","Waiting":"0.1.0.72","RestartNeeded":true,"Playing":$playing,
                   "LastCheckedAt":"2026-11-23T16:10:00+00:00","LastCheck":"Installed version 0.1.0.72; it starts after a restart.","AutoUpdate":true}""",
                HttpStatusCode.OK, json,
            )
            else -> respond("{}", HttpStatusCode.OK, json)
        }
    }

    private val settings = object : PlaybackSettingsRepository {
        override val settings = MutableStateFlow(PlaybackSettings())
        override suspend fun current() = settings.value
        override suspend fun update(transform: (PlaybackSettings) -> PlaybackSettings) { settings.value = transform(settings.value) }
    }

    private fun client() = JellyfinClient(
        engine, ClientIdentity("Nitrate", "1.0", "dev1", "Test"),
        MemorySessionStorage(listOf(StoredSession(server, "token", "user-1", "admin"))), { caps() }, settings,
    ).also { runBlocking { it.restoreSession() } }

    @Test fun aWaitingUpdateAndItsRestart() = runBlocking {
        val c = client()
        val status = c.checkCompanionUpdates()
        assertEquals("0.1.0.72", status.waiting)
        assertTrue(status.restartNeeded)
        assertEquals(1_795_450_200_000L, status.lastCheckedAtMs)
        // Something is playing: no "Restart now", and the server refuses with its reason.
        assertFalse(status.canRestart)
        assertTrue(status.summary.startsWith("Version 0.1.0.72 is installed and starts after the server restarts"))
        try {
            c.restartForCompanionUpdate(); fail("should be refused")
        } catch (e: RestartRefused) {
            assertEquals("Something is playing; restarting now would stop it.", e.message)
        }
        playing = 0
        assertTrue(c.companionUpdates().canRestart)
        c.restartForCompanionUpdate()
        assertTrue("POST /Nitrate/Updates/Check" in calls)
    }
}
