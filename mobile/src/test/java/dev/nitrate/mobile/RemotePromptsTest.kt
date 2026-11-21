package dev.nitrate.mobile

import dev.nitrate.android.remote.RemoteControlHub
import dev.nitrate.core.MediaServer
import dev.nitrate.core.admin.ServerSession
import dev.nitrate.core.events.ServerEvent
import dev.nitrate.core.remote.RemotePrompt
import dev.nitrate.testing.FakeServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** The TV's questions reach the user's phones, never show as messages, and a phone's answer comes back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemotePromptsTest {
    private class Server : MediaServer by FakeServer() {
        val sent = mutableListOf<Triple<String, String, String>>()
        val events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 16)
        override val userId = "user-1"
        override val deviceId: String = "tv-1"
        override suspend fun controllableSessions() = listOf(
            ServerSession("phone", "Joe", RemotePrompt.PHONE_CLIENT, "Pixel", "phone-1", null, null, false, null, null, null, true, true, userId = "user-1"),
            ServerSession("tv", "Joe", "Nitrate", "Living room TV", "tv-1", null, null, false, null, null, null, true, true, userId = "user-1"),
            ServerSession("web", "Joe", "Jellyfin Web", "Firefox", "web-1", null, null, false, null, null, null, true, true, userId = "user-1"),
            ServerSession("other", "Sam", RemotePrompt.PHONE_CLIENT, "Sam's phone", "phone-2", null, null, false, null, null, null, true, true, userId = "user-2"),
        )
        override suspend fun sendMessage(sessionId: String, header: String, text: String) { sent += Triple(sessionId, header, text) }
        override fun events() = events
    }

    private val prompt = RemotePrompt("p1", RemotePrompt.Kind.UP_NEXT, "tv-1", "Up next: Pilot", null, listOf(RemotePrompt.Choice("play", "Play now")))

    @Test fun onlyTheUsersOwnPhonesAreAskedAndAnswersComeBack() = runBlocking {
        val server = Server()
        val hub = RemoteControlHub(server)
        hub.offer(prompt)
        assertEquals(listOf("phone"), server.sent.map { it.first })
        assertEquals(RemotePrompt.PROMPT, server.sent.single().second)

        // On the TV: the phone's answer arrives as a message, which is never passed on to be shown.
        hub.start()
        val scope = CoroutineScope(Dispatchers.Main.immediate)
        val shown = mutableListOf<ServerEvent>()
        val watching = scope.launch(start = CoroutineStart.UNDISPATCHED) { hub.events.collect { shown += it } }
        val answer = scope.async(start = CoroutineStart.UNDISPATCHED) { hub.answers.first() }
        server.events.emit(ServerEvent.General("DisplayMessage", mapOf("Header" to RemotePrompt.ANSWER, "Text" to RemotePrompt.answer("p1", "play"))))
        ShadowLooper.idleMainLooper()
        assertEquals("p1" to "play", withTimeout(2_000) { answer.await() })
        assertTrue(shown.none { it is ServerEvent.General && it.name == "DisplayMessage" })
        watching.cancel()
        hub.stop()
    }

    @Test fun aPhoneKeepsWhatItsTvAsksUntilItGoes() = runBlocking {
        val server = Server()
        val hub = RemoteControlHub(server)
        hub.start()
        server.events.emit(ServerEvent.General("DisplayMessage", mapOf("Header" to RemotePrompt.PROMPT, "Text" to RemotePrompt.encode(prompt))))
        ShadowLooper.idleMainLooper()
        assertEquals(prompt, hub.promptFrom("tv-1", dev.nitrate.android.nowMs()))
        // Not heard again for a long while: forgotten.
        assertNull(hub.promptFrom("tv-1", dev.nitrate.android.nowMs() + RemotePrompt.FORGET_MS + 1))
        server.events.emit(ServerEvent.General("DisplayMessage", mapOf("Header" to RemotePrompt.PROMPT_GONE, "Text" to "p1")))
        ShadowLooper.idleMainLooper()
        assertNull(hub.promptFrom("tv-1", dev.nitrate.android.nowMs()))
        hub.stop()
    }

}
