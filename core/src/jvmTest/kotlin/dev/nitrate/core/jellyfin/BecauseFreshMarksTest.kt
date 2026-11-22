package dev.nitrate.core.jellyfin

import dev.nitrate.core.cache.TextCache
import dev.nitrate.core.playback.PlaybackSettings
import dev.nitrate.core.playback.PlaybackSettingsRepository
import dev.nitrate.core.playback.caps
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Because you watched" rows kept from earlier show today's watched marks, not the day-old ones. */
class BecauseFreshMarksTest {
    private val server = "http://jf.example:8096"
    private val a = "a".repeat(32)
    private var aPlayed = false
    private var similarAsked = 0

    private fun item(id: String, name: String, played: Boolean) = """{"Id":"$id","Name":"$name","Type":"Movie","UserData":{"Played":$played}}"""

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        val p = request.url.parameters
        val body = when {
            path.endsWith("/Similar") -> { similarAsked++; """{"Items":[${item(a, "A", false)},${item("b".repeat(32), "B", false)},${item("c".repeat(32), "C", false)}]}""" }
            path == "/Items" && p["sortBy"]?.contains("DatePlayed") == true -> """{"Items":[${item("d".repeat(32), "Seed", true)}]}"""
            path == "/Items" && p["ids"] != null -> """{"Items":[${item(a, "A", aPlayed)}]}"""
            else -> """{"Items":[]}"""
        }
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private val settings = object : PlaybackSettingsRepository {
        override val settings = MutableStateFlow(PlaybackSettings())
        override suspend fun current() = settings.value
        override suspend fun update(transform: (PlaybackSettings) -> PlaybackSettings) { settings.value = transform(settings.value) }
    }

    private val cache = object : TextCache {
        val map = mutableMapOf<String, String>()
        override suspend fun get(key: String) = map[key]
        override suspend fun put(key: String, value: String) { map[key] = value }
        override suspend fun remove(key: String) { map.remove(key) }
    }

    private fun client() = JellyfinClient(
        engine, ClientIdentity("Nitrate", "1.0", "dev1", "Test"),
        MemorySessionStorage(listOf(StoredSession(server, "token", "user-1", "sam"))), { caps() }, settings, textCache = cache,
    ).also { runBlocking { it.restoreSession() } }

    private suspend fun JellyfinClient.cardA() =
        homeRows().first { it.key.startsWith("home/because_you_watched") }.items.first { it.id.replace("-", "") == a }

    @Test fun keptRowsGetFreshWatchedMarks() = runBlocking {
        assertFalse(client().cardA().user.played)
        val asked = similarAsked
        aPlayed = true
        // Next app run: the rows come from the device, only the marks are asked for.
        val next = client()
        assertTrue(next.cardA().user.played)
        assertEquals(asked, similarAsked)
    }

    @Test fun forgettingTheAccountDropsWhatWasRemembered() = runBlocking {
        val c = client()
        c.cardA()
        assertTrue(cache.map.keys.any { it.startsWith("because/") })
        c.logout()
        assertTrue(cache.map.keys.none { it.startsWith("because/") || it.startsWith("views/") })
    }
}
