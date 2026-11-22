package dev.nitrate.testing

import dev.nitrate.core.music.Bookmark
import dev.nitrate.core.music.BookmarkStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Bookmarks kept in memory for screenshots and tests. */
class FakeBookmarks(initial: Map<String, List<Bookmark>> = emptyMap()) : BookmarkStore {
    val all = MutableStateFlow(initial)
    override fun bookmarks(itemId: String) = all.map { it[itemId].orEmpty() }
    override suspend fun save(itemId: String, bookmarks: List<Bookmark>) { all.value = all.value + (itemId to bookmarks) }
}
