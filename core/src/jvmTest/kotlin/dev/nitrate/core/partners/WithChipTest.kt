package dev.nitrate.core.partners

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.UserState
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

    /** A Group Watch Session on [itemId] with [names] (the user, "Me", among them). */
    private fun session(itemId: String, vararg names: String, mine: Boolean = false) = GroupWatchSession(
        "s1", "ts", itemId, "S1:E4", "The Bear", "Tommy", mine, GroupWatchSession.PLAYING, 0L, 0, 0, false, 0L,
        names.map { SessionMember(it.lowercase(), it, it == "Me", 0) }, true, 0L,
    )

    @Test fun saysWhichSituationThePlaybackIsIn() {
        val st = standings(tommySuzy)
        val on = WithChip.of(eps[3], info(tommySuzy), st, null, "Me")!!
        assertEquals("Counting for Tommy & Suzy · Change", on.label)
        assertFalse(on.ambiguous)
        // No group, chosen.
        assertEquals("No group · Change", WithChip.of(eps[3], info(tommySuzy), st, null, "Me", with = WatchingWith.JustMe)!!.label)
        // A Group Watch Session: the user left out.
        assertEquals("In a Group Watch Session with Tommy, Suzy", WithChip.of(eps[3], info(tommySuzy), st, session("e4", "Me", "Tommy", "Suzy"), "Me")!!.label)
        assertEquals("In a Group Watch Session · waiting for others", WithChip.of(eps[3], info(tommySuzy), st, session("e4", "Me"), "Me")!!.label)
        // A session on another part (this player isn't in it): counted as usual.
        assertEquals("Counting for Tommy & Suzy · Change", WithChip.of(eps[3], info(tommySuzy), st, session("e9", "Me", "Tommy"), "Me")!!.label)
        // In the same room with some of the group.
        assertEquals("In the room with Tommy", WithChip.of(eps[3], info(tommySuzy), st, null, "Me", room = SameRoom("ts", listOf("t")))!!.label)
        // Unshared and no session: no chip; a session on an unshared title still shows one.
        assertNull(WithChip.of(film, info(tommySuzy), st, null, "Me"))
        assertEquals("In a Group Watch Session with Kim", WithChip.of(film, info(tommySuzy), st, session("f", "Me", "Kim"), "Me")!!.label)
        assertEquals("Tommy, Suzy, Kim & 2 more", WithChip.and(listOf("Tommy", "Suzy", "Kim", "Lee", "Sam")))
    }

    @Test fun moreThanOneGroupFittingIsShownUntilChosen() {
        // Kim's group has already watched episode 4: it's next only with Tommy & Suzy, so the chip says it counts there.
        val kimAhead = kim.copy(ownRecord = (1..4).map { "e$it" }.toSet())
        val chip = WithChip.of(eps[3], info(tommySuzy, kimAhead), standings(tommySuzy, kimAhead), null, "Me")!!
        assertTrue(chip.ambiguous)
        assertEquals("Counting for: Tommy & Suzy", chip.counting)
        // Next in both groups and started elsewhere: it counts for neither until chosen.
        val st = standings(tommySuzy, kim)
        val neither = WithChip.of(eps[3], info(tommySuzy, kim), st, null, "Me")!!
        assertFalse(neither.ambiguous)
        assertEquals("No group · Change", neither.label)
        val chosen = WithChip.of(eps[3], info(tommySuzy, kim), st, null, "Me", with = WatchingWith.Group("k"))!!
        assertFalse(chosen.ambiguous)
        assertEquals("Counting for Kim · Change", chosen.label)
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
        assertEquals("Counting for Kim · Change", company.chip.value!!.label)
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
        assertEquals("Counting for Kim · Change", next.chip.value!!.label)
    }

    @Test fun fromAGroupsCardItCountsThereAndAsksAboutTheOthersOnce() = runTest(UnconfinedTestDispatcher()) {
        // Episode 4 is next with Tommy & Suzy and with Kim; played from Tommy & Suzy's card.
        val st = standings(tommySuzy, kim)
        val marked = mutableListOf<String>()
        val asked = mutableListOf<CountAsk>()
        val playing = mutableMapOf<String, PlayOrigin>()
        val handed = mutableMapOf<String, PlayOrigin>("e4" to PlayOrigin.GroupCard("ts"))
        val company = WatchCompany(
            backgroundScope, MutableStateFlow(info(tommySuzy, kim)), MutableStateFlow(st), MutableStateFlow(null), { "Me" },
            originOf = { handed.remove(it.id) },
            onPlaying = { t, o -> playing[t] = o },
            mark = { groups, items -> marked += "$groups $items" },
            ask = { asked += it },
        )
        company.start(eps[3], st)
        advanceUntilIdle()
        assertEquals("Counting for Tommy & Suzy · Change", company.chip.value!!.label)
        assertEquals(PlayOrigin.GroupCard("ts"), playing["bear"])
        // Not to the end: nothing; to the end: counted for the card's group, and asked about Kim's once.
        company.left("e4", 30_000, 60_000)
        company.left("e4", 55_000, 60_000)
        company.left("e4", 60_000, 60_000, ended = true)
        assertEquals(listOf("[ts] [e4]"), marked)
        assertEquals(1, asked.size)
        assertEquals("Also count it for Kim, where it's next too?", asked[0].question)
        assertEquals(listOf("k"), asked[0].others)
        assertEquals(listOf(CountAsk.JUST_THIS, CountAsk.ALL), CountAsk.CHOICES)
        // Up Next in the same title carries on from the card.
        company.start(eps[4], st)
        assertEquals(PlayOrigin.GroupCard("ts"), company.startedFrom)
        // "Change" → no group: nothing is counted.
        company.countFor(WatchingWith.JustMe)
        company.left("e5", 60_000, 60_000, ended = true)
        assertEquals(1, marked.size)
        // In a Group Watch Session the server counts it, not the app.
        val live = WatchCompany(
            backgroundScope, MutableStateFlow(info(tommySuzy, kim)), MutableStateFlow(st),
            MutableStateFlow(session("e4", "Me", "Tommy")), { "Me" },
            mark = { groups, items -> marked += "$groups $items" },
        )
        live.start(eps[3], st, PlayOrigin.GroupCard("ts"))
        live.left("e4", 60_000, 60_000, ended = true)
        assertEquals(1, marked.size)
    }

    @Test fun startedElsewhereItCountsOnlyWhereItsTheOneNextPart() = runTest(UnconfinedTestDispatcher()) {
        val kimAhead = kim.copy(ownRecord = (1..4).map { "e$it" }.toSet())
        val st = standings(tommySuzy, kimAhead)
        val marked = mutableListOf<String>()
        val company = WatchCompany(backgroundScope, MutableStateFlow(info(tommySuzy, kimAhead)), MutableStateFlow(st), MutableStateFlow(null), { "Me" }, mark = { g, i -> marked += "$g $i" })
        company.start(eps[3], st)
        advanceUntilIdle()
        assertEquals("Counting for Tommy & Suzy · Change", company.chip.value!!.label)
        company.left("e4", 60_000, 60_000, ended = true)
        assertEquals(listOf("[ts] [e4]"), marked)
        // "Watch for myself": no group.
        val mine = WatchCompany(backgroundScope, MutableStateFlow(info(tommySuzy, kimAhead)), MutableStateFlow(st), MutableStateFlow(null), { "Me" }, mark = { g, i -> marked += "$g $i" })
        mine.start(eps[3], st, PlayOrigin.ForMyself)
        advanceUntilIdle()
        assertEquals("No group · Change", mine.chip.value!!.label)
        mine.left("e4", 60_000, 60_000, ended = true)
        assertEquals(1, marked.size)
    }
}
