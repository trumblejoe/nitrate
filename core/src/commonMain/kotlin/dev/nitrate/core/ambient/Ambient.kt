package dev.nitrate.core.ambient

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.rows.MediaRow

/**
 * One picture of the TV's ambient screensaver: [item]'s artwork with [label] ("Up next",
 * "New") and [title]; [detail] is "S2 · E5" or the year, never an episode's name or story.
 */
data class AmbientSlide(val item: MediaItem, val label: String, val title: String, val detail: String?)

/**
 * The TV's ambient screensaver: when nothing has been pressed for a while (outside the player),
 * slow artwork from the user's library with the time, what's next and what's new. Any press
 * leaves it; OK opens the title shown.
 */
object Ambient {
    /** Each picture stays this long. */
    const val SLIDE_MS = 20_000L
    const val MAX_SLIDES = 30

    /** Minutes before it starts (0: never). */
    val CHOICES = listOf(0, 2, 5, 10, 15, 30)
    const val DEFAULT_MINUTES = 5

    fun label(minutes: Int): String = if (minutes == 0) "Off" else "After $minutes minutes"

    /**
     * From Home's rows: carrying on first (continue watching, next up), then what's new, then the
     * rest; one picture per show; only titles with artwork ([hasArt]).
     */
    fun slides(rows: List<MediaRow>, hasArt: (MediaItem) -> Boolean): List<AmbientSlide> {
        fun rank(key: String) = when {
            key == "home/continue_watching" -> 0
            key == "home/next_up" -> 1
            key.startsWith("home/latest/") -> 2
            else -> 3
        }
        val ordered = rows.sortedBy { rank(it.key) }.flatMap { row -> row.items.map { row.key to it } }
        return ordered
            .filter { (_, i) -> i.kind in setOf(ItemKind.MOVIE, ItemKind.EPISODE, ItemKind.SERIES, ItemKind.SEASON) && !i.isMissing && hasArt(i) }
            .distinctBy { (_, i) -> i.seriesId ?: i.id }
            .take(MAX_SLIDES)
            .map { (key, i) ->
                val label = when {
                    key == "home/continue_watching" -> "Continue watching"
                    key == "home/next_up" -> "Up next"
                    key.startsWith("home/latest/") -> "New"
                    else -> "From your library"
                }
                val detail = if (i.isEpisode) listOfNotNull(i.seasonNumber?.let { "S$it" }, i.indexNumber?.let { "E$it" }).joinToString(" · ").ifEmpty { null }
                else i.productionYear?.toString()
                AmbientSlide(i, label, i.displayTitle, detail)
            }
    }

    /** Whether it's time: idle at least [minutes] (0 never), and not while something plays. */
    fun due(idleMs: Long, minutes: Int, playing: Boolean): Boolean = minutes > 0 && !playing && idleMs >= minutes * 60_000L
}
