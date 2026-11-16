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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.TimeSource

/** Home's "Latest in…" rows don't wait for a slow library list once the list has been seen. */
class HomeLatestTest {
    private val server = "http://jf.example:8096"
    private var libraries = """[{"Id":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","Name":"Movies","Type":"CollectionFolder","CollectionType":"movies"}]"""

    private val engine = MockEngine { request ->
        val body = when (request.url.encodedPath) {
            "/UserViews" -> { delay(2_000); """{"Items":$libraries}""" }
            "/Items" -> if (request.url.parameters["parentId"] != null)
                """{"Items":[{"Id":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","Name":"New film","Type":"Movie"}]}""" else """{"Items":[]}"""
            else -> """{"Items":[]}"""
        }
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private val settings = object : PlaybackSettingsRepository {
        override val settings = MutableStateFlow(PlaybackSettings())
        override suspend fun current() = settings.value
        override suspend fun update(transform: (PlaybackSettings) -> PlaybackSettings) { settings.value = transform(settings.value) }
    }

    /** Kept between "app runs", like the device's cache folder. */
    private val cache = object : TextCache {
        val map = mutableMapOf<String, String>()
        override suspend fun get(key: String) = map[key]
        override suspend fun put(key: String, value: String) { map[key] = value }
    }

    private fun client() = JellyfinClient(
        engine, ClientIdentity("Nitrate", "1.0", "dev1", "Test"),
        MemorySessionStorage(listOf(StoredSession(server, "token", "user-1", "sam"))), { caps() }, settings, textCache = cache,
    )

    /** How long until Home has its "Latest in Movies" row. */
    private suspend fun latestAfter(client: JellyfinClient): Long {
        val mark = TimeSource.Monotonic.markNow()
        client.homeRowsProgress().first { p -> p.rows.any { it.key.startsWith("home/latest/") } }
        return mark.elapsedNow().inWholeMilliseconds
    }

    @Test fun latestStartsFromTheSavedLibraryList() = runBlocking {
        val first = client().also { it.restoreSession() }
        // Never seen the list: it has to wait for it.
        val firstMs = latestAfter(first)
        assertTrue("first took $firstMs ms", firstMs >= 2_000)
        // Next time the app runs, the saved list starts the row at once.
        val next = client().also { it.restoreSession() }
        val ms = latestAfter(next)
        assertTrue("took $ms ms", ms < 1_000)
    }

    @Test fun aLibraryGoneSinceLastTimeIsDropped() = runBlocking {
        val first = client().also { it.restoreSession() }
        latestAfter(first)
        libraries = "[]"
        val rows = client().also { it.restoreSession() }.homeRowsProgress().first { it.complete }.rows
        assertEquals(emptyList<String>(), rows.filter { it.key.startsWith("home/latest/") }.map { it.key })
    }
}
