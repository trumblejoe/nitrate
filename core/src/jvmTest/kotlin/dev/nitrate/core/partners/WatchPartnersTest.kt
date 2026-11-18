package dev.nitrate.core.partners

import dev.nitrate.core.MediaServer
import dev.nitrate.core.companion.CompanionInfo
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.UserState
import dev.nitrate.core.pages.DisplayPrefs
import dev.nitrate.core.plugins.GuardAction
import dev.nitrate.core.plugins.GuardDecision
import dev.nitrate.core.plugins.PluginHost
import dev.nitrate.core.plugins.PluginStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

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
    }

    @Test fun upNextTogetherIsTheFirstEpisodeSomeoneHasntSeen() {
        val episodes = listOf(ep(0, season = 0), ep(1, true), ep(2, true), ep(3))
        assertEquals("e1-2", WatchPartners.nextTogether(episodes, listOf(sam, kim))?.id)
        assertEquals("e1-3", WatchPartners.nextTogether(episodes, listOf(sam))?.id)
        assertNull(WatchPartners.nextTogether(listOf(ep(1, true)), listOf(PartnerProgress("g", "x", "X", setOf("e1-1"), null, null))))
        // Per group: Sam's group is next at 3, Kim's at 2.
        assertEquals(listOf("e1-3", "e1-2"), WatchPartners.nextPerGroup(episodes, listOf(sam, kim)).map { it.id })
    }

    /** A server with partners on "bear", whose episodes' watched state the test changes. */
    private class Server(var progress: List<PartnerProgress>) {
        var episodes = listOf(MediaItem("e1-1", "1", ItemKind.EPISODE, seriesId = "bear", seasonNumber = 1, indexNumber = 1, user = UserState(played = true)),
            MediaItem("e1-2", "2", ItemKind.EPISODE, seriesId = "bear", seasonNumber = 1, indexNumber = 2),
            MediaItem("e1-3", "3", ItemKind.EPISODE, seriesId = "bear", seasonNumber = 1, indexNumber = 3))
        val marked = mutableListOf<Pair<String, String>>()
        val server: MediaServer = Proxy.newProxyInstance(MediaServer::class.java.classLoader, arrayOf(MediaServer::class.java)) { _, m, args ->
            when (m.name) {
                "companion" -> CompanionInfo("0.1", setOf("partners"))
                "partnerProgress" -> progress
                "allEpisodes" -> episodes
                "watchedTogether" -> { marked += (args[0] as String) to (args[1] as String); listOf("x") }
                else -> throw UnsupportedOperationException(m.name)
            }
        } as MediaServer
    }

    private val store = object : PluginStorage {
        override val enabledOverrides: Flow<Map<String, Boolean>> = flowOf(emptyMap())
        override suspend fun setEnabled(pluginId: String, enabled: Boolean) {}
        override fun raw(pluginId: String): Flow<String?> = flowOf(null)
        override suspend fun saveRaw(pluginId: String, value: String) {}
    }

    @Test fun watchingTogetherCountsForBothThenCarriesOn() = runBlocking {
        var now = 1_000L
        val fake = Server(listOf(kim))
        val plugin = WatchPartnersPlugin(TogetherSessions { now }) { now }
        val host = PluginHost(fake.server, store)
        val e2 = fake.episodes[1]
        val ask = plugin.beforePlay(e2, host) as GuardDecision.Ask
        assertEquals("Kim hasn't seen this yet", ask.title)
        assertEquals(listOf("Watching with Kim", "Just me", "Cancel"), ask.choices.map { it.label })
        // Kim's seen episode 1: no question.
        assertEquals(GuardDecision.Allow, plugin.beforePlay(fake.episodes[0], host))

        (ask.choices[0].action as GuardAction.RunThenPlay).run()
        // Watching together now: the next episode plays on without asking.
        assertEquals(GuardDecision.Allow, plugin.beforePlay(fake.episodes[2], host))
        // Episode 2 and 3 finished: both count for Kim; episode 1 (seen before) doesn't.
        fake.episodes = fake.episodes.map { it.copy(user = UserState(played = true)) }
        assertEquals(2, plugin.settle(fake.server))
        assertEquals(listOf("e1-2" to "g2", "e1-3" to "g2"), fake.marked)
        assertEquals(0, plugin.settle(fake.server))
        // After a few hours it asks again.
        now += WatchPartners.TOGETHER_MS + 1
        assertTrue(plugin.beforePlay(MediaItem("e1-4", "4", ItemKind.EPISODE, seriesId = "bear"), host) is GuardDecision.Ask)
    }

    @Test fun eachGroupIsItsOwnChoice() = runBlocking {
        val will = PartnerProgress("gw", "will", "Will", emptySet(), null, null)
        val frank = PartnerProgress("gf", "frank", "Frank", setOf("e1-2"), "S1 E2", null)
        val tommy = PartnerProgress("gf", "tommy", "Tommy", emptySet(), null, null)
        val fake = Server(listOf(will, frank, tommy))
        val plugin = WatchPartnersPlugin(TogetherSessions { 0 }) { 0 }
        val ask = plugin.beforePlay(fake.episodes[1], PluginHost(fake.server, store)) as GuardDecision.Ask
        assertEquals("Will and Tommy haven't seen this yet", ask.title)
        assertEquals(listOf("Watching with Will", "Watching with Frank and Tommy", "Just me", "Cancel"), ask.choices.map { it.label })
        (ask.choices[1].action as GuardAction.RunThenPlay).run()
        assertEquals("gf", plugin.sessions.active("bear")?.groupId)
    }

    @Test fun noPartnersNoQuestion() = runBlocking {
        val plugin = WatchPartnersPlugin(TogetherSessions { 0 }) { 0 }
        val fake = Server(emptyList())
        assertEquals(GuardDecision.Allow, plugin.beforePlay(fake.episodes[1], PluginHost(fake.server, store)))
        assertEquals(GuardDecision.Allow, plugin.beforePlay(MediaItem("m", "Movie", ItemKind.MOVIE), PluginHost(fake.server, store)))
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

    @Test fun sessionsClearForTheNextAccount() {
        val sessions = TogetherSessions { 0L }
        sessions.put(TogetherSession("bear", "g1", listOf("Sam"), 1_000, emptySet()))
        sessions.clear()
        assertNull(sessions.active("bear"))
    }
}
