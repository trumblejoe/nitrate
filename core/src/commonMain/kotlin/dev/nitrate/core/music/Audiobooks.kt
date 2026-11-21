package dev.nitrate.core.music

import dev.nitrate.core.model.Chapter
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem

/** Stopping by itself: after a while, or at the end of the chapter (audiobooks). */
sealed interface Sleep {
    val label: String

    data class After(val minutes: Int) : Sleep { override val label get() = "$minutes min" }
    data object EndOfChapter : Sleep { override val label get() = "End of chapter" }

    companion object {
        val CHOICES: List<Sleep> = listOf(After(5), After(15), After(30), After(45), After(60), EndOfChapter)
    }
}

/** Listening to a book: speeds, skips, chapters, and how much is left. */
object Audiobooks {
    val SPEEDS = listOf(0.8f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f)

    /** Back a little (missed a line) and on a bit further (a skip). */
    const val BACK_MS = 15_000L
    const val FORWARD_MS = 30_000L

    fun isBook(item: MediaItem?) = item?.kind == ItemKind.AUDIOBOOK

    fun speedLabel(speed: Float): String = if (speed == speed.toInt().toFloat()) "${speed.toInt()}×" else "${speed}×"

    /** The chapter playing at [positionMs] (the last one started), or -1 before any. */
    fun chapterAt(chapters: List<Chapter>, positionMs: Long): Int = chapters.indexOfLast { it.startMs <= positionMs }

    /** Where Next goes: the next chapter's start, or null at the last. */
    fun nextChapterStart(chapters: List<Chapter>, positionMs: Long): Long? =
        chapters.getOrNull(chapterAt(chapters, positionMs) + 1)?.startMs

    /**
     * Where Previous goes: back to the start of this chapter, or to the one before when only just
     * into it ([ListenRules.RESTART_AFTER_MS]).
     */
    fun previousChapterStart(chapters: List<Chapter>, positionMs: Long): Long {
        val i = chapterAt(chapters, positionMs)
        if (i < 0) return 0
        val start = chapters[i].startMs
        return if (positionMs - start > ListenRules.RESTART_AFTER_MS || i == 0) start else chapters[i - 1].startMs
    }

    /** Where this chapter ends: the next one's start, else the book's end. */
    fun chapterEnd(chapters: List<Chapter>, positionMs: Long, durationMs: Long): Long =
        nextChapterStart(chapters, positionMs) ?: durationMs

    /** "2 h 13 min left", at [speed] (what's left takes less at a faster speed). */
    fun timeLeft(positionMs: Long, durationMs: Long, speed: Float = 1f): String {
        val left = ((durationMs - positionMs).coerceAtLeast(0) / speed.coerceAtLeast(0.1f)).toLong() / 60_000
        val h = left / 60
        val m = left % 60
        return when {
            h > 0 && m > 0 -> "$h h $m min left"
            h > 0 -> "$h h left"
            else -> "$m min left"
        }
    }
}
