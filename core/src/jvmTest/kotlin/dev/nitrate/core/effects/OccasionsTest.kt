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
}
