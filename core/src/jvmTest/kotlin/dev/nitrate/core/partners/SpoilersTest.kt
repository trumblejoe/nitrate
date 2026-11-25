package dev.nitrate.core.partners

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.UserState
import dev.nitrate.core.pages.DisplayPrefs
import dev.nitrate.core.writing.UserRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One spoiler rule for every screen: what's seen anywhere, Together groups, partners, schedules. */
class SpoilersTest {
    private fun ep(n: Int, played: Boolean = false) = MediaItem(
        "e$n", "The Long Night", ItemKind.EPISODE, seriesId = "bear", seriesName = "The Bear", seasonNumber = 1, indexNumber = n,
        overview = "Everything changes.", user = UserState(played = played),
    )

    private val off = DisplayPrefs(hideSpoilers = false)
    private val on = DisplayPrefs(hideSpoilers = true)
    private fun group(together: Boolean, record: Set<String> = emptySet()) = WatchGroup(
        "ts", "bear", "The Bear", listOf(UserRef("t", "Tommy")), 0, schedule = GroupSchedule(together = together), ownRecord = record,
    )
    private fun info(vararg g: WatchGroup) = PartnersInfo(g.toList(), emptyList(), emptyList())
    private val noLock: (MediaItem) -> Boolean = { false }

    @Test fun aTogetherGroupHidesWhateverTheSetting() {
        // Not seen, "Hide spoilers" off: only a Together group's title hides it.
        assertFalse(Spoilers.picture(off, ep(5), emptyList(), info(group(together = false)), false))
        assertTrue(Spoilers.picture(off, ep(5), emptyList(), info(group(together = true)), false))
        assertTrue(Spoilers.picture(on, ep(5), emptyList(), null, false))
    }

    @Test fun seenWithAGroupCountsAsSeen() {
        // Watched with the group (kept out of the user's own history): not a spoiler for them.
        assertFalse(Spoilers.picture(on, ep(5), emptyList(), info(group(together = true, record = setOf("e5"))), false))
        assertFalse(Spoilers.picture(on, ep(5, played = true), emptyList(), null, false))
    }

    @Test fun aPartnerBehindKeepsItHiddenAndASchedulesLockAlways() {
        val tommy = PartnerProgress("ts", "t", "Tommy", setOf("e1"), "e1", "e2")
        assertTrue(Spoilers.picture(on, ep(5, played = true), listOf(tommy), info(group(true)), false))
        assertFalse(Spoilers.picture(on.copy(ownSpoilerShows = setOf("bear")), ep(5, played = true), listOf(tommy), info(group(true)), false))
        assertTrue(Spoilers.picture(off, ep(5, played = true), emptyList(), null, locked = true))
    }

    @Test fun theSafeCopyIsNamedByNumberWithoutItsDescription() {
        val safe = Spoilers.safe(ep(5), on, emptyList(), null, noLock)
        assertTrue(safe.spoiler)
        assertEquals("Episode 5", safe.name)
        assertNull(safe.overview)
        assertEquals("S1:E5", safe.episodeLabel)
        // Seen: as it is.
        assertEquals(ep(5, true), Spoilers.safe(ep(5, true), on, emptyList(), null, noLock))
        // A film not seen: only its description held back (a poster isn't a spoiler).
        val film = MediaItem("f", "Heat", ItemKind.MOVIE, overview = "A heist.")
        val safeFilm = Spoilers.safe(film, on, emptyList(), null, noLock)
        assertFalse(safeFilm.spoiler)
        assertEquals("Heat", safeFilm.name)
        assertNull(safeFilm.overview)
    }
}
