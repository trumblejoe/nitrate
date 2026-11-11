package dev.nitrate.core.episodes

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.UserState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpNextTest {
    private fun ep(season: Int, n: Int, played: Boolean = false, before: Int? = null, after: Int? = null) = MediaItem(
        "s${season}e$n", "E$n", ItemKind.EPISODE, seriesId = "show", seasonNumber = season, indexNumber = n,
        airsBeforeSeason = before, airsAfterSeason = after, user = UserState(played = played),
    )

    private val s1e1 = ep(1, 1, played = true)
    private val s1e2 = ep(1, 2, played = true)
    private val special = ep(0, 1, after = 1)
    private val s2e1 = ep(2, 1)
    private val s2e2 = ep(2, 2)
    private val all = listOf(s2e2, special, s1e2, s2e1, s1e1)

    @Test
    fun `next follows viewing order with specials in place`() {
        assertEquals(s1e2, UpNext.after(s1e1, all))
        assertEquals(special, UpNext.after(s1e2, all))
        assertEquals(s2e1, UpNext.after(special, all))
        assertNull(UpNext.after(s2e2, all))
        assertNull(UpNext.after(MediaItem("m", "Movie", ItemKind.MOVIE), all))
    }

    @Test
    fun `upcoming starts after the last watched episode`() {
        assertEquals(listOf(special, s2e1), UpNext.upcoming(all, 2))
        // An unwatched episode before the last watched one is not picked up again.
        val skipped = listOf(ep(1, 1), ep(1, 2, played = true), ep(1, 3))
        assertEquals(listOf("s1e3"), UpNext.upcoming(skipped, 3).map { it.id })
    }
}
