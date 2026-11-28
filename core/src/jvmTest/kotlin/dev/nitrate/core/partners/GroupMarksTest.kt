package dev.nitrate.core.partners

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.writing.UserRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A page's group marks: only entered from a group, only on its titles' parts, asking only when it's next elsewhere too. */
class GroupMarksTest {
    private val eps = (1..6).map { MediaItem("e$it", "Episode $it", ItemKind.EPISODE, seriesId = "bear", seasonNumber = 1, indexNumber = it) }
    private val show = MediaItem("bear", "The Bear", ItemKind.SERIES)
    private val tommy = WatchGroup("t", "bear", "The Bear", listOf(UserRef("t", "Tommy")), 0, ownRecord = setOf("e1", "e2", "e3"))
    private val kim = WatchGroup("k", "bear", "The Bear", listOf(UserRef("k", "Kim")), 0, ownRecord = setOf("e1", "e2", "e3"))
    private val ahead = WatchGroup("a", "bear", "The Bear", listOf(UserRef("s", "Sam")), 0, ownRecord = setOf("e1", "e2", "e3", "e4"))
    private val info = PartnersInfo(listOf(tommy, kim, ahead), emptyList(), emptyList())
    private val standings = mapOf("bear" to KeepUp.of("bear", eps, emptyList(), info.groups))

    @Test fun aPartIsDrawnAsTheGroupHasIt() {
        // Watched alone long ago, not yet with the group: no tick here; the group's resume point is its progress.
        val alone = eps[4].copy(runtimeMs = 1_000_000, user = eps[4].user.copy(played = true))
        val g = tommy.copy(resume = mapOf("e5" to GroupResume(250_000, 1)))
        val shown = GroupMarks.asGroupSees(g, alone)
        assertFalse(shown.user.played)
        assertEquals(250_000L, shown.user.positionMs)
        assertEquals(25.0, shown.user.playedPercentage!!, 0.01)
        // Watched with the group: ticked, whatever the user's own state.
        assertTrue(GroupMarks.asGroupSees(tommy, eps[2]).user.played)
    }

    @Test fun onlyAPageEnteredFromTheGroupHasItsMark() {
        // Entered from Tommy's group: the episode has the group's mark; the show itself doesn't (its episodes do).
        assertEquals("t", GroupMarks.groupFor("t", eps[3], info, standings)?.id)
        assertNull(GroupMarks.groupFor("t", show, info, standings))
        // Entered any other way (library, search, Continue Watching): only the user's own Watched.
        assertNull(GroupMarks.groupFor(null, eps[3], info, standings))
        // Not one of the group's titles.
        assertNull(GroupMarks.groupFor("t", MediaItem("x", "Elsewhere", ItemKind.MOVIE), info, standings))
        assertTrue(GroupMarks.watched(tommy, eps[2]))
        assertFalse(GroupMarks.watched(tommy, eps[3]))
        assertEquals("Not watched on your track with Tommy", GroupMarks.label(tommy, eps[3]))
        assertEquals("Watched on your track with Tommy", GroupMarks.label(tommy, eps[2]))
    }

    @Test fun markingWatchedAsksOnlyWhenItsNextInOtherGroupsToo() {
        // Episode 4 is next with Tommy and with Kim (Sam's group is past it): ask about Kim only.
        val others = GroupMarks.alsoNext(eps[3], tommy, watched = true, info, standings)
        assertEquals(listOf("k"), others.map { it.id })
        assertEquals("Also mark it watched on your track with Kim, where it's next too?", GroupMarks.question(others))
        // Unwatched never asks; a part further on (not next anywhere else) doesn't either.
        assertTrue(GroupMarks.alsoNext(eps[3], tommy, watched = false, info, standings).isEmpty())
        assertTrue(GroupMarks.alsoNext(eps[5], tommy, watched = true, info, standings).isEmpty())
    }
}
