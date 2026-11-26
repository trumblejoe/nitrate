package dev.nitrate.core.remote

import dev.nitrate.core.companion.ChatKind
import dev.nitrate.core.companion.ChatThread
import dev.nitrate.core.companion.ThreadGroup
import dev.nitrate.core.companion.TimelineNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteChatTest {
    private fun note(i: Int, kind: ChatKind = ChatKind.TEXT) = TimelineNote("n$i", "will", "Will", "ep1", i * 60_000L, kind, if (kind == ChatKind.REACTION) "🔥" else "line $i")

    @Test fun theTvsChatAsThePhoneSeesIt() {
        val threads = listOf(ThreadGroup("g1", "The Bear", "show", canStart = false, threads = listOf(ChatThread("now", 10, current = true), ChatThread("old", 0))))
        val history = (1..8).map { note(it) } + note(9, ChatKind.REACTION)
        val s = RemoteChatState.of("tv1", "ep1", on = true, who = "Will", replaying = null, threads = threads,
            history = history, emojis = listOf("👻"), lines = listOf("Iris!"), offsetAt = { 0 })
        assertEquals("Group chat · Will", s.line)
        assertEquals(RemoteChatState.RECENT, s.recent.size)
        assertEquals(RemoteChatState.Line("Will", "9:00", "🔥", reaction = true), s.recent.last())
        assertEquals(s, RemoteChatState.decode(RemoteChatState.encode(s)))
        assertEquals("Replaying 1970/01/01 · Will", RemoteChatState.of("tv1", "ep1", true, "Will", "old", threads, history, emptyList(), emptyList()) { 0 }.line)
        // Off: nothing of what was said goes to the phone.
        assertTrue(RemoteChatState.of("tv1", "ep1", false, "Will", null, threads, history, emptyList(), emptyList()) { 0 }.recent.isEmpty())
    }

    @Test fun actionsRoundTrip() {
        val d = RemoteChatDo(RemoteChatDo.Kind.TURN, on = false)
        assertEquals(d, RemoteChatDo.decode(RemoteChatDo.encode(d)))
        assertTrue(RemotePrompt.isOurs(RemoteChatDo.HEADER) && RemotePrompt.isOurs(RemoteChatState.STATE) && RemotePrompt.isOurs(RemoteChatState.GONE))
    }
}
