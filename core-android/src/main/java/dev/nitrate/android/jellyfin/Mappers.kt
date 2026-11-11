package dev.nitrate.android.jellyfin

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
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.MediaStream as SdkStream
import org.jellyfin.sdk.model.api.PlayMethod as SdkPlayMethod

/** Jellyfin SDK types to Nitrate's own. Server times are 100 ns ticks; ours are milliseconds. */
internal fun Long.ticksToMs() = this / 10_000
internal fun Long.msToTicks() = this * 10_000

internal fun BaseItemDto.toMediaItem(): MediaItem {
    val kind = type.toKind()
    return MediaItem(
        id = id.toString(),
        name = name.orEmpty(),
        kind = kind,
        mediaKind = when (mediaType) {
            MediaType.VIDEO -> MediaKind.VIDEO
            MediaType.AUDIO -> MediaKind.AUDIO
            else -> null
        },
        overview = overview,
        tagline = taglines?.firstOrNull(),
        productionYear = productionYear,
        endYear = endDate?.year,
        officialRating = officialRating,
        communityRating = communityRating,
        criticRating = criticRating,
        runtimeMs = runTimeTicks?.ticksToMs(),
        genres = genres.orEmpty(),
        tags = tags.orEmpty(),
        collection = collectionType?.toKind(),
        seriesId = seriesId?.toString(),
        seriesName = seriesName,
        seasonId = seasonId?.toString(),
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
            primary = imageTags?.get(ImageType.PRIMARY),
            thumb = imageTags?.get(ImageType.THUMB),
            logo = imageTags?.get(ImageType.LOGO),
            backdrops = backdropImageTags.orEmpty(),
            parentBackdropItemId = parentBackdropItemId?.toString(),
            parentBackdrops = parentBackdropImageTags.orEmpty(),
            parentLogoItemId = parentLogoItemId?.toString(),
            parentLogo = parentLogoImageTag,
            seriesPrimary = seriesPrimaryImageTag,
        ),
        people = people.orEmpty().map { Person(it.id.toString(), it.name.orEmpty(), it.role, it.primaryImageTag) },
    )
}

internal fun BaseItemKind.toKind(): ItemKind = when (this) {
    BaseItemKind.MOVIE -> ItemKind.MOVIE
    BaseItemKind.SERIES -> ItemKind.SERIES
    BaseItemKind.SEASON -> ItemKind.SEASON
    BaseItemKind.EPISODE -> ItemKind.EPISODE
    BaseItemKind.VIDEO -> ItemKind.VIDEO
    BaseItemKind.MUSIC_VIDEO -> ItemKind.MUSIC_VIDEO
    BaseItemKind.AUDIO -> ItemKind.AUDIO
    BaseItemKind.MUSIC_ALBUM -> ItemKind.MUSIC_ALBUM
    BaseItemKind.PLAYLIST -> ItemKind.PLAYLIST
    BaseItemKind.BOX_SET -> ItemKind.BOX_SET
    BaseItemKind.COLLECTION_FOLDER, BaseItemKind.USER_VIEW,
    BaseItemKind.PLAYLISTS_FOLDER, BaseItemKind.MANUAL_PLAYLISTS_FOLDER -> ItemKind.LIBRARY
    BaseItemKind.FOLDER -> ItemKind.FOLDER
    BaseItemKind.PERSON -> ItemKind.PERSON
    else -> ItemKind.OTHER
}

internal fun CollectionType.toKind(): CollectionKind = when (this) {
    CollectionType.MOVIES -> CollectionKind.MOVIES
    CollectionType.TVSHOWS -> CollectionKind.TV_SHOWS
    CollectionType.MUSIC -> CollectionKind.MUSIC
    CollectionType.MUSICVIDEOS -> CollectionKind.MUSIC_VIDEOS
    CollectionType.HOMEVIDEOS -> CollectionKind.HOME_VIDEOS
    CollectionType.BOXSETS -> CollectionKind.BOX_SETS
    CollectionType.PLAYLISTS -> CollectionKind.PLAYLISTS
    CollectionType.BOOKS -> CollectionKind.BOOKS
    CollectionType.PHOTOS -> CollectionKind.PHOTOS
    CollectionType.LIVETV -> CollectionKind.LIVE_TV
    else -> CollectionKind.MIXED
}

internal fun SdkStream.toMediaStream() = MediaStream(
    index = index,
    kind = when (type) {
        MediaStreamType.VIDEO -> StreamKind.VIDEO
        MediaStreamType.AUDIO -> StreamKind.AUDIO
        MediaStreamType.SUBTITLE -> StreamKind.SUBTITLE
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
        SubtitleDeliveryMethod.EMBED -> SubtitleDelivery.EMBED
        SubtitleDeliveryMethod.EXTERNAL -> SubtitleDelivery.EXTERNAL
        SubtitleDeliveryMethod.HLS -> SubtitleDelivery.HLS
        SubtitleDeliveryMethod.ENCODE -> SubtitleDelivery.ENCODE
        else -> null
    },
    deliveryUrl = deliveryUrl,
    channels = channels,
    channelLayout = channelLayout,
    width = width,
    height = height,
    bitDepth = bitDepth,
    rangeType = videoRangeType?.serialName,
)

internal fun MediaSourceInfo.toMediaSource() = MediaSource(
    id = id,
    container = container,
    bitrate = bitrate,
    streams = mediaStreams.orEmpty().map { it.toMediaStream() },
)

internal fun PlayMethod.toSdk(): SdkPlayMethod = when (this) {
    PlayMethod.DIRECT_PLAY -> SdkPlayMethod.DIRECT_PLAY
    PlayMethod.DIRECT_STREAM -> SdkPlayMethod.DIRECT_STREAM
    PlayMethod.TRANSCODE -> SdkPlayMethod.TRANSCODE
}
