package dev.nitrate.core.partners

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.UserState
import dev.nitrate.core.plugins.GuardAction
import dev.nitrate.core.plugins.GuardDecision
import dev.nitrate.core.plugins.PluginHost
import dev.nitrate.core.plugins.PluginStorage
import dev.nitrate.core.plugins.TogetherSession
import dev.nitrate.core.writing.UserRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One person in two groups on one show, each group with its own record: at episode 3 with Tommy
 * (Together on) and at episode 7 with Suzy (Together off, weekly, episode 9 not out yet). On their
 * own (Jellyfin) they've seen 1 to 7.
 */
class GroupRecordTest {
    private fun ep(n: Int, played: Boolean) =
        MediaItem("e$n", "Episode $n", ItemKind.EPISODE, seriesId = "bear", seasonNumber = 1, indexNumber = n, user = UserState(played = played))

    private val eps = (1..10).map { ep(it, played = it <= 7) }
    private fun ids(range: IntRange) = range.map { "e$it" }.toSet()

    private val tommy = WatchGroup(
        "tommy", "bear", "The Bear", listOf(UserRef("t", "Tommy")), 0, GroupSchedule(together = true), togetherFromId = "e4", ownRecord = ids(1..3),
    )
    private val suzy = WatchGroup(
        "suzy", "bear", "The Bear", listOf(UserRef("s", "Suzy")), 0, GroupSchedule(GroupSchedule.WEEKLY), lockedFromId = "e9", opensDay = 4, ownRecord = ids(1..7),
    )
    private val info = PartnersInfo(listOf(tommy, suzy), emptyList(), emptyList())
    private val progress = listOf(
        PartnerProgress("tommy", "t", "Tommy", ids(1..3), "S1 E3", "e4"),
        PartnerProgress("suzy", "s", "Suzy", ids(1..7), "S1 E7", "e8"),
    )
    private val k = KeepUp.of("bear", eps, progress, info.groups)
    private val standings = mapOf("bear" to k)

    @Test fun keepingUpGoesByEachGroupsOwnPlace() {
        assertEquals(ids(1..3), k.mineIn("tommy"))
        assertEquals(ids(1..7), k.mineIn("suzy"))
        // Level with both: Tommy isn't 4 behind, he's where the user is with him.
        assertEquals("Level · Tommy level · Suzy level", WatchPartners.standing(eps[3], k)!!.short)
        assertEquals(Turn.YOURS, TogetherRow.turn(k))
        // Without the record (an older server) the user would be 4 ahead of Tommy.
        val old = KeepUp.of("bear", eps, progress)
        assertEquals("Ahead", WatchPartners.standing(eps[3], old, setOf("tommy"))!!.word)
    }

    @Test fun theRowHasACardPerGroupAndTitle() {
        val severance = (1..2).map { MediaItem("s$it", "Severance $it", ItemKind.EPISODE, seriesId = "sev", seasonNumber = 1, indexNumber = it) }
        val withSev = tommy.copy(titleList = tommy.titles + GroupTitle("sev", "Severance"))
        val groups = listOf(withSev, suzy)
        val kSev = KeepUp.of("sev", severance, listOf(PartnerProgress("tommy", "t", "Tommy", setOf("s1"), "S1 E1", "s2")), groups)
        val invite = MediaItem("dune", "Dune", ItemKind.MOVIE)
        val cards = TogetherRow.cards(listOf(invite), listOf(SharedShow(null, eps, KeepUp.of("bear", eps, progress, groups)), SharedShow(null, severance, kSev)))
        // The Bear: episode 4 with Tommy, episode 8 with Suzy; Severance (Tommy's group only): catching up to episode 1.
        assertEquals(listOf("tommy e4", "suzy e8", "tommy s1"), cards.cards.map { "${it.groupId} ${it.item.id}" })
        assertEquals(listOf(Turn.YOURS, Turn.YOURS, Turn.CATCH_UP), cards.cards.map { it.turn })
        assertEquals(listOf("bear", "bear", "sev"), cards.cards.map { it.titleId })
        // Each card carries its group, so playing it starts from that group's card.
        assertEquals(listOf("tommy", "suzy", "tommy"), cards.cards.map { it.item.watchGroupId })
        assertEquals(PlayOrigin.GroupCard("suzy"), PlayOrigin.of(cards.cards[1].item))
        assertEquals(PlayOrigin.Elsewhere, PlayOrigin.of(eps[3]))
        // The row: invites, the cards, then the start card.
        assertEquals(listOf("dune", "e4", "e8", "s1", TogetherRow.START_ID), cards.items.map { it.id })
        // The same episode next in two groups is two cards, one for each.
        val kim = suzy.copy(id = "kim", others = listOf(UserRef("k", "Kim")), ownRecord = ids(1..3), lockedFromId = null)
        val twice = TogetherRow.cards(emptyList(), listOf(SharedShow(null, eps, KeepUp.of("bear", eps, progress, listOf(tommy, kim)), listOf("tommy", "kim"))))
        assertEquals(listOf("tommy e4", "kim e4"), twice.cards.map { "${it.groupId} ${it.item.id}" })
        // Its line and choices are that group's (from Tommy's own card, his group's next asks nothing).
        assertEquals("Level · Tommy level", TogetherRow.line(cards.cards[0].item, info, standings)!!.short)
        assertEquals("Level · Suzy level", TogetherRow.line(cards.cards[1].item, info, standings)!!.short)
        val choices = TogetherRow.choices(cards.cards[1].item, info, standings, eps, canWatchNow = true, ownSpoilers = emptySet())
        assertTrue(choices.filterIsInstance<TogetherChoice.Leave>().all { it.group.id == "suzy" })
        assertEquals(listOf("Start a Group Watch Session with Suzy"), choices.filterIsInstance<TogetherChoice.WatchNow>().map { it.label })
        // Continue Watching is the user's own again: only this row's cards answer for watching together.
        assertEquals(setOf(TogetherRow.KEY), TogetherRow.ROW_KEYS)
    }

    @Test fun whatAPlaybackCountsForDependsOnWhereItStarted() {
        assertEquals(listOf("tommy"), WatchPartners.nextIn(eps[3], info, standings).map { it.id })
        assertEquals(listOf("suzy"), WatchPartners.nextIn(eps[7], info, standings).map { it.id })
        assertTrue(WatchPartners.nextIn(eps[5], info, standings).isEmpty())
        // Started elsewhere: the one group it's next in, shown so it can be changed.
        val elsewhere = WatchPartners.countingFor(PlayOrigin.Elsewhere, eps[3], info, standings)
        assertEquals(listOf("tommy"), elsewhere.groups.map { it.id })
        assertEquals("Counting for Tommy · Change", elsewhere.label)
        assertFalse(elsewhere.asksAll)
        // Next in no group: it counts for none.
        assertTrue(WatchPartners.countingFor(PlayOrigin.Elsewhere, eps[5], info, standings).groups.isEmpty())
        assertNull(WatchPartners.countingFor(PlayOrigin.Elsewhere, eps[5], info, standings).label)
        // From Suzy's card, episode 4 counts for Suzy's group, and it's also Tommy's next: the app asks "Just this group / All groups where it's next".
        val fromCard = WatchPartners.countingFor(PlayOrigin.GroupCard("suzy"), eps[3], info, standings)
        assertEquals(listOf("suzy"), fromCard.groups.map { it.id })
        assertEquals(listOf("tommy"), fromCard.alsoNext.map { it.id })
        assertTrue(fromCard.asksAll)
        assertEquals(listOf("suzy"), fromCard.recordFor())
        assertEquals(listOf("suzy", "tommy"), fromCard.recordFor(all = true))
        assertNull(fromCard.label)
        assertEquals(listOf("tommy"), WatchPartners.alsoNextIn(eps[3], "suzy", info, standings).map { it.id })
        // From Tommy's card nothing else has it next.
        assertFalse(WatchPartners.countingFor(PlayOrigin.GroupCard("tommy"), eps[3], info, standings).asksAll)
        // Watch for myself: no group.
        assertTrue(WatchPartners.countingFor(PlayOrigin.ForMyself, eps[3], info, standings).groups.isEmpty())
        // Next in two groups and started elsewhere: no group (nothing to guess).
        val kim = suzy.copy(id = "kim", others = listOf(UserRef("k", "Kim")), ownRecord = ids(1..3))
        val two = PartnersInfo(listOf(tommy, kim), emptyList(), emptyList())
        val st2 = mapOf("bear" to KeepUp.of("bear", eps, progress, two.groups))
        assertEquals(listOf("tommy", "kim"), WatchPartners.nextIn(eps[3], two, st2).map { it.id })
        assertTrue(WatchPartners.countingFor(PlayOrigin.Elsewhere, eps[3], two, st2).groups.isEmpty())
    }

    @Test fun theContextIsTheGroupThePartIsNextFor() {
        fun ctx(n: Int, live: List<String> = emptyList(), with: WatchingWith? = null) = WatchPartners.contextFor(eps[n - 1], info, standings, live, with)
        assertEquals(listOf("tommy"), ctx(4).groups.map { it.id })
        assertEquals(PlayContext.Reason.NEXT, ctx(4).reason)
        assertEquals("With Tommy", ctx(4).label)
        assertEquals(listOf("suzy"), ctx(8).groups.map { it.id })
        // Next in no group: just the user.
        assertTrue(ctx(6).justMe)
        assertEquals(PlayContext.Reason.NONE, ctx(10).reason)
        // In a live session the groups whose members are there.
        assertEquals(listOf("tommy"), ctx(8, live = listOf("me", "TOMMY")).groups.map { it.id })
        assertEquals(PlayContext.Reason.LIVE, ctx(8, live = listOf("me", "tommy")).reason)
        // The user's own choice wins.
        assertEquals("Just me", ctx(4, with = WatchingWith.JustMe).label)
        assertEquals(listOf("suzy"), ctx(4, with = WatchingWith.Group("suzy")).groups.map { it.id })
    }

    @Test fun cardsShowTheirGroupsQuestion() {
        // Episode 4 in the library: Tommy's group (Together on) hasn't watched it.
        assertEquals(PartLock.WATCH_TOGETHER, PartLock.of(eps[3], info, standings)!!.word)
        // On Suzy's card it's simply her group's next.
        assertNull(PartLock.of(eps[3].copy(watchGroupId = "suzy"), info, standings))
        // Episode 9 isn't out yet in Suzy's group (Together off): on her card, and elsewhere too (not out yet comes before Tommy's question).
        assertEquals("Opens Thursday 7pm", PartLock.of(eps[8].copy(watchGroupId = "suzy"), info, standings)!!.word)
        assertEquals("Opens Thursday 7pm", PartLock.of(eps[8], info, standings)!!.word)
        // On Tommy's card only his group's schedule counts: nothing of Suzy's.
        assertNull(PartLock.of(eps[8].copy(watchGroupId = "tommy"), info, standings))
        // Spoilers go by the Together groups' progress only.
        assertEquals(listOf("tommy"), WatchPartners.spoilerProgress(progress, info).map { it.groupId })
        assertEquals(progress, WatchPartners.spoilerProgress(progress, null))
    }

    private val noStore = object : PluginStorage {
        override val enabledOverrides = kotlinx.coroutines.flow.flowOf(emptyMap<String, Boolean>())
        override suspend fun setEnabled(pluginId: String, enabled: Boolean) {}
        override fun raw(pluginId: String) = kotlinx.coroutines.flow.flowOf<String?>(null)
        override suspend fun saveRaw(pluginId: String, value: String) {}
    }

    private fun server(partners: PartnersInfo): dev.nitrate.core.MediaServer =
        java.lang.reflect.Proxy.newProxyInstance(dev.nitrate.core.MediaServer::class.java.classLoader, arrayOf(dev.nitrate.core.MediaServer::class.java)) { _, m, _ ->
            when (m.name) {
                "companion" -> dev.nitrate.core.companion.CompanionInfo("1", setOf("partners", "grouprecord", "groupwatch"))
                "partners" -> partners
                "titleItems" -> eps
                "getUserId" -> "me"
                else -> throw UnsupportedOperationException(m.name)
            }
        } as dev.nitrate.core.MediaServer

    private class Session(var names: List<String>) : TogetherSession {
        val started = mutableListOf<String>()
        override fun participants() = names
        override suspend fun start(item: MediaItem, groupId: String, userIds: List<String>, names: String) { started += "${item.id} $groupId" }
    }

    @Test fun togetherAsksFirstAndTheScheduleSaysWhenItOpens() = runBlocking {
        val plugin = WatchPartnersPlugin()
        val session = Session(emptyList())
        val host = PluginHost(server(info), noStore, session)
        // Episode 8 from the library: Tommy's group (Together on) hasn't watched it, so it asks first.
        val ask = plugin.beforePlay(eps[7], host, PlayOrigin.Elsewhere) as GuardDecision.Ask
        assertEquals("Tommy hasn't watched this yet", ask.title)
        assertEquals(listOf("Start a Group Watch Session", "We're in the same room", "Watch for myself", "Not now"), ask.choices.map { it.label })
        assertTrue(ask.message, ask.message.contains("Tommy"))
        // "Start a Group Watch Session" starts the live session for Tommy's group, and the playback counts there.
        (ask.choices[0].action as GuardAction.RunThenPlay).run()
        assertEquals(listOf("e8 tommy"), session.started)
        assertEquals(PlayOrigin.GroupCard("tommy"), plugin.originOf(host, eps[7]))
        // The answer is used once.
        assertEquals(PlayOrigin.Elsewhere, plugin.originOf(host, eps[7]))
        // "Watch for myself" plays and counts for no group.
        (ask.choices[2].action as GuardAction.RunThenPlay).run()
        assertEquals(PlayOrigin.ForMyself, plugin.originOf(host, eps[7], PlayOrigin.Elsewhere))
        // "We're in the same room": the whole group's here, and it counts from the group's card.
        (ask.choices[1].action as GuardAction.RunThenPlay).run()
        assertEquals(SameRoom("tommy", listOf("t")), plugin.roomOn(host, "bear"))
        assertEquals(PlayOrigin.GroupCard("tommy"), plugin.takeOrigin(host, eps[7]))
        assertNull(plugin.takeOrigin(host, eps[7]))
        plugin.setRoom(host, "bear", null)
        // From Suzy's card, episode 8 is her group's next: no question (Tommy's switch is about plays started elsewhere).
        assertEquals(GuardDecision.Allow, plugin.beforePlay(eps[7], host, PlayOrigin.GroupCard("suzy")))
        // Episode 9 from Suzy's card isn't out yet: it says when, and offers it for oneself.
        val notYet = plugin.beforePlay(eps[8], host, PlayOrigin.GroupCard("suzy")) as GuardDecision.Ask
        assertEquals("Opens Thursday 7pm", notYet.title)
        assertEquals(listOf("Watch for myself", "Not now"), notYet.choices.map { it.label })
        // What Tommy's group has watched (a rewatch, catching up) is free; so is anything once played for oneself.
        assertEquals(GuardDecision.Allow, plugin.beforePlay(eps[1], host, PlayOrigin.Elsewhere))
        assertEquals(GuardDecision.Allow, plugin.beforePlay(eps[7], host, PlayOrigin.ForMyself))
        // In a live session with Tommy, it's watching together already.
        session.names = listOf("me", "Tommy")
        assertEquals(GuardDecision.Allow, plugin.beforePlay(eps[7], host, PlayOrigin.Elsewhere))
        // With Together off everywhere, only what a schedule hasn't let out asks when started elsewhere.
        session.names = emptyList()
        val off = PluginHost(server(PartnersInfo(listOf(tommy.copy(schedule = null), suzy), emptyList(), emptyList())), noStore, session)
        assertEquals(GuardDecision.Allow, plugin.beforePlay(eps[7], off, PlayOrigin.Elsewhere))
        assertEquals("Opens Thursday 7pm", (plugin.beforePlay(eps[8], off, null) as GuardDecision.Ask).title)
        // Without a way to start a live session here: only for myself, or not now.
        val bare = plugin.beforePlay(eps[7], PluginHost(server(info), noStore), PlayOrigin.Elsewhere) as GuardDecision.Ask
        assertEquals(listOf("We're in the same room", "Watch for myself", "Not now"), bare.choices.map { it.label })
    }
}
