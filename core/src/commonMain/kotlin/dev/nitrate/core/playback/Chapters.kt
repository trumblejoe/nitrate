package dev.nitrate.core.playback

import dev.nitrate.core.model.Chapter

/** Finding your way by chapter marks. */
object Chapters {
    /** Index of the chapter playing at [positionMs], or -1 before the first. */
    fun current(chapters: List<Chapter>, positionMs: Long): Int = chapters.indexOfLast { it.startMs <= positionMs }

    /** Where the next chapter starts, or null on the last. */
    fun next(chapters: List<Chapter>, positionMs: Long): Long? = chapters.firstOrNull { it.startMs > positionMs + 500 }?.startMs

    /**
     * Where "previous" goes: the start of this chapter if more than 3 s in (like a CD player),
     * otherwise the one before.
     */
    fun previous(chapters: List<Chapter>, positionMs: Long): Long? {
        val i = current(chapters, positionMs)
        if (i < 0) return null
        return if (positionMs - chapters[i].startMs > 3_000 || i == 0) chapters[i].startMs else chapters[i - 1].startMs
    }

    /** "Chapter 3" for unnamed chapters. */
    fun title(chapters: List<Chapter>, index: Int): String = chapters[index].name.ifBlank { "Chapter ${index + 1}" }
}
