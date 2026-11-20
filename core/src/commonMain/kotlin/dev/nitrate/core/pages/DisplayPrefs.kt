package dev.nitrate.core.pages

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.formatOneDecimal
import dev.nitrate.core.model.formatRuntime
import kotlinx.serialization.Serializable

/**
 * What the user wants shown about items, everywhere in the app. Per user, synced with their
 * other devices. Defaults show everything.
 */
@Serializable
data class DisplayPrefs(
    val communityRating: Boolean = true,
    val criticRating: Boolean = true,
    /** Age rating, e.g. PG-13. */
    val officialRating: Boolean = true,
    val year: Boolean = true,
    val runtime: Boolean = true,
    val genres: Boolean = true,
    val overview: Boolean = true,
    val tagline: Boolean = true,
    /** 4K, HDR, Dolby Vision, Atmos... */
    val qualityBadges: Boolean = true,
    val cardTitles: CardTitles = CardTitles.BY_ROW,
    val progressBars: Boolean = true,
    val watchedMarks: Boolean = true,
    val unwatchedCounts: Boolean = true,
    val logos: Boolean = true,
    val cast: Boolean = true,
    val similar: Boolean = true,
    /** Blurs unwatched episode stills and hides their descriptions. */
    val hideSpoilers: Boolean = false,
    /** Theme music on details pages; null: the device's default (on for TV, off for phones). */
    val themeMusic: Boolean? = null,
    /**
     * Start with the audio-described track (a narrator describing what's on screen) when a title
     * has one in its language; off: avoid it when it would otherwise start. A pick for a show wins.
     */
    val describedAudio: Boolean = false,
    /**
     * Skip the credits to a scene during or after them, when Nitrate knows where it is (the
     * server's credit markers, chapters, or thumbnails confirmed by the title's tags); otherwise
     * it's offered. Off: only offered.
     */
    val creditScenes: Boolean = true,
    /** TV: minutes with nothing pressed (outside the player) before the ambient screensaver (0: never). */
    val tvAmbientMinutes: Int = dev.nitrate.core.ambient.Ambient.DEFAULT_MINUTES,
    /**
     * Easy mode: bigger text and touch targets, only the essential menu stops and player
     * controls, and a confirmation before anything is deleted or signed out.
     */
    val easyMode: Boolean = false,
    /** "Last time" recap before resuming after this many days away; 0: never. */
    val recapAfterDays: Int = 14,
    /** The same for playlists marked in story order, set separately; 0: never. */
    val playlistRecapAfterDays: Int = 14,
    /** Playlists marked "in story order", which get a recap too (shared on every device, like spoiler hiding). */
    val recapPlaylists: Set<String> = emptySet(),
    val accent: AccentMode = AccentMode.ARTWORK,
    /** Pure black backgrounds (OLED). */
    val trueBlack: Boolean = false,
    /** TV Home: one row at a time across the whole screen, or the classic stack of rows. */
    val tvHome: TvHomeLayout = TvHomeLayout.FULL_ART,
    /** TV library pages' Recommended tab: like Home (null), or a layout of their own. */
    val tvLibraryLayout: TvHomeLayout? = null,
    /** How page tabs look on TV (Home's pinned pages, a library's tabs). */
    val tvTabStyle: TabStyle = TabStyle.CHIPS,
    /** Row, page and seasonal effects (TV). */
    val effects: dev.nitrate.core.effects.EffectsPrefs = dev.nitrate.core.effects.EffectsPrefs(),
    /** Each page's animated theme, by page key (Home, a library, a custom page). */
    val pageThemes: Map<String, dev.nitrate.core.effects.PageTheme> = emptyMap(),
    /** What each page draws behind it (by page key; missing = the focused title's picture). */
    val pageBackdrops: Map<String, dev.nitrate.core.effects.PageBackdrop> = emptyMap(),
    /** What plays before titles: verses, scenes, the server's intros (off until switched on). */
    val prerolls: dev.nitrate.core.preroll.PrerollPrefs = dev.nitrate.core.preroll.PrerollPrefs(),
    /** Where the TV library's A–Z bar sits in the All view. */
    val tvLetterBar: LetterBarPlace = LetterBarPlace.AUTO,
    /** Where the TV's main menu sits. */
    val tvMenu: TvMenuStyle = TvMenuStyle.LEFT,
    /** Keep the menu invisible until you move onto it (any menu but the dial). */
    val tvMenuAutoHide: Boolean = false,
    /** The TV menu (rail, bar or dial) on solid black, so it stands out over page themes and art. */
    val tvMenuBlack: Boolean = false,
    /** TV rows layout: every row stacked down the page, rather than one at a time along the bottom. */
    val tvRowsStacked: Boolean = false,
    /** Where the title's caption sits (rows one at a time, full art, showcases). */
    val tvCaptionCorner: CaptionCorner = CaptionCorner.ALTERNATE,
    val tvCaptionSize: CaptionSize = CaptionSize.NORMAL,
    /** Rows one at a time: where the row in use sits, and whether it runs the page's full length. */
    val tvRowPlace: RowPlace = RowPlace.BOTTOM,
    val tvRowExtent: RowExtent = RowExtent.FULL,
    /** Pages with a layout of their own (by top-level page key, [PageLooks.ownerOf]); the rest use the fields above. */
    val pageLooks: Map<String, PageLook> = emptyMap(),
    /** The menu's stops in order (rail and bars), as [MenuStops] ids; null until arranged. */
    val tvMenuStops: List<String>? = null,
    /** The dial's spots, `ring * 4 + spoke` (up, right, down, left), null for an empty spot. */
    val tvDialStops: List<String?>? = null,
)

/** Where the A–Z bar goes on TV library pages. */
@Serializable
enum class LetterBarPlace(val label: String) {
    /** The side away from the menu rail (the right, unless the menu is on the right). */
    AUTO("Automatic"),
    LEFT("Left side"),
    RIGHT("Right side"),
    TOP("Above the titles"),
    OFF("Off"),
    ;

    /** Where it actually goes with the menu at [menu]: AUTO resolved, OFF as null. */
    fun resolve(menu: TvMenuStyle): LetterBarPlace? = when (this) {
        AUTO -> if (menu == TvMenuStyle.RIGHT) LEFT else RIGHT
        OFF -> null
        else -> this
    }
}

/** The corner of a title's caption on TV; [ALTERNATE] moves it side to side, row by row (or title by title). */
@Serializable
enum class CaptionCorner(val label: String) {
    ALTERNATE("Alternate sides"), TOP_LEFT("Top left"), TOP_RIGHT("Top right"), BOTTOM_LEFT("Bottom left"), BOTTOM_RIGHT("Bottom right");

    /** Where it lands for a row (or title) at [place] in the page, with the row at [rowPlace]: (top, right side). */
    fun resolve(place: Int, rowPlace: RowPlace = RowPlace.BOTTOM): Pair<Boolean, Boolean> = when (this) {
        ALTERNATE -> (rowPlace == RowPlace.TOP) to (place % 2 == 1)
        TOP_LEFT -> true to false
        TOP_RIGHT -> true to true
        BOTTOM_LEFT -> false to false
        BOTTOM_RIGHT -> false to true
    }
}

/** A title's caption on TV: [COMPACT] is the label, a smaller title and its facts, no description. */
@Serializable
enum class CaptionSize(val label: String, val detail: String) {
    NORMAL("Normal", "The title, its facts and a two-line description"),
    COMPACT("Compact", "A smaller title and its facts, no description: more of the picture"),
    /** The title's logo in place of its name (when it has one), its facts, no description. */
    MINIMAL("Minimal", "The title's logo, its facts and no description: the least over the picture"),
}

/** Where the row in use sits, one row at a time: along the top or bottom, or as a column down the left or right. */
@Serializable
enum class RowPlace(val label: String) {
    BOTTOM("Along the bottom"), TOP("Along the top"), LEFT("Down the left"), RIGHT("Down the right");
    val horizontal get() = this == BOTTOM || this == TOP
}

/** How far the row in use runs: the page's full width (or height), or part of it. */
@Serializable
enum class RowExtent(val label: String) { FULL("Full length"), PARTIAL("Part way") }

/**
 * How page tabs are drawn on TV. Each keeps "open" (the page showing) and "focused" (where the
 * remote is) clearly different. Unknown values (a newer app's) read as the default.
 */
@Serializable
enum class TabStyle(val label: String, val detail: String) {
    CHIPS("Chips", "Rounded buttons"),
    UNDERLINE("Underline", "Plain words, a short line under the open one"),
    PILL("Sliding pill", "One capsule, a pill moving between them"),
    LARGE("Large words", "Big type, the others dimmed"),
    GLASS("Frosted glass", "A pane of frosted glass catching the light"),
    NEON("Neon sign", "Glowing tubes, flickering on as you reach one"),
    MARQUEE("Cinema marquee", "Chasing bulbs around the one you're on"),
    FILM("Film strip", "Frames on a strip of film, the open one lit"),
    BRUSH("Ink brush", "A sweep of ink behind the open one"),
    TICKET("Ticket stubs", "Admit-one tickets, perforated"),
}

@Serializable
enum class TvHomeLayout(val label: String) {
    /** The focused title fills the screen; Left/Right step through a row, Up/Down change rows. */
    FULL_ART("Full-screen artwork"),
    ROWS("Rows"),
}

@Serializable
enum class TvMenuStyle(val label: String, val detail: String) {
    LEFT("Left", "Icons down the left; opens with labels when you move onto it"),
    RIGHT("Right", "The same rail on the right-hand side"),
    TOP("Top/Bottom", "A bar at the top while you're on the first row, at the bottom on the last row"),
    /** Kept so older saved settings still read; shown as Top/Bottom with "Fade in" on. */
    FADE("Fade in", "Top/Bottom, hidden until you reach it"),
    RADIAL("Dial", "Rings of destinations straight up, right, down and left; move up past the top or press Menu"),
}

@Serializable
enum class CardTitles(val label: String) { BY_ROW("As each row is set"), ALWAYS("Always"), NEVER("Never") }

/** Where the accent colour comes from. */
@Serializable
enum class AccentMode(val label: String, val argb: Long?) {
    ARTWORK("From artwork", null),
    AMBER("Amber", 0xFFE8A33D),
    CRIMSON("Crimson", 0xFFE5484D),
    TEAL("Teal", 0xFF2EC4B6),
    VIOLET("Violet", 0xFF9B7BFF),
    SKY("Sky", 0xFF5AB0FF),
}

/** Year, age rating, runtime, ratings and quality badges for [item], each only if shown. */
fun DisplayPrefs.facts(item: MediaItem): List<String> = buildList {
    if (year) {
        val start = item.productionYear
        if (item.kind == ItemKind.SERIES) {
            start?.let { add(if (item.endYear != null && item.endYear != it) "$it–${item.endYear}" else "$it") }
        } else {
            start?.let { add(it.toString()) }
        }
    }
    if (officialRating) item.officialRating?.let { add(it) }
    if (runtime) item.runtimeMs?.takeIf { item.kind != ItemKind.SERIES }?.let { add(formatRuntime(it)) }
    if (communityRating) item.communityRating?.let { add("★ ${formatOneDecimal(it.toDouble())}") }
    if (criticRating) item.criticRating?.let { add("${it.toInt()}% critics") }
    if (qualityBadges) addAll(item.badges)
}

/** Whether an episode's still would give away something not yet watched (blurred). */
fun DisplayPrefs.isSpoiler(item: MediaItem): Boolean = hideSpoilers && item.kind == ItemKind.EPISODE && !item.user.played

/** Whether a description would give away something not yet watched: unwatched episodes and movies. */
fun DisplayPrefs.hidesDescription(item: MediaItem): Boolean =
    hideSpoilers && !item.user.played && (item.kind == ItemKind.EPISODE || item.kind == ItemKind.MOVIE)

/** A label shown on artwork or a details page for picture and sound quality. */
object QualityBadges {
    /**
     * Badges from a video's size and streams: "4K" or "HD", the HDR format, and notable audio.
     * [videoRange] is Jellyfin's range type (HDR10, DOVI...), [audioCodecs] with profiles/titles.
     */
    fun of(width: Int?, height: Int?, videoRange: String?, audio: List<AudioInfo>): List<String> = buildList {
        val w = width ?: 0
        val h = height ?: 0
        when {
            w >= 3200 || h >= 2000 -> add("4K")
            w >= 1200 || h >= 700 -> add("HD")
        }
        val range = videoRange.orEmpty()
        when {
            range.startsWith("DOVI") -> add("Dolby Vision")
            range == "HDR10Plus" -> add("HDR10+")
            range == "HDR10" || range == "HDR" -> add("HDR")
            range == "HLG" -> add("HLG")
        }
        val text = audio.joinToString(" ") { "${it.codec} ${it.profile.orEmpty()} ${it.title.orEmpty()}" }.lowercase()
        when {
            "atmos" in text -> add("Atmos")
            "dts:x" in text || "dts-x" in text -> add("DTS:X")
            "truehd" in text -> add("TrueHD")
        }
        audio.maxOfOrNull { it.channels ?: 0 }?.let { ch ->
            when {
                ch >= 8 -> add("7.1")
                ch >= 6 -> add("5.1")
            }
        }
    }

    data class AudioInfo(val codec: String?, val profile: String?, val title: String?, val channels: Int?)
}
