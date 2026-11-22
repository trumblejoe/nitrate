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

    /** Retired: shown as [WIDE] (see [RowDisplay.normalized]); kept so saved settings still read. */
    SHOWCASE("Showcase"),

    /** Retired: shown as [WIDE] with its cards hidden (see [RowDisplay.normalized]). */
    FULL_SHOWCASE("Full Showcase"),

    /** A tilted strip of film; the frame in the gate (focused, or centred on a phone) is named below it. */
    FILM_STRIP("Film strip"),

    /** Two titles to a card, split by a lightning bolt; pick a side. */
    LIGHTNING("Lightning split"),

    /** Half colour, half monochrome under tattoo-like linework, with the title up the edge. */
    CRESSIDA("Cressida"),
    ;

    /** Moves through the styles that can be chosen, round from the last to the first. */
    fun step(by: Int): RowStyle {
        val i = choices.indexOf(this).let { if (it < 0) choices.indexOf(WIDE) else it }
        return choices[(i + by).mod(choices.size)]
    }

    companion object {
        /** The styles offered in pickers, Fast-forward/Rewind and the row menus. */
        val choices: List<RowStyle> = listOf(POSTER, WIDE, FILM_STRIP, LIGHTNING, CRESSIDA)
    }
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
    /** What a card does when clicked (posters and wide cards). */
    val clickEffect: dev.nitrate.core.effects.ClickEffect = dev.nitrate.core.effects.ClickEffect.NONE,
    /** What now and then happens to a card while browsing, and how often. */
    val cardSpark: dev.nitrate.core.effects.CardSpark = dev.nitrate.core.effects.CardSpark.NONE,
    val sparkRate: dev.nitrate.core.effects.SparkRate = dev.nitrate.core.effects.SparkRate.SOMETIMES,
    /**
     * Artwork only: false hides the cards, leaving the focused title's picture as the page's
     * background with its caption, stepped through with ◀▶ (what Full Showcase was).
     */
    val cards: Boolean = true,
    /** TV: this row's caption (for the title in focus); null = the page's (`DisplayPrefs.tvCaptionSize`). */
    val captionSize: dev.nitrate.core.pages.CaptionSize? = null,
    /** Reaching the end of the row fetches more of it (rows that can say how: [MediaRow.more]). */
    val loadMore: Boolean = false,
    /** Past the last card ◀▶ go round to the first (and back from the first to the last). */
    val wrapRound: Boolean = false,
    /** Titles under the cards only on the focused one (with [showTitles]); the room is kept so nothing moves. */
    val titlesOnFocus: Boolean = false,
    /** Wide cards: the title's logo over its picture instead of words under it (words when there's no logo). */
    val logoCards: Boolean = false,
) {
    /**
     * The look [by] places on, round the list: each style in [RowStyle.choices], Artwork followed by
     * Artwork with its cards hidden (Fast-forward/Rewind and the Edit page's quick choice).
     */
    fun stepped(by: Int): RowDisplay {
        val looks = RowStyle.choices.flatMap { if (it == RowStyle.WIDE) listOf(it to true, it to false) else listOf(it to true) }
        val now = normalized()
        val i = looks.indexOf(now.style to (now.cards || now.style != RowStyle.WIDE)).coerceAtLeast(0)
        val (style, cards) = looks[(i + by).mod(looks.size)]
        return now.copy(style = style, cards = cards)
    }

    /** What the style is called, the cards-hidden form of Artwork included. */
    val styleLabel: String get() = if (cardsHidden) "Artwork, cards hidden" else style.label

    /** Artwork with its cards hidden: one title at a time over the page's background. */
    val cardsHidden: Boolean get() = style == RowStyle.WIDE && !cards

    /**
     * The retired showcase styles as they show now, the stored value left as it is: Showcase is
     * plain Artwork, Full Showcase is Artwork with the cards hidden.
     */
    fun normalized(): RowDisplay = when (style) {
        RowStyle.SHOWCASE -> copy(style = RowStyle.WIDE)
        RowStyle.FULL_SHOWCASE -> copy(style = RowStyle.WIDE, cards = false)
        else -> this
    }
}

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
    DRAGON("Eastern dragon"),
    WESTERN_DRAGON("Western dragon"),
    CONSTELLATION("Constellation"),
    WAVES("Waves"),
    DECO("Deco"),
    MAGIC("Magic"),
    SONGBIRD("Songbird"),
    FLAMES("Flames"),
    PHOENIX("Phoenix"),
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
    /** Off the last item to the first and the first to the last, except towards a menu rail on that side. */
    val infiniteScroll: Boolean = true,
    /** Cycle through an item's backdrops while it is shown. */
    val rotateArtwork: Boolean = false,
)

/** Defaults that put artwork first: poster rows as titled artwork. */
object ArtworkFirst {
    fun display(row: MediaRow): RowDisplay = when {
        row.defaultDisplay.style != RowStyle.POSTER -> row.defaultDisplay
        else -> row.defaultDisplay.copy(style = RowStyle.WIDE, showTitles = true)
    }
}

/**
 * A row on the home or a library page.
 * [key] is stable across app versions and is what display settings are saved against.
 */
@Serializable
data class MediaRow(
    val key: String,
    val title: String,
    val items: List<MediaItem>,
    val defaultDisplay: RowDisplay,
    /** How to fetch more of this row (`MediaServer.moreOfRow`); null when it can't. */
    val more: RowMore? = null,
)

/**
 * The request that made a row, opaque outside the server client (which alone knows its API), so
 * the row can be continued from where it ends.
 */
@Serializable
data class RowMore(val path: List<String>, val params: Map<String, String>)

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
