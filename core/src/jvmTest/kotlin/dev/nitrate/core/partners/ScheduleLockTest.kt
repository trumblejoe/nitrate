package dev.nitrate.core.partners

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.UserState
import dev.nitrate.core.plugins.GuardAction
import dev.nitrate.core.plugins.GuardDecision
import dev.nitrate.core.plugins.PluginHost
import dev.nitrate.core.writing.UserRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "If someone plays ahead: Ask first / Don't allow" in a group's schedule, and where a playback started from a details page. */
class ScheduleLockTest {
    private fun ep(n: Int, played: Boolean = false) =
        MediaItem("e1-$n", "Episode $n", ItemKind.EPISODE, seriesId = "bear", seasonNumber = 1, indexNumber = n, user = UserState(played = played))

    private val eps = (1..4).map { ep(it) }
    private val sam = UserRef("sam", "Sam")
    private val kim = UserRef("kim", "Kim")
    private val weekly = GroupSchedule(GroupSchedule.WEEKLY, day = 4, hour = 19)

    /** Episode 3 isn't out yet (opens Thursday 7pm), and nobody in the group has watched it. */
    private fun group(id: String, who: UserRef, schedule: GroupSchedule, notOut: Boolean = schedule.limits) =
        WatchGroup(id, "bear", "Bear", listOf(who), 0, schedule, lockedFromId = if (notOut) "e1-3" else null, opensDay = if (notOut) 4 else null,
            togetherFromId = if (schedule.together) "e1-3" else null)

    private val noStore = object : dev.nitrate.core.plugins.PluginStorage {
        override val enabledOverrides = kotlinx.coroutines.flow.flowOf(emptyMap<String, Boolean>())
        override suspend fun setEnabled(pluginId: String, enabled: Boolean) {}
        override fun raw(pluginId: String) = kotlinx.coroutines.flow.flowOf<String?>(null)
        override suspend fun saveRaw(pluginId: String, value: String) {}
    }

    private fun server(vararg groups: WatchGroup): dev.nitrate.core.MediaServer =
        java.lang.reflect.Proxy.newProxyInstance(dev.nitrate.core.MediaServer::class.java.classLoader, arrayOf(dev.nitrate.core.MediaServer::class.java)) { _, m, _ ->
            when (m.name) {
                "companion" -> dev.nitrate.core.companion.CompanionInfo("1", setOf("partners"))
                "partners" -> PartnersInfo(groups.toList(), emptyList(), emptyList())
                "titleItems" -> eps
                "getUserId" -> "me"
                else -> throw UnsupportedOperationException(m.name)
            }
        } as dev.nitrate.core.MediaServer

    private class Session(var names: List<String>, var id: String? = null) : dev.nitrate.core.plugins.TogetherSession {
        override fun participants() = names
        override fun sessionId() = id
        override suspend fun start(item: MediaItem, groupId: String, userIds: List<String>, names: String) {}
    }

    private val card = PlayOrigin.GroupCard("g1")
    private val origins = listOf(null, PlayOrigin.Elsewhere, card, PlayOrigin.ForMyself)

    private fun ask(d: GuardDecision) = d as GuardDecision.Ask

    @Test fun theChoiceIsPartOfTheLabelAndTheInvite() {
        assertEquals("Thursdays 7pm, together, locked", weekly.copy(together = true, locked = true).label)
        assertEquals("Thursdays 7pm, locked", weekly.copy(locked = true).label)
        // Binge with Together off holds nothing back: nothing to lock.
        assertEquals("Binge", GroupSchedule(locked = true).label)
        assertFalse(group("g", sam, GroupSchedule(locked = true)).locked)
        assertTrue(group("g", sam, weekly.copy(locked = true)).locked)
        assertEquals("Don't allow", weekly.copy(locked = true).playAhead)
        assertEquals("Ask first", weekly.playAhead)
        assertTrue(weekly.copy(locked = true).terms.contains("Playing ahead isn't allowed"))
        assertTrue(weekly.terms.contains("Nothing is locked"))
        assertTrue(GroupSchedule(together = true, locked = true).terms.contains("plays only when you watch it together"))
        // The old SoftLock is never read as the new choice.
        assertFalse(weekly.copy(softLock = true).locked)
    }

    @Test fun lockedRefusesWhatIsNotOutYetFromAnywhere() = runBlocking {
        val plugin = WatchPartnersPlugin()
        val host = PluginHost(server(group("g1", sam, weekly.copy(locked = true))), noStore, Session(emptyList()))
        for (origin in origins) {
            val refused = ask(plugin.beforePlay(eps[2], host, origin))
            assertEquals("Opens Thursday 7pm", refused.title)
            assertEquals(listOf("OK"), refused.choices.map { it.label })
            assertEquals(GuardAction.Cancel, refused.choices.single().action)
            assertTrue(refused.message, refused.message.contains("Sam") && refused.message.contains("doesn't allow playing ahead"))
            // Everything after it too.
            assertEquals(listOf("OK"), ask(plugin.beforePlay(eps[3], host, origin)).choices.map { it.label })
            // Catching up to what's out stays free.
            assertEquals(GuardDecision.Allow, plugin.beforePlay(eps[1], host, origin))
        }
        // Even with Sam in the session: the schedule hasn't let it out.
        val withSam = PluginHost(server(group("g1", sam, weekly.copy(locked = true))), noStore, Session(listOf("me", "Sam")))
        assertEquals(listOf("OK"), ask(plugin.beforePlay(eps[2], withSam)).choices.map { it.label })
        // Rewatching something the user has seen stays free of the lock (asked as Ask first).
        assertEquals(listOf("Watch on my own", "Not now"), ask(plugin.beforePlay(eps[2].copy(user = UserState(played = true)), host)).choices.map { it.label })
    }

    /** A session can't be started on what the lock refuses (everyone was left in one nobody could play). */
    @Test fun aSessionIsNotStartedOnWhatTheLockRefuses() = runBlocking {
        val plugin = WatchPartnersPlugin()
        val host = PluginHost(server(group("g1", sam, weekly.copy(locked = true))), noStore, Session(emptyList()))
        assertTrue(plugin.refusedInSession(host, "g1", eps[2])!!.contains("doesn't allow playing ahead"))
        assertNull(plugin.refusedInSession(host, "g1", eps[1]))
        // Seen already, another group, or Ask first: nothing stops the session.
        assertNull(plugin.refusedInSession(host, "g1", eps[2].copy(user = UserState(played = true))))
        assertNull(plugin.refusedInSession(host, "other", eps[2]))
        val askFirst = PluginHost(server(group("g1", sam, weekly)), noStore, Session(emptyList()))
        assertNull(plugin.refusedInSession(askFirst, "g1", eps[2]))
    }

    @Test fun togetherAndLockedPlaysOnlyTogether() = runBlocking {
        val plugin = WatchPartnersPlugin()
        val g = group("g1", sam, GroupSchedule(together = true, locked = true))
        val session = Session(emptyList())
        val host = PluginHost(server(g), noStore, session)
        for (origin in origins) {
            val only = ask(plugin.beforePlay(eps[2], host, origin))
            assertEquals("Sam hasn't watched this yet", only.title)
            assertEquals(listOf("Start a Group Watch Session", "We're in the same room", "Not now"), only.choices.map { it.label })
            assertEquals(GuardDecision.Allow, plugin.beforePlay(eps[1], host, origin))
        }
        // Without Group Watch Sessions: the same room, or not now.
        assertEquals(listOf("We're in the same room", "Not now"), ask(plugin.beforePlay(eps[2], PluginHost(server(g), noStore))).choices.map { it.label })
        // In a live session with Sam: plays as today.
        session.names = listOf("me", "sam")
        assertEquals(GuardDecision.Allow, plugin.beforePlay(eps[2], host))
        // In a session this device started for the group, before anyone has joined: that's watching together.
        session.names = listOf("me")
        session.id = "sp1"
        assertTrue(plugin.beforePlay(eps[2], host) is GuardDecision.Ask)
        plugin.watchingNow(host, "g1", "sp1")
        assertEquals(GuardDecision.Allow, plugin.beforePlay(eps[2], host))
        // Another session later: not any more.
        session.id = "sp2"
        assertTrue(plugin.beforePlay(eps[2], host) is GuardDecision.Ask)
        // In the same room with Sam: plays.
        session.id = null
        plugin.setRoom(host, "bear", SameRoom("g1", listOf("sam")))
        assertEquals(GuardDecision.Allow, plugin.beforePlay(eps[2], host))
    }

    @Test fun askFirstElsewhereNowAsksForWhatIsNotOutYet() = runBlocking {
        val plugin = WatchPartnersPlugin()
        // Together off, paced: from the library it says when it opens (it used to say nothing).
        val host = PluginHost(server(group("g1", sam, weekly)), noStore, Session(emptyList()))
        val a = ask(plugin.beforePlay(eps[2], host))
        assertEquals("Opens Thursday 7pm", a.title)
        assertEquals(listOf("Watch on my own", "Not now"), a.choices.map { it.label })
        assertEquals(a.choices.map { it.label }, ask(plugin.beforePlay(eps[2], host, card)).choices.map { it.label })
        // "Watch on my own" asks nothing more; what's out plays.
        assertEquals(GuardDecision.Allow, plugin.beforePlay(eps[2], host, PlayOrigin.ForMyself))
        assertEquals(GuardDecision.Allow, plugin.beforePlay(eps[1], host))
        // Together off and binge: nothing to ask.
        assertEquals(GuardDecision.Allow, plugin.beforePlay(eps[2], PluginHost(server(group("g1", sam, GroupSchedule())), noStore)))
    }

    @Test fun theStrictestGroupDecidesAndIsNamed() = runBlocking {
        val plugin = WatchPartnersPlugin()
        // Sam's group asks first (together); Kim's doesn't allow playing ahead and 3 isn't out there.
        val host = PluginHost(server(group("g1", sam, GroupSchedule(together = true)), group("g2", kim, weekly.copy(locked = true))), noStore, Session(emptyList()))
        val refused = ask(plugin.beforePlay(eps[2], host))
        assertEquals(listOf("OK"), refused.choices.map { it.label })
        assertTrue(refused.message, refused.message.contains("Kim") && !refused.message.contains("Sam"))
        // From Sam's card too: Kim's lock still applies.
        assertEquals(listOf("OK"), ask(plugin.beforePlay(eps[2], host, PlayOrigin.GroupCard("g1"))).choices.map { it.label })
        // Sam's group says not out yet (ask first); Kim's plays only together: watching together wins.
        val both = PluginHost(server(group("g1", sam, weekly), group("g2", kim, GroupSchedule(together = true, locked = true))), noStore, Session(emptyList()))
        val only = ask(plugin.beforePlay(eps[2], both))
        assertEquals("Kim hasn't watched this yet", only.title)
        assertFalse(only.choices.any { it.label == "Watch on my own" })
        // Nothing locked in either: the first group's question, as before.
        val open = PluginHost(server(group("g1", sam, GroupSchedule(together = true)), group("g2", kim, GroupSchedule(together = true))), noStore, Session(emptyList()))
        assertEquals("Sam hasn't watched this yet", ask(plugin.beforePlay(eps[2], open)).title)
    }

    @Test fun aDetailsPageOpenedFromAGroupsCardPlaysFromThatCard() = runBlocking {
        val g = group("g1", sam, weekly.copy(locked = true))
        val info = PartnersInfo(listOf(g), emptyList(), emptyList())
        val standings = mapOf("bear" to KeepUp.of("bear", eps, emptyList(), info.groups))
        // The card's item carries its group; the details page it opens keeps it for parts of the title.
        val cardItem = eps[2].copy(watchGroupId = "g1")
        val group = cardItem.watchGroupId
        assertEquals(card, PlayOrigin.fromDetails(group, eps[2], info, standings))
        assertEquals(card, PlayOrigin.fromDetails(group, MediaItem("bear", "Bear", ItemKind.SERIES), info, standings))
        assertEquals(card, PlayOrigin.fromDetails(group, eps[0], info, standings))
        // A trailer or something similar on the page: elsewhere.
        assertNull(PlayOrigin.fromDetails(group, MediaItem("other", "Other", ItemKind.MOVIE), info, standings))
        // Opened any other way: elsewhere.
        assertNull(PlayOrigin.fromDetails(null, eps[2], info, standings))
        // A group the user has since left: elsewhere.
        assertNull(PlayOrigin.fromDetails("gone", eps[2], info, standings))
        // And the guard and the player get it, just as from the card.
        val plugin = WatchPartnersPlugin()
        val host = PluginHost(server(group("g1", sam, weekly)), noStore)
        val from = PlayOrigin.fromDetails(group, eps[1], info, standings)
        assertEquals(GuardDecision.Allow, plugin.beforePlay(eps[1], host, from))
        assertEquals(card, plugin.takeOrigin(host, eps[1]))
        assertEquals(listOf(g), WatchPartners.countingFor(from!!, eps[1], info, standings).groups)
    }
}
