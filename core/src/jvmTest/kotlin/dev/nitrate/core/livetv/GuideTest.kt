package dev.nitrate.core.livetv

import kotlin.test.Test
import kotlin.test.assertEquals

class GuideTest {
    private val hour = 3_600_000L
    private fun p(id: String, start: Long, end: Long) = TvProgram(id, "c", id, start, end)

    @Test
    fun `cells are clipped to the window`() {
        val from = 10 * hour
        val cells = Guide.cells(listOf(p("before", 8 * hour, 9 * hour), p("a", 9 * hour, 11 * hour), p("b", 11 * hour, 12 * hour), p("c", 12 * hour, 14 * hour)), from, from + 3 * hour)
        assertEquals(listOf("a", "b", "c"), cells.map { it.program.id })
        assertEquals(0f, cells[0].startFraction)
        assertEquals(1f / 3, cells[0].widthFraction, 0.001f)
        assertEquals(2f / 3, cells[2].startFraction, 0.001f)
        assertEquals(1f / 3, cells[2].widthFraction, 0.001f)
    }

    @Test
    fun `window starts on the half hour and progress runs 0 to 1`() {
        assertEquals(10 * hour + 30 * 60_000, Guide.windowStart(10 * hour + 47 * 60_000))
        val show = p("a", 0, hour)
        assertEquals(0.5f, show.progress(hour / 2))
        assertEquals(true, show.isAiring(hour - 1))
        assertEquals(false, show.isAiring(hour))
        assertEquals("b", Guide.next(listOf(show, p("b", hour, 2 * hour), p("c", 2 * hour, 3 * hour)), show, hour / 2)?.id)
    }
}
