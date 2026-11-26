package dev.nitrate.core.partners

import dev.nitrate.core.writing.UserRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A Group Watch Session as the phone's remote shows it while the TV hosts it. */
class SessionStatusTest {
    private val group = WatchGroup("g1", "show", "The Bear", listOf(UserRef("u2", "Suzy"), UserRef("u3", "Kim")), 0)

    private fun session(state: String, vararg others: Pair<String, String>, mine: Boolean = true, leftMs: Long = 12 * 60_000L) = GroupWatchSession(
        "s1", "g1", "e4", "S1:E4", "The Bear", "Me", mine, state, null, 0, 0, false, 1_000_000 + leftMs,
        listOf(SessionMember("me", "Me", true, 0)) + others.map { (id, name) -> SessionMember(id, name, false, 0) }, true, 1_000_000,
    )

    @Test fun gatheringSaysWhoItWaitsForWhoIsInAndWhenItStartsByItself() {
        val s = session(GroupWatchSession.WAITING, "u3" to "Kim")
        assertEquals(listOf("Suzy"), SessionStatus.waitingFor(s, group))
        assertEquals("Waiting for Suzy · Kim is in · starts by itself in 12 min", SessionStatus.line(s, group))
        assertTrue(SessionStatus.canStartNow(s))
        assertEquals("Everyone's in · starts by itself in a moment",
            SessionStatus.line(session(GroupWatchSession.WAITING, "u2" to "Suzy", "u3" to "Kim", leftMs = 20_000), group))
    }

    @Test fun playingSaysWhoIsWatchingAndOnlyTheStarterManagesIt() {
        assertEquals("Watching with Kim", SessionStatus.line(session(GroupWatchSession.PLAYING, "u3" to "Kim"), group))
        assertEquals("Playing · nobody else has joined", SessionStatus.line(session(GroupWatchSession.PLAYING), group))
        val theirs = session(GroupWatchSession.WAITING, mine = false)
        assertFalse(SessionStatus.canStartNow(theirs))
        assertFalse(SessionStatus.canEnd(theirs))
        assertFalse(SessionStatus.canStartNow(session(GroupWatchSession.PLAYING)))
        assertTrue(SessionStatus.canEnd(session(GroupWatchSession.PLAYING)))
    }
}
