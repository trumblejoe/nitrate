package dev.nitrate.core.admin

import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.MediaItem

/**
 * One change made to many titles at once (admins): genres, tags and studios added or removed,
 * the age rating set or cleared, fields locked or unlocked, the whole item locked or unlocked.
 * Each title's own metadata is read, changed and saved back (never rebuilt), so nothing else
 * about it is touched.
 */
data class BulkEdit(
    val addGenres: Set<String> = emptySet(),
    val removeGenres: Set<String> = emptySet(),
    val addTags: Set<String> = emptySet(),
    val removeTags: Set<String> = emptySet(),
    val addStudios: Set<String> = emptySet(),
    val removeStudios: Set<String> = emptySet(),
    /** null: unchanged; "": cleared; else set to it. */
    val officialRating: String? = null,
    val lock: Set<LockableField> = emptySet(),
    val unlock: Set<LockableField> = emptySet(),
    /** null: unchanged. */
    val lockItem: Boolean? = null,
) {
    val isEmpty get() = this == BulkEdit()

    fun apply(m: ItemMetadata): ItemMetadata {
        fun edit(list: List<String>, add: Set<String>, remove: Set<String>): List<String> {
            val removed = list.filterNot { e -> remove.any { it.equals(e, ignoreCase = true) } }
            return removed + add.filter { a -> removed.none { it.equals(a, ignoreCase = true) } }
        }
        val locked = m.lockedFields.mapNotNull(LockableField::of).toSet()
        return m.copy(
            genres = edit(m.genres, addGenres, removeGenres),
            tags = edit(m.tags, addTags, removeTags),
            studios = edit(m.studios, addStudios, removeStudios),
            officialRating = when (officialRating) { null -> m.officialRating; "" -> null; else -> officialRating },
            lockedFields = ((locked - unlock) + lock).sortedBy { it.ordinal }.map { it.wire },
            lockData = lockItem ?: m.lockData,
        )
    }

    /** What it will do, in words, for the review before applying. */
    fun describe(): List<String> = buildList {
        fun words(s: Set<String>) = s.joinToString { "“$it”" }
        if (addGenres.isNotEmpty()) add("Add genre ${words(addGenres)}")
        if (removeGenres.isNotEmpty()) add("Remove genre ${words(removeGenres)}")
        if (addTags.isNotEmpty()) add("Add tag ${words(addTags)}")
        if (removeTags.isNotEmpty()) add("Remove tag ${words(removeTags)}")
        if (addStudios.isNotEmpty()) add("Add studio ${words(addStudios)}")
        if (removeStudios.isNotEmpty()) add("Remove studio ${words(removeStudios)}")
        officialRating?.let { add(if (it.isEmpty()) "Clear the age rating" else "Set the age rating to $it") }
        if (lock.isNotEmpty()) add("Lock " + lock.joinToString { it.label.lowercase() })
        if (unlock.isNotEmpty()) add("Unlock " + unlock.joinToString { it.label.lowercase() })
        lockItem?.let { add(if (it) "Lock the whole item against refreshes" else "Unlock the whole item") }
    }

    data class Failure(val item: MediaItem, val reason: String)
    data class Result(val changed: Int, val unchanged: Int, val failed: List<Failure>)

    /** Applies it to [items] one by one, telling [onProgress] (done, total) as it goes. */
    suspend fun run(server: MediaServer, items: List<MediaItem>, onProgress: (Int, Int) -> Unit = { _, _ -> }): Result {
        var changed = 0
        var unchanged = 0
        val failed = mutableListOf<Failure>()
        items.forEachIndexed { i, item ->
            try {
                val now = server.metadata(item.id)
                val next = apply(now)
                if (next == now) unchanged++ else { server.saveMetadata(next); changed++ }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += Failure(item, e.message ?: e::class.simpleName ?: "failed")
            }
            onProgress(i + 1, items.size)
        }
        return Result(changed, unchanged, failed)
    }
}
