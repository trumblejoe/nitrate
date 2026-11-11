package dev.nitrate.core.writing

/** A parsed XML element: name, attributes, and child elements and text, in order. */
internal class XmlElement(val name: String, val attributes: Map<String, String>) {
    val children = mutableListOf<Any>() // XmlElement or String

    fun elements(name: String): List<XmlElement> = children.filterIsInstance<XmlElement>().filter { it.name == name }
    fun firstElement(name: String): XmlElement? = elements(name).firstOrNull()
    fun attr(name: String): String = attributes[name].orEmpty()

    /** Concatenated text of this element and its descendants. */
    val text: String
        get() = children.joinToString("") { if (it is XmlElement) it.text else it as String }

    /** First descendant (depth-first) with this name. */
    fun find(name: String): XmlElement? {
        for (c in children) if (c is XmlElement) {
            if (c.name == name) return c
            c.find(name)?.let { return it }
        }
        return null
    }
}

/**
 * Minimal XML parser for screenplay files: elements, attributes, text, character references and
 * CDATA. Skips comments, processing instructions and DOCTYPE (never resolves external entities).
 */
internal object Xml {
    fun parse(input: String): XmlElement {
        var i = 0
        val stack = ArrayDeque<XmlElement>()
        var root: XmlElement? = null

        fun fail(msg: String): Nothing = throw IllegalArgumentException("Invalid XML at $i: $msg")

        while (i < input.length) {
            if (input[i] != '<') {
                val end = input.indexOf('<', i).let { if (it < 0) input.length else it }
                val text = decode(input.substring(i, end))
                if (stack.isNotEmpty()) stack.last().children += text
                i = end
                continue
            }
            when {
                input.startsWith("<!--", i) -> i = input.indexOf("-->", i).takeIf { it >= 0 }?.plus(3) ?: fail("unclosed comment")
                input.startsWith("<![CDATA[", i) -> {
                    val end = input.indexOf("]]>", i).takeIf { it >= 0 } ?: fail("unclosed CDATA")
                    if (stack.isNotEmpty()) stack.last().children += input.substring(i + 9, end)
                    i = end + 3
                }
                input.startsWith("<?", i) -> i = input.indexOf("?>", i).takeIf { it >= 0 }?.plus(2) ?: fail("unclosed declaration")
                input.startsWith("<!", i) -> i = input.indexOf('>', i).takeIf { it >= 0 }?.plus(1) ?: fail("unclosed DOCTYPE")
                input.startsWith("</", i) -> {
                    val end = input.indexOf('>', i).takeIf { it >= 0 } ?: fail("unclosed end tag")
                    val name = input.substring(i + 2, end).trim()
                    val open = stack.removeLastOrNull() ?: fail("unexpected </$name>")
                    if (open.name != name) fail("expected </${open.name}>, found </$name>")
                    i = end + 1
                }
                else -> {
                    val end = tagEnd(input, i) ?: fail("unclosed tag")
                    val selfClosing = input[end - 1] == '/'
                    val body = input.substring(i + 1, if (selfClosing) end - 1 else end)
                    val name = body.takeWhile { !it.isWhitespace() }
                    if (name.isEmpty()) fail("empty tag name")
                    val element = XmlElement(name, attributes(body.substring(name.length)))
                    if (stack.isEmpty()) {
                        if (root != null) fail("more than one root element")
                        root = element
                    } else {
                        stack.last().children += element
                    }
                    if (!selfClosing) stack.addLast(element)
                    i = end + 1
                }
            }
        }
        if (stack.isNotEmpty()) fail("unclosed <${stack.last().name}>")
        return root ?: fail("no root element")
    }

    /** Index of the '>' ending a start tag, skipping quoted attribute values. */
    private fun tagEnd(s: String, start: Int): Int? {
        var quote: Char? = null
        for (j in start + 1 until s.length) {
            val c = s[j]
            if (quote != null) { if (c == quote) quote = null } else if (c == '"' || c == '\'') quote = c else if (c == '>') return j
        }
        return null
    }

    private val ATTR = Regex("""([^\s=]+)\s*=\s*("([^"]*)"|'([^']*)')""")

    private fun attributes(s: String): Map<String, String> =
        ATTR.findAll(s).associate { m -> m.groupValues[1] to decode(m.groupValues[3].ifEmpty { m.groupValues[4] }) }

    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);")

    fun decode(s: String): String = if ('&' !in s) s else ENTITY.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e == "amp" -> "&"
            e == "lt" -> "<"
            e == "gt" -> ">"
            e == "quot" -> "\""
            e == "apos" -> "'"
            e.startsWith("#x") -> codePoint(e.substring(2).toInt(16))
            else -> codePoint(e.substring(1).toInt())
        }
    }

    private fun codePoint(cp: Int): String = if (cp < 0x10000) cp.toChar().toString() else {
        val v = cp - 0x10000
        charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
    }

    fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
