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

    private val sam = PartnerProgress("sam", "Sam", setOf("e1-1", "e1-2"), "S1 E2", "e1-3")
    private val kim = PartnerProgress("kim", "Kim", setOf("e1-1"), "S1 E1", "e1-2")

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
        assertNull(WatchPartners.nextTogether(listOf(ep(1, true)), listOf(PartnerProgress("x", "X", setOf("e1-1"), null, null))))
    }

    /** A server with partners on "bear", whose episodes' watched state the test changes. */
    private class Server(var progress: List<PartnerProgress>) {
        var episodes = listOf(MediaItem("e1-1", "1", ItemKind.EPISODE, seriesId = "bear", seasonNumber = 1, indexNumber = 1, user = UserState(played = true)),
            MediaItem("e1-2", "2", ItemKind.EPISODE, seriesId = "bear", seasonNumber = 1, indexNumber = 2),
            MediaItem("e1-3", "3", ItemKind.EPISODE, seriesId = "bear", seasonNumber = 1, indexNumber = 3))
        val marked = mutableListOf<Pair<String, List<String>>>()
        val server: MediaServer = Proxy.newProxyInstance(MediaServer::class.java.classLoader, arrayOf(MediaServer::class.java)) { _, m, args ->
            when (m.name) {
                "companion" -> CompanionInfo("0.1", setOf("partners"))
                "partnerProgress" -> progress
                "allEpisodes" -> episodes
                "watchedTogether" -> { @Suppress("UNCHECKED_CAST") marked += (args[0] as String) to (args[1] as List<String>); args[1] }
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
        assertEquals(listOf("e1-2" to listOf("kim"), "e1-3" to listOf("kim")), fake.marked)
        assertEquals(0, plugin.settle(fake.server))
        // After a few hours it asks again.
        now += WatchPartners.TOGETHER_MS + 1
        assertTrue(plugin.beforePlay(MediaItem("e1-4", "4", ItemKind.EPISODE, seriesId = "bear"), host) is GuardDecision.Ask)
    }

    @Test fun noPartnersNoQuestion() = runBlocking {
        val plugin = WatchPartnersPlugin(TogetherSessions { 0 }) { 0 }
        val fake = Server(emptyList())
        assertEquals(GuardDecision.Allow, plugin.beforePlay(fake.episodes[1], PluginHost(fake.server, store)))
        assertEquals(GuardDecision.Allow, plugin.beforePlay(MediaItem("m", "Movie", ItemKind.MOVIE), PluginHost(fake.server, store)))
    }
}
