package dev.nitrate.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SecondSubtitlesTest {
    private val srt = """
        1
        00:00:01,000 --> 00:00:03,500
        <i>Hola,</i> ¿qué tal?

        2
        00:00:04,000 --> 00:00:06,000
        {\an8}Muy bien.
        Gracias.

        3
        00:00:05,500 --> 00:00:07,000
        (música)
    """.trimIndent()

    @Test fun readsSrt() {
        val cues = SecondSubtitles.parse(srt)
        assertEquals(3, cues.size)
        assertEquals(Cue(1_000, 3_500, "Hola, ¿qué tal?"), cues[0])
        assertEquals("Muy bien.\nGracias.", cues[1].text)
    }

    @Test fun readsVtt() {
        val cues = SecondSubtitles.parse("WEBVTT\n\n00:01.000 --> 00:02.000 align:start\nHi\n\n01:00:00.000 --> 01:00:01.500\nLater\n")
        assertEquals(listOf(Cue(1_000, 2_000, "Hi"), Cue(3_600_000, 3_601_500, "Later")), cues)
    }

    @Test fun whatsOnScreen() {
        val cues = SecondSubtitles.parse(srt)
        assertNull(SecondSubtitles.at(cues, 500))
        assertEquals("Hola, ¿qué tal?", SecondSubtitles.at(cues, 2_000))
        assertNull(SecondSubtitles.at(cues, 3_800))
        assertEquals("Muy bien.\nGracias.\n(música)", SecondSubtitles.at(cues, 5_700))
        assertEquals("(música)", SecondSubtitles.at(cues, 6_500))
        assertNull(SecondSubtitles.at(cues, 9_000))
    }
}
