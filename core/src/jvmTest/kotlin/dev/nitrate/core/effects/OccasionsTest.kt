package dev.nitrate.core.effects

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OccasionsTest {
    @Test fun `Easter by the computus`() {
        assertEquals(Day(2024, 3, 31), Occasions.easter(2024))
        assertEquals(Day(2025, 4, 20), Occasions.easter(2025))
        assertEquals(Day(2026, 4, 5), Occasions.easter(2026))
        assertEquals(Day(2027, 3, 28), Occasions.easter(2027))
    }

    @Test fun `days of the week and moving holidays`() {
        assertEquals(3, Day(2026, 11, 18).dayOfWeek) // a Wednesday
        assertEquals(Day(2026, 11, 26), Occasions.nthWeekday(2026, 11, 4, 4)) // Thanksgiving
        assertEquals(Day(2026, 9, 7), Occasions.nthWeekday(2026, 9, 1, 1)) // Labor Day
        assertEquals(Day(2000, 2, 29), Day.fromEpochDay(Day(2000, 2, 29).epochDay))
        assertEquals(Day(2026, 3, 1), Day(2026, 2, 28).plus(1))
    }

    @Test fun `the most specific occasion wins`() {
        val prefs = EffectsPrefs()
        assertEquals(Occasion.FESTIVUS, Occasions.active(Day(2026, 12, 23), prefs))
        assertEquals(Occasion.CHRISTMAS, Occasions.active(Day(2026, 12, 25), prefs))
        assertEquals(Occasion.WINTER, Occasions.active(Day(2026, 12, 5), prefs))
        assertEquals(Occasion.NEW_YEAR, Occasions.active(Day(2027, 1, 1), prefs))
        assertEquals(Occasion.WINTER, Occasions.active(Day(2027, 2, 2), prefs))
        assertEquals(Occasion.EASTER, Occasions.active(Day(2026, 4, 3), prefs)) // Good Friday
        assertEquals(Occasion.LABOR_DAY, Occasions.active(Day(2026, 9, 5), prefs)) // the Saturday before
        assertEquals(Occasion.MARINE_CORPS_BIRTHDAY, Occasions.active(Day(2026, 11, 10), prefs))
        assertNull(Occasions.active(Day(2026, 8, 1), prefs))
    }

    @Test fun `switched off occasions and the whole calendar`() {
        val noFestivus = EffectsPrefs().with(Occasion.FESTIVUS, false)
        assertEquals(Occasion.CHRISTMAS, Occasions.active(Day(2026, 12, 23), noFestivus))
        assertNull(Occasions.active(Day(2026, 12, 23), EffectsPrefs(seasonal = false)))
        assertNull(Occasions.active(Day(2026, 12, 23), EffectsPrefs(on = false)))
    }

    @Test fun `next start`() {
        assertEquals(Day(2026, 12, 18), Occasions.next(Occasion.CHRISTMAS, Day(2026, 11, 18)))
        assertEquals(Day(2027, 3, 26), Occasions.next(Occasion.EASTER, Day(2026, 11, 18)))
        assertEquals(Day(2026, 12, 1), Occasions.next(Occasion.WINTER, Day(2026, 11, 18)))
        assertEquals(Day(2026, 12, 20), Occasions.next(Occasion.CHRISTMAS, Day(2026, 12, 20)))
    }

    @Test fun `own dates replace the usual ones`() {
        // Christmas only from the 24th to the 26th.
        val prefs = EffectsPrefs().withDates(Occasion.CHRISTMAS, OccasionDates(MonthDay(12, 24), MonthDay(12, 26)))
        assertEquals(Occasion.WINTER, Occasions.active(Day(2026, 12, 20), prefs))
        assertEquals(Occasion.CHRISTMAS, Occasions.active(Day(2026, 12, 24), prefs))
        assertEquals(Day(2026, 12, 24), Occasions.next(Occasion.CHRISTMAS, Day(2026, 11, 18), prefs.datesOf(Occasion.CHRISTMAS)))
    }

    @Test fun `own dates can run on past New Year`() {
        val dates = OccasionDates(MonthDay(12, 20), MonthDay(1, 6))
        val prefs = EffectsPrefs().with(Occasion.FESTIVUS, false).with(Occasion.NEW_YEAR, false).withDates(Occasion.CHRISTMAS, dates)
        assertEquals(Occasion.CHRISTMAS, Occasions.active(Day(2027, 1, 5), prefs))
        assertEquals(Occasion.WINTER, Occasions.active(Day(2027, 1, 7), prefs))
        assertEquals(Day(2026, 12, 20) to Day(2027, 1, 6), Occasions.current(Occasion.CHRISTMAS, Day(2027, 1, 2), dates))
    }

    @Test fun `usual dates stretched, moving holidays included`() {
        // Easter 2026 is 5 April: usually Good Friday (3rd) to Easter Monday (6th), here five days sooner and a day longer.
        val dates = OccasionDates(earlier = 5, later = 1)
        assertEquals(Day(2026, 3, 29) to Day(2026, 4, 7), Occasions.current(Occasion.EASTER, Day(2026, 3, 1), dates))
        val prefs = EffectsPrefs().withDates(Occasion.EASTER, dates)
        assertEquals(Occasion.EASTER, Occasions.active(Day(2026, 3, 30), prefs))
        assertNull(Occasions.active(Day(2026, 3, 30), EffectsPrefs()))
    }

    @Test fun `back to the usual dates`() {
        val custom = EffectsPrefs().withDates(Occasion.HALLOWEEN, OccasionDates(MonthDay(10, 1), MonthDay(10, 31)))
        assertEquals(EffectsPrefs(), custom.withDates(Occasion.HALLOWEEN, null))
        assertEquals(EffectsPrefs(), EffectsPrefs().withDates(Occasion.HALLOWEEN, OccasionDates()))
        // Winter's December and the following January read as one stretch.
        assertEquals(Day(2026, 12, 1) to Day(2027, 2, 28), Occasions.current(Occasion.WINTER, Day(2026, 12, 15)))
    }

    @Test fun `days of the year go round`() {
        assertEquals(MonthDay(1, 1), MonthDay(12, 31).plus(1))
        assertEquals(MonthDay(12, 31), MonthDay(1, 1).plus(-1))
        assertEquals(MonthDay(2, 28), MonthDay(1, 31).plusMonths(1))
        assertEquals(MonthDay(12, 15), MonthDay(1, 15).plusMonths(-1))
        assertEquals(Day(2028, 2, 29), MonthDay(2, 29).inYear(2028))
        assertEquals(Day(2027, 2, 28), MonthDay(2, 29).inYear(2027))
    }
}
