package dev.nitrate.core.music

import dev.nitrate.core.model.Chapter
import dev.nitrate.core.model.formatClock
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** A place marked in an audiobook, with an optional note ("good bit"). */
@Serializable
data class Bookmark(val id: String, val positionMs: Long, val note: String = "", val createdMs: Long = 0)

/** Where an audiobook's bookmarks are kept (the user's synced display settings, the same on every device). */
interface BookmarkStore {
    fun bookmarks(itemId: String): Flow<List<Bookmark>>
    suspend fun save(itemId: String, bookmarks: List<Bookmark>)
}

object Bookmarks {
    /** A bookmark this close to another replaces it rather than adding a second. */
    const val SAME_PLACE_MS = 5_000L

    /** [list] with [mark] added, in the book's order (one close by is replaced, keeping its note if the new one has none). */
    fun add(list: List<Bookmark>, mark: Bookmark): List<Bookmark> {
        val near = list.firstOrNull { kotlin.math.abs(it.positionMs - mark.positionMs) < SAME_PLACE_MS }
        val placed = if (near != null && mark.note.isBlank()) mark.copy(note = near.note) else mark
        return (list.filter { it !== near } + placed).sortedBy { it.positionMs }
    }

    fun remove(list: List<Bookmark>, id: String) = list.filter { it.id != id }

    fun edit(list: List<Bookmark>, id: String, note: String) = list.map { if (it.id == id) it.copy(note = note.trim()) else it }

    /** "Chapter 12 · 1:23:45": where it is, by chapter when the book has them. */
    fun place(mark: Bookmark, chapters: List<Chapter>): String {
        val at = Audiobooks.chapterAt(chapters, mark.positionMs)
        val chapter = chapters.getOrNull(at)?.name?.takeIf { it.isNotBlank() } ?: if (at >= 0) "Chapter ${at + 1}" else null
        return listOfNotNull(chapter, formatClock(mark.positionMs)).joinToString(" · ")
    }
}
