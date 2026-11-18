package dev.nitrate.core.pages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryTabsTest {
    @Test fun `never arranged, Recommended and All shown and the rest there to switch on`() {
        val tabs = LibraryTabs.resolve(emptyList(), hasRecommended = true)
        assertEquals(listOf("Recommended", "All"), LibraryTabs.shown(tabs).map { it.label })
        assertTrue(tabs.any { it.kind == LibraryTabKind.GENRES && it.hidden })
        // Music has no Recommended.
        assertTrue(LibraryTabs.resolve(emptyList(), hasRecommended = false).none { it.kind == LibraryTabKind.RECOMMENDED })
    }

    @Test fun `saved order kept, own tabs kept, new kinds added after`() {
        val own = LibraryTab("own1", LibraryTabKind.OWN, "Kids")
        val saved = listOf(LibraryTab("genres", LibraryTabKind.GENRES), own, LibraryTab("all", LibraryTabKind.ALL, title = "Everything"))
        val tabs = LibraryTabs.resolve(saved, hasRecommended = true)
        // A kind the saved list never had comes after, shown only if it's one shown by default.
        assertEquals(listOf("Genres", "Kids", "Everything", "Recommended"), LibraryTabs.shown(tabs).map { it.label })
        assertTrue(tabs.drop(3).all { it.hidden || it.kind == LibraryTabKind.RECOMMENDED })
    }

    @Test fun `everything hidden still leaves All`() {
        val tabs = LibraryTabs.resolve(emptyList(), true).map { it.copy(hidden = true) }
        assertEquals(listOf(LibraryTabKind.ALL), LibraryTabs.shown(tabs).map { it.kind })
    }

    @Test fun `Recommended keeps the library layout key and other tabs get their own`() {
        assertEquals(PageKeys.library("l1"), LibraryTabs.pageKey("l1", LibraryTab("recommended", LibraryTabKind.RECOMMENDED)))
        assertEquals("library/l1/tab/own2", LibraryTabs.pageKey("l1", LibraryTab("own2", LibraryTabKind.OWN)))
        val made = LibraryTabs.newOwn(listOf(LibraryTab("own1", LibraryTabKind.OWN)), "4K")
        assertEquals("own2", made.id)
        assertEquals(listOf("b", "a"), LibraryTabs.move(listOf(LibraryTab("a", LibraryTabKind.ALL), LibraryTab("b", LibraryTabKind.GENRES)), "b", -1).map { it.id })
    }
}
