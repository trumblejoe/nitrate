package dev.nitrate.core.playback

import kotlin.math.abs

/**
 * Brightness (left) and volume (right) swipes on a phone player, made hard to trigger by accident:
 * they start only away from the screen's edges (system gestures, the seek bar), only once the
 * finger has moved clearly upright for a moment (a flick never counts), and the level rests at
 * 50% for a stretch so it's easy to land there.
 */
object SwipeLevel {
    /** No swipe starts in the top or bottom part of the view, or along its sides (fractions). */
    const val EDGE_TOP = 0.15f
    const val EDGE_BOTTOM = 0.22f
    const val EDGE_SIDE = 0.06f

    /** Before anything changes: this far upright (fraction of the height), for this long. */
    const val START_DISTANCE = 0.06f
    const val START_MS = 150L

    /** Upright means at least this many times more vertical than sideways. */
    const val UPRIGHT = 2.5f

    /** Swiping the full height of the view moves the level this much. */
    const val RANGE_PER_HEIGHT = 0.8f

    /** The resting level, and how much finger travel (in level terms) it holds on each side. */
    const val MIDDLE = 0.5f
    const val HOLD = 0.06f

    /** Whether a touch at ([x], [y]) in a [width] × [height] view may become a swipe. */
    fun canStart(x: Float, y: Float, width: Float, height: Float): Boolean =
        y >= height * EDGE_TOP && y <= height * (1 - EDGE_BOTTOM) && x >= width * EDGE_SIDE && x <= width * (1 - EDGE_SIDE)

    /** What a move of ([dx], [dy]) from the touch, [elapsedMs] after it, means for a swipe. */
    fun judge(dx: Float, dy: Float, height: Float, elapsedMs: Long): Start = when {
        abs(dy) < height * START_DISTANCE && abs(dx) < height * START_DISTANCE -> Start.NOT_YET
        // Covered the distance too fast (a flick, like the one for the phone's own menus) or
        // went sideways: this touch never becomes a swipe.
        elapsedMs < START_MS -> Start.NEVER
        abs(dy) < abs(dx) * UPRIGHT -> Start.NEVER
        abs(dy) < height * START_DISTANCE -> Start.NOT_YET
        else -> Start.SWIPE
    }

    enum class Start { NOT_YET, SWIPE, NEVER }

    /** The level shown for a finger position [raw] (0..1): 50% across the hold band, scaled either side. */
    fun level(raw: Float): Float {
        val r = raw.coerceIn(0f, 1f)
        return when {
            r < MIDDLE - HOLD -> r * MIDDLE / (MIDDLE - HOLD)
            r <= MIDDLE + HOLD -> MIDDLE
            else -> MIDDLE + (r - MIDDLE - HOLD) * (1 - MIDDLE) / (1 - MIDDLE - HOLD)
        }
    }

    /** The finger position that shows [level] (the middle of the hold band for 50%). */
    fun raw(level: Float): Float {
        val l = level.coerceIn(0f, 1f)
        return when {
            l < MIDDLE -> l * (MIDDLE - HOLD) / MIDDLE
            l == MIDDLE -> MIDDLE
            else -> MIDDLE + HOLD + (l - MIDDLE) * (1 - MIDDLE - HOLD) / (1 - MIDDLE)
        }
    }
}

/** One swipe, from the level it started at: [move] by a fraction of the view's height (up is positive). */
class LevelDrag(start: Float) {
    private var raw = SwipeLevel.raw(start)

    var level: Float = SwipeLevel.level(raw)
        private set

    /** Moves and returns the new level; [reachedMiddle] is set when this move landed on 50%. */
    fun move(heightFraction: Float): Float {
        val before = level
        raw = (raw + heightFraction * SwipeLevel.RANGE_PER_HEIGHT).coerceIn(0f, 1f)
        level = SwipeLevel.level(raw)
        reachedMiddle = level == SwipeLevel.MIDDLE && before != SwipeLevel.MIDDLE
        return level
    }

    /** True right after a move that came to rest at 50% (for a small tap of feedback). */
    var reachedMiddle = false
        private set
}
