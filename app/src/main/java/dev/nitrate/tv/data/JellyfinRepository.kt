package dev.nitrate.tv.data

import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.authenticateWithQuickConnect
import org.jellyfin.sdk.api.client.extensions.displayPreferenceApi
import org.jellyfin.sdk.api.client.extensions.audioApi
import org.jellyfin.sdk.api.client.extensions.authenticationApi
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.playlistApi
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.api.client.extensions.showApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.client.extensions.userViewApi
import org.jellyfin.sdk.api.client.extensions.videoApi
import dev.nitrate.tv.data.playback.ChannelClip
import dev.nitrate.tv.data.playback.ChannelTest
import dev.nitrate.tv.data.playback.DeviceCapabilities
import dev.nitrate.tv.data.playback.DeviceProfileFactory
import dev.nitrate.tv.data.playback.PlaybackSettingsStore
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import dev.nitrate.tv.data.rows.MediaRow
import dev.nitrate.tv.data.rows.RowDisplay
import dev.nitrate.tv.data.rows.RowStyle
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import java.time.LocalDateTime
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.discovery.RecommendedServerInfo
import org.jellyfin.sdk.discovery.RecommendedServerInfoScore
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.QuickConnectResult
import org.jellyfin.sdk.model.api.ServerDiscoveryInfo
import org.jellyfin.sdk.model.api.UserDto
import kotlinx.coroutines.flow.Flow
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.LocationType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

enum class LibrarySort(val label: String, val field: ItemSortBy, val order: SortOrder) {
    NAME("Name", ItemSortBy.SORT_NAME, SortOrder.ASCENDING),
    DATE_ADDED("Date added", ItemSortBy.DATE_CREATED, SortOrder.DESCENDING),
    RELEASE_DATE("Release date", ItemSortBy.PREMIERE_DATE, SortOrder.DESCENDING),
    RATING("Rating", ItemSortBy.COMMUNITY_RATING, SortOrder.DESCENDING),
    RANDOM("Random", ItemSortBy.RANDOM, SortOrder.ASCENDING),
}

enum class SessionState {
    /** No saved sign-in. */
    NONE,

    /** Signed in and the server answered. */
    VALID,

    /** The server rejected the saved sign-in. */
    EXPIRED,

    /** Saved sign-in, but the server can't be reached right now (offline, server down). */
    UNREACHABLE,
}

/** A subtitle file the server serves separately from the video, side-loaded into the player. */
data class ExternalSubtitle(val streamIndex: Int, val url: String, val mimeType: String, val language: String?, val label: String)

/** How a given item will be played, as negotiated with the server. */
data class PlaybackPlan(
    val itemId: UUID,
    val url: String,
    val playMethod: PlayMethod,
    val playSessionId: String?,
    val mediaSourceId: String?,
    /** True when the URL is an HLS playlist (remux or transcode). */
    val isHls: Boolean,
    val source: MediaSourceInfo,
    /** Jellyfin stream index of the selected audio track, if any. */
    val audioIndex: Int?,
    /** Jellyfin stream index of the selected subtitle track, or -1 for none. */
    val subtitleIndex: Int,
    val externalSubtitles: List<ExternalSubtitle>,
    /** Server's reasons for transcoding, from the transcoding URL (e.g. VideoCodecNotSupported). */
    val transcodeReasons: List<String>,
)

class JellyfinRepository(
    private val jellyfin: Jellyfin,
    private val sessionStore: SessionStore,
    private val capabilities: () -> DeviceCapabilities,
    private val playbackSettings: PlaybackSettingsStore,
) {
    private val api: ApiClient = jellyfin.createApi()

    /** Outlives screens, for fire-and-forget reports such as playback stopped. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Signed-in user's id; scopes per-user settings such as row display. */
    var userId: String? = null
        private set

    /** Restores a saved session. Returns true if the token is still valid. */
    suspend fun restoreSession(): SessionState {
        val session = sessionStore.load() ?: return SessionState.NONE
        api.update(baseUrl = session.serverUrl, accessToken = session.accessToken)
        userId = session.userId
        serverUrl = session.serverUrl
        return checkSession()
    }

    /** Re-checks the restored session, e.g. from the offline screen's Retry. */
    suspend fun checkSession(): SessionState = try {
        api.userApi.getCurrentUser()
        SessionState.VALID
    } catch (e: InvalidStatusException) {
        // The server answered: the token was revoked or the user removed.
        if (e.status == 401 || e.status == 403) SessionState.EXPIRED else SessionState.UNREACHABLE
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        SessionState.UNREACHABLE
    }

    /** Address of the signed-in server, for messages. */
    var serverUrl: String? = null
        private set

    /**
     * Turns whatever the user typed ("192.168.1.5", "jelly.example.com") into a reachable
     * server, trying http/https and the default ports. Returns the best candidate or throws.
     */
    suspend fun findServer(input: String): RecommendedServerInfo {
        val candidates = jellyfin.discovery.getRecommendedServers(
            input,
            RecommendedServerInfoScore.OK,
        )
        return candidates.maxByOrNull { it.score }
            ?: error("No Jellyfin server found at \"$input\"")
    }

    /** Jellyfin servers answering a UDP broadcast on the local network. */
    fun discoverServers(): Flow<ServerDiscoveryInfo> = jellyfin.discovery.discoverLocalServers()

    /** Users the server shows on its login screen (hidden users are not listed). */
    suspend fun publicUsers(serverUrl: String): List<UserDto> = runCatching {
        api.update(baseUrl = serverUrl, accessToken = null)
        api.userApi.getPublicUsers().content
    }.getOrDefault(emptyList())

    fun userImageUrl(user: UserDto): String? =
        user.primaryImageTag?.let { api.imageApi.getUserImageUrl(userId = user.id, tag = it) }

    suspend fun login(serverUrl: String, username: String, password: String) {
        api.update(baseUrl = serverUrl, accessToken = null)
        val result by api.authenticationApi.authenticateUserByName(username = username, password = password)
        finishLogin(serverUrl, result)
    }

    suspend fun quickConnectEnabled(serverUrl: String): Boolean = runCatching {
        api.update(baseUrl = serverUrl, accessToken = null)
        api.authenticationApi.getQuickConnectEnabled().content
    }.getOrDefault(false)

    /** Starts a Quick Connect request; the user approves [QuickConnectResult.code] from another device. */
    suspend fun startQuickConnect(serverUrl: String): QuickConnectResult {
        api.update(baseUrl = serverUrl, accessToken = null)
        return api.authenticationApi.initiateQuickConnect().content
    }

    suspend fun quickConnectApproved(secret: String): Boolean =
        api.authenticationApi.getQuickConnectState(secret = secret).content.authenticated

    suspend fun finishQuickConnect(serverUrl: String, secret: String) {
        val result by api.authenticationApi.authenticateWithQuickConnect(secret)
        finishLogin(serverUrl, result)
    }

    private suspend fun finishLogin(serverUrl: String, result: AuthenticationResult) {
        val token = requireNotNull(result.accessToken) { "Server returned no access token" }
        val user = requireNotNull(result.user) { "Server returned no user" }
        api.update(baseUrl = serverUrl, accessToken = token)
        userId = user.id.toString()
        this.serverUrl = serverUrl
        sessionStore.save(
            Session(
                serverUrl = serverUrl,
                accessToken = token,
                userId = user.id.toString(),
                userName = user.name.orEmpty(),
            )
        )
    }

    /** Nitrate's entries in the user's server-side display preferences ([prefsId] picks the slot). */
    suspend fun loadCustomPrefs(prefsId: String = PREFS_ID): Map<String, String> =
        api.displayPreferenceApi.getDisplayPreferences(displayPreferencesId = prefsId, client = PREFS_CLIENT).content.customPrefs
            .orEmpty().mapNotNull { (k, v) -> v?.let { k to it } }.toMap()

    suspend fun saveCustomPrefs(prefs: Map<String, String>, prefsId: String = PREFS_ID) {
        val current = api.displayPreferenceApi.getDisplayPreferences(displayPreferencesId = prefsId, client = PREFS_CLIENT).content
        api.displayPreferenceApi.updateDisplayPreferences(
            displayPreferencesId = prefsId,
            client = PREFS_CLIENT,
            data = current.copy(customPrefs = prefs),
        )
    }

    suspend fun logout() {
        runCatching { api.sessionApi.reportSessionEnded() }
        sessionStore.clear()
        api.update(accessToken = null)
    }

    suspend fun serverName(serverUrl: String): String? = runCatching {
        api.update(baseUrl = serverUrl, accessToken = null)
        api.systemApi.getPublicSystemInfo().content.serverName
    }.getOrNull()

    suspend fun views(): List<BaseItemDto> = api.userViewApi.getUserViews().content.items

    suspend fun homeRows(): List<MediaRow> {
        val rows = mutableListOf<MediaRow>()
        val views = views()
        if (views.isNotEmpty()) rows += MediaRow("home/my_media", "My Media", views, WIDE_DEFAULT)

        val resume = resumeItems(parentId = null, types = null)
        if (resume.isNotEmpty()) rows += MediaRow("home/continue_watching", "Continue Watching", resume, WIDE_DEFAULT)

        val nextUp = api.showApi.getNextUp(limit = ROW_LIMIT, fields = ROW_FIELDS).content.items
        if (nextUp.isNotEmpty()) rows += MediaRow("home/next_up", "Next Up", nextUp, WIDE_DEFAULT)

        for (view in views) {
            if (view.collectionType !in setOf(CollectionType.MOVIES, CollectionType.TVSHOWS, CollectionType.HOMEVIDEOS, null)) continue
            val latest = api.libraryApi.getLatestMedia(parentId = view.id, fields = ROW_FIELDS, limit = ROW_LIMIT).content
            if (latest.isNotEmpty()) rows += MediaRow("home/latest/${view.id}", "Latest in ${view.name}", latest, RowDisplay())
        }
        return rows
    }

    /** The "Recommended" rows for one library, chosen by library type. */
    suspend fun libraryRows(library: BaseItemDto): List<MediaRow> {
        val id = library.id
        val prefix = "library/$id"
        val now = LocalDateTime.now()
        val rows = mutableListOf<MediaRow>()
        fun add(key: String, title: String, items: List<BaseItemDto>, display: RowDisplay = RowDisplay()) {
            if (items.isNotEmpty()) rows += MediaRow("$prefix/$key", title, items, display)
        }

        when (library.collectionType) {
            CollectionType.TVSHOWS -> {
                add("continue_watching", "Continue Watching", resumeItems(id, listOf(BaseItemKind.EPISODE)), WIDE_DEFAULT)
                add("next_up", "Next Up", api.showApi.getNextUp(parentId = id, limit = ROW_LIMIT, fields = ROW_FIELDS).content.items, WIDE_DEFAULT)
                add("recently_added", "Recently Added", api.libraryApi.getLatestMedia(parentId = id, fields = ROW_FIELDS, limit = ROW_LIMIT).content)
                add("recently_released", "Recently Aired", items(id, listOf(BaseItemKind.EPISODE), ItemSortBy.PREMIERE_DATE, maxPremiere = now), WIDE_DEFAULT)
                add("top_unwatched", "Top Unwatched", items(id, listOf(BaseItemKind.SERIES), ItemSortBy.COMMUNITY_RATING, unplayed = true))
                add("favorites", "Favorites", items(id, listOf(BaseItemKind.SERIES), ItemSortBy.SORT_NAME, SortOrder.ASCENDING, favorite = true))
            }
            else -> {
                val types = if (library.collectionType == CollectionType.MOVIES) listOf(BaseItemKind.MOVIE) else null
                add("continue_watching", "Continue Watching", resumeItems(id, types), WIDE_DEFAULT)
                add("recently_released", "Recently Released", items(id, types, ItemSortBy.PREMIERE_DATE, maxPremiere = now))
                add("recently_added", "Recently Added", items(id, types, ItemSortBy.DATE_CREATED))
                add("top_unwatched", "Top Unwatched", items(id, types, ItemSortBy.COMMUNITY_RATING, unplayed = true))
                add("favorites", "Favorites", items(id, types, ItemSortBy.SORT_NAME, SortOrder.ASCENDING, favorite = true))
            }
        }
        return rows
    }

    /** One page of a library's contents for the grid view. Returns the items and the total count. */
    suspend fun libraryPage(
        library: BaseItemDto,
        sort: LibrarySort,
        startIndex: Int,
        limit: Int,
        extraFields: List<ItemFields> = emptyList(),
    ): Pair<List<BaseItemDto>, Int> {
        val types = when (library.collectionType) {
            CollectionType.MOVIES -> listOf(BaseItemKind.MOVIE)
            CollectionType.TVSHOWS -> listOf(BaseItemKind.SERIES)
            CollectionType.BOXSETS -> listOf(BaseItemKind.BOX_SET)
            CollectionType.PLAYLISTS -> listOf(BaseItemKind.PLAYLIST)
            else -> null
        }
        val result = api.libraryApi.getItems(
            GetItemsRequest(
                parentId = library.id,
                includeItemTypes = types,
                recursive = types != null,
                sortBy = listOf(sort.field, ItemSortBy.SORT_NAME),
                sortOrder = listOf(sort.order, SortOrder.ASCENDING),
                startIndex = startIndex,
                limit = limit,
                fields = ROW_FIELDS + extraFields,
                enableTotalRecordCount = true,
            ),
        ).content
        return result.items to result.totalRecordCount
    }

    private suspend fun resumeItems(parentId: UUID?, types: List<BaseItemKind>?): List<BaseItemDto> =
        api.libraryApi.getResumeItems(
            parentId = parentId,
            limit = ROW_LIMIT,
            fields = ROW_FIELDS,
            mediaTypes = listOf(MediaType.VIDEO),
            includeItemTypes = types,
        ).content.items

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

    suspend fun getItem(id: UUID): BaseItemDto = api.libraryApi.getItem(itemId = id).content

    fun imageUrl(item: BaseItemDto, type: ImageType = ImageType.PRIMARY, maxHeight: Int = 480): String? {
        // Episodes look better with their own thumb, falling back to the series primary.
        val tag = item.imageTags?.get(type)
        return when {
            tag != null -> api.imageApi.getItemImageUrl(item.id, type, maxHeight = maxHeight, tag = tag)
            type == ImageType.PRIMARY && item.seriesId != null && item.seriesPrimaryImageTag != null ->
                api.imageApi.getItemImageUrl(item.seriesId!!, type, maxHeight = maxHeight, tag = item.seriesPrimaryImageTag)
            else -> null
        }
    }

    /** Every backdrop for the item (or its series), for artwork rotation. */
    fun backdropUrls(item: BaseItemDto): List<String> {
        val own = item.backdropImageTags.orEmpty()
        if (own.isNotEmpty()) {
            return own.mapIndexed { i, tag -> api.imageApi.getItemImageUrl(item.id, ImageType.BACKDROP, maxWidth = 1920, tag = tag, imageIndex = i) }
        }
        val parent = item.parentBackdropItemId ?: return emptyList()
        return item.parentBackdropImageTags.orEmpty().mapIndexed { i, tag ->
            api.imageApi.getItemImageUrl(parent, ImageType.BACKDROP, maxWidth = 1920, tag = tag, imageIndex = i)
        }
    }

    fun backdropUrl(item: BaseItemDto): String? {
        val tag = item.backdropImageTags?.firstOrNull()
        val parentTag = item.parentBackdropImageTags?.firstOrNull()
        return when {
            tag != null -> api.imageApi.getItemImageUrl(item.id, ImageType.BACKDROP, maxWidth = 1920, tag = tag)
            parentTag != null && item.parentBackdropItemId != null ->
                api.imageApi.getItemImageUrl(item.parentBackdropItemId!!, ImageType.BACKDROP, maxWidth = 1920, tag = parentTag)
            else -> null
        }
    }

    suspend fun seasons(seriesId: UUID): List<BaseItemDto> =
        api.showApi.getSeasons(seriesId = seriesId, fields = listOf(ItemFields.OVERVIEW)).content.items

    suspend fun episodes(seriesId: UUID, seasonId: UUID): List<BaseItemDto> =
        api.showApi.getEpisodes(
            seriesId = seriesId,
            seasonId = seasonId,
            fields = listOf(ItemFields.OVERVIEW, ItemFields.PRIMARY_IMAGE_ASPECT_RATIO),
        ).content.items

    /**
     * Every episode of a series the library actually has, specials included. Placeholder
     * "missing" items are excluded even if the server creates them.
     */
    suspend fun allEpisodes(seriesId: UUID): List<BaseItemDto> {
        val all = api.showApi.getEpisodes(seriesId = seriesId, enableUserData = true).content.items
        // Some servers list season 0 only when asked for it directly.
        val specials = runCatching { api.showApi.getEpisodes(seriesId = seriesId, season = 0, enableUserData = true).content.items }
            .getOrDefault(emptyList())
        return (all + specials).distinctBy { it.id }.filter { it.locationType != LocationType.VIRTUAL }
    }

    /** The episode to play when the user hits Play on a series. */
    suspend fun nextUpFor(seriesId: UUID): BaseItemDto? =
        api.showApi.getNextUp(seriesId = seriesId, limit = 1, enableResumable = true).content.items.firstOrNull()

    suspend fun similar(itemId: UUID): List<BaseItemDto> =
        api.libraryApi.getSimilarItems(itemId = itemId, limit = 16, fields = listOf(ItemFields.PRIMARY_IMAGE_ASPECT_RATIO))
            .content.items

    suspend fun playlistItems(playlistId: UUID): List<BaseItemDto> =
        api.playlistApi.getPlaylistItems(
            playlistId = playlistId,
            fields = listOf(ItemFields.OVERVIEW, ItemFields.PRIMARY_IMAGE_ASPECT_RATIO, ItemFields.GENRES),
        ).content.items

    suspend fun setPlayed(itemId: UUID, played: Boolean) {
        if (played) api.userDataApi.markPlayedItem(itemId = itemId) else api.userDataApi.markUnplayedItem(itemId = itemId)
    }

    suspend fun setFavorite(itemId: UUID, favorite: Boolean) {
        if (favorite) api.userDataApi.markFavoriteItem(itemId = itemId) else api.userDataApi.unmarkFavoriteItem(itemId = itemId)
    }

    fun personImageUrl(personId: UUID, tag: String?): String? =
        tag?.let { api.imageApi.getItemImageUrl(personId, ImageType.PRIMARY, maxHeight = 300, tag = it) }

    fun logoUrl(item: BaseItemDto): String? {
        item.imageTags?.get(ImageType.LOGO)?.let {
            return api.imageApi.getItemImageUrl(item.id, ImageType.LOGO, maxWidth = 800, tag = it)
        }
        val parent = item.parentLogoItemId ?: return null
        return item.parentLogoImageTag?.let { api.imageApi.getItemImageUrl(parent, ImageType.LOGO, maxWidth = 800, tag = it) }
    }

    fun isPlayable(item: BaseItemDto) =
        item.type in setOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE, BaseItemKind.VIDEO, BaseItemKind.MUSIC_VIDEO, BaseItemKind.AUDIO)

    /**
     * Files on the server whose title contains [ChannelTest.SERVER_TAG], as channel-test clips.
     * Always the original file (static stream), never a transcode: the point is to send the
     * real bitstream to the receiver.
     */
    suspend fun channelTestClips(): List<ChannelClip> {
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
            val audio = streams.firstOrNull { it.index == source?.defaultAudioStreamIndex } ?: streams.firstOrNull { it.type == MediaStreamType.AUDIO }
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

    /** Authorization header for requests made outside the SDK (ExoPlayer). */
    fun authorizationHeader(): String = AuthorizationHeaderBuilder.buildHeader(
        clientName = api.clientInfo.name,
        clientVersion = api.clientInfo.version,
        deviceId = api.deviceInfo.id,
        deviceName = api.deviceInfo.name,
        accessToken = api.accessToken,
    )

    /**
     * Asks the server how to play [itemId] given this device's effective profile.
     * With [forceTranscode] the server must re-encode, used after a local decoder failure.
     * [audioIndex] / [subtitleIndex] pick streams (null lets the server choose; subtitle -1 is off).
     */
    suspend fun resolvePlayback(
        itemId: UUID,
        isAudio: Boolean,
        startMs: Long,
        forceTranscode: Boolean = false,
        audioIndex: Int? = null,
        subtitleIndex: Int? = null,
        mediaSourceId: String? = null,
    ): PlaybackPlan {
        val settings = playbackSettings.current()
        val directPlay = !forceTranscode && settings.allowDirectPlay
        val directStream = !forceTranscode && settings.allowDirectStream
        val info = api.mediaInfoApi.getPostedPlaybackInfo(
            itemId = itemId,
            data = PlaybackInfoDto(
                deviceProfile = DeviceProfileFactory.create(capabilities(), settings),
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
        val source = info.mediaSources.firstOrNull() ?: error("Item has no playable media")

        val streams = source.mediaStreams.orEmpty()
        val chosenAudio = audioIndex ?: source.defaultAudioStreamIndex ?: streams.firstOrNull { it.type == MediaStreamType.AUDIO }?.index
        val chosenSubtitle = subtitleIndex ?: source.defaultSubtitleStreamIndex ?: -1
        val external = streams
            .filter { it.type == MediaStreamType.SUBTITLE && it.deliveryMethod == SubtitleDeliveryMethod.EXTERNAL && it.deliveryUrl != null }
            .mapNotNull { stream ->
                val mime = subtitleMime(stream.codec) ?: return@mapNotNull null
                ExternalSubtitle(
                    streamIndex = stream.index,
                    url = absoluteUrl(stream.deliveryUrl!!),
                    mimeType = mime,
                    language = stream.language,
                    label = stream.displayTitle ?: stream.language ?: "Subtitle ${stream.index}",
                )
            }

        val transcodingUrl = source.transcodingUrl
        val (url, method) = when {
            source.supportsDirectPlay && directPlay -> {
                val streamUrl = if (isAudio) {
                    api.audioApi.getAudioStreamUrl(itemId = itemId, static = true, mediaSourceId = source.id, playSessionId = info.playSessionId, tag = source.eTag)
                } else {
                    api.videoApi.getVideoStreamUrl(itemId = itemId, static = true, mediaSourceId = source.id, playSessionId = info.playSessionId, tag = source.eTag)
                }
                streamUrl to PlayMethod.DIRECT_PLAY
            }
            transcodingUrl != null -> absoluteUrl(transcodingUrl) to
                if (source.supportsDirectStream && directStream) PlayMethod.DIRECT_STREAM else PlayMethod.TRANSCODE
            else -> error("Server offered no way to play this item")
        }

        return PlaybackPlan(
            itemId = itemId,
            url = url,
            playMethod = method,
            playSessionId = info.playSessionId,
            mediaSourceId = source.id,
            isHls = method != PlayMethod.DIRECT_PLAY && source.transcodingSubProtocol == MediaStreamProtocol.HLS,
            source = source,
            audioIndex = chosenAudio,
            subtitleIndex = chosenSubtitle,
            externalSubtitles = external,
            transcodeReasons = transcodingUrl?.let(::transcodeReasons).orEmpty(),
        )
    }

    /**
     * Remembers that direct play failed on this TV so it isn't attempted again for the same
     * format. Blames the HDR format if the video is HDR (the codec itself usually works in SDR).
     */
    suspend fun recordDirectPlayFailure(plan: PlaybackPlan, videoFailed: Boolean, error: String) {
        val streams = plan.source.mediaStreams.orEmpty()
        val id = if (videoFailed) {
            val video = streams.firstOrNull { it.type == MediaStreamType.VIDEO } ?: return
            val range = video.videoRangeType?.serialName
            when {
                range == null || range == "SDR" || range == "Unknown" -> video.codec?.lowercase()
                range.startsWith("DOVI") -> PlaybackSettingsStore.hdrIdFor("DOVI")
                range.startsWith("HDR10Plus") -> PlaybackSettingsStore.hdrIdFor("HDR10Plus")
                else -> PlaybackSettingsStore.hdrIdFor(range)
            }
        } else {
            streams.firstOrNull { it.index == plan.audioIndex }?.codec?.lowercase()
        } ?: return
        playbackSettings.recordFailure(id, error)
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

    suspend fun reportStart(plan: PlaybackPlan, positionMs: Long) = runCatching {
        api.sessionApi.reportPlaybackStart(
            PlaybackStartInfo(
                itemId = plan.itemId,
                playSessionId = plan.playSessionId,
                mediaSourceId = plan.mediaSourceId,
                positionTicks = positionMs.msToTicks(),
                canSeek = true,
                isPaused = false,
                isMuted = false,
                playMethod = plan.playMethod,
                repeatMode = RepeatMode.REPEAT_NONE,
                playbackOrder = PlaybackOrder.DEFAULT,
            )
        )
    }

    suspend fun reportProgress(plan: PlaybackPlan, positionMs: Long, isPaused: Boolean) = runCatching {
        api.sessionApi.reportPlaybackProgress(
            PlaybackProgressInfo(
                itemId = plan.itemId,
                playSessionId = plan.playSessionId,
                mediaSourceId = plan.mediaSourceId,
                positionTicks = positionMs.msToTicks(),
                canSeek = true,
                isPaused = isPaused,
                isMuted = false,
                playMethod = plan.playMethod,
                repeatMode = RepeatMode.REPEAT_NONE,
                playbackOrder = PlaybackOrder.DEFAULT,
            )
        )
    }

    /** Fire-and-forget: the server also stops any transcode tied to this play session. */
    fun reportStopped(plan: PlaybackPlan, positionMs: Long) {
        appScope.launch {
            runCatching {
                api.sessionApi.reportPlaybackStopped(
                    PlaybackStopInfo(
                        itemId = plan.itemId,
                        playSessionId = plan.playSessionId,
                        mediaSourceId = plan.mediaSourceId,
                        positionTicks = positionMs.msToTicks(),
                        failed = false,
                    )
                )
            }
        }
    }

    companion object {
        /** Default for the bitrate setting. */
        const val DEFAULT_BITRATE = 120_000_000
        private const val PREFS_ID = "nitrate"
        private const val PREFS_CLIENT = "nitrate"
        private const val ROW_LIMIT = 24
        private val ROW_FIELDS = listOf(ItemFields.OVERVIEW, ItemFields.PRIMARY_IMAGE_ASPECT_RATIO, ItemFields.GENRES)
        private val WIDE_DEFAULT = RowDisplay(style = RowStyle.WIDE, showTitles = true)

    }
}

private fun Long.msToTicks() = this * 10_000
fun Long.ticksToMs() = this / 10_000
