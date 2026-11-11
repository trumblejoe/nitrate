package dev.nitrate.core.rows

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeChannelContentTest {
    private fun movie(id: String) = MediaItem(id, id, ItemKind.MOVIE)
    private fun ep(id: String, series: String) = MediaItem(id, id, ItemKind.EPISODE, seriesId = series)

    @Test
    fun `play next carries on, the channel starts something new`() {
        val rows = listOf(
            MediaRow("home/continue_watching", "", listOf(movie("m1"), ep("e5", "s1")), RowDisplay()),
            MediaRow("home/next_up", "", listOf(ep("e6", "s1"), ep("x2", "s2")), RowDisplay()),
            MediaRow("home/latest/lib", "", listOf(movie("m1"), movie("m9"), MediaItem("show", "show", ItemKind.SERIES)), RowDisplay()),
        )
        val content = HomeChannelContent.from(rows)
        assertEquals(listOf("m1", "e5", "x2"), content.watchNext.map { it.item.id })
        assertEquals(listOf(HomeChannelContent.WatchNextEntry.Kind.CONTINUE, HomeChannelContent.WatchNextEntry.Kind.CONTINUE, HomeChannelContent.WatchNextEntry.Kind.NEXT), content.watchNext.map { it.kind })
        assertEquals(listOf("e6", "m9", "show"), content.channel.map { it.id })
    }
}
