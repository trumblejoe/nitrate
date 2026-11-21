package dev.nitrate.core.music

import dev.nitrate.core.model.Chapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudiobooksTest {
    private val chapters = listOf(Chapter(0, "Opening"), Chapter(600_000, "One"), Chapter(1_800_000, "Two"))

    @Test fun chaptersStepLikeSongs() {
        assertEquals(1, Audiobooks.chapterAt(chapters, 700_000))
        assertEquals(1_800_000, Audiobooks.nextChapterStart(chapters, 700_000))
        assertNull(Audiobooks.nextChapterStart(chapters, 1_900_000))
        // Well into a chapter, Previous restarts it; just into it, it goes back one.
        assertEquals(600_000, Audiobooks.previousChapterStart(chapters, 700_000))
        assertEquals(0, Audiobooks.previousChapterStart(chapters, 601_000))
        assertEquals(0, Audiobooks.previousChapterStart(chapters, 1_000))
        assertEquals(1_800_000, Audiobooks.chapterEnd(chapters, 700_000, 5_000_000))
        assertEquals(5_000_000, Audiobooks.chapterEnd(chapters, 2_000_000, 5_000_000))
    }

    @Test fun timeLeftCountsTheSpeed() {
        assertEquals("2 h 13 min left", Audiobooks.timeLeft(0, (2 * 60 + 13) * 60_000L))
        assertEquals("1 h left", Audiobooks.timeLeft(0, 2 * 3_600_000L, speed = 2f))
        assertEquals("5 min left", Audiobooks.timeLeft(0, 5 * 60_000L))
        assertEquals("1.5×", Audiobooks.speedLabel(1.5f))
        assertEquals("1×", Audiobooks.speedLabel(1f))
    }
}
