package dev.nitrate.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test
    fun `one decimal matches the JVM formatter`() {
        for (v in listOf(0.0, 7.8, 7.85, 12.0, 0.04, 99.95, 120.0, -3.25, 1.234567)) {
            assertEquals("for $v", java.lang.String.format(java.util.Locale.ROOT, "%.1f", v), formatOneDecimal(v))
        }
    }

    @Test
    fun `clock and runtime`() {
        assertEquals("2:03", formatClock(123_000))
        assertEquals("1:02:03", formatClock(3_723_000))
        assertEquals("0:05", formatClock(5_000))
        assertEquals("1h 52m", formatRuntime(6_720_000))
        assertEquals("48m", formatRuntime(2_880_000))
    }
}
