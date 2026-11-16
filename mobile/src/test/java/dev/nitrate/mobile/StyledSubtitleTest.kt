package dev.nitrate.mobile

import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.media3.common.text.Cue
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.ssa.SsaParser
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What the player keeps from an ASS file's styling (shown when "As the file styles them" is on). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class StyledSubtitleTest {
    private val ass = """
        [Script Info]
        ScriptType: v4.00+
        PlayResX: 1280
        PlayResY: 720

        [V4+ Styles]
        Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
        Style: Sign,Arial,48,&H0000FFFF,&H000000FF,&H00000000,&H00000000,-1,-1,0,0,100,100,0,0,1,2,0,8,10,10,10,1

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        Dialogue: 0,0:00:01.00,0:00:04.00,Sign,,0,0,0,,{\pos(320,100)}Yellow bold italic sign
    """.trimIndent()

    @Test fun keepsColoursStylesAndPositions() {
        val header = ass.substringBefore("[Events]").toByteArray()
        val events = ass.substring(ass.indexOf("[Events]")).toByteArray()
        val cues = mutableListOf<Cue>()
        SsaParser(listOf("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text".toByteArray(), header))
            .parse(events, SubtitleParser.OutputOptions.allCues()) { cues += it.cues }
        val cue = cues.single()
        val text = cue.text as Spanned
        println("cue '${cue.text}' position=${cue.position} line=${cue.line} size=${cue.textSize} spans=" + text.getSpans(0, text.length, Any::class.java).map { it::class.simpleName })
        assertTrue("colour", text.getSpans(0, text.length, ForegroundColorSpan::class.java).isNotEmpty())
        assertTrue("bold/italic", text.getSpans(0, text.length, StyleSpan::class.java).isNotEmpty())
        assertTrue("placed where the file says", cue.position in 0.2f..0.3f && cue.line in 0.1f..0.2f)
    }
}
