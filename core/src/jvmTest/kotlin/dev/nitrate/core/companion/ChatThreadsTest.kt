package dev.nitrate.core.companion

import dev.nitrate.core.MediaServer
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatThreadsTest {
    // Tuesday 2026-11-24, 21:00 UTC.
    private val now = 1_795_554_000_000L
    private val hour = 3_600_000L
    private val day = 24 * hour

    @Test fun threadsAreNamedByTheDevicesOwnDates() {
        assertEquals("2026/11/24", Threads.date(now - hour, 0))
        assertEquals("2025/12/30", Threads.date(now - 329 * day, 0))
        // In the device's own time zone: 21:00 UTC is already the next day three hours ahead.
        assertEquals("2026/11/25", Threads.date(now, 180))
        assertEquals("Started 2026/11/24", Threads.name(ChatThread("t", now - hour)) { 0 })
        assertEquals("14 messages here", Threads.detail(ChatThread("t", count = 14)))
        assertEquals("Nothing here yet", Threads.detail(ChatThread("t")))
        val groups = listOf(ThreadGroup("g1", "The Bear", "show", false, listOf(ChatThread("now", now, current = true), ChatThread("old", now - 30 * day))))
        assertEquals("Group chat · Will", Threads.line(null, groups, "Will") { 0 })
        assertEquals("Replaying 2026/10/25 · Will", Threads.line("old", groups, "Will") { 0 })
        assertEquals("Replaying an earlier thread", Threads.line("gone", groups, null) { 0 })
        assertEquals("Not a favourite", Threads.heart(ChatThread("t")))
    }
}

class TimelineChatThreadsTest {
    private val unused: MediaServer = java.lang.reflect.Proxy.newProxyInstance(MediaServer::class.java.classLoader, arrayOf(MediaServer::class.java)) { _, method, _ ->
        error("not used: ${method.name}")
    } as MediaServer

    private fun note(id: String, at: Long, user: String = "tommy", thread: String = "now", group: String = "g1") =
        TimelineNote(id, user, user, "ep1", at, ChatKind.TEXT, id, listOf(group), chatId = thread)

    private inner class Server : MediaServer by unused {
        var notes = listOf(note("a", 10_000), note("b", 40_000), note("other", 20_000, group = "g2"))
        val older = listOf(note("o1", 15_000, thread = "old"), note("o2", 30_000, thread = "old"))
        val pinned = mutableListOf<Pair<String?, List<String>?>>()
        val along = mutableListOf<String?>()
        var started = 0
        var noteLoads = 0
        val favourites = mutableListOf<Pair<String, Boolean>>()
        var refuse = false
        val threads get() = listOf(
            ThreadGroup("g1", "The Bear", "show", canStart = true, threads = listOf(ChatThread("now", 2, 2, current = true), ChatThread("old", 1, 2))),
            ThreadGroup("g2", "The Bear", "show", canStart = false, threads = listOf(ChatThread("theirs", 1, 1, current = true))),
        )
        override suspend fun titleThreads(itemId: String) = TitleThreads(listOf(NoteGroup("g1", "The Bear"), NoteGroup("g2", "The Bear")), threads, notes)
        override suspend fun titleNotes(itemId: String): TitleNotes { noteLoads++; return TitleNotes(emptyList(), emptyList()) }
        override suspend fun threadNotes(itemId: String, threadId: String) = if (threadId == "old") older else emptyList()
        override suspend fun pinNote(itemId: String, positionMs: Long, kind: ChatKind, text: String, sessionId: String?, groupIds: List<String>?): TimelineNote {
            pinned += sessionId to groupIds
            return TimelineNote("n${pinned.size}", "me", "me", itemId, positionMs, kind, text, groupIds.orEmpty(), chatId = "now")
        }
        override suspend fun startThread(itemId: String, groupId: String) { started++ }
        override suspend fun favouriteThread(itemId: String, threadId: String, favourite: Boolean) {
            if (refuse) throw ChatForbidden()
            favourites += threadId to favourite
        }
        override suspend fun playAlong(sessionId: String, itemId: String, chatId: String?) { along += chatId }
        override suspend fun sessionChat(sessionId: String, itemId: String) = SessionChat("now", along.lastOrNull(), "me", if (along.lastOrNull() == "old") older else emptyList())
        override suspend fun chatMessages(groupId: String, after: Long, waitMs: Int): ChatPage = kotlinx.coroutines.awaitCancellation()
    }

    @Test fun theGroupsConversationPopsUpAsItsReachedAndWhatsWrittenGoesIntoIt() = runTest(UnconfinedTestDispatcher()) {
        val server = Server()
        val chat = TimelineChat(server, backgroundScope, { "me" }, null, threadsOn = { true }) { 0 }
        val popped = mutableListOf<String>()
        backgroundScope.launch { chat.popups.collect { popped += it.id } }
        chat.start("ep1", 0) { true }
        chat.focus(listOf("g1"))
        advanceUntilIdle()
        assertTrue(chat.hasThreads.value)
        // Only the group this counts for.
        assertEquals(listOf("g1"), chat.threads.value.map { it.groupId })
        (0..45L).forEach { chat.at(it * 1_000) }
        assertEquals(listOf("a", "b"), popped)
        assertTrue(chat.send("so good", ChatKind.TEXT, 45_000).isSuccess)
        assertEquals(listOf<Pair<String?, List<String>?>>(null to listOf("g1")), server.pinned)
        assertEquals(listOf("a", "b", "n1"), chat.history.value.map { it.id })
        assertEquals(0, server.noteLoads)
    }

    @Test fun anOlderThreadIsReplayedTooAndTheDetailsPagePickStartsIt() = runTest(UnconfinedTestDispatcher()) {
        val server = Server()
        val chat = TimelineChat(server, backgroundScope, { "me" }, null, threadsOn = { true }) { 0 }
        val popped = mutableListOf<String>()
        backgroundScope.launch { chat.popups.collect { popped += it.id } }
        chat.start("ep1", 0, ChatPick.Replay("old")) { false }
        chat.focus(listOf("g1"))
        advanceUntilIdle()
        assertTrue(chat.on.value)
        assertEquals("old", chat.replaying.value)
        (0..45L).forEach { chat.at(it * 1_000) }
        assertEquals(listOf("a", "o1", "o2", "b"), popped)
        assertTrue(chat.play(null).isSuccess)
        assertNull(chat.replaying.value)
        assertEquals(listOf("a", "b"), chat.history.value.map { it.id })
        // Off from the details page, though the setting is on.
        chat.start("ep1", 0, ChatPick.Off) { true }
        advanceUntilIdle()
        assertFalse(chat.on.value)
    }

    @Test fun inAGroupWatchSessionAReplayPlaysForEveryone() = runTest(UnconfinedTestDispatcher()) {
        val server = Server()
        val live = GroupChat(server, backgroundScope) { "me" }
        live.follow("sess")
        val chat = TimelineChat(server, backgroundScope, { "me" }, live, threadsOn = { true }) { 0 }
        chat.start("ep1", 0) { true }
        advanceUntilIdle()
        assertTrue(chat.play("old").isSuccess)
        advanceUntilIdle()
        assertEquals(listOf<String?>("old"), server.along)
        assertEquals("old", chat.replaying.value)
        assertTrue(chat.send("hi", ChatKind.TEXT, 0).isSuccess)
        assertEquals("sess", server.pinned.single().first)
    }

    @Test fun theCreatorStartsANewThreadAndHeartsChangeOnlyOnceTheServerSaysSo() = runTest(UnconfinedTestDispatcher()) {
        val server = Server()
        val chat = TimelineChat(server, backgroundScope, { "me" }, null, threadsOn = { true }) { 0 }
        chat.start("ep1", 0) { true }
        advanceUntilIdle()
        assertTrue(chat.startThread("g1").isSuccess)
        assertEquals(1, server.started)
        assertTrue(chat.favourite("old", true).isSuccess)
        assertEquals(listOf("old" to true), server.favourites)
        assertTrue(chat.threads.value.first().threads.first { it.id == "old" }.favourite)
        server.refuse = true
        assertTrue(chat.favourite("old", false).isFailure)
        assertTrue(chat.threads.value.first().threads.first { it.id == "old" }.favourite)
    }

    @Test fun withoutThreadsItIsAsBefore() = runTest(UnconfinedTestDispatcher()) {
        val server = Server()
        val chat = TimelineChat(server, backgroundScope, { "me" }, null, threadsOn = { false }) { 0 }
        chat.start("ep1", 0) { true }
        advanceUntilIdle()
        assertEquals(1, server.noteLoads)
        assertFalse(chat.hasThreads.value)
        assertTrue(chat.play("old").isFailure)
    }
}
