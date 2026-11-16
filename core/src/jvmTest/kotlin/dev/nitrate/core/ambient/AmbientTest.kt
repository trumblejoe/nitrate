package dev.nitrate.core.ambient

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.rows.MediaRow
import dev.nitrate.core.rows.RowDisplay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AmbientTest {
    private fun row(key: String, vararg items: MediaItem) = MediaRow(key, key, items.toList(), RowDisplay())
    private val e5 = MediaItem("e5", "The Twist Revealed", ItemKind.EPISODE, seriesId = "s1", seriesName = "The Night Reel", seasonNumber = 2, indexNumber = 5)
    private val e6 = MediaItem("e6", "Aftermath", ItemKind.EPISODE, seriesId = "s1", seriesName = "The Night Reel", seasonNumber = 2, indexNumber = 6)
    private val film = MediaItem("m1", "Silver Screen", ItemKind.MOVIE, productionYear = 2021)
    private val song = MediaItem("a1", "Song", ItemKind.AUDIO)
    private val noArt = MediaItem("m2", "Bare", ItemKind.MOVIE)

    @Test fun carryingOnFirstOnePerShowNoEpisodeNames() {
        val slides = Ambient.slides(
            listOf(row("home/latest/x", film, noArt, song), row("home/next_up", e6), row("home/continue_watching", e5)),
        ) { it.id != "m2" }
        assertEquals(listOf("e5", "m1"), slides.map { it.item.id })
        val first = slides.first()
        assertEquals("Continue watching", first.label)
        assertEquals("The Night Reel", first.title)
        assertEquals("S2 · E5", first.detail)
        assertFalse(slides.any { s -> "Twist" in s.title || "Twist" in (s.detail ?: "") })
        assertEquals("New", slides[1].label)
        assertEquals("2021", slides[1].detail)
    }

    @Test fun due() {
        assertTrue(Ambient.due(5 * 60_000, 5, playing = false))
        assertFalse(Ambient.due(4 * 60_000, 5, playing = false))
        assertFalse(Ambient.due(60 * 60_000, 0, playing = false))
        assertFalse(Ambient.due(60 * 60_000, 5, playing = true))
    }
}
