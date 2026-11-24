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

    private val utc: (Long) -> Int = { 0 }

    @Test fun daysAreTheDevicesOwnDates() {
        assertEquals("2026/11/24", NoteChats.date(now - hour, 0))
        assertEquals("2026/11/23", NoteChats.date(now - day, 0))
        assertEquals("2025/12/30", NoteChats.date(now - 329 * day, 0))
        // In the device's own time zone: 21:00 UTC is already the next day three hours ahead, still today an hour behind.
        assertEquals("2026/11/25", NoteChats.date(now, 180))
        assertEquals("2026/11/24", NoteChats.date(now, -60))
        // The zone's offset at the chat's own time (summer time then, not now).
        val summer = NoteChat("s", createdMs = 1_782_000_000_000L - 30 * 60_000L)
        assertEquals("2026/06/21", NoteChats.name(summer) { ms -> if (ms < now - 60 * day) 60 else 0 })
    }

    private val tuesday = NoteChat("tue", "g1", now - 7 * day + hour, people = listOf("Tommy", "Suzy"), count = 14)
    private val nov = NoteChat("nov", "g1", now - 12 * day, people = listOf("Tommy"), count = 3)
    private val tonight = NoteChat("tonight", "g1", now - hour, buildsOn = "tue", people = listOf("Tommy"), count = 3)
    private val earlier = NoteChat("old", "g1", now - 40 * day, people = emptyList(), count = 2, earlier = true)
    private val all = listOf(tonight, tuesday, nov, earlier)

    @Test fun chatsAreListedWithTheirDayPeopleAndCount() {
        assertEquals("2026/11/17 · Tommy, Suzy", NoteChats.title(tuesday, all, utc))
        assertEquals("14 messages", NoteChats.detail(tuesday, all, utc))
        // One building on another names everyone in both, and what it builds on underneath.
        assertEquals("2026/11/24 · Tommy, Suzy", NoteChats.title(tonight, all, utc))
        assertEquals("3 new · builds on 2026/11/17 (Tommy, Suzy · 14 messages)", NoteChats.detail(tonight, all, utc))
        assertEquals("2026/11/24 · Tommy, Suzy. 3 new · builds on 2026/11/17 (Tommy, Suzy · 14 messages)", NoteChats.label(tonight, all, utc))
        assertEquals("Earlier notes · You", NoteChats.title(earlier, all, utc))
        // A loop stops.
        val a = NoteChat("a", buildsOn = "b", people = listOf("Ann")); val b = NoteChat("b", buildsOn = "a", people = listOf("Bo"))
        assertEquals("1970/01/01 · Ann, Bo", NoteChats.title(a, listOf(a, b), utc))
        assertEquals("1 message", NoteChats.count(1))
        assertEquals("New chat", NoteChats.playingLabel(null, all, utc))
        assertEquals("Playing along: 2026/11/17 · Tommy, Suzy", NoteChats.playingLabel("tue", all, utc))
        assertEquals("Playing along: Earlier notes", NoteChats.playingLabel("old", all, utc))
        // Building on one the viewer can't see: listed on its own.
        assertEquals("2026/11/24 · Tommy", NoteChats.title(tonight, listOf(tonight), utc))
        assertEquals("3 messages", NoteChats.detail(tonight, listOf(tonight), utc))
        // The heart beside each says which it is.
        assertEquals("Not a favourite", NoteChats.heart(tuesday))
        assertEquals("Favourite", NoteChats.heart(tuesday.copy(favourite = true)))
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
        val favourites = mutableListOf<Pair<String, Boolean>>()
        var refuse = false
        override suspend fun favouriteChat(itemId: String, chatId: String, favourite: Boolean) {
            if (refuse) throw ChatForbidden()
            favourites += chatId to favourite
        }
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

    @Test fun aHeartMakesAChatAFavouriteAtOnceAndIsPutBackIfTheServerSaysNo() = runTest(UnconfinedTestDispatcher()) {
        val server = Server()
        val chat = TimelineChat(server, backgroundScope, { "me" }, null, chatsOn = { true }, favouritesOn = { true }) { 0 }
        assertTrue(chat.canFavourite)
        chat.start("ep1", 0) { true }
        advanceUntilIdle()
        assertTrue(chat.favourite("tue", true).isSuccess)
        assertEquals(listOf("tue" to true), server.favourites)
        assertTrue(chat.chats.value.single().favourite)
        assertTrue(chat.allChats.value.single().favourite)
        server.refuse = true
        assertTrue(chat.favourite("tue", false).isFailure)
        assertTrue(chat.chats.value.single().favourite)
        assertTrue(chat.favourite("nope", true).isFailure)
        assertFalse(TimelineChat(server, backgroundScope, { "me" }, null) { 0 }.canFavourite)
    }
}
