package dev.nitrate.core.opds

/** An element of a parsed XML document: its qualified name, attributes, children and text. */
class XmlElement(val name: String, val attributes: Map<String, String>, val children: List<XmlElement>, val text: String) {
    /** The name without its namespace prefix ("atom:link" → "link"). */
    val localName get() = name.substringAfter(':')

    fun attr(name: String): String? = attributes[name] ?: attributes.entries.firstOrNull { it.key.substringAfter(':') == name }?.value

    fun all(localName: String): List<XmlElement> = children.filter { it.localName == localName }

    fun first(localName: String): XmlElement? = children.firstOrNull { it.localName == localName }

    /** The text of the first child called [localName], trimmed, or null. */
    fun textOf(localName: String): String? = first(localName)?.text?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * A small, lenient XML reader for catalog feeds (OPDS's Atom and OpenSearch descriptions): elements,
 * attributes, text, CDATA and character references. No DTDs or namespaces beyond keeping prefixes.
 */
object Xml {
    fun parse(xml: String): XmlElement {
        val p = Parser(xml)
        return p.document()
    }

    fun unescape(s: String): String {
        if ('&' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val end = s.indexOf(';', i)
                if (end > i && end - i <= 10) {
                    val ref = s.substring(i + 1, end)
                    val decoded = when {
                        ref == "amp" -> "&"
                        ref == "lt" -> "<"
                        ref == "gt" -> ">"
                        ref == "quot" -> "\""
                        ref == "apos" -> "'"
                        ref == "nbsp" -> " "
                        ref.startsWith("#x") || ref.startsWith("#X") -> ref.drop(2).toIntOrNull(16)?.let(::codePoint)
                        ref.startsWith("#") -> ref.drop(1).toIntOrNull()?.let(::codePoint)
                        else -> null
                    }
                    if (decoded != null) {
                        out.append(decoded)
                        i = end + 1
                        continue
                    }
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun codePoint(cp: Int): String = when {
        cp < 0x10000 -> cp.toChar().toString()
        else -> {
            val v = cp - 0x10000
            charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
        }
    }

    private class Parser(val s: String) {
        var i = 0

        fun document(): XmlElement {
            while (i < s.length) {
                skipMisc()
                if (i < s.length && s[i] == '<') return element()
                i++
            }
            throw IllegalArgumentException("No XML element found")
        }

        /** Skips declarations, comments, processing instructions, DOCTYPE and whitespace. */
        private fun skipMisc() {
            while (i < s.length) {
                when {
                    s.startsWith("<?", i) -> i = (s.indexOf("?>", i).takeIf { it >= 0 } ?: s.length) + 2
                    s.startsWith("<!--", i) -> i = (s.indexOf("-->", i).takeIf { it >= 0 } ?: s.length) + 3
                    s.startsWith("<!", i) && !s.startsWith("<![CDATA[", i) -> i = (s.indexOf('>', i).takeIf { it >= 0 } ?: s.length) + 1
                    s[i].isWhitespace() -> i++
                    else -> return
                }
            }
        }

        private fun element(): XmlElement {
            i++ // '<'
            val nameStart = i
            while (i < s.length && !s[i].isWhitespace() && s[i] != '>' && s[i] != '/') i++
            val name = s.substring(nameStart, i)
            val attrs = LinkedHashMap<String, String>()
            while (i < s.length) {
                while (i < s.length && s[i].isWhitespace()) i++
                if (i >= s.length) break
                if (s[i] == '/') {
                    i = s.indexOf('>', i).let { if (it < 0) s.length else it + 1 }
                    return XmlElement(name, attrs, emptyList(), "")
                }
                if (s[i] == '>') { i++; break }
                val aStart = i
                while (i < s.length && s[i] != '=' && !s[i].isWhitespace() && s[i] != '>' && s[i] != '/') i++
                val aName = s.substring(aStart, i)
                while (i < s.length && s[i].isWhitespace()) i++
                if (i < s.length && s[i] == '=') {
                    i++
                    while (i < s.length && s[i].isWhitespace()) i++
                    val quote = s.getOrNull(i)
                    if (quote == '"' || quote == '\'') {
                        val end = s.indexOf(quote, i + 1).let { if (it < 0) s.length else it }
                        attrs[aName] = unescape(s.substring(i + 1, end))
                        i = end + 1
                    } else {
                        val vStart = i
                        while (i < s.length && !s[i].isWhitespace() && s[i] != '>') i++
                        attrs[aName] = unescape(s.substring(vStart, i))
                    }
                } else if (aName.isNotEmpty()) attrs[aName] = ""
            }
            val children = mutableListOf<XmlElement>()
            val text = StringBuilder()
            while (i < s.length) {
                when {
                    s.startsWith("</", i) -> {
                        i = s.indexOf('>', i).let { if (it < 0) s.length else it + 1 }
                        return XmlElement(name, attrs, children, text.toString())
                    }
                    s.startsWith("<![CDATA[", i) -> {
                        val end = s.indexOf("]]>", i).let { if (it < 0) s.length else it }
                        text.append(s, i + 9, end)
                        i = end + 3
                    }
                    s.startsWith("<!--", i) || s.startsWith("<?", i) -> skipMisc()
                    s[i] == '<' -> children += element()
                    else -> {
                        val end = s.indexOf('<', i).let { if (it < 0) s.length else it }
                        text.append(unescape(s.substring(i, end)))
                        i = end
                    }
                }
            }
            return XmlElement(name, attrs, children, text.toString())
        }
    }
}
