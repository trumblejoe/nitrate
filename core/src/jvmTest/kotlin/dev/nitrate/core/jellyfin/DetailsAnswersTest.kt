package dev.nitrate.core.jellyfin

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
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
import org.junit.Test

/**
 * What a details page holds (a show's seasons, a season's episodes, a film's collections and what
 * they hold) is kept for hours: opening the page again later asks the server nothing for it, while
 * the watch marks are asked for afresh in one light request.
 */
class DetailsAnswersTest {
    private val server = "http://jf.example:8096"
    private val asked = mutableListOf<String>()
    private var now = 1_800_000_000_000L
    private val film = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val saga = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    private val show = "cccccccccccccccccccccccccccccccc"

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        val shape = when {
            path.endsWith("/Collections") -> "collections"
            path == "/Items" && request.url.parameters["parentId"] != null -> "collection-items"
            path == "/Items" && request.url.parameters["ids"] != null -> "marks"
            path.endsWith("/Seasons") -> "seasons"
            path.endsWith("/Episodes") -> "episodes"
            else -> path
        }
        synchronized(asked) { asked += shape }
        val body = when (shape) {
            "collections" -> """{"Items":[{"Id":"$saga","Name":"The saga","Type":"BoxSet"}]}"""
            "collection-items" -> """{"Items":[{"Id":"$film","Name":"One","Type":"Movie","ProductionYear":2001},{"Id":"dddddddddddddddddddddddddddddddd","Name":"Two","Type":"Movie","ProductionYear":2003}]}"""
            "seasons" -> """{"Items":[{"Id":"eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee","Name":"Season 1","Type":"Season","IndexNumber":1}]}"""
            "episodes" -> """{"Items":[{"Id":"ffffffffffffffffffffffffffffffff","Name":"Pilot","Type":"Episode","IndexNumber":1}]}"""
            else -> """{"Items":[{"Id":"$film","Name":"One","Type":"Movie","UserData":{"Played":true}}]}"""
        }
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private val settings = object : PlaybackSettingsRepository {
        override val settings = MutableStateFlow(PlaybackSettings())
        override suspend fun current() = settings.value
        override suspend fun update(transform: (PlaybackSettings) -> PlaybackSettings) { settings.value = transform(settings.value) }
    }

    private fun client() = JellyfinClient(
        engine, ClientIdentity("Nitrate", "1.0", "dev1", "Test"),
        MemorySessionStorage(listOf(StoredSession(server, "token", "user-1", "sam"))), { caps() }, settings,
    ).also { c -> c.clock = { now }; runBlocking { c.restoreSession() }; synchronized(asked) { asked.clear() } }

    /** What a film page and the player's Next in collection ask about its collections. */
    private suspend fun JellyfinClient.filmPage(): List<List<MediaItem>> = collectionsOf(film).take(5).map { collectionItems(it.id) }

    private suspend fun JellyfinClient.episodePage() {
        val seasons = seasons(show)
        episodes(show, seasons.first().id)
    }

    @Test fun aFilmsCollectionsAreKeptForHours() = runBlocking {
        val c = client()
        c.filmPage()
        assertEquals(listOf("collections", "collection-items"), asked.toList())
        // The player's Next in collection at the credits, and the page again half an hour later.
        c.nextInCollection(MediaItem("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "One", ItemKind.MOVIE, productionYear = 2001))
        now += 30 * 60_000L
        c.filmPage()
        assertEquals(listOf("collections", "collection-items"), asked.toList())
        // Hours later they're asked again.
        now += 6 * 60 * 60_000L
        c.filmPage()
        assertEquals(4, asked.size)
    }

    @Test fun aShowsSeasonsAndEpisodesAreKeptAndMarksAskedAfresh() = runBlocking {
        val c = client()
        c.episodePage()
        now += 30 * 60_000L
        c.episodePage()
        assertEquals(listOf("seasons", "episodes"), asked.toList())
        // Coming back from the player: the marks, asked straight each time (never kept).
        repeat(2) { c.userStates(listOf("ffffffff-ffff-ffff-ffff-ffffffffffff")) }
        assertEquals(listOf("seasons", "episodes", "marks", "marks"), asked.toList())
    }
}
