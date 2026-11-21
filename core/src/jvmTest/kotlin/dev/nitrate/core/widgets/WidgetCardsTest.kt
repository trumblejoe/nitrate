package dev.nitrate.core.widgets

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.UserState
import dev.nitrate.core.rows.MediaRow
import dev.nitrate.core.rows.RowDisplay
import kotlin.test.Test
import kotlin.test.assertEquals

class WidgetCardsTest {
    private fun ep(id: String, show: String, s: Int, e: Int, pos: Long = 0) = MediaItem(
        id, "Secret name $id", ItemKind.EPISODE, seriesId = show, seriesName = "Show $show", seasonNumber = s, indexNumber = e,
        runtimeMs = 1_000_000, user = UserState(positionMs = pos),
    )

    @Test fun cardsNameTheShowAndPlaceNeverTheEpisode() {
        val c = WidgetCards.card(ep("e1", "a", 2, 5, pos = 250_000))
        assertEquals("Show a", c.title)
        assertEquals("S2 · E5", c.line)
        assertEquals(0.25f, c.progress)
        assertEquals("Show a, season 2, episode 5", c.spoken)
        val film = WidgetCards.card(MediaItem("m", "Film", ItemKind.MOVIE, productionYear = 2020))
        assertEquals("2020", film.line)
        assertEquals(null, film.progress)
    }

    @Test fun oneCardPerShowFromTheWidgetsOwnRow() {
        val rows = listOf(
            MediaRow("home/next_up", "Next Up", listOf(ep("1", "a", 1, 2), ep("2", "a", 1, 3), ep("3", "b", 4, 1)), RowDisplay()),
            MediaRow("home/continue_watching", "Continue", listOf(ep("9", "c", 1, 1, 10)), RowDisplay()),
        )
        assertEquals(listOf("1", "3"), WidgetCards.of(WidgetKind.NEXT_UP, rows).map { it.itemId })
        assertEquals(listOf("9"), WidgetCards.of(WidgetKind.CONTINUE, rows).map { it.itemId })
        assertEquals(emptyList(), WidgetCards.of(WidgetKind.CONTINUE, emptyList()))
    }
}
