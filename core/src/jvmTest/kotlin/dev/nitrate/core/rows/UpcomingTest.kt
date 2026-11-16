package dev.nitrate.core.rows

import dev.nitrate.core.model.AirDates
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.UserState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpcomingTest {
    private val day = 86_400_000L

    /** Midnight UTC on a date. */
    private fun date(y: Int, m: Int, d: Int): Long = java.time.LocalDate.of(y, m, d).toEpochDay() * day

    @Test fun longDatesReadLikeACalendar() {
        assertEquals("Monday, April 20th, 2026", AirDates.long(date(2026, 4, 20)))
        assertEquals("Sunday, November 1st, 2026", AirDates.long(date(2026, 11, 1)))
        assertEquals("Wednesday, December 2nd, 2026", AirDates.long(date(2026, 12, 2)))
        assertEquals("Wednesday, June 3rd, 2026", AirDates.long(date(2026, 6, 3)))
        assertEquals("Monday, May 11th, 2026", AirDates.long(date(2026, 5, 11)))
        assertEquals("Wednesday, August 12th, 2026", AirDates.long(date(2026, 8, 12)))
        assertEquals("Friday, March 13th, 2026", AirDates.long(date(2026, 3, 13)))
        assertEquals("Thursday, January 22nd, 2026", AirDates.long(date(2026, 1, 22)))
        assertEquals("Tuesday, February 29th, 2028", AirDates.long(date(2028, 2, 29)))
        assertEquals("Thursday, January 1st, 1970", AirDates.long(0))
        // Every day for a few years against Java's own calendar.
        var d = java.time.LocalDate.of(2024, 1, 1)
        while (d.year < 2031) {
            val text = AirDates.long(d.toEpochDay() * day)
            val weekday = d.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)
            val month = d.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)
            assertEquals(true, text.startsWith("$weekday, $month ${d.dayOfMonth}") && text.endsWith(", ${d.year}"))
            d = d.plusDays(1)
        }
    }

    private val now = date(2026, 11, 16) + 15 * 60 * 60_000L
    private fun ep(id: String, show: String, airs: Long?) = MediaItem(id, "Secret title", ItemKind.EPISODE, seriesId = show, seriesName = show, seasonNumber = 3, indexNumber = 1, premiereDateMs = airs, isMissing = true)
    private fun show(id: String) = MediaItem(id, "Show $id", ItemKind.SERIES)

    @Test fun oneCardPerWatchedShowWithItsNextDateAndNothingElse() {
        val upcoming = listOf(
            ep("a2", "a", date(2026, 12, 2)), ep("a1", "a", date(2026, 11, 20)),
            ep("b1", "b", date(2026, 11, 18)),
            ep("c1", "c", date(2026, 11, 17)), // not watched
            ep("d1", "d", null), // no date
            ep("e1", "e", date(2026, 11, 16)), // today
        )
        val shows = listOf("a", "b", "c", "d", "e").associateWith(::show)
        val row = Upcoming.row(upcoming, setOf("a", "b", "d", "e"), shows, now)
        assertEquals(listOf("e", "b", "a"), row.map { it.id })
        assertEquals(ItemKind.SERIES, row[0].kind)
        assertEquals("Friday, November 20th, 2026", row.single { it.id == "a" }.note)
        // The episode's number and title are nowhere on the card.
        assertNull(row[0].episodeLabel)
        assertEquals("Show e", row[0].name)
    }

    @Test fun watchingMeansPlayedInTheLastYearOrAFavourite() {
        val played = listOf(
            MediaItem("x", "x", ItemKind.EPISODE, seriesId = "recent", user = UserState(played = true, lastPlayedMs = now - 30 * day)),
            MediaItem("y", "y", ItemKind.EPISODE, seriesId = "old", user = UserState(played = true, lastPlayedMs = now - 400 * day)),
        )
        assertEquals(setOf("recent", "fav"), Upcoming.watching(played, listOf("fav"), now))
    }
}
