package dev.nitrate.core.model

import kotlinx.serialization.Serializable

/**
 * Nitrate's own view of a library item. Screens use this, never a server SDK's types, so the
 * same UI works whichever client library talks to the server (Android SDK today, a
 * multiplatform client for iOS later).
 */
@Serializable
data class MediaItem(
    val id: String,
    val name: String,
    val kind: ItemKind,
    /** The server's sort name (lowercase, articles dropped), when the query asked for it. */
    val sortName: String? = null,
    val mediaKind: MediaKind? = null,
    val overview: String? = null,
    val tagline: String? = null,
    val productionYear: Int? = null,
    /** Last year of a series that has ended. */
    val endYear: Int? = null,
    val officialRating: String? = null,
    val communityRating: Float? = null,
    val criticRating: Float? = null,
    val runtimeMs: Long? = null,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val collection: CollectionKind? = null,
    // Episodes and seasons
    val seriesId: String? = null,
    val seriesName: String? = null,
    val seasonId: String? = null,
    /** Season number of an episode (0 for specials), or the number of a season. */
    val seasonNumber: Int? = null,
    val indexNumber: Int? = null,
    /** Last episode in a multi-episode file (S01E01-E02). */
    val indexNumberEnd: Int? = null,
    val airsBeforeSeason: Int? = null,
    val airsBeforeEpisode: Int? = null,
    val airsAfterSeason: Int? = null,
    val user: UserState = UserState(),
    val images: ItemImages = ItemImages(),
    val people: List<Person> = emptyList(),
    /** Picture and sound quality labels, e.g. 4K, Dolby Vision, Atmos (see QualityBadges). */
    val badges: List<String> = emptyList(),
    /** Music: the album a track is on, and who made it. */
    val album: String? = null,
    val albumId: String? = null,
    val artists: List<String> = emptyList(),
    val albumArtist: String? = null,
    val albumArtistId: String? = null,
    /** This item's entry in the playlist it was listed from, for removing or moving it. */
    val playlistEntryId: String? = null,
    /** When the server added it (epoch ms), where known. */
    val dateAddedMs: Long? = null,
    /** First air or release date (epoch ms), where known. */
    val premiereDateMs: Long? = null,
    /** Copies of this title the server has (4K, 1080p...), when there's more than one. */
    val versions: List<MediaVersion> = emptyList(),
    /** Files the title is split across (part 1, part 2...), when more than one. */
    val partCount: Int? = null,
    /** How a 3D film packs its two views (null: 2D). */
    val video3D: dev.nitrate.core.playback.Video3D? = null,
    val chapters: List<Chapter> = emptyList(),
    /** Trailers on the web (YouTube...), as the server lists them. */
    val remoteTrailers: List<RemoteTrailer> = emptyList(),
    /** How many trailer files and extras (behind the scenes, deleted scenes...) the server has. */
    val localTrailerCount: Int = 0,
    val specialFeatureCount: Int = 0,
    /** What kind of extra this is ("Trailer", "Behind the scenes"...), for extras. */
    val extraType: String? = null,
    /** An episode the library doesn't have: missing, or not aired yet (see [premiereDateMs]). */
    val isMissing: Boolean = false,
    /** Studios or networks that made it. */
    val studios: List<String> = emptyList(),
) {
    val isEpisode get() = kind == ItemKind.EPISODE
    /** Something to play: not an episode the library is missing or that hasn't aired. */
    val isPlayable get() = kind in PLAYABLE && !isMissing

    /** Series name for episodes, otherwise the item's name. */
    val displayTitle get() = if (isEpisode) seriesName ?: name else name

    /** "S2:E5 · Episode name" for episodes, null otherwise. */
    val episodeLabel get() = if (isEpisode) "S${seasonNumber ?: "?"}:E${indexNumber ?: "?"} · $name" else null

    companion object {
        val PLAYABLE = setOf(ItemKind.MOVIE, ItemKind.EPISODE, ItemKind.VIDEO, ItemKind.MUSIC_VIDEO, ItemKind.AUDIO, ItemKind.TV_CHANNEL)
    }
}

@Serializable
enum class ItemKind { MOVIE, SERIES, SEASON, EPISODE, VIDEO, MUSIC_VIDEO, AUDIO, MUSIC_ALBUM, MUSIC_ARTIST, TV_CHANNEL, PLAYLIST, BOX_SET, LIBRARY, FOLDER, PERSON, OTHER }

@Serializable
enum class MediaKind { VIDEO, AUDIO }

/** What a library holds, which decides its pages and rows. */
@Serializable
enum class CollectionKind { MOVIES, TV_SHOWS, MUSIC, MUSIC_VIDEOS, HOME_VIDEOS, BOX_SETS, PLAYLISTS, BOOKS, PHOTOS, LIVE_TV, MIXED }

@Serializable
data class UserState(
    val played: Boolean = false,
    val playedPercentage: Double? = null,
    val positionMs: Long = 0,
    val isFavorite: Boolean = false,
    /** For series, seasons and playlists. */
    val unplayedCount: Int? = null,
    /** When it was last played (watched to the end or not), if ever. */
    val lastPlayedMs: Long? = null,
    /** How many times it's been played (the server counts each finish). */
    val playCount: Int = 0,
)

/**
 * Image tags for building URLs (tags make URLs cache-safe). Episodes fall back to their
 * series' artwork via the parent fields.
 */
/** What a library can be filtered by, as the server lists it. */
@Serializable
data class LibraryFilters(
    val genres: List<String> = emptyList(),
    val years: List<Int> = emptyList(),
    val officialRatings: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
)

/** One copy of a title, as a choice of version. */
@Serializable
data class MediaVersion(val id: String, val name: String)

/** A chapter mark: where it starts, its name and picture. */
@Serializable
data class Chapter(val startMs: Long, val name: String, val imageTag: String? = null)

@Serializable
data class RemoteTrailer(val url: String, val name: String? = null)

@Serializable
data class ItemImages(
    val primary: String? = null,
    val thumb: String? = null,
    val logo: String? = null,
    val backdrops: List<String> = emptyList(),
    val parentBackdropItemId: String? = null,
    val parentBackdrops: List<String> = emptyList(),
    val parentLogoItemId: String? = null,
    val parentLogo: String? = null,
    val seriesPrimary: String? = null,
    /** A track's album cover, when the track has no art of its own. */
    val albumPrimary: String? = null,
)

@Serializable
data class Person(val id: String, val name: String, val role: String? = null, val imageTag: String? = null)

enum class ImageKind { PRIMARY, THUMB, BACKDROP, LOGO }

/** A title's theme songs; [ownerId] is the item they belong to when that's not the title itself (its series). */
data class ThemeMusicInfo(val songs: List<MediaItem>, val ownerId: String?) {
    fun inheritedFrom(itemId: String) = ownerId?.takeIf { it != itemId }
}
