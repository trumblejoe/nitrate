package dev.nitrate.core.jellyfin

import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

// Jellyfin's JSON uses PascalCase names ("SeriesId"); these DTOs use Kotlin camelCase and the
// [JellyfinJson] naming strategy maps between them. Only the fields Nitrate uses are declared;
// unknown fields are ignored, so newer servers keep working.

internal object PascalCase : JsonNamingStrategy {
    override fun serialNameForJson(descriptor: SerialDescriptor, elementIndex: Int, serialName: String) =
        serialName.replaceFirstChar { it.uppercaseChar() }
}

internal val JellyfinJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
    namingStrategy = PascalCase
}

@Serializable
internal data class QueryResultDto<T>(val items: List<T> = emptyList(), val totalRecordCount: Int = 0)

@Serializable
internal data class UserDataDto(
    val played: Boolean = false,
    val playedPercentage: Double? = null,
    val playbackPositionTicks: Long = 0,
    val isFavorite: Boolean = false,
    val unplayedItemCount: Int? = null,
    val lastPlayedDate: String? = null,
    val playCount: Int? = null,
)

@Serializable
internal data class PersonDto(val id: String, val name: String? = null, val role: String? = null, val primaryImageTag: String? = null)

@Serializable
internal data class ThemeMediaDto(val items: List<ItemDto> = emptyList(), val ownerId: String? = null)

@Serializable
internal data class ItemDto(
    val id: String,
    val name: String? = null,
    val sortName: String? = null,
    val dateCreated: String? = null,
    val playlistItemId: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val artists: List<String>? = null,
    val albumArtist: String? = null,
    val albumArtists: List<NameIdDto>? = null,
    val albumPrimaryImageTag: String? = null,
    val type: String? = null,
    val mediaType: String? = null,
    /** The file's kind (books: "epub", "cbz", "pdf"…). */
    val container: String? = null,
    val overview: String? = null,
    val providerIds: Map<String, String?>? = null,
    val taglines: List<String>? = null,
    val productionYear: Int? = null,
    val endDate: String? = null,
    val officialRating: String? = null,
    val communityRating: Float? = null,
    val criticRating: Float? = null,
    val runTimeTicks: Long? = null,
    val genres: List<String>? = null,
    val tags: List<String>? = null,
    val collectionType: String? = null,
    val parentId: String? = null,
    val seriesId: String? = null,
    val seriesName: String? = null,
    val seasonId: String? = null,
    val parentIndexNumber: Int? = null,
    val indexNumber: Int? = null,
    val indexNumberEnd: Int? = null,
    val airsBeforeSeasonNumber: Int? = null,
    val airsBeforeEpisodeNumber: Int? = null,
    val airsAfterSeasonNumber: Int? = null,
    val userData: UserDataDto? = null,
    val imageTags: Map<String, String>? = null,
    val backdropImageTags: List<String>? = null,
    val parentBackdropItemId: String? = null,
    val parentBackdropImageTags: List<String>? = null,
    val parentLogoItemId: String? = null,
    val parentLogoImageTag: String? = null,
    val seriesPrimaryImageTag: String? = null,
    val people: List<PersonDto>? = null,
    val locationType: String? = null,
    val premiereDate: String? = null,
    val partCount: Int? = null,
    @kotlinx.serialization.SerialName("Video3DFormat") val video3DFormat: String? = null,
    val chapters: List<ChapterInfoDto>? = null,
    val remoteTrailers: List<MediaUrlDto>? = null,
    val localTrailerCount: Int? = null,
    val specialFeatureCount: Int? = null,
    val extraType: String? = null,
    val studios: List<NameIdDto>? = null,
    val mediaSources: List<MediaSourceDto>? = null,
    val width: Int? = null,
    val height: Int? = null,
    val mediaStreams: List<MediaStreamDto>? = null,
    /** Media source id -> thumbnail width -> sheet layout. */
    val trickplay: Map<String, Map<String, TrickplayInfoDto>>? = null,
)

@Serializable
internal data class MediaStreamDto(
    val index: Int = 0,
    val type: String? = null,
    val codec: String? = null,
    val profile: String? = null,
    val language: String? = null,
    val displayTitle: String? = null,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
    val isExternal: Boolean = false,
    val deliveryMethod: String? = null,
    val deliveryUrl: String? = null,
    val channels: Int? = null,
    val sampleRate: Int? = null,
    val channelLayout: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val bitDepth: Int? = null,
    val videoRangeType: String? = null,
    /** Dolby Vision profile (5, 7, 8...), when the stream carries Dolby Vision. */
    val dvProfile: Int? = null,
    val isInterlaced: Boolean = false,
    val realFrameRate: Float? = null,
    val averageFrameRate: Float? = null,
)

@Serializable
internal data class MediaAttachmentDto(val index: Int = 0, val fileName: String? = null, val mimeType: String? = null)

@Serializable
internal data class MediaSourceDto(
    val id: String? = null,
    val name: String? = null,
    val container: String? = null,
    val bitrate: Int? = null,
    val size: Long? = null,
    val eTag: String? = null,
    val mediaStreams: List<MediaStreamDto>? = null,
    val mediaAttachments: List<MediaAttachmentDto>? = null,
    val supportsDirectPlay: Boolean = false,
    val supportsDirectStream: Boolean = false,
    val transcodingUrl: String? = null,
    val transcodingSubProtocol: String? = null,
    val defaultAudioStreamIndex: Int? = null,
    val defaultSubtitleStreamIndex: Int? = null,
)

@Serializable
internal data class PlaybackInfoResponseDto(
    val mediaSources: List<MediaSourceDto> = emptyList(),
    val playSessionId: String? = null,
    val errorCode: String? = null,
)

@Serializable
internal data class PlaybackInfoRequestDto(
    val deviceProfile: DeviceProfileDto,
    val maxStreamingBitrate: Int,
    val startTimeTicks: Long,
    val audioStreamIndex: Int? = null,
    val subtitleStreamIndex: Int? = null,
    val mediaSourceId: String? = null,
    val enableDirectPlay: Boolean,
    val enableDirectStream: Boolean,
    val enableTranscoding: Boolean,
    val allowVideoStreamCopy: Boolean,
    val allowAudioStreamCopy: Boolean,
    val autoOpenLiveStream: Boolean,
)

@Serializable
internal data class UserDto(val id: String, val name: String? = null, val hasPassword: Boolean = true, val primaryImageTag: String? = null)

@Serializable
internal data class AuthenticationResultDto(val user: UserDto? = null, val accessToken: String? = null)

@Serializable
internal data class AuthenticateByNameDto(val username: String, val pw: String)

@Serializable
internal data class QuickConnectResultDto(val secret: String = "", val code: String = "", val authenticated: Boolean = false)

@Serializable
internal data class QuickConnectDto(val secret: String)

@Serializable
internal data class PublicSystemInfoDto(val serverName: String? = null, val version: String? = null, val id: String? = null)

@Serializable
internal data class ServerDiscoveryDto(val address: String? = null, val id: String? = null, val name: String? = null)

@Serializable
internal data class PlaybackStartDto(
    val itemId: String,
    val playSessionId: String?,
    val mediaSourceId: String?,
    val positionTicks: Long,
    val canSeek: Boolean = true,
    val isPaused: Boolean = false,
    val isMuted: Boolean = false,
    val playMethod: String,
    val repeatMode: String = "RepeatNone",
    val playbackOrder: String = "Default",
    /** What's playing, so the dashboard shows it and (with Remember selections on) the server keeps it for the title. -1: no subtitles. */
    val audioStreamIndex: Int? = null,
    val subtitleStreamIndex: Int? = null,
)

@Serializable
internal data class PlaybackStopDto(
    val itemId: String,
    val playSessionId: String?,
    val mediaSourceId: String?,
    val positionTicks: Long,
    val failed: Boolean = false,
)

@Serializable
internal data class UpdateUserDataDto(val playbackPositionTicks: Long, val played: Boolean, val lastPlayedDate: String)

@Serializable
internal data class TrickplayInfoDto(
    val width: Int = 0,
    val height: Int = 0,
    val tileWidth: Int = 0,
    val tileHeight: Int = 0,
    val thumbnailCount: Int = 0,
    val interval: Int = 0,
)

@Serializable
internal data class MediaSegmentDto(val type: String = "Unknown", val startTicks: Long = 0, val endTicks: Long = 0)

@Serializable
internal data class RemoteSubtitleDto(
    val id: String,
    val name: String? = null,
    val threeLetterISOLanguageName: String? = null,
    val providerName: String? = null,
    val format: String? = null,
    val downloadCount: Int? = null,
    val communityRating: Float? = null,
    val isHashMatch: Boolean? = null,
    val forced: Boolean? = null,
    val hearingImpaired: Boolean? = null,
    val machineTranslated: Boolean? = null,
    val aiTranslated: Boolean? = null,
)

@Serializable
internal data class LyricsDto(val metadata: LyricMetadataDto? = null, val lyrics: List<LyricLineDto> = emptyList())

@Serializable
internal data class LyricMetadataDto(val isSynced: Boolean? = null)

@Serializable
internal data class LyricLineDto(val text: String? = null, val start: Long? = null)

/** A command with arguments for another device (Sessions/{id}/Command). */
@Serializable
internal data class GeneralCommandDto(val name: String, val arguments: Map<String, String> = emptyMap())

@Serializable
internal data class CapabilitiesDto(
    val playableMediaTypes: List<String>,
    val supportedCommands: List<String>,
    val supportsMediaControl: Boolean,
    val supportsPersistentIdentifier: Boolean = true,
)

// ---- SyncPlay ----

@Serializable
internal data class NewGroupRequestDto(val groupName: String)

@Serializable
internal data class JoinGroupRequestDto(val groupId: String)

@Serializable
internal data class PlayRequestDto(val playingQueue: List<String>, val playingItemPosition: Int, val startPositionTicks: Long)

@Serializable
internal data class SeekRequestDto(val positionTicks: Long)

/** Body of both /SyncPlay/Ready and /SyncPlay/Buffering. */
@Serializable
internal data class ReadyRequestDto(
    @kotlinx.serialization.SerialName("When") val whenTime: String,
    val positionTicks: Long,
    val isPlaying: Boolean,
    val playlistItemId: String,
)

@Serializable
internal data class NextItemRequestDto(val playlistItemId: String)

@Serializable
internal data class PingRequestDto(val ping: Long)

@Serializable
internal data class UtcTimeResponseDto(val requestReceptionTime: String, val responseTransmissionTime: String)

@Serializable
internal data class CreatePlaylistDto(val name: String, val ids: List<String>, val userId: String?, val mediaType: String?)

@Serializable
internal data class UpdatePlaylistDto(val name: String)

@Serializable
internal data class CreationResultDto(val id: String)

@Serializable
internal data class NameIdDto(val name: String? = null, val id: String)

@Serializable
internal data class ClientLogDocumentResponseDto(val fileName: String? = null)

/** One match from `Search/Hints`: the cheap as-you-type lookup. */
@Serializable
internal data class SearchHintDto(
    val id: String? = null,
    val itemId: String? = null,
    val name: String? = null,
    val type: String? = null,
    val productionYear: Int? = null,
    val indexNumber: Int? = null,
    val parentIndexNumber: Int? = null,
    val series: String? = null,
    val primaryImageTag: String? = null,
    val thumbImageTag: String? = null,
    val backdropImageTag: String? = null,
    val backdropImageItemId: String? = null,
)

@Serializable
internal data class SearchHintResultDto(val searchHints: List<SearchHintDto> = emptyList(), val totalRecordCount: Int = 0)

@Serializable
internal data class ChapterInfoDto(val startPositionTicks: Long = 0, val name: String? = null, val imageTag: String? = null)

@Serializable
internal data class MediaUrlDto(val url: String? = null, val name: String? = null)

/** Choices a library offers for filtering (`Items/Filters`). */
@Serializable
internal data class QueryFiltersLegacyDto(
    val genres: List<String>? = null,
    val tags: List<String>? = null,
    val officialRatings: List<String>? = null,
    val years: List<Int>? = null,
)
