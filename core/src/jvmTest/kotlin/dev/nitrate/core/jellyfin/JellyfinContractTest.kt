package dev.nitrate.core.jellyfin

import dev.nitrate.core.LibrarySort
import dev.nitrate.core.model.CollectionKind
import dev.nitrate.core.model.ImageKind
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.PlayMethod
import dev.nitrate.core.playback.PlaybackSettings
import dev.nitrate.core.playback.PlaybackSettingsRepository
import dev.nitrate.core.playback.caps
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks [JellyfinClient] against Jellyfin's OpenAPI description (trimmed by
 * tools/trim-openapi.py): every request it sends must name a real endpoint, query parameter,
 * body property and enum value, and every DTO field it decodes must exist in the response schema.
 * Catches the typos and renames that would otherwise only show up against a real server.
 */
class JellyfinContractTest {
    private val spec = Json.parseToJsonElement(javaClass.getResource("/jellyfin-openapi.json")!!.readText()).jsonObject
    private val schemas = spec["components"]!!.jsonObject["schemas"]!!.jsonObject
    private val paths = spec["paths"]!!.jsonObject

    private data class Sent(val method: HttpMethod, val url: Url, val body: String?)

    private val sent = mutableListOf<Sent>()

    private val engine = MockEngine { request ->
        val body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString()
        sent += Sent(request.method, request.url, body)
        val path = request.url.encodedPath
        val json = when {
            path.endsWith("/System/Info/Public") -> """{"ServerName":"Home","Version":"10.11.0","Id":"$HEX"}"""
            path.endsWith("/Users/Public") -> """[{"Id":"$HEX","Name":"joe","HasPassword":true,"PrimaryImageTag":"t"}]"""
            path.contains("/Users/AuthenticateBy") || path.contains("/Users/AuthenticateWith") ->
                """{"User":{"Id":"$HEX","Name":"joe"},"AccessToken":"tok"}"""
            path.endsWith("/QuickConnect/Enabled") -> "true"
            path.contains("/QuickConnect/") -> """{"Secret":"s","Code":"123456","Authenticated":true}"""
            path.contains("/DisplayPreferences/") -> """{"Id":"x","CustomPrefs":{"a":"1"},"Client":"nitrate","SortBy":"SortName"}"""
            path.endsWith("/Items/Latest") -> "[$ITEM]"
            path.endsWith("/PlaybackInfo") -> PLAYBACK_INFO
            Regex("/Items/[0-9a-f-]+$").containsMatchIn(path) -> ITEM
            path.endsWith("/Users/Me") -> """{"Id":"$HEX","Name":"joe"}"""
            else -> """{"Items":[$ITEM],"TotalRecordCount":1}"""
        }
        respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private val settings = object : PlaybackSettingsRepository {
        override val settings = MutableStateFlow(PlaybackSettings())
        override suspend fun current() = settings.value
        override suspend fun update(transform: (PlaybackSettings) -> PlaybackSettings) {
            settings.value = transform(settings.value)
        }
    }

    private val storage = object : SessionStorage {
        var session: StoredSession? = null
        override suspend fun load() = session
        override suspend fun save(session: StoredSession) {
            this.session = session
        }
        override suspend fun clear() {
            session = null
        }
    }

    private val client = JellyfinClient(engine, ClientIdentity("Nitrate", "1.0", "dev1", "Test TV"), storage, { caps() }, settings)

    @Test
    fun `every request matches the spec`() = runBlocking {
        val server = "http://jf.local:8096"
        assertEquals(server, client.findServer("http://jf.local:8096/").address)
        assertEquals(1, client.publicUsers(server).size)
        assertTrue(client.quickConnectEnabled(server))
        client.startQuickConnect(server)
        assertTrue(client.quickConnectApproved("s"))
        client.finishQuickConnect(server, "s")
        client.login(server, "joe", "pw")
        assertEquals("$DASHED", client.userId)
        assertEquals(StoredSession(server, "tok", DASHED, "joe"), storage.session)
        client.checkSession()

        assertEquals(mapOf("a" to "1"), client.loadCustomPrefs())
        client.saveCustomPrefs(mapOf("b" to "2"))

        client.homeRows()
        for (collection in listOf(CollectionKind.TV_SHOWS, CollectionKind.MOVIES)) {
            val library = MediaItem(id = DASHED, name = "Lib", kind = ItemKind.LIBRARY, collection = collection)
            client.libraryRows(library)
            for (sort in LibrarySort.entries) client.libraryPage(library, sort, 0, 50, includeTags = true)
        }
        val item = client.getItem(DASHED)
        client.seasons(DASHED)
        client.episodes(DASHED, DASHED)
        client.allEpisodes(DASHED)
        client.nextUpFor(DASHED)
        client.similar(DASHED)
        client.playlistItems(DASHED)
        for (on in listOf(true, false)) {
            client.setPlayed(DASHED, on)
            client.setFavorite(DASHED, on)
        }
        client.channelTestClips()

        val plan = client.resolvePlayback(DASHED, isAudio = false, startMs = 1000, forceTranscode = false, audioIndex = 1, subtitleIndex = 2, mediaSourceId = HEX)
        assertEquals(PlayMethod.DIRECT_PLAY, plan.method)
        client.reportStart(plan, 0)
        client.reportProgress(plan, 5000, isPaused = true)
        client.reportStopped(plan, 9000)
        client.logout()

        // Built URLs must be valid GETs too.
        val urls = listOfNotNull(
            plan.url,
            client.imageUrl(item, ImageKind.PRIMARY, 400),
            client.imageUrl(item, ImageKind.THUMB, 400),
            client.backdropUrl(item),
            client.logoUrl(item),
        ) + client.backdropUrls(item) + plan.externalSubtitles.map { it.url }.filter { "/Subtitles/" !in it }
        assertTrue(urls.size >= 5)
        kotlinx.coroutines.delay(200) // reportStopped is fire-and-forget

        val problems = sent.flatMap { check(it.method, it.url, it.body) } + urls.flatMap { check(HttpMethod.Get, Url(it), null) }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
        val endpoints = sent.map { "${it.method.value} ${template(it.url)}" }.toSet()
        assertTrue("Only ${endpoints.size} endpoints exercised: $endpoints", endpoints.size >= 28)
    }

    @Test
    fun `decoded DTO fields exist in response schemas`() {
        val problems = listOf<Pair<KSerializer<*>, String>>(
            serializer<QueryResultDto<ItemDto>>() to "BaseItemDtoQueryResult",
            serializer<ItemDto>() to "BaseItemDto",
            serializer<PlaybackInfoResponseDto>() to "PlaybackInfoResponse",
            serializer<UserDto>() to "UserDto",
            serializer<AuthenticationResultDto>() to "AuthenticationResult",
            serializer<QuickConnectResultDto>() to "QuickConnectResult",
            serializer<PublicSystemInfoDto>() to "PublicSystemInfo",
            // Request bodies, including fields left at their defaults in the request test.
            serializer<PlaybackInfoRequestDto>() to "PlaybackInfoDto",
            serializer<AuthenticateByNameDto>() to "AuthenticateUserByName",
            serializer<QuickConnectDto>() to "QuickConnectDto",
            serializer<PlaybackStartDto>() to "PlaybackStartInfo",
            serializer<PlaybackStopDto>() to "PlaybackStopInfo",
        ).flatMap { (s, schema) -> fields(s.descriptor, schema(schema), schema) }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `ids are normalized to the dashed form`() {
        assertEquals(DASHED, normalizeId(HEX))
        assertEquals(DASHED, normalizeId(DASHED.uppercase()))
        assertEquals("not-an-id", normalizeId("not-an-id"))
    }

    @Test
    fun `address candidates`() {
        assertEquals(listOf("https://jf:8920", "http://jf:8096", "https://jf", "http://jf"), addressCandidates("jf"))
        assertEquals(listOf("https://jf:1234", "http://jf:1234"), addressCandidates("jf:1234/"))
        assertEquals(listOf("http://jf/jellyfin"), addressCandidates(" http://jf/jellyfin/ "))
    }

    @Test
    fun `authorization header`() {
        assertEquals(
            "MediaBrowser Client=\"Nitrate\", Device=\"Test%20TV\", DeviceId=\"dev1\", Version=\"1.0\"",
            client.authorizationHeader(),
        )
    }

    // ---- Spec checks ----

    /** Spec path for a URL, preferring literal segments over parameters (/Items/Latest over /Items/{itemId}). */
    private fun template(url: Url): String? =
        paths.keys.filter { matches(it, url.encodedPath) }.minByOrNull { t -> t.count { it == '{' } }

    private fun matches(template: String, path: String): Boolean {
        val a = template.trim('/').split('/')
        val b = path.trim('/').split('/')
        return a.size == b.size && a.zip(b).all { (t, p) -> (t.startsWith("{") && t.endsWith("}")) || t.equals(p, ignoreCase = true) }
    }

    private fun check(method: HttpMethod, url: Url, body: String?): List<String> {
        val where = "${method.value} ${url.encodedPath}"
        val template = template(url) ?: return listOf("$where: no such path")
        val op = paths[template]!!.jsonObject[method.value.lowercase()]?.jsonObject ?: return listOf("$where: no ${method.value} on $template")
        val params = op["parameters"]?.jsonArray.orEmpty().map { it.jsonObject }.filter { it["in"]?.jsonPrimitive?.content == "query" }
            .associateBy { it["name"]!!.jsonPrimitive.content }
        val problems = mutableListOf<String>()
        for ((name, values) in url.parameters.entries()) {
            val param = params[name]
            if (param == null) {
                problems += "$where: unknown query parameter $name"
                continue
            }
            for (value in values) problems += checkQueryValue("$where?$name", resolve(param["schema"]!!.jsonObject), value)
        }
        if (body != null) {
            val schema = op["requestBody"]?.jsonObject?.get("content")?.jsonObject?.get("application/json")?.jsonObject?.get("schema")?.jsonObject
            if (schema == null) problems += "$where: endpoint takes no body" else problems += checkValue(where, schema, Json.parseToJsonElement(body))
        }
        return problems
    }

    private fun checkQueryValue(where: String, schema: JsonObject, value: String): List<String> {
        val type = schema["type"]?.jsonPrimitive?.content
        if (type == "array") {
            val items = resolve(schema["items"]!!.jsonObject)
            return value.split(',').flatMap { checkQueryValue(where, items, it) }
        }
        val enum = schema["enum"]?.jsonArray?.map { it.jsonPrimitive.content }
        return when {
            enum != null && value !in enum -> listOf("$where: \"$value\" not one of $enum")
            type == "boolean" && value !in setOf("true", "false") -> listOf("$where: \"$value\" is not a boolean")
            type == "integer" && value.toLongOrNull() == null -> listOf("$where: \"$value\" is not an integer")
            else -> emptyList()
        }
    }

    private fun checkValue(where: String, schemaRef: JsonObject, value: JsonElement): List<String> {
        val schema = resolve(schemaRef)
        return when (value) {
            is JsonObject -> {
                val props = properties(schema)
                if (props.isEmpty() && schema["additionalProperties"] != null) {
                    emptyList()
                } else {
                    value.flatMap { (k, v) ->
                        val prop = props[k] ?: return@flatMap if (schema["additionalProperties"] != null) emptyList() else listOf("$where: unknown property $k")
                        checkValue("$where.$k", prop, v)
                    }
                }
            }
            is JsonArray -> schema["items"]?.jsonObject?.let { items -> value.flatMap { checkValue("$where[]", items, it) } }.orEmpty()
            is JsonNull -> emptyList()
            is JsonPrimitive -> {
                val enum = schema["enum"]?.jsonArray?.map { it.jsonPrimitive.content }
                if (enum != null && value.content !in enum) listOf("$where: \"${value.content}\" not one of $enum") else emptyList()
            }
        }
    }

    private fun fields(descriptor: SerialDescriptor, schemaRef: JsonObject, where: String): List<String> {
        val props = properties(resolve(schemaRef))
        return (0 until descriptor.elementsCount).flatMap { i ->
            val name = PascalCase.serialNameForJson(descriptor, i, descriptor.getElementName(i))
            val prop = props[name] ?: return@flatMap listOf("$where: no property $name")
            val element = descriptor.getElementDescriptor(i)
            when (element.kind) {
                StructureKind.CLASS -> fields(element, prop, "$where.$name")
                StructureKind.LIST -> {
                    val inner = element.getElementDescriptor(0)
                    val items = resolve(prop)["items"]?.jsonObject
                    if (inner.kind == StructureKind.CLASS && items != null) fields(inner, items, "$where.$name[]") else emptyList()
                }
                else -> emptyList()
            }
        }
    }

    /** Follows $ref and single-element allOf/oneOf wrappers (how the spec marks nullable refs). */
    private fun resolve(schema: JsonObject): JsonObject {
        schema["\$ref"]?.jsonPrimitive?.content?.let { return resolve(schema(it.substringAfterLast('/'))) }
        for (key in listOf("allOf", "oneOf", "anyOf")) {
            val list = schema[key]?.jsonArray ?: continue
            if (list.size == 1) return resolve(list[0].jsonObject)
        }
        return schema
    }

    private fun schema(name: String) = schemas[name]?.jsonObject ?: error("No schema $name in spec subset")

    private fun properties(schema: JsonObject): Map<String, JsonObject> =
        schema["properties"]?.jsonObject?.mapValues { it.value.jsonObject }.orEmpty()

    private companion object {
        const val HEX = "0123456789abcdef0123456789abcdef"
        const val DASHED = "01234567-89ab-cdef-0123-456789abcdef"
        val ITEM = """{"Id":"$HEX","Name":"Pilot","Type":"Episode","MediaType":"Video","SeriesId":"$HEX","ParentIndexNumber":1,"IndexNumber":1,
            |"RunTimeTicks":36000000000,"ImageTags":{"Primary":"p","Thumb":"t","Logo":"l"},"BackdropImageTags":["b0","b1"],
            |"UserData":{"Played":false,"PlaybackPositionTicks":10000000,"IsFavorite":true},"LocationType":"FileSystem"}""".trimMargin()
        val PLAYBACK_INFO = """{"PlaySessionId":"ps","MediaSources":[{"Id":"$HEX","Container":"mkv","Bitrate":8000000,"ETag":"e",
            |"SupportsDirectPlay":true,"SupportsDirectStream":true,"DefaultAudioStreamIndex":1,
            |"MediaStreams":[{"Index":0,"Type":"Video","Codec":"hevc"},{"Index":1,"Type":"Audio","Codec":"eac3","Channels":6},
            |{"Index":2,"Type":"Subtitle","Codec":"srt","IsExternal":true,"DeliveryMethod":"External","DeliveryUrl":"/Videos/$HEX/$HEX/Subtitles/2/0/Stream.srt"}]}]}""".trimMargin()
    }
}
