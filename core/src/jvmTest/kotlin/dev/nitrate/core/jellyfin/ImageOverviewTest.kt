package dev.nitrate.core.jellyfin

import dev.nitrate.core.admin.ImageFlawKind
import dev.nitrate.core.admin.ImageOverviewAsk
import dev.nitrate.core.admin.ImageType
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.playback.PlaybackSettings
import dev.nitrate.core.playback.PlaybackSettingsRepository
import dev.nitrate.core.playback.caps
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Library images overview (Companion feature `imagesurvey`): the guide sent with the ask, the answer read. */
class ImageOverviewTest {
    private var body = ""

    private val engine = MockEngine { request ->
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        when (request.url.encodedPath) {
            "/Nitrate/Images/Overview" -> {
                body = request.body.toByteArray().decodeToString()
                respond(
                    """{"Items":3,"Images":4,"Bytes":5200000,
                        "Types":[{"Type":"Primary","Count":3,"Bytes":5000000,"Small":1,"Misshapen":0,"UnknownSize":0,"NotSaved":0,"Gone":1},{"Type":"Chapter","Count":1,"Bytes":200000}],
                        "Kinds":[{"Kind":"Movie","Items":2,"Complete":1,"Bytes":5000000,"Missing":{"Primary":0,"Backdrop":1,"Logo":2}},{"Kind":"Episode","Items":1,"Complete":0,"Missing":{"Primary":0}}],
                        "Problems":[{"Id":"0f1e2d3c4b5a69788796a5b4c3d2e1f0","Name":"Pilot","Kind":"Episode","SeriesName":"Show","Season":1,"Episode":1,"Missing":[],"Flaws":[{"Type":"Primary","Index":0,"Width":1920,"Height":1080,"Flaw":"gone"}]},
                                    {"Id":"aaaaaaaabbbbccccddddeeeeeeeeeeee","Name":"Beta","Kind":"Movie","Missing":["Logo","Backdrop"],"Flaws":[{"Type":"Primary","Index":0,"Width":600,"Height":900,"Flaw":"small"}]}],
                        "ProblemsTotal":7,
                        "Largest":[{"Id":"aaaaaaaabbbbccccddddeeeeeeeeeeee","Name":"Beta","Kind":"Movie","Type":"Primary","Index":0,"Bytes":4000000,"Width":600,"Height":900}]}""",
                    HttpStatusCode.OK, json,
                )
            }
            else -> respond("", HttpStatusCode.NoContent)
        }
    }

    private val settings = object : PlaybackSettingsRepository {
        override val settings = MutableStateFlow(PlaybackSettings())
        override suspend fun current() = settings.value
        override suspend fun update(transform: (PlaybackSettings) -> PlaybackSettings) { settings.value = transform(settings.value) }
    }

    private fun client() = JellyfinClient(
        engine, ClientIdentity("Nitrate", "1.0", "dev1", "Test"),
        MemorySessionStorage(listOf(StoredSession("http://jf.example:8096", "token", "user1", "sam"))), { caps() }, settings,
    ).also { runBlocking { it.restoreSession() } }

    @Test fun theGuideGoesWithTheAskAndTheAnswerIsRead() = runBlocking {
        val o = client().imageOverview(ImageOverviewAsk("lib1", ImageFlawKind.SMALL, ImageType.PRIMARY, limit = 50))

        val sent = Json.parseToJsonElement(body).jsonObject
        val get = { key: String -> sent.entries.first { it.key.equals(key, ignoreCase = true) }.value }
        assertEquals("lib1", get("libraryId").jsonPrimitive.content)
        assertEquals("small", get("only").jsonPrimitive.content)
        assertEquals("Primary", get("onlyType").jsonPrimitive.content)
        assertEquals(listOf("Primary", "Backdrop", "Logo"), (get("expected") as JsonObject)["Movie"]!!.jsonArray.map { it.jsonPrimitive.content })
        val rules = get("sizes").jsonArray.map { it.jsonObject.entries.associate { (k, v) -> k.lowercase() to v } }
        assertTrue(rules.any { it["type"]?.jsonPrimitive?.content == "Primary" && it["itemkind"]?.jsonPrimitive?.content == "Episode" && it["minwidth"]?.jsonPrimitive?.content == "1280" })

        assertEquals(5_200_000L, o.bytes)
        assertEquals(7, o.problemsTotal)
        assertEquals("Poster", o.types[0].label)
        assertEquals("Chapter", o.types[1].label)
        assertEquals(1, o.gone)
        assertEquals(3, o.missing)
        assertEquals("Films", o.kinds[0].label)
        assertEquals(2, o.kinds[0].missing[ImageType.LOGO])
        val pilot = o.problems[0]
        assertEquals("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0", pilot.itemId)
        assertEquals(ItemKind.EPISODE, pilot.kind)
        assertEquals("Show · S1E1", pilot.title)
        assertEquals("Still: file gone", pilot.line)
        assertEquals("Missing logo, backdrop · Poster small: 600×900", o.problems[1].line)
        assertEquals(4_000_000L, o.largest.single().bytes)
    }

    @Test fun aFixRunOpensEachTitleOnTheKindItNeeds() {
        fun flaw(t: ImageType, k: ImageFlawKind) = dev.nitrate.core.admin.ImageFlaw(t, t.wire, 0, 500, 750, k)
        val problems = listOf(
            dev.nitrate.core.admin.ImageProblem("a", "A", ItemKind.MOVIE, null, null, null, listOf(ImageType.LOGO, ImageType.BACKDROP), listOf(flaw(ImageType.PRIMARY, ImageFlawKind.SMALL))),
            dev.nitrate.core.admin.ImageProblem("b", "B", ItemKind.MOVIE, null, null, null, emptyList(), listOf(flaw(ImageType.PRIMARY, ImageFlawKind.SMALL))),
            dev.nitrate.core.admin.ImageProblem("e", "Pilot", ItemKind.EPISODE, "Show", 1, 1, listOf(ImageType.PRIMARY), emptyList()),
        )
        val run = dev.nitrate.core.admin.ImageFixRun.steps(problems)
        assertEquals(listOf("a" to ImageType.LOGO, "b" to ImageType.PRIMARY, "e" to ImageType.PRIMARY), run.map { it.itemId to it.type })
        assertEquals("Show · S1E1", run[2].title)
        // Filtered by a kind: that kind where the title has a problem with it.
        assertEquals(ImageType.PRIMARY, dev.nitrate.core.admin.ImageFixRun.steps(problems, ImageType.PRIMARY)[0].type)
        assertEquals(listOf("a", "e"), dev.nitrate.core.admin.ImageFixRun.missingIds(problems))
    }
}
