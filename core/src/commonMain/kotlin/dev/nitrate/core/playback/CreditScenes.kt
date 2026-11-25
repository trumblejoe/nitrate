package dev.nitrate.core.playback

import dev.nitrate.core.model.Chapter
import dev.nitrate.core.model.MediaSegment
import dev.nitrate.core.model.SegmentKind

/**
 * A scene during or after the credits: the credits to skip ([creditsStartMs] → [sceneStartMs]).
 * [spoken]: the server heard dialogue in the scene (its subtitles), so it's a scene for sure.
 */
data class CreditSkip(val creditsStartMs: Long, val sceneStartMs: Long, val afterCredits: Boolean, val spoken: Boolean = false)

/** How sure Nitrate is about a title's scenes in the credits. */
enum class CreditCertainty {
    /** From the server's credit markers or the file's chapters: exact. */
    MARKED,
    /** Read from the scrub thumbnails, with the title's tags saying there is such a scene. */
    CONFIRMED,
    /** Read from the scrub thumbnails only: offered, never skipped by itself. */
    GUESSED,
}

/**
 * What's known about scenes in a title's credits: where to skip ([skips], with their
 * [certainty]), or only that one exists ([tagged]) when it can't be found.
 */
data class CreditScenesInfo(
    val skips: List<CreditSkip>,
    val certainty: CreditCertainty?,
    /** The title's tags (TMDb keywords) say there's a scene during and/or after the credits. */
    val taggedDuring: Boolean,
    val taggedAfter: Boolean,
    /** Where the credits start, for a heads-up when the scene can't be found. */
    val creditsStartMs: Long?,
) {
    val tagged: Boolean get() = taggedDuring || taggedAfter

    /** Credits skip by themselves: marked, or found and confirmed by the tags. */
    val automatic: Boolean get() = skips.isNotEmpty() && certainty != CreditCertainty.GUESSED

    /** Up Next and "Next in collection" wait until here: after the last scene. */
    val lastSceneMs: Long? get() = skips.maxOfOrNull { it.sceneStartMs }

    val isEmpty: Boolean get() = skips.isEmpty() && !tagged

    /**
     * When Up Next (or the next film) may show, given when it would ([upNextAtMs]): not over a
     * scene in the credits, so after the last one, or near the very end when the tags say there's
     * one that couldn't be found.
     */
    fun upNextAt(upNextAtMs: Long?, durationMs: Long): Long? {
        upNextAtMs ?: return null
        val end = (durationMs - END_MS).coerceAtLeast(0)
        val hold = lastSceneMs?.let { maxOf(it, end) } ?: end.takeIf { tagged } ?: return upNextAtMs
        return maxOf(upNextAtMs, hold)
    }

    companion object {
        /** With a scene in the credits, Up Next waits until this close to the end. */
        const val END_MS = 10_000L
    }
}

/**
 * One scrub thumbnail, as the credits reader sees it: brightness (0–255) per pixel and, when
 * known, each pixel's colourfulness ([saturation]: max of |U−128| and |V−128|, white ≈ 0).
 */
class FrameLuma(val width: Int, val height: Int, val luma: IntArray, val saturation: IntArray? = null)

/**
 * What the credits reader measures in one frame (and against the frame before it). The same
 * definitions as the Companion's `Credits/CreditFeatures.cs` (CreditFeatureReader): keep the two in
 * step, and docs/features/playback.md ("Scenes in the credits").
 */
data class CreditFeatures(
    /** Share darker than [CreditFeatureReader.DARK_BELOW]. */
    val dark: Double,
    /** Share brighter than [CreditFeatureReader.BRIGHT_ABOVE]. */
    val bright: Double,
    /** Share in between. */
    val mid: Double,
    /** Share of marks: lighter than [CreditFeatureReader.MARK_ABOVE] (text, thin strokes softened by scaling). */
    val marks: Double,
    /** Entropy (bits) of the 32-bin brightness histogram. */
    val entropy: Double,
    /** Of neighbouring pairs where a mark meets a non-mark, the share jumping by [CreditFeatureReader.EDGE_JUMP] or more. */
    val sharp: Double,
    /** Bands of rows with marks, separated by empty rows, at most [CreditFeatureReader.LINE_MAX_SHARE] of the height. */
    val lines: Int,
    /** Share of the marks inside those bands. */
    val lineInk: Double,
    /** 1 − the lines' heights' coefficient of variation (one line: 1). */
    val regular: Double,
    /** How centred the lines are (1 = both ends equally far from the middle). */
    val symmetry: Double,
    /** Share of the rows' runs of marks at most [CreditFeatureReader.STROKE_MAX] wide (at 320 across). */
    val strokes: Double,
    /** The runs' mean width. */
    val strokeWidth: Double,
    /** Mean colourfulness of the marks; null without colour or marks. */
    val saturation: Double?,
    /** Rows the picture moved up since the frame before; 0: no scroll. */
    val scrollDy: Int,
    /** Mean difference to the frame before at that shift. */
    val scrollMad: Double,
    /** Mean difference to the frame before, unshifted. */
    val stillMad: Double,
) {
    val still: Boolean get() = stillMad <= CreditFeatureReader.STILL_MAD_MAX
    val scrolled: Boolean get() = scrollDy > 0
}

/**
 * Reads [CreditFeatures] frame after frame, keeping only the frame before (for the scroll test).
 * [maxShift]: the most a scroll can move between two frames, as a share of the height (0.25 for
 * the Companion's frame a second; [CreditScenes.THUMBNAIL_MAX_SHIFT] for thumbnails 10 s apart).
 */
class CreditFeatureReader(private val maxShift: Double = 0.25) {
    private var previous: FrameLuma? = null
    private var previousRows: DoubleArray? = null

    fun next(frame: FrameLuma): CreditFeatures {
        val w = frame.width
        val h = frame.height
        val n = w * h
        val luma = frame.luma
        val sat = frame.saturation?.takeIf { it.size >= n }
        var dark = 0; var bright = 0; var mid = 0; var marks = 0
        val histogram = IntArray(32)
        val rowMarks = IntArray(h)
        val rows = DoubleArray(h)
        var satSum = 0L
        var sharp = 0; var edges = 0
        var runs = 0; var shortRuns = 0; var runWidth = 0L
        val strokeMax = maxOf(3, STROKE_MAX * w / 320)
        fun endRun(length: Int) {
            runs++; runWidth += length
            if (length <= strokeMax) shortRuns++
        }
        fun edge(a: Int, b: Int) {
            if ((a > MARK_ABOVE) != (b > MARK_ABOVE)) {
                edges++
                if (kotlin.math.abs(a - b) >= EDGE_JUMP) sharp++
            }
        }
        for (y in 0 until h) {
            val row = y * w
            var rowSum = 0L
            var run = 0
            for (x in 0 until w) {
                val p = luma[row + x]
                rowSum += p
                histogram[(p shr 3).coerceIn(0, 31)]++
                when {
                    p < DARK_BELOW -> dark++
                    p > BRIGHT_ABOVE -> bright++
                    else -> mid++
                }
                if (p > MARK_ABOVE) {
                    marks++; rowMarks[y]++; run++
                    if (sat != null) satSum += sat[row + x]
                } else if (run > 0) { endRun(run); run = 0 }
                if (x + 1 < w) edge(p, luma[row + x + 1])
                if (y + 1 < h) edge(p, luma[row + w + x])
            }
            if (run > 0) endRun(run)
            rows[y] = rowSum.toDouble() / w
        }
        var entropy = 0.0
        for (c in histogram) if (c > 0) { val q = c.toDouble() / n; entropy -= q * kotlin.math.ln(q) / kotlin.math.ln(2.0) }
        // Text lines: bands of rows with marks in them.
        val inkMin = maxOf(2, w / 100)
        val lineMax = maxOf(1, kotlin.math.round(h * LINE_MAX_SHARE).toInt())
        val heights = mutableListOf<Int>()
        var offsets = 0.0
        var lineMarks = 0
        var y = 0
        while (y < h) {
            if (rowMarks[y] < inkMin) { y++; continue }
            val top = y
            var bandMarks = 0
            while (y < h && rowMarks[y] >= inkMin) { bandMarks += rowMarks[y]; y++ }
            if (y - top <= lineMax) {
                heights += y - top
                lineMarks += bandMarks
                var left = w; var right = -1
                for (yy in top until y) for (x in 0 until w) if (luma[yy * w + x] > MARK_ABOVE) { left = minOf(left, x); right = maxOf(right, x) }
                offsets += kotlin.math.abs((left + right) / 2.0 - (w - 1) / 2.0) / (w / 2.0)
            }
        }
        var regular = 1.0
        if (heights.size >= 2) {
            val mean = heights.average()
            val sd = kotlin.math.sqrt(heights.sumOf { (it - mean) * (it - mean) } / heights.size)
            regular = (1 - sd / mean).coerceIn(0.0, 1.0)
        }
        // Scroll: the best upward shift of the row means, checked over the whole frame.
        var scrollDy = 0
        var scrollMad = 0.0
        var stillMad = 0.0
        val prev = previous?.takeIf { it.width == w && it.height == h }
        val prevRows = previousRows
        if (prev != null && prevRows != null) {
            stillMad = mad(frame, prev, 0)
            val maxDy = (h * maxShift).toInt()
            var best = -1
            var bestProfile = Double.MAX_VALUE
            var dy = 1
            while (dy <= maxDy && h - dy >= h / 4) {
                var d = 0.0
                for (r in 0 until h - dy) d += kotlin.math.abs(rows[r] - prevRows[r + dy])
                d /= (h - dy)
                if (d < bestProfile) { bestProfile = d; best = dy }
                dy++
            }
            if (best > 0 && marks >= MIN_MARKS * n) {
                scrollMad = mad(frame, prev, best)
                if (scrollMad <= SCROLL_MAD_MAX && stillMad >= SCROLL_GAIN * scrollMad + 1) scrollDy = best
            }
        }
        previous = frame
        previousRows = rows
        return CreditFeatures(
            dark = dark.toDouble() / n, bright = bright.toDouble() / n, mid = mid.toDouble() / n, marks = marks.toDouble() / n,
            entropy = entropy,
            sharp = if (edges == 0) 0.0 else sharp.toDouble() / edges,
            lines = heights.size,
            lineInk = if (marks == 0) 0.0 else lineMarks.toDouble() / marks,
            regular = regular,
            symmetry = if (heights.isEmpty()) 0.0 else 1 - offsets / heights.size,
            strokes = if (runs == 0) 0.0 else shortRuns.toDouble() / runs,
            strokeWidth = if (runs == 0) 0.0 else runWidth.toDouble() / runs,
            saturation = if (sat != null && marks > 0) satSum.toDouble() / marks else null,
            scrollDy = scrollDy, scrollMad = scrollMad, stillMad = stillMad,
        )
    }

    // Mean |now(x, y) − before(x, y + dy)| over the rows both have.
    private fun mad(now: FrameLuma, before: FrameLuma, dy: Int): Double {
        val count = (now.height - dy) * now.width
        if (count <= 0) return 0.0
        var sum = 0L
        val off = dy * now.width
        for (i in 0 until count) sum += kotlin.math.abs(now.luma[i] - before.luma[i + off])
        return sum.toDouble() / count
    }

    companion object {
        const val DARK_BELOW = 40
        const val BRIGHT_ABOVE = 170
        const val MARK_ABOVE = 110
        const val EDGE_JUMP = 80
        const val LINE_MAX_SHARE = 0.12
        const val STROKE_MAX = 8
        const val STILL_MAD_MAX = 1.5
        const val SCROLL_MAD_MAX = 4.0
        const val SCROLL_GAIN = 2.0
        const val MIN_MARKS = 0.0005

        /** A pixel's colourfulness from RGB (BT.601 U and V, studio swing): max of |U−128|, |V−128|. */
        fun saturationOf(r: Int, g: Int, b: Int): Int {
            val u = (-38 * r - 74 * g + 112 * b + 128) shr 8
            val v = (112 * r - 94 * g - 18 * b + 128) shr 8
            return maxOf(kotlin.math.abs(u), kotlin.math.abs(v))
        }

        /** A pixel's brightness from RGB (BT.601). */
        fun lumaOf(r: Int, g: Int, b: Int): Int = (299 * r + 587 * g + 114 * b) / 1000
    }
}

/**
 * Scenes during and after the credits ("stingers"): whether a title has one, from the tags TMDb
 * gives it (Jellyfin keeps TMDb keywords as tags), and where, from the server's credit markers,
 * the file's chapter names, or the scrub thumbnails (credits look like small bright text on
 * black; a picture after a long run of that is a scene).
 */
object CreditScenes {
    const val TAG_AFTER = "aftercreditsstinger"
    const val TAG_DURING = "duringcreditsstinger"

    /** A credits marker or chapter that ends at least this long before the end leaves room for a scene. */
    const val MIN_SCENE_MS = 15_000L

    /** Credits read from thumbnails: at least this long, starting in the last part of the title. */
    const val MIN_CREDITS_MS = 60_000L
    const val CREDITS_FROM = 0.7

    /**
     * A scene in or after the credits is short: more pictures than this after a dark stretch means
     * the film went on (a night or smoke-filled scene read as credits), not a scene in the credits.
     */
    const val MAX_SCENE_MS = 6 * 60_000L

    /** A scene read from the pictures (not marked) starts within this of the end; earlier, it's the film itself. */
    const val SCENE_NEAR_END_MS = 20 * 60_000L

    /** Thumbnails are 10 s apart: rolling credits may move up to this share of the height between two. */
    const val THUMBNAIL_MAX_SHIFT = 0.75

    // A frame's verdict: the Companion's CreditFinder constants (keep in step).
    const val DARK_MIN = 0.72
    const val BRIGHT_MAX = 0.22
    const val MID_MAX = 0.18
    const val BLANK_DARK = 0.97
    const val SHARP_MIN = 0.5
    const val STROKES_MIN = 0.75
    const val LINE_INK_MIN = 0.6
    const val REGULAR_MIN = 0.5
    const val SYMMETRY_MIN = 0.85
    const val SATURATION_MAX = 40.0
    const val ENTROPY_MAX = 3.0
    const val GAP_MS = 3_000L
    const val MIN_TEXT_SHARE = 0.5

    private val SCENE_CHAPTERS = listOf("post-credit", "post credit", "postcredit", "after credits", "after the credits", "mid-credit", "mid credit", "midcredit", "stinger", "bonus scene", "end scene", "credits scene")
    private val CREDIT_CHAPTERS = listOf("credit", "end titles", "closing")

    fun info(
        runtimeMs: Long,
        tags: List<String>,
        segments: List<MediaSegment>,
        chapters: List<Chapter>,
        frames: List<FrameLuma>? = null,
        frameIntervalMs: Long = 10_000,
        /** The thumbnails' features (null each where none was read), in place of [frames]. */
        features: List<CreditFeatures?>? = null,
        /** What the server found (the same reading of the video, done once for every device); empty = none there. */
        fromServer: List<CreditSkip>? = null,
    ): CreditScenesInfo {
        val lower = tags.map { it.lowercase().replace(" ", "") }
        val during = TAG_DURING in lower
        val after = TAG_AFTER in lower
        val creditsStart = segments.filter { it.kind == SegmentKind.OUTRO && it.startMs > runtimeMs / 2 }.minOfOrNull { it.startMs }
            ?: chapters.firstOrNull { c -> c.startMs > runtimeMs / 2 && CREDIT_CHAPTERS.any { c.name.contains(it, ignoreCase = true) } }?.startMs
        val marked = fromMarkers(runtimeMs, segments, chapters)
        if (marked.isNotEmpty()) return CreditScenesInfo(marked, CreditCertainty.MARKED, during, after, creditsStart ?: marked.first().creditsStartMs)
        // Read from the pictures (here or by the server, results kept from before this rule too):
        // only scenes near the end count.
        val read = (fromServer ?: features?.let { fromFeatures(runtimeMs, it, frameIntervalMs) } ?: frames?.let { fromFrames(runtimeMs, it, frameIntervalMs) }.orEmpty())
            .filter { runtimeMs - it.sceneStartMs <= SCENE_NEAR_END_MS }
        if (read.isNotEmpty()) {
            // Confirmed by the tags, or by the server hearing dialogue in every scene it found.
            val certainty = if ((after && read.any { it.afterCredits }) || (during && read.any { !it.afterCredits }) || read.all { it.spoken }) CreditCertainty.CONFIRMED else CreditCertainty.GUESSED
            // An Outro may be only the final credits, after the scene (the Companion's never holds one):
            // the credits start where they first do.
            val start = listOfNotNull(creditsStart, read.minOf { it.creditsStartMs }).min()
            return CreditScenesInfo(read, certainty, during, after, start)
        }
        return CreditScenesInfo(emptyList(), null, during, after, creditsStart)
    }

    /** Exact skips from the server's credit markers (outros ending well before the end) or chapter names. */
    fun fromMarkers(runtimeMs: Long, segments: List<MediaSegment>, chapters: List<Chapter>): List<CreditSkip> {
        if (runtimeMs <= 0) return emptyList()
        val outros = segments.filter { it.kind == SegmentKind.OUTRO && it.startMs > runtimeMs / 2 }.sortedBy { it.startMs }
        val fromOutros = outros.filter { it.endMs < runtimeMs - MIN_SCENE_MS }.map { o ->
            CreditSkip(o.startMs, o.endMs, afterCredits = outros.none { it.startMs > o.endMs })
        }
        if (fromOutros.isNotEmpty()) return fromOutros
        // Chapters: a scene chapter, with the credits chapter before it.
        val sorted = chapters.sortedBy { it.startMs }
        return sorted.mapIndexedNotNull { i, c ->
            if (SCENE_CHAPTERS.none { c.name.contains(it, ignoreCase = true) }) return@mapIndexedNotNull null
            val credits = sorted.getOrNull(i - 1)?.takeIf { p -> CREDIT_CHAPTERS.any { p.name.contains(it, ignoreCase = true) } && SCENE_CHAPTERS.none { p.name.contains(it, ignoreCase = true) } }
                ?: return@mapIndexedNotNull null
            val later = sorted.drop(i + 1).any { n -> CREDIT_CHAPTERS.any { n.name.contains(it, ignoreCase = true) } && SCENE_CHAPTERS.none { n.name.contains(it, ignoreCase = true) } }
            CreditSkip(credits.startMs, c.startMs, afterCredits = !later)
        }
    }

    /**
     * Skips read from the scrub thumbnails ([frames], one every [intervalMs] from the start): long
     * runs of credits frames in the last part of the title, and pictures after them.
     */
    fun fromFrames(runtimeMs: Long, frames: List<FrameLuma>, intervalMs: Long): List<CreditSkip> =
        fromFeatures(runtimeMs, features(frames), intervalMs)

    /** Every frame's features, in order ([THUMBNAIL_MAX_SHIFT] for the scroll test); null for an empty frame. */
    fun features(frames: List<FrameLuma>, maxShift: Double = THUMBNAIL_MAX_SHIFT): List<CreditFeatures?> {
        val reader = CreditFeatureReader(maxShift)
        return frames.map { if (it.luma.isEmpty()) null else reader.next(it) }
    }

    /** Skips from each frame's [features] (null: not read, not credits), as [fromFrames]. */
    fun fromFeatures(runtimeMs: Long, features: List<CreditFeatures?>, intervalMs: Long): List<CreditSkip> {
        if (runtimeMs <= 0 || intervalMs <= 0 || features.isEmpty()) return emptyList()
        val credits = verdicts(features, intervalMs).map(::counts)
        val minCredits = (MIN_CREDITS_MS / intervalMs).toInt().coerceAtLeast(3)
        val minScene = (MIN_SCENE_MS / intervalMs).toInt().coerceAtLeast(1) + 1
        val maxScene = (MAX_SCENE_MS / intervalMs).toInt()
        val from = (runtimeMs * CREDITS_FROM / intervalMs).toInt()
        // Runs of the same kind, as (start, end exclusive, isCredits).
        val runs = mutableListOf<Triple<Int, Int, Boolean>>()
        var start = 0
        for (i in 1..credits.size) {
            if (i == credits.size || credits[i] != credits[start]) { runs += Triple(start, i, credits[start]); start = i }
        }
        val creditRuns = runs.filter { it.third && it.second - it.first >= minCredits && it.first >= from }
        if (creditRuns.isEmpty()) return emptyList()
        val skips = mutableListOf<CreditSkip>()
        creditRuns.forEachIndexed { k, run ->
            // The pictures after this stretch of credits: up to the next stretch, or the end.
            val nextCredits = creditRuns.getOrNull(k + 1)?.first ?: credits.size
            val scene = (run.second until nextCredits).count { !credits[it] }
            if (scene in minScene..maxScene) skips += CreditSkip(run.first * intervalMs, run.second * intervalMs, afterCredits = k == creditRuns.lastIndex)
        }
        return skips
    }

    /** Whether a verdict code counts as credits: C text, S steady scroll, B black after credits, g a short gap between them. */
    fun counts(verdict: Char): Boolean = verdict == 'C' || verdict == 'S' || verdict == 'B' || verdict == 'g'

    /**
     * One frame's verdict on its own (the Companion's CreditFinder.Verdict): '.' picture, 'b'
     * black, 'o' coloured marks, 'e' brightness too spread, 'C' text, 's' scrolled (steadiness
     * decided in [verdicts]), 'w' text moving without scrolling.
     */
    fun verdict(f: CreditFeatures): Char {
        if (!(f.dark > DARK_MIN && f.bright < BRIGHT_MAX && f.mid < MID_MAX)) return '.'
        if (f.dark >= BLANK_DARK && f.marks < CreditFeatureReader.MIN_MARKS) return 'b'
        if (f.marks < CreditFeatureReader.MIN_MARKS) return '.'
        if (f.saturation != null && f.saturation > SATURATION_MAX) return 'o'
        if (f.entropy > ENTROPY_MAX) return 'e'
        val text = f.sharp >= SHARP_MIN && f.strokes >= STROKES_MIN && f.lines >= 1 && f.lineInk >= LINE_INK_MIN
        if (text && ((f.lines >= 2 && f.regular >= REGULAR_MIN) || f.symmetry >= SYMMETRY_MIN)) return 'C'
        if (text && (f.still || f.scrolled)) return 'C'
        if (f.scrolled) return 's'
        return if (text) 'w' else '.'
    }

    /**
     * Every frame's verdict with the sequence rules (the Companion's CreditFinder.Classify, plus
     * its "too little text" check, 'f'): steady scroll, black right after credits, gaps up to
     * [GAP_MS] between them (none at the thumbnails' 10 s).
     */
    fun verdicts(features: List<CreditFeatures?>, intervalMs: Long): CharArray {
        val v = CharArray(features.size) { i -> features[i]?.let(::verdict) ?: '.' }
        for (i in v.indices) {
            val f = features[i] ?: continue
            if (v[i] == 's' || (v[i] == 'w' && f.scrolled)) v[i] = if (steady(features, i)) 'S' else '.'
        }
        blackAfterCredits(v)
        val gap = (GAP_MS / intervalMs.coerceAtLeast(1)).toInt()
        var i = 0
        while (i < v.size) {
            if (counts(v[i])) { i++; continue }
            val start = i
            while (i < v.size && !counts(v[i])) i++
            if (start > 0 && i < v.size && i - start <= gap) for (k in start until i) v[k] = 'g'
        }
        blackAfterCredits(v)
        // A stretch that's mostly black and gaps isn't credits.
        i = 0
        while (i < v.size) {
            if (!counts(v[i])) { i++; continue }
            val start = i
            while (i < v.size && counts(v[i])) i++
            val text = (start until i).count { v[it] == 'C' || v[it] == 'S' }
            if (text < (i - start) * MIN_TEXT_SHARE) for (k in start until i) v[k] = 'f'
        }
        return v
    }

    private fun steady(features: List<CreditFeatures?>, i: Int): Boolean {
        val dy = features[i]?.scrollDy ?: 0
        fun near(k: Int): Boolean {
            val f = features.getOrNull(k) ?: return false
            return f.scrollDy > 0 && kotlin.math.abs(f.scrollDy - dy) <= maxOf(2, dy / 4)
        }
        return dy > 0 && (near(i - 1) || near(i + 1))
    }

    private fun blackAfterCredits(v: CharArray) {
        for (i in 1 until v.size) if (v[i] == 'b' && counts(v[i - 1])) v[i] = 'B'
    }

    /**
     * Whether one frame, alone, looks like credits (text on black) or a black card between them:
     * crisp, narrow, light strokes in lines, not coloured, on a frame almost all black.
     */
    fun looksLikeCredits(frame: FrameLuma): Boolean = verdict(CreditFeatureReader().next(frame)).let { it == 'C' || it == 'b' }
}

/**
 * What the player does about scenes in the credits as it plays: skip to the next scene when its
 * credits start (if [autoSkip] and the skip is sure), or a heads-up. Each moment acts once; a
 * skip that was undone stays undone.
 */
class CreditSkipper(private val info: CreditScenesInfo, private val autoSkip: Boolean) {
    sealed interface Action {
        /** Seek to [toMs]; "Watch credits" goes back to [backMs]. */
        data class Skip(val toMs: Long, val backMs: Long, val afterCredits: Boolean) : Action
        /** A scene can be skipped to (thumbnails only): offer it. */
        data class Offer(val toMs: Long, val afterCredits: Boolean) : Action
        /** The tags say there's a scene, but it couldn't be found: stay for it. */
        data class HeadsUp(val during: Boolean, val after: Boolean) : Action
    }

    private val done = mutableSetOf<Long>()
    private var headsUpShown = false

    /** What to do at [positionMs], if anything (call as playback moves on). */
    fun at(positionMs: Long): Action? {
        for (s in info.skips) {
            if (s.creditsStartMs in done) continue
            // Inside the first few seconds of these credits.
            if (positionMs >= s.creditsStartMs && positionMs < minOf(s.sceneStartMs, s.creditsStartMs + WINDOW_MS)) {
                done += s.creditsStartMs
                return if (info.automatic && autoSkip) Action.Skip(s.sceneStartMs, s.creditsStartMs, s.afterCredits)
                else Action.Offer(s.sceneStartMs, s.afterCredits)
            }
        }
        val credits = info.creditsStartMs
        if (info.skips.isEmpty() && info.tagged && !headsUpShown && credits != null && positionMs >= credits && positionMs < credits + WINDOW_MS) {
            headsUpShown = true
            return Action.HeadsUp(info.taggedDuring, info.taggedAfter)
        }
        return null
    }

    companion object {
        /** A skip happens when its credits start (seeking past that start doesn't trigger it late). */
        const val WINDOW_MS = 5_000L

        fun message(action: Action): String = when (action) {
            is Action.Skip -> if (action.afterCredits) "Post-Credits Scene" else "Mid-Credits Scene"
            is Action.Offer -> if (action.afterCredits) "There's a scene after the credits" else "There's a scene during the credits"
            is Action.HeadsUp -> when {
                action.during && action.after -> "Stay for scenes during and after the credits"
                action.during -> "Stay for a scene during the credits"
                else -> "Stay for a scene after the credits"
            }
        }
    }
}
