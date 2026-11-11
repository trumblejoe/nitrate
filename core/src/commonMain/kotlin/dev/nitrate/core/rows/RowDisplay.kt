package dev.nitrate.core.rows

import kotlinx.serialization.Serializable
import dev.nitrate.core.model.MediaItem

/** How a row of items is drawn. Each style picks its own image type and aspect ratio. */
@Serializable
enum class RowStyle(val label: String) {
    /** 2:3 Primary images. */
    POSTER("Posters"),

    /** 16:9 Thumb images, falling back to backdrops. */
    WIDE("Artwork"),

    /** Big artwork, one item at a time (TV: full screen while focused; phone: large swipeable cards). */
    SHOWCASE("Showcase"),

    /** A tilted strip of film; the frame in the gate (focused, or centred on a phone) is named below it. */
    FILM_STRIP("Film strip"),

    /** Two titles to a card, split by a lightning bolt; pick a side. */
    LIGHTNING("Lightning split"),

    /** Half colour, half monochrome under tattoo-like linework, with the title up the edge. */
    CRESSIDA("Cressida"),
}

/** Shapes the decorative row styles share, so the phone and TV draw them alike. Fractions of the card. */
object RowArt {
    /** The lightning bolt from top to bottom, as (x, y) fractions. */
    val bolt: List<Pair<Float, Float>> = listOf(0.60f to 0f, 0.43f to 0.34f, 0.61f to 0.38f, 0.36f to 0.70f, 0.55f to 0.73f, 0.41f to 1f)

    /** Where the bolt crosses height [y] (0–1), for telling which side a touch landed on. */
    fun boltX(y: Float): Float {
        for (i in 0 until bolt.lastIndex) {
            val (x0, y0) = bolt[i]
            val (x1, y1) = bolt[i + 1]
            if (y in minOf(y0, y1)..maxOf(y0, y1) && y1 != y0) return x0 + (x1 - x0) * (y - y0) / (y1 - y0)
        }
        return bolt.last().first
    }

    /** Cressida: where colour gives way to the marked side, and how far the seam bows. */
    const val CRESSIDA_SPLIT = 0.47f
    const val CRESSIDA_BOW = 0.08f
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
    /** The linework over a Cressida row's marked side. */
    val ink: CressidaInk = CressidaInk.IVY,
    /** How the ink is coloured: picked per card from its artwork, the app accent, or [inkArgb]. */
    val inkTint: InkTint = InkTint.SMART,
    /** The ink colour when [inkTint] is CUSTOM (ARGB). */
    val inkArgb: Long = InkPalette.GOLD,
)

@Serializable
enum class InkTint(val label: String) {
    /** Chosen from each card's artwork so it stands out without clashing. */
    SMART("Smart"),
    ACCENT("Accent"),
    CUSTOM("Custom"),
}

/** Designs for the Cressida style's ink. */
@Serializable
enum class CressidaInk(val label: String) {
    IVY("Ivy"),
    TRIBAL("Tribal"),
    DRAGON("Dragon"),
    CONSTELLATION("Constellation"),
    WAVES("Waves"),
    DECO("Deco"),
    MAGIC("Magic"),
    SONGBIRD("Songbird"),
    FLAMES("Flames"),
    GEOMETRY("Geometry"),
    THORNS("Thorns"),
    CIRCUIT("Circuit"),
}

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

/** Defaults that put artwork first: "Latest" rows as showcases, other poster rows as titled artwork. */
object ArtworkFirst {
    fun display(row: MediaRow): RowDisplay = when {
        row.defaultDisplay.style != RowStyle.POSTER -> row.defaultDisplay
        row.key.startsWith("home/latest/") -> row.defaultDisplay.copy(style = RowStyle.SHOWCASE)
        else -> row.defaultDisplay.copy(style = RowStyle.WIDE, showTitles = true)
    }
}

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
