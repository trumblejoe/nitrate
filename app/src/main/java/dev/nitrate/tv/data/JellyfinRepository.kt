package dev.nitrate.tv.data

import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.authenticationApi
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.api.client.extensions.showApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.client.extensions.userViewApi
import org.jellyfin.sdk.api.client.extensions.videoApi
import org.jellyfin.sdk.discovery.RecommendedServerInfo
import org.jellyfin.sdk.discovery.RecommendedServerInfoScore
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PlayMethod
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

class JellyfinRepository(
    private val jellyfin: Jellyfin,
    private val sessionStore: SessionStore,
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

    /**
     * Direct-play stream URL. ExoPlayer handles most containers; transcoding via
     * PlaybackInfo + a DeviceProfile is the next step for unsupported codecs.
     */
    fun streamUrl(itemId: UUID, mediaSourceId: String? = null): String =
        api.videoApi.getVideoStreamUrl(
            itemId = itemId,
            static = true,
            mediaSourceId = mediaSourceId,
        )

    fun isPlayable(item: BaseItemDto) =
        item.type in setOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE, BaseItemKind.VIDEO, BaseItemKind.MUSIC_VIDEO)

    suspend fun reportStart(itemId: UUID, positionMs: Long) = runCatching {
        api.sessionApi.reportPlaybackStart(
            PlaybackStartInfo(
                itemId = itemId,
                positionTicks = positionMs.msToTicks(),
                canSeek = true,
                isPaused = false,
                isMuted = false,
                playMethod = PlayMethod.DIRECT_PLAY,
                repeatMode = RepeatMode.REPEAT_NONE,
                playbackOrder = PlaybackOrder.DEFAULT,
            )
        )
    }

    suspend fun reportProgress(itemId: UUID, positionMs: Long, isPaused: Boolean) = runCatching {
        api.sessionApi.reportPlaybackProgress(
            PlaybackProgressInfo(
                itemId = itemId,
                positionTicks = positionMs.msToTicks(),
                canSeek = true,
                isPaused = isPaused,
                isMuted = false,
                playMethod = PlayMethod.DIRECT_PLAY,
                repeatMode = RepeatMode.REPEAT_NONE,
                playbackOrder = PlaybackOrder.DEFAULT,
            )
        )
    }

    fun reportStopped(itemId: UUID, positionMs: Long) {
        appScope.launch {
            runCatching {
                api.sessionApi.reportPlaybackStopped(
                    PlaybackStopInfo(
                        itemId = itemId,
                        positionTicks = positionMs.msToTicks(),
                        failed = false,
                    )
                )
            }
        }
    }
}

private fun Long.msToTicks() = this * 10_000
fun Long.ticksToMs() = this / 10_000
