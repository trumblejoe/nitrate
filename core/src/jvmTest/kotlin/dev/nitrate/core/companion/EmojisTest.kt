package dev.nitrate.core.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojisTest {
    @Test fun theTitlesOwnComeFirstAndNothingRepeats() {
        val picks = QuickPicks(listOf("👻", "🔍") + QUICK_REACTIONS, emptyList())
        val groups = Emojis.groupsFor(picks)
        assertEquals("For this", groups.first().name)
        assertEquals(picks.emojis, groups.first().emojis)
        val all = groups.flatMap { it.emojis }
        assertEquals(all.size, all.toSet().size)
        assertTrue(groups.drop(1).none { "👻" in it.emojis || "🍿" in it.emojis })
        assertTrue(all.size > 100)
    }
}
