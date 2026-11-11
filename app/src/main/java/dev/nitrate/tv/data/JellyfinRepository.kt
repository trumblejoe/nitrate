package dev.nitrate.tv.data

import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
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
import dev.nitrate.tv.data.playback.DeviceProfileFactory
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

/** How a given item will be played, as negotiated with the server. */
data class PlaybackPlan(
    val itemId: UUID,
    val url: String,
    val playMethod: PlayMethod,
    val playSessionId: String?,
    val mediaSourceId: String?,
    /** True when the URL is an HLS playlist (remux or transcode). */
    val isHls: Boolean,
)

class JellyfinRepository(
    private val jellyfin: Jellyfin,
    private val sessionStore: SessionStore,
    private val profileFactory: DeviceProfileFactory,
) {
    private val api: ApiClient = jellyfin.createApi()

    /** Outlives screens, for fire-and-forget reports such as playback stopped. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Signed-in user's id; scopes per-user settings such as row display. */
    var userId: String? = null
        private set

    /** Restores a saved session. Returns true if the token is still valid. */
    suspend fun restoreSession(): Boolean {
        val session = sessionStore.load() ?: return false
        api.update(baseUrl = session.serverUrl, accessToken = session.accessToken)
        userId = session.userId
        return runCatching { api.userApi.getCurrentUser() }.isSuccess
    }

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
        sessionStore.save(
            Session(
                serverUrl = serverUrl,
                accessToken = token,
                userId = user.id.toString(),
                userName = user.name.orEmpty(),
            )
        )
    }

    /** Nitrate's entries in the user's server-side display preferences. */
    suspend fun loadCustomPrefs(): Map<String, String> =
        api.displayPreferenceApi.getDisplayPreferences(displayPreferencesId = PREFS_ID, client = PREFS_CLIENT).content.customPrefs
            .orEmpty().mapNotNull { (k, v) -> v?.let { k to it } }.toMap()

    suspend fun saveCustomPrefs(prefs: Map<String, String>) {
        val current = api.displayPreferenceApi.getDisplayPreferences(displayPreferencesId = PREFS_ID, client = PREFS_CLIENT).content
        api.displayPreferenceApi.updateDisplayPreferences(
            displayPreferencesId = PREFS_ID,
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

    /** Authorization header for requests made outside the SDK (ExoPlayer). */
    fun authorizationHeader(): String = AuthorizationHeaderBuilder.buildHeader(
        clientName = api.clientInfo.name,
        clientVersion = api.clientInfo.version,
        deviceId = api.deviceInfo.id,
        deviceName = api.deviceInfo.name,
        accessToken = api.accessToken,
    )

    /**
     * Asks the server how to play [itemId] given this device's capabilities.
     * With [forceTranscode] the server must re-encode, used after a local decoder failure.
     */
    suspend fun resolvePlayback(
        itemId: UUID,
        isAudio: Boolean,
        startMs: Long,
        forceTranscode: Boolean = false,
        maxBitrate: Int = DEFAULT_MAX_BITRATE,
    ): PlaybackPlan {
        val info = api.mediaInfoApi.getPostedPlaybackInfo(
            itemId = itemId,
            data = PlaybackInfoDto(
                deviceProfile = profileFactory.create(maxBitrate),
                maxStreamingBitrate = maxBitrate,
                startTimeTicks = startMs.msToTicks(),
                enableDirectPlay = !forceTranscode,
                enableDirectStream = !forceTranscode,
                enableTranscoding = true,
                allowVideoStreamCopy = !forceTranscode,
                allowAudioStreamCopy = !forceTranscode,
                autoOpenLiveStream = true,
            ),
        ).content
        info.errorCode?.let { error("Server can't play this item: $it") }
        val source = info.mediaSources.firstOrNull() ?: error("Item has no playable media")

        val transcodingUrl = source.transcodingUrl
        return if (source.supportsDirectPlay && !forceTranscode) {
            PlaybackPlan(
                itemId = itemId,
                url = if (isAudio) {
                    api.audioApi.getAudioStreamUrl(itemId = itemId, static = true, mediaSourceId = source.id, playSessionId = info.playSessionId, tag = source.eTag)
                } else {
                    api.videoApi.getVideoStreamUrl(itemId = itemId, static = true, mediaSourceId = source.id, playSessionId = info.playSessionId, tag = source.eTag)
                },
                playMethod = PlayMethod.DIRECT_PLAY,
                playSessionId = info.playSessionId,
                mediaSourceId = source.id,
                isHls = false,
            )
        } else if (transcodingUrl != null) {
            PlaybackPlan(
                itemId = itemId,
                url = api.baseUrl!!.trimEnd('/') + transcodingUrl,
                playMethod = if (source.supportsDirectStream && !forceTranscode) PlayMethod.DIRECT_STREAM else PlayMethod.TRANSCODE,
                playSessionId = info.playSessionId,
                mediaSourceId = source.id,
                isHls = source.transcodingSubProtocol == MediaStreamProtocol.HLS,
            )
        } else {
            error("Server offered no way to play this item")
        }
    }

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
        private const val PREFS_ID = "nitrate"
        private const val PREFS_CLIENT = "nitrate"
        private const val ROW_LIMIT = 24
        private val ROW_FIELDS = listOf(ItemFields.OVERVIEW, ItemFields.PRIMARY_IMAGE_ASPECT_RATIO, ItemFields.GENRES)
        private val WIDE_DEFAULT = RowDisplay(style = RowStyle.WIDE, showTitles = true)

        /** 120 Mbps: enough for 4K remuxes on a LAN. Becomes a setting later. */
        const val DEFAULT_MAX_BITRATE = 120_000_000
    }
}

private fun Long.msToTicks() = this * 10_000
fun Long.ticksToMs() = this / 10_000
