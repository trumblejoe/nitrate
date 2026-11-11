package dev.nitrate.core.jellyfin

import dev.nitrate.core.LibrarySort
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.CollectionKind
import dev.nitrate.core.model.DiscoveredServer
import dev.nitrate.core.model.ExternalSubtitle
import dev.nitrate.core.model.ImageKind
import dev.nitrate.core.model.InvalidCredentialsException
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.MediaSegment
import dev.nitrate.core.model.MediaSource
import dev.nitrate.core.model.Person
import dev.nitrate.core.model.PlayMethod
import dev.nitrate.core.model.PlaybackPlan
import dev.nitrate.core.model.QuickConnectRequest
import dev.nitrate.core.model.RemoteUserData
import dev.nitrate.core.model.SearchResults
import dev.nitrate.core.model.SegmentKind
import dev.nitrate.core.model.TrickplayInfo
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.ServerAddress
import dev.nitrate.core.model.ServerUser
import dev.nitrate.core.model.SessionState
import dev.nitrate.core.model.StreamKind
import dev.nitrate.core.model.SubtitleDelivery
import dev.nitrate.core.model.subtitleMimeType
import dev.nitrate.core.admin.AccountInfo
import dev.nitrate.core.admin.ActivityEntry
import dev.nitrate.core.admin.ImageType
import dev.nitrate.core.admin.ItemImage
import dev.nitrate.core.admin.ItemMetadata
import dev.nitrate.core.admin.RefreshMode
import dev.nitrate.core.admin.RemoteImage
import dev.nitrate.core.admin.RemoteMatch
import dev.nitrate.core.admin.ServerSession
import dev.nitrate.core.admin.ServerTask
import dev.nitrate.core.admin.SessionCommand
import dev.nitrate.core.admin.UserAccount
import dev.nitrate.core.admin.UserPermissions
import dev.nitrate.core.downloads.TranscodeSpec
import dev.nitrate.core.pages.ItemQuery
import dev.nitrate.core.pages.QuerySort
import dev.nitrate.core.pages.ResolutionFilter
import dev.nitrate.core.pages.WatchFilter
import dev.nitrate.core.playback.ChannelClip
import dev.nitrate.core.playback.ChannelTest
import dev.nitrate.core.playback.DeviceCapabilities
import dev.nitrate.core.playback.PlaybackSettingsRepository
import dev.nitrate.core.rows.MediaRow
import dev.nitrate.core.rows.RowDisplay
import dev.nitrate.core.rows.RowStyle
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * [MediaServer] for Jellyfin, in common Kotlin over Ktor: the same code serves Android (OkHttp
 * engine) and iOS (Darwin engine). Only the endpoints Nitrate uses are implemented; they're
 * checked against Jellyfin's OpenAPI description in the contract tests.
 */
class JellyfinClient(
    engine: HttpClientEngine,
    private val identity: ClientIdentity,
    private val sessionStorage: SessionStorage,
    private val capabilities: () -> DeviceCapabilities,
    private val playbackSettings: PlaybackSettingsRepository,
) : MediaServer {
    internal val http = HttpClient(engine) {
        expectSuccess = true
        install(ContentNegotiation) { json(JellyfinJson) }
        // Pin the casing: a request with no Accept header gets camelCase from newer servers.
        defaultRequest { header(HttpHeaders.Accept, "application/json; profile=PascalCase") }
        install(HttpTimeout) {
            connectTimeoutMillis = 8_000
            requestTimeoutMillis = 60_000
        }
    }

    /** Outlives screens, for fire-and-forget reports such as playback stopped. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var baseUrl: String? = null
    private var token: String? = null

    override var userId: String? = null
        private set
    override val serverUrl: String? get() = baseUrl

    private fun base() = baseUrl ?: error("No server selected")

    internal fun url(vararg segments: String, query: Map<String, Any?> = emptyMap()): String =
        URLBuilder(base()).apply {
            appendPathSegments(*segments)
            query.forEach { (k, v) -> if (v != null) parameters.append(k, v.toString()) }
        }.buildString()

    internal fun HttpRequestBuilder.auth() {
        header("Authorization", authorizationHeader())
    }

    internal suspend fun get(vararg segments: String, query: Map<String, Any?> = emptyMap()): HttpResponse =
        http.get(url(*segments, query = query)) { auth() }

    private suspend inline fun <reified T> post(vararg segments: String, body: Any? = null, query: Map<String, Any?> = emptyMap()): HttpResponse =
        http.post(url(*segments, query = query)) {
            auth()
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body as T)
            }
        }

    private suspend inline fun <reified T> HttpResponse.decode(): T = JellyfinJson.decodeFromString(bodyAsText())

    // ---- Session ----

    override suspend fun restoreSession(): SessionState {
        val session = sessionStorage.load() ?: return SessionState.NONE
        baseUrl = session.serverUrl
        token = session.accessToken
        userId = session.userId
        return checkSession()
    }

    override suspend fun checkSession(): SessionState = try {
        get("Users", "Me")
        SessionState.VALID
    } catch (e: ClientRequestException) {
        // The server answered: the token was revoked or the user removed.
        if (e.response.status.value == 401 || e.response.status.value == 403) SessionState.EXPIRED else SessionState.UNREACHABLE
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SessionState.UNREACHABLE
    }

    override suspend fun findServer(input: String): ServerAddress = coroutineScope {
        // Probe every candidate at once; take the first that answers, in preference order.
        val probes = addressCandidates(input).map { candidate ->
            async {
                withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                    runCatching {
                        val info = http.get("$candidate/System/Info/Public").bodyAsText().let { JellyfinJson.decodeFromString<PublicSystemInfoDto>(it) }
                        if (info.id != null) ServerAddress(candidate, info.serverName ?: candidate) else null
                    }.getOrNull()
                }
            }
        }
        probes.awaitAll().firstNotNullOfOrNull { it } ?: error("No Jellyfin server found at \"$input\"")
    }

    override fun discoverServers(): Flow<DiscoveredServer> = discoverJellyfinServers()

    override suspend fun publicUsers(serverUrl: String): List<ServerUser> = runCatching {
        selectServer(serverUrl)
        get("Users", "Public").decode<List<UserDto>>().map { u ->
            ServerUser(
                id = normalizeId(u.id),
                name = u.name.orEmpty(),
                hasPassword = u.hasPassword,
                imageUrl = u.primaryImageTag?.let { url("UserImage", query = mapOf("userId" to u.id, "tag" to it)) },
            )
        }
    }.getOrDefault(emptyList())

    private fun selectServer(serverUrl: String) {
        baseUrl = serverUrl.trimEnd('/')
        token = null
    }

    override suspend fun login(serverUrl: String, username: String, password: String) {
        selectServer(serverUrl)
        val result = try {
            post<AuthenticateByNameDto>("Users", "AuthenticateByName", body = AuthenticateByNameDto(username, password)).decode<AuthenticationResultDto>()
        } catch (e: ClientRequestException) {
            if (e.response.status.value == 401) throw InvalidCredentialsException() else throw e
        }
        finishLogin(result)
    }

    override suspend fun quickConnectEnabled(serverUrl: String): Boolean = runCatching {
        selectServer(serverUrl)
        get("QuickConnect", "Enabled").bodyAsText().trim() == "true"
    }.getOrDefault(false)

    override suspend fun startQuickConnect(serverUrl: String): QuickConnectRequest {
        selectServer(serverUrl)
        val result = post<Unit>("QuickConnect", "Initiate").decode<QuickConnectResultDto>()
        return QuickConnectRequest(result.code, result.secret)
    }

    override suspend fun quickConnectApproved(secret: String): Boolean =
        get("QuickConnect", "Connect", query = mapOf("secret" to secret)).decode<QuickConnectResultDto>().authenticated

    override suspend fun finishQuickConnect(serverUrl: String, secret: String) {
        finishLogin(post<QuickConnectDto>("Users", "AuthenticateWithQuickConnect", body = QuickConnectDto(secret)).decode())
    }

    private suspend fun finishLogin(result: AuthenticationResultDto) {
        val accessToken = requireNotNull(result.accessToken) { "Server returned no access token" }
        val user = requireNotNull(result.user) { "Server returned no user" }
        token = accessToken
        userId = normalizeId(user.id)
        sessionStorage.save(StoredSession(base(), accessToken, normalizeId(user.id), user.name.orEmpty()))
    }

    override suspend fun logout() {
        runCatching { post<Unit>("Sessions", "Logout") }
        sessionStorage.clear()
        token = null
    }

    // ---- Per-user storage ----

    override suspend fun loadCustomPrefs(prefsId: String): Map<String, String> {
        val prefs = get("DisplayPreferences", prefsId, query = mapOf("client" to PREFS_CLIENT)).decode<JsonObject>()
        val custom = prefs["CustomPrefs"] as? JsonObject ?: return emptyMap()
        return custom.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.let { k to it.content } }.toMap()
    }

    override suspend fun saveCustomPrefs(prefs: Map<String, String>, prefsId: String) {
        // Round-trip the whole object so fields Nitrate doesn't know about are preserved.
        val current = get("DisplayPreferences", prefsId, query = mapOf("client" to PREFS_CLIENT)).decode<JsonObject>()
        val updated = JsonObject(current + ("CustomPrefs" to JsonObject(prefs.mapValues { JsonPrimitive(it.value) })))
        post<JsonObject>("DisplayPreferences", prefsId, body = updated, query = mapOf("client" to PREFS_CLIENT))
    }

    // ---- Browsing ----

    private suspend fun items(vararg segments: String, query: Map<String, Any?>): List<MediaItem> =
        get(*segments, query = query).decode<QueryResultDto<ItemDto>>().items.map { it.toMediaItem() }

    override suspend fun views(): List<MediaItem> = items("UserViews", query = emptyMap())

    override suspend fun homeRows(): List<MediaRow> {
        val rows = mutableListOf<MediaRow>()
        val views = views()
        if (views.isNotEmpty()) rows += MediaRow("home/my_media", "My Media", views, WIDE_DEFAULT)

        val resume = resumeItems(parentId = null, types = null)
        if (resume.isNotEmpty()) rows += MediaRow("home/continue_watching", "Continue Watching", resume, WIDE_DEFAULT)

        val nextUp = items("Shows", "NextUp", query = mapOf("limit" to ROW_LIMIT, "fields" to ROW_FIELDS))
        if (nextUp.isNotEmpty()) rows += MediaRow("home/next_up", "Next Up", nextUp, WIDE_DEFAULT)

        for (view in views) {
            if (view.collection !in setOf(CollectionKind.MOVIES, CollectionKind.TV_SHOWS, CollectionKind.HOME_VIDEOS, null)) continue
            val latest = latest(view.id)
            if (latest.isNotEmpty()) rows += MediaRow("home/latest/${view.id}", "Latest in ${view.name}", latest, RowDisplay())
        }
        return rows
    }

    private suspend fun latest(parentId: String): List<MediaItem> =
        get("Items", "Latest", query = mapOf("parentId" to parentId, "fields" to ROW_FIELDS, "limit" to ROW_LIMIT))
            .decode<List<ItemDto>>().map { it.toMediaItem() }

    @OptIn(ExperimentalTime::class)
    override suspend fun libraryRows(library: MediaItem): List<MediaRow> {
        val id = library.id
        val prefix = "library/$id"
        val now = Clock.System.now().toString()
        val rows = mutableListOf<MediaRow>()
        fun add(key: String, title: String, items: List<MediaItem>, display: RowDisplay = RowDisplay()) {
            if (items.isNotEmpty()) rows += MediaRow("$prefix/$key", title, items, display)
        }

        when (library.collection) {
            CollectionKind.TV_SHOWS -> {
                add("continue_watching", "Continue Watching", resumeItems(id, "Episode"), WIDE_DEFAULT)
                add("next_up", "Next Up", items("Shows", "NextUp", query = mapOf("parentId" to id, "limit" to ROW_LIMIT, "fields" to ROW_FIELDS)), WIDE_DEFAULT)
                add("recently_added", "Recently Added", latest(id))
                add("recently_released", "Recently Aired", query(id, "Episode", "PremiereDate", maxPremiere = now), WIDE_DEFAULT)
                add("top_unwatched", "Top Unwatched", query(id, "Series", "CommunityRating", filters = "IsUnplayed"))
                add("favorites", "Favorites", query(id, "Series", "SortName", "Ascending", filters = "IsFavorite"))
            }
            else -> {
                val types = if (library.collection == CollectionKind.MOVIES) "Movie" else null
                add("continue_watching", "Continue Watching", resumeItems(id, types), WIDE_DEFAULT)
                add("recently_released", "Recently Released", query(id, types, "PremiereDate", maxPremiere = now))
                add("recently_added", "Recently Added", query(id, types, "DateCreated"))
                add("top_unwatched", "Top Unwatched", query(id, types, "CommunityRating", filters = "IsUnplayed"))
                add("favorites", "Favorites", query(id, types, "SortName", "Ascending", filters = "IsFavorite"))
            }
        }
        return rows
    }

    private suspend fun resumeItems(parentId: String?, types: String?) = items(
        "UserItems", "Resume",
        query = mapOf("parentId" to parentId, "limit" to ROW_LIMIT, "fields" to ROW_FIELDS, "mediaTypes" to "Video", "includeItemTypes" to types),
    )

    private suspend fun query(
        parentId: String,
        types: String?,
        sortBy: String,
        order: String = "Descending",
        filters: String? = null,
        maxPremiere: String? = null,
    ) = items(
        "Items",
        query = mapOf(
            "parentId" to parentId,
            "includeItemTypes" to types,
            "recursive" to true,
            "sortBy" to "$sortBy,SortName",
            "sortOrder" to "$order,Ascending",
            "filters" to filters,
            "maxPremiereDate" to maxPremiere,
            "limit" to ROW_LIMIT,
            "fields" to ROW_FIELDS,
            "enableTotalRecordCount" to false,
        ),
    )

    override suspend fun libraryPage(library: MediaItem, sort: LibrarySort, startIndex: Int, limit: Int, includeTags: Boolean): Pair<List<MediaItem>, Int> {
        val types = when (library.collection) {
            CollectionKind.MOVIES -> "Movie"
            CollectionKind.TV_SHOWS -> "Series"
            CollectionKind.BOX_SETS -> "BoxSet"
            CollectionKind.PLAYLISTS -> "Playlist"
            else -> null
        }
        val (field, order) = when (sort) {
            LibrarySort.NAME -> "SortName" to "Ascending"
            LibrarySort.DATE_ADDED -> "DateCreated" to "Descending"
            LibrarySort.RELEASE_DATE -> "PremiereDate" to "Descending"
            LibrarySort.RATING -> "CommunityRating" to "Descending"
            LibrarySort.RANDOM -> "Random" to "Ascending"
        }
        val result = get(
            "Items",
            query = mapOf(
                "parentId" to library.id,
                "includeItemTypes" to types,
                "recursive" to (types != null),
                "sortBy" to "$field,SortName",
                "sortOrder" to "$order,Ascending",
                "startIndex" to startIndex,
                "limit" to limit,
                "fields" to if (includeTags) "$ROW_FIELDS,Tags" else ROW_FIELDS,
                "enableTotalRecordCount" to true,
            ),
        ).decode<QueryResultDto<ItemDto>>()
        return result.items.map { it.toMediaItem() } to result.totalRecordCount
    }

    override suspend fun getItem(id: String): MediaItem = get("Items", id).decode<ItemDto>().toMediaItem()

    override suspend fun seasons(seriesId: String) = items("Shows", seriesId, "Seasons", query = mapOf("fields" to "Overview"))

    override suspend fun episodes(seriesId: String, seasonId: String) =
        items("Shows", seriesId, "Episodes", query = mapOf("seasonId" to seasonId, "fields" to "Overview,PrimaryImageAspectRatio"))

    override suspend fun allEpisodes(seriesId: String): List<MediaItem> {
        val all = get("Shows", seriesId, "Episodes", query = mapOf("enableUserData" to true)).decode<QueryResultDto<ItemDto>>().items
        // Some servers list season 0 only when asked for it directly.
        val specials = runCatching {
            get("Shows", seriesId, "Episodes", query = mapOf("season" to 0, "enableUserData" to true)).decode<QueryResultDto<ItemDto>>().items
        }.getOrDefault(emptyList())
        // Placeholder "missing" episodes are excluded even if the server creates them.
        return (all + specials).distinctBy { it.id }.filter { it.locationType != "Virtual" }.map { it.toMediaItem() }
    }

    override suspend fun nextUpFor(seriesId: String): MediaItem? =
        items("Shows", "NextUp", query = mapOf("seriesId" to seriesId, "limit" to 1, "enableResumable" to true)).firstOrNull()

    override suspend fun similar(itemId: String) = items("Items", itemId, "Similar", query = mapOf("limit" to 16, "fields" to "PrimaryImageAspectRatio"))

    override suspend fun playlistItems(playlistId: String) =
        items("Playlists", playlistId, "Items", query = mapOf("fields" to "Overview,PrimaryImageAspectRatio,Genres"))

    override suspend fun setPlayed(itemId: String, played: Boolean) {
        if (played) post<Unit>("UserPlayedItems", itemId) else http.delete(url("UserPlayedItems", itemId)) { auth() }
    }

    override suspend fun setFavorite(itemId: String, favorite: Boolean) {
        if (favorite) post<Unit>("UserFavoriteItems", itemId) else http.delete(url("UserFavoriteItems", itemId)) { auth() }
    }

    // ---- Images (public on Jellyfin; tags make them cache-safe) ----

    internal fun image(itemId: String, type: String, tag: String, index: Int? = null, maxWidth: Int? = null, maxHeight: Int? = null): String =
        url(*listOfNotNull("Items", itemId, "Images", type, index?.toString()).toTypedArray(), query = mapOf("maxWidth" to maxWidth, "maxHeight" to maxHeight, "tag" to tag))

    override fun imageUrl(item: MediaItem, kind: ImageKind, maxHeight: Int): String? {
        if (kind == ImageKind.BACKDROP) return backdropUrl(item)
        if (kind == ImageKind.LOGO) return logoUrl(item)
        val type = if (kind == ImageKind.THUMB) "Thumb" else "Primary"
        val tag = if (kind == ImageKind.THUMB) item.images.thumb else item.images.primary
        val series = item.seriesId
        return when {
            tag != null -> image(item.id, type, tag, maxHeight = maxHeight)
            // Episodes without art of their own fall back to the series poster.
            kind == ImageKind.PRIMARY && series != null && item.images.seriesPrimary != null -> image(series, type, item.images.seriesPrimary, maxHeight = maxHeight)
            else -> null
        }
    }

    override fun backdropUrls(item: MediaItem): List<String> {
        if (item.images.backdrops.isNotEmpty()) return item.images.backdrops.mapIndexed { i, tag -> image(item.id, "Backdrop", tag, i, maxWidth = 1920) }
        val parent = item.images.parentBackdropItemId ?: return emptyList()
        return item.images.parentBackdrops.mapIndexed { i, tag -> image(parent, "Backdrop", tag, i, maxWidth = 1920) }
    }

    override fun backdropUrl(item: MediaItem): String? {
        item.images.backdrops.firstOrNull()?.let { return image(item.id, "Backdrop", it, maxWidth = 1920) }
        val parent = item.images.parentBackdropItemId ?: return null
        return item.images.parentBackdrops.firstOrNull()?.let { image(parent, "Backdrop", it, maxWidth = 1920) }
    }

    override fun logoUrl(item: MediaItem): String? {
        item.images.logo?.let { return image(item.id, "Logo", it, maxWidth = 800) }
        val parent = item.images.parentLogoItemId ?: return null
        return item.images.parentLogo?.let { image(parent, "Logo", it, maxWidth = 800) }
    }

    override fun personImageUrl(person: Person): String? = person.imageTag?.let { image(person.id, "Primary", it, maxHeight = 300) }

    // ---- Playback ----

    override fun authorizationHeader(): String = buildString {
        fun param(name: String, value: String) = append(name).append("=\"").append(value.encodeURLParameter()).append('"')
        append("MediaBrowser ")
        param("Client", identity.clientName); append(", ")
        param("Device", identity.deviceName); append(", ")
        param("DeviceId", identity.deviceId); append(", ")
        param("Version", identity.version)
        token?.let { append(", "); param("Token", it) }
    }

    override suspend fun channelTestClips(): List<ChannelClip> {
        val items = get(
            "Items",
            query = mapOf(
                "searchTerm" to ChannelTest.SERVER_TAG,
                "recursive" to true,
                "includeItemTypes" to "Movie,Video,Episode,Audio,MusicVideo",
                "fields" to "MediaSources,MediaStreams",
                "limit" to 50,
            ),
        ).decode<QueryResultDto<ItemDto>>().items
        return items.filter { it.name?.contains(ChannelTest.SERVER_TAG, ignoreCase = true) == true }.map { item ->
            val source = item.mediaSources?.firstOrNull()
            val streams = source?.mediaStreams.orEmpty()
            val audio = streams.firstOrNull { it.index == source?.defaultAudioStreamIndex } ?: streams.firstOrNull { it.type == "Audio" }
            // Always the original file: the point is to send the real bitstream to the receiver.
            val url = streamUrl(normalizeId(item.id), item.type == "Audio", source?.id, null, null)
            ChannelTest.fromServer(normalizeId(item.id), item.name.orEmpty(), url, audio?.channels, audio?.displayTitle ?: audio?.codec?.uppercase() ?: "Unknown audio")
        }
    }

    private fun streamUrl(itemId: String, isAudio: Boolean, mediaSourceId: String?, playSessionId: String?, tag: String?) =
        url(if (isAudio) "Audio" else "Videos", itemId, "stream", query = mapOf("static" to true, "mediaSourceId" to mediaSourceId, "playSessionId" to playSessionId, "tag" to tag))

    override suspend fun resolvePlayback(
        itemId: String,
        isAudio: Boolean,
        startMs: Long,
        forceTranscode: Boolean,
        audioIndex: Int?,
        subtitleIndex: Int?,
        mediaSourceId: String?,
    ): PlaybackPlan {
        val settings = playbackSettings.current()
        val directPlay = !forceTranscode && settings.allowDirectPlay
        val directStream = !forceTranscode && settings.allowDirectStream
        val info = post<PlaybackInfoRequestDto>(
            "Items", itemId, "PlaybackInfo",
            body = PlaybackInfoRequestDto(
                deviceProfile = JellyfinDeviceProfile.create(capabilities(), settings),
                maxStreamingBitrate = settings.maxBitrate,
                startTimeTicks = startMs.msToTicks(),
                audioStreamIndex = audioIndex,
                subtitleStreamIndex = subtitleIndex,
                mediaSourceId = mediaSourceId,
                enableDirectPlay = directPlay,
                enableDirectStream = directStream,
                enableTranscoding = true,
                allowVideoStreamCopy = directStream,
                allowAudioStreamCopy = directStream,
                autoOpenLiveStream = true,
            ),
        ).decode<PlaybackInfoResponseDto>()
        info.errorCode?.let { error("Server can't play this item: $it") }
        val dto = info.mediaSources.firstOrNull() ?: error("Item has no playable media")
        val source = dto.toMediaSource()

        val streams = source.streams
        val chosenAudio = audioIndex ?: dto.defaultAudioStreamIndex ?: streams.firstOrNull { it.kind == StreamKind.AUDIO }?.index
        val chosenSubtitle = subtitleIndex ?: dto.defaultSubtitleStreamIndex ?: -1
        val external = streams
            .filter { it.kind == StreamKind.SUBTITLE && it.delivery == SubtitleDelivery.EXTERNAL && it.deliveryUrl != null }
            .mapNotNull { s ->
                val mime = subtitleMimeType(s.codec) ?: return@mapNotNull null
                ExternalSubtitle(s.index, absoluteUrl(s.deliveryUrl!!), mime, s.language, s.title ?: s.language ?: "Subtitle ${s.index}")
            }

        val transcodingUrl = dto.transcodingUrl
        val (url, method) = when {
            dto.supportsDirectPlay && directPlay -> streamUrl(itemId, isAudio, dto.id, info.playSessionId, dto.eTag) to PlayMethod.DIRECT_PLAY
            transcodingUrl != null -> absoluteUrl(transcodingUrl) to
                if (dto.supportsDirectStream && directStream) PlayMethod.DIRECT_STREAM else PlayMethod.TRANSCODE
            else -> error("Server offered no way to play this item")
        }

        return PlaybackPlan(
            itemId = itemId,
            url = url,
            method = method,
            playSessionId = info.playSessionId,
            source = source,
            isHls = method != PlayMethod.DIRECT_PLAY && dto.transcodingSubProtocol.equals("hls", ignoreCase = true),
            audioIndex = chosenAudio,
            subtitleIndex = chosenSubtitle,
            externalSubtitles = external,
            transcodeReasons = transcodingUrl?.let(::transcodeReasons).orEmpty(),
        )
    }

    // ---- Administration (implemented in JellyfinAdmin.kt) ----

    override suspend fun account(): AccountInfo = admin.account()
    override suspend fun metadata(itemId: String): ItemMetadata = admin.metadata(itemId)
    override suspend fun saveMetadata(metadata: ItemMetadata) = admin.saveMetadata(metadata)
    override suspend fun remoteSearch(item: MediaItem, name: String, year: Int?): List<RemoteMatch> = admin.remoteSearch(item, name, year)
    override suspend fun applyMatch(itemId: String, match: RemoteMatch, replaceImages: Boolean) = admin.applyMatch(itemId, match, replaceImages)
    override suspend fun refreshItem(itemId: String, mode: RefreshMode, replaceImages: Boolean, regenerateTrickplay: Boolean) =
        admin.refreshItem(itemId, mode, replaceImages, regenerateTrickplay)
    override suspend fun itemImages(itemId: String): List<ItemImage> = admin.itemImages(itemId)
    override suspend fun remoteImages(itemId: String, type: ImageType): List<RemoteImage> = admin.remoteImages(itemId, type)
    override suspend fun downloadRemoteImage(itemId: String, type: ImageType, url: String) = admin.downloadRemoteImage(itemId, type, url)
    override suspend fun deleteImage(itemId: String, type: ImageType, index: Int?) = admin.deleteImage(itemId, type, index)
    override suspend fun uploadImage(itemId: String, type: ImageType, bytes: ByteArray, mimeType: String) = admin.uploadImage(itemId, type, bytes, mimeType)
    override suspend fun scanAllLibraries() = admin.scanAll()
    override suspend fun tasks(): List<ServerTask> = admin.tasks()
    override suspend fun startTask(taskId: String) = admin.startTask(taskId)
    override suspend fun stopTask(taskId: String) = admin.stopTask(taskId)
    override suspend fun activity(limit: Int): List<ActivityEntry> = admin.activity(limit)
    override suspend fun sessions(): List<ServerSession> = admin.sessions()
    override suspend fun sendMessage(sessionId: String, header: String, text: String) = admin.sendMessage(sessionId, header, text)
    override suspend fun sessionCommand(sessionId: String, command: SessionCommand) = admin.sessionCommand(sessionId, command)
    override suspend fun users(): List<UserAccount> = admin.users()
    override suspend fun setPermissions(userId: String, permissions: UserPermissions) = admin.setPermissions(userId, permissions)
    override suspend fun createUser(name: String, password: String): UserAccount = admin.createUser(name, password)
    override suspend fun deleteUser(userId: String) = admin.deleteUser(userId)
    override suspend fun setPassword(userId: String, newPassword: String) = admin.setPassword(userId, newPassword)

    private val admin = JellyfinAdmin(this)

    // ---- Custom rows ----

    override suspend fun queryItems(query: ItemQuery): List<MediaItem> = items("Items", query = itemQueryParams(query))

    override suspend fun collectionItems(collectionId: String): List<MediaItem> = items(
        "Items",
        query = mapOf("parentId" to collectionId, "sortBy" to "ProductionYear,SortName", "sortOrder" to "Ascending,Ascending", "fields" to ROW_FIELDS, "limit" to 100),
    )

    override suspend fun genres(libraryId: String?): List<String> =
        get("Genres", query = mapOf("parentId" to libraryId, "sortBy" to "SortName", "sortOrder" to "Ascending", "enableTotalRecordCount" to false))
            .decode<QueryResultDto<ItemDto>>().items.mapNotNull { it.name }

    override suspend fun collections(): List<MediaItem> = listOfType("BoxSet")

    override suspend fun playlists(): List<MediaItem> = listOfType("Playlist")

    private suspend fun listOfType(type: String) =
        items("Items", query = mapOf("includeItemTypes" to type, "recursive" to true, "sortBy" to "SortName", "sortOrder" to "Ascending", "fields" to "PrimaryImageAspectRatio", "limit" to 500))

    // ---- Search ----

    override suspend fun search(query: String): SearchResults = coroutineScope {
        val term = query.trim()
        if (term.isEmpty()) return@coroutineScope SearchResults()
        val items = async {
            items(
                "Items",
                query = mapOf(
                    "searchTerm" to term,
                    "recursive" to true,
                    "includeItemTypes" to "Movie,Series,Episode,BoxSet",
                    "limit" to 60,
                    "fields" to "PrimaryImageAspectRatio",
                    "enableTotalRecordCount" to false,
                ),
            )
        }
        val people = async {
            runCatching {
                get("Persons", query = mapOf("searchTerm" to term, "limit" to 16)).decode<QueryResultDto<ItemDto>>().items
                    .map { Person(normalizeId(it.id), it.name.orEmpty(), null, it.imageTags?.get("Primary")) }
            }.getOrDefault(emptyList())
        }
        val found = items.await()
        SearchResults(
            movies = found.filter { it.kind == ItemKind.MOVIE },
            shows = found.filter { it.kind == ItemKind.SERIES },
            episodes = found.filter { it.kind == ItemKind.EPISODE },
            collections = found.filter { it.kind == ItemKind.BOX_SET },
            people = people.await(),
        )
    }

    override suspend fun itemsWithPerson(personId: String): List<MediaItem> = items(
        "Items",
        query = mapOf(
            "personIds" to personId,
            "recursive" to true,
            "includeItemTypes" to "Movie,Series",
            "sortBy" to "PremiereDate,SortName",
            "sortOrder" to "Descending,Ascending",
            "fields" to ROW_FIELDS,
            "limit" to 100,
        ),
    )

    // ---- Player extras ----

    override suspend fun mediaSegments(itemId: String): List<MediaSegment> = runCatching {
        get("MediaSegments", itemId).decode<QueryResultDto<MediaSegmentDto>>().items.map { s ->
            val kind = when (s.type) {
                "Intro" -> SegmentKind.INTRO
                "Outro" -> SegmentKind.OUTRO
                "Recap" -> SegmentKind.RECAP
                "Preview" -> SegmentKind.PREVIEW
                "Commercial" -> SegmentKind.COMMERCIAL
                else -> SegmentKind.UNKNOWN
            }
            MediaSegment(kind, s.startTicks.ticksToMs(), s.endTicks.ticksToMs())
        }.sortedBy { it.startMs }
    }.getOrDefault(emptyList())

    override suspend fun trickplay(itemId: String, mediaSourceId: String?): TrickplayInfo? = runCatching {
        val bySource = get("Items", itemId).decode<ItemDto>().trickplay ?: return null
        val wanted = mediaSourceId?.replace("-", "")
        val (sourceId, sizes) = bySource.entries.firstOrNull { it.key.replace("-", "").equals(wanted, ignoreCase = true) }?.toPair()
            ?: bySource.entries.firstOrNull()?.toPair() ?: return null
        // The smallest size that still looks sharp in a phone's scrubbing preview.
        val info = sizes.values.filter { it.width >= TRICKPLAY_MIN_WIDTH }.minByOrNull { it.width } ?: sizes.values.maxByOrNull { it.width } ?: return null
        TrickplayInfo(info.width, info.height, info.tileWidth, info.tileHeight, info.thumbnailCount, info.interval.toLong(), sourceId)
    }.getOrNull()

    override fun trickplaySheetUrl(itemId: String, info: TrickplayInfo, sheet: Int): String =
        url("Videos", itemId, "Trickplay", info.width.toString(), "$sheet.jpg", query = mapOf("mediaSourceId" to info.mediaSourceId))

    // ---- Downloads ----

    override suspend fun mediaSource(itemId: String): MediaSource =
        get("Items", itemId).decode<ItemDto>().mediaSources?.firstOrNull()?.toMediaSource() ?: error("Item has no media")

    override fun downloadUrl(itemId: String, source: MediaSource, transcode: TranscodeSpec?, audioIndex: Int?, sessionId: String): String {
        // The original file, with range support so interrupted downloads resume. Honours the
        // user's "allow media downloading" permission on the server.
        if (transcode == null) return url("Items", itemId, "Download")
        return url(
            "Videos", itemId, "stream.mp4",
            query = mapOf(
                "static" to false,
                "mediaSourceId" to source.id,
                "playSessionId" to sessionId,
                "deviceId" to identity.deviceId,
                "videoCodec" to transcode.videoCodec,
                "audioCodec" to "aac",
                "videoBitRate" to transcode.videoBitrate,
                "audioBitRate" to transcode.audioBitrate,
                "audioChannels" to transcode.audioChannels,
                "maxWidth" to transcode.maxWidth,
                "maxHeight" to transcode.maxHeight,
                "audioStreamIndex" to audioIndex,
            ),
        )
    }

    override fun subtitleFileUrl(itemId: String, source: MediaSource, streamIndex: Int, format: String): String =
        url("Videos", itemId, source.id ?: itemId, "Subtitles", streamIndex.toString(), "Stream.$format")

    @OptIn(ExperimentalTime::class)
    override suspend fun userData(itemId: String): RemoteUserData {
        val data = get("UserItems", itemId, "UserData").decode<UserDataDto>()
        return RemoteUserData(
            played = data.played,
            positionMs = data.playbackPositionTicks.ticksToMs(),
            lastPlayedMs = data.lastPlayedDate?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() },
        )
    }

    @OptIn(ExperimentalTime::class)
    override suspend fun saveUserData(itemId: String, positionMs: Long, played: Boolean, playedAtMs: Long) {
        post<UpdateUserDataDto>(
            "UserItems", itemId, "UserData",
            body = UpdateUserDataDto(positionMs.msToTicks(), played, Instant.fromEpochMilliseconds(playedAtMs).toString()),
        )
    }

    private fun absoluteUrl(path: String) = if (path.startsWith("http")) path else base().trimEnd('/') + path

    private fun transcodeReasons(url: String): List<String> =
        Regex("[?&]TranscodeReasons=([^&]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
            ?.replace("%2C", ",", ignoreCase = true)
            ?.split(',')?.filter(String::isNotBlank).orEmpty()

    private fun startInfo(plan: PlaybackPlan, positionMs: Long, isPaused: Boolean) =
        PlaybackStartDto(plan.itemId, plan.playSessionId, plan.mediaSourceId, positionMs.msToTicks(), isPaused = isPaused, playMethod = plan.method.wire())

    override suspend fun reportStart(plan: PlaybackPlan, positionMs: Long) {
        runCatching { post<PlaybackStartDto>("Sessions", "Playing", body = startInfo(plan, positionMs, false)) }
    }

    override suspend fun reportProgress(plan: PlaybackPlan, positionMs: Long, isPaused: Boolean) {
        runCatching { post<PlaybackStartDto>("Sessions", "Playing", "Progress", body = startInfo(plan, positionMs, isPaused)) }
    }

    override fun reportStopped(plan: PlaybackPlan, positionMs: Long) {
        appScope.launch {
            runCatching {
                post<PlaybackStopDto>("Sessions", "Playing", "Stopped", body = PlaybackStopDto(plan.itemId, plan.playSessionId, plan.mediaSourceId, positionMs.msToTicks()))
            }
        }
    }

    internal companion object {
        const val PREFS_CLIENT = "nitrate"
        const val ROW_LIMIT = 24
        const val ROW_FIELDS = "Overview,PrimaryImageAspectRatio,Genres"
        const val PROBE_TIMEOUT_MS = 5_000L
        const val TRICKPLAY_MIN_WIDTH = 240
        val WIDE_DEFAULT = RowDisplay(style = RowStyle.WIDE, showTitles = true)
    }
}

/** Jellyfin /Items parameters for a saved search. */
internal fun itemQueryParams(q: ItemQuery): Map<String, Any?> {
    val types = q.types.mapNotNull {
        when (it) {
            ItemKind.MOVIE -> "Movie"
            ItemKind.SERIES -> "Series"
            ItemKind.EPISODE -> "Episode"
            ItemKind.BOX_SET -> "BoxSet"
            ItemKind.VIDEO -> "Video"
            ItemKind.MUSIC_VIDEO -> "MusicVideo"
            else -> null
        }
    }.ifEmpty { if (q.libraryId == null) listOf("Movie", "Series") else emptyList() }
    val sort = when (q.sort) {
        QuerySort.DATE_ADDED -> "DateCreated"
        QuerySort.RELEASE_DATE -> "PremiereDate"
        QuerySort.NAME -> "SortName"
        QuerySort.RATING -> "CommunityRating"
        QuerySort.CRITIC_RATING -> "CriticRating"
        QuerySort.RUNTIME -> "Runtime"
        QuerySort.DATE_PLAYED -> "DatePlayed"
        QuerySort.RANDOM -> "Random"
    }
    val filters = listOfNotNull(
        when (q.watched) {
            WatchFilter.UNWATCHED -> "IsUnplayed"
            WatchFilter.WATCHED -> "IsPlayed"
            WatchFilter.IN_PROGRESS -> "IsResumable"
            WatchFilter.ANY -> null
        },
        "IsFavorite".takeIf { q.favoritesOnly },
    )
    val years = if (q.yearFrom != null && q.yearTo != null && q.yearTo >= q.yearFrom && q.yearTo - q.yearFrom <= 200) (q.yearFrom..q.yearTo).joinToString(",") else null
    return mapOf(
        "parentId" to q.libraryId,
        "recursive" to true,
        "includeItemTypes" to types.joinToString(",").ifEmpty { null },
        // Jellyfin takes several genres, tags and studios separated by "|".
        "genres" to q.genres.joinToString("|").ifEmpty { null },
        "tags" to q.tags.joinToString("|").ifEmpty { null },
        "studios" to q.studios.joinToString("|").ifEmpty { null },
        "personIds" to q.personId,
        "years" to years,
        "minPremiereDate" to (if (years == null) q.yearFrom?.let { "$it-01-01T00:00:00Z" } else null),
        "maxPremiereDate" to (if (years == null) q.yearTo?.let { "$it-12-31T23:59:59Z" } else null),
        "minCommunityRating" to q.minRating,
        "filters" to filters.joinToString(",").ifEmpty { null },
        "isHd" to (if (q.resolution == ResolutionFilter.HD) true else null),
        "is4K" to (if (q.resolution == ResolutionFilter.UHD) true else null),
        "sortBy" to if (q.sort == QuerySort.RANDOM) sort else "$sort,SortName",
        "sortOrder" to if (q.descending) "Descending,Ascending" else "Ascending,Ascending",
        "limit" to q.limit,
        "fields" to "Overview,PrimaryImageAspectRatio,Genres",
        "enableTotalRecordCount" to false,
    )
}
