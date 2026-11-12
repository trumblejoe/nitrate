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
            path.endsWith("/Users/Me") || path.endsWith("/Users/$HEX") || path.endsWith("/Users/$DASHED") || path.endsWith("/Users/New") -> USER
            path.endsWith("/Users") -> "[$USER]"
            path.contains("/RemoteSearch/Subtitles/") -> """[{"Id":"os-1","Name":"Nitrate.2023.srt","ThreeLetterISOLanguageName":"eng","ProviderName":"Open Subtitles","Format":"srt","DownloadCount":900,"IsHashMatch":true}]"""
            path.contains("/Items/RemoteSearch/") && !path.contains("/Apply/") -> """[{"Name":"Nitrate","ProductionYear":2023,"ProviderIds":{"Tmdb":"1"},"SearchProviderName":"TheMovieDb","ImageUrl":"http://x/i.jpg"}]"""
            (path.endsWith("/Collections") || path.endsWith("/Playlists")) && request.method.value == "POST" -> """{"Id":"$HEX"}"""
            path.endsWith("/LiveTv/Timers/Defaults") -> """{"ProgramId":"$HEX","ChannelId":"$HEX","PrePaddingSeconds":0}"""
            path.endsWith("/LiveTv/Info") -> """{"IsEnabled":true}"""
            path.endsWith("/LiveTv/Channels") || path.endsWith("/LiveTv/Programs") || path.endsWith("/LiveTv/Recordings") -> """{"Items":[],"TotalRecordCount":0}"""
            path.endsWith("/SyncPlay/List") -> """[{"GroupId":"$HEX","GroupName":"Movie night","State":"Paused","Participants":["joe","sam"],"LastUpdatedAt":"2026-01-01T00:00:00Z"}]"""
            path.endsWith("/GetUtcTime") -> """{"RequestReceptionTime":"2026-01-01T00:00:00.100Z","ResponseTransmissionTime":"2026-01-01T00:00:00.102Z"}"""
            path.endsWith("/Images") -> """[{"ImageType":"Primary","ImageTag":"t","Width":1000,"Height":1500},{"ImageType":"Backdrop","ImageIndex":1,"ImageTag":"b","Width":1920,"Height":1080}]"""
            path.endsWith("/RemoteImages") -> """{"Images":[{"Url":"http://x/a.jpg","ProviderName":"TheMovieDb","Width":1000,"Height":1500,"Language":"en","CommunityRating":7.5}],"TotalRecordCount":1}"""
            path.endsWith("/ScheduledTasks") -> """[{"Id":"t1","Name":"Scan media library","Category":"Library","State":"Running","CurrentProgressPercentage":42.0,"LastExecutionResult":{"Status":"Completed","EndTimeUtc":"2026-01-01T00:00:00Z"}}]"""
            path.endsWith("/ActivityLog/Entries") -> """{"Items":[{"Id":1,"Name":"joe signed in","Type":"SessionStarted","Date":"2026-01-01T00:00:00Z","Severity":"Information"}],"TotalRecordCount":1}"""
            path.endsWith("/Sessions") -> """[{"Id":"s1","UserName":"joe","Client":"Nitrate","DeviceName":"TV","PlayState":{"PositionTicks":600000000,"IsPaused":true,"PlayMethod":"DirectPlay"},"NowPlayingItem":$ITEM,"SupportsRemoteControl":true}]"""
            path.endsWith("/System/Info/Public") -> """{"ServerName":"Home","Version":"10.11.0","Id":"$HEX"}"""
            path.endsWith("/Users/Public") -> """[{"Id":"$HEX","Name":"joe","HasPassword":true,"PrimaryImageTag":"t"}]"""
            path.contains("/Users/AuthenticateBy") || path.contains("/Users/AuthenticateWith") ->
                """{"User":{"Id":"$HEX","Name":"joe"},"AccessToken":"tok"}"""
            path.endsWith("/QuickConnect/Enabled") || path.endsWith("/QuickConnect/Authorize") -> "true"
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
        assertEquals(StoredSession(server, "tok", DASHED, "joe", serverName = "Home"), storage.session)
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

        client.queryItems(
            dev.nitrate.core.pages.ItemQuery(
                libraryId = DASHED, types = listOf(ItemKind.MOVIE, ItemKind.SERIES), genres = listOf("Horror", "Sci-Fi"), tags = listOf("cult"),
                studios = listOf("A24"), personId = DASHED, yearFrom = 1980, yearTo = 1989, minRating = 7f,
                watched = dev.nitrate.core.pages.WatchFilter.UNWATCHED, favoritesOnly = true, resolution = dev.nitrate.core.pages.ResolutionFilter.UHD,
                sort = dev.nitrate.core.pages.QuerySort.CRITIC_RATING,
            ),
        )
        for (sort in dev.nitrate.core.pages.QuerySort.entries) {
            client.queryItems(dev.nitrate.core.pages.ItemQuery(sort = sort, yearFrom = 2000, watched = dev.nitrate.core.pages.WatchFilter.IN_PROGRESS, resolution = dev.nitrate.core.pages.ResolutionFilter.HD))
        }
        client.collectionItems(DASHED)
        client.genres(DASHED)
        client.collections()
        client.playlists()

        val found = client.search("pilot")
        assertEquals(1, found.episodes.size)
        client.itemsWithPerson(DASHED)
        client.mediaSegments(DASHED)
        val trickplay = client.trickplay(DASHED, HEX)!!
        assertEquals(320, trickplay.width)

        // Administration.
        assertTrue(client.account().isAdmin)
        val meta = client.metadata(DASHED)
        client.saveMetadata(
            meta.copy(
                tagline = "New", genres = listOf("Drama"), studios = listOf("A24"), premiereDate = "2024-05-01", lockData = true,
                lockedFields = listOf("Overview"), people = listOf(dev.nitrate.core.admin.PersonCredit("Maya", "Lead")), providerIds = mapOf("Tmdb" to "1"),
            ),
        )
        // The server can't read its own Trickplay field back, so it must not be sent.
        val saved = sent.last { it.method == HttpMethod.Post && it.url.encodedPath.endsWith("/Items/$DASHED") }
        assertTrue(saved.body!!.contains("\"Tagline") || saved.body!!.contains("Taglines"))
        assertTrue("Trickplay" !in saved.body!! && "MediaSources" !in saved.body!!)
        val match = client.remoteSearch(item.copy(kind = ItemKind.MOVIE), "Nitrate", 2023).single()
        assertEquals("TheMovieDb", match.providerName)
        client.applyMatch(DASHED, match, replaceImages = true)
        client.remoteSearch(item.copy(kind = ItemKind.SERIES), "Nitrate", null)
        for (mode in dev.nitrate.core.admin.RefreshMode.entries) client.refreshItem(DASHED, mode, replaceImages = mode == dev.nitrate.core.admin.RefreshMode.REPLACE_ALL, regenerateTrickplay = true)
        assertEquals(2, client.itemImages(DASHED).size)
        assertEquals(1, client.remoteImages(DASHED, dev.nitrate.core.admin.ImageType.PRIMARY).size)
        client.downloadRemoteImage(DASHED, dev.nitrate.core.admin.ImageType.BACKDROP, "http://x/a.jpg")
        client.deleteImage(DASHED, dev.nitrate.core.admin.ImageType.BACKDROP, 1)
        client.deleteImage(DASHED, dev.nitrate.core.admin.ImageType.LOGO, null)
        client.uploadImage(DASHED, dev.nitrate.core.admin.ImageType.PRIMARY, byteArrayOf(1, 2, 3), "image/jpeg")
        client.scanAllLibraries()
        assertEquals(dev.nitrate.core.admin.TaskState.RUNNING, client.tasks().single().state)
        client.startTask("t1")
        client.stopTask("t1")
        assertEquals(1, client.activity(20).size)
        assertEquals(60_000L, client.sessions().single().positionMs)
        client.sendMessage("s1", "Hi", "Dinner's ready")
        for (c in dev.nitrate.core.admin.SessionCommand.entries) client.sessionCommand("s1", c)
        val subs = client.searchSubtitles(DASHED, "eng")
        assertTrue(subs.single().hashMatch)
        client.downloadSubtitle(DASHED, "os-1")
        client.reportCapabilities()
        client.playOn("s1", listOf(DASHED), 60_000)
        client.seekSession("s1", 90_000)
        for (g in dev.nitrate.core.admin.GeneralCommand.entries) client.sendGeneralCommand("s1", g)
        client.authorizeQuickConnect(" 123456 ")
        client.recentEpisodes()
        client.liveTvAvailable()
        client.liveChannels()
        client.guide(listOf(DASHED), 0, 3_600_000)
        client.recordings()
        client.record(DASHED)
        client.cancelRecording("timer-1")
        client.albumTracks(DASHED)
        client.artistAlbums(DASHED)
        client.albumArtists(DASHED, 0, 50)
        client.instantMix(DASHED)
        assertEquals(DASHED, client.createCollection("Heist films", listOf(DASHED)))
        client.addToCollection(DASHED, listOf(DASHED))
        client.removeFromCollection(DASHED, listOf(DASHED))
        assertEquals(DASHED, client.createPlaylist("Road trip", listOf(DASHED)))
        client.renamePlaylist(DASHED, "Long road trip")
        client.addToPlaylist(DASHED, listOf(DASHED))
        client.removeFromPlaylist(DASHED, listOf("entry-1"))
        client.movePlaylistItem(DASHED, "entry-1", 0)
        client.deleteList(DASHED)
        val browse = dev.nitrate.core.pages.BrowseOptions(genres = listOf("Drama"), decade = 1990, watched = dev.nitrate.core.pages.WatchFilter.UNWATCHED).query(DASHED, dev.nitrate.core.model.CollectionKind.MOVIES)
        client.queryPage(browse, 100, 50)
        client.queryPage(dev.nitrate.core.pages.Browse.before(browse, "M")!!, 0, 0)
        client.queryPage(browse.copy(nameFrom = "M"), 0, 10)
        // Codec filters: the codec index (MediaStreams) and the page by ids, for movies and for shows.
        client.queryPage(browse.copy(videoCodecs = listOf("hevc"), audioCodecs = listOf("dts")), 0, 10)
        client.queryPage(dev.nitrate.core.pages.BrowseOptions(videoCodecs = listOf("h264")).query(DASHED, dev.nitrate.core.model.CollectionKind.TV_SHOWS), 0, 10)
        client.followedSeries()
        val group = client.syncPlayGroups().single()
        assertEquals(DASHED, group.id)
        assertEquals(dev.nitrate.core.syncplay.GroupState.PAUSED, group.state)
        client.syncPlayCreate("Movie night")
        client.syncPlayJoin(group.id)
        listOf(
            dev.nitrate.core.syncplay.SyncPlayRequest.SetQueue(listOf(DASHED), 0, 60_000),
            dev.nitrate.core.syncplay.SyncPlayRequest.Pause,
            dev.nitrate.core.syncplay.SyncPlayRequest.Unpause,
            dev.nitrate.core.syncplay.SyncPlayRequest.Stop,
            dev.nitrate.core.syncplay.SyncPlayRequest.Seek(90_000),
            dev.nitrate.core.syncplay.SyncPlayRequest.Status(true, 1_000, 2_000, true, DASHED),
            dev.nitrate.core.syncplay.SyncPlayRequest.Status(false, 1_000, 2_000, false, DASHED),
            dev.nitrate.core.syncplay.SyncPlayRequest.NextItem(DASHED),
            dev.nitrate.core.syncplay.SyncPlayRequest.Ping(40),
        ).forEach { client.syncPlay(it) }
        client.syncPlayLeave()
        assertEquals(2L, client.serverTime().let { it.responseSentMs - it.requestReceivedMs })
        val user = client.users().single()
        assertTrue(user.permissions.isAdministrator)
        client.setPermissions(user.id, user.permissions.copy(enableContentDownloading = false))
        client.createUser("kid", "pw")
        client.deleteUser(user.id)
        client.setPassword(user.id, "new")

        val source = client.mediaSource(DASHED)
        assertEquals(1_000L, source.size)
        client.userData(DASHED)
        client.saveUserData(DASHED, 60_000, played = false, playedAtMs = 1_700_000_000_000)

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
        ) + client.backdropUrls(item) + plan.externalSubtitles.map { it.url }.filter { "/Subtitles/" !in it } + listOf(
            client.downloadUrl(DASHED, source, null, 1, "s1"),
            client.downloadUrl(DASHED, source, dev.nitrate.core.downloads.DownloadQuality.LOW.spec(hevc = true), 1, "s2"),
            client.subtitleFileUrl(DASHED, source, 2, "srt"),
            client.trickplaySheetUrl(DASHED, trickplay, 3),
        )
        assertTrue(urls.size >= 5)
        kotlinx.coroutines.delay(200) // reportStopped is fire-and-forget

        val problems = sent.flatMap { check(it.method, it.url, it.body) } + urls.flatMap { check(HttpMethod.Get, Url(it), null) }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
        val endpoints = sent.map { "${it.method.value} ${template(it.url)}" }.toSet()
        assertTrue("Only ${endpoints.size} endpoints exercised: $endpoints", endpoints.size >= 61)
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
            serializer<UserDataDto>() to "UserItemDataDto",
            serializer<UpdateUserDataDto>() to "UpdateUserItemDataDto",
            serializer<TrickplayInfoDto>() to "TrickplayInfoDto",
            serializer<RemoteSubtitleDto>() to "RemoteSubtitleInfo",
            serializer<CapabilitiesDto>() to "ClientCapabilitiesDto",
            serializer<MediaSegmentDto>() to "MediaSegmentDto",
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
    private fun template(url: Url, method: HttpMethod? = null): String? =
        paths.keys.filter { matches(it, url.encodedPath) && (method == null || paths[it]!!.jsonObject.containsKey(method.value.lowercase())) }
            .minByOrNull { t -> t.count { it == '{' } }

    private fun matches(template: String, path: String): Boolean {
        val a = template.trim('/').split('/')
        val b = path.trim('/').split('/')
        // A segment may mix text and parameters, e.g. "stream.{container}".
        return a.size == b.size && a.zip(b).all { (t, p) ->
            Regex(t.split(Regex("\\{[^}]+}")).joinToString("[^/]+") { Regex.escape(it) }, RegexOption.IGNORE_CASE).matches(p)
        }
    }

    private fun check(method: HttpMethod, url: Url, body: String?): List<String> {
        val where = "${method.value} ${url.encodedPath}"
        val template = template(url, method) ?: template(url) ?: return listOf("$where: no such path")
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
            val content = op["requestBody"]?.jsonObject?.get("content")?.jsonObject
            val schema = content?.get("application/json")?.jsonObject?.get("schema")?.jsonObject
            when {
                content == null -> problems += "$where: endpoint takes no body"
                // Binary bodies (image uploads) only need the endpoint to accept one.
                schema == null -> Unit
                else -> problems += checkValue(where, schema, Json.parseToJsonElement(body))
            }
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
        val USER = """{"Id":"$HEX","Name":"joe","HasPassword":true,"Policy":{"IsAdministrator":true,"EnableContentDownloading":true,"AuthenticationProviderId":"x","PasswordResetProviderId":"y"}}"""
        val ITEM = """{"Id":"$HEX","Name":"Pilot","Type":"Episode","MediaType":"Video","SeriesId":"$HEX","ParentIndexNumber":1,"IndexNumber":1,
            |"RunTimeTicks":36000000000,"ImageTags":{"Primary":"p","Thumb":"t","Logo":"l"},"BackdropImageTags":["b0","b1"],
            |"UserData":{"Played":false,"PlaybackPositionTicks":10000000,"IsFavorite":true},"LocationType":"FileSystem",
            |"Trickplay":{"$HEX":{"160":{"Width":160,"Height":90,"TileWidth":10,"TileHeight":10,"ThumbnailCount":100,"Interval":10000},
            |"320":{"Width":320,"Height":180,"TileWidth":10,"TileHeight":10,"ThumbnailCount":100,"Interval":10000}}},
            |"MediaSources":[{"Id":"$HEX","Container":"mkv","Size":1000,"MediaStreams":[{"Index":0,"Type":"Video","Codec":"h264"}]}]}""".trimMargin()
        val PLAYBACK_INFO = """{"PlaySessionId":"ps","MediaSources":[{"Id":"$HEX","Container":"mkv","Bitrate":8000000,"ETag":"e",
            |"SupportsDirectPlay":true,"SupportsDirectStream":true,"DefaultAudioStreamIndex":1,
            |"MediaStreams":[{"Index":0,"Type":"Video","Codec":"hevc"},{"Index":1,"Type":"Audio","Codec":"eac3","Channels":6},
            |{"Index":2,"Type":"Subtitle","Codec":"srt","IsExternal":true,"DeliveryMethod":"External","DeliveryUrl":"/Videos/$HEX/$HEX/Subtitles/2/0/Stream.srt"}]}]}""".trimMargin()
    }
}
