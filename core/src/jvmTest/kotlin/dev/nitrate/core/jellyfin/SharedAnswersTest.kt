package dev.nitrate.core.jellyfin

import dev.nitrate.core.diagnostics.QueryOutcome
import dev.nitrate.core.diagnostics.QuerySummary
import dev.nitrate.core.diagnostics.withQueryOrigin
import dev.nitrate.core.playback.PlaybackSettings
import dev.nitrate.core.playback.PlaybackSettingsRepository
import dev.nitrate.core.playback.caps
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Pages asking the same thing share one answer, and the query log says where each request came from. */
class SharedAnswersTest {
    private val server = "http://jf.example:8096"
    private val asked = AtomicInteger()
    private val tags = mutableListOf<String>()
    private val counting = mutableListOf<String?>()
    private val paths = mutableListOf<String>()

    private val engine = MockEngine { request ->
        if (request.url.encodedPath.endsWith("/Similar") || request.url.encodedPath.endsWith("/NextUp")) asked.incrementAndGet()
        request.headers[JellyfinClient.QUERY_TAG_HEADER]?.let { synchronized(tags) { tags += it } }
        synchronized(paths) { paths += request.url.encodedPath }
        if (request.url.encodedPath == "/Items") synchronized(counting) { counting += request.url.parameters["enableTotalRecordCount"] }
        delay(200)
        when {
            request.method.value == "POST" -> respond("", HttpStatusCode.NoContent)
            else -> respond("""{"Items":[{"Id":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","Name":"A film","Type":"Movie"}]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
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

    private suspend fun JellyfinClient.nextUp(c: JellyfinClient) = c.moreOfRow(dev.nitrate.core.rows.RowMore(listOf("Shows", "NextUp"), emptyMap()), 0, 10)

    @Test fun sameQuestionAtOnceIsAskedOnce() = runBlocking {
        val c = client()
        val id = "cccccccccccccccccccccccccccccccc"
        listOf("Home", "Films").map { page -> async { withQueryOrigin(page) { c.similar(id) } } }.awaitAll()
        assertEquals(1, asked.get())
        // Later askers get the kept answer.
        withQueryOrigin("Details") { c.similar(id) }
        assertEquals(1, asked.get())

        val similar = c.queryLog.records.value.filter { it.endpoint == "/Items/{id}/Similar" }
        assertEquals(listOf(QueryOutcome.FETCHED, QueryOutcome.JOINED, QueryOutcome.KEPT), similar.map { it.outcome }.sortedBy { it.ordinal })
        assertEquals(setOf("Home", "Films", "Details"), similar.map { it.origin }.toSet())
        assertTrue(similar.first { it.outcome == QueryOutcome.FETCHED }.hints.any { it.startsWith("Similar titles") })
        // The server was told each request's tag.
        assertTrue(tags.any { it == similar.first { r -> r.outcome == QueryOutcome.FETCHED }.tag })
    }

    @Test fun watchStateChangesLetWatchingAnswersGo() = runBlocking {
        val c = client()
        c.nextUp(c)
        c.nextUp(c)
        assertEquals(1, asked.get())
        // Anything sent to the server (a watched mark, a progress report) lets Next Up go.
        c.setPlayed("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", true)
        c.nextUp(c)
        assertEquals(2, asked.get())
    }

    @Test fun nestedOriginsAndSummary() = runBlocking {
        val c = client()
        withQueryOrigin("Home") { withQueryOrigin("Next Up") { c.nextUp(c) } }
        val r = c.queryLog.records.value.single { it.endpoint == "/Shows/NextUp" }
        assertEquals("Home › Next Up", r.origin)
        val summary = QuerySummary.of(c.queryLog.records.value)
        assertEquals(1, summary.groups.single { it.endpoint == "/Shows/NextUp" }.fetched)
        assertTrue(summary.render("test").contains("/Shows/NextUp"))
    }

    /** An app row on a custom page asks nothing more of the server than Home already did. */
    @Test fun appRowSharesHomesAnswer() = runBlocking {
        val c = client()
        listOf(
            async { withQueryOrigin("Home") { c.homeRowsProgress(emptySet()).collect {} } },
            async { withQueryOrigin("Custom page") { c.appRow(dev.nitrate.core.rows.AppRows.NEXT_UP) } },
        ).awaitAll()
        val nextUps = c.queryLog.records.value.filter { it.endpoint == "/Shows/NextUp" }
        assertEquals(1, nextUps.count { it.outcome == QueryOutcome.FETCHED })
        // Whichever asked first fetched; the other shared it.
        assertEquals(2, nextUps.size)
        assertEquals(setOf("Home › Next Up", "Custom page"), nextUps.map { it.origin }.toSet())
    }

    /** A watched mark changing elsewhere fixes the kept list in place: nothing asked again. */
    @Test fun watchStateIsPatchedIntoKeptLists() = runBlocking {
        val c = client()
        val id = "cccccccccccccccccccccccccccccccc"
        val before = c.similar(id).single()
        assertTrue(!before.user.played)
        c.nextUp(c)
        c.watchStateChanged(mapOf(before.id to dev.nitrate.core.model.UserState(played = true)))
        val after = c.similar(id).single()
        assertTrue(after.user.played)
        assertEquals(1, c.queryLog.records.value.count { it.endpoint == "/Items/{id}/Similar" && it.outcome == QueryOutcome.FETCHED })
        // What's next does depend on it: asked again.
        c.nextUp(c)
        assertEquals(2, c.queryLog.records.value.count { it.endpoint == "/Shows/NextUp" && it.outcome == QueryOutcome.FETCHED })
    }

    /** Lists never shown with a total don't make the server count every match. */
    @Test fun rowListsDontCount() = runBlocking {
        val c = client()
        c.homeRowsProgress(emptySet()).collect {}
        assertTrue(counting.isNotEmpty())
        assertTrue(counting.toString(), counting.all { it == "false" })
    }

    @Test fun userDataEventsCarryTheStates() {
        val data = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"UserId":"u","UserDataList":[{"ItemId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","Played":true,"PlaybackPositionTicks":600000000,"IsFavorite":true,"PlayCount":2}]}""",
        )
        val e = parseEvent("UserDataChanged", data) as dev.nitrate.core.events.ServerEvent.UserDataChanged
        val s = e.states.values.single()
        assertTrue(s.played && s.isFavorite)
        assertEquals(60_000L, s.positionMs)
        assertEquals(2, s.playCount)
    }

    @Test fun aSearchAgainAsksNothingAndPeopleComeWithTheQuickMatches() = runBlocking {
        val c = client()
        withQueryOrigin("Search") { c.searchHints("hunger"); c.search("hunger") }
        // Back to Search, with a stray space: the same search.
        withQueryOrigin("Search") { c.searchHints("hunger "); c.search("hunger ") }
        assertEquals(1, paths.count { it == "/Search/Hints" })
        assertEquals(1, paths.count { it == "/Items" })
        assertEquals(0, paths.count { it == "/Persons" })
    }

    @Test fun onlyTheFirstPageIsCounted() = runBlocking {
        val c = client()
        val query = dev.nitrate.core.pages.ItemQuery(types = listOf(dev.nitrate.core.model.ItemKind.MOVIE))
        val (_, total) = c.queryPage(query, 0, 1)
        val (_, later) = c.queryPage(query, 1, 1)
        assertEquals(listOf("true", "false"), counting)
        // The later page is full: "keep the total you had".
        assertEquals(-1, later)
        // A short page (one title of five asked, from the third) says where the list ends.
        assertEquals(3, c.queryPage(query, 2, 5).second)
        assertTrue(total >= 0)
    }
}
