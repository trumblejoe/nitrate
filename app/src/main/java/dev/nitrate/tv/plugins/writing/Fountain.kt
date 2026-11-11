package dev.nitrate.tv.plugins.writing

/** Screenplay elements of the Fountain format (https://fountain.io), one per line. */
enum class Element {
    TITLE_PAGE, SCENE_HEADING, ACTION, CHARACTER, PARENTHETICAL, DIALOGUE, TRANSITION,
    CENTERED, LYRIC, SECTION, SYNOPSIS, NOTE, BONEYARD, PAGE_BREAK, BLANK,
}

/**
 * A line-based Fountain classifier: enough for highlighting, page estimates and element shortcuts.
 * Follows the spec's rules for forced elements (".", "!", "@", ">", "~") and the blank-line
 * context that decides scene headings, characters and transitions.
 */
object Fountain {
    private val SCENE = Regex("""^(INT|EXT|EST|INT\.?/EXT|I/E)[.\s]""", RegexOption.IGNORE_CASE)
    private val TITLE_KEY = Regex("""^[A-Za-z][A-Za-z ]*:""")

    fun classify(text: String): List<Element> {
        val lines = text.split('\n')
        val out = MutableList(lines.size) { Element.ACTION }
        var inBoneyard = false
        var inTitlePage = lines.firstOrNull()?.let { TITLE_KEY.containsMatchIn(it) } == true

        fun blank(i: Int) = i < 0 || i >= lines.size || lines[i].isBlank()

        for ((i, raw) in lines.withIndex()) {
            val line = raw.trim()
            if (inTitlePage) {
                if (line.isEmpty()) { inTitlePage = false; out[i] = Element.BLANK } else out[i] = Element.TITLE_PAGE
                continue
            }
            if (inBoneyard || line.startsWith("/*")) {
                out[i] = Element.BONEYARD
                inBoneyard = !line.contains("*/")
                continue
            }
            val previous = if (i > 0) out[i - 1] else Element.BLANK
            out[i] = when {
                line.isEmpty() -> Element.BLANK
                line.startsWith("[[") && line.endsWith("]]") -> Element.NOTE
                line.startsWith("===") -> Element.PAGE_BREAK
                line.startsWith("#") -> Element.SECTION
                line.startsWith("=") -> Element.SYNOPSIS
                line.startsWith("~") -> Element.LYRIC
                line.startsWith("!") -> Element.ACTION
                line.startsWith(">") && line.endsWith("<") -> Element.CENTERED
                line.startsWith(">") -> Element.TRANSITION
                line.startsWith(".") && !line.startsWith("..") -> Element.SCENE_HEADING
                line.startsWith("@") -> Element.CHARACTER
                // Inside a dialogue block until the next blank line.
                previous in DIALOGUE_BLOCK && line.startsWith("(") -> Element.PARENTHETICAL
                previous in DIALOGUE_BLOCK -> Element.DIALOGUE
                blank(i - 1) && SCENE.containsMatchIn(line) -> Element.SCENE_HEADING
                blank(i - 1) && blank(i + 1) && isUpper(line) && line.endsWith("TO:") -> Element.TRANSITION
                blank(i - 1) && !blank(i + 1) && isUpper(line.substringBefore('(')) -> Element.CHARACTER
                else -> Element.ACTION
            }
        }
        return out
    }

    private val DIALOGUE_BLOCK = setOf(Element.CHARACTER, Element.PARENTHETICAL, Element.DIALOGUE)

    private fun isUpper(s: String) = s.any { it.isLetter() } && s.none { it.isLowerCase() }

    /** Characters per line by element on a standard screenplay page, for page estimates. */
    private fun width(e: Element) = when (e) {
        Element.DIALOGUE, Element.LYRIC -> 35
        Element.PARENTHETICAL -> 25
        else -> 61
    }

    /** Roughly 55 lines to a page in standard screenplay format. */
    fun estimatePages(text: String): Double {
        val lines = text.split('\n')
        val elements = classify(text)
        var count = 0
        for ((i, e) in elements.withIndex()) {
            count += when (e) {
                Element.TITLE_PAGE, Element.NOTE, Element.BONEYARD, Element.SECTION, Element.SYNOPSIS -> 0
                Element.BLANK -> 1
                else -> maxOf(1, (lines[i].trim().length + width(e) - 1) / width(e))
            }
        }
        return count / 55.0
    }

    fun wordCount(text: String) = text.split(Regex("\\s+")).count { w -> w.any { it.isLetterOrDigit() } }

    /** Rewrites one line as [element], for the element shortcuts. */
    fun convertLine(line: String, element: Element): String {
        val bare = line.trim().removePrefix(".").removePrefix("!").removePrefix("@").removePrefix(">")
            .removeSurrounding("(", ")").trim()
        return when (element) {
            Element.SCENE_HEADING -> if (SCENE.containsMatchIn(bare)) bare.uppercase() else ".${bare.uppercase()}"
            Element.CHARACTER -> bare.uppercase()
            Element.PARENTHETICAL -> "($bare)"
            Element.TRANSITION -> bare.uppercase().let { if (it.endsWith("TO:")) it else ">$it" }
            Element.ACTION -> if (isUpper(bare)) "!$bare" else bare
            else -> bare
        }
    }
}
