package dev.nitrate.core.partners

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The guided setup of a new watch group: what, who, the pace, the Group Watch Session, Together. */
class GroupSetupTest {
    private val severance = MediaItem("sev", "Severance", ItemKind.SERIES)
    private val bear = MediaItem("bear", "The Bear", ItemKind.SERIES)

    @Test fun stepsOnlyGoOnWithWhatTheyNeedAndBackStepsBack() {
        var s = GroupSetup()
        assertEquals("Step 1 of 5 · What to watch", s.heading)
        assertFalse(s.canGoOn)
        assertEquals(s, s.next())
        assertNull(s.back())
        s = s.toggle(severance).toggle(bear).toggle(severance).toggle(severance)
        assertEquals(listOf("bear", "sev"), s.titles.map { it.id })
        s = s.next()
        assertEquals(GroupSetup.Step.WHO, s.step)
        assertFalse(s.canGoOn)
        // Someone listed, or a typed name: one clears the other.
        s = s.pick("tommy")
        assertTrue(s.canGoOn)
        s = s.type("suzy")
        assertNull(s.personId)
        s = s.pick("tommy")
        assertEquals("", s.typed)
        s = s.next().next().next()
        assertEquals(GroupSetup.Step.TOGETHER, s.step)
        assertTrue(s.last)
        assertEquals(s, s.next())
        assertEquals(GroupSetup.Step.SESSION, s.back()!!.step)
        s = s.copy(schedule = s.schedule.copy(together = true))
        assertEquals("The Bear and Severance · Binge, together", s.summary)
        assertEquals(GroupSchedule.TOGETHER_ON, s.schedule.togetherNote)
    }
}
