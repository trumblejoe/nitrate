package dev.nitrate.core.livetv

import dev.nitrate.core.model.MediaItem

/** A programme in the guide. Times are epoch ms. */
data class TvProgram(
    val id: String,
    val channelId: String,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val episodeTitle: String? = null,
    val overview: String? = null,
    /** Set when it's scheduled to record. */
    val timerId: String? = null,
    val isMovie: Boolean = false,
    val isSeries: Boolean = false,
    val isNews: Boolean = false,
    val isKids: Boolean = false,
    val isSports: Boolean = false,
) {
    fun isAiring(nowMs: Long) = nowMs in startMs until endMs
    fun hasEnded(nowMs: Long) = nowMs >= endMs

    /** How far through it is, 0–1. */
    fun progress(nowMs: Long): Float = ((nowMs - startMs).toFloat() / (endMs - startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
}

/** A channel (playable: [item]) and what's on it now. */
data class LiveChannel(val item: MediaItem, val number: String?, val now: TvProgram?) {
    val id get() = item.id
    val name get() = item.name
}

/** Lays out a time window of the guide: where each programme starts and how wide it is. */
object Guide {
    const val SLOT_MS = 30 * 60_000L

    /** The window start: the half hour [nowMs] falls in. */
    fun windowStart(nowMs: Long): Long = nowMs - nowMs % SLOT_MS

    /** A programme within the window [fromMs, toMs): its visible part as fractions of the window. */
    data class Cell(val program: TvProgram, val startFraction: Float, val widthFraction: Float)

    /**
     * The cells for one channel's [programs] within the window, in time order; gaps (no
     * listings) are left for the caller to show as empty space.
     */
    fun cells(programs: List<TvProgram>, fromMs: Long, toMs: Long): List<Cell> {
        val span = (toMs - fromMs).toFloat()
        return programs.filter { it.endMs > fromMs && it.startMs < toMs }.sortedBy { it.startMs }.map { p ->
            val start = maxOf(p.startMs, fromMs)
            val end = minOf(p.endMs, toMs)
            Cell(p, (start - fromMs) / span, (end - start) / span)
        }
    }

    /** What's on after [now] on the same channel. */
    fun next(programs: List<TvProgram>, now: TvProgram?, nowMs: Long): TvProgram? =
        programs.filter { it.startMs >= (now?.endMs ?: nowMs) }.minByOrNull { it.startMs }
}
