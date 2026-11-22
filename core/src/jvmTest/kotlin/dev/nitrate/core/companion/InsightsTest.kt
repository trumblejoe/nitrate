package dev.nitrate.core.companion

import dev.nitrate.core.model.MediaSegment
import dev.nitrate.core.model.SegmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightsTest {
    private val day = 86_400_000L

    private fun sitting(startMs: Long, watchedMs: Long) = Sitting("i", "Movie", "Film", null, startMs, startMs + watchedMs, watchedMs)

    @Test fun watchTimeAddsUpStreaksAndWeekdays() {
        // 1 Jan 2026 is a Thursday: watched Thu, Fri, Sat, then the next Thursday.
        val jan1 = 20_454 * day
        val w = WatchTime.of(listOf(sitting(jan1 + 3_600_000, 3_600_000), sitting(jan1 + day, 1_800_000), sitting(jan1 + 2 * day, 600_000), sitting(jan1 + 7 * day, 7_200_000)), 0)
        assertEquals(4, w.sittings)
        assertEquals(3, w.longestStreak)
        assertEquals(4, w.busiestWeekday) // Thursday
        assertEquals(setOf(1), w.byMonth.keys)
        assertEquals(13_200_000L, w.totalMs)
        assertTrue(WatchTime.line(w).contains("mostly Thursdays"))
    }

    @Test fun watchTimeByYearAndTimeZone() {
        val jan1 = 20_454 * day
        // 23:30 UTC on 31 Dec 2025 is 1 Jan 2026 an hour ahead.
        val s = listOf(sitting(jan1 - 1_800_000, 60_000))
        assertNull(WatchTime.forYear(s, 2026, 0))
        assertEquals(1, WatchTime.forYear(s, 2026, 3_600_000)?.sittings)
        assertEquals(1, WatchTime.forYear(s, null, 0)?.sittings)
        assertEquals(12, WatchTime.monthOf(jan1 / day - 1))
    }

    @Test fun skipThenGoingBackSaysItWasWrong() {
        val reported = mutableListOf<SegmentPart>()
        val w = SkipWatch { _, part -> reported += part }
        val intro = MediaSegment(SegmentKind.INTRO, 60_000, 120_000)
        w.skipped("a", intro, 1_000)
        w.position("a", 125_000, 2_000)
        w.position("a", 70_000, 8_000) // back into the intro
        assertEquals(listOf(SegmentPart.INTRO), reported)
        // Only once per skip, and never after the window or for another title.
        w.position("a", 70_000, 9_000)
        w.skipped("a", intro, 10_000)
        w.position("a", 70_000, 10_000 + SkipWatch.WINDOW_MS + 1)
        w.skipped("a", intro, 40_000)
        w.position("b", 70_000, 41_000)
        // Landing in the last few seconds isn't a complaint.
        w.skipped("a", intro, 50_000)
        w.position("a", 118_000, 51_000)
        assertEquals(1, reported.size)
    }

    @Test fun trendsCompareWeeks() {
        val days = (1..14).map { QueryTrendDay("d$it", 10, if (it <= 7) 200 else 100, 400) }
        val t = QueryTrend("GET /Items", days)
        assertEquals(-50, t.change())
        assertTrue(t.line(), t.line().contains("50% faster"))
        assertNull(QueryTrend("x", days.take(1)).change())
    }

    @Test fun clocks() {
        assertEquals("0:05", Moments.clock(5_000))
        assertEquals("12:34", Moments.clock(754_000))
        assertEquals("1:02:03", Moments.clock(3_723_000))
        val m = Moment("1", "u2", "Sam", "i", 754_000, "the shot", null, 0)
        assertEquals("12:34 · the shot · Sam", Moments.line(m, "u1"))
        assertEquals("12:34 · the shot", Moments.line(m, "u2"))
    }
}
