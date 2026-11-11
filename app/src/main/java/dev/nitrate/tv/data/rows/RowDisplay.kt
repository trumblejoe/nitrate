package dev.nitrate.tv.data.rows

import kotlinx.serialization.Serializable
import org.jellyfin.sdk.model.api.BaseItemDto

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
    val items: List<BaseItemDto>,
    val defaultDisplay: RowDisplay,
)
