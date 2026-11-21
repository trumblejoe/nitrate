package dev.nitrate.core.comics

import kotlin.math.abs

/** A panel on a page, as fractions of the page's width and height. */
data class PanelRect(val x: Float, val y: Float, val w: Float, val h: Float) {
    val area get() = w * h

    companion object {
        val WHOLE = PanelRect(0f, 0f, 1f, 1f)
    }
}

/**
 * Finds a page's panels for guided view (one panel at a time), from a small greyscale copy of the
 * page: the page's background colour is read from its edges, then the page is cut along gutters
 * (bands of rows or columns that are all background) into tiers, each tier into panels, and so on
 * a few levels down. The cut order is the reading order: tiers top to bottom, panels left to right
 * (right to left for manga). Pages where nothing clear is found give the whole page.
 */
object Panels {
    /** How close to the background a pixel must be to count as gutter (0–255). */
    private const val TOLERANCE = 28

    /** Share of a line that must be background for the line to be gutter. */
    private const val GUTTER_SHARE = 0.985

    /** Panels smaller than this share of the page are dropped (stray marks, page numbers). */
    private const val MIN_AREA = 0.015f

    /**
     * [luma]: [width] × [height] brightness values (0–255), row by row. Returns the panels in
     * reading order, or just [PanelRect.WHOLE] when the page doesn't split into at least two.
     */
    fun find(luma: IntArray, width: Int, height: Int, rightToLeft: Boolean = false): List<PanelRect> {
        if (width < 16 || height < 16 || luma.size < width * height) return listOf(PanelRect.WHOLE)
        val bg = background(luma, width, height)
        fun isBg(x: Int, y: Int) = abs(luma[y * width + x] - bg) <= TOLERANCE
        val minGap = maxOf(2, (minOf(width, height) * 0.006).toInt())

        val out = mutableListOf<IntArray>()

        fun trim(r: IntArray): IntArray? {
            var (x0, y0, x1, y1) = r.toList()
            fun rowBg(y: Int) = (x0 until x1).count { isBg(it, y) } >= (x1 - x0) * GUTTER_SHARE
            fun colBg(x: Int) = (y0 until y1).count { isBg(x, it) } >= (y1 - y0) * GUTTER_SHARE
            while (y0 < y1 && rowBg(y0)) y0++
            while (y1 > y0 && rowBg(y1 - 1)) y1--
            while (x0 < x1 && colBg(x0)) x0++
            while (x1 > x0 && colBg(x1 - 1)) x1--
            return if (x1 - x0 < 4 || y1 - y0 < 4) null else intArrayOf(x0, y0, x1, y1)
        }

        /** The content bands between gutters along one axis of [r]. */
        fun bands(r: IntArray, horizontal: Boolean): List<IntArray> {
            val (x0, y0, x1, y1) = r.toList()
            val lines = if (horizontal) (y0 until y1) else (x0 until x1)
            val across = if (horizontal) (x1 - x0) else (y1 - y0)
            val gutter = lines.map { l ->
                val count = if (horizontal) (x0 until x1).count { isBg(it, l) } else (y0 until y1).count { isBg(l, it) }
                count >= across * GUTTER_SHARE
            }
            val result = mutableListOf<IntArray>()
            var start = -1
            var gap = 0
            for ((i, g) in gutter.withIndex()) {
                if (!g) {
                    if (start < 0) start = i
                    gap = 0
                } else if (start >= 0) {
                    gap++
                    if (gap >= minGap) {
                        val end = i - gap + 1
                        result += if (horizontal) intArrayOf(x0, y0 + start, x1, y0 + end) else intArrayOf(x0 + start, y0, x0 + end, y1)
                        start = -1
                        gap = 0
                    }
                }
            }
            if (start >= 0) {
                val end = gutter.size
                result += if (horizontal) intArrayOf(x0, y0 + start, x1, y0 + end) else intArrayOf(x0 + start, y0, x0 + end, y1)
            }
            return result
        }

        fun cut(r: IntArray, horizontal: Boolean, depth: Int) {
            val region = trim(r) ?: return
            val parts = bands(region, horizontal).mapNotNull(::trim)
            when {
                // Can't split this way: try the other way once, else it's a panel.
                parts.size <= 1 -> {
                    val other = bands(region, !horizontal).mapNotNull(::trim)
                    if (other.size > 1 && depth < 6) {
                        val ordered = if (!horizontal && rightToLeft) other.reversed() else other
                        ordered.forEach { cut(it, horizontal, depth + 1) }
                    } else out += region
                }
                depth >= 6 -> out += region
                else -> {
                    val ordered = if (!horizontal && rightToLeft) parts.reversed() else parts
                    ordered.forEach { cut(it, !horizontal, depth + 1) }
                }
            }
        }

        cut(intArrayOf(0, 0, width, height), horizontal = true, depth = 0)
        val panels = out.map { (x0, y0, x1, y1) ->
            PanelRect(x0 / width.toFloat(), y0 / height.toFloat(), (x1 - x0) / width.toFloat(), (y1 - y0) / height.toFloat())
        }.filter { it.area >= MIN_AREA }
        return if (panels.size < 2) listOf(PanelRect.WHOLE) else panels
    }

    /** The commonest brightness around the page's edge (white for most comics, black for some). */
    fun background(luma: IntArray, width: Int, height: Int): Int {
        val counts = IntArray(256)
        val band = maxOf(1, minOf(width, height) / 50)
        for (y in 0 until height) for (x in 0 until width) {
            if (x < band || y < band || x >= width - band || y >= height - band) counts[luma[y * width + x].coerceIn(0, 255) / 8]++
        }
        return counts.indices.maxBy { counts[it] } * 8 + 4
    }

    /**
     * How to show [panel] filling a [screenW] × [screenH] box where the page is drawn fitted
     * (aspect [pageAspect] = width / height): the scale and the shift (in box pixels, applied after
     * scaling from the top-left corner) that put the panel in the middle, with [margin] around it.
     */
    fun fit(panel: PanelRect, pageAspect: Float, screenW: Float, screenH: Float, margin: Float = 0.04f): Triple<Float, Float, Float> {
        val boxAspect = screenW / screenH
        val dw = if (pageAspect > boxAspect) screenW else screenH * pageAspect
        val dh = dw / pageAspect
        val ox = (screenW - dw) / 2
        val oy = (screenH - dh) / 2
        val pw = panel.w * dw
        val ph = panel.h * dh
        val scale = (minOf(screenW / pw, screenH / ph) * (1 - margin)).coerceIn(1f, 6f)
        val cx = ox + (panel.x + panel.w / 2) * dw
        val cy = oy + (panel.y + panel.h / 2) * dh
        return Triple(scale, screenW / 2 - cx * scale, screenH / 2 - cy * scale)
    }
}
