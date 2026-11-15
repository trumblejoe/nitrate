package dev.nitrate.core.companion

import dev.nitrate.core.MediaServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class GroupChatTest {
    private fun msg(seq: Long, user: String, text: String) = ChatMessage(seq, user, user, ChatKind.TEXT, text, null, 0)

    /** Answers each chat request with the next page; when they run out, the group is gone. */
    private val unused: MediaServer = java.lang.reflect.Proxy.newProxyInstance(MediaServer::class.java.classLoader, arrayOf(MediaServer::class.java)) { _, method, _ ->
        error("not used: ${method.name}")
    } as MediaServer

    /** Answers each chat request with the next page; when they run out, the group is gone. */
    private fun server(pages: MutableList<ChatPage>, sent: MutableList<String>): MediaServer = object : MediaServer by unused {
        override suspend fun chatMessages(groupId: String, after: Long, waitMs: Int): ChatPage =
            if (pages.isEmpty()) throw ChatForbidden() else pages.removeAt(0)

        override suspend fun sendChat(groupId: String, kind: ChatKind, text: String, positionMs: Long?): ChatMessage {
            sent += text
            return msg(3, "me", text)
        }
    }

    @Test fun backlogIsShownButOnlyNewMessagesFromOthersPopUp() = runTest(UnconfinedTestDispatcher()) {
        val pages = mutableListOf(
            ChatPage(listOf(msg(1, "ann", "earlier")), 1),
            ChatPage(listOf(msg(2, "me", "mine"), msg(3, "bob", "new")), 3),
        )
        val chat = GroupChat(server(pages, mutableListOf()), backgroundScope) { "me" }
        val popped = mutableListOf<ChatMessage>()
        backgroundScope.launch { chat.incoming.toList(popped) }
        chat.follow("g1")
        advanceUntilIdle()
        assertEquals(listOf("earlier", "mine", "new"), chat.messages.value.map { it.text })
        assertEquals(listOf("new"), popped.map { it.text })
        // The pages ran out: the fake says the group is gone.
        assertEquals(GroupChat.Status.NOT_ALLOWED, chat.status.value)
    }

    @Test fun sentMessagesShowAtOnceAndAreNotDoubled() = runTest(UnconfinedTestDispatcher()) {
        val sent = mutableListOf<String>()
        val pages = mutableListOf(ChatPage(listOf(msg(1, "ann", "hi"), msg(2, "ann", "hey")), 2))
        val chat = GroupChat(server(pages, sent), backgroundScope) { "me" }
        chat.follow("g1")
        advanceUntilIdle()
        assertEquals(true, chat.send("  hello  ").isSuccess)
        assertEquals(listOf("hello"), sent)
        assertEquals(listOf(1L, 2L, 3L), chat.messages.value.map { it.seq })
        assertEquals(true, chat.send("   ").exceptionOrNull() is ChatRejected)
    }

    @Test fun followingNothingClears() = runTest(UnconfinedTestDispatcher()) {
        val chat = GroupChat(server(mutableListOf(ChatPage(listOf(msg(1, "ann", "x")), 1)), mutableListOf()), backgroundScope) { "me" }
        chat.follow("g1")
        advanceUntilIdle()
        chat.follow(null)
        assertEquals(emptyList<ChatMessage>(), chat.messages.value)
        assertEquals(GroupChat.Status.OFF, chat.status.value)
        assertEquals(1_000L, GroupChat.backoffMs(1))
        assertEquals(30_000L, GroupChat.backoffMs(9))
    }
}
