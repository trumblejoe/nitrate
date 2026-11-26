package dev.nitrate.core.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatPicksTest {
    @Test fun aDetailsPicksGoesToOnePlaybackOnly() {
        val picks = ChatPicks()
        // Until picked, the page shows the user's setting.
        assertEquals(ChatPick.On, picks.chosen("ep1", ChatPick.default(chatOn = true)))
        assertEquals(ChatPick.Off, picks.chosen("ep1", ChatPick.default(chatOn = false)))
        picks.choose("ep1", ChatPick.Replay("t1"))
        picks.arm("ep1")
        assertEquals(ChatPick.On, picks.chosen("ep1"))
        assertEquals(ChatPick.Replay("t1"), picks.take("ep1"))
        assertNull("taken once", picks.take("ep1"))
        assertNull("not started from a details page", picks.take("ep2"))
        // Played from the page without picking: the setting decides, as for any playback.
        picks.arm("ep2")
        assertNull(picks.take("ep2"))
    }

    @Test fun labels() {
        val groups = listOf(ThreadGroup("g1", "The Bear", "show", false, listOf(ChatThread("t1", 0))))
        assertEquals("Off", ChatPick.label(ChatPick.Off, groups) { 0 })
        assertEquals("On", ChatPick.label(ChatPick.On, groups) { 0 })
        assertEquals("Replay 1970/01/01", ChatPick.label(ChatPick.Replay("t1"), groups) { 0 })
        assertEquals("Replay an earlier thread", ChatPick.label(ChatPick.Replay("gone"), groups) { 0 })
    }
}
