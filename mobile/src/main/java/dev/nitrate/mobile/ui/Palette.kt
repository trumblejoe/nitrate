package dev.nitrate.mobile.ui

import kotlin.math.max
import kotlin.math.min

/**
 * The colour a screen should take on from a piece of artwork: the most prominent vivid hue,
 * brightened enough to read as light on a dark background. Pure Kotlin over ARGB pixels so it
 * moves to Compose Multiplatform unchanged.
 */
object ArtworkPalette {
    private const val BUCKETS = 36

    /** Returns an ARGB colour, or null when the image is effectively grey. */
    fun accent(pixels: IntArray): Int? {
        val weight = DoubleArray(BUCKETS)
        val sat = DoubleArray(BUCKETS)
        val value = DoubleArray(BUCKETS)
        val hueSin = DoubleArray(BUCKETS)
        val hueCos = DoubleArray(BUCKETS)
        for (p in pixels) {
            val r = (p shr 16 and 0xff) / 255.0
            val g = (p shr 8 and 0xff) / 255.0
            val b = (p and 0xff) / 255.0
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            val delta = mx - mn
            if (mx < 0.12 || delta < 0.08) continue
            val s = delta / mx
            val h = when (mx) {
                r -> 60 * (((g - b) / delta).mod(6.0))
                g -> 60 * ((b - r) / delta + 2)
                else -> 60 * ((r - g) / delta + 4)
            }
            val w = s * s * mx
            val i = ((h / 360 * BUCKETS).toInt()).coerceIn(0, BUCKETS - 1)
            weight[i] += w
            sat[i] += s * w
            value[i] += mx * w
            hueSin[i] += kotlin.math.sin((h * kotlin.math.PI / 180)) * w
            hueCos[i] += kotlin.math.cos((h * kotlin.math.PI / 180)) * w
        }
        // Neighbouring buckets count together so a hue split across a boundary still wins.
        val best = (0 until BUCKETS).maxByOrNull { weight[it] + 0.5 * (weight[(it + 1) % BUCKETS] + weight[(it + BUCKETS - 1) % BUCKETS]) } ?: return null
        val total = weight.sum()
        if (total <= 0 || weight[best] < total * 0.05 && total < pixels.size * 0.02) return null
        val w = weight[best]
        val hue = (kotlin.math.atan2(hueSin[best], hueCos[best]) * 180 / kotlin.math.PI).let { if (it < 0) it + 360 else it }
        // Bright enough to read as text on a near-black background, whatever the artwork.
        val s = (sat[best] / w).coerceIn(0.40, 0.72)
        val v = (value[best] / w).coerceIn(0.88, 0.98)
        return hsv(hue, s, v)
    }

    private fun hsv(h: Double, s: Double, v: Double): Int {
        val c = v * s
        val x = c * (1 - kotlin.math.abs((h / 60).mod(2.0) - 1))
        val m = v - c
        val (r, g, b) = when {
            h < 60 -> Triple(c, x, 0.0)
            h < 120 -> Triple(x, c, 0.0)
            h < 180 -> Triple(0.0, c, x)
            h < 240 -> Triple(0.0, x, c)
            h < 300 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        fun ch(f: Double) = ((f + m) * 255).toInt().coerceIn(0, 255)
        return (0xff shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }
}
