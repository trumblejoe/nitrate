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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One person in two groups on one show at different points: at episode 3 with Tommy (together
 * only, the group's own record) and at episode 7 with Suzy (separately, Jellyfin's watched state,
 * weekly with episode 9 locked).
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
        "suzy", "bear", "The Bear", listOf(UserRef("s", "Suzy")), 0, GroupSchedule(GroupSchedule.WEEKLY), lockedFromId = "e9", opensDay = 4,
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
        assertEquals(ids(1..7), k.mine)
        // Level with both: Tommy isn't 4 behind, he's where the user is with him.
        assertEquals("Level · Tommy level · Suzy level", WatchPartners.standing(eps[3], k)!!.short)
        assertEquals(Turn.YOURS, TogetherRow.turn(k))
        // A card for each place: episode 4 with Tommy, episode 8 with Suzy.
        val cards = TogetherRow.cards(emptyList(), listOf(SharedShow(null, eps, k)))
        assertEquals(listOf("e8", "e4"), cards.yours.map { it.id })
        // Without the record (an older server) the user would be 4 ahead of Tommy.
        val old = KeepUp.of("bear", eps, progress)
        assertEquals("Ahead", WatchPartners.standing(eps[3], old, setOf("tommy"))!!.word)
    }

    @Test fun theContextIsTheGroupThePartIsNextFor() {
        fun ctx(n: Int, live: List<String> = emptyList(), last: String? = null, with: WatchingWith? = null) =
            WatchPartners.contextFor(eps[n - 1], info, standings, live, last, with)
        assertEquals(listOf("tommy"), ctx(4).groups.map { it.id })
        assertEquals(PlayContext.Reason.NEXT, ctx(4).reason)
        assertEquals("With Tommy", ctx(4).label)
        assertEquals(listOf("suzy"), ctx(8).groups.map { it.id })
        // Ahead of both: the furthest-along group it's ahead of.
        assertEquals(listOf("suzy"), ctx(10).groups.map { it.id })
        assertEquals(PlayContext.Reason.NEAREST, ctx(10).reason)
        // Between the two places: Tommy's group (it's ahead of where they are; behind Suzy's).
        assertEquals(listOf("tommy"), ctx(6).groups.map { it.id })
        // A rewatch: the group used last, else the nearest.
        assertEquals(listOf("tommy"), ctx(2).groups.map { it.id })
        assertEquals(listOf("suzy"), ctx(2, last = "suzy").groups.map { it.id })
        // In a live session the groups whose members are there.
        assertEquals(listOf("tommy"), ctx(8, live = listOf("me", "TOMMY")).groups.map { it.id })
        assertEquals(PlayContext.Reason.LIVE, ctx(8, live = listOf("me", "tommy")).reason)
        // A session with nobody from the groups: as if alone.
        assertEquals(listOf("suzy"), ctx(8, live = listOf("me", "Kim")).groups.map { it.id })
        // The user's own choice wins.
        assertTrue(ctx(4, with = WatchingWith.JustMe).justMe)
        assertEquals("Just me", ctx(4, with = WatchingWith.JustMe).label)
        assertEquals(listOf("suzy"), ctx(4, with = WatchingWith.Group("suzy")).groups.map { it.id })
        // A tie between two groups going by Jellyfin's state: the one used last.
        val kim = suzy.copy(id = "kim", others = listOf(UserRef("k", "Kim")), schedule = null, lockedFromId = null)
        val both = PartnersInfo(listOf(suzy, kim), emptyList(), emptyList())
        assertEquals(listOf("suzy"), WatchPartners.contextFor(eps[7], both, standings).groups.map { it.id })
        val tie = WatchPartners.contextFor(eps[7], both, standings, lastUsed = "kim")
        assertEquals(listOf("kim"), tie.groups.map { it.id })
        assertEquals(PlayContext.Reason.LAST_USED, tie.reason)
    }

    @Test fun cardsShowOnlyTheContextsLock() {
        // Episode 8 is the user's next with Suzy: Tommy's together-only rule doesn't lock it.
        assertNull(PartLock.of(eps[7], info, standings))
        assertEquals("Level · Suzy level", TogetherRow.line(eps[7], info, standings)!!.short)
        // Episode 4 is next with Tommy: for watching together.
        assertEquals(PartLock.WATCH_TOGETHER, PartLock.of(eps[3], info, standings)!!.word)
        assertEquals("Watch together · Tommy level", TogetherRow.line(eps[3], info, standings)!!.short)
        // Episode 9 is past Suzy's schedule.
        assertEquals("Opens Thursday 7pm", PartLock.of(eps[8], info, standings)!!.word)
    }

    private val noStore = object : PluginStorage {
        override val enabledOverrides = kotlinx.coroutines.flow.flowOf(emptyMap<String, Boolean>())
        override suspend fun setEnabled(pluginId: String, enabled: Boolean) {}
        override fun raw(pluginId: String) = kotlinx.coroutines.flow.flowOf<String?>(null)
        override suspend fun saveRaw(pluginId: String, value: String) {}
    }

    private val server: dev.nitrate.core.MediaServer =
        java.lang.reflect.Proxy.newProxyInstance(dev.nitrate.core.MediaServer::class.java.classLoader, arrayOf(dev.nitrate.core.MediaServer::class.java)) { _, m, _ ->
            when (m.name) {
                "companion" -> dev.nitrate.core.companion.CompanionInfo("1", setOf("partners", "grouprecord"))
                "partners" -> info
                "titleItems" -> eps
                "getUserId" -> "me"
                else -> throw UnsupportedOperationException(m.name)
            }
        } as dev.nitrate.core.MediaServer

    private class Session(var names: List<String>) : TogetherSession {
        override fun participants() = names
        override suspend fun start(item: MediaItem, groupId: String, userIds: List<String>, names: String) {}
    }

    @Test fun onlyTheContextsLockBlocksAnotherGroupsWarns() = runBlocking {
        val plugin = WatchPartnersPlugin()
        val session = Session(emptyList())
        val host = PluginHost(server, noStore, session)
        // Episode 8 alone counts with Suzy: Tommy's together-only rule doesn't stop it (it doesn't count for Tommy's group).
        assertEquals(GuardDecision.Allow, plugin.beforePlay(eps[7], host))
        assertEquals("suzy", plugin.lastUsedOn(host, "bear"))
        // Episode 4 alone counts with Tommy: for watching together.
        assertEquals(PartLock.WATCH_TOGETHER, (plugin.beforePlay(eps[3], host) as GuardDecision.Ask).title)
        // Episode 9 alone is past Suzy's schedule: blocked.
        assertEquals("Opens Thursday 7pm", (plugin.beforePlay(eps[8], host) as GuardDecision.Ask).title)
        // With Tommy, live: episode 4 plays; episode 9 counts with Tommy, and Suzy's lock only warns.
        session.names = listOf("me", "Tommy")
        assertEquals(GuardDecision.Allow, plugin.beforePlay(eps[3], host))
        val warn = plugin.beforePlay(eps[8], host) as GuardDecision.Ask
        assertEquals("This is ahead of your weekly schedule with Suzy. It counts as seen for that group.", warn.message)
        assertEquals(listOf("Watch anyway", "Not now"), warn.choices.map { it.label })
        assertEquals(GuardAction.PlayAnyway, warn.choices.first().action)
        // A choice passed in wins: just me, episode 4 plays (nothing counts for Tommy's group); with Suzy, episode 9 is blocked.
        session.names = emptyList()
        assertEquals(GuardDecision.Allow, plugin.beforePlay(eps[3], host, WatchingWith.JustMe))
        assertEquals("Opens Thursday 7pm", (plugin.beforePlay(eps[8], host, WatchingWith.Group("suzy")) as GuardDecision.Ask).title)
        // Just me past Suzy's schedule: it would count for her group, so it warns.
        assertEquals("Ahead of your schedule", (plugin.beforePlay(eps[8], host, WatchingWith.JustMe) as GuardDecision.Ask).title)
    }
}
