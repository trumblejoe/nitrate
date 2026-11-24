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
    val normalizationGain: Double? = null,
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

/** The Companion plugin's time for one tagged request. */
@Serializable
internal data class QueryTimeDto(val tag: String, val ms: Long)

/** The Companion plugin's Home in one answer. */
@Serializable
internal data class HomeBundleDto(
    val resume: List<ItemDto>? = null,
    val nextUp: List<ItemDto>? = null,
    val latest: List<LatestBundleDto> = emptyList(),
    val because: List<BecauseBundleDto>? = null,
)

@Serializable
internal data class LatestBundleDto(val libraryId: String, val items: List<ItemDto> = emptyList())

@Serializable
internal data class BecauseBundleDto(val seedName: String, val items: List<ItemDto> = emptyList())

/** A title's formats from the Companion plugin's index. */
@Serializable
internal data class TitleFormatsDto(val id: String, val video: List<VideoFormatDto> = emptyList(), val audio: List<String> = emptyList())

@Serializable
internal data class VideoFormatDto(val codec: String? = null, val bitDepth: Int? = null, val rangeType: String? = null, val dvProfile: Int? = null)

/** One kind of request across the household (Companion, admins). */
@Serializable
internal data class HouseholdQueryDto(val shape: String, val count: Int, val people: Int, val totalMs: Long, val medianMs: Long, val slowMs: Long, val maxMs: Long)

@Serializable
internal data class QueryTrendDto(val shape: String, val days: List<QueryTrendDayDto> = emptyList())

@Serializable
internal data class QueryTrendDayDto(val day: String, val count: Long = 0, val medianMs: Long = 0, val slowMs: Long = 0)

@Serializable
internal data class SegmentFeedbackDto(val itemId: String, val part: String)

@Serializable
internal data class SittingDto(val itemId: String, val kind: String = "", val name: String = "", val seriesName: String? = null, val startedAt: Long = 0, val endedAt: Long = 0, val watchedMs: Long = 0)

@Serializable
internal data class ProblemPostDto(val itemId: String, val deviceId: String, val deviceName: String, val kind: String, val reason: String? = null)

@Serializable
internal data class ProblemTitleDto(val itemId: String, val name: String = "", val devices: Int = 0, val failed: Int = 0, val converted: Int = 0, val reasons: List<String> = emptyList(), val deviceNames: List<String> = emptyList(), val lastAt: Long = 0)

@Serializable
internal data class MomentDto(val id: String, val userId: String, val userName: String = "", val itemId: String, val positionMs: Long = 0, val note: String? = null, val groupId: String? = null, val at: Long = 0)

@Serializable
internal data class NoteDto(
    val id: String, val userId: String, val userName: String = "", val itemId: String, val positionMs: Long = 0,
    val kind: String = "Text", val text: String = "", val groupIds: List<String> = emptyList(), val at: Long = 0, val moment: Boolean = false,
    val brought: Boolean = false, val chatId: String? = null,
)

@Serializable
internal data class NoteGroupDto(val id: String, val title: String = "")

@Serializable
internal data class NotesPageDto(val groups: List<NoteGroupDto> = emptyList(), val notes: List<NoteDto> = emptyList())

@Serializable
internal data class NotePostDto(
    val itemId: String, val positionMs: Long, val text: String, val kind: String = "Text", val sessionId: String? = null, val groupIds: List<String>? = null,
    val chats: Boolean = false, val chatId: String? = null, val buildsOn: String? = null,
)

@Serializable
internal data class ChatViewDto(
    val id: String, val groupId: String? = null, val created: Long = 0, val buildsOn: String? = null, val sessionId: String? = null,
    val people: List<String> = emptyList(), val count: Int = 0, val earlier: Boolean = false,
)

@Serializable
internal data class ChatsPageDto(val groups: List<NoteGroupDto> = emptyList(), val chats: List<ChatViewDto> = emptyList())

@Serializable
internal data class ChatNotesDto(val id: String, val chain: List<String> = emptyList(), val notes: List<NoteDto> = emptyList())

@Serializable
internal data class AlongPostDto(val itemId: String, val chatId: String?)

@Serializable
internal data class SessionChatDto(val chatId: String? = null, val along: String? = null, val by: String? = null, val notes: List<NoteDto> = emptyList())

@Serializable
internal data class BringPostDto(val groupId: String?)

@Serializable
internal data class BroughtNotesDto(val by: String? = null, val notes: List<NoteDto> = emptyList())

@Serializable
internal data class ChatRecordDto(val from: String, val name: String = "", val kind: String = "Text", val text: String = "", val positionMs: Long? = null, val at: Long = 0)
