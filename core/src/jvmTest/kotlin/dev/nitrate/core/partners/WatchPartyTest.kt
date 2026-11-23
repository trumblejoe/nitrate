package dev.nitrate.core.partners

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.writing.UserRef
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchPartyTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    // Thursday 19 November 2026.
    private val thursday8pm = ms("2026-11-19T20:00:00Z")

    @Test
    fun partyTimeLabels() {
        assertEquals("Thursdays 8pm", PartyTime(listOf(4)).label)
        assertEquals("Mondays and Thursdays 8:30pm", PartyTime(listOf(4, 1), 20, 30).label)
        assertEquals("Every day 7pm", PartyTime((0..6).toList(), 19).label)
        val s = GroupSchedule(GroupSchedule.WEEKLY, day = 4, hour = 19, party = PartyTime(listOf(5), 21))
        assertEquals("Thursdays 7pm, Group Watch Session Fridays 9pm", s.label)
        assertEquals("Group Watch Session Fridays 9pm", s.partyLabel)
        assertTrue(GroupSchedule(party = PartyTime(listOf(5))).kept)
        assertFalse(GroupSchedule(party = PartyTime(listOf(5))).active)
        assertTrue(GroupSchedule(party = PartyTime(listOf(5))).terms.contains("Group Watch Session Fridays 8pm"))
        assertEquals("", GroupSchedule().terms)
    }

    @Test
    fun nextOccurrenceAcrossWeekdaysAndOffset() {
        val p = PartyTime(listOf(1, 4), 20)
        // From Thursday noon UTC: tonight 8pm UTC; from just after it, Monday.
        assertEquals(thursday8pm, p.nextAfter(ms("2026-11-19T12:00:00Z"), 0))
        assertEquals(ms("2026-11-23T20:00:00Z"), p.nextAfter(thursday8pm, 0))
        // New York in winter (UTC−5): Thursday 8pm there is 01:00 UTC on Friday.
        assertEquals(ms("2026-11-20T01:00:00Z"), p.nextAfter(ms("2026-11-19T12:00:00Z"), -300))
        // East of UTC (+9): Thursday 8pm there was 11:00 UTC; from noon UTC it's Monday's.
        assertEquals(ms("2026-11-23T11:00:00Z"), p.nextAfter(ms("2026-11-19T12:00:00Z"), 540))
        assertNull(PartyTime(emptyList()).nextAfter(0, 0))
    }

    @Test
    fun reminderTimes() {
        val party = WatchParty("g", thursday8pm, thursday8pm + 15 * 60_000, nextAtMs = thursday8pm + 7 * PartyTime.DAY_MS)
        assertEquals(thursday8pm, party.reminderAtMs)
        assertEquals(thursday8pm, party.nextReminderAt(ms("2026-11-19T18:00:00Z")))
        // Past it, or not going: next week's.
        assertEquals(thursday8pm + 7 * PartyTime.DAY_MS - WatchParty.LEAD_MS, party.nextReminderAt(thursday8pm + 1))
        assertEquals(thursday8pm + 7 * PartyTime.DAY_MS - WatchParty.LEAD_MS, party.copy(skipped = true).nextReminderAt(0))
        assertEquals(thursday8pm + 7 * PartyTime.DAY_MS - WatchParty.LEAD_MS, party.copy(iCantMake = true).nextReminderAt(0))
        assertEquals("8:15", party.clock)
        assertEquals("8:15", party.copy(startsAtMs = ms("2026-11-20T01:15:00Z"), offsetMinutes = -300).clock)
        assertTrue(party.joinable(thursday8pm))
        assertFalse(party.joinable(ms("2026-11-19T18:00:00Z")))
        assertEquals("Tonight's Group Watch Session: 8:15 · S1:E4", party.copy(partLabel = "S1:E4").line(ms("2026-11-19T18:00:00Z")))
        assertEquals("Next Group Watch Session: Thursday 8:15", party.line(ms("2026-11-16T18:00:00Z")))
        assertEquals("Tonight's Group Watch Session is off", party.copy(skipped = true).line(ms("2026-11-19T18:00:00Z")))
    }

    @Test
    fun readsTheCompanionsMessage() {
        val text = """
            {"Kind":"pushed","GroupId":"8f0c7a6e1d2b4c3a9e8f7d6c5b4a3920","SeriesName":"Bear","Others":["Tommy","Suzy"],"ByName":"Suzy","ByMe":false,"Minutes":15,
             "Party":{"GroupId":"8f0c7a6e-1d2b-4c3a-9e8f-7d6c5b4a3920","At":"2026-11-19T20:00:00+00:00","StartsAt":"2026-11-19T15:15:00-05:00",
               "PartId":"11111111-2222-3333-4444-555555555555","PartLabel":"S1:E4","Pushes":[{"Name":"Suzy","Minutes":15,"Mine":false}],
               "CanPushBack":true,"Request":null,"CantMake":[],"ICantMake":false,"Skipped":false,"Started":false,"StartedBy":null,"SessionId":null,
               "NextAt":"2026-11-26T20:00:00+00:00","OffsetMinutes":0,"Extra":1}}
        """.trimIndent()
        val news = PartyNews.decode(text)!!
        assertEquals(PartyNews.PUSHED, news.kind)
        assertEquals("8f0c7a6e-1d2b-4c3a-9e8f-7d6c5b4a3920", news.groupId)
        assertEquals(listOf("Tommy", "Suzy"), news.others)
        val p = news.party!!
        assertEquals(ms("2026-11-19T20:15:00Z"), p.startsAtMs)
        assertEquals("S1:E4", p.partLabel)
        assertEquals(listOf(PartyDelay("Suzy", 15, false)), p.delays)
        assertNull(PartyNews.decode("Hello"))
    }

    private fun news(kind: String, party: WatchParty, byMe: Boolean = false, minutes: Int = 0) =
        PartyNews(kind, "g", "Bear", listOf("Tommy", "Suzy"), "Suzy", byMe, minutes, party)

    @Test
    fun promptsSayWhatTheyShould() {
        val p = WatchParty("g", thursday8pm, thursday8pm, partLabel = "S1:E4")
        val reminder = PartyPrompt.of(news(PartyNews.REMINDER, p))!!
        assertEquals("Group Watch Session with Tommy & Suzy · 8:00 · S1:E4", reminder.text)
        assertEquals(listOf("Join", "+15 min", "+30 min", "+60 min", "Not tonight", "Can't make it"), reminder.choices.map { it.label })
        assertEquals(listOf("Join", "Not tonight"), PartyPrompt.of(news(PartyNews.REMINDER, p), easy = true)!!.choices.map { it.label })
        // +15 used: not offered again.
        assertFalse(PartyPrompt.of(news(PartyNews.REMINDER, p.copy(canPushBack = false)))!!.choices.contains(PartyChoice.Later(15)))
        // Not going: no reminder.
        assertNull(PartyPrompt.of(news(PartyNews.REMINDER, p.copy(skipped = true))))

        val pushed = PartyPrompt.of(news(PartyNews.PUSHED, p.copy(startsAtMs = thursday8pm + 15 * 60_000), minutes = 15))!!
        assertEquals("Suzy pushed it back 15 min (8:15)", pushed.text)
        assertNull(PartyPrompt.of(news(PartyNews.PUSHED, p, byMe = true, minutes = 15)))

        val ask = PartyAsk("r", "Suzy", PartyAsk.LATER, 30, listOf("Suzy"), emptyList(), voted = false, mine = false)
        val asked = PartyPrompt.of(news(PartyNews.ASKED, p.copy(ask = ask)))!!
        assertEquals("Suzy postponed 30 minutes. Agree?", asked.text)
        assertEquals(listOf(PartyChoice.Vote("r", true), PartyChoice.Vote("r", false)), asked.choices)
        assertNull(PartyPrompt.of(news(PartyNews.ASKED, p.copy(ask = ask.copy(voted = true)))))
        // While someone else's request waits, only they can ask: the reminder offers the vote instead.
        val withAsk = PartyPrompt.of(news(PartyNews.REMINDER, p.copy(ask = ask)))!!
        assertEquals(listOf("Join", "+15 min", "Can't make it", "Yes", "No"), withAsk.choices.map { it.label })

        val without = PartyPrompt.of(news(PartyNews.WITHOUT, p.copy(cantMake = listOf("Suzy"))))!!
        assertEquals("Suzy can't tonight. Start the Group Watch Session without them? Suzy will fall behind.", without.text)
        assertEquals(listOf(PartyChoice.Join, PartyChoice.NotTonight), without.choices)
        assertNull(PartyPrompt.of(news(PartyNews.WITHOUT, p.copy(cantMake = listOf("Suzy"), iCantMake = true))))
        assertNull(PartyPrompt.of(news(PartyNews.CHANGED, p)))
        assertEquals("Tommy, Suzy & Kim", PartyPrompt.names(listOf("Tommy", "Suzy", "Kim")))
    }

    @Test
    fun cardChoicesShowTheParty() {
        val party = WatchParty("g1", thursday8pm, thursday8pm + 15 * 60_000, partLabel = "S1:E4")
        val group = WatchGroup("g1", "show", "Bear", listOf(UserRef("u2", "Tommy")), 0, GroupSchedule(party = PartyTime(listOf(4))), party = party)
        val info = PartnersInfo(listOf(group), emptyList(), emptyList())
        val show = MediaItem("show", "Bear", ItemKind.SERIES)
        val choices = TogetherRow.choices(show, info, emptyMap(), emptyList(), canWatchNow = false, ownSpoilers = emptySet(), nowMs = ms("2026-11-19T18:00:00Z"))
        val c = choices.filterIsInstance<TogetherChoice.Party>().single()
        assertEquals("Tonight's Group Watch Session: 8:15 · S1:E4 · Postpone… · Not tonight", c.label)
    }
}
