package dev.nitrate.tv.ui.rows

import dev.nitrate.core.rows.MediaRow

/**
 * Where focus goes when the card that had it is gone after the page's rows change (an episode
 * finished drops out of Continue Watching, Next Up moves on to the next one, a row empties and goes):
 * focus is never left nowhere. The card now in the same place in that row; else that row's last; if
 * the row is gone, the row now in its place (or the one above), at its first card; else nothing
 * (no rows at all).
 */
object FocusKeep {
    /** The row index and item id to focus, or null when the card is still there (or there's nowhere to go). */
    fun replacement(before: List<MediaRow>, after: List<MediaRow>, rowIndex: Int, itemId: String): Pair<Int, String>? {
        val row = before.getOrNull(rowIndex) ?: return null
        val at = row.items.indexOfFirst { it.id == itemId }.takeIf { it >= 0 } ?: return null
        val nowIndex = after.indexOfFirst { it.key == row.key }
        val now = after.getOrNull(nowIndex)
        if (now != null && now.items.any { it.id == itemId }) return null
        if (now != null && now.items.isNotEmpty()) return nowIndex to now.items[at.coerceAtMost(now.items.lastIndex)].id
        // The row went: the one now in its place, else the one above it.
        val shown = after.withIndex().filter { it.value.items.isNotEmpty() }
        val next = shown.firstOrNull { it.index >= rowIndex } ?: shown.lastOrNull() ?: return null
        return next.index to next.value.items.first().id
    }
}
