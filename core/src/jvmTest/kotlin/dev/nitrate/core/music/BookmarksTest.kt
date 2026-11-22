package dev.nitrate.core.music

import dev.nitrate.core.model.Chapter
import kotlin.test.Test
import kotlin.test.assertEquals

class BookmarksTest {
    @Test fun marksStayInTheBooksOrderAndOneCloseByIsReplaced() {
        var list = Bookmarks.add(emptyList(), Bookmark("a", 600_000, "good bit"))
        list = Bookmarks.add(list, Bookmark("b", 60_000))
        assertEquals(listOf("b", "a"), list.map { it.id })
        // Two seconds from "a": replaces it, keeping its note.
        list = Bookmarks.add(list, Bookmark("c", 602_000))
        assertEquals(listOf("b", "c"), list.map { it.id })
        assertEquals("good bit", list[1].note)
        assertEquals(listOf("c"), Bookmarks.remove(list, "b").map { it.id })
        assertEquals("ending", Bookmarks.edit(list, "c", " ending ")[1].note)
    }

    @Test fun aPlaceIsNamedByItsChapter() {
        val chapters = listOf(Chapter(0, "Prologue"), Chapter(300_000, ""))
        assertEquals("Prologue · 1:00", Bookmarks.place(Bookmark("a", 60_000), chapters))
        assertEquals("Chapter 2 · 10:00", Bookmarks.place(Bookmark("a", 600_000), chapters))
        assertEquals("10:00", Bookmarks.place(Bookmark("a", 600_000), emptyList()))
    }
}
