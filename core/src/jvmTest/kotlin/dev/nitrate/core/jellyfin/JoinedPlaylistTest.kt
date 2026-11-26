package dev.nitrate.core.jellyfin

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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Someone else's playlist in a group the user joins: refused before they're in it (Jellyfin keeps a
 * playlist to its owner until the Companion shares it), read as soon as the groups say they've joined,
 * not half an hour later (a refusal is otherwise remembered so pages don't keep asking).
 */
class JoinedPlaylistTest {
    private val server = "http://jf.example:8096"
    private val playlist = "a".repeat(32)
    private val group = "1".repeat(32)
    @Volatile private var joined = false
    private var itemsAsked = 0

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        when (path) {
            "/Nitrate/Partners" -> respond(
                if (!joined) """{"Groups":[]}"""
                else """{"Groups":[{"Id":"$group","SeriesId":"$playlist","SeriesName":"Marathon","Others":[{"Id":"${"5".repeat(32)}","Name":"Sam"}],"Record":true,"Mine":[]}]}""",
                HttpStatusCode.OK, json,
            )
            "/Playlists/${normalizeId(playlist)}/Items" -> {
                itemsAsked++
                if (joined) respond("""{"Items":[{"Id":"${"b".repeat(32)}","Name":"Pilot","Type":"Movie"}],"TotalRecordCount":1}""", HttpStatusCode.OK, json)
                else respond("Playlist not found", HttpStatusCode.NotFound)
            }
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
        MemorySessionStorage(listOf(StoredSession(server, "token", "user-1", "kim"))), { caps() }, settings,
    ).also { runBlocking { it.restoreSession() } }

    @Test fun aPlaylistRefusedBeforeJoiningIsReadOnceTheGroupsChange() = runBlocking {
        val c = client()
        c.partners()
        assertTrue(runCatching { c.playlistItems(normalizeId(playlist)) }.isFailure)
        // Refused: not asked again straight away.
        assertTrue(runCatching { c.playlistItems(normalizeId(playlist)) }.isFailure)
        assertEquals(1, itemsAsked)
        // Joined (the Companion shares the playlist as the groups are read).
        joined = true
        c.partners()
        assertEquals(listOf("Pilot"), c.playlistItems(normalizeId(playlist)).map { it.name })
    }
}
