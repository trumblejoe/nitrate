package dev.nitrate.core.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsTest {
    private val timed = Lyrics(listOf(LyricLine("One", 1_000), LyricLine("Two", 4_000), LyricLine("Three", 9_000)), synced = true)

    @Test fun `the line being sung follows the song`() {
        assertNull(timed.currentIndex(500))
        assertEquals(0, timed.currentIndex(1_000))
        assertEquals(1, timed.currentIndex(8_999))
        assertEquals(2, timed.currentIndex(60_000))
    }

    @Test fun `plain lyrics have no current line`() {
        assertNull(Lyrics(listOf(LyricLine("Words")), synced = false).currentIndex(5_000))
    }
}
