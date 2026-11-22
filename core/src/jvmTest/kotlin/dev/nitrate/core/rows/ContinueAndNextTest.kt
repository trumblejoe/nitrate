package dev.nitrate.core.rows

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import kotlin.test.Test
import kotlin.test.assertEquals

class ContinueAndNextTest {
    private fun item(id: String, series: String? = null) = MediaItem(id = id, name = id, kind = if (series == null) ItemKind.MOVIE else ItemKind.EPISODE, seriesId = series)

    @Test fun partWayFirstThenNextEpisodesOfOtherShows() {
        val merged = ContinueAndNext.merge(
            resume = listOf(item("film"), item("a-s1e3", "a")),
            nextUp = listOf(item("a-s1e3", "a"), item("b-s2e1", "b"), item("a-s1e4", "a")),
        )
        // Show "a" appears once (the part-way episode); "b"'s next episode follows.
        assertEquals(listOf("film", "a-s1e3", "b-s2e1"), merged.map { it.id })
    }
}
