package dev.nitrate.core.partners

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The paces and their words: daily, every few days, any weekdays (every week or every other), monthly; parts per release, the first part, sequential titles. */
class SchedulePaceTest {
    private val g = GroupSchedule

    @Test fun eachPaceSaysWhenParts0pen() {
        assertEquals("Daily, 7:30pm", GroupSchedule(g.DAILY, minute = 30).paceLabel)
        assertEquals("Every 4 days, 7pm", GroupSchedule(g.EVERY_FEW_DAYS, everyDays = 4).paceLabel)
        assertEquals("Thursdays 7pm", GroupSchedule(g.WEEKLY).paceLabel)
        assertEquals("Mon, Wed and Fri 9pm", GroupSchedule(g.WEEKLY, hour = 21, weekdays = listOf(5, 1, 3)).paceLabel)
        assertEquals("Every other Thursday 7pm", GroupSchedule(g.WEEKLY, everyWeeks = 2).paceLabel)
        assertEquals("Every other week: Mon and Thu 7pm", GroupSchedule(g.WEEKLY, everyWeeks = 2, weekdays = listOf(1, 4)).paceLabel)
        assertEquals("Monthly on the 1st, 8pm", GroupSchedule(g.MONTHLY, hour = 20).paceLabel)
        assertEquals("Monthly on the 22nd, 8pm", GroupSchedule(g.MONTHLY, hour = 20, monthDay = 22).paceLabel)
        assertEquals("Daily, 7pm, 2 at a time", GroupSchedule(g.DAILY, perRelease = 2).paceLabel)
        // The older every 2 and 3 days still read as before.
        assertEquals("Every 3 days, 7pm", GroupSchedule(g.DAYS3).paceLabel)
        listOf(g.DAILY, g.EVERY_FEW_DAYS, g.WEEKLY, g.MONTHLY, g.DAYS2).forEach { assertTrue(it, GroupSchedule(it).limits) }
        assertFalse(GroupSchedule().limits)
    }

    @Test fun pickersChangeItTheSameWayOnEveryApp() {
        // Every 2 days, stored the old way, picked again as every few days keeps its count.
        assertEquals(GroupSchedule(g.EVERY_FEW_DAYS, everyDays = 2), GroupSchedule(g.DAYS2).picked(g.EVERY_FEW_DAYS))
        assertEquals(g.EVERY_FEW_DAYS, g.pickerCadence(g.DAYS3))
        // Weekdays toggle, never to none.
        val weekly = GroupSchedule(g.WEEKLY).picked(g.WEEKLY)
        assertEquals(listOf(4), weekly.days)
        assertEquals(listOf(1, 4), weekly.toggleDay(1).days)
        assertEquals(listOf(4), weekly.toggleDay(4).days)
        // Round the clock an hour at a time.
        assertEquals(0, GroupSchedule(hour = 23).hourStep(1).hour)
        assertEquals(23, GroupSchedule(hour = 0).hourStep(-1).hour)
    }

    @Test fun sequentialTitlesInTheirOrder() {
        val titles = listOf(GroupTitle("a", "Alpha"), GroupTitle("b", "Bravo"), GroupTitle("c", "Charlie"))
        val s = GroupSchedule(g.WEEKLY, sequential = true)
        assertEquals(listOf("a", "b", "c"), s.ordered(titles).map { it.id })
        val moved = s.moved(titles, "c", -2)
        assertEquals(listOf("c", "a", "b"), moved.ordered(titles).map { it.id })
        assertEquals(listOf("c", "a", "b"), moved.titleOrder)
        // What the invite says.
        assertTrue(moved.termLines.any { it.startsWith("Titles: sequential") })
        assertTrue(GroupSchedule(g.WEEKLY, firstLocked = true).termLines.contains("The first part opens on the first release"))
        assertTrue(g.ORDER_NOTE.contains("playlist"))
    }
}
