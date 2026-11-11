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
) {
    val isEpisode get() = kind == ItemKind.EPISODE
    val isPlayable get() = kind in PLAYABLE

    /** Series name for episodes, otherwise the item's name. */
    val displayTitle get() = if (isEpisode) seriesName ?: name else name

    /** "S2:E5 · Episode name" for episodes, null otherwise. */
    val episodeLabel get() = if (isEpisode) "S${seasonNumber ?: "?"}:E${indexNumber ?: "?"} · $name" else null

    companion object {
        val PLAYABLE = setOf(ItemKind.MOVIE, ItemKind.EPISODE, ItemKind.VIDEO, ItemKind.MUSIC_VIDEO, ItemKind.AUDIO)
    }
}

@Serializable
enum class ItemKind { MOVIE, SERIES, SEASON, EPISODE, VIDEO, MUSIC_VIDEO, AUDIO, MUSIC_ALBUM, PLAYLIST, BOX_SET, LIBRARY, FOLDER, PERSON, OTHER }

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
)

/**
 * Image tags for building URLs (tags make URLs cache-safe). Episodes fall back to their
 * series' artwork via the parent fields.
 */
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
)

@Serializable
data class Person(val id: String, val name: String, val role: String? = null, val imageTag: String? = null)

enum class ImageKind { PRIMARY, THUMB, BACKDROP, LOGO }
