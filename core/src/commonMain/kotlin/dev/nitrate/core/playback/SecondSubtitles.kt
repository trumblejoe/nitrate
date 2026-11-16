package dev.nitrate.core.playback

import dev.nitrate.core.model.MediaSource
import dev.nitrate.core.model.StreamKind

/** One subtitle line on screen from [startMs] to [endMs]. */
data class Cue(val startMs: Long, val endMs: Long, val text: String)

/**
 * Dual subtitles: a second subtitle track shown at the top of the picture alongside the usual one
 * (one language under the other, for learners and mixed households). Text subtitles only: the
 * server hands any of them over as SRT, which is read into [Cue]s here and drawn by the app.
 */
object SecondSubtitles {
    private val time = Regex("""(?:(\d{1,2}):)?(\d{2}):(\d{2})[,.](\d{3})""")
    private val tags = Regex("""<[^>]+>|\{\\[^}]*\}""")
    private val textCodecs = setOf("srt", "subrip", "vtt", "webvtt", "ass", "ssa", "mov_text", "text", "tx3g", "ttml")

    /** Subtitle tracks that can be shown second (text ones), except [except] (the one already on). */
    fun choices(source: MediaSource, except: Int?): List<TrackChoice> =
        source.subtitleChoices().filter { c ->
            c.index >= 0 && c.index != except &&
                source.streams.firstOrNull { it.index == c.index && it.kind == StreamKind.SUBTITLE }?.codec?.lowercase() in textCodecs
        }

    /** Reads an SRT or WebVTT file into cues, in time order, styling tags removed. */
    fun parse(text: String): List<Cue> {
        val cues = mutableListOf<Cue>()
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if ("-->" in line) {
                val (from, to) = line.split("-->", limit = 2)
                val start = ms(from)
                val end = ms(to)
                val body = mutableListOf<String>()
                i++
                while (i < lines.size && lines[i].isNotBlank()) { body += lines[i]; i++ }
                val clean = body.joinToString("\n") { it.replace(tags, "").trim() }.trim()
                if (start != null && end != null && end > start && clean.isNotEmpty()) cues += Cue(start, end, clean)
            } else {
                i++
            }
        }
        return cues.sortedBy { it.startMs }
    }

    private fun ms(s: String): Long? {
        val m = time.find(s) ?: return null
        val (h, mi, sec, milli) = m.destructured
        return (h.ifEmpty { "0" }.toLong() * 3_600_000) + mi.toLong() * 60_000 + sec.toLong() * 1000 + milli.toLong()
    }

    /** What's on screen at [positionMs] (overlapping cues joined), or null. */
    fun at(cues: List<Cue>, positionMs: Long): String? {
        if (cues.isEmpty()) return null
        // The last cue starting at or before now, then any overlapping ones just before it.
        var lo = 0
        var hi = cues.size - 1
        var last = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (cues[mid].startMs <= positionMs) { last = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (last < 0) return null
        val on = mutableListOf<String>()
        var k = last
        while (k >= 0 && last - k < 8) {
            val c = cues[k]
            if (positionMs < c.endMs) on.add(0, c.text)
            k--
        }
        return on.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }
}
