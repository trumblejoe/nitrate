package dev.nitrate.android.jellyfin

import dev.nitrate.android.storage.Session
import dev.nitrate.android.storage.SessionStore
import dev.nitrate.core.LibrarySort
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.CollectionKind
import dev.nitrate.core.model.DiscoveredServer
import dev.nitrate.core.model.ExternalSubtitle
import dev.nitrate.core.model.ImageKind
import dev.nitrate.core.model.InvalidCredentialsException
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.Person
import dev.nitrate.core.model.PlayMethod
import dev.nitrate.core.model.PlaybackPlan
import dev.nitrate.core.model.QuickConnectRequest
import dev.nitrate.core.model.ServerAddress
import dev.nitrate.core.model.ServerUser
import dev.nitrate.core.model.SessionState
import dev.nitrate.core.model.StreamKind
import dev.nitrate.core.model.SubtitleDelivery
import dev.nitrate.core.playback.ChannelClip
import dev.nitrate.core.playback.ChannelTest
import dev.nitrate.core.playback.DeviceCapabilities
import dev.nitrate.core.playback.PlaybackSettingsRepository
import dev.nitrate.core.rows.MediaRow
import dev.nitrate.core.rows.RowDisplay
import dev.nitrate.core.rows.RowStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.audioApi
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.authenticateWithQuickConnect
import org.jellyfin.sdk.api.client.extensions.authenticationApi
import org.jellyfin.sdk.api.client.extensions.displayPreferenceApi
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.playlistApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.showApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.api.client.extensions.userViewApi
import org.jellyfin.sdk.api.client.extensions.videoApi
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.discovery.RecommendedServerInfoScore
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.LocationType
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import java.time.LocalDateTime
import java.util.UUID

/** [MediaServer] backed by the Jellyfin Kotlin SDK (Android and JVM only). */
class JellyfinServer(
    private val jellyfin: Jellyfin,
    private val sessionStore: SessionStore,
    private val capabilities: () -> DeviceCapabilities,
    private val playbackSettings: PlaybackSettingsRepository,
) : MediaServer {
    private val api: ApiClient = jellyfin.createApi()

    /** Outlives screens, for fire-and-forget reports such as playback stopped. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override var userId: String? = null
        private set

    override var serverUrl: String? = null
        private set

    private fun uuid(id: String) = UUID.fromString(id)

    // ---- Session ----

    override suspend fun restoreSession(): SessionState {
        val session = sessionStore.load() ?: return SessionState.NONE
        api.update(baseUrl = session.serverUrl, accessToken = session.accessToken)
        userId = session.userId
        serverUrl = session.serverUrl
        return checkSession()
    }

    override suspend fun checkSession(): SessionState = try {
        api.userApi.getCurrentUser()
        SessionState.VALID
    } catch (e: InvalidStatusException) {
        // The server answered: the token was revoked or the user removed.
        if (e.status == 401 || e.status == 403) SessionState.EXPIRED else SessionState.UNREACHABLE
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SessionState.UNREACHABLE
    }

    override suspend fun findServer(input: String): ServerAddress {
        val best = jellyfin.discovery.getRecommendedServers(input, RecommendedServerInfoScore.OK).maxByOrNull { it.score }
            ?: error("No Jellyfin server found at \"$input\"")
        return ServerAddress(best.address, best.systemInfo.getOrNull()?.serverName ?: best.address)
    }

    override fun discoverServers(): Flow<DiscoveredServer> = jellyfin.discovery.discoverLocalServers().map {
        DiscoveredServer(it.id.orEmpty(), it.name ?: "Jellyfin", it.address.orEmpty())
    }

    override suspend fun publicUsers(serverUrl: String): List<ServerUser> = runCatching {
        api.update(baseUrl = serverUrl, accessToken = null)
        api.userApi.getPublicUsers().content.map { user ->
            ServerUser(
                id = user.id.toString(),
                name = user.name.orEmpty(),
                hasPassword = user.hasPassword != false,
                imageUrl = user.primaryImageTag?.let { api.imageApi.getUserImageUrl(userId = user.id, tag = it) },
            )
        }
    }.getOrDefault(emptyList())

    override suspend fun login(serverUrl: String, username: String, password: String) {
        api.update(baseUrl = serverUrl, accessToken = null)
        val result = try {
            api.authenticationApi.authenticateUserByName(username = username, password = password).content
        } catch (e: InvalidStatusException) {
            if (e.status == 401) throw InvalidCredentialsException() else throw e
        }
        finishLogin(serverUrl, result)
    }

    override suspend fun quickConnectEnabled(serverUrl: String): Boolean = runCatching {
        api.update(baseUrl = serverUrl, accessToken = null)
        api.authenticationApi.getQuickConnectEnabled().content
    }.getOrDefault(false)

    override suspend fun startQuickConnect(serverUrl: String): QuickConnectRequest {
        api.update(baseUrl = serverUrl, accessToken = null)
        val result = api.authenticationApi.initiateQuickConnect().content
        return QuickConnectRequest(result.code, result.secret)
    }

    override suspend fun quickConnectApproved(secret: String): Boolean =
        api.authenticationApi.getQuickConnectState(secret = secret).content.authenticated

    override suspend fun finishQuickConnect(serverUrl: String, secret: String) {
        finishLogin(serverUrl, api.authenticationApi.authenticateWithQuickConnect(secret).content)
    }

    private suspend fun finishLogin(serverUrl: String, result: AuthenticationResult) {
        val token = requireNotNull(result.accessToken) { "Server returned no access token" }
        val user = requireNotNull(result.user) { "Server returned no user" }
        api.update(baseUrl = serverUrl, accessToken = token)
        userId = user.id.toString()
        this.serverUrl = serverUrl
        sessionStore.save(Session(serverUrl = serverUrl, accessToken = token, userId = user.id.toString(), userName = user.name.orEmpty()))
    }

    override suspend fun logout() {
        runCatching { api.sessionApi.reportSessionEnded() }
        sessionStore.clear()
        api.update(accessToken = null)
    }

    // ---- Per-user storage ----

    override suspend fun loadCustomPrefs(prefsId: String): Map<String, String> =
        api.displayPreferenceApi.getDisplayPreferences(displayPreferencesId = prefsId, client = PREFS_CLIENT).content.customPrefs
            .orEmpty().mapNotNull { (k, v) -> v?.let { k to it } }.toMap()

    override suspend fun saveCustomPrefs(prefs: Map<String, String>, prefsId: String) {
        val current = api.displayPreferenceApi.getDisplayPreferences(displayPreferencesId = prefsId, client = PREFS_CLIENT).content
        api.displayPreferenceApi.updateDisplayPreferences(displayPreferencesId = prefsId, client = PREFS_CLIENT, data = current.copy(customPrefs = prefs))
    }

    // ---- Browsing ----

    override suspend fun views(): List<MediaItem> = api.userViewApi.getUserViews().content.items.map { it.toMediaItem() }

    override suspend fun homeRows(): List<MediaRow> {
        val rows = mutableListOf<MediaRow>()
        val views = views()
        if (views.isNotEmpty()) rows += MediaRow("home/my_media", "My Media", views, WIDE_DEFAULT)

        val resume = resumeItems(parentId = null, types = null)
        if (resume.isNotEmpty()) rows += MediaRow("home/continue_watching", "Continue Watching", resume, WIDE_DEFAULT)

        val nextUp = api.showApi.getNextUp(limit = ROW_LIMIT, fields = ROW_FIELDS).content.items.map { it.toMediaItem() }
        if (nextUp.isNotEmpty()) rows += MediaRow("home/next_up", "Next Up", nextUp, WIDE_DEFAULT)

        for (view in views) {
            if (view.collection !in setOf(CollectionKind.MOVIES, CollectionKind.TV_SHOWS, CollectionKind.HOME_VIDEOS, null)) continue
            val latest = api.libraryApi.getLatestMedia(parentId = uuid(view.id), fields = ROW_FIELDS, limit = ROW_LIMIT).content.map { it.toMediaItem() }
            if (latest.isNotEmpty()) rows += MediaRow("home/latest/${view.id}", "Latest in ${view.name}", latest, RowDisplay())
        }
        return rows
    }

    override suspend fun libraryRows(library: MediaItem): List<MediaRow> {
        val id = uuid(library.id)
        val prefix = "library/${library.id}"
        val now = LocalDateTime.now()
        val rows = mutableListOf<MediaRow>()
        fun add(key: String, title: String, items: List<BaseItemDto>, display: RowDisplay = RowDisplay()) {
            if (items.isNotEmpty()) rows += MediaRow("$prefix/$key", title, items.map { it.toMediaItem() }, display)
        }

        when (library.collection) {
            CollectionKind.TV_SHOWS -> {
                add("continue_watching", "Continue Watching", resumeDtos(id, listOf(BaseItemKind.EPISODE)), WIDE_DEFAULT)
                add("next_up", "Next Up", api.showApi.getNextUp(parentId = id, limit = ROW_LIMIT, fields = ROW_FIELDS).content.items, WIDE_DEFAULT)
                add("recently_added", "Recently Added", api.libraryApi.getLatestMedia(parentId = id, fields = ROW_FIELDS, limit = ROW_LIMIT).content)
                add("recently_released", "Recently Aired", items(id, listOf(BaseItemKind.EPISODE), ItemSortBy.PREMIERE_DATE, maxPremiere = now), WIDE_DEFAULT)
                add("top_unwatched", "Top Unwatched", items(id, listOf(BaseItemKind.SERIES), ItemSortBy.COMMUNITY_RATING, unplayed = true))
                add("favorites", "Favorites", items(id, listOf(BaseItemKind.SERIES), ItemSortBy.SORT_NAME, SortOrder.ASCENDING, favorite = true))
            }
            else -> {
                val types = if (library.collection == CollectionKind.MOVIES) listOf(BaseItemKind.MOVIE) else null
                add("continue_watching", "Continue Watching", resumeDtos(id, types), WIDE_DEFAULT)
                add("recently_released", "Recently Released", items(id, types, ItemSortBy.PREMIERE_DATE, maxPremiere = now))
                add("recently_added", "Recently Added", items(id, types, ItemSortBy.DATE_CREATED))
                add("top_unwatched", "Top Unwatched", items(id, types, ItemSortBy.COMMUNITY_RATING, unplayed = true))
                add("favorites", "Favorites", items(id, types, ItemSortBy.SORT_NAME, SortOrder.ASCENDING, favorite = true))
            }
        }
        return rows
    }

    override suspend fun libraryPage(
        library: MediaItem,
        sort: LibrarySort,
        startIndex: Int,
        limit: Int,
        includeTags: Boolean,
    ): Pair<List<MediaItem>, Int> {
        val types = when (library.collection) {
            CollectionKind.MOVIES -> listOf(BaseItemKind.MOVIE)
            CollectionKind.TV_SHOWS -> listOf(BaseItemKind.SERIES)
            CollectionKind.BOX_SETS -> listOf(BaseItemKind.BOX_SET)
            CollectionKind.PLAYLISTS -> listOf(BaseItemKind.PLAYLIST)
            else -> null
        }
        val (field, order) = when (sort) {
            LibrarySort.NAME -> ItemSortBy.SORT_NAME to SortOrder.ASCENDING
            LibrarySort.DATE_ADDED -> ItemSortBy.DATE_CREATED to SortOrder.DESCENDING
            LibrarySort.RELEASE_DATE -> ItemSortBy.PREMIERE_DATE to SortOrder.DESCENDING
            LibrarySort.RATING -> ItemSortBy.COMMUNITY_RATING to SortOrder.DESCENDING
            LibrarySort.RANDOM -> ItemSortBy.RANDOM to SortOrder.ASCENDING
        }
        val result = api.libraryApi.getItems(
            GetItemsRequest(
                parentId = uuid(library.id),
                includeItemTypes = types,
                recursive = types != null,
                sortBy = listOf(field, ItemSortBy.SORT_NAME),
                sortOrder = listOf(order, SortOrder.ASCENDING),
                startIndex = startIndex,
                limit = limit,
                fields = ROW_FIELDS + if (includeTags) listOf(ItemFields.TAGS) else emptyList(),
                enableTotalRecordCount = true,
            ),
        ).content
        return result.items.map { it.toMediaItem() } to result.totalRecordCount
    }

    private suspend fun resumeDtos(parentId: UUID?, types: List<BaseItemKind>?): List<BaseItemDto> =
        api.libraryApi.getResumeItems(
            parentId = parentId,
            limit = ROW_LIMIT,
            fields = ROW_FIELDS,
            mediaTypes = listOf(MediaType.VIDEO),
            includeItemTypes = types,
        ).content.items

    private suspend fun resumeItems(parentId: UUID?, types: List<BaseItemKind>?) = resumeDtos(parentId, types).map { it.toMediaItem() }

    private suspend fun items(
        parentId: UUID,
        types: List<BaseItemKind>?,
        sortBy: ItemSortBy,
        order: SortOrder = SortOrder.DESCENDING,
        unplayed: Boolean = false,
        favorite: Boolean = false,
        maxPremiere: LocalDateTime? = null,
    ): List<BaseItemDto> = api.libraryApi.getItems(
        GetItemsRequest(
            parentId = parentId,
            includeItemTypes = types,
            recursive = true,
            sortBy = listOf(sortBy, ItemSortBy.SORT_NAME),
            sortOrder = listOf(order, SortOrder.ASCENDING),
            filters = buildList {
                if (unplayed) add(ItemFilter.IS_UNPLAYED)
                if (favorite) add(ItemFilter.IS_FAVORITE)
            },
            maxPremiereDate = maxPremiere,
            limit = ROW_LIMIT,
            fields = ROW_FIELDS,
            enableTotalRecordCount = false,
        ),
    ).content.items

    override suspend fun getItem(id: String): MediaItem = api.libraryApi.getItem(itemId = uuid(id)).content.toMediaItem()

    override suspend fun seasons(seriesId: String): List<MediaItem> =
        api.showApi.getSeasons(seriesId = uuid(seriesId), fields = listOf(ItemFields.OVERVIEW)).content.items.map { it.toMediaItem() }

    override suspend fun episodes(seriesId: String, seasonId: String): List<MediaItem> =
        api.showApi.getEpisodes(
            seriesId = uuid(seriesId),
            seasonId = uuid(seasonId),
            fields = listOf(ItemFields.OVERVIEW, ItemFields.PRIMARY_IMAGE_ASPECT_RATIO),
        ).content.items.map { it.toMediaItem() }

    override suspend fun allEpisodes(seriesId: String): List<MediaItem> {
        val id = uuid(seriesId)
        val all = api.showApi.getEpisodes(seriesId = id, enableUserData = true).content.items
        // Some servers list season 0 only when asked for it directly.
        val specials = runCatching { api.showApi.getEpisodes(seriesId = id, season = 0, enableUserData = true).content.items }.getOrDefault(emptyList())
        return (all + specials).distinctBy { it.id }.filter { it.locationType != LocationType.VIRTUAL }.map { it.toMediaItem() }
    }

    override suspend fun nextUpFor(seriesId: String): MediaItem? =
        api.showApi.getNextUp(seriesId = uuid(seriesId), limit = 1, enableResumable = true).content.items.firstOrNull()?.toMediaItem()

    override suspend fun similar(itemId: String): List<MediaItem> =
        api.libraryApi.getSimilarItems(itemId = uuid(itemId), limit = 16, fields = listOf(ItemFields.PRIMARY_IMAGE_ASPECT_RATIO)).content.items.map { it.toMediaItem() }

    override suspend fun playlistItems(playlistId: String): List<MediaItem> =
        api.playlistApi.getPlaylistItems(
            playlistId = uuid(playlistId),
            fields = listOf(ItemFields.OVERVIEW, ItemFields.PRIMARY_IMAGE_ASPECT_RATIO, ItemFields.GENRES),
        ).content.items.map { it.toMediaItem() }

    override suspend fun setPlayed(itemId: String, played: Boolean) {
        if (played) api.userDataApi.markPlayedItem(itemId = uuid(itemId)) else api.userDataApi.markUnplayedItem(itemId = uuid(itemId))
    }

    override suspend fun setFavorite(itemId: String, favorite: Boolean) {
        if (favorite) api.userDataApi.markFavoriteItem(itemId = uuid(itemId)) else api.userDataApi.unmarkFavoriteItem(itemId = uuid(itemId))
    }

    // ---- Images ----

    override fun imageUrl(item: MediaItem, kind: ImageKind, maxHeight: Int): String? {
        if (kind == ImageKind.BACKDROP) return backdropUrl(item)
        if (kind == ImageKind.LOGO) return logoUrl(item)
        val type = if (kind == ImageKind.THUMB) ImageType.THUMB else ImageType.PRIMARY
        val tag = if (kind == ImageKind.THUMB) item.images.thumb else item.images.primary
        val series = item.seriesId
        return when {
            tag != null -> api.imageApi.getItemImageUrl(uuid(item.id), type, maxHeight = maxHeight, tag = tag)
            // Episodes without art of their own fall back to the series poster.
            kind == ImageKind.PRIMARY && series != null && item.images.seriesPrimary != null ->
                api.imageApi.getItemImageUrl(uuid(series), type, maxHeight = maxHeight, tag = item.images.seriesPrimary)
            else -> null
        }
    }

    override fun backdropUrls(item: MediaItem): List<String> {
        if (item.images.backdrops.isNotEmpty()) {
            return item.images.backdrops.mapIndexed { i, tag -> api.imageApi.getItemImageUrl(uuid(item.id), ImageType.BACKDROP, maxWidth = 1920, tag = tag, imageIndex = i) }
        }
        val parent = item.images.parentBackdropItemId ?: return emptyList()
        return item.images.parentBackdrops.mapIndexed { i, tag -> api.imageApi.getItemImageUrl(uuid(parent), ImageType.BACKDROP, maxWidth = 1920, tag = tag, imageIndex = i) }
    }

    override fun backdropUrl(item: MediaItem): String? {
        item.images.backdrops.firstOrNull()?.let { return api.imageApi.getItemImageUrl(uuid(item.id), ImageType.BACKDROP, maxWidth = 1920, tag = it) }
        val parent = item.images.parentBackdropItemId ?: return null
        return item.images.parentBackdrops.firstOrNull()?.let { api.imageApi.getItemImageUrl(uuid(parent), ImageType.BACKDROP, maxWidth = 1920, tag = it) }
    }

    override fun logoUrl(item: MediaItem): String? {
        item.images.logo?.let { return api.imageApi.getItemImageUrl(uuid(item.id), ImageType.LOGO, maxWidth = 800, tag = it) }
        val parent = item.images.parentLogoItemId ?: return null
        return item.images.parentLogo?.let { api.imageApi.getItemImageUrl(uuid(parent), ImageType.LOGO, maxWidth = 800, tag = it) }
    }

    override fun personImageUrl(person: Person): String? =
        person.imageTag?.let { api.imageApi.getItemImageUrl(uuid(person.id), ImageType.PRIMARY, maxHeight = 300, tag = it) }

    // ---- Playback ----

    override suspend fun channelTestClips(): List<ChannelClip> {
        val items = api.libraryApi.getItems(
            GetItemsRequest(
                searchTerm = ChannelTest.SERVER_TAG,
                recursive = true,
                includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.VIDEO, BaseItemKind.EPISODE, BaseItemKind.AUDIO, BaseItemKind.MUSIC_VIDEO),
                fields = listOf(ItemFields.MEDIA_SOURCES, ItemFields.MEDIA_STREAMS),
                limit = 50,
            ),
        ).content.items
        return items.filter { it.name?.contains(ChannelTest.SERVER_TAG, ignoreCase = true) == true }.map { item ->
            val source = item.mediaSources?.firstOrNull()
            val streams = source?.mediaStreams.orEmpty()
            val audio = streams.firstOrNull { it.index == source?.defaultAudioStreamIndex }
                ?: streams.firstOrNull { it.type == org.jellyfin.sdk.model.api.MediaStreamType.AUDIO }
            // Always the original file: the point is to send the real bitstream to the receiver.
            val url = if (item.type == BaseItemKind.AUDIO) {
                api.audioApi.getAudioStreamUrl(itemId = item.id, static = true, mediaSourceId = source?.id)
            } else {
                api.videoApi.getVideoStreamUrl(itemId = item.id, static = true, mediaSourceId = source?.id)
            }
            ChannelTest.fromServer(
                itemId = item.id.toString(),
                title = item.name.orEmpty(),
                uri = url,
                channels = audio?.channels,
                format = audio?.displayTitle ?: audio?.codec?.uppercase() ?: "Unknown audio",
            )
        }
    }

    override fun authorizationHeader(): String = AuthorizationHeaderBuilder.buildHeader(
        clientName = api.clientInfo.name,
        clientVersion = api.clientInfo.version,
        deviceId = api.deviceInfo.id,
        deviceName = api.deviceInfo.name,
        accessToken = api.accessToken,
    )

    override suspend fun resolvePlayback(
        itemId: String,
        isAudio: Boolean,
        startMs: Long,
        forceTranscode: Boolean,
        audioIndex: Int?,
        subtitleIndex: Int?,
        mediaSourceId: String?,
    ): PlaybackPlan {
        val id = uuid(itemId)
        val settings = playbackSettings.current()
        val directPlay = !forceTranscode && settings.allowDirectPlay
        val directStream = !forceTranscode && settings.allowDirectStream
        val info = api.mediaInfoApi.getPostedPlaybackInfo(
            itemId = id,
            data = PlaybackInfoDto(
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
        ).content
        info.errorCode?.let { error("Server can't play this item: $it") }
        val sdkSource = info.mediaSources.firstOrNull() ?: error("Item has no playable media")
        val source = sdkSource.toMediaSource()

        val streams = source.streams
        val chosenAudio = audioIndex ?: sdkSource.defaultAudioStreamIndex ?: streams.firstOrNull { it.kind == StreamKind.AUDIO }?.index
        val chosenSubtitle = subtitleIndex ?: sdkSource.defaultSubtitleStreamIndex ?: -1
        val external = streams
            .filter { it.kind == StreamKind.SUBTITLE && it.delivery == SubtitleDelivery.EXTERNAL && it.deliveryUrl != null }
            .mapNotNull { stream ->
                val mime = subtitleMime(stream.codec) ?: return@mapNotNull null
                ExternalSubtitle(stream.index, absoluteUrl(stream.deliveryUrl!!), mime, stream.language, stream.title ?: stream.language ?: "Subtitle ${stream.index}")
            }

        val transcodingUrl = sdkSource.transcodingUrl
        val (url, method) = when {
            sdkSource.supportsDirectPlay && directPlay -> {
                val streamUrl = if (isAudio) {
                    api.audioApi.getAudioStreamUrl(itemId = id, static = true, mediaSourceId = sdkSource.id, playSessionId = info.playSessionId, tag = sdkSource.eTag)
                } else {
                    api.videoApi.getVideoStreamUrl(itemId = id, static = true, mediaSourceId = sdkSource.id, playSessionId = info.playSessionId, tag = sdkSource.eTag)
                }
                streamUrl to PlayMethod.DIRECT_PLAY
            }
            transcodingUrl != null -> absoluteUrl(transcodingUrl) to
                if (sdkSource.supportsDirectStream && directStream) PlayMethod.DIRECT_STREAM else PlayMethod.TRANSCODE
            else -> error("Server offered no way to play this item")
        }

        return PlaybackPlan(
            itemId = itemId,
            url = url,
            method = method,
            playSessionId = info.playSessionId,
            source = source,
            isHls = method != PlayMethod.DIRECT_PLAY && sdkSource.transcodingSubProtocol == MediaStreamProtocol.HLS,
            audioIndex = chosenAudio,
            subtitleIndex = chosenSubtitle,
            externalSubtitles = external,
            transcodeReasons = transcodingUrl?.let(::transcodeReasons).orEmpty(),
        )
    }

    private fun absoluteUrl(path: String) = if (path.startsWith("http")) path else api.baseUrl!!.trimEnd('/') + path

    private fun subtitleMime(codec: String?): String? = when (codec?.lowercase()) {
        "srt", "subrip" -> "application/x-subrip"
        "vtt", "webvtt" -> "text/vtt"
        "ass", "ssa" -> "text/x-ssa"
        "ttml" -> "application/ttml+xml"
        else -> null
    }

    private fun transcodeReasons(url: String): List<String> =
        Regex("[?&]TranscodeReasons=([^&]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            ?.split(',')?.filter(String::isNotBlank).orEmpty()

    override suspend fun reportStart(plan: PlaybackPlan, positionMs: Long) {
        runCatching {
            api.sessionApi.reportPlaybackStart(
                PlaybackStartInfo(
                    itemId = uuid(plan.itemId),
                    playSessionId = plan.playSessionId,
                    mediaSourceId = plan.mediaSourceId,
                    positionTicks = positionMs.msToTicks(),
                    canSeek = true,
                    isPaused = false,
                    isMuted = false,
                    playMethod = plan.method.toSdk(),
                    repeatMode = RepeatMode.REPEAT_NONE,
                    playbackOrder = PlaybackOrder.DEFAULT,
                ),
            )
        }
    }

    override suspend fun reportProgress(plan: PlaybackPlan, positionMs: Long, isPaused: Boolean) {
        runCatching {
            api.sessionApi.reportPlaybackProgress(
                PlaybackProgressInfo(
                    itemId = uuid(plan.itemId),
                    playSessionId = plan.playSessionId,
                    mediaSourceId = plan.mediaSourceId,
                    positionTicks = positionMs.msToTicks(),
                    canSeek = true,
                    isPaused = isPaused,
                    isMuted = false,
                    playMethod = plan.method.toSdk(),
                    repeatMode = RepeatMode.REPEAT_NONE,
                    playbackOrder = PlaybackOrder.DEFAULT,
                ),
            )
        }
    }

    override fun reportStopped(plan: PlaybackPlan, positionMs: Long) {
        appScope.launch {
            runCatching {
                api.sessionApi.reportPlaybackStopped(
                    PlaybackStopInfo(
                        itemId = uuid(plan.itemId),
                        playSessionId = plan.playSessionId,
                        mediaSourceId = plan.mediaSourceId,
                        positionTicks = positionMs.msToTicks(),
                        failed = false,
                    ),
                )
            }
        }
    }

    private companion object {
        const val PREFS_CLIENT = "nitrate"
        const val ROW_LIMIT = 24
        val ROW_FIELDS = listOf(ItemFields.OVERVIEW, ItemFields.PRIMARY_IMAGE_ASPECT_RATIO, ItemFields.GENRES)
        val WIDE_DEFAULT = RowDisplay(style = RowStyle.WIDE, showTitles = true)
    }
}
