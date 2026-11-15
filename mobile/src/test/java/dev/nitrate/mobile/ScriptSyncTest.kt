package dev.nitrate.mobile

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nitrate.android.writing.SharedWriting
import dev.nitrate.android.writing.WritingStore
import dev.nitrate.core.MediaServer
import dev.nitrate.core.companion.CompanionInfo
import dev.nitrate.core.model.SavedAccount
import dev.nitrate.core.model.SavedServer
import dev.nitrate.core.writing.SharedScriptDoc
import dev.nitrate.core.writing.SharedScriptInfo
import dev.nitrate.core.writing.UserRef
import dev.nitrate.core.writing.story.RecordChange
import dev.nitrate.core.writing.story.Story
import dev.nitrate.testing.FakeServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class ScriptSyncTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val home = "http://home|joe"
    private val away = "http://away|joe2"

    /** A Companion plugin's scripts, in memory: the scripts on one server for one user. */
    private class Plugin {
        val scripts = mutableMapOf<String, Pair<SharedScriptInfo, String>>()
        var next = 0
    }

    /** One device's view of the server for the signed-in account, with the plugin when [plugin] is set. */
    private inner class Server(var account: String, val plugins: Map<String, Plugin>) : MediaServer by FakeServer() {
        private val plugin get() = plugins[account.substringBefore('|')]
        override val currentAccountKey get() = account
        override val userId get() = account.substringAfter('|')
        override suspend fun companion() = plugin?.let { CompanionInfo("0.2", setOf("scripts")) }
        override suspend fun servers() = listOf(
            SavedServer("http://home", "Home", null, null, null, primary = true, accounts = listOf(SavedAccount(home, "http://home", "Home", "joe", "joe", null)), active = null),
            SavedServer("http://away", "Away", null, null, null, primary = false, accounts = listOf(SavedAccount(away, "http://away", "Away", "joe2", "joe2", null)), active = null),
        )
        override suspend fun sharedScripts() = plugin!!.scripts.values.map { it.first }
        override suspend fun createSharedScript(text: String, members: List<String>, records: List<RecordChange>): SharedScriptDoc {
            val p = plugin!!
            val info = SharedScriptInfo("s${p.next++}", dev.nitrate.core.writing.Scripts.titleOf(text), UserRef(userId, userId), members.map { UserRef(it, it) }, 0, 0)
            p.scripts[info.id] = info to text
            return SharedScriptDoc(info, text, 0)
        }
        override suspend fun sharedScript(id: String) = plugin!!.scripts.getValue(id).let { (i, t) -> SharedScriptDoc(i, t, 0) }
        override suspend fun deleteSharedScript(id: String) { plugin!!.scripts.remove(id) }
        override suspend fun setScriptMembers(id: String, members: List<String>): SharedScriptInfo {
            val p = plugin!!
            val (info, text) = p.scripts.getValue(id)
            return info.copy(members = members.map { UserRef(it, it) }).also { p.scripts[id] = it to text }
        }
    }

    @Before fun clean() {
        File(context.filesDir, "writing").deleteRecursively()
    }

    private fun setUp(account: String, plugins: Map<String, Plugin>): Triple<Server, WritingStore, SharedWriting> {
        val server = Server(account, plugins)
        val store = WritingStore(context, server).also { it.account = account; it.author = { server.userId to server.userId } }
        val companion = MutableStateFlow<CompanionInfo?>(runBlocking { server.companion() })
        return Triple(server, store, SharedWriting(store, server, companion) { server.userId })
    }

    @Test fun scriptsBelongToTheProfileThatMadeThem() = runBlocking {
        val (_, store, _) = setUp(home, emptyMap())
        val mine = store.create("Title: Mine")
        store.account = away
        assertTrue(store.docs.value.isEmpty())
        val theirs = store.create("Title: Theirs")
        assertEquals(listOf(theirs), store.docs.value.map { it.id })
        store.account = home
        assertEquals(listOf(mine), store.docs.value.map { it.id })
        assertEquals(home, store.ownerOf(mine))
    }

    @Test fun scriptsFromBeforeOwnersGoToTheFirstSignedInProfile() = runBlocking {
        val dir = File(context.filesDir, "writing").apply { mkdirs() }
        File(dir, "old-script.fountain").writeText("Title: From before")
        val store = WritingStore(context, Server(home, emptyMap()))
        store.account = home
        assertEquals(listOf("old-script"), store.docs.value.map { it.id })
        store.account = away
        assertTrue(store.docs.value.isEmpty())
    }

    @Test fun anUnsyncedScriptKeepsItsHistoryOnTheDevice() = runBlocking {
        val (_, store, shared) = setUp(home, emptyMap())
        val id = store.create("INT. DINER - NIGHT [[scene:aaaa]]\n\nSam waits.")
        store.save(id, "INT. DINER - NIGHT [[scene:aaaa]]\n\nSam waits. Rain.")
        store.recordStory(id, listOf(Story.setEntry("e1", Story.CHARACTERS, "SAM", "A cook")))
        store.save(id, "INT. DINER - NIGHT [[scene:aaaa]]\n\nSam waits. Rain. Jo arrives.")
        val history = shared.history(id)
        val log = history.log()
        assertEquals(3, log.size)
        assertEquals("aaaa", log[0].sceneId)
        assertEquals("joe", log[0].userName)
        assertEquals("INT. DINER - NIGHT [[scene:aaaa]]\n\nSam waits. Rain.", history.textAt(2))
        assertEquals("A cook", history.recordAt(2, "e1")?.get("notes"))
        val (text, who) = shared.authorship(id)!!
        assertEquals(store.read(id), text)
        assertEquals("joe", who.at(text.indexOf("Jo arrives")))
    }

    @Test fun onlyThePrimaryServerSyncsUntilAnotherIsSwitchedOn() = runBlocking {
        val (_, _, shared) = setUp(home, emptyMap())
        assertEquals(mapOf(home to true, away to false), shared.syncOptions().associate { it.account.key to it.on })
        shared.setSync(away, true)
        shared.setSync(home, false)
        assertEquals(mapOf(home to false, away to true), shared.syncOptions().associate { it.account.key to it.on })
    }

    @Test fun syncingPutsScriptsOnTheServerAndBringsInOnesMadeElsewhere() = runBlocking {
        val homePlugin = Plugin()
        val (server, store, shared) = setUp(home, mapOf("http://home" to homePlugin))
        val here = store.create("Title: Written here")
        server.createSharedScript("Title: Written on the phone", emptyList(), emptyList())
        shared.autoSync()
        // Written here: now on the server, privately.
        val link = store.link(here)!!
        assertTrue(homePlugin.scripts.getValue(link.sharedId).first.members.isEmpty())
        assertEquals("Synced", store.docs.value.first { it.id == here }.placeLabel)
        // Written elsewhere: now here too.
        assertEquals(setOf("Written here", "Written on the phone"), store.docs.value.map { it.title }.toSet())
        // Nothing twice.
        shared.autoSync()
        assertEquals(2, homePlugin.scripts.size)
        assertEquals(2, store.docs.value.size)
    }

    @Test fun aNewServerGetsNothingUntilItsSwitchedOn() = runBlocking {
        val awayPlugin = Plugin()
        val (_, store, shared) = setUp(away, mapOf("http://away" to awayPlugin))
        store.create("Title: Private")
        shared.autoSync()
        assertTrue(awayPlugin.scripts.isEmpty())
        assertEquals("On this device", store.docs.value.single().placeLabel)
        shared.setSync(away, true)
        shared.autoSync()
        assertEquals(1, awayPlugin.scripts.size)
    }

    @Test fun movingGivesTheScriptToTheOtherAccountAndItsServer() = runBlocking {
        val homePlugin = Plugin()
        val awayPlugin = Plugin()
        val plugins = mapOf("http://home" to homePlugin, "http://away" to awayPlugin)
        val (server, store, shared) = setUp(home, plugins)
        val id = store.create("Title: Moving")
        shared.autoSync()
        assertEquals(1, homePlugin.scripts.size)
        shared.move(id, away)
        // Gone from Home's server and from this profile.
        assertTrue(homePlugin.scripts.isEmpty())
        assertTrue(store.docs.value.isEmpty())
        assertNull(store.link(id))
        // Signed in as the other account (syncing there): it goes to that server.
        shared.setSync(away, true)
        server.account = away
        store.account = away
        shared.autoSync()
        assertEquals(listOf("Moving"), awayPlugin.scripts.values.map { it.first.title })
        assertEquals("Synced", store.docs.value.single().placeLabel)
    }

    @Test fun keptOnThisDeviceOnlyItLeavesTheServerAndStaysOff() = runBlocking {
        val homePlugin = Plugin()
        val (_, store, shared) = setUp(home, mapOf("http://home" to homePlugin))
        val id = store.create("Title: Just here")
        shared.autoSync()
        shared.move(id, null)
        shared.autoSync()
        assertTrue(homePlugin.scripts.isEmpty())
        assertEquals("On this device", store.docs.value.single().placeLabel)
        assertFalse(store.docs.value.single().isShared)
    }
}
