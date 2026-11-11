package dev.nitrate.tv.plugins.previousepisode

import dev.nitrate.tv.plugins.previousepisode.EpisodeOrder.Scope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeOrderTest {
    private fun ep(s: Int, e: Int, played: Boolean = false, end: Int? = null) =
        EpisodeRef("s${s}e$e", s, e, episodeEnd = end, played = played)

    private fun special(e: Int, beforeSeason: Int? = null, beforeEpisode: Int? = null, afterSeason: Int? = null, played: Boolean = false) =
        EpisodeRef("sp$e", 0, e, airsBeforeSeason = beforeSeason, airsBeforeEpisode = beforeEpisode, airsAfterSeason = afterSeason, played = played)

    private fun ids(list: List<EpisodeRef>) = list.map { it.id }

    @Test
    fun `specials slot in before a season, mid-season, after a season and after the finale`() {
        val order = EpisodeOrder.viewingOrder(
            listOf(
                ep(1, 1), ep(1, 2), ep(2, 1), ep(2, 2), ep(2, 3),
                special(1, beforeSeason = 1), // prequel before the show starts
                special(2, beforeSeason = 2, beforeEpisode = 3), // mid-season 2
                special(3, afterSeason = 1), // between seasons
                special(4, afterSeason = 2), // after the finale
                special(5), // no placement
            ),
        )
        assertEquals(listOf("sp1", "s1e1", "s1e2", "sp3", "s2e1", "s2e2", "sp2", "s2e3", "sp4"), ids(order))
    }

    @Test
    fun `several specials in one slot keep their own order`() {
        val order = EpisodeOrder.viewingOrder(listOf(ep(2, 1), special(7, beforeSeason = 2), special(6, beforeSeason = 2)))
        assertEquals(listOf("sp6", "sp7", "s2e1"), ids(order))
    }

    @Test
    fun `gaps cover a late season start, jumps, skipped seasons and respect double episodes`() {
        val gaps = EpisodeOrder.gaps(listOf(ep(1, 1, end = 2), ep(1, 3), ep(1, 5), ep(3, 2)))
        assertEquals(
            listOf(
                Gap.MissingEpisodes(1, 4, 4, ep(1, 5)),
                Gap.MissingSeason(2, ep(3, 2)),
                Gap.MissingEpisodes(3, 1, 1, ep(3, 2)),
            ),
            gaps,
        )
    }

    @Test
    fun `previous scope looks only at what comes right before, including a special`() {
        val eps = listOf(ep(1, 1), ep(1, 2, played = true), special(1, beforeSeason = 1, beforeEpisode = 3), ep(1, 3))
        val result = EpisodeOrder.check(eps, "s1e3", Scope.PREVIOUS, includeSpecials = true, detectGaps = true)
        assertEquals(listOf("sp1"), ids(result.unwatched))
    }

    @Test
    fun `specials can be ignored`() {
        val eps = listOf(ep(1, 2, played = true), special(1, beforeSeason = 1, beforeEpisode = 3), ep(1, 3))
        assertTrue(EpisodeOrder.check(eps, "s1e3", Scope.PREVIOUS, includeSpecials = false, detectGaps = false).clear)
    }

    @Test
    fun `season scope stays in the target's season and counts specials airing in it`() {
        val eps = listOf(ep(1, 1), ep(2, 1, played = true), special(1, beforeSeason = 2, beforeEpisode = 2), ep(2, 2), ep(2, 3))
        val result = EpisodeOrder.check(eps, "s2e3", Scope.SEASON, includeSpecials = true, detectGaps = false)
        assertEquals(listOf("sp1", "s2e2"), ids(result.unwatched))
    }

    @Test
    fun `series scope reaches back across seasons`() {
        val eps = listOf(ep(1, 1), ep(2, 1, played = true), ep(2, 2))
        assertEquals(listOf("s1e1"), ids(EpisodeOrder.check(eps, "s2e2", Scope.SERIES, true, false).unwatched))
    }

    @Test
    fun `a missing episode right before the target is reported even when everything present is watched`() {
        val eps = listOf(ep(1, 1, played = true), ep(1, 2, played = true), ep(1, 4))
        val result = EpisodeOrder.check(eps, "s1e4", Scope.PREVIOUS, includeSpecials = true, detectGaps = true)
        assertTrue(result.unwatched.isEmpty())
        assertEquals(listOf(Gap.MissingEpisodes(1, 3, 3, ep(1, 4))), result.gaps)
    }

    @Test
    fun `the first episode of a show is always clear`() {
        assertTrue(EpisodeOrder.check(listOf(ep(1, 1), ep(1, 2)), "s1e1", Scope.SERIES, true, true).clear)
    }
}
