package dev.nitrate.core.writing.story

import dev.nitrate.core.writing.Element
import dev.nitrate.core.writing.Fountain
import dev.nitrate.core.writing.TextOperation
import kotlin.random.Random

/** One scene of a screenplay: its heading line and everything up to the next heading. */
data class Scene(
    /** Stable id from the heading's hidden marker; null until one is added. */
    val id: String?,
    /** The heading as written, without the marker. */
    val heading: String,
    /** 0-based place in the script. */
    val index: Int,
    /** Offset of the heading line's start. */
    val start: Int,
    /** Offset where the next scene (or the end) starts. */
    val end: Int,
    /** Character names speaking in it (as written in their cues, extensions removed). */
    val characters: Set<String>,
    /** The place from the heading ("DINER" in "INT. DINER - NIGHT"). */
    val location: String?,
)

/**
 * Scenes come from the screenplay itself: each scene heading starts one. A heading carries a
 * hidden Fountain note `[[scene:id]]` giving it an identity that survives retyping and moving, so
 * comments, links and assignments stay on it. Markers are hidden in the editor and removed from
 * exports. Reordering or adding scenes is a change to the text, so it's shared and merged like
 * any other edit.
 */
object Outline {
    private val MARKER = Regex("""\s*\[\[scene:([a-z0-9]{4,16})]]""")
    private val PREFIX = Regex("""^\.?\s*(INT\.?/EXT\.?|I/E|INT|EXT|EST)[.\s]+""", RegexOption.IGNORE_CASE)

    fun newId(random: Random = Random): String = (1..8).map { "abcdefghijklmnopqrstuvwxyz0123456789"[random.nextInt(36)] }.joinToString("")

    fun scenes(text: String): List<Scene> {
        val lines = text.split('\n')
        val elements = Fountain.classify(text)
        val starts = IntArray(lines.size)
        var at = 0
        for (i in lines.indices) { starts[i] = at; at += lines[i].length + 1 }
        val headings = elements.indices.filter { elements[it] == Element.SCENE_HEADING }
        return headings.mapIndexed { n, line ->
            val nextLine = headings.getOrNull(n + 1)
            val end = nextLine?.let { starts[it] } ?: text.length
            val raw = lines[line]
            val id = MARKER.find(raw)?.groupValues?.get(1)
            val heading = raw.replace(MARKER, "").trim()
            val speakers = ((line + 1) until (nextLine ?: lines.size))
                .filter { elements[it] == Element.CHARACTER }
                .map { characterName(lines[it]) }
                .filter { it.isNotEmpty() }
                .toSet()
            Scene(id, heading, n, starts[line], end, speakers, locationOf(heading))
        }
    }

    /** "@McCLANE (V.O.)" → "McCLANE"; "SAM (CONT'D)" → "SAM". */
    fun characterName(cue: String): String = cue.trim().removePrefix("@").substringBefore('(').substringBefore('^').trim()

    /** The place in a heading: without INT./EXT. and the time of day after the last " - ". */
    fun locationOf(heading: String): String? {
        val bare = heading.replace(MARKER, "").trim().replace(PREFIX, "").let { h ->
            if (heading.trim().startsWith(".") && !PREFIX.containsMatchIn(heading.trim())) h.removePrefix(".") else h
        }
        val place = bare.substringBeforeLast(" - ").substringBefore(" #").trim()
        return place.takeIf { it.isNotEmpty() }?.uppercase()
    }

    /**
     * The change that gives every heading without a marker one, and drops extra markers (two
     * writers marking the same new heading at once keep the first). Null when nothing's needed.
     */
    fun addIds(text: String, makeId: () -> String = { newId() }, skipAt: Int? = null): TextOperation? {
        val lines = text.split('\n')
        val elements = Fountain.classify(text)
        var changed = false
        // The line being typed on is left alone (it may not be finished).
        val skipLine = skipAt?.let { at -> text.substring(0, at.coerceIn(0, text.length)).count { it == '\n' } }
        val out = lines.mapIndexed { i, line ->
            if (elements[i] != Element.SCENE_HEADING || i == skipLine) return@mapIndexed line
            val markers = MARKER.findAll(line).toList()
            when {
                markers.isEmpty() -> { changed = true; "${line.trimEnd()} [[scene:${makeId()}]]" }
                markers.size > 1 -> {
                    changed = true
                    val keep = markers.first()
                    line.substring(0, keep.range.last + 1) + line.substring(keep.range.last + 1).replace(MARKER, "")
                }
                else -> line
            }
        }
        return if (changed) TextOperation.diff(text, out.joinToString("\n")) else null
    }

    /**
     * The text as the editor shows it: markers taken out, with offsets mapped both ways (a
     * cursor never lands inside a hidden marker).
     */
    class Hidden(val original: String) {
        private val ranges = markerRanges(original)
        val shown: String = if (ranges.isEmpty()) original else stripMarkers(original)

        fun toShown(offset: Int): Int {
            var removed = 0
            for (r in ranges) {
                if (offset <= r.first) break
                removed += minOf(offset, r.last + 1) - r.first
            }
            return offset - removed
        }

        fun toOriginal(offset: Int): Int {
            var at = offset
            for (r in ranges) {
                // A marker sits at the end of its heading: a cursor there goes before it.
                if (r.first < at) at += r.last + 1 - r.first else break
            }
            return at
        }
    }

    /** The text without scene markers, for exports and word counts. */
    fun stripMarkers(text: String): String = text.replace(MARKER, "")

    /** Where markers are, to hide them in the editor: (start, end) of each. */
    fun markerRanges(text: String): List<IntRange> = MARKER.findAll(text).map { it.range }.toList()

    /**
     * Moves scene [sceneId] to just before the scene now at [toIndex] (the end, for the scene
     * count): one change deleting it where it was and inserting it there.
     */
    fun move(text: String, sceneId: String, toIndex: Int): TextOperation? {
        val scenes = scenes(text)
        val from = scenes.indexOfFirst { it.id == sceneId }.takeIf { it >= 0 } ?: return null
        if (toIndex == from || toIndex == from + 1) return null
        val scene = scenes[from]
        var block = text.substring(scene.start, scene.end)
        // Scenes are separated by a blank line; the last one may not end with one.
        if (!block.endsWith("\n\n")) block = block.trimEnd('\n') + "\n\n"
        val target = scenes.getOrNull(toIndex)?.start ?: text.length
        val b = TextOperation.Builder()
        if (target < scene.start) {
            b.retain(target).insert(block).retain(scene.start - target).delete(scene.end - scene.start).retain(text.length - scene.end)
        } else {
            // To the end: make sure the text before it ends with a blank line too.
            val lead = if (target == text.length && !text.endsWith("\n\n")) (if (text.endsWith("\n")) "\n" else "\n\n") else ""
            val moved = if (target == text.length) block.trimEnd('\n') + "\n" else block
            b.retain(scene.start).delete(scene.end - scene.start).retain(target - scene.end).insert(lead + moved)
        }
        return b.build()
    }

    /** Adds a new scene with [heading] after scene [afterId] (null: at the end). */
    fun add(text: String, heading: String, afterId: String?, id: String = newId()): TextOperation {
        val scenes = scenes(text)
        val after = afterId?.let { a -> scenes.firstOrNull { it.id == a } }
        val at = after?.end ?: text.length
        val clean = heading.trim().uppercase().let { if (PREFIX.containsMatchIn(it) || it.startsWith(".")) it else ".$it" }
        val before = text.substring(0, at)
        val lead = when {
            before.isEmpty() -> ""
            before.endsWith("\n\n") -> ""
            before.endsWith("\n") -> "\n"
            else -> "\n\n"
        }
        val trail = if (at < text.length) "\n\n" else "\n"
        return TextOperation.Builder().retain(at).insert("$lead$clean [[scene:$id]]$trail").retain(text.length - at).build()
    }
}
