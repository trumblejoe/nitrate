package dev.nitrate.core.rows

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Colours for Cressida ink. [smart] looks at a card's artwork and picks a hue that stands apart
 * from the picture's own colours, light or deep to suit how bright the marked side is.
 * Pure Kotlin over ARGB pixels so both apps (and iOS later) share it.
 */
object InkPalette {
    const val GOLD = 0xFFD9B26AL

    /** Swatches offered for a custom ink. */
    val SWATCHES: List<Pair<String, Long>> = listOf(
        "Gold" to GOLD,
        "Silver" to 0xFFC9CED6L,
        "White" to 0xFFF4F1EAL,
        "Crimson" to 0xFFE0455AL,
        "Ember" to 0xFFF08A3CL,
        "Jade" to 0xFF3FC6A0L,
        "Sapphire" to 0xFF4C8DF0L,
        "Violet" to 0xFFA77BF3L,
        "Rose" to 0xFFF27BB5L,
        "Ink black" to 0xFF141414L,
    )

    /** A hue the artwork hardly uses, as far as possible from its main colours. */
    data class Analysis(val prominentHues: List<Double>, val markedLuminance: Double, val colourful: Boolean)

    /** Analyses [pixels] (ARGB, row by row, [width] wide); the marked side is the left [split] of it. */
    fun analyse(pixels: IntArray, width: Int, split: Float = RowArt.CRESSIDA_SPLIT): Analysis {
        val buckets = 36
        val weight = DoubleArray(buckets)
        var lumSum = 0.0
        var lumCount = 0
        var colourWeight = 0.0
        pixels.forEachIndexed { i, p ->
            val r = (p shr 16 and 0xff) / 255.0
            val g = (p shr 8 and 0xff) / 255.0
            val b = (p and 0xff) / 255.0
            if (width > 0 && i % width < width * split) {
                lumSum += 0.299 * r + 0.587 * g + 0.114 * b
                lumCount++
            }
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            val delta = mx - mn
            if (mx < 0.12 || delta < 0.08) return@forEachIndexed
            val h = when (mx) {
                r -> 60 * (((g - b) / delta).mod(6.0))
                g -> 60 * ((b - r) / delta + 2)
                else -> 60 * ((r - g) / delta + 4)
            }
            val w = (delta / mx) * mx
            weight[((h / 360 * buckets).toInt()).coerceIn(0, buckets - 1)] += w
            colourWeight += w
        }
        val total = weight.sum()
        // Hues that make up a real share of the picture, strongest first.
        val hues = weight.indices.filter { total > 0 && weight[it] >= total * 0.08 }
            .sortedByDescending { weight[it] }
            .take(4)
            .map { (it + 0.5) * 360 / buckets }
        return Analysis(hues, if (lumCount > 0) lumSum / lumCount else 0.3, colourWeight > pixels.size * 0.04)
    }

    /** The ink for a card whose artwork is [pixels]: ARGB. */
    fun smart(pixels: IntArray, width: Int): Long = smart(analyse(pixels, width))

    fun smart(a: Analysis): Long {
        // Washed-out or grey art: gold reads well on it and suits the look.
        if (!a.colourful || a.prominentHues.isEmpty()) return if (a.markedLuminance > 0.62) 0xFF6B4A12L else GOLD
        // Candidates: the main hue's complement and its neighbours, triads, and gold.
        val main = a.prominentHues.first()
        val candidates = listOf(main + 180, main + 150, main + 210, main + 120, main + 240, 42.0).map { it.mod(360.0) }
        fun distance(x: Double, y: Double) = abs(((x - y + 540) % 360) - 180)
        // Pick the one furthest from every prominent hue (the strongest counting most).
        val hue = candidates.maxBy { c ->
            a.prominentHues.mapIndexed { i, h -> distance(c, h) / (1 + i * 0.35) }.min()
        }
        // Light ink on the usual darkened side; deep ink if that side is still bright.
        val bright = a.markedLuminance > 0.62
        return hsv(hue, if (bright) 0.75 else 0.55, if (bright) 0.38 else 0.93)
    }

    /** Hue (degrees) of an ARGB colour, for tests and display. */
    fun hue(argb: Long): Double {
        val r = (argb shr 16 and 0xff) / 255.0
        val g = (argb shr 8 and 0xff) / 255.0
        val b = (argb and 0xff) / 255.0
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        if (d == 0.0) return 0.0
        return when (mx) {
            r -> 60 * (((g - b) / d).mod(6.0))
            g -> 60 * ((b - r) / d + 2)
            else -> 60 * ((r - g) / d + 4)
        }
    }

    /** ARGB from hue (0–360), saturation and value (0–1). */
    fun hsv(h: Double, s: Double, v: Double): Long {
        val c = v * s
        val x = c * (1 - abs((h / 60).mod(2.0) - 1))
        val m = v - c
        val (r, g, b) = when {
            h < 60 -> Triple(c, x, 0.0)
            h < 120 -> Triple(x, c, 0.0)
            h < 180 -> Triple(0.0, c, x)
            h < 240 -> Triple(0.0, x, c)
            h < 300 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        fun ch(f: Double) = ((f + m) * 255).toInt().coerceIn(0, 255).toLong()
        return (0xffL shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }
}
