package dev.nitrate.tv.plugins.writing

import org.w3c.dom.Element as XmlElement
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Converts between Fountain and Fade In's native `.fadein` files: a ZIP holding `document.xml`
 * in Open Screenplay Format (OSF). Writes OSF 4.0; reads 4.0, 2.x and 1.2.
 *
 * There is no published OSF specification. The structure follows the open-source parsers
 * (the `osf` Rust crate and `rsdoiel/osf`): `<para>` elements with a base style
 * (`basestyle`, `baseStyleName` or `basestylename`) and `<text>` runs that may be bold,
 * italic or underlined; title page fields are `<titlepage>` paragraphs with a `bookmark`.
 */
object FadeIn {
    private const val STYLE_SCENE = "Scene Heading"
    private const val STYLE_ACTION = "Action"
    private const val STYLE_CHARACTER = "Character"
    private const val STYLE_PARENTHETICAL = "Parenthetical"
    private const val STYLE_DIALOGUE = "Dialogue"
    private const val STYLE_TRANSITION = "Transition"
    private const val STYLE_SHOT = "Shot"

    /** Fountain title page keys to OSF title page bookmarks. Other keys have no Fade In field and are dropped. */
    private val TITLE_KEYS = mapOf(
        "title" to "Title",
        "credit" to null, // "Written by" line: implied by Author in Fade In
        "author" to "Author",
        "authors" to "Author",
        "draft date" to "Draft",
        "draft" to "Draft",
        "contact" to "Contact",
        "copyright" to "Copyright",
    )

    // ---- Fountain -> .fadein ----

    fun export(fountain: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("document.xml"))
            zip.write(toOsfXml(fountain).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    fun toOsfXml(fountain: String): String {
        val lines = fountain.replace("\r\n", "\n").split('\n')
        val elements = Fountain.classify(fountain)
        val titlePage = parseTitlePage(lines, elements)

        val paras = StringBuilder()
        for ((i, e) in elements.withIndex()) {
            val style = when (e) {
                Element.SCENE_HEADING -> STYLE_SCENE
                Element.ACTION, Element.CENTERED -> STYLE_ACTION
                Element.CHARACTER -> STYLE_CHARACTER
                Element.PARENTHETICAL -> STYLE_PARENTHETICAL
                Element.DIALOGUE, Element.LYRIC -> STYLE_DIALOGUE
                Element.TRANSITION -> STYLE_TRANSITION
                // Notes, sections, synopses, boneyard and page breaks have no OSF paragraph.
                else -> null
            } ?: continue
            paras.append("    <para><style basestyle=\"").append(style).append("\"/>")
            appendRuns(paras, stripForcing(lines[i].trim(), e))
            paras.append("</para>\n")
        }

        val title = StringBuilder()
        for ((bookmark, value) in titlePage) {
            for (line in value.split('\n')) {
                title.append("    <para bookmark=\"").append(bookmark).append("\"><style basestyle=\"Action\"/>")
                appendRuns(title, line)
                title.append("</para>\n")
            }
        }

        val pages = maxOf(1, kotlin.math.ceil(Fountain.estimatePages(fountain)).toInt())
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<document type=\"Open Screenplay Format document\" version=\"40\">\n")
            append("  <info uuid=\"").append(UUID.randomUUID()).append("\" pagecount=\"").append(pages).append("\"/>\n")
            append("  <settings/>\n  <styles/>\n")
            append("  <paragraphs>\n").append(paras).append("  </paragraphs>\n")
            if (title.isEmpty()) append("  <titlepage/>\n") else append("  <titlepage>\n").append(title).append("  </titlepage>\n")
            append("  <lists/>\n")
            append("</document>\n")
        }
    }

    private fun parseTitlePage(lines: List<String>, elements: List<Element>): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var key: String? = null
        val value = StringBuilder()
        fun flush() {
            val bookmark = key?.let { TITLE_KEYS[it.lowercase()] }
            if (bookmark != null && value.isNotBlank()) out += bookmark to value.toString().trim()
            value.clear()
        }
        for ((i, e) in elements.withIndex()) {
            if (e != Element.TITLE_PAGE) break
            val line = lines[i]
            val match = Regex("""^([A-Za-z][A-Za-z ]*):\s*(.*)$""").find(line)
            if (match != null && !line.first().isWhitespace()) {
                flush()
                key = match.groupValues[1].trim()
                value.append(match.groupValues[2])
            } else {
                // Indented continuation line of a multi-line value.
                if (value.isNotEmpty()) value.append('\n')
                value.append(line.trim())
            }
        }
        flush()
        return out
    }

    /** Removes Fountain's force markers, which aren't part of the text. */
    private fun stripForcing(line: String, e: Element): String = when (e) {
        Element.SCENE_HEADING -> line.removePrefix(".")
        Element.ACTION -> line.removePrefix("!")
        Element.CHARACTER -> line.removePrefix("@")
        Element.TRANSITION -> line.removePrefix(">")
        Element.CENTERED -> line.removePrefix(">").removeSuffix("<").trim()
        Element.LYRIC -> line.removePrefix("~")
        else -> line
    }

    /** Writes `<text>` runs, turning Fountain's ***bold italic***, **bold**, *italic* and _underline_ into attributes. */
    private fun appendRuns(sb: StringBuilder, text: String) {
        if (text.isEmpty()) { sb.append("<text></text>"); return }
        var bold = false
        var italic = false
        var underline = false
        val run = StringBuilder()
        fun flush() {
            if (run.isEmpty()) return
            sb.append("<text")
            if (bold) sb.append(" bold=\"1\"")
            if (italic) sb.append(" italic=\"1\"")
            if (underline) sb.append(" underline=\"1\"")
            sb.append('>').append(escape(run.toString())).append("</text>")
            run.clear()
        }
        var i = 0
        while (i < text.length) {
            when {
                text[i] == '\\' && i + 1 < text.length -> { run.append(text[i + 1]); i += 2; continue }
                text.startsWith("***", i) -> { flush(); bold = !bold; italic = !italic; i += 3; continue }
                text.startsWith("**", i) -> { flush(); bold = !bold; i += 2; continue }
                text[i] == '*' -> { flush(); italic = !italic; i += 1; continue }
                text[i] == '_' -> { flush(); underline = !underline; i += 1; continue }
            }
            run.append(text[i])
            i++
        }
        flush()
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    // ---- .fadein -> Fountain ----

    /** Accepts a `.fadein` ZIP or raw OSF XML. */
    fun import(bytes: ByteArray): String {
        val xml = if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) unzipDocument(bytes) else bytes
        return fromOsfXml(xml)
    }

    private fun unzipDocument(bytes: ByteArray): ByteArray {
        var fallback: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name.endsWith("document.xml")) return zip.readBytes()
                if (fallback == null && entry.name.endsWith(".xml")) fallback = zip.readBytes()
            }
        }
        return fallback ?: error("No screenplay inside this .fadein file")
    }

    private class Para(val style: String, val text: String, val bookmark: String?)

    fun fromOsfXml(xml: ByteArray): String {
        val factory = DocumentBuilderFactory.newInstance().apply {
            // Untrusted input: no external entities or DTDs.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            isExpandEntityReferences = false
        }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml)).documentElement
        require(doc.tagName == "document") { "Not an Open Screenplay Format document" }

        fun paras(container: String): List<Para> {
            val parent = doc.getElementsByTagName(container).item(0) as? XmlElement ?: return emptyList()
            return parent.childElements("para").map { p ->
                val styleEl = p.childElements("style").firstOrNull()
                val style = styleEl?.let { s -> listOf("basestyle", "baseStyleName", "basestylename").map(s::getAttribute).firstOrNull { it.isNotEmpty() } }.orEmpty()
                val text = p.childElements("text").joinToString("") { t -> t.toFountainRun() }
                Para(style, text, p.getAttribute("bookmark").ifEmpty { null })
            }
        }

        val out = StringBuilder()

        // Title page: v2/v4 bookmarks, else v1.2 <info> attributes (skipping Fade In's placeholders).
        val titleFields = linkedMapOf<String, MutableList<String>>()
        for (p in paras("titlepage")) {
            val key = when (p.bookmark) {
                "Title" -> "Title"; "Author" -> "Author"; "Draft" -> "Draft date"
                "Contact" -> "Contact"; "Copyright" -> "Copyright"; else -> null
            } ?: continue
            if (p.text.isNotBlank()) titleFields.getOrPut(key) { mutableListOf() } += p.text.trim()
        }
        if (titleFields.isEmpty()) {
            val info = doc.childElements("info").firstOrNull()
            info?.getAttribute("title")?.takeIf { it.isNotBlank() && it != "Untitled" }?.let { titleFields["Title"] = mutableListOf(it) }
            info?.getAttribute("written_by")?.takeIf { it.isNotBlank() && it != "Written by" }?.let { titleFields["Author"] = mutableListOf(it) }
        }
        for ((key, values) in titleFields) {
            if (values.size == 1) out.append(key).append(": ").append(values[0]).append('\n')
            else { out.append(key).append(":\n"); values.forEach { out.append("    ").append(it).append('\n') } }
        }
        if (titleFields.isNotEmpty()) out.append('\n')

        var previous: String? = null
        for (p in paras("paragraphs")) {
            val text = p.text.trim()
            if (text.isEmpty()) continue
            val inDialogue = p.style in setOf(STYLE_PARENTHETICAL, STYLE_DIALOGUE) &&
                previous in setOf(STYLE_CHARACTER, STYLE_PARENTHETICAL, STYLE_DIALOGUE)
            // Dialogue blocks stay together; everything else is separated by a blank line.
            if (previous != null && !inDialogue) out.append('\n')
            out.append(toFountainLine(p.style, text)).append('\n')
            previous = p.style
        }
        return out.toString().trimEnd() + "\n"
    }

    /** Adds Fountain force markers where the line wouldn't otherwise be read as [style]. */
    private fun toFountainLine(style: String, text: String): String {
        val upper = text.any { it.isLetter() } && text.none { it.isLowerCase() }
        return when (style) {
            STYLE_SCENE -> if (Regex("""^(INT|EXT|EST|INT\.?/EXT|I/E)[.\s]""", RegexOption.IGNORE_CASE).containsMatchIn(text)) text else ".$text"
            STYLE_CHARACTER -> if (upper) text else "@$text"
            STYLE_PARENTHETICAL -> if (text.startsWith("(")) text else "($text)"
            STYLE_TRANSITION -> if (upper && text.endsWith("TO:")) text else ">$text"
            STYLE_SHOT -> "!$text"
            // An all-caps action line would read as a character or transition.
            else -> if (upper || text.startsWith("!") || text.startsWith(".") || text.startsWith("@")) "!$text" else text
        }
    }

    private fun XmlElement.childElements(tag: String): List<XmlElement> {
        val nodes = childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? XmlElement }.filter { it.tagName == tag }
    }

    private fun XmlElement.toFountainRun(): String {
        val raw = textContent.orEmpty()
        if (raw.isBlank()) return raw
        // Keep surrounding spaces outside the markers so emphasis stays valid Fountain.
        val lead = raw.takeWhile { it == ' ' }
        val trail = raw.takeLastWhile { it == ' ' }
        var core = raw.trim().replace("*", "\\*").replace("_", "\\_")
        if (getAttribute("underline") == "1") core = "_${core}_"
        val bold = getAttribute("bold") == "1"
        val italic = getAttribute("italic") == "1"
        core = when {
            bold && italic -> "***$core***"
            bold -> "**$core**"
            italic -> "*$core*"
            else -> core
        }
        return lead + core + trail
    }
}
