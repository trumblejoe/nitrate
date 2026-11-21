package dev.nitrate.core.remote

import dev.nitrate.core.admin.GeneralCommand
import dev.nitrate.core.admin.ServerSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemotePromptTest {
    @Test fun aPromptAndItsAnswerTravelAsText() {
        val p = RemotePrompt("p1", RemotePrompt.Kind.UP_NEXT, "tv", "Up next: Pilot", null, listOf(RemotePrompt.Choice("play", "Play now")))
        assertEquals(p, RemotePrompt.decode(RemotePrompt.encode(p)))
        assertEquals("p1" to "play", RemotePrompt.readAnswer(RemotePrompt.answer("p1", "play")))
        assertNull(RemotePrompt.readAnswer("garbage"))
        assertNull(RemotePrompt.decode("{"))
        assertTrue(RemotePrompt.isOurs(RemotePrompt.ANSWER))
        assertFalse(RemotePrompt.isOurs("Hello"))
    }

    @Test fun onlyADeviceTakingEveryArrowIsNavigable() {
        val base = ServerSession("s", null, null, null, null, null, null, false, null, null, null, true, true)
        assertFalse(base.navigable)
        assertTrue(base.copy(supportedCommands = GeneralCommand.NAVIGATION.map { it.wire } + "DisplayMessage").navigable)
    }
}
