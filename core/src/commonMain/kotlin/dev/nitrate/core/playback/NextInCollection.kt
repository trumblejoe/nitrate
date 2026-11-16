package dev.nitrate.core.playback

import dev.nitrate.core.model.Chapter
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.MediaSegment
import dev.nitrate.core.model.SegmentKind

/**
 * "Next in collection": after a film that's part of a collection (a saga such as a trilogy),
 * the one that follows it, offered like Up Next for episodes.
 */
object NextInCollection {
    /** Collections bigger than this are lists ("Christmas films"), not a story to follow. */
    const val MAX_SIZE = 40

    /** Without a credits marker, a film's credits are taken to start this long before the end. */
    const val CREDITS_GUESS_MS = 3 * 60_000L

    private val CREDIT_NAMES = listOf("credit", "end titles", "closing")

    /**
     * The film after [current] in the most specific collection it's in ([collections]: each with
     * its films): only collections of films, at most [MAX_SIZE], in release order. Null when it's
     * the last one, or in none.
     */
    fun next(current: MediaItem, collections: List<List<MediaItem>>): MediaItem? {
        val saga = collections
            .filter { films -> films.size in 2..MAX_SIZE && films.all { it.kind == ItemKind.MOVIE } && films.any { it.id == current.id } }
            .minByOrNull { it.size }
            ?: return null
        val ordered = saga.sortedWith(compareBy<MediaItem>({ it.premiereDateMs ?: Long.MAX_VALUE }, { it.productionYear ?: Int.MAX_VALUE }, { it.sortName ?: it.name }))
        val at = ordered.indexOfFirst { it.id == current.id }
        return ordered.getOrNull(at + 1)?.takeIf { !it.isMissing }
    }

    /**
     * When to offer the next film: at the credits (the server's marker, else a chapter named like
     * credits in the second half), else [CREDITS_GUESS_MS] before the end. Never a countdown: a
     * film can have scenes during and after its credits.
     */
    fun offerAtMs(durationMs: Long, segments: List<MediaSegment>, chapters: List<Chapter>): Long? {
        if (durationMs <= 0) return null
        return segments.filter { it.kind == SegmentKind.OUTRO && it.startMs > durationMs / 2 }.minOfOrNull { it.startMs }
            ?: chapters.lastOrNull { c -> CREDIT_NAMES.any { c.name.contains(it, ignoreCase = true) } }?.startMs?.takeIf { it > durationMs / 2 }
            ?: (durationMs - CREDITS_GUESS_MS).coerceAtLeast(durationMs * 9 / 10)
    }
}
