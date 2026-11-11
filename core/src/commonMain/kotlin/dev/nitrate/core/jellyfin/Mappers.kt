package dev.nitrate.core.jellyfin

import dev.nitrate.core.model.CollectionKind
import dev.nitrate.core.model.ItemImages
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.MediaKind
import dev.nitrate.core.model.MediaSource
import dev.nitrate.core.model.MediaStream
import dev.nitrate.core.model.Person
import dev.nitrate.core.model.PlayMethod
import dev.nitrate.core.model.StreamKind
import dev.nitrate.core.model.SubtitleDelivery
import dev.nitrate.core.model.UserState
import dev.nitrate.core.pages.QualityBadges

/** Server times are 100 ns ticks; Nitrate's are milliseconds. */
internal fun Long.ticksToMs() = this / 10_000
internal fun Long.msToTicks() = this * 10_000

/**
 * Jellyfin's JSON writes ids without dashes; Nitrate keeps the dashed form (as the Kotlin SDK
 * did) so ids stored in settings and row keys stay the same. The server accepts both.
 */
internal fun normalizeId(id: String): String {
    val hex = id.replace("-", "").lowercase()
    if (hex.length != 32 || hex.any { it !in '0'..'9' && it !in 'a'..'f' }) return id
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}

internal fun ItemDto.toMediaItem(): MediaItem {
    val kind = itemKind(type)
    return MediaItem(
        id = normalizeId(id),
        name = name.orEmpty(),
        dateAddedMs = dateCreated?.let(::parseDateMs),
        kind = kind,
        mediaKind = when (mediaType) {
            "Video" -> MediaKind.VIDEO
            "Audio" -> MediaKind.AUDIO
            else -> null
        },
        overview = overview,
        tagline = taglines?.firstOrNull(),
        productionYear = productionYear,
        endYear = endDate?.take(4)?.toIntOrNull(),
        officialRating = officialRating,
        communityRating = communityRating,
        criticRating = criticRating,
        runtimeMs = runTimeTicks?.ticksToMs(),
        genres = genres.orEmpty(),
        tags = tags.orEmpty(),
        collection = collectionType?.let(::collectionKind),
        seriesId = seriesId?.let(::normalizeId),
        seriesName = seriesName,
        seasonId = seasonId?.let(::normalizeId),
        // A season's own number is its index; an episode's is its parent's.
        seasonNumber = if (kind == ItemKind.SEASON) indexNumber else parentIndexNumber,
        indexNumber = indexNumber,
        indexNumberEnd = indexNumberEnd,
        airsBeforeSeason = airsBeforeSeasonNumber,
        airsBeforeEpisode = airsBeforeEpisodeNumber,
        airsAfterSeason = airsAfterSeasonNumber,
        user = UserState(
            played = userData?.played == true,
            playedPercentage = userData?.playedPercentage,
            positionMs = (userData?.playbackPositionTicks ?: 0L).ticksToMs(),
            isFavorite = userData?.isFavorite == true,
            unplayedCount = userData?.unplayedItemCount,
        ),
        images = ItemImages(
            primary = imageTags?.get("Primary"),
            thumb = imageTags?.get("Thumb"),
            logo = imageTags?.get("Logo"),
            backdrops = backdropImageTags.orEmpty(),
            parentBackdropItemId = parentBackdropItemId?.let(::normalizeId),
            parentBackdrops = parentBackdropImageTags.orEmpty(),
            parentLogoItemId = parentLogoItemId?.let(::normalizeId),
            parentLogo = parentLogoImageTag,
            seriesPrimary = seriesPrimaryImageTag,
        ),
        people = people.orEmpty().map { Person(normalizeId(it.id), it.name.orEmpty(), it.role, it.primaryImageTag) },
        badges = badges(),
    )
}

internal fun itemKind(type: String?): ItemKind = when (type) {
    "Movie" -> ItemKind.MOVIE
    "Series" -> ItemKind.SERIES
    "Season" -> ItemKind.SEASON
    "Episode" -> ItemKind.EPISODE
    "Video" -> ItemKind.VIDEO
    "MusicVideo" -> ItemKind.MUSIC_VIDEO
    "Audio" -> ItemKind.AUDIO
    "MusicAlbum" -> ItemKind.MUSIC_ALBUM
    "Playlist" -> ItemKind.PLAYLIST
    "BoxSet" -> ItemKind.BOX_SET
    "CollectionFolder", "UserView", "PlaylistsFolder", "ManualPlaylistsFolder" -> ItemKind.LIBRARY
    "Folder" -> ItemKind.FOLDER
    "Person" -> ItemKind.PERSON
    else -> ItemKind.OTHER
}

internal fun collectionKind(type: String): CollectionKind = when (type.lowercase()) {
    "movies" -> CollectionKind.MOVIES
    "tvshows" -> CollectionKind.TV_SHOWS
    "music" -> CollectionKind.MUSIC
    "musicvideos" -> CollectionKind.MUSIC_VIDEOS
    "homevideos" -> CollectionKind.HOME_VIDEOS
    "boxsets" -> CollectionKind.BOX_SETS
    "playlists" -> CollectionKind.PLAYLISTS
    "books" -> CollectionKind.BOOKS
    "photos" -> CollectionKind.PHOTOS
    "livetv" -> CollectionKind.LIVE_TV
    else -> CollectionKind.MIXED
}

internal fun MediaStreamDto.toMediaStream() = MediaStream(
    index = index,
    kind = when (type) {
        "Video" -> StreamKind.VIDEO
        "Audio" -> StreamKind.AUDIO
        "Subtitle" -> StreamKind.SUBTITLE
        else -> StreamKind.OTHER
    },
    codec = codec,
    profile = profile,
    language = language,
    title = displayTitle,
    isDefault = isDefault,
    isForced = isForced,
    isExternal = isExternal,
    delivery = when (deliveryMethod) {
        "Embed" -> SubtitleDelivery.EMBED
        "External" -> SubtitleDelivery.EXTERNAL
        "Hls" -> SubtitleDelivery.HLS
        "Encode" -> SubtitleDelivery.ENCODE
        else -> null
    },
    deliveryUrl = deliveryUrl,
    channels = channels,
    channelLayout = channelLayout,
    width = width,
    height = height,
    bitDepth = bitDepth,
    rangeType = videoRangeType,
)

internal fun MediaSourceDto.toMediaSource() = MediaSource(id = id, container = container, bitrate = bitrate, streams = mediaStreams.orEmpty().map { it.toMediaStream() }, size = size)

internal fun PlayMethod.wire() = when (this) {
    PlayMethod.DIRECT_PLAY -> "DirectPlay"
    PlayMethod.DIRECT_STREAM -> "DirectStream"
    PlayMethod.TRANSCODE -> "Transcode"
}

/** Quality labels from the item's size and, when the response includes them, its streams. */
internal fun ItemDto.badges(): List<String> {
    val streams = mediaStreams ?: mediaSources?.firstOrNull()?.mediaStreams.orEmpty()
    val video = streams.firstOrNull { it.type == "Video" }
    val audio = streams.filter { it.type == "Audio" }.map { QualityBadges.AudioInfo(it.codec, it.profile, it.displayTitle, it.channels) }
    return QualityBadges.of(video?.width ?: width, video?.height ?: height, video?.videoRangeType, audio)
}

@OptIn(kotlin.time.ExperimentalTime::class)
internal fun parseDateMs(iso: String): Long? = runCatching { kotlin.time.Instant.parse(iso).toEpochMilliseconds() }.getOrNull()
