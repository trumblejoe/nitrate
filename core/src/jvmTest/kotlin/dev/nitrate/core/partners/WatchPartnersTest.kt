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
        assertEquals("Behind · Sam 1 ahead", s.short)
        assertEquals("Behind", s.word)
        assertTrue(s.catchUp)
    }

    @Test fun nobodyHasSeenItMeansWatchTogether() {
        val k = KeepUp.of("bear", eps.map { if (it.id in setOf("e1-1", "e1-2")) it.copy(user = UserState(played = true)) else it }, listOf(sam))
        assertEquals("Level · Sam level", WatchPartners.standing(eps[2], k)!!.short)
    }

    @Test fun aheadOfThePartner() {
        val k = KeepUp.of("bear", eps.map { if (it.id != "e1-4") it.copy(user = UserState(played = true)) else it }, listOf(kim))
        val s = WatchPartners.standing(eps[0].copy(kind = ItemKind.SERIES, id = "bear"), k)!!
        assertEquals("Ahead · Kim 2 behind", s.short)
        assertEquals("Kim is on S1 E1, 2 episodes behind you", s.long)
        assertFalse(s.catchUp)
    }

    @Test fun inStep() {
        val k = KeepUp.of("bear", eps.map { if (it.id in setOf("e1-1", "e1-2")) it.copy(user = UserState(played = true)) else it }, listOf(sam))
        assertEquals("Level · Sam level", WatchPartners.standing(eps[0].copy(kind = ItemKind.SERIES, id = "bear"), k)!!.short)
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

    /** Continue Watching takes what's watched together: invites, part way, your move, Next Up, waiting; each title once. */
    @Test fun continueWatchingTakesTheSharedCards() {
        val invite = MediaItem("dune", "Dune", ItemKind.SERIES)
        val waiting = show("w", setOf("e1-1", "e1-2"), kim)
        val yours = show("y", setOf("e1-1", "e1-2"), sam)
        val catchUp = show("c", setOf("e1-1"), sam)
        val cards = TogetherRow.cards(listOf(invite), listOf(waiting, yours, catchUp))
        val partWay = MediaItem("film", "A film", ItemKind.MOVIE)
        // Part way through the shared show "c": its card is the resume one, not added twice.
        val resumingShared = MediaItem("c1-2", "Episode 2", ItemKind.EPISODE, seriesId = "c")
        val otherShow = MediaItem("o1-4", "Episode 4", ItemKind.EPISODE, seriesId = "o")
        val nextUpOfShared = MediaItem("y1-3", "Episode 3", ItemKind.EPISODE, seriesId = "y")
        val merged = dev.nitrate.core.rows.ContinueAndNext.withTogether(listOf(partWay, resumingShared), listOf(nextUpOfShared, otherShow), cards)
        assertEquals(listOf("dune", "film", "c1-2", "y1-3", "o1-4", "w1-3"), merged.map { it.id })
        // Two rows: Continue Watching has them, Next Up leaves the shared titles out.
        assertEquals(listOf("dune", "film", "c1-2", "y1-3", "w1-3"), dev.nitrate.core.rows.ContinueAndNext.continueWithTogether(listOf(partWay, resumingShared), cards).map { it.id })
        assertEquals(listOf("o1-4"), dev.nitrate.core.rows.ContinueAndNext.nextUpWithout(listOf(nextUpOfShared, otherShow), cards).map { it.id })
        // Nothing shared: Continue Watching as it always was.
        assertEquals(listOf("film"), dev.nitrate.core.rows.ContinueAndNext.withTogether(listOf(partWay), emptyList(), TogetherCards()).map { it.id })
    }

    @Test fun onlySharedCardsAnswerForWatchingTogether() {
        val info = PartnersInfo(listOf(WatchGroup("g1", "bear", "Bear", listOf(dev.nitrate.core.writing.UserRef("sam", "Sam")), 0L)), emptyList(), emptyList())
        val standings = mapOf("bear" to keep(setOf("e1-1"), sam))
        assertTrue(TogetherRow.isShared(eps[1], info, standings))
        assertFalse(TogetherRow.isShared(MediaItem("x", "Other", ItemKind.EPISODE, seriesId = "other"), info, standings))
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
                "Change the schedule (Binge)", "Hide spoilers by my progress only", "Open the show", "Watch something with someone", "Leave the group with Kim",
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
        assertEquals("Invited · by Jessica", TogetherRow.line(card, info, emptyMap())?.short)
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
        assertEquals("Not seen · Sam seen", WatchPartners.standing(film, k)!!.short)
        assertEquals(Turn.CATCH_UP, TogetherRow.turn(k))
        val notYet = PartnerProgress("g", "sam", "Sam", emptySet(), null, null)
        assertEquals("Not yet · Sam not yet", WatchPartners.standing(film, KeepUp.of("dune", listOf(film), listOf(notYet)))!!.short)
        val seen = film.copy(user = UserState(played = true))
        assertEquals("Seen · Sam not yet", WatchPartners.standing(seen, KeepUp.of("dune", listOf(seen), listOf(notYet)))!!.short)
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
        assertEquals("Behind · Kim 2 ahead", TogetherRow.line(card, null, standings)?.short)
        val group = WatchGroup("g", "list", "Marathon", listOf(dev.nitrate.core.writing.UserRef("kim", "Kim")), 0)
        val c = TogetherRow.choices(card, PartnersInfo(listOf(group), emptyList(), emptyList()), standings, items, canWatchNow = false, ownSpoilers = emptySet())
        assertEquals("Catch up to Kim (2 titles)", c.first().label)
        assertTrue(c.none { it is TogetherChoice.OwnSpoilers })
        assertTrue(c.any { it.label == "Open it" })
    }

    @Test fun findingWhatToWatchWith() {
        assertEquals(listOf("Show", "Film", "Playlist", "Collection"), TogetherRow.KINDS.map { TogetherRow.kindLabel(MediaItem("x", "x", it)) })
    }

    @Test fun betweenAndManyPartners() {
        fun p(name: String, upTo: Int) = PartnerProgress("g", name, name, (1..upTo).map { "e1-$it" }.toSet(), if (upTo > 0) "S1 E$upTo" else null, null)
        val k = KeepUp.of("bear", eps.map { if (it.id in setOf("e1-1", "e1-2")) it.copy(user = UserState(played = true)) else it },
            listOf(p("Kim", 0), p("Kevin", 4), p("Jane", 2), p("Lee", 1)))
        val s = WatchPartners.standing(eps[2], k)!!
        assertEquals("Between", s.word)
        // Furthest ahead first, three shown, the rest counted.
        assertEquals("Kevin 2 ahead · Jane level · Lee 1 behind · and 1 more", s.partners)
        assertTrue(s.catchUp)
    }

    // ---- Schedules ----

    @Test fun scheduleLabels() {
        assertEquals("Binge", GroupSchedule().label)
        assertEquals("Thursdays 7pm", GroupSchedule(GroupSchedule.WEEKLY, day = 4, hour = 19).label)
        assertEquals("Every 2 days, 8pm, ask first", GroupSchedule(GroupSchedule.DAYS2, hour = 20, softLock = true).label)
        assertEquals("12pm", GroupSchedule.clockOf(12, 0))
        assertEquals("7:30pm", GroupSchedule.clockOf(19, 30))
    }

    private val weekly = GroupSchedule(GroupSchedule.WEEKLY, day = 4, hour = 19)

    @Test fun aLockedNextEpisodeSaysWhenItOpens() {
        // The user's seen 1 and 2 like Sam; 3 is locked until Thursday 7pm.
        val k = keep(setOf("e1-1", "e1-2"), sam)
        val group = WatchGroup("g1", "bear", "Bear", listOf(dev.nitrate.core.writing.UserRef("sam", "Sam")), 0, weekly, lockedFromId = "e1-3", opensAtMs = 1, opensDay = 4)
        val info = PartnersInfo(listOf(group), emptyList(), emptyList())
        val standings = mapOf("bear" to k)
        assertTrue(group.locks("e1-3", k.order))
        assertTrue(group.locks("e1-4", k.order))
        assertFalse(group.locks("e1-2", k.order))
        val line = TogetherRow.line(eps[2], info, standings)!!
        assertEquals("Opens Thursday 7pm", line.word)
        assertEquals("Sam level", line.partners)
        // Hard lock: no "watch it together now"; the schedule can be changed.
        val c = TogetherRow.choices(eps[2], info, standings, eps, canWatchNow = true, ownSpoilers = emptySet())
        assertTrue(c.none { it is TogetherChoice.WatchNow })
        assertTrue(c.any { it is TogetherChoice.ChangeSchedule && it.label == "Change the schedule (Thursdays 7pm)" })
    }

    @Test fun aChangeWaitingForYourVoteComesFirst() {
        val change = ScheduleChange("c1", "Sam", GroupSchedule(), listOf("Sam"), emptyList(), voted = false, atMs = 0)
        val group = WatchGroup("g1", "bear", "Bear", listOf(dev.nitrate.core.writing.UserRef("sam", "Sam")), 0, weekly, pending = change)
        val info = PartnersInfo(listOf(group), emptyList(), emptyList())
        val c = TogetherRow.choices(eps[2], info, mapOf("bear" to keep(setOf("e1-1", "e1-2"), sam)), eps, canWatchNow = true, ownSpoilers = emptySet())
        assertEquals(listOf("Agree to Sam's change: Binge", "Keep the schedule as it is"), c.take(2).map { it.label })
        // While one is waiting, nobody asks for another.
        assertTrue(c.none { it is TogetherChoice.ChangeSchedule })
    }

    @Test fun playingSomethingLockedIsStoppedOrAsked() = kotlinx.coroutines.runBlocking {
        fun server(soft: Boolean): dev.nitrate.core.MediaServer =
            java.lang.reflect.Proxy.newProxyInstance(dev.nitrate.core.MediaServer::class.java.classLoader, arrayOf(dev.nitrate.core.MediaServer::class.java)) { _, m, _ ->
                when (m.name) {
                    "companion" -> dev.nitrate.core.companion.CompanionInfo("1", setOf("partners"))
                    "partners" -> PartnersInfo(listOf(WatchGroup("g1", "bear", "Bear", listOf(dev.nitrate.core.writing.UserRef("sam", "Sam")), 0, weekly.copy(softLock = soft), lockedFromId = "e1-3", opensDay = 4)), emptyList(), emptyList())
                    "titleItems" -> eps
                    else -> throw UnsupportedOperationException(m.name)
                }
            } as dev.nitrate.core.MediaServer
        val store = object : dev.nitrate.core.plugins.PluginStorage {
            override val enabledOverrides = kotlinx.coroutines.flow.flowOf(emptyMap<String, Boolean>())
            override suspend fun setEnabled(pluginId: String, enabled: Boolean) {}
            override fun raw(pluginId: String) = kotlinx.coroutines.flow.flowOf<String?>(null)
            override suspend fun saveRaw(pluginId: String, value: String) {}
        }
        val plugin = WatchPartnersPlugin()
        val hard = plugin.beforePlay(eps[2], dev.nitrate.core.plugins.PluginHost(server(false), store)) as dev.nitrate.core.plugins.GuardDecision.Ask
        assertEquals("Opens Thursday 7pm", hard.title)
        assertEquals(listOf("OK"), hard.choices.map { it.label })
        val soft = plugin.beforePlay(eps[2], dev.nitrate.core.plugins.PluginHost(server(true), store)) as dev.nitrate.core.plugins.GuardDecision.Ask
        assertEquals(listOf("Watch anyway", "Not now"), soft.choices.map { it.label })
        // What's open plays.
        assertEquals(dev.nitrate.core.plugins.GuardDecision.Allow, plugin.beforePlay(eps[1], dev.nitrate.core.plugins.PluginHost(server(false), store)))
    }

    // ---- Together only ----

    @Test fun howWeWatchIsPartOfTheLabel() {
        assertEquals("Binge, together only", GroupSchedule(together = true).label)
        assertEquals("Thursdays 7pm, together only, ask first", weekly.copy(together = true, softLock = true).label)
        assertTrue(GroupSchedule(together = true).active)
        assertFalse(GroupSchedule().active)
        assertEquals("Together only", GroupSchedule(together = true).how)
        assertEquals("Separately, at the group's pace", GroupSchedule().how)
    }

    private val samRef = dev.nitrate.core.writing.UserRef("sam", "Sam")

    /** Sam and the user have both seen 1 and 2; nobody's seen 3 (together only from there). */
    private fun togetherGroup(soft: Boolean = false, schedule: GroupSchedule = GroupSchedule(together = true, softLock = soft)) =
        WatchGroup("g1", "bear", "Bear", listOf(samRef), 0, schedule, togetherFromId = "e1-3")

    @Test fun whatNobodyHasSeenIsForWatchingTogether() {
        val k = keep(setOf("e1-1", "e1-2"), sam)
        val g = togetherGroup()
        assertFalse(g.needsTogether("e1-2", k.order))
        assertTrue(g.needsTogether("e1-3", k.order))
        assertTrue(g.needsTogether("e1-4", k.order))
        // Separately: nothing needs anyone.
        assertFalse(g.copy(schedule = GroupSchedule(GroupSchedule.WEEKLY)).needsTogether("e1-3", k.order))
        val info = PartnersInfo(listOf(g), emptyList(), emptyList())
        val standings = mapOf("bear" to k)
        val lock = PartLock.of(eps[2], info, standings)!!
        assertTrue(lock.together)
        assertEquals("Watch together", lock.word)
        assertNull(PartLock.of(eps[1], info, standings))
        val line = TogetherRow.line(eps[2], info, standings)!!
        assertEquals("Watch together", line.word)
        assertEquals("Watch together · Sam level", line.short)
        // Watching it together now is the way in, so it's offered.
        assertTrue(TogetherRow.choices(eps[2], info, standings, eps, canWatchNow = true, ownSpoilers = emptySet()).any { it is TogetherChoice.WatchNow })
        // Not out yet wins over together only.
        val both = g.copy(schedule = weekly.copy(together = true), lockedFromId = "e1-3", opensDay = 4)
        assertEquals("Opens Thursday 7pm", PartLock.of(eps[2], PartnersInfo(listOf(both), emptyList(), emptyList()), standings)!!.word)
    }

    private val noStore = object : dev.nitrate.core.plugins.PluginStorage {
        override val enabledOverrides = kotlinx.coroutines.flow.flowOf(emptyMap<String, Boolean>())
        override suspend fun setEnabled(pluginId: String, enabled: Boolean) {}
        override fun raw(pluginId: String) = kotlinx.coroutines.flow.flowOf<String?>(null)
        override suspend fun saveRaw(pluginId: String, value: String) {}
    }

    private fun serverWith(group: WatchGroup): dev.nitrate.core.MediaServer =
        java.lang.reflect.Proxy.newProxyInstance(dev.nitrate.core.MediaServer::class.java.classLoader, arrayOf(dev.nitrate.core.MediaServer::class.java)) { _, m, _ ->
            when (m.name) {
                "companion" -> dev.nitrate.core.companion.CompanionInfo("1", setOf("partners"))
                "partners" -> PartnersInfo(listOf(group), emptyList(), emptyList())
                "titleItems" -> eps
                else -> throw UnsupportedOperationException(m.name)
            }
        } as dev.nitrate.core.MediaServer

    private class Session(var names: List<String>) : dev.nitrate.core.plugins.TogetherSession {
        val started = mutableListOf<String>()
        override fun participants() = names
        override suspend fun start(item: MediaItem, groupId: String, userIds: List<String>, names: String) { started += "${item.id} $groupId $userIds $names" }
    }

    @Test fun togetherOnlyPlaysInAWatchTogetherSessionWithTheGroup() = kotlinx.coroutines.runBlocking {
        val plugin = WatchPartnersPlugin()
        val session = Session(emptyList())
        fun host(g: WatchGroup) = dev.nitrate.core.plugins.PluginHost(serverWith(g), noStore, session)
        // Alone: refused, with watching it together now offered first.
        val hard = plugin.beforePlay(eps[2], host(togetherGroup())) as dev.nitrate.core.plugins.GuardDecision.Ask
        assertEquals("Watch together", hard.title)
        assertEquals(listOf("Watch it together now", "Not now"), hard.choices.map { it.label })
        assertTrue(hard.message, hard.message.contains("Sam"))
        // Choosing it starts the session with the group's members invited, then plays.
        val run = hard.choices.first().action as dev.nitrate.core.plugins.GuardAction.RunThenPlay
        run.run()
        assertEquals(listOf("e1-3 g1 [sam] Sam"), session.started)
        // Ask first: watching anyway is allowed too.
        val soft = plugin.beforePlay(eps[2], host(togetherGroup(soft = true))) as dev.nitrate.core.plugins.GuardDecision.Ask
        assertEquals(listOf("Watch it together now", "Watch anyway", "Not now"), soft.choices.map { it.label })
        // Catching up to the furthest member, and rewatching, are free.
        assertEquals(dev.nitrate.core.plugins.GuardDecision.Allow, plugin.beforePlay(eps[1], host(togetherGroup())))
        // In a watch-together session with someone else: still refused.
        session.names = listOf("me", "Kim")
        assertTrue(plugin.beforePlay(eps[2], host(togetherGroup())) is dev.nitrate.core.plugins.GuardDecision.Ask)
        // With Sam in it: plays.
        session.names = listOf("me", "sam")
        assertEquals(dev.nitrate.core.plugins.GuardDecision.Allow, plugin.beforePlay(eps[2], host(togetherGroup())))
        // Not out yet stays locked even together.
        val notYet = togetherGroup(schedule = weekly.copy(together = true)).copy(lockedFromId = "e1-3", opensDay = 4)
        assertEquals("Opens Thursday 7pm", (plugin.beforePlay(eps[2], host(notYet)) as dev.nitrate.core.plugins.GuardDecision.Ask).title)
        // Without a session to start from (no SyncPlay here): only Not now.
        session.names = emptyList()
        val bare = plugin.beforePlay(eps[2], dev.nitrate.core.plugins.PluginHost(serverWith(togetherGroup()), noStore)) as dev.nitrate.core.plugins.GuardDecision.Ask
        assertEquals(listOf("Not now"), bare.choices.map { it.label })
    }
}
