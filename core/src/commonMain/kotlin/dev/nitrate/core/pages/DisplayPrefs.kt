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
    val accent: AccentMode = AccentMode.ARTWORK,
    /** Pure black backgrounds (OLED). */
    val trueBlack: Boolean = false,
    /** TV Home: one row at a time across the whole screen, or the classic stack of rows. */
    val tvHome: TvHomeLayout = TvHomeLayout.FULL_ART,
    /** Where the TV's main menu sits. */
    val tvMenu: TvMenuStyle = TvMenuStyle.LEFT,
    /** The menu's stops in order (rail and bars), as [MenuStops] ids; null until arranged. */
    val tvMenuStops: List<String>? = null,
    /** The dial's spots, `ring * 4 + spoke` (up, right, down, left), null for an empty spot. */
    val tvDialStops: List<String?>? = null,
)

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
    FADE("Fade in", "Hidden until you move up past the top, or press the remote's Menu key"),
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

/** Whether an episode's still and description would give away something not yet watched. */
fun DisplayPrefs.isSpoiler(item: MediaItem): Boolean = hideSpoilers && item.kind == ItemKind.EPISODE && !item.user.played

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
