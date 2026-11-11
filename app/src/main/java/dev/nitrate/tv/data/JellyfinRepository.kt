package dev.nitrate.tv.data

import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.authenticationApi
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.api.client.extensions.showApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.client.extensions.userViewApi
import org.jellyfin.sdk.api.client.extensions.videoApi
import dev.nitrate.tv.data.playback.DeviceProfileFactory
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.discovery.RecommendedServerInfo
import org.jellyfin.sdk.discovery.RecommendedServerInfoScore
import org.jellyfin.sdk.model.api.BaseItemDto
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

data class HomeRow(val title: String, val items: List<BaseItemDto>)

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

    /** Restores a saved session. Returns true if the token is still valid. */
    suspend fun restoreSession(): Boolean {
        val session = sessionStore.load() ?: return false
        api.update(baseUrl = session.serverUrl, accessToken = session.accessToken)
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

    suspend fun login(serverUrl: String, username: String, password: String) {
        api.update(baseUrl = serverUrl, accessToken = null)
        val result by api.authenticationApi.authenticateUserByName(username = username, password = password)
        val token = requireNotNull(result.accessToken) { "Server returned no access token" }
        val user = requireNotNull(result.user) { "Server returned no user" }
        api.update(baseUrl = serverUrl, accessToken = token)
        sessionStore.save(
            Session(
                serverUrl = serverUrl,
                accessToken = token,
                userId = user.id.toString(),
                userName = user.name.orEmpty(),
            )
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

    suspend fun homeRows(): List<HomeRow> {
        val fields = listOf(ItemFields.OVERVIEW, ItemFields.PRIMARY_IMAGE_ASPECT_RATIO)
        val rows = mutableListOf<HomeRow>()

        val resume = api.libraryApi.getResumeItems(
            limit = 20,
            fields = fields,
            mediaTypes = listOf(MediaType.VIDEO),
            enableImageTypes = listOf(ImageType.PRIMARY, ImageType.THUMB, ImageType.BACKDROP),
        ).content.items
        if (resume.isNotEmpty()) rows += HomeRow("Continue Watching", resume)

        val nextUp = api.showApi.getNextUp(limit = 20, fields = fields).content.items
        if (nextUp.isNotEmpty()) rows += HomeRow("Next Up", nextUp)

        val views = api.userViewApi.getUserViews().content.items
        for (view in views) {
            if (view.collectionType !in setOf(CollectionType.MOVIES, CollectionType.TVSHOWS, CollectionType.HOMEVIDEOS, null)) continue
            val latest = api.libraryApi.getLatestMedia(
                parentId = view.id,
                fields = fields,
                limit = 20,
            ).content
            if (latest.isNotEmpty()) rows += HomeRow("Latest in ${view.name}", latest)
        }
        return rows
    }

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
        item.type in setOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE, BaseItemKind.VIDEO, BaseItemKind.MUSIC_VIDEO)

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
                url = api.videoApi.getVideoStreamUrl(
                    itemId = itemId,
                    static = true,
                    mediaSourceId = source.id,
                    playSessionId = info.playSessionId,
                    tag = source.eTag,
                ),
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
        /** 120 Mbps: enough for 4K remuxes on a LAN. Becomes a setting later. */
        const val DEFAULT_MAX_BITRATE = 120_000_000
    }
}

private fun Long.msToTicks() = this * 10_000
fun Long.ticksToMs() = this / 10_000
