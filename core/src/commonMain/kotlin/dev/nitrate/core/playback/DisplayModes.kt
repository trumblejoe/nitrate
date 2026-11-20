package dev.nitrate.core.playback

import kotlin.math.abs
import kotlinx.serialization.Serializable

/** Whether the TV's refresh rate follows the video's frame rate. */
@Serializable
enum class FrameRateMatch(val label: String, val detail: String, val shortLabel: String) {
    OFF("Off", "The TV stays at its usual refresh rate", "Off"),
    SEAMLESS("Only when seamless", "Switches only where the TV can do it without a blank screen", "When seamless"),
    ALWAYS("Always", "Switches the TV to match each video (a second or two of blank screen while it changes)", "Always"),
}

/**
 * How long the TV stays at a film's rate after the player closes, while Nitrate is in use
 * ([keepMs]; null: until Nitrate is left or another video needs a different rate). Never still
 * keeps it a few seconds, so the next episode or an extra doesn't switch twice.
 */
@kotlinx.serialization.Serializable
enum class RefreshHold(val label: String, val detail: String, val keepMs: Long?) {
    ALWAYS("Always", "Stays at the film's rate while you're in Nitrate: no switch back to the menus, none for the next film at that rate. Menus move a little less smoothly at 24 Hz.", null),
    FIFTEEN_MINUTES("15 minutes", "Stays at the film's rate for 15 minutes after the player closes, long enough to pick the next one, then goes back.", 15 * 60_000L),
    NEVER("Never", "Goes back to the TV's usual rate a few seconds after the player closes: smoothest menus, one more switch per film.", 15_000L),
}

/** One display mode a TV offers. */
data class DisplayMode(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

/** Picking the display mode that shows a video without judder. */
object DisplayModes {
    /**
     * The mode at [current]'s resolution whose refresh rate is a whole multiple of [fps], the
     * smallest multiple first (23.976 Hz before 47.952 before 119.88), or null when the current mode
     * already fits or none does better. Exact multiples come first (23.976 is shown at 23.976 Hz);
     * when the TV has none, a near one (within [NEAR]: 24.000 Hz for 23.976 fps, one repeated frame
     * every 42 s) still beats a rate that doesn't fit at all (59.94 Hz: 3:2 judder all the time),
     * but never replaces a current mode that's already near.
     */
    fun best(modes: List<DisplayMode>, current: DisplayMode, fps: Float): DisplayMode? {
        if (fps <= 0f) return null
        if (fits(current.refreshRate, fps) != null) return null
        val sameSize = modes.filter { it.width == current.width && it.height == current.height }
        fun pick(test: (Float, Float) -> Int?) = sameSize
            .mapNotNull { mode -> test(mode.refreshRate, fps)?.let { multiple -> mode to multiple } }
            .minWithOrNull(compareBy<Pair<DisplayMode, Int>> { it.second }.thenBy { abs(it.first.refreshRate - it.second * fps) })
            ?.first
        pick(::fits)?.let { return it }
        if (nearFits(current.refreshRate, fps) != null) return null
        return pick(::nearFits)
    }

    /** How far off a near match may be: 0.2% (24.000 against 23.976 is 0.1%; 25 against 24 is 4%). */
    const val NEAR = 0.002f

    /** What showing [fps] at [refreshRate] looks like, for explaining it. */
    fun cadence(fps: Float, refreshRate: Float): Cadence = when {
        fps <= 0f || refreshRate <= 0f -> Cadence.UNKNOWN
        fits(refreshRate, fps) != null -> Cadence.EXACT
        nearFits(refreshRate, fps) != null -> Cadence.NEAR
        else -> Cadence.JUDDER
    }

    enum class Cadence { EXACT, NEAR, JUDDER, UNKNOWN }

    /** The whole multiple of [fps] that [refreshRate] nearly is (within [NEAR]), or null. */
    private fun nearFits(refreshRate: Float, fps: Float): Int? {
        val multiple = kotlin.math.round(refreshRate / fps).toInt()
        if (multiple < 1) return null
        return multiple.takeIf { abs(refreshRate - multiple * fps) / (multiple * fps) < NEAR }
    }

    /** The whole multiple of [fps] that [refreshRate] is (within a hair), or null. */
    private fun fits(refreshRate: Float, fps: Float): Int? {
        val multiple = kotlin.math.round(refreshRate / fps).toInt()
        if (multiple < 1) return null
        // 23.976 × 1 against 24.000 is off by 0.024 Hz: a judder every 42 s, so not a fit.
        return multiple.takeIf { abs(refreshRate - multiple * fps) < 0.01f * multiple }
    }
}
