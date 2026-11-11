package dev.nitrate.core.model

import kotlin.math.abs
import kotlin.math.roundToLong

/** "1h 52m" or "48m". */
fun formatRuntime(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}

/** "1:02:03" or "2:03" for a playback position. */
fun formatClock(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "$h:${pad2(m)}:${pad2(s)}" else "$m:${pad2(s)}"
}

/** One decimal place, e.g. 7.8 or 12.0 (multiplatform stand-in for "%.1f"). */
fun formatOneDecimal(value: Double): String {
    // Round the magnitude so halves go away from zero, as "%.1f" does.
    val tenths = (abs(value) * 10).roundToLong()
    val sign = if (value < 0 && tenths != 0L) "-" else ""
    return "$sign${tenths / 10}.${tenths % 10}"
}

/** "★ 7.8" for a community rating. */
fun formatRating(rating: Float): String = "★ ${formatOneDecimal(rating.toDouble())}"

private fun pad2(n: Long) = if (n < 10) "0$n" else "$n"
