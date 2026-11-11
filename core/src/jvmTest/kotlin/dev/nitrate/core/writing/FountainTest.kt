package dev.nitrate.core.writing

import dev.nitrate.core.writing.Element.ACTION
import dev.nitrate.core.writing.Element.BLANK
import dev.nitrate.core.writing.Element.BONEYARD
import dev.nitrate.core.writing.Element.CHARACTER
import dev.nitrate.core.writing.Element.DIALOGUE
import dev.nitrate.core.writing.Element.NOTE
import dev.nitrate.core.writing.Element.PARENTHETICAL
import dev.nitrate.core.writing.Element.SCENE_HEADING
import dev.nitrate.core.writing.Element.TITLE_PAGE
import dev.nitrate.core.writing.Element.TRANSITION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FountainTest {
    @Test
    fun `classifies a typical scene`() {
        val script = """
            Title: Nitrate
            Author: Someone

            INT. PROJECTION BOOTH - NIGHT

            Film rattles through the gate.

            MAYA (V.O.)
            (quietly)
            It's catching fire.

            CUT TO:

            ext. street - continuous
        """.trimIndent()
        assertEquals(
            listOf(
                TITLE_PAGE, TITLE_PAGE, BLANK,
                SCENE_HEADING, BLANK,
                ACTION, BLANK,
                CHARACTER, PARENTHETICAL, DIALOGUE, BLANK,
                TRANSITION, BLANK,
                SCENE_HEADING,
            ),
            Fountain.classify(script),
        )
    }

    @Test
    fun `forced elements override context`() {
        assertEquals(listOf(SCENE_HEADING), Fountain.classify(".FLASHBACK"))
        assertEquals(listOf(CHARACTER, DIALOGUE), Fountain.classify("@McCLANE\nYippee."))
        assertEquals(listOf(ACTION), Fountain.classify("!BOOM"))
        assertEquals(listOf(TRANSITION), Fountain.classify(">FADE OUT."))
    }

    @Test
    fun `an all-caps line without dialogue after it is action, not a character`() {
        assertEquals(listOf(BLANK, ACTION, BLANK), Fountain.classify("\nSILENCE\n"))
    }

    @Test
    fun `notes and boneyard are recognised`() {
        assertEquals(listOf(NOTE, BONEYARD, BONEYARD, BONEYARD, ACTION), Fountain.classify("[[fix this]]\n/* cut\nall of\nthis */\nKept."))
    }

    @Test
    fun `element shortcuts rewrite a line`() {
        assertEquals("INT. KITCHEN", Fountain.convertLine("int. kitchen", SCENE_HEADING))
        assertEquals(".ROOFTOP", Fountain.convertLine("rooftop", SCENE_HEADING))
        assertEquals("MAYA", Fountain.convertLine("maya", CHARACTER))
        assertEquals("(beat)", Fountain.convertLine("beat", PARENTHETICAL))
        assertEquals("SMASH CUT TO:", Fountain.convertLine("smash cut to:", TRANSITION))
        assertEquals(">FADE OUT.", Fountain.convertLine("fade out.", TRANSITION))
        assertEquals("It's quiet.", Fountain.convertLine("(It's quiet.)", ACTION))
    }

    @Test
    fun `page estimate grows with content and ignores notes`() {
        val action = List(55) { "A line of action." }.joinToString("\n")
        assertEquals(1.0, Fountain.estimatePages(action), 0.01)
        assertEquals(0.0, Fountain.estimatePages("[[note]]"), 0.01)
        assertTrue(Fountain.wordCount("INT. HOUSE - DAY\n\nShe waits.") == 5)
    }
}
