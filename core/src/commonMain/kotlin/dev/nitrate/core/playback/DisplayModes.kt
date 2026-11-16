package dev.nitrate.core.playback

import kotlin.math.abs
import kotlinx.serialization.Serializable

/** Whether the TV's refresh rate follows the video's frame rate. */
@Serializable
enum class FrameRateMatch(val label: String, val detail: String) {
    OFF("Off", "The TV stays at its usual refresh rate"),
    SEAMLESS("Only when seamless", "Switches only where the TV can do it without a blank screen"),
    ALWAYS("Always", "Switches the TV to match each video (a second or two of blank screen while it changes)"),
}

/** One display mode a TV offers. */
data class DisplayMode(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

/** Picking the display mode that shows a video without judder. */
object DisplayModes {
    /**
     * The mode at [current]'s resolution whose refresh rate is a whole multiple of [fps] (exactly:
     * 23.976 isn't 24), the smallest multiple first (24 Hz before 48 before 120), or null when the
     * current mode already fits or none does.
     */
    fun best(modes: List<DisplayMode>, current: DisplayMode, fps: Float): DisplayMode? {
        if (fps <= 0f) return null
        if (fits(current.refreshRate, fps) != null) return null
        return modes
            .filter { it.width == current.width && it.height == current.height }
            .mapNotNull { mode -> fits(mode.refreshRate, fps)?.let { multiple -> mode to multiple } }
            .minWithOrNull(compareBy<Pair<DisplayMode, Int>> { it.second }.thenBy { abs(it.first.refreshRate - it.second * fps) })
            ?.first
    }

    /** The whole multiple of [fps] that [refreshRate] is (within a hair), or null. */
    private fun fits(refreshRate: Float, fps: Float): Int? {
        val multiple = kotlin.math.round(refreshRate / fps).toInt()
        if (multiple < 1) return null
        // 23.976 × 1 against 24.000 is off by 0.024 Hz: a judder every 42 s, so not a fit.
        return multiple.takeIf { abs(refreshRate - multiple * fps) < 0.01f * multiple }
    }
}
