package dev.nitrate.core.companion

import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.UserState
import dev.nitrate.core.pages.DisplayPrefs
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineChatTest {
    private fun note(id: String, at: Long, user: String = "sam", text: String = id) =
        TimelineNote(id, user, user, "ep1", at, ChatKind.TEXT, text, listOf("g1"), atMs = at)

    private val notes = listOf(note("a", 10_000), note("b", 60_000), note("mine", 65_000, user = "me"), note("c", 120_000), note("d", 3_000_000))

    private fun clock(on: Boolean = true, startMs: Long = 0) = NoteClock { "me" }.apply { start(startMs, on); setNotes(notes) }

    /** Plays from [from] to [to] a second at a time, collecting what pops up. */
    private fun NoteClock.play(from: Long, to: Long): List<String> = (from..to step 1_000).flatMap { at(it) }.map { it.id }

    @Test fun notesPopUpAsTheirPointIsReachedNeverAhead() {
        val c = clock()
        assertEquals(listOf("a"), c.play(0, 30_000))
        // Nothing further on is shown, in pop-ups or the history.
        assertEquals(listOf("a"), c.history().map { it.id })
        assertEquals(listOf("b", "c"), c.play(31_000, 200_000))
        // The viewer's own note is in the history but never pops up.
        assertEquals(listOf("a", "b", "mine", "c"), c.history().map { it.id })
    }

    @Test fun eachPopsUpOnceAndJumpsSkipWhatTheyPass() {
        val c = clock()
        c.play(0, 15_000)
        // Back and through it again: no second pop-up.
        c.at(5_000)
        assertEquals(emptyList<String>(), c.play(5_000, 15_000))
        // A jump forward over b and c: not popped, but reached (the viewer chose to go there).
        assertEquals(emptyList<String>(), c.at(200_000).map { it.id })
        assertEquals(listOf("a", "b", "mine", "c"), c.history().map { it.id })
        // Jumping back keeps what was reached.
        c.at(20_000)
        assertEquals(4, c.history().size)
    }

    @Test fun resumingStartsFromTheResumePoint() {
        val c = clock(startMs = 100_000)
        // Before the resume point counts as reached; nothing pops up for it.
        assertEquals(listOf("a", "b", "mine"), c.history().map { it.id })
        assertEquals(listOf("c"), c.play(100_000, 130_000))
    }

    @Test fun offShowsNothingAndTurningOnOffersWhatWasMissed() {
        val c = clock(on = false)
        assertEquals(emptyList<String>(), c.play(0, 90_000))
        c.turn(true, 90_000)
        // From here on as usual; what came before waits for "Show what I missed" (own notes stay).
        assertEquals(listOf("a", "b"), c.missed().map { it.id })
        assertEquals(listOf("mine"), c.history().map { it.id })
        assertEquals(listOf("a", "b", "mine"), c.history(all = true).map { it.id })
        assertEquals(listOf("c"), c.play(91_000, 130_000))
        // Off again: nothing more pops up.
        c.turn(false, 130_000)
        assertEquals(emptyList<String>(), c.play(130_000, 140_000))
    }

    @Test fun liveMessagesShowAtOnceAndTheirPinnedCopyDoesNotPopAgain() {
        val c = clock()
        val live = Notes.fromLive(ChatMessage(7, "kim", "kim", ChatKind.REACTION, "😂", 40_500, 0), "ep1", 0)
        assertEquals("live-7", c.live(live)?.id)
        // The same message arrives pinned when the notes are loaded again.
        val pinned = TimelineNote("p7", "kim", "kim", "ep1", 40_000, ChatKind.REACTION, "😂")
        c.setNotes(notes + pinned)
        assertEquals(listOf("a", "b"), c.play(0, 61_000))
        assertEquals(1, c.history().count { it.text == "😂" })
        // Own live messages never pop up.
        assertEquals(null, c.live(Notes.fromLive(ChatMessage(8, "me", "me", ChatKind.TEXT, "hi", 61_000, 0), "ep1", 0)))
    }

    @Test fun detailsListOnlyUpToWhereTheViewerHasGot() {
        val started = MediaItem("ep1", "Pilot", ItemKind.EPISODE, user = UserState(positionMs = 60_000))
        assertEquals(listOf("a", "b"), Notes.reached(notes, Notes.listableUpTo(started)).filter { it.userId != "me" }.map { it.id })
        val watched = started.copy(user = UserState(played = true))
        assertEquals(5, Notes.reached(notes, Notes.listableUpTo(watched)).size)
        assertEquals(emptyList<TimelineNote>(), Notes.reached(notes, Notes.listableUpTo(started.copy(user = UserState()))))
    }

    @Test fun groupChatIsRememberedPerGroupAndStartsOffInEasyMode() {
        assertTrue(DisplayPrefs().groupChatOn(listOf("g1")))
        assertFalse(DisplayPrefs(easyMode = true).groupChatOn(listOf("g1")))
        val off = DisplayPrefs().withGroupChat(listOf("g1", "g2"), false)
        assertFalse(off.groupChatOn(listOf("g2")))
        assertTrue(off.groupChatOn(listOf("g3")))
        assertTrue(DisplayPrefs(easyMode = true).withGroupChat(listOf("g1"), true).groupChatOn(listOf("g1")))
    }

    @Test fun oldMomentsReadAsNotes() {
        val m = TimelineNote("m", "sam", "Sam", "ep1", 3_723_000, ChatKind.TEXT, "", moment = true)
        assertEquals("1:02:03 · Marked this moment · Sam", Notes.line(m, "me"))
        assertEquals("1:02:03 · Marked this moment", Notes.line(m, "sam"))
    }

    private val unused: MediaServer = java.lang.reflect.Proxy.newProxyInstance(MediaServer::class.java.classLoader, arrayOf(MediaServer::class.java)) { _, method, _ ->
        error("not used: ${method.name}")
    } as MediaServer

    @Test fun loadsOnceThenRarelyAndPinsWhatIsSent() = runTest(UnconfinedTestDispatcher()) {
        var loads = 0
        val pinned = mutableListOf<Long>()
        var now = 0L
        val server = object : MediaServer by unused {
            override suspend fun titleNotes(itemId: String): TitleNotes { loads++; return TitleNotes(listOf(NoteGroup("g1", "The Bear")), notes) }
            override suspend fun pinNote(itemId: String, positionMs: Long, kind: ChatKind, text: String, sessionId: String?): TimelineNote {
                pinned += positionMs
                return TimelineNote("new", "me", "me", itemId, positionMs, kind, text, listOf("g1"))
            }
        }
        val chat = TimelineChat(server, backgroundScope, { "me" }, null) { now }
        val popped = mutableListOf<TimelineNote>()
        backgroundScope.launch { chat.popups.toList(popped) }
        chat.start("ep1", 0) { true }
        advanceUntilIdle()
        assertTrue(chat.available)
        for (s in 0..70L) { now = s * 1_000; chat.at(s * 1_000) }
        assertEquals(1, loads)
        assertEquals(listOf("a", "b"), popped.map { it.id })
        // Opened within a minute of loading: no new request; later, one; ten minutes in: one more.
        now = TimelineChat.REOPEN_MS - 1
        chat.opened()
        assertEquals(1, loads)
        now = TimelineChat.REOPEN_MS
        chat.opened()
        advanceUntilIdle()
        assertEquals(2, loads)
        now = TimelineChat.REOPEN_MS + 1_000
        chat.at(71_000)
        assertEquals(2, loads)
        now = TimelineChat.REOPEN_MS + TimelineChat.REFRESH_MS
        chat.at(72_000)
        advanceUntilIdle()
        assertEquals(3, loads)
        assertTrue(chat.send("🔥", ChatKind.REACTION, 71_000).isSuccess)
        assertEquals(listOf(71_000L), pinned)
        assertTrue(chat.history.value.any { it.id == "new" })
    }

    /** A server with a live session "sess": its chat hands out [pages] one per long poll. */
    private class LiveServer(val unused: MediaServer, val groups: List<NoteGroup> = listOf(NoteGroup("g1", "The Bear"))) : MediaServer by unused {
        val pages = kotlinx.coroutines.channels.Channel<ChatPage>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        var stored = listOf<TimelineNote>()
        var brought = listOf<TimelineNote>()
        val pins = mutableListOf<String?>()
        var liveSends = 0
        var loads = 0
        val brings = mutableListOf<String?>()
        override suspend fun titleNotes(itemId: String): TitleNotes { loads++; return TitleNotes(groups, stored) }
        override suspend fun pinNote(itemId: String, positionMs: Long, kind: ChatKind, text: String, sessionId: String?): TimelineNote {
            pins += sessionId
            return TimelineNote("n${pins.size}", "me", "me", itemId, positionMs, kind, text).also { stored = stored + it }
        }
        override suspend fun sendChat(groupId: String, kind: ChatKind, text: String, positionMs: Long?): ChatMessage { liveSends++; error("written twice") }
        override suspend fun chatMessages(groupId: String, after: Long, waitMs: Int): ChatPage =
            if (after < 0) ChatPage(emptyList(), 0) else pages.receive()
        override suspend fun broughtNotes(sessionId: String, itemId: String) = if (sessionId == "sess") brought else emptyList()
        override suspend fun bringNotes(sessionId: String, groupId: String?) { brings += groupId }
    }

    @Test fun inALiveSessionAMessageIsWrittenOnceAsANoteAndArrivesLiveAsThatNote() = runTest(UnconfinedTestDispatcher()) {
        val server = LiveServer(unused)
        val live = GroupChat(server, backgroundScope) { "me" }
        live.follow("sess")
        var shared = 0
        var now = 0L
        val chat = TimelineChat(server, backgroundScope, { "me" }, live, onShared = { shared++ }) { now }
        val popped = mutableListOf<TimelineNote>()
        backgroundScope.launch { chat.popups.toList(popped) }
        chat.start("ep1", 0) { true }
        advanceUntilIdle()
        // The title is shared with a group: told once.
        assertEquals(1, shared)

        assertTrue(chat.send("what a twist", ChatKind.TEXT, 30_000).isSuccess)
        // One write, with the session: never also to the live chat.
        assertEquals(listOf<String?>("sess"), server.pins)
        assertEquals(0, server.liveSends)

        // Kim's message comes live as her note: it pops up at once...
        server.pages.send(ChatPage(listOf(ChatMessage(1, "kim", "Kim", ChatKind.REACTION, "😂", 31_000, 0, noteId = "k1", itemId = "ep1")), 1))
        advanceUntilIdle()
        assertEquals(listOf("k1"), popped.map { it.id })
        // ...and the same note loaded later is that one, not a second (no second pop-up as playback passes it).
        server.stored = server.stored + TimelineNote("k1", "kim", "Kim", "ep1", 31_000, ChatKind.REACTION, "😂")
        now = TimelineChat.REOPEN_MS
        chat.opened()
        advanceUntilIdle()
        assertEquals(2, server.loads)
        (0..40L).forEach { chat.at(it * 1_000) }
        assertEquals(listOf("k1"), popped.map { it.id })
        assertEquals(1, chat.history.value.count { it.id == "k1" })
        assertEquals(1, chat.history.value.count { it.text == "what a twist" })
        // Told once per playback, however often the notes load.
        assertEquals(1, shared)
    }

    @Test fun broughtNotesPopUpInTheSessionOnly() = runTest(UnconfinedTestDispatcher()) {
        val server = LiveServer(unused, groups = emptyList())
        server.brought = listOf(TimelineNote("b1", "sam", "Sam", "ep1", 20_000, ChatKind.TEXT, "the kitchen!", brought = true))
        val live = GroupChat(server, backgroundScope) { "me" }
        live.follow("sess")
        var shared = 0
        val chat = TimelineChat(server, backgroundScope, { "me" }, live, onShared = { shared++ }) { 0 }
        val popped = mutableListOf<TimelineNote>()
        backgroundScope.launch { chat.popups.toList(popped) }
        chat.start("ep1", 0) { true }
        advanceUntilIdle()
        // Not shared with any of the viewer's groups: nothing to reload.
        assertEquals(0, shared)
        // Nothing ahead of the viewer: it pops up as playback passes it.
        assertEquals(emptyList<String>(), popped.map { it.id })
        (0..25L).forEach { chat.at(it * 1_000) }
        assertEquals(listOf("b1"), popped.map { it.id })

        // Someone brings another group's notes mid-play: the session's chat says so and they're asked for again.
        server.brought = server.brought + TimelineNote("b2", "kim", "Kim", "ep1", 40_000, ChatKind.TEXT, "told you", brought = true)
        server.pages.send(ChatPage(emptyList(), 0, brought = 1))
        advanceUntilIdle()
        (26..45L).forEach { chat.at(it * 1_000) }
        assertEquals(listOf("b1", "b2"), popped.map { it.id })
        assertTrue(chat.bring("g1").isSuccess)
        assertEquals(listOf<String?>("g1"), server.brings)

        // Outside a live session there's nothing brought (and nothing to bring into).
        val alone = TimelineChat(server, backgroundScope, { "me" }, null) { 0 }
        alone.start("ep1", 0) { true }
        advanceUntilIdle()
        assertEquals(emptyList<TimelineNote>(), alone.history.value)
        (0..45L).forEach { alone.at(it * 1_000) }
        assertEquals(emptyList<TimelineNote>(), alone.history.value.filter { it.brought })
        assertTrue(alone.bring("g1").isFailure)
    }
}
