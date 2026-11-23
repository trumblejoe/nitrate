package dev.nitrate.core.partners

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.UserState
import dev.nitrate.core.syncplay.GroupState
import dev.nitrate.core.syncplay.SyncPlayGroup
import dev.nitrate.core.writing.UserRef
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The player's "With…" chip: which situation a playback is in, and what choosing in its panel does. */
@OptIn(ExperimentalCoroutinesApi::class)
class WithChipTest {
    private fun ep(n: Int, played: Boolean) =
        MediaItem("e$n", "Episode $n", ItemKind.EPISODE, seriesId = "bear", seasonNumber = 1, indexNumber = n, user = UserState(played = played))

    private val eps = (1..10).map { ep(it, played = it <= 3) }
    private val tommySuzy = WatchGroup("ts", "bear", "The Bear", listOf(UserRef("t", "Tommy"), UserRef("s", "Suzy")), 0)
    private val kim = WatchGroup("k", "bear", "The Bear", listOf(UserRef("k", "Kim")), 0)
    private val progress = listOf(
        PartnerProgress("ts", "t", "Tommy", (1..3).map { "e$it" }.toSet(), "S1 E3", "e4"),
        PartnerProgress("ts", "s", "Suzy", (1..3).map { "e$it" }.toSet(), "S1 E3", "e4"),
        PartnerProgress("k", "k", "Kim", (1..3).map { "e$it" }.toSet(), "S1 E3", "e4"),
    )

    private fun standings(vararg groups: WatchGroup) = mapOf("bear" to KeepUp.of("bear", eps, progress, groups.toList()))
    private fun info(vararg groups: WatchGroup) = PartnersInfo(groups.toList(), emptyList(), emptyList())
    private val film = MediaItem("f", "Alone", ItemKind.MOVIE)

    @Test fun saysWhichSituationThePlaybackIsIn() {
        val st = standings(tommySuzy)
        val on = WithChip.of(eps[3], info(tommySuzy), st, null, "Me")!!
        assertEquals("With Tommy & Suzy · on your own", on.label)
        assertFalse(on.ambiguous)
        // Just me, chosen.
        assertEquals("Just me", WithChip.of(eps[3], info(tommySuzy), st, null, "Me", with = WatchingWith.JustMe)!!.label)
        // A watch party: the user's own name (any device) left out.
        val party = SyncPlayGroup("p", "Party", GroupState.PLAYING, listOf("Me", "Tommy", "me", "Suzy"))
        assertEquals("Watch party with Tommy & Suzy", WithChip.of(eps[3], info(tommySuzy), st, party, "Me")!!.label)
        assertEquals("Watch party · waiting for others", WithChip.of(eps[3], info(tommySuzy), st, party.copy(participants = listOf("Me")), "Me")!!.label)
        // In the same room with some of the group.
        assertEquals("In the room with Tommy", WithChip.of(eps[3], info(tommySuzy), st, null, "Me", room = SameRoom("ts", listOf("t")))!!.label)
        // Unshared and no party: no chip; a party on an unshared title still shows one.
        assertNull(WithChip.of(film, info(tommySuzy), st, null, "Me"))
        assertEquals("Watch party with Kim", WithChip.of(film, info(tommySuzy), st, party.copy(participants = listOf("Me", "Kim")), "Me")!!.label)
        assertEquals("Tommy, Suzy, Kim & 2 more", WithChip.and(listOf("Tommy", "Suzy", "Kim", "Lee", "Sam")))
    }

    @Test fun moreThanOneGroupFittingIsShownUntilChosen() {
        val st = standings(tommySuzy, kim)
        val chip = WithChip.of(eps[3], info(tommySuzy, kim), st, null, "Me")!!
        assertTrue(chip.ambiguous)
        assertEquals("Counting for: Tommy & Suzy", chip.counting)
        val chosen = WithChip.of(eps[3], info(tommySuzy, kim), st, null, "Me", with = WatchingWith.Group("k"))!!
        assertFalse(chosen.ambiguous)
        assertEquals("With Kim · on your own", chosen.label)
    }

    @Test fun theCompanyKeepsChoicesAcrossAShowAndCountsTheRoom() = runTest(UnconfinedTestDispatcher()) {
        val st = standings(tommySuzy, kim)
        val recorded = mutableListOf<String>()
        val chosen = mutableListOf<String>()
        // As the plugin keeps them for this run of the app (the guard's "We're in the same room" set one).
        val rooms = mutableMapOf("bear" to SameRoom("ts", listOf("t", "s")))
        val choices = mutableMapOf<String, WatchingWith>()
        val company = WatchCompany(
            backgroundScope, MutableStateFlow(info(tommySuzy, kim)), MutableStateFlow(st), MutableStateFlow(null), { "Me" },
            chosen = { choices[it] },
            onChosen = { t, w -> choices[t] = w; chosen += "$t ${(w as? WatchingWith.Group)?.groupId ?: "me"}" },
            room = { rooms[it] },
            onRoom = { t, r -> if (r == null) rooms -= t else rooms[t] = r },
            record = { g, items, present -> recorded += "$g $items $present" },
        )
        // The guard's "We're in the same room" carries into the player.
        company.start(eps[3], st)
        advanceUntilIdle()
        assertEquals("In the room with Tommy & Suzy", company.chip.value!!.label)
        // Not to the end: nothing counted; to the end: counted once for those there.
        company.left("e4", 10_000, 60_000)
        company.left("e4", 55_000, 60_000)
        company.left("e4", 60_000, 60_000, ended = true)
        assertEquals(listOf("ts [e4] [t, s]"), recorded)
        // Rolling on keeps the room; only Tommy there now.
        company.start(eps[4], st)
        company.sameRoom("ts", listOf("t"))
        advanceUntilIdle()
        assertEquals("In the room with Tommy", company.chip.value!!.label)
        // Counting for another group ends the room.
        company.countFor(WatchingWith.Group("k"))
        advanceUntilIdle()
        assertEquals("With Kim · on your own", company.chip.value!!.label)
        assertEquals(listOf("bear ts", "bear k"), chosen)
        company.left("e5", 60_000, 60_000, ended = true)
        assertEquals(1, recorded.size)
        assertTrue(rooms.isEmpty())
        // Another title starts afresh.
        company.start(film, st)
        advanceUntilIdle()
        assertNull(company.chip.value)
        assertNull(company.choice)
        // A later player on the show (the next episode) keeps "Counts for".
        val next = WatchCompany(backgroundScope, MutableStateFlow(info(tommySuzy, kim)), MutableStateFlow(st), MutableStateFlow(null), { "Me" }, chosen = { choices[it] })
        next.start(eps[5], st)
        advanceUntilIdle()
        assertEquals("With Kim · on your own", next.chip.value!!.label)
    }
}
