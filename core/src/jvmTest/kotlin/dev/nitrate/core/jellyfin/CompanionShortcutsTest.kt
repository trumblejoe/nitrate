package dev.nitrate.core.jellyfin

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.pages.ItemQuery
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

/** With the Companion plugin, Home is one request, "Because you watched" is the plugin's, and format filters read its index. */
class CompanionShortcutsTest {
    private val server = "http://jf.example:8096"
    private val asked = mutableListOf<String>()
    private var features = """["home","because","formats"]"""

    private fun item(c: Char, name: String) = """{"Id":"${c.toString().repeat(32)}","Name":"$name","Type":"Movie"}"""

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        synchronized(asked) { asked += if (path == "/UserItems/Resume") path + "?" + request.url.parameters["mediaTypes"] else path }
        val body = when (path) {
            "/Nitrate/Companion" -> """{"Version":"1","Features":$features}"""
            "/Nitrate/Home" -> """{"Resume":[${item('a', "Part way")}],"NextUp":[${item('b', "Next")}],"Latest":[],"Because":[{"SeedName":"Dune","Items":[${item('c', "C")},${item('d', "D")},${item('e', "E")}]}]}"""
            "/Nitrate/Formats" -> """[{"Id":"${"f".repeat(32)}","Video":[{"Codec":"hevc","BitDepth":10,"RangeType":"HDR10"}],"Audio":["eac3"]},{"Id":"${"9".repeat(32)}","Video":[{"Codec":"h264","BitDepth":8,"RangeType":"SDR"}],"Audio":["aac"]}]"""
            "/Items" -> if (request.url.parameters["ids"] != null) """{"Items":[${item('f', "HDR film")}]}"""
                else """{"Items":[${item('f', "HDR film")},${item('9', "Plain film")}],"TotalRecordCount":2}"""
            else -> """{"Items":[]}"""
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
    ).also { runBlocking { it.restoreSession() } }

    @Test fun homeIsOneRequest() = runBlocking {
        val rows = client().homeRows()
        assertEquals("Part way", rows.first { it.key == "home/continue_watching" }.items.single().name)
        assertEquals("Next", rows.first { it.key == "home/next_up" }.items.single().name)
        assertEquals("Because you watched Dune", rows.first { it.key == "home/because_you_watched_1" }.title)
        // None of the requests the bundle answers went out on their own.
        // (Continue Listening's audiobooks aren't in it.)
        listOf("/UserItems/Resume?Video", "/Shows/NextUp").forEach { assertTrue("$it asked", it !in asked) }
        assertTrue(asked.none { it.endsWith("/Similar") })
        assertEquals(1, asked.count { it == "/Nitrate/Home" })
    }

    @Test fun withoutThePluginHomeAsksAsBefore() = runBlocking {
        features = "[]"
        client().homeRows()
        assertTrue("/Shows/NextUp" in asked)
        assertTrue("/Nitrate/Home" !in asked)
    }

    @Test fun formatFiltersReadThePluginsIndex() = runBlocking {
        val (page, total) = client().queryPage(ItemQuery(types = listOf(ItemKind.MOVIE), videoRanges = listOf("hdr10")), 0, 20)
        assertEquals(1, total)
        assertEquals("HDR film", page.single().name)
        assertTrue("/Nitrate/Formats" in asked)
    }
}
