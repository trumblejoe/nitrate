package dev.nitrate.core.playback

import kotlinx.serialization.Serializable

/** How the player shares the screen with the writing pane. Chosen in the player; per device. */
@Serializable
enum class WritingSplit(val label: String) {
    /** Whichever leaves the picture bigger, from the window's shape and the video's. */
    AUTO("Auto"),
    /** Video on the left, writing on the right. */
    SIDE_BY_SIDE("Side by side"),
    /** Video on top, writing below. */
    STACKED("Video on top");

    /** The arrangement to use in a [width] x [height] window (any unit) for a video of [aspect] (width / height). */
    fun sideBySide(width: Float, height: Float, aspect: Float?): Boolean = when (this) {
        SIDE_BY_SIDE -> true
        STACKED -> false
        AUTO -> {
            val a = aspect?.takeIf { it > 0f } ?: (16f / 9f)
            val side = fitted(width - sidePaneWidth(width), height, a)
            val stacked = fitted(width, stackedVideoHeight(width, height, a), a)
            // A pane too narrow to write in loses whatever the picture gains.
            if (width - sidePaneWidth(width) <= 0f || sidePaneWidth(width) < MIN_PANE_WIDTH) false
            else side >= stacked
        }
    }

    companion object {
        /** Narrowest writing pane worth having side by side, in dp. */
        const val MIN_PANE_WIDTH = 280f

        /** The writing pane beside the video: about 45%, never more than half or 560 dp. */
        fun sidePaneWidth(width: Float): Float = (width * 0.45f).coerceIn(minOf(320f, width * 0.5f), 560f)

        /** The video above the pane: full width at its own shape, but never more than half the height. */
        fun stackedVideoHeight(width: Float, height: Float, aspect: Float?): Float {
            val a = aspect?.takeIf { it > 0f } ?: (16f / 9f)
            return minOf(width / a, height * 0.5f)
        }

        /** Area of an [aspect] picture fitted inside a box. */
        private fun fitted(boxW: Float, boxH: Float, aspect: Float): Float {
            if (boxW <= 0f || boxH <= 0f) return 0f
            val w = minOf(boxW, boxH * aspect)
            return w * (w / aspect)
        }
    }
}
