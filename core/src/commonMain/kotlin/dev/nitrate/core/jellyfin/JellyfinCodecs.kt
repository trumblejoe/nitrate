package dev.nitrate.core.jellyfin

import dev.nitrate.core.jellyfin.JellyfinClient.Companion.CODEC_INDEX_PAGE

import dev.nitrate.core.jellyfin.JellyfinClient.CodecIndex

import dev.nitrate.core.jellyfin.JellyfinClient.Companion.CODEC_INDEX_TTL_MS

import dev.nitrate.core.LibrarySort
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.CollectionKind
import dev.nitrate.core.model.DiscoveredServer
import dev.nitrate.core.model.ExternalSubtitle
import dev.nitrate.core.model.ImageKind
import dev.nitrate.core.model.ItemImages
import dev.nitrate.core.model.InvalidCredentialsException
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.MediaSegment
import dev.nitrate.core.model.MediaSource
import dev.nitrate.core.model.Person
import dev.nitrate.core.model.PlayMethod
import dev.nitrate.core.model.PlaybackPlan
import dev.nitrate.core.model.VersionOption
import dev.nitrate.core.model.QuickConnectRequest
import dev.nitrate.core.model.SavedAccount
import dev.nitrate.core.model.RemoteUserData
import dev.nitrate.core.model.SearchResults
import dev.nitrate.core.model.SegmentKind
import dev.nitrate.core.model.TrickplayInfo
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.ServerAddress
import dev.nitrate.core.model.ServerUser
import dev.nitrate.core.model.SessionState
import dev.nitrate.core.model.SavedServer
import dev.nitrate.core.pages.Codecs
import dev.nitrate.core.pages.StreamInfo
import dev.nitrate.core.pages.VideoStreamInfo
import kotlinx.coroutines.sync.withLock
import dev.nitrate.core.model.AddressCheck
import dev.nitrate.core.model.StreamKind
import dev.nitrate.core.model.SubtitleDelivery
import dev.nitrate.core.model.subtitleMimeType
import dev.nitrate.core.admin.AccountInfo
import dev.nitrate.core.admin.GeneralCommand
import dev.nitrate.core.events.ServerEvent
import dev.nitrate.core.playback.RemoteSubtitle
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
import dev.nitrate.core.syncplay.ServerTimeSample
import dev.nitrate.core.syncplay.SyncPlayGroup
import dev.nitrate.core.syncplay.SyncPlayRequest
import io.ktor.client.plugins.websocket.WebSockets
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
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach
import io.ktor.client.call.save
import io.ktor.client.plugins.plugin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** What every title in [query]'s library is made of, from the plugin's index; null without it. */
internal suspend fun JellyfinClient.companionFormats(query: ItemQuery): Map<String, StreamInfo>? {
    if (!companionHas("formats")) return null
    val types = query.types.mapNotNull(::itemTypeName).ifEmpty { null }?.joinToString(",")
    return runCatching {
        get("Nitrate", "Formats", query = mapOf("parentId" to query.libraryId?.let { dashless(listOf(it)) }, "types" to types))
            .decode<List<TitleFormatsDto>>()
            .associate { t ->
                normalizeId(t.id) to StreamInfo(
                    t.video.map { v -> VideoStreamInfo(Codecs.normalize(v.codec), v.bitDepth, Codecs.rangeTags(v.rangeType, v.dvProfile)) }.toSet(),
                    t.audio.mapNotNull { Codecs.normalize(it) }.toSet(),
                )
            }
    }.getOrNull()
}

/**
 * One page of a codec-filtered query: the server's list for everything else (ids only, in its
 * order), narrowed by the codec index, then this page's items fetched by id.
 */
internal suspend fun JellyfinClient.codecFilteredPage(query: ItemQuery, startIndex: Int, limit: Int): Pair<List<MediaItem>, Int> {
    val base = query.copy(videoCodecs = emptyList(), audioCodecs = emptyList(), bitDepths = emptyList(), videoRanges = emptyList())
    val ids = get(
        "Items",
        query = itemQueryParams(base) + mapOf("userId" to userId, "limit" to null, "fields" to null, "enableImages" to false, "enableUserData" to false),
    ).decode<QueryResultDto<ItemDto>>().items.map { normalizeId(it.id) }
    val index = codecIndex(query)
    val matching = ids.filter { id -> index[id]?.let { Codecs.matches(it, query) } == true }
    val page = matching.drop(startIndex).take(limit)
    if (page.isEmpty()) return emptyList<MediaItem>() to matching.size
    val items = get(
        "Items",
        query = mapOf("ids" to page.joinToString(","), "userId" to userId, "fields" to "Overview,PrimaryImageAspectRatio,Genres,SortName", "enableTotalRecordCount" to false),
    ).decode<QueryResultDto<ItemDto>>().items.associateBy { normalizeId(it.id) }
    return page.mapNotNull { items[it]?.toMediaItem() } to matching.size
}

/** The codecs of everything [query] can list in its library, kept for a while (it's a big read). */
internal suspend fun JellyfinClient.codecIndex(query: ItemQuery): Map<String, StreamInfo> = codecIndexLock.withLock {
    val key = "${baseUrl}|${userId}|${query.libraryId}|${query.types.sorted()}"
    val now = Clock.System.now().toEpochMilliseconds()
    codecIndexes[key]?.takeIf { now - it.builtMs < CODEC_INDEX_TTL_MS }?.let { return@withLock it.streams }
    // The plugin keeps this index itself: one small answer instead of every title's streams.
    companionFormats(query)?.let { codecIndexes[key] = CodecIndex(it, now); return@withLock it }
    val found = mutableMapOf<String, Pair<MutableSet<VideoStreamInfo>, MutableSet<String>>>()
    fun add(id: String, streams: List<MediaStreamDto>?) {
        val (video, audio) = found.getOrPut(id) { mutableSetOf<VideoStreamInfo>() to mutableSetOf() }
        streams.orEmpty().forEach { st ->
            when (st.type) {
                "Video" -> video += VideoStreamInfo(Codecs.normalize(st.codec), st.bitDepth, Codecs.rangeTags(st.videoRangeType, st.dvProfile))
                "Audio" -> Codecs.normalize(st.codec)?.let { audio += it }
            }
        }
    }
    // Shows have no streams of their own: their episodes speak for them.
    val shows = ItemKind.SERIES in query.types || query.types.isEmpty()
    val others = query.types.filter { it != ItemKind.SERIES }.ifEmpty { if (query.types.isEmpty()) listOf(ItemKind.MOVIE, ItemKind.VIDEO, ItemKind.MUSIC_VIDEO) else emptyList() }
    suspend fun read(types: List<ItemKind>, each: (ItemDto) -> Unit) {
        var start = 0
        while (true) {
            val page = get(
                "Items",
                query = itemQueryParams(ItemQuery(libraryId = query.libraryId, types = types)) + mapOf(
                    "userId" to userId, "startIndex" to start, "limit" to CODEC_INDEX_PAGE, "fields" to "MediaStreams",
                    "sortBy" to "SortName", "sortOrder" to "Ascending", "enableImages" to false, "enableUserData" to false,
                ),
            ).decode<QueryResultDto<ItemDto>>().items
            page.forEach(each)
            if (page.size < CODEC_INDEX_PAGE) break
            start += page.size
        }
    }
    if (others.isNotEmpty()) read(others) { item -> add(normalizeId(item.id), item.mediaStreams) }
    if (shows) read(listOf(ItemKind.EPISODE)) { ep -> ep.seriesId?.let { add(normalizeId(it), ep.mediaStreams) } }
    val built = found.mapValues { (_, v) -> StreamInfo(v.first.toSet(), v.second.toSet()) }
    codecIndexes[key] = CodecIndex(built, now)
    built
}
