package dev.nitrate.core.widgets

import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.rows.MediaRow

/** Which home-screen widget: carrying on with what's half watched, or the next episode of shows. */
enum class WidgetKind(val title: String, val rowKey: String, val empty: String) {
    CONTINUE("Continue watching", "home/continue_watching", "Nothing half watched"),
    NEXT_UP("Next up", "home/next_up", "No next episodes"),
}

/**
 * One title on a widget: its name, a quiet line (an episode as "S2 · E5", never its name, so a
 * widget on the home screen spoils nothing; a film its year) and how far through it is.
 */
data class WidgetCard(
    val itemId: String,
    val title: String,
    val line: String?,
    /** 0–1 through, when started. */
    val progress: Float?,
    /** Said by a screen reader. */
    val spoken: String,
)

object WidgetCards {
    /** The most a widget lists (a large widget shows them all, a small one the first). */
    const val MAX = 6

    fun of(kind: WidgetKind, rows: List<MediaRow>): List<WidgetCard> = items(kind, rows).map(::card)

    /** The titles [kind]'s widget lists, from Home's rows. */
    fun items(kind: WidgetKind, rows: List<MediaRow>): List<MediaItem> =
        rows.firstOrNull { it.key == kind.rowKey }?.items.orEmpty()
            .filter { it.isPlayable }
            // One per show: the next episode of a show is the one to carry on with.
            .distinctBy { it.seriesId ?: it.id }
            .take(MAX)

    fun card(item: MediaItem): WidgetCard {
        val line = when {
            item.isEpisode -> listOfNotNull(item.seasonNumber?.let { "S$it" }, item.indexNumber?.let { "E$it" }).joinToString(" · ").ifEmpty { null }
            else -> item.productionYear?.toString()
        }
        val runtime = item.runtimeMs ?: 0
        val progress = if (runtime > 0 && item.user.positionMs > 0) (item.user.positionMs.toFloat() / runtime).coerceIn(0f, 1f) else null
        return WidgetCard(item.id, item.displayTitle, line, progress, item.spokenTitle)
    }
}
