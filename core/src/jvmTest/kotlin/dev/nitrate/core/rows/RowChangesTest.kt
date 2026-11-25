package dev.nitrate.core.rows

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

class RowChangesTest {
    private fun row(key: String, title: String = key) = MediaRow(key, title, listOf(MediaItem("$key-1", title, ItemKind.MOVIE)), RowDisplay())

    @Test fun tokensSayWhatChanged() {
        val a = RowChanges.token("e1", 1, 1, 1, 1, 0)
        assertEquals(emptySet<ChangeArea>(), RowChanges.compare(a, a))
        assertEquals(setOf(ChangeArea.WATCH), RowChanges.compare(a, RowChanges.token("e1", 1, 1, 1, 2, 0)))
        assertEquals(setOf(ChangeArea.LIBRARY, ChangeArea.BECAUSE), RowChanges.compare(a, RowChanges.token("e1", 5, 1, 1, 1, 9)))
        // Another run of the server's plugin, or nothing kept: everything.
        assertEquals(ChangeArea.entries.toSet(), RowChanges.compare(a, RowChanges.token("e2", 1, 1, 1, 1, 0)))
        assertEquals(ChangeArea.entries.toSet(), RowChanges.compare(null, a))
        assertEquals(ChangeArea.entries.toSet(), RowChanges.compare("garbage", a))
    }

    @Test fun onlyRowsNothingChangedForAreKept() {
        val rows = listOf(row("home/continue_watching"), row("home/latest/m"), row("home/continue_playlists"), row("home/up_next_together"), row("library/m/recently_added"))
        // Watched something: only "Latest in…" stays (its marks are read afresh).
        assertEquals(listOf("home/latest/m"), RowChanges.keepable(rows, setOf(ChangeArea.WATCH)).map { it.key })
        // A playlist changed: everything but that row and the group's (always loaded).
        assertEquals(listOf("home/continue_watching", "home/latest/m", "library/m/recently_added"), RowChanges.keepable(rows, setOf(ChangeArea.PLAYLISTS)).map { it.key })
        // A new film: nothing about the library is kept.
        assertEquals(emptyList<String>(), RowChanges.keepable(rows, setOf(ChangeArea.LIBRARY)).map { it.key })
    }

    @Test fun keptAndFreshRowsKeepThePagesOrder() {
        val before = listOf(row("a"), row("b"), row("c"))
        val merged = RowChanges.merge(before, kept = listOf(row("b")), fresh = listOf(row("c", "C new"), row("d")))
        // a came back empty (gone), b kept, c fresh, d new at the end.
        assertEquals(listOf("b", "C new", "d"), merged.map { it.title })
    }
}
