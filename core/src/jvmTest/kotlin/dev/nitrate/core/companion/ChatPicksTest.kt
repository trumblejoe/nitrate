package dev.nitrate.core.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatPicksTest {
    @Test fun aDetailsPicksGoesToOnePlaybackOnly() {
        val picks = ChatPicks()
        assertEquals(ChatPick.Off, picks.chosen("ep1"))
        picks.choose("ep1", ChatPick.Replay("c1"))
        picks.arm("ep1")
        assertEquals(ChatPick.Off, picks.chosen("ep1"))
        assertEquals(ChatPick.Replay("c1"), picks.take("ep1"))
        assertNull("taken once", picks.take("ep1"))
        assertNull("not started from a details page", picks.take("ep2"))
        // Played from the page without picking: Off.
        picks.arm("ep2")
        assertEquals(ChatPick.Off, picks.take("ep2"))
    }

    @Test fun labels() {
        val all = listOf(NoteChat("c1", createdMs = 0, people = listOf("Tommy")))
        assertEquals("Off", ChatPick.label(ChatPick.Off, all) { 0 })
        assertEquals("New chat", ChatPick.label(ChatPick.New, all) { 0 })
        assertEquals("Replay 1970/01/01 · Tommy", ChatPick.label(ChatPick.Replay("c1"), all) { 0 })
        assertEquals("Replay an earlier chat", ChatPick.label(ChatPick.Replay("gone"), all) { 0 })
    }
}
