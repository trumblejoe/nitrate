package dev.nitrate.core.comics

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.UserState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ComicSeriesTest {
    private fun book(id: String, name: String, parent: String = "f1", series: String? = null, index: Int? = null, user: UserState = UserState()) =
        MediaItem(id, name, ItemKind.BOOK, parentId = parent, seriesName = series, indexNumber = index, user = user)

    @Test fun numbersComeFromTheServerElseTheName() {
        assertEquals(12.0, ComicSeries.number(book("a", "Saga 012")))
        assertEquals(12.0, ComicSeries.number(book("a", "Saga #12 (2014)")))
        assertEquals(3.5, ComicSeries.number(book("a", "Saga 3.5")))
        assertEquals(7.0, ComicSeries.number(book("a", "Anything", index = 7)))
        assertNull(ComicSeries.number(book("a", "Annual")))
        assertEquals("#12", ComicSeries.label(book("a", "Saga 012")))
        assertEquals("Annual", ComicSeries.label(book("a", "Annual")))
    }

    @Test fun issuesInOrderAndWhatsNext() {
        val issues = listOf(
            book("10", "Saga 10"), book("2", "Saga 2", user = UserState(played = true)),
            book("1", "Saga 1", user = UserState(played = true)), book("3", "Saga 3"),
        )
        assertEquals(listOf("1", "2", "3", "10"), ComicSeries.order(issues).map { it.id })
        assertEquals("3", ComicSeries.next(issues)?.id)
        assertEquals("10", ComicSeries.after(issues[3], issues)?.id)
        val reading = issues.map { if (it.id == "10") it.copy(user = UserState(positionMs = 4_000)) else it }
        assertEquals("10", ComicSeries.next(reading)?.id)
    }

    @Test fun aFolderWithTwoSeriesKeepsThemApart() {
        val a = book("a1", "A 1", series = "Alpha")
        val siblings = listOf(a, book("a2", "A 2", series = "Alpha"), book("b1", "B 1", series = "Beta"))
        assertEquals(listOf("a1", "a2"), ComicSeries.of(a, siblings).map { it.id })
    }

    @Test fun continueReadingMixesHalfReadAndNextIssues() = runBlocking {
        val half = book("h", "Moon 4", parent = "moon", user = UserState(positionMs = 3_000, lastPlayedMs = 100))
        val done = book("s2", "Sun 2", parent = "sun", user = UserState(played = true, lastPlayedMs = 200))
        val sun = listOf(book("s1", "Sun 1", parent = "sun", user = UserState(played = true)), done, book("s3", "Sun 3", parent = "sun"))
        val doneMoon = book("m3", "Moon 3", parent = "moon", user = UserState(played = true, lastPlayedMs = 300))
        val row = ComicSeries.continueReading(listOf(half), listOf(doneMoon, done)) { b -> if (b.parentId == "sun") sun else emptyList() }
        // The next Sun issue (read at 200) before the half-read Moon (100); Moon only once.
        assertEquals(listOf("s3", "h"), row.map { it.id })
    }
}
