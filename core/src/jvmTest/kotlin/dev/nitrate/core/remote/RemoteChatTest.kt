package dev.nitrate.core.remote

import dev.nitrate.core.companion.ChatKind
import dev.nitrate.core.companion.NoteChat
import dev.nitrate.core.companion.TimelineNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteChatTest {
    private fun note(i: Int, kind: ChatKind = ChatKind.TEXT) = TimelineNote("n$i", "will", "Will", "ep1", i * 60_000L, kind, if (kind == ChatKind.REACTION) "🔥" else "line $i")

    @Test fun theTvsChatAsThePhoneSeesIt() {
        val chats = listOf(NoteChat("tue", "g1", 0, people = listOf("Will"), count = 14))
        val history = (1..8).map { note(it) } + note(9, ChatKind.REACTION)
        val s = RemoteChatState.of("tv1", "ep1", on = true, who = "Will", hasChats = true, playing = null, offered = chats, all = chats,
            history = history, emojis = listOf("👻"), lines = listOf("Iris!"), offsetAt = { 0 })
        assertEquals("New chat · Will", s.line)
        assertEquals(RemoteChatState.RECENT, s.recent.size)
        assertEquals(RemoteChatState.Line("Will", "9:00", "🔥", reaction = true), s.recent.last())
        assertEquals("1970/01/01 · Will", s.chats.single().title)
        assertEquals(s, RemoteChatState.decode(RemoteChatState.encode(s)))
        // Off: nothing of what was said goes to the phone.
        assertTrue(RemoteChatState.of("tv1", "ep1", false, "Will", true, null, chats, chats, history, emptyList(), emptyList()) { 0 }.recent.isEmpty())
    }

    @Test fun actionsRoundTrip() {
        val d = RemoteChatDo(RemoteChatDo.Kind.PLAY, chatId = "tue")
        assertEquals(d, RemoteChatDo.decode(RemoteChatDo.encode(d)))
        assertEquals(RemoteChatDo(RemoteChatDo.Kind.PLAY), RemoteChatDo.decode(RemoteChatDo.encode(RemoteChatDo(RemoteChatDo.Kind.PLAY))))
        assertTrue(RemotePrompt.isOurs(RemoteChatDo.HEADER) && RemotePrompt.isOurs(RemoteChatState.STATE) && RemotePrompt.isOurs(RemoteChatState.GONE))
    }
}
