package dev.nitrate.core.plugins

import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The phone runs the guards before telling a TV to play something ("Play on…", the remote). */
class CheckBeforeSendingTest {
    private val episode = MediaItem("e3", "Episode 3", ItemKind.EPISODE)
    private val earlier = MediaItem("e2", "Episode 2", ItemKind.EPISODE)

    private val store = object : PluginStorage {
        override val enabledOverrides = flowOf(emptyMap<String, Boolean>())
        override suspend fun setEnabled(pluginId: String, enabled: Boolean) {}
        override fun raw(pluginId: String) = flowOf<String?>(null)
        override suspend fun saveRaw(pluginId: String, value: String) {}
    }

    private val server = java.lang.reflect.Proxy.newProxyInstance(MediaServer::class.java.classLoader, arrayOf(MediaServer::class.java)) { _, m, _ ->
        throw UnsupportedOperationException(m.name)
    } as MediaServer

    private fun guard(decision: GuardDecision) = object : PlaybackGuard {
        override val id = "test"
        override val name = "Test"
        override val description = ""
        override val enabledByDefault = true
        override suspend fun beforePlay(item: MediaItem, host: PluginHost) = decision
    }

    private fun registry(decision: GuardDecision) = PluginRegistry(listOf(guard(decision)), PluginHost(server, store))

    private fun asking(vararg actions: GuardAction) = GuardDecision.Ask("Wait", "Really?", actions.map { GuardChoice(it.toString(), it) })

    @Test fun allowedSendsTheItemAndAsksNothing() = runTest {
        var asked = 0
        assertEquals(episode, registry(GuardDecision.Allow).checkBeforeSending(episode, { asked++; GuardAction.Cancel }) {})
        assertEquals(0, asked)
    }

    @Test fun theAnswerOnThePhoneDecidesWhatIsSent() = runTest {
        val ask = asking(GuardAction.Cancel)
        // "Not now": nothing goes to the TV.
        assertNull(registry(ask).checkBeforeSending(episode, { GuardAction.Cancel }) {})
        assertEquals(episode, registry(ask).checkBeforeSending(episode, { GuardAction.PlayAnyway }) {})
        assertEquals(earlier, registry(ask).checkBeforeSending(episode, { GuardAction.PlayInstead(earlier) }) {})
        val marked = mutableListOf<String>()
        assertEquals(episode, registry(ask).checkBeforeSending(episode, { GuardAction.MarkWatchedThenPlay(listOf("e1", "e2")) }) { marked += it })
        assertEquals(listOf("e1", "e2"), marked)
        // A plugin's own step first; sent only if it worked (or the guard plays anyway).
        assertNull(registry(ask).checkBeforeSending(episode, { GuardAction.RunThenPlay(playIfFailed = false) { error("no session") } }) {})
        assertEquals(episode, registry(ask).checkBeforeSending(episode, { GuardAction.RunThenPlay(playIfFailed = true) { error("no session") } }) {})
        assertEquals(episode, registry(ask).checkBeforeSending(episode, { GuardAction.RunThenPlay(playIfFailed = false) {} }) {})
    }

    @Test fun everyGuardHearsWhereThePlayStartedEvenWhenAnEarlierOneAsks() = runTest {
        // A group's card: the previous-episode guard asks first; the watch groups' guard still has to
        // hear the origin, or the play counts for no group.
        val heard = mutableListOf<String>()
        val later = object : PlaybackGuard {
            override val id = "later"
            override val name = "Later"
            override val description = ""
            override val enabledByDefault = true
            override suspend fun beforePlay(item: MediaItem, host: PluginHost) = GuardDecision.Allow
            override fun noteOrigin(item: MediaItem, host: PluginHost, origin: dev.nitrate.core.partners.PlayOrigin?) { heard += "${item.id} $origin" }
        }
        val registry = PluginRegistry(listOf(guard(asking(GuardAction.PlayAnyway)), later), PluginHost(server, store))
        registry.checkBeforePlay(episode, dev.nitrate.core.partners.PlayOrigin.GroupCard("g")) { GuardAction.PlayAnyway }
        assertEquals(listOf("e3 GroupCard(groupId=g)"), heard)
    }
}
