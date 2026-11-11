package dev.nitrate.core.rows

import kotlinx.serialization.Serializable
import dev.nitrate.core.model.MediaItem

/** How a row of items is drawn. Each style picks its own image type and aspect ratio. */
@Serializable
enum class RowStyle(val label: String) {
    /** 2:3 Primary images. */
    POSTER("Posters"),

    /** 16:9 Thumb images, falling back to backdrops. */
    WIDE("Wide"),

    /** One item at a time with full-screen artwork while the row is focused. */
    SHOWCASE("Showcase"),
}

@Serializable
enum class RowSize(val label: String, val posterHeightDp: Int, val wideHeightDp: Int, val bannerHeightDp: Int) {
    SMALL("Small", 180, 120, 160),
    MEDIUM("Medium", 220, 150, 220),
    LARGE("Large", 280, 190, 300),
}

/** Per-row display options. Anything left at the row's default is not stored. */
@Serializable
data class RowDisplay(
    val style: RowStyle = RowStyle.POSTER,
    val size: RowSize = RowSize.MEDIUM,
    val showTitles: Boolean = false,
    val showcase: ShowcaseOptions = ShowcaseOptions(),
)

@Serializable
data class ShowcaseOptions(
    val showLogo: Boolean = true,
    val showDetails: Boolean = true,
    val showRatings: Boolean = true,
    val showOverview: Boolean = true,
    /** Right on the last item wraps to the first. */
    val infiniteScroll: Boolean = true,
    /** Cycle through an item's backdrops while it is shown. */
    val rotateArtwork: Boolean = false,
)

/**
 * A row on the home or a library page.
 * [key] is stable across app versions and is what display settings are saved against.
 */
data class MediaRow(
    val key: String,
    val title: String,
    val items: List<MediaItem>,
    val defaultDisplay: RowDisplay,
)

/** View options for the Playlists library page. */
@Serializable
data class PlaylistLibraryView(
    val image: PlaylistTileImage = PlaylistTileImage.WIDE,
    val progress: PlaylistProgress = PlaylistProgress.NONE,
    val groupByTag: Boolean = false,
)

@Serializable
enum class PlaylistTileImage(val label: String, val columns: Int) {
    /** Thumb or backdrop, else stylized art from the primary image. */
    WIDE("Wide tiles", 3),

    /** The playlist's square primary image (usually the server's collage). */
    SQUARE("Square tiles", 6),
}

@Serializable
enum class PlaylistProgress(val label: String) { NONE("None"), COUNT("Unwatched count"), BAR("Progress bar") }

/** View options for a single playlist page. */
@Serializable
data class PlaylistPageView(
    /** Large header for the focused item over its backdrop. */
    val cinematic: Boolean = true,
)
