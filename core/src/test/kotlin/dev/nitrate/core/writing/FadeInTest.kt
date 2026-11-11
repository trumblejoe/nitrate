package dev.nitrate.core.writing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class FadeInTest {
    private val script = """
        Title: The Projectionist
        Author: Sam Reel
        Draft date: 11 November 2026

        INT. PROJECTION BOOTH - NIGHT

        Film rattles through the gate. **Smoke** curls from the *lamp house*.

        MAYA (V.O.)
        (quietly)
        It's catching fire.

        CUT TO:

        .ROOFTOP

        @McCLANE
        Yippee & co.
    """.trimIndent() + "\n"

    @Test
    fun `fountain survives a round trip through fadein`() {
        assertEquals(script, FadeIn.import(FadeIn.export(script)))
    }

    @Test
    fun `export is a zip holding OSF 4 document xml with base styles`() {
        val bytes = FadeIn.export(script)
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                entries[e.name] = zip.readBytes().decodeToString()
            }
        }
        val xml = entries.getValue("document.xml")
        assertTrue(xml.contains("""<document type="Open Screenplay Format document" version="40">"""))
        assertTrue(xml.contains("""<para><style basestyle="Scene Heading"/><text>INT. PROJECTION BOOTH - NIGHT</text></para>"""))
        assertTrue(xml.contains("""<para><style basestyle="Scene Heading"/><text>ROOFTOP</text></para>"""))
        assertTrue(xml.contains("""<text bold="1">Smoke</text>"""))
        assertTrue(xml.contains("""<text italic="1">lamp house</text>"""))
        assertTrue(xml.contains("""<text>Yippee &amp; co.</text>"""))
        assertTrue(xml.contains("""<para bookmark="Title"><style basestyle="Action"/><text>The Projectionist</text></para>"""))
    }

    @Test
    fun `reads OSF 4 with split text runs`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<document type="Open Screenplay Format document" version="40">
  <info/><settings/><styles/>
  <paragraphs>
    <para page_number="1"><style basestyle="Scene Heading"/><text>Int. First Location - day</text></para>
    <para page_number="1">
      <style basestyle="Action"/>
      <text bold="1">Sarah</text>
      <text> walks to the </text>
      <text italic="1">window</text>
      <text>.</text>
    </para>
    <para><style basestyle="Character"/><text>SARAH</text></para>
    <para><style basestyle="Dialogue"/><text>Nothing.</text></para>
    <para><style basestyle="Transition"/><text>CUT TO:</text></para>
  </paragraphs>
  <titlepage>
    <para bookmark="Title"><style basestyle="Action"/><text>The Formula</text></para>
  </titlepage>
  <lists/>
</document>"""
        assertEquals(
            "Title: The Formula\n\nInt. First Location - day\n\n**Sarah** walks to the *window*.\n\nSARAH\nNothing.\n\nCUT TO:\n",
            FadeIn.import(xml.toByteArray()),
        )
    }

    @Test
    fun `reads OSF 2 camelCase styles and OSF 1·2 info title page`() {
        val v2 = """<document type="Open Screenplay Format document" version="21"><info/><paragraphs>
            <para><style baseStyleName="Scene Heading"/><text>EXT. PIER - DAWN</text></para></paragraphs></document>"""
        assertEquals("EXT. PIER - DAWN\n", FadeIn.import(v2.toByteArray()))

        val v12 = """<document type="Open Screenplay Format document" version="12">
            <info title="Pier" written_by="A. Writer" uuid="x"/><paragraphs>
            <para><style basestylename="Action"/><text>Gulls.</text></para></paragraphs></document>"""
        assertEquals("Title: Pier\nAuthor: A. Writer\n\nGulls.\n", FadeIn.import(v12.toByteArray()))
    }

    @Test
    fun `adds force markers where Fountain would misread a paragraph`() {
        val xml = """<document type="Open Screenplay Format document" version="40"><info/><paragraphs>
            <para><style basestyle="Scene Heading"/><text>The Rooftop</text></para>
            <para><style basestyle="Action"/><text>BANG</text></para>
            <para><style basestyle="Character"/><text>McCoy</text></para>
            <para><style basestyle="Parenthetical"/><text>beat</text></para>
            <para><style basestyle="Dialogue"/><text>Hm.</text></para>
            <para><style basestyle="Transition"/><text>FADE OUT.</text></para>
        </paragraphs></document>"""
        val fountain = FadeIn.import(xml.toByteArray())
        assertEquals(".The Rooftop\n\n!BANG\n\n@McCoy\n(beat)\nHm.\n\n>FADE OUT.\n", fountain)
        // And the Fountain classifier reads it back as the same elements.
        assertEquals(
            listOf(Element.SCENE_HEADING, Element.BLANK, Element.ACTION, Element.BLANK, Element.CHARACTER, Element.PARENTHETICAL, Element.DIALOGUE, Element.BLANK, Element.TRANSITION, Element.BLANK),
            Fountain.classify(fountain),
        )
    }

    @Test
    fun `notes and sections are not exported as script paragraphs`() {
        val xml = FadeIn.toOsfXml("# Act One\n\n[[remember the gun]]\n\nShe waits.\n")
        assertTrue(!xml.contains("Act One") && !xml.contains("remember"))
        assertTrue(xml.contains("<text>She waits.</text>"))
    }

    @Test
    fun `literal asterisks and underscores survive the round trip`() {
        // Not "Rating: …" as a first line: that is a Fountain title page field.
        val text = "She rates it 5\\* out of 5 in file\\_name.\n"
        assertEquals(text, FadeIn.import(FadeIn.export(text)))
    }
}
