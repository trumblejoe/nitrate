package dev.nitrate.core.lines

import dev.nitrate.core.model.ItemKind

/**
 * A line said in a film or episode, found by "search what people say" (Companion plugin,
 * feature [SpokenLines.FEATURE]): where ([startMs]) and the lines either side.
 */
data class SpokenLine(
    val itemId: String,
    val kind: ItemKind,
    val name: String,
    val seriesId: String?,
    val seriesName: String?,
    val season: Int?,
    val episode: Int?,
    val year: Int?,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val language: String?,
    val before: String?,
    val after: String?,
) {
    val title: String get() = if (kind == ItemKind.EPISODE) seriesName ?: name else name

    /** "S2:E5" for an episode (never its name, which can give things away), else the year. */
    val where: String? get() = if (kind == ItemKind.EPISODE) "S${season ?: "?"}:E${episode ?: "?"}" else year?.toString()
}

object SpokenLines {
    const val FEATURE = "lines"

    /** Searching needs a few letters. */
    const val MIN_QUERY = 3

    /** Playback starts this much before the line, so it's heard in context. */
    const val LEAD_IN_MS = 3_000L

    fun startAt(line: SpokenLine): Long = (line.startMs - LEAD_IN_MS).coerceAtLeast(0)

    /** Three words or more reads like a quote: lines come before titles in the results. */
    fun looksLikeQuote(query: String): Boolean = query.trim().split(Regex("\\s+")).count { it.isNotBlank() } >= 3
}
