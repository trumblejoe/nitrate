package dev.nitrate.core.playback

import dev.nitrate.core.model.Chapter
import dev.nitrate.core.model.MediaSegment
import dev.nitrate.core.model.SegmentKind

/** A scene during or after the credits: the credits to skip ([creditsStartMs] → [sceneStartMs]). */
data class CreditSkip(val creditsStartMs: Long, val sceneStartMs: Long, val afterCredits: Boolean)

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

/** One scrub thumbnail, as the credits detector sees it: a small grid of brightness (0–255). */
class FrameLuma(val width: Int, val height: Int, val luma: IntArray)

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

    private val SCENE_CHAPTERS = listOf("post-credit", "post credit", "postcredit", "after credits", "after the credits", "mid-credit", "mid credit", "midcredit", "stinger", "bonus scene", "end scene", "credits scene")
    private val CREDIT_CHAPTERS = listOf("credit", "end titles", "closing")

    fun info(
        runtimeMs: Long,
        tags: List<String>,
        segments: List<MediaSegment>,
        chapters: List<Chapter>,
        frames: List<FrameLuma>? = null,
        frameIntervalMs: Long = 10_000,
    ): CreditScenesInfo {
        val lower = tags.map { it.lowercase().replace(" ", "") }
        val during = TAG_DURING in lower
        val after = TAG_AFTER in lower
        val creditsStart = segments.filter { it.kind == SegmentKind.OUTRO && it.startMs > runtimeMs / 2 }.minOfOrNull { it.startMs }
            ?: chapters.firstOrNull { c -> c.startMs > runtimeMs / 2 && CREDIT_CHAPTERS.any { c.name.contains(it, ignoreCase = true) } }?.startMs
        val marked = fromMarkers(runtimeMs, segments, chapters)
        if (marked.isNotEmpty()) return CreditScenesInfo(marked, CreditCertainty.MARKED, during, after, creditsStart ?: marked.first().creditsStartMs)
        val read = frames?.let { fromFrames(runtimeMs, it, frameIntervalMs) }.orEmpty()
        if (read.isNotEmpty()) {
            val certainty = if ((after && read.any { it.afterCredits }) || (during && read.any { !it.afterCredits })) CreditCertainty.CONFIRMED else CreditCertainty.GUESSED
            return CreditScenesInfo(read, certainty, during, after, creditsStart ?: read.first().creditsStartMs)
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
    fun fromFrames(runtimeMs: Long, frames: List<FrameLuma>, intervalMs: Long): List<CreditSkip> {
        if (runtimeMs <= 0 || intervalMs <= 0 || frames.isEmpty()) return emptyList()
        val credits = frames.map(::looksLikeCredits)
        val minCredits = (MIN_CREDITS_MS / intervalMs).toInt().coerceAtLeast(3)
        val minScene = (MIN_SCENE_MS / intervalMs).toInt().coerceAtLeast(1) + 1
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
            if (scene >= minScene) skips += CreditSkip(run.first * intervalMs, run.second * intervalMs, afterCredits = k == creditRuns.lastIndex)
        }
        return skips
    }

    /**
     * Credits: a dark frame with small bright marks (text) on it, or a black one between cards. A
     * dark scene has soft mid-tones; credits are almost all black with crisp near-white pixels.
     */
    fun looksLikeCredits(frame: FrameLuma): Boolean {
        val n = frame.luma.size.takeIf { it > 0 } ?: return false
        var dark = 0
        var bright = 0
        var mid = 0
        for (v in frame.luma) when {
            v < 40 -> dark++
            v > 170 -> bright++
            else -> mid++
        }
        val darkShare = dark.toDouble() / n
        val brightShare = bright.toDouble() / n
        val midShare = mid.toDouble() / n
        return darkShare > 0.72 && brightShare < 0.22 && midShare < 0.18
    }
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
            is Action.Skip -> if (action.afterCredits) "Post-Credits Scene" else "Skipped the credits: a scene during them"
            is Action.Offer -> if (action.afterCredits) "There's a scene after the credits" else "There's a scene during the credits"
            is Action.HeadsUp -> when {
                action.during && action.after -> "Stay for scenes during and after the credits"
                action.during -> "Stay for a scene during the credits"
                else -> "Stay for a scene after the credits"
            }
        }
    }
}
