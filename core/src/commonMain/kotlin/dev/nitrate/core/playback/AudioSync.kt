package dev.nitrate.core.playback

import kotlin.math.abs

/** Audio sync (audio delay): how far the sound is moved against the picture. */
object AudioSync {
    /** Steps the player offers, in milliseconds. */
    val STEPS = listOf(-100, -25, 25, 100)

    /** The furthest either way: past this it's not lag, it's the wrong file. */
    const val LIMIT_MS = 2_000

    fun clamp(ms: Int): Int = ms.coerceIn(-LIMIT_MS, LIMIT_MS)

    /** "In sync", "+0.150 s (sound later)", "−0.200 s (sound earlier)". */
    fun label(ms: Int): String = when {
        ms == 0 -> "In sync"
        else -> "${if (ms > 0) "+" else "−"}${formatSeconds(abs(ms))} s (sound ${if (ms > 0) "later" else "earlier"})"
    }

    /**
     * The audio clock the player follows, moved by [delayUs]: video frames for time t show when the
     * reported position reaches t, so reporting `position + delay` shows the picture earlier
     * against the sound, i.e. the sound plays [delayUs] later. Never below zero.
     */
    fun shiftedPositionUs(positionUs: Long, delayUs: Long): Long = (positionUs + delayUs).coerceAtLeast(0)

    private fun formatSeconds(ms: Int): String {
        val whole = ms / 1000
        val frac = (ms % 1000).toString().padStart(3, '0')
        return "$whole.$frac"
    }
}
