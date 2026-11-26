package dev.nitrate.core.pages

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.formatOneDecimal
import dev.nitrate.core.model.formatRuntime
import kotlinx.serialization.SerialName
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
    /** How progress shows on cards: a bar, a small circle, or nothing; null = from [progressBars] (saved before this). */
    val progressStyle: ProgressStyle? = null,
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
    /** Shared shows whose spoiler hiding goes by the user's own progress, not the furthest-behind member's. */
    val ownSpoilerShows: Set<String> = emptySet(),
    /** Older apps' group chat on or off per watch group id; no longer read (one setting, [chatOn]), kept under its name. */
    val groupChat: Map<String, Boolean> = emptyMap(),
    /** Group chat in the player (the group's conversation popping up, writing), on unless turned off; one setting for every group. */
    val chatOn: Boolean = true,
    val recapPlaylists: Set<String> = emptySet(),
    val accent: AccentMode = AccentMode.ARTWORK,
    /** Pure black backgrounds (OLED). */
    val trueBlack: Boolean = false,
    /**
     * How TV pages lay out their rows ([PageArrangement]). Stored under its old name, so Full art
     * and Rows read as they were; the old separate "stacked" switch is gone, so stacked rows saved
     * that way come back as Rows one at a time.
     */
    @SerialName("tvHome") val tvArrangement: PageArrangement = PageArrangement.FULL_ART,
    /** TV library pages' Recommended tab: like every page (null), or an arrangement of their own. */
    @SerialName("tvLibraryLayout") val tvLibraryArrangement: PageArrangement? = null,
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
    /**
     * Where the TV's main menu sits. Always written: settings saved before the overlay menu became
     * the default have no `tvMenu` (the left rail, then the default, wasn't written), and [decode]
     * keeps those on the rail.
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault
    val tvMenu: TvMenuStyle = TvMenuStyle.OVERLAY,
    /** Keep the menu invisible until you move onto it (any menu but the dial). */
    val tvMenuAutoHide: Boolean = false,
    /** The TV menu (pop-up card, rail, bar or dial) on solid black, so it stands out over page themes and art. */
    val tvMenuBlack: Boolean = false,
    /** The overlay pop-up ([TvMenuStyle.OVERLAY]): which arrow at the page's edge opens it (Menu always does). */
    val tvPopupKey: PopupKey = PopupKey.LEFT,
    /** Where the title's caption sits (rows one at a time, full art, showcases). */
    val tvCaptionCorner: CaptionCorner = CaptionCorner.ALTERNATE,
    val tvCaptionSize: CaptionSize = CaptionSize.NORMAL,
    /** Rows one at a time: where the row in use sits, and whether it runs the page's full length. */
    val tvRowPlace: RowPlace = RowPlace.BOTTOM,
    val tvRowExtent: RowExtent = RowExtent.FULL,
    /** Full art: ▲▼ step along the row as ◀▶ do, going to the row above or below only past its first or last title. */
    val tvUpDownAlong: Boolean = false,
    /** How the focused card shows (per page through [PageLook.focusStyle]). */
    val tvFocusStyle: FocusStyle = FocusStyle.GROW,
    /** Cards' corners and the gap between them (per page through [PageLook]). */
    val tvCardCorners: CardCorners = CardCorners.ROUNDED,
    val tvCardSpacing: CardSpacing = CardSpacing.NORMAL,
    /** A library's full list (All and the other list tabs): the focused title's details held at the top, or posters filling the page (per page through [PageLook.gridLayout]). */
    val tvGridLayout: GridLayout = GridLayout.FIXED,
    /** Phones, tablets, iPhone, iPad and desktop: a library's full list keeps its tabs and Sort/Filters bar pinned, or lets them scroll away. */
    val phoneGridLayout: GridLayout = GridLayout.FIXED,
    /** Rows one at a time and full art: a slim marker saying which row this is ("3 / 9"). */
    val tvRowMarker: Boolean = true,
    /** A small clock in the page's top corner. */
    val tvClock: Boolean = false,
    /** Home: Continue Watching and Next Up as one row (`rows/ContinueAndNext`), on both apps. */
    val combineContinueNextUp: Boolean = false,
    /** TV: a contrast box behind a caption's words instead of the shade along the screen's edge. */
    val tvCaptionBox: Boolean = false,
    /** Pages with a layout of their own (by top-level page key, [PageLooks.ownerOf]); the rest use the fields above. */
    val pageLooks: Map<String, PageLook> = emptyMap(),
    /** The menu's stops in order (rail and bars), as [MenuStops] ids; null until arranged. */
    val tvMenuStops: List<String>? = null,
    /** The dial's spots, `ring * 4 + spoke` (up, right, down, left), null for an empty spot. */
    val tvDialStops: List<String?>? = null,
    /** Menu groups (TV): a stop `group:<id>` opening in place onto its member stops (`MenuGroups`). */
    val tvMenuGroups: List<MenuGroup> = emptyList(),
    /** Stops kept on the menu's "More" page, in order; null = the default list (`MenuStops.defaultMore`). */
    val tvMoreStops: List<String>? = null,
    /** Group suggestions answered "Not now" (`MenuGroups.suggestionKey`), never offered again. */
    val tvGroupSuggestionsDismissed: Set<String> = emptySet(),
    /** Playlists pinned to Home as tabs (TV), id → name, in order. */
    val homePlaylists: Map<String, String> = emptyMap(),
    /** Playlists added to the menu as stops `playlist:<id>` (TV), id → name. */
    val menuPlaylists: Map<String, String> = emptyMap(),
) {
    /** How progress shows on cards (from the old [progressBars] switch until a style is chosen). */
    val progress: ProgressStyle get() = progressStyle ?: if (progressBars) ProgressStyle.BAR else ProgressStyle.NONE

    /** Progress shown as [style]; None also turns the bars off everywhere else (details pages, the player). */
    fun withProgress(style: ProgressStyle): DisplayPrefs = copy(progressStyle = style, progressBars = style != ProgressStyle.NONE)

    /** Whether group chat starts on for a playback (any group's): the user's setting, [chatOn]; never in easy mode. */
    fun groupChatOn(@Suppress("UNUSED_PARAMETER") groupIds: Collection<String> = emptyList()): Boolean = chatOn && !easyMode

    companion object {
        /**
         * Display settings saved as [raw] JSON. Settings saved before the overlay menu became the
         * default have no `tvMenu` and keep the left rail they had; new users (nothing saved) get
         * [TvMenuStyle.OVERLAY].
         */
        fun decode(json: kotlinx.serialization.json.Json, raw: String): DisplayPrefs {
            val obj = json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonObject
                ?: return json.decodeFromString(serializer(), raw)
            val fixed = if ("tvMenu" in obj) obj
            else kotlinx.serialization.json.JsonObject(obj + ("tvMenu" to kotlinx.serialization.json.JsonPrimitive(TvMenuStyle.LEFT.name)))
            return json.decodeFromJsonElement(serializer(), fixed)
        }
    }
}

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

/** Card corners on TV: rounded (the TV's own) or square, for poster walls with clean edges. */
@Serializable
enum class CardCorners(val label: String) { ROUNDED("Rounded"), SQUARE("Square") }

/**
 * How a library's full list is laid out. TV: [FIXED] holds the focused title's picture and details at
 * the top as Home does, the posters scrolling beneath; [SCROLLING] lets the posters fill the page and
 * everything scroll together. Phone and the rest: [FIXED] pins the tabs and the Sort/Filters bar,
 * [SCROLLING] lets them scroll away with the posters.
 */
@Serializable
enum class GridLayout(val label: String, val tvDetail: String, val phoneDetail: String) {
    FIXED("Fixed grid", "The focused title's details stay at the top, the posters scroll beneath", "The tabs and the Sort and Filters bar stay at the top"),
    SCROLLING("Scrolling grid", "The posters fill the page and scroll with it", "The tabs and the bar scroll away with the posters"),
}

/** The gap between cards in a row on TV, in dp. */
@Serializable
enum class CardSpacing(val label: String, val dp: Int) { TIGHT("Tight", 8), NORMAL("Normal", 18), ROOMY("Roomy", 30) }

/** How far into something you are, on cards. */
@Serializable
enum class ProgressStyle(val label: String) { BAR("Bar"), CIRCLE("Circle"), NONE("None") }

/** How the focused card shows on TV. Grow is the TV's own way; the others keep rows still. */
@Serializable
enum class FocusStyle(val label: String, val note: String) {
    GROW("Grow", "The focused card grows a little"),
    OUTLINE("Outline", "A white frame, nothing moves"),
    GLOW("Glow", "A soft glow in the accent colour, nothing moves"),
    LIFT("Lift", "Rises slightly with a shadow under it"),
}

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

/** How a TV page lays out its rows. */
@Serializable
enum class PageArrangement(val label: String, val note: String) {
    /** The focused title fills the screen; Left/Right step through a row, Up/Down change rows. */
    FULL_ART("Full art", "One title at a time across the screen: ◀ ▶ through a row, ▲ ▼ between rows"),

    /** The row in use as a strip of cards, the focused title's picture around it, the caption in a corner. */
    ROWS("Rows one at a time", "The row in use with the picture around it; the others show as you reach them"),

    /** Every row down the page, the focused title's summary at the top. */
    STACKED("Stacked rows", "Every row down the page, the focused title's summary above them"),
    ;

    val fullArt get() = this == FULL_ART
    val oneAtATime get() = this == ROWS
    val stacked get() = this == STACKED
    fun next() = entries[(ordinal + 1) % entries.size]
}

@Serializable
enum class TvMenuStyle(val label: String, val detail: String) {
    /** Nothing on screen until called: a pop-up card of big tiles in the middle of the dimmed page (the default). */
    OVERLAY("Overlay", "Nothing on screen while you browse; Menu, or the arrow you choose at the page's edge, opens a pop-up of big tiles in the middle"),
    /** The side rail: hidden until called, then a column of icons and words at the edge, over the page. */
    LEFT("Side rail", "Hidden until you move off the page's left edge or press Menu; then icons and words down the left"),
    RIGHT("Side rail", "Hidden until you move off the page's right edge or press Menu; then icons and words down the right"),
    TOP("Top bar", "A bar at the top while you're on the first row, at the bottom on the last row"),
    /** Kept so older saved settings still read; shown as Top/Bottom with "Fade in" on. */
    FADE("Fade in", "Top/Bottom, hidden until you reach it"),
    RADIAL("Dial", "Rings of destinations straight up, right, down and left; move up past the top or press Menu"),
    ;

    /** The side rail, on either side. */
    val rail: Boolean get() = this == LEFT || this == RIGHT

    /**
     * Search, Settings and Switch user leave the menu's stops: on the rail they live in the profile
     * corner (top right of every page), on the overlay in the pop-up's top row (Search and the
     * avatar, whose panel has Switch user and Settings). The bar and the dial keep them.
     */
    val cornerStops: Boolean get() = this == OVERLAY || rail
}

/**
 * Which arrow at the page's edge opened the overlay pop-up (`DisplayPrefs.tvPopupKey`); stored by name.
 * No longer read: the overlay opens as the dial does (Menu, or Up past the page's top). Kept so saved
 * settings still read.
 */
@Serializable
enum class PopupKey(val label: String) { LEFT("Left"), RIGHT("Right"), UP("Up") }

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
