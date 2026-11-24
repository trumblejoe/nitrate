package dev.nitrate.core.companion

import dev.nitrate.core.MediaServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteChatsTest {
    // Tuesday 2026-11-24, 21:00 UTC.
    private val now = 1_795_554_000_000L
    private val hour = 3_600_000L
    private val day = 24 * hour

    @Test fun daysReadAsPeopleSayThem() {
        assertEquals("Tonight", NoteChats.day(now - hour, now, 0))
        assertEquals("Today", NoteChats.day(now - 10 * hour, now, 0))
        assertEquals("Yesterday", NoteChats.day(now - day, now, 0))
        assertEquals("Saturday", NoteChats.day(now - 3 * day, now, 0))
        assertEquals("Nov 12", NoteChats.day(now - 12 * day, now, 0))
        assertEquals("Dec 30, 2025", NoteChats.day(now - 329 * day, now, 0))
        // In the viewer's own time zone (an hour behind UTC).
        assertEquals("Tonight", NoteChats.day(now - hour, now, -60))
    }

    private val tuesday = NoteChat("tue", "g1", now - 7 * day + hour, people = listOf("Tommy", "Suzy"), count = 14)
    private val nov = NoteChat("nov", "g1", now - 12 * day, people = listOf("Tommy"), count = 3)
    private val tonight = NoteChat("tonight", "g1", now - hour, buildsOn = "tue", people = listOf("Tommy"), count = 3)
    private val earlier = NoteChat("old", "g1", now - 40 * day, people = emptyList(), count = 2, earlier = true)
    private val all = listOf(tonight, tuesday, nov, earlier)

    @Test fun chatsAreListedWithTheirDayPeopleAndCount() {
        assertEquals("Nov 17 · Tommy, Suzy · 14 messages", NoteChats.label(tuesday, all, now, 0))
        assertEquals("Tonight · builds on Nov 17 · 3 new", NoteChats.label(tonight, all, now, 0))
        assertEquals("Earlier notes · You · 2 messages", NoteChats.label(earlier, all, now, 0))
        assertEquals("1 message", NoteChats.count(1))
        // Building on one the viewer can't see: listed on its own.
        assertEquals("Tonight · Tommy · 3 messages", NoteChats.label(tonight, listOf(tonight), now, 0))
    }

    @Test fun chainsFollowBuildsOnAndStopAtLoops() {
        assertEquals(listOf("tonight", "tue"), NoteChats.chain("tonight", all))
        val loop = listOf(NoteChat("a", buildsOn = "b"), NoteChat("b", buildsOn = "a"))
        assertEquals(listOf("a", "b"), NoteChats.chain("a", loop))
        assertEquals(listOf("x"), NoteChats.chain("x", all))
    }

    @Test fun offeredChatsAreTheCountedGroupsNewestFirstWithoutTheOneBeingWritten() {
        val other = NoteChat("other", "g2", now - 2 * hour, count = 1)
        val chats = all + other
        assertEquals(listOf("tonight", "tue", "nov", "old"), NoteChats.offered(chats, listOf("g1"), null).map { it.id })
        assertEquals(listOf("tonight", "other", "tue", "nov", "old"), NoteChats.offered(chats, null, null).map { it.id })
        assertEquals(listOf("tue", "nov", "old"), NoteChats.offered(chats, listOf("g1"), "tonight").map { it.id })
    }

    @Test fun eachNoteShowsOnce() {
        val a = TimelineNote("a", "tommy", "Tommy", "ep1", 10_000, ChatKind.TEXT, "a")
        val b = TimelineNote("b", "me", "me", "ep1", 5_000, ChatKind.TEXT, "b")
        assertEquals(listOf("b", "a"), NoteChats.showing(listOf(a), listOf(b, a)).map { it.id })
    }
}

class TimelineChatHistoriesTest {
    private val unused: MediaServer = java.lang.reflect.Proxy.newProxyInstance(MediaServer::class.java.classLoader, arrayOf(MediaServer::class.java)) { _, method, _ ->
        error("not used: ${method.name}")
    } as MediaServer

    private val tuesdayNotes = listOf(
        TimelineNote("t1", "tommy", "Tommy", "ep1", 10_000, ChatKind.TEXT, "the kitchen!", chatId = "tue"),
        TimelineNote("t2", "suzy", "Suzy", "ep1", 40_000, ChatKind.TEXT, "yes chef", chatId = "tue"),
    )

    private inner class Server : MediaServer by unused {
        val writes = mutableListOf<ChatWrite>()
        val along = mutableListOf<String?>()
        var noteLoads = 0
        var shared = SessionChat(null, null, null, emptyList())
        override suspend fun titleNotes(itemId: String): TitleNotes { noteLoads++; return TitleNotes(emptyList(), tuesdayNotes) }
        override suspend fun titleChats(itemId: String) =
            TitleChats(listOf(NoteGroup("g1", "The Bear")), listOf(NoteChat("tue", "g1", 0, people = listOf("Tommy", "Suzy"), count = 2)))
        override suspend fun chatNotes(itemId: String, chatId: String) = if (chatId == "tue") tuesdayNotes else emptyList()
        override suspend fun pinChatNote(itemId: String, positionMs: Long, kind: ChatKind, text: String, sessionId: String?, groupIds: List<String>?, chat: ChatWrite): TimelineNote {
            writes += chat
            return TimelineNote("n${writes.size}", "me", "me", itemId, positionMs, kind, text, chatId = chat.chatId ?: "mine")
        }
        override suspend fun playAlong(sessionId: String, itemId: String, chatId: String?) {
            along += chatId
            shared = SessionChat(shared.chatId, chatId, "me", if (chatId == "tue") tuesdayNotes else emptyList())
        }
        override suspend fun sessionChat(sessionId: String, itemId: String) = shared
        override suspend fun chatMessages(groupId: String, after: Long, waitMs: Int): ChatPage = kotlinx.coroutines.awaitCancellation()
    }

    @Test fun aPlaybackStartsOnANewChatAndNothingEarlierPopsUpUnasked() = runTest(UnconfinedTestDispatcher()) {
        val server = Server()
        val chat = TimelineChat(server, backgroundScope, { "me" }, null, chatsOn = { true }) { 0 }
        val popped = mutableListOf<String>()
        backgroundScope.launch { chat.popups.collect { popped += it.id } }
        chat.start("ep1", 0) { true }
        advanceUntilIdle()
        assertEquals(0, server.noteLoads)
        assertTrue(chat.hasChats.value)
        assertNull(chat.playing.value)
        assertEquals(listOf("tue"), chat.chats.value.map { it.id })
        (0..50L).forEach { chat.at(it * 1_000) }
        assertEquals(emptyList<String>(), popped)
        assertEquals(emptyList<TimelineNote>(), chat.history.value)

        // The first message makes the chat; the rest of the playback writes into it.
        assertTrue(chat.send("hi", ChatKind.TEXT, 50_000).isSuccess)
        assertTrue(chat.send("again", ChatKind.TEXT, 50_000).isSuccess)
        assertEquals(listOf(ChatWrite(null, null), ChatWrite("mine", null)), server.writes)
        assertEquals(listOf("n1", "n2"), chat.history.value.map { it.id })
    }

    @Test fun pickingAnEarlierChatPlaysItAlongAndANewMessageBuildsOnIt() = runTest(UnconfinedTestDispatcher()) {
        val server = Server()
        val chat = TimelineChat(server, backgroundScope, { "me" }, null, chatsOn = { true }) { 0 }
        val popped = mutableListOf<String>()
        backgroundScope.launch { chat.popups.collect { popped += it.id } }
        chat.start("ep1", 0) { true }
        advanceUntilIdle()
        (0..20L).forEach { chat.at(it * 1_000) }
        // Picked at 20 s: what's before is listed, not popped; what's ahead pops up as it's passed.
        assertTrue(chat.play("tue").isSuccess)
        assertEquals("tue", chat.playing.value)
        assertEquals(listOf("t1"), chat.history.value.map { it.id })
        (21..45L).forEach { chat.at(it * 1_000) }
        assertEquals(listOf("t2"), popped)
        assertTrue(chat.send("so good", ChatKind.TEXT, 45_000).isSuccess)
        assertEquals(ChatWrite(null, "tue"), server.writes.single())
        assertEquals(listOf("t1", "t2", "n1"), chat.history.value.map { it.id })
        // Back to a new chat: Tuesday's goes; what was written here stays.
        assertTrue(chat.play(null).isSuccess)
        assertEquals(listOf("n1"), chat.history.value.map { it.id })
    }

    @Test fun inAGroupWatchSessionAPickedChatPlaysForEveryone() = runTest(UnconfinedTestDispatcher()) {
        val server = Server()
        val live = GroupChat(server, backgroundScope) { "me" }
        live.follow("sess")
        val chat = TimelineChat(server, backgroundScope, { "me" }, live, chatsOn = { true }) { 0 }
        chat.start("ep1", 0) { true }
        advanceUntilIdle()
        assertTrue(chat.play("tue").isSuccess)
        advanceUntilIdle()
        assertEquals(listOf<String?>("tue"), server.along)
        assertEquals("tue", chat.playing.value)
        assertEquals("me", chat.broughtBy.value)
        // The session's chat is the server's choice: nothing about playing along is sent with a message.
        assertTrue(chat.send("hi", ChatKind.TEXT, 0).isSuccess)
        assertEquals(ChatWrite(null, null), server.writes.single())
    }

    @Test fun withoutChatHistoriesItIsAsBefore() = runTest(UnconfinedTestDispatcher()) {
        val server = Server()
        val chat = TimelineChat(server, backgroundScope, { "me" }, null, chatsOn = { false }) { 0 }
        chat.start("ep1", 0) { true }
        advanceUntilIdle()
        assertEquals(1, server.noteLoads)
        assertFalse(chat.hasChats.value)
        assertTrue(chat.play("tue").isFailure)
    }
}
