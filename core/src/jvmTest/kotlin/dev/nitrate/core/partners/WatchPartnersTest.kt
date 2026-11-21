package dev.nitrate.core.partners

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.UserState
import dev.nitrate.core.pages.DisplayPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchPartnersTest {
    private fun ep(n: Int, played: Boolean = false, season: Int = 1) =
        MediaItem("e$season-$n", "Episode $n", ItemKind.EPISODE, seriesId = "bear", seasonNumber = season, indexNumber = n, user = UserState(played = played))

    private val sam = PartnerProgress("g1", "sam", "Sam", setOf("e1-1", "e1-2"), "S1 E2", "e1-3")
    private val kim = PartnerProgress("g2", "kim", "Kim", setOf("e1-1"), "S1 E1", "e1-2")

    @Test fun whoIsBehindAndHowTheyAreNamed() {
        assertEquals(listOf("Kim"), WatchPartners.behind(ep(2), listOf(sam, kim)).map { it.userName })
        assertEquals("Sam and Kim", WatchPartners.names(listOf("Sam", "Kim")))
        assertEquals("A, B and C", WatchPartners.names(listOf("A", "B", "C")))
    }

    @Test fun spoilersGoByWhoeverIsFurthestBehind() {
        val prefs = DisplayPrefs(hideSpoilers = true)
        // I've seen it, Kim hasn't: still hidden.
        assertTrue(WatchPartners.hidesDescription(prefs, ep(2, played = true), listOf(sam, kim)))
        // Everyone's seen it.
        assertFalse(WatchPartners.hidesDescription(prefs, ep(1, played = true), listOf(sam, kim)))
        assertFalse(WatchPartners.hidesDescription(DisplayPrefs(), ep(2), listOf(kim)))
        // The user chose their own progress for this show: Kim being behind no longer hides it.
        assertFalse(WatchPartners.hidesDescription(prefs.copy(ownSpoilerShows = setOf("bear")), ep(2, played = true), listOf(sam, kim)))
        assertTrue(WatchPartners.hidesDescription(prefs.copy(ownSpoilerShows = setOf("bear")), ep(3), listOf(sam, kim)))
    }

    @Test fun upNextTogetherIsTheFirstEpisodeSomeoneHasntSeen() {
        val episodes = listOf(ep(0, season = 0), ep(1, true), ep(2, true), ep(3))
        assertEquals("e1-2", WatchPartners.nextTogether(episodes, listOf(sam, kim))?.id)
        assertEquals("e1-3", WatchPartners.nextTogether(episodes, listOf(sam))?.id)
        assertNull(WatchPartners.nextTogether(listOf(ep(1, true)), listOf(PartnerProgress("g", "x", "X", setOf("e1-1"), null, null))))
    }

    // ---- Where the user stands (cards on Home) ----

    private val eps = (1..4).map { ep(it) }

    @Test fun partnerAheadMeansCatchUp() {
        val k = KeepUp.of("bear", eps.mapIndexed { i, e -> if (i == 0) e.copy(user = UserState(played = true)) else e }, listOf(sam))
        val s = WatchPartners.standing(eps[1], k)!!
        assertEquals("Sam is 1 ahead", s.short)
        assertTrue(s.catchUp)
    }

    @Test fun nobodyHasSeenItMeansWatchTogether() {
        val k = KeepUp.of("bear", eps.map { if (it.id in setOf("e1-1", "e1-2")) it.copy(user = UserState(played = true)) else it }, listOf(sam))
        assertEquals("Watch with Sam", WatchPartners.standing(eps[2], k)!!.short)
    }

    @Test fun aheadOfThePartner() {
        val k = KeepUp.of("bear", eps.map { if (it.id != "e1-4") it.copy(user = UserState(played = true)) else it }, listOf(kim))
        val s = WatchPartners.standing(eps[0].copy(kind = ItemKind.SERIES, id = "bear"), k)!!
        assertEquals("Kim catching up", s.short)
        assertFalse(s.catchUp)
    }

    @Test fun inStep() {
        val k = KeepUp.of("bear", eps.map { if (it.id in setOf("e1-1", "e1-2")) it.copy(user = UserState(played = true)) else it }, listOf(sam))
        assertEquals("In step with Sam", WatchPartners.standing(eps[0].copy(kind = ItemKind.SERIES, id = "bear"), k)!!.short)
    }

    // ---- Home's "Watching together" row ----

    private fun keep(mine: Set<String>, vararg partners: PartnerProgress) =
        KeepUp.of("bear", eps.map { if (it.id in mine) it.copy(user = UserState(played = true)) else it }, partners.toList())

    @Test fun whoseTurnItIs() {
        assertEquals(Turn.CATCH_UP, TogetherRow.turn(keep(setOf("e1-1"), sam)))
        assertEquals(Turn.WAITING, TogetherRow.turn(keep(setOf("e1-1", "e1-2"), kim)))
        assertEquals(Turn.YOURS, TogetherRow.turn(keep(setOf("e1-1", "e1-2"), sam)))
    }

    private fun show(id: String, mine: Set<String>, vararg partners: PartnerProgress): SharedShow {
        val episodes = eps.map { e -> e.copy(id = e.id.replace("e", id), seriesId = id, user = UserState(played = e.id in mine)) }
        val p = partners.map { pp -> pp.copy(watched = pp.watched.map { it.replace("e", id) }.toSet()) }
        return SharedShow(MediaItem(id, id, ItemKind.SERIES), episodes, KeepUp.of(id, episodes, p))
    }

    @Test fun invitesThenYourTurnThenCatchingUpThenWaitingThenStart() {
        val invite = MediaItem("dune", "Dune", ItemKind.SERIES)
        val waiting = show("w", setOf("e1-1", "e1-2"), kim)
        val catchUp = show("c", setOf("e1-1"), sam)
        val yours = show("y", setOf("e1-1", "e1-2"), sam)
        val done = show("d", eps.map { it.id }.toSet(), PartnerProgress("g", "x", "X", eps.map { it.id }.toSet(), null, null))
        val items = TogetherRow.items(listOf(invite), listOf(waiting, catchUp, yours, done))
        // Each show's card is the user's next episode; the finished show has none.
        assertEquals(listOf("dune", "y1-3", "c1-2", "w1-3", TogetherRow.START_ID), items.map { it.id })
        // Nothing shared and nothing asked: no row at all.
        assertTrue(TogetherRow.items(emptyList(), listOf(done)).isEmpty())
    }

    @Test fun waitingWithNothingLeftShowsTheShow() {
        val all = eps.map { it.id }.toSet()
        val s = show("w", all, kim)
        assertEquals("w", TogetherRow.card(s)?.id)
        assertEquals(ItemKind.SERIES, TogetherRow.card(s)?.kind)
    }

    @Test fun cardChoices() {
        val group = WatchGroup("g2", "bear", "Bear", listOf(dev.nitrate.core.writing.UserRef("kim", "Kim")), 0)
        val info = PartnersInfo(listOf(group), emptyList(), emptyList())
        // The user has seen 1 and 2, Kim only 1: count episode 2 for Kim, or watch the next one now.
        val k = keep(setOf("e1-1", "e1-2"), kim)
        val episodes = eps.map { if (it.id in setOf("e1-1", "e1-2")) it.copy(user = UserState(played = true)) else it }
        val labels = TogetherRow.choices(eps[2], info, mapOf("bear" to k), episodes, canWatchNow = true, ownSpoilers = emptySet()).map { it.label }
        assertEquals(
            listOf(
                "Kim watched S1:E2 with me", "Watch it together now with Kim", "Invite someone to watch with Kim",
                "Hide spoilers by my progress only", "Open the show", "Leave the group with Kim",
            ),
            labels,
        )
        // Sam ahead by one: catch up first; without invites on the server, no "now".
        val samGroup = group.copy(id = "g1", others = listOf(dev.nitrate.core.writing.UserRef("sam", "Sam")))
        val c = TogetherRow.choices(eps[1], PartnersInfo(listOf(samGroup), emptyList(), emptyList()), mapOf("bear" to keep(setOf("e1-1"), sam)), eps, canWatchNow = false, ownSpoilers = setOf("bear"))
        assertEquals(TogetherChoice.CatchUp("Sam", listOf("e1-2")), c.first())
        assertEquals("Catch up to Sam (1 episode)", c.first().label)
        assertTrue(c.none { it is TogetherChoice.WatchNow })
        assertTrue(c.any { it is TogetherChoice.OwnSpoilers && !it.on })
    }

    @Test fun inviteCardsAnswerAndSayWhoAsked() {
        val r = IncomingRequest("rq", "jess", "Jessica", "dune", "Dune", null, listOf("Jessica"), 0)
        val info = PartnersInfo(emptyList(), listOf(r), emptyList())
        val card = MediaItem("dune", "Dune", ItemKind.SERIES)
        assertEquals(r, TogetherRow.inviteFor(card, info))
        assertEquals("Jessica invites you", TogetherRow.line(card, info, emptyMap())?.short)
        assertEquals(listOf("Accept", "Decline", "Open it"), TogetherRow.choices(card, info, emptyMap(), emptyList(), true, emptySet()).map { it.label })
        assertEquals(listOf(TogetherChoice.Start), TogetherRow.choices(TogetherRow.start, info, emptyMap(), emptyList(), true, emptySet()))
        assertNull(TogetherRow.inviteFor(MediaItem("e", "E", ItemKind.EPISODE, seriesId = "dune"), info))
    }

    // ---- Films, playlists and collections ----

    private val film = MediaItem("dune", "Dune", ItemKind.MOVIE)

    @Test fun aFilmIsOneThingToHaveSeen() {
        val samSaw = PartnerProgress("g", "sam", "Sam", setOf("dune"), "Dune", null)
        val k = KeepUp.of("dune", listOf(film), listOf(samSaw))
        assertEquals("title", k.noun)
        assertEquals("Sam has seen it", WatchPartners.standing(film, k)!!.short)
        assertEquals(Turn.CATCH_UP, TogetherRow.turn(k))
        val notYet = PartnerProgress("g", "sam", "Sam", emptySet(), null, null)
        assertEquals("Watch with Sam", WatchPartners.standing(film, KeepUp.of("dune", listOf(film), listOf(notYet)))!!.short)
        val seen = film.copy(user = UserState(played = true))
        assertEquals("Sam hasn't seen it", WatchPartners.standing(seen, KeepUp.of("dune", listOf(seen), listOf(notYet)))!!.short)
        // Everyone's seen it: no card, the group's done.
        val both = PartnerProgress("g", "sam", "Sam", setOf("dune"), "Dune", null)
        assertTrue(TogetherRow.items(emptyList(), listOf(SharedShow(seen, listOf(seen), KeepUp.of("dune", listOf(seen), listOf(both))))).isEmpty())
    }

    @Test fun aPlaylistKeepsPaceInItsOrder() {
        val items = listOf(
            MediaItem("m1", "First", ItemKind.MOVIE, user = UserState(played = true)),
            MediaItem("m2", "Second", ItemKind.MOVIE),
            MediaItem("m3", "Third", ItemKind.MOVIE),
        )
        val kim = PartnerProgress("g", "kim", "Kim", setOf("m1", "m2", "m3"), "Third", null)
        val k = KeepUp.of("list", items, listOf(kim))
        val standings = mapOf("list" to k)
        // The card is the user's next film; it belongs to the playlist's group.
        val card = TogetherRow.card(SharedShow(MediaItem("list", "Marathon", ItemKind.PLAYLIST), items, k))
        assertEquals("m2", card?.id)
        assertEquals("list", TogetherRow.titleOf(card!!, standings))
        assertEquals("Kim is 2 ahead", TogetherRow.line(card, null, standings)?.short)
        val group = WatchGroup("g", "list", "Marathon", listOf(dev.nitrate.core.writing.UserRef("kim", "Kim")), 0)
        val c = TogetherRow.choices(card, PartnersInfo(listOf(group), emptyList(), emptyList()), standings, items, canWatchNow = false, ownSpoilers = emptySet())
        assertEquals("Catch up to Kim (2 titles)", c.first().label)
        assertTrue(c.none { it is TogetherChoice.OwnSpoilers })
        assertTrue(c.any { it.label == "Open it" })
    }

    @Test fun findingWhatToWatchWith() {
        assertEquals(listOf("Show", "Film", "Playlist", "Collection"), TogetherRow.KINDS.map { TogetherRow.kindLabel(MediaItem("x", "x", it)) })
    }
}
