package dev.nitrate.core.partners

import dev.nitrate.core.writing.UserRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScheduleNewsTest {
    // As the Companion's PartyPush sends it (PascalCase, dashed ids, the member's own party view first).
    private val ask = """
        {"Kind":"schedule-ask","GroupId":"0f8fad5b-d9cb-469f-a165-70867728950e","SeriesName":"The Bear & Severance","Others":["sam","kim"],
         "ByName":null,"ByMe":false,"Minutes":0,"Party":null,"ChangeId":"7c9e6679-7425-40de-944b-e07fc1f90ae7",
         "Titles":["The Bear","Severance"],"FromName":"sam","FromMe":false,
         "Proposed":{"Cadence":"weekly","Day":4,"Hour":19,"Minute":0,"SoftLock":false,"Together":true,"TimeZone":"UTC","Since":"0001-01-01T00:00:00+00:00","Limits":true,"Active":true,"Party":{"Days":[5],"Hour":21,"Minute":0}},
         "Current":null,"Outcome":null}
    """.trimIndent()

    @Test
    fun readsTheCompanionsMessage() {
        val n = assertNotNull(ScheduleNews.decode(ask))
        assertEquals(ScheduleNews.ASK, n.kind)
        assertEquals("0f8fad5b-d9cb-469f-a165-70867728950e", n.groupId)
        assertEquals("7c9e6679-7425-40de-944b-e07fc1f90ae7", n.changeId)
        assertEquals(listOf("The Bear", "Severance"), n.titles)
        assertEquals("sam", n.fromName)
        assertFalse(n.fromMe)
        assertEquals(GroupSchedule.WEEKLY, n.proposed.cadence)
        assertTrue(n.proposed.together)
        assertEquals(PartyTime(listOf(5), 21, 0), n.proposed.party)
        assertNull(n.current)
    }

    @Test
    fun partyNewsIsNotScheduleNewsAndTheOtherWayRound() {
        val party = """{"Kind":"pushed","GroupId":"0f8fad5b-d9cb-469f-a165-70867728950e","SeriesName":"The Bear","Others":["sam"],"ByName":"sam","ByMe":false,"Minutes":15,"Party":null}"""
        assertNull(ScheduleNews.decode(party))
        assertNotNull(PartyNews.decode(party))
        assertNull(ScheduleNews.decode("not json"))
        // Older apps read the schedule message as party news that asks nothing.
        val old = assertNotNull(PartyNews.decode(ask))
        assertNull(PartyPrompt.of(old))
    }

    @Test
    fun askAndOutcomeWords() {
        val n = ScheduleNews.decode(ask)!!
        val p = assertNotNull(SchedulePrompt.of(n))
        assertTrue(p.asks)
        assertEquals("sam asks to change the schedule of The Bear and Severance to Thursdays 7pm, together, Group Watch Session Fridays 9pm. Agree?", p.text)
        // Never the asker's own.
        assertNull(SchedulePrompt.of(n.copy(fromMe = true)))

        val passed = SchedulePrompt.of(n.copy(kind = ScheduleNews.DONE, outcome = ScheduleNews.PASSED, current = n.proposed))!!
        assertFalse(passed.asks)
        assertEquals("The schedule of The Bear and Severance changed to Thursdays 7pm, together, Group Watch Session Fridays 9pm.", passed.text)
        val failed = SchedulePrompt.of(n.copy(kind = ScheduleNews.DONE, outcome = ScheduleNews.FAILED))!!
        assertEquals("Not enough of the group agreed to sam's change. The schedule of The Bear and Severance stays Binge.", failed.text)
        val lapsed = SchedulePrompt.of(n.copy(kind = ScheduleNews.DONE, outcome = ScheduleNews.LAPSED, current = GroupSchedule(GroupSchedule.DAYS2, hour = 20)))!!
        assertEquals("The change to Thursdays 7pm, together, Group Watch Session Fridays 9pm lapsed after 7 days. The schedule of The Bear and Severance stays Every 2 days, 8pm.", lapsed.text)
    }

    @Test
    fun aWaitingChangeIsAskedOnlyOfThoseWhoHaventAnswered() {
        val change = ScheduleChange("c1", "Sam", GroupSchedule(GroupSchedule.WEEKLY), listOf("Sam"), emptyList(), voted = false, atMs = 0)
        val group = WatchGroup("g1", "s1", "The Bear", listOf(UserRef("u2", "Sam")), 0, pending = change)
        val a = assertNotNull(ScheduleNews.askOf(group))
        assertEquals("c1", a.changeId)
        assertEquals(listOf("The Bear"), a.titles)
        assertEquals("Sam asks to change the schedule of The Bear to Thursdays 7pm. Agree?", SchedulePrompt.of(a)!!.text)
        // Answered already (the asker always has): nothing to ask.
        assertNull(ScheduleNews.askOf(group.copy(pending = change.copy(voted = true))))
        assertNull(ScheduleNews.askOf(group.copy(pending = null)))
    }
}
