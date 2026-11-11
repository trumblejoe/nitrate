package dev.nitrate.core.rows

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.UserState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class BecauseYouWatchedTest {
    private fun movie(id: String, played: Boolean = false) = MediaItem(id, id.uppercase(), ItemKind.MOVIE, user = UserState(played = played))
    private fun episode(id: String, series: String) = MediaItem(id, "Ep", ItemKind.EPISODE, seriesId = series, seriesName = "Show $series")

    @Test
    fun `rows from recent watches without repeats or watched titles`() = runBlocking {
        val recent = listOf(episode("e2", "s1"), episode("e1", "s1"), movie("m1", played = true), movie("m2", played = true))
        val similar = mapOf(
            "s1" to listOf(movie("a"), movie("b"), movie("c"), movie("m2", played = true)),
            "m1" to listOf(movie("a"), movie("b"), movie("d")), // Only "d" is new: too few.
            "m2" to listOf(movie("e"), movie("f"), movie("g"), movie("a")),
        )
        val rows = BecauseYouWatched.rows(recent, { similar[it].orEmpty() })
        assertEquals(listOf("home/because_you_watched_1", "home/because_you_watched_2"), rows.map { it.key })
        assertEquals("Because you watched Show s1", rows[0].title)
        assertEquals(listOf("a", "b", "c"), rows[0].items.map { it.id })
        assertEquals("Because you watched M2", rows[1].title)
        assertEquals(listOf("e", "f", "g"), rows[1].items.map { it.id })
    }
}
