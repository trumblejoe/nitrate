package dev.nitrate.android.art

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import dev.nitrate.core.rows.CressidaInk
import dev.nitrate.core.rows.RowArt
import androidx.compose.ui.graphics.drawscope.clipPath
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Drawing for the Film strip, Lightning split and Cressida row styles, shared by phone and TV. */
object RowArtDrawing {
    /** Film base: nearly black, a little warm. */
    val FilmBase = Color(0xFF14110C)
    private val Sprocket = Color(0xFF2C2A26)

    /** One side of a lightning-split card: [right] is the part right of the bolt. */
    fun boltSide(width: Float, height: Float, right: Boolean) = Path().apply {
        moveTo(if (right) width else 0f, 0f)
        RowArt.bolt.forEach { (x, y) -> lineTo(x * width, y * height) }
        lineTo(if (right) width else 0f, height)
        close()
    }

    /** The marked (monochrome) side of a Cressida card, bounded by a gently bowed seam. */
    fun cressidaMarked(width: Float, height: Float) = Path().apply {
        val s = RowArt.CRESSIDA_SPLIT
        val b = RowArt.CRESSIDA_BOW
        moveTo(0f, 0f)
        lineTo(width * s, 0f)
        cubicTo(width * (s + b), height * 0.3f, width * (s - b), height * 0.65f, width * s, height)
        lineTo(0f, height)
        close()
    }
}

/** Sprocket holes along the top and bottom of a piece of film. */
fun DrawScope.drawSprockets(holeWidth: Float, holeHeight: Float, pitch: Float, margin: Float) {
    var x = pitch / 2 - holeWidth / 2
    while (x < size.width) {
        drawRoundRect(Color(0xFF2C2A26), Offset(x, margin), Size(holeWidth, holeHeight), CornerRadius(holeHeight / 3))
        drawRoundRect(Color(0xFF2C2A26), Offset(x, size.height - margin - holeHeight), Size(holeWidth, holeHeight), CornerRadius(holeHeight / 3))
        x += pitch
    }
}

/** Fine scratches and darkened edges over a frame, like a much-loved print. */
fun DrawScope.drawWornFrame() {
    listOf(0.17f, 0.52f, 0.81f).forEach { f -> drawLine(Color.White.copy(alpha = 0.16f), Offset(size.width * f, 0f), Offset(size.width * (f + 0.02f), size.height), 1f) }
    drawRect(Brush.radialGradient(listOf(Color.Transparent, Color(0x88000000)), radius = size.width * 0.75f))
}

/** The bolt itself: a glow in [glow], then a white-hot core with sparks at the corners. */
fun DrawScope.drawBolt(glow: Color, highlightRight: Boolean? = null) {
    val bolt = Path().apply {
        RowArt.bolt.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * size.width, y * size.height) else lineTo(x * size.width, y * size.height) }
    }
    val scale = size.minDimension / 600f
    listOf(40f to 0.10f, 22f to 0.22f, 10f to 0.55f).forEach { (w, al) ->
        drawPath(bolt, glow.copy(alpha = al), style = Stroke(w * scale.coerceAtLeast(0.6f), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    drawPath(bolt, Color.White, style = Stroke(4f * scale.coerceAtLeast(0.6f), cap = StrokeCap.Round, join = StrokeJoin.Round))
    RowArt.bolt.drop(1).dropLast(1).forEach { (x, y) ->
        val c = Offset(x * size.width, y * size.height)
        repeat(5) { k ->
            val ang = (k * 72 + 20) * PI / 180
            val len = 18f * scale.coerceAtLeast(0.6f)
            drawLine(Color.White.copy(alpha = 0.7f), c, c + Offset((cos(ang) * len).toFloat(), (sin(ang) * len).toFloat()), 2f, StrokeCap.Round)
        }
    }
    // Dim the side not chosen (TV focus), so the chosen one reads as lit.
    if (highlightRight != null) drawPath(RowArtDrawing.boltSide(size.width, size.height, !highlightRight), Color(0x77000000))
}

/** Cressida's ink in the chosen [design]. */
fun DrawScope.drawCressidaInk(ink: Color, design: CressidaInk = CressidaInk.IVY) {
    when (design) {
        CressidaInk.IVY -> drawIvy(ink)
        CressidaInk.TRIBAL -> drawTribal(ink)
        CressidaInk.DRAGON -> drawDragon(ink)
        CressidaInk.WESTERN_DRAGON -> drawWesternDragon(ink)
        CressidaInk.CONSTELLATION -> drawConstellation(ink)
        CressidaInk.WAVES -> drawWaves(ink)
        CressidaInk.DECO -> drawDeco(ink)
        CressidaInk.MAGIC -> drawMagic(ink)
        CressidaInk.SONGBIRD -> drawSongbird(ink)
        CressidaInk.FLAMES -> drawFlames(ink)
        CressidaInk.PHOENIX -> drawPhoenix(ink)
        CressidaInk.GEOMETRY -> drawGeometry(ink)
        CressidaInk.THORNS -> drawThorns(ink)
        CressidaInk.CIRCUIT -> drawCircuit(ink)
    }
}

/** Ivy: a vine down the seam with leaves either side, spirals and a dotted crown. */
private fun DrawScope.drawIvy(ink: Color) {
    val split = RowArt.CRESSIDA_SPLIT
    val unit = unit
    val stroke = Stroke(2.2f * unit, cap = StrokeCap.Round)
    val vine = Path().apply {
        moveTo(size.width * split, -10f)
        cubicTo(size.width * (split + 0.10f), size.height * 0.28f, size.width * (split - 0.12f), size.height * 0.62f, size.width * (split + 0.02f), size.height + 10f)
    }
    drawPath(vine, ink, style = Stroke(3f * unit, cap = StrokeCap.Round))
    for (i in 1..9) {
        val t = i / 10f
        val y = size.height * t
        val x = size.width * (split + 0.05f * sin(t * 2 * PI).toFloat())
        val side = if (i % 2 == 0) 1f else -1f
        rotate(side * 35f + (if (side > 0) 0f else 180f), Offset(x, y)) {
            drawOval(ink, Offset(x, y - 7f * unit), Size(46f * unit, 14f * unit), style = stroke)
            drawLine(ink, Offset(x, y), Offset(x + 40f * unit, y), 1.2f * unit)
        }
    }
    listOf(Offset(0.18f, 0.22f) to 34f, Offset(0.30f, 0.48f) to 22f, Offset(0.14f, 0.70f) to 28f).forEach { (f, r0) ->
        val c = Offset(size.width * f.x, size.height * f.y)
        val r = r0 * unit
        val p = Path()
        for (k in 0..120) {
            val ang = k / 120f * 4 * PI
            val rr = r * k / 120f
            val pt = c + Offset((cos(ang) * rr).toFloat(), (sin(ang) * rr).toFloat())
            if (k == 0) p.moveTo(pt.x, pt.y) else p.lineTo(pt.x, pt.y)
        }
        drawPath(p, ink, style = stroke)
    }
    for (row in 0..3) for (col in 0..8) drawCircle(ink.copy(alpha = 0.7f), 2.2f * unit, Offset((16f + col * 26f + (row % 2) * 13f) * unit, (18f + row * 16f) * unit))
}

/**
 * Stroke widths and sizes scale with the card: designed on a ~1150px-tall phone card, and wide
 * TV cards go by their width so the ink isn't spindly.
 */
private val DrawScope.unit get() = maxOf(size.height, size.width * 0.85f) / 1150f

private fun DrawScope.at(x: Float, y: Float) = Offset(size.width * x, size.height * y)

/** Keeps a pattern on the marked side. */
private inline fun DrawScope.onMarkedSide(block: DrawScope.() -> Unit) =
    clipPath(RowArtDrawing.cressidaMarked(size.width, size.height)) { block() }

/**
 * Tribal: solid tapered blades sweeping out from a spine on the seam, a couple spilling over
 * onto the colour side.
 */
private fun DrawScope.drawTribal(ink: Color) {
    fun blade(base: Offset, tip: Offset, width: Float, bend: Float) {
        val dx = tip.x - base.x
        val dy = tip.y - base.y
        val len = hypot(dx, dy)
        // Unit normal to the blade's direction.
        val nx = -dy / len
        val ny = dx / len
        val a = base + Offset(nx * width / 2, ny * width / 2)
        val b = base - Offset(nx * width / 2, ny * width / 2)
        val mid = Offset((base.x + tip.x) / 2, (base.y + tip.y) / 2)
        val path = Path().apply {
            moveTo(a.x, a.y)
            quadraticTo(mid.x + nx * bend, mid.y + ny * bend, tip.x, tip.y)
            quadraticTo(mid.x + nx * bend * 0.35f, mid.y + ny * bend * 0.35f, b.x, b.y)
            close()
        }
        drawPath(path, ink.copy(alpha = 0.92f))
    }
    val h = size.height
    // The spine: a thick tapering line down the seam.
    val spine = Path().apply {
        moveTo(size.width * 0.47f, 0f)
        cubicTo(size.width * 0.55f, h * 0.3f, size.width * 0.38f, h * 0.65f, size.width * 0.48f, h)
    }
    drawPath(spine, ink, style = Stroke(14f * unit, cap = StrokeCap.Round))
    listOf(
        Triple(at(0.48f, 0.12f), at(0.06f, 0.03f), 0.12f),
        Triple(at(0.49f, 0.27f), at(0.03f, 0.30f), -0.10f),
        Triple(at(0.46f, 0.45f), at(0.10f, 0.58f), 0.12f),
        Triple(at(0.43f, 0.63f), at(0.02f, 0.80f), -0.09f),
        Triple(at(0.46f, 0.82f), at(0.20f, 0.99f), 0.08f),
        // Over the seam onto the colour side.
        Triple(at(0.50f, 0.36f), at(0.66f, 0.22f), 0.06f),
        Triple(at(0.46f, 0.74f), at(0.62f, 0.90f), -0.05f),
    ).forEachIndexed { i, (base, tip, bend) ->
        blade(base, tip, (if (i < 5) 70f else 40f) * unit, bend * h)
    }
    // Hooked chevrons between the blades.
    listOf(0.20f, 0.37f, 0.54f, 0.72f).forEach { y ->
        val c = at(0.30f, y)
        val p = Path().apply {
            moveTo(c.x - 30f * unit, c.y - 18f * unit)
            lineTo(c.x, c.y)
            lineTo(c.x - 30f * unit, c.y + 18f * unit)
        }
        drawPath(p, ink.copy(alpha = 0.8f), style = Stroke(8f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Waves: overlapping wave scales (seigaiha), fading out towards the seam. */
private fun DrawScope.drawWaves(ink: Color) = onMarkedSide {
    val r = size.width * 0.075f
    var row = 0
    var y = -r * 0.2f
    while (y < size.height + r) {
        var x = if (row % 2 == 0) 0f else r
        while (x < size.width * 0.6f) {
            val fade = (1f - x / (size.width * RowArt.CRESSIDA_SPLIT)).coerceIn(0.15f, 1f)
            // Cover the scale behind, then draw its rings.
            drawArc(Color.Black.copy(alpha = 0.45f), 180f, 180f, true, Offset(x - r, y - r), Size(r * 2, r * 2))
            for (k in 1..4) {
                val rr = r * k / 4f
                drawArc(ink.copy(alpha = 0.85f * fade), 180f, 180f, false, Offset(x - rr, y - rr), Size(rr * 2, rr * 2), style = Stroke(2.2f * unit))
            }
            x += r * 2
        }
        y += r * 0.5f
        row++
    }
}

/** Deco: a sunburst rising from the bottom of the seam, with stepped lines and a double rule. */
private fun DrawScope.drawDeco(ink: Color) {
    val centre = at(RowArt.CRESSIDA_SPLIT * 0.55f, 1.02f)
    onMarkedSide {
        for (k in 0..18) {
            val ang = PI + k / 18.0 * PI
            val end = centre + Offset((cos(ang) * size.height * 1.2f).toFloat(), (sin(ang) * size.height * 1.2f).toFloat())
            drawLine(ink.copy(alpha = if (k % 2 == 0) 0.7f else 0.35f), centre, end, (if (k % 2 == 0) 2.4f else 1.2f) * unit)
        }
        listOf(0.18f, 0.30f, 0.44f, 0.6f).forEach { f ->
            val rr = size.height * f
            drawArc(ink.copy(alpha = 0.8f), 180f, 180f, false, centre - Offset(rr, rr), Size(rr * 2, rr * 2), style = Stroke(3f * unit))
        }
        // Stepped, ziggurat lines across the top.
        val step = size.width * 0.06f
        val stairs = Path().apply {
            moveTo(0f, size.height * 0.08f)
            var x = 0f
            var up = true
            while (x < size.width * 0.6f) {
                lineTo(x + step, if (up) size.height * 0.08f else size.height * 0.13f)
                x += step
                lineTo(x, if (up) size.height * 0.13f else size.height * 0.08f)
                up = !up
            }
        }
        drawPath(stairs, ink, style = Stroke(2.4f * unit))
    }
    // A double rule following the seam.
    listOf(-6f, 6f).forEach { d ->
        val rule = Path().apply {
            val s = RowArt.CRESSIDA_SPLIT
            val b = RowArt.CRESSIDA_BOW
            moveTo(size.width * s + d * unit, 0f)
            cubicTo(size.width * (s + b) + d * unit, size.height * 0.3f, size.width * (s - b) + d * unit, size.height * 0.65f, size.width * s + d * unit, size.height)
        }
        drawPath(rule, ink, style = Stroke(2f * unit))
    }
}

/** A fixed sequence of "random" numbers, so a design always comes out the same. */
private class Seeded(var seed: Long) {
    fun next(): Float { seed = (seed * 1103515245 + 12345) and 0x7fffffff; return (seed % 10000) / 10000f }
}

/** Centre and size of a motif that sits on the marked side. */
private val DrawScope.motifCentre get() = at(RowArt.CRESSIDA_SPLIT * 0.52f, 0.46f)
private val DrawScope.motifRadius get() = minOf(size.width * RowArt.CRESSIDA_SPLIT * 0.46f, size.height * 0.34f)

/** Magic: a glowing arcane sigil (rings of runes, a six-point star) with sparkles around it. */
private fun DrawScope.drawMagic(ink: Color) {
    val c = motifCentre
    val r = motifRadius
    drawCircle(Brush.radialGradient(listOf(ink.copy(alpha = 0.35f), Color.Transparent), center = c, radius = r * 1.5f), r * 1.5f, c)
    listOf(1f, 0.84f, 0.5f).forEach { f -> drawCircle(ink, r * f, c, style = Stroke((if (f == 1f) 3f else 1.8f) * unit)) }
    // Runes between the two outer rings: little glyphs of two or three strokes.
    val rnd = Seeded(11)
    val glyphs = 18
    for (g in 0 until glyphs) {
        val ang = g / glyphs.toFloat() * 2 * PI
        val p = c + Offset((cos(ang) * r * 0.92f).toFloat(), (sin(ang) * r * 0.92f).toFloat())
        val s = r * 0.05f
        repeat(2 + (rnd.next() * 2).toInt()) {
            val a = Offset((rnd.next() - 0.5f) * 2 * s, (rnd.next() - 0.5f) * 2 * s)
            val b = Offset((rnd.next() - 0.5f) * 2 * s, (rnd.next() - 0.5f) * 2 * s)
            drawLine(ink, p + a, p + b, 1.8f * unit, StrokeCap.Round)
        }
    }
    // Six-point star of two triangles.
    listOf(0.0, PI).forEach { turn ->
        val tri = Path().apply {
            for (k in 0..3) {
                val ang = turn + k * 2 * PI / 3 - PI / 2
                val p = c + Offset((cos(ang) * r * 0.82f).toFloat(), (sin(ang) * r * 0.82f).toFloat())
                if (k == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
        }
        drawPath(tri, ink, style = Stroke(2f * unit, join = StrokeJoin.Round))
    }
    drawCircle(ink, r * 0.08f, c)
    // Sparkles drifting off the sigil.
    repeat(14) {
        val p = at(rnd.next() * 0.55f, rnd.next())
        val s = (6f + rnd.next() * 12f) * unit
        drawLine(ink.copy(alpha = 0.8f), p - Offset(s, 0f), p + Offset(s, 0f), 1.4f * unit, StrokeCap.Round)
        drawLine(ink.copy(alpha = 0.8f), p - Offset(0f, s), p + Offset(0f, s), 1.4f * unit, StrokeCap.Round)
    }
}

/** Geometry: a flower of life inside a ring. */
private fun DrawScope.drawGeometry(ink: Color) {
    val c = motifCentre
    val r = motifRadius / 3f
    val centres = mutableListOf(c)
    for (ring in 1..2) for (k in 0 until 6 * ring) {
        // Hexagonal rings of centres.
        val side = k / ring
        val step = k % ring
        val a0 = side * PI / 3
        val a1 = (side + 1) * PI / 3
        val p0 = Offset((cos(a0) * r * ring).toFloat(), (sin(a0) * r * ring).toFloat())
        val p1 = Offset((cos(a1) * r * ring).toFloat(), (sin(a1) * r * ring).toFloat())
        centres += c + p0 + (p1 - p0) * (step / ring.toFloat())
    }
    centres.forEach { drawCircle(ink.copy(alpha = 0.8f), r, it, style = Stroke(1.6f * unit)) }
    drawCircle(ink, r * 3f, c, style = Stroke(3f * unit))
    drawCircle(ink, r * 3.15f, c, style = Stroke(1.4f * unit))
}

/** Thorns: a barbed vine winding down the seam, and a spiral rose. */
private fun DrawScope.drawThorns(ink: Color) {
    val steps = 60
    val pts = List(steps + 1) { i ->
        val t = i / steps.toFloat()
        at(RowArt.CRESSIDA_SPLIT - 0.04f + 0.07f * sin(t * 5 * PI).toFloat(), t)
    }
    val vine = Path().apply { pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
    drawPath(vine, ink, style = Stroke(4f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
    for (i in 2 until steps step 3) {
        val p = pts[i]
        val d = pts[i + 1] - pts[i - 1]
        val len = hypot(d.x, d.y)
        val n = Offset(-d.y / len, d.x / len) * (if (i % 2 == 0) 1f else -1f)
        val along = d * (8f * unit / len)
        val thorn = Path().apply {
            moveTo(p.x - along.x, p.y - along.y)
            lineTo(p.x + n.x * 26f * unit + along.x, p.y + n.y * 26f * unit + along.y)
            lineTo(p.x + along.x, p.y + along.y)
            close()
        }
        drawPath(thorn, ink)
    }
    // The rose: a tight spiral of petals near the top of the marked side.
    val c = at(RowArt.CRESSIDA_SPLIT * 0.4f, 0.22f)
    val r = motifRadius * 0.5f
    val spiral = Path()
    for (k in 0..200) {
        val ang = k / 200f * 6 * PI
        val rr = r * k / 200f * (1 + 0.12f * sin(ang * 3).toFloat())
        val p = c + Offset((cos(ang) * rr).toFloat(), (sin(ang) * rr).toFloat())
        if (k == 0) spiral.moveTo(p.x, p.y) else spiral.lineTo(p.x, p.y)
    }
    drawPath(spiral, ink, style = Stroke(2.4f * unit, cap = StrokeCap.Round))
    // Its stem joins the vine.
    drawLine(ink, c + Offset(r * 0.6f, r * 0.8f), at(RowArt.CRESSIDA_SPLIT - 0.03f, 0.40f), 3f * unit, StrokeCap.Round)
}

/** Circuit: traces with 45° bends running from the seam, ending in pads, and a chip. */
private fun DrawScope.drawCircuit(ink: Color) {
    val rnd = Seeded(41)
    val stroke = Stroke(2.2f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)
    for (k in 0 until 11) {
        val y = size.height * (0.05f + k * 0.09f)
        val start = at(RowArt.CRESSIDA_SPLIT, 0f).copy(y = y)
        val run1 = size.width * (0.05f + rnd.next() * 0.12f)
        val diag = size.width * (0.03f + rnd.next() * 0.05f) * (if (rnd.next() > 0.5f) 1f else -1f)
        val run2 = size.width * (0.05f + rnd.next() * 0.15f)
        val p1 = start - Offset(run1, 0f)
        val p2 = p1 - Offset(kotlin.math.abs(diag), diag)
        val p3 = p2 - Offset(run2, 0f)
        val trace = Path().apply { moveTo(start.x, start.y); lineTo(p1.x, p1.y); lineTo(p2.x, p2.y); lineTo(p3.x, p3.y) }
        drawPath(trace, ink.copy(alpha = 0.85f), style = stroke)
        drawCircle(ink, 7f * unit, p3, style = Stroke(2f * unit))
        drawCircle(ink, 2.5f * unit, p3)
    }
    // A chip the traces lead to.
    val c = at(RowArt.CRESSIDA_SPLIT * 0.45f, 0.5f)
    val s = motifRadius * 0.45f
    drawRect(Color.Black.copy(alpha = 0.5f), c - Offset(s, s), Size(s * 2, s * 2))
    drawRect(ink, c - Offset(s, s), Size(s * 2, s * 2), style = Stroke(3f * unit))
    for (k in 0 until 6) {
        val o = -s + (k + 0.5f) * (2 * s / 6)
        drawLine(ink, c + Offset(-s, o), c + Offset(-s - 14f * unit, o), 2f * unit)
        drawLine(ink, c + Offset(s, o), c + Offset(s + 14f * unit, o), 2f * unit)
        drawLine(ink, c + Offset(o, -s), c + Offset(o, -s - 14f * unit), 2f * unit)
        drawLine(ink, c + Offset(o, s), c + Offset(o, s + 14f * unit), 2f * unit)
    }
}

// ---- Shared shapes for the figurative inks ----

private val Shadow = Color.Black

/** A smooth curve through [points] (Catmull-Rom as cubics). */
private fun smooth(points: List<Offset>, closed: Boolean = false): Path = Path().apply {
    if (points.isEmpty()) return@apply
    val n = points.size
    fun pt(i: Int) = if (closed) points[(i + n) % n] else points[i.coerceIn(0, n - 1)]
    moveTo(points[0].x, points[0].y)
    val last = if (closed) n else n - 1
    for (i in 0 until last) {
        val p0 = pt(i - 1); val p1 = pt(i); val p2 = pt(i + 1); val p3 = pt(i + 2)
        cubicTo(
            p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
            p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
            p2.x, p2.y,
        )
    }
    if (closed) close()
}

/** Unit normals along a centre line. */
private fun normals(centre: List<Offset>): List<Offset> = centre.indices.map { i ->
    val a = centre[(i - 1).coerceAtLeast(0)]
    val b = centre[(i + 1).coerceAtMost(centre.lastIndex)]
    val len = hypot(b.x - a.x, b.y - a.y).coerceAtLeast(0.001f)
    Offset(-(b.y - a.y) / len, (b.x - a.x) / len)
}

/** A body around a [centre] line, [width] wide at each fraction along it (0 = start). */
private fun taper(centre: List<Offset>, width: (Float) -> Float): Path {
    val n = normals(centre)
    val last = (centre.size - 1).coerceAtLeast(1).toFloat()
    val left = centre.indices.map { centre[it] + n[it] * (width(it / last) / 2) }
    val right = centre.indices.map { centre[it] - n[it] * (width(it / last) / 2) }
    return smooth(left + right.asReversed(), closed = true)
}

/** Places local drawing coordinates: x forward along [angleDeg], y to its right, both times [scale]. */
private class Frame(val origin: Offset, angleDeg: Float, val scale: Float, val mirror: Boolean = false) {
    private val c = cos(angleDeg * PI / 180).toFloat()
    private val s = sin(angleDeg * PI / 180).toFloat()
    fun p(x: Float, y: Float): Offset {
        val yy = if (mirror) -y else y
        return origin + Offset((x * c - yy * s) * scale, (x * s + yy * c) * scale)
    }
    fun path(vararg pts: Pair<Float, Float>, closed: Boolean = true) = smooth(pts.map { p(it.first, it.second) }, closed)
}

/** Line art, like the other inks: a faint dark wash inside, the outline in ink. */
private fun DrawScope.inked(path: Path, ink: Color, line: Float, alpha: Float = 1f) {
    drawPath(path, Shadow.copy(alpha = 0.35f))
    drawPath(path, ink.copy(alpha = alpha), style = Stroke(line, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** A curling flame tongue along [centre], [width] at its root, tapering to a point. */
private fun DrawScope.flameTongue(centre: List<Offset>, width: Float, ink: Color, alpha: Float, keyline: Float, inner: Boolean = true) {
    val body = taper(centre) { t -> (width * (1f - t).let { it * kotlin.math.sqrt(it) } * (1f + 1.1f * t)).coerceAtLeast(width * 0.02f) }
    drawPath(body, Shadow.copy(alpha = 0.3f))
    drawPath(body, ink.copy(alpha = alpha), style = Stroke(keyline, join = StrokeJoin.Round))
    if (inner && centre.size > 6) {
        // The inner tongue: a second, finer outline following the outer one.
        val core = centre.take((centre.size * 0.62f).toInt()).drop(1)
        val cut = taper(core) { t -> width * 0.38f * (1f - t) }
        drawPath(cut, ink.copy(alpha = alpha * 0.6f), style = Stroke(keyline * 0.7f, join = StrokeJoin.Round))
    }
}

/** A sinuous line from [from], [length] long, heading [angleDeg], waving [amp] with a hook at the end. */
private fun wavyLine(from: Offset, angleDeg: Float, length: Float, amp: Float, waves: Float, hook: Float, steps: Int = 24): List<Offset> {
    val f = Frame(from, angleDeg, 1f)
    return List(steps + 1) { i ->
        val t = i / steps.toFloat()
        val bend = hook * t * t * t
        f.p(length * t, amp * sin(t * waves * PI).toFloat() * t + bend)
    }
}

/**
 * Dragon: an eastern dragon in fine line coiling down the marked side, crested, scaled and
 * clawed, head lowered and lunging with jaws wide, fangs bared, horns swept back and whiskers
 * streaming, a flame tuft on its tail.
 */
private fun DrawScope.drawDragon(ink: Color) {
    val w = size.width * RowArt.CRESSIDA_SPLIT
    val line = 2.4f * unit
    val fine = 1.4f * unit
    val steps = 120
    val centre = List(steps + 1) { i ->
        val t = i / steps.toFloat()
        Offset(w * (0.48f + 0.34f * sin(t * 3.3f * PI + 0.35f).toFloat() * (1f - 0.25f * t)), size.height * (0.22f + 0.68f * t))
    }
    fun thick(t: Float) = (60f * (1f - t) + 9f) * unit
    val n = normals(centre)
    val body = taper(centre) { thick(it) }

    // Dorsal crest: hooked blades raking back towards the tail.
    for (i in 5 until steps - 6 step 5) {
        val t = i / steps.toFloat()
        val base = centre[i] + n[i] * (thick(t) * 0.42f)
        val back = centre[(i + 4).coerceAtMost(steps)] - centre[i]
        val tip = base + n[i] * (thick(t) * 0.5f + 6f * unit) + back * 0.9f
        val fin = smooth(listOf(base - back * 0.5f, base + n[i] * thick(t) * 0.45f + back * 0.2f, tip, base + back * 0.55f))
        drawPath(fin, Shadow.copy(alpha = 0.35f))
        drawPath(fin, ink, style = Stroke(fine * 1.3f, join = StrokeJoin.Round, cap = StrokeCap.Round))
    }
    // Legs reaching forward, talons splayed.
    listOf(0.20f, 0.58f).forEachIndexed { k, t ->
        val i = (t * steps).toInt()
        val side = if (k == 0) -1f else 1f
        val hip = centre[i] - n[i] * side * thick(t) * 0.35f
        val knee = hip - n[i] * side * thick(t) * 1.0f + Offset(side * 6f * unit, 16f * unit)
        val foot = knee + Offset(side * 22f * unit, 18f * unit)
        val limb = taper(listOf(hip, knee, foot)) { thick(t) * (0.42f - 0.22f * it) }
        inked(limb, ink, fine * 1.4f)
        repeat(4) { c ->
            val a = (if (side > 0) 10f else 170f) + (c - 1.5f) * 28f
            val claw = wavyLine(foot, a, 24f * unit, 0f, 0f, 9f * unit, 8)
            drawPath(smooth(claw), ink, style = Stroke(line, cap = StrokeCap.Round))
        }
    }

    inked(body, ink, line)
    // Scales: fine crescents in staggered rows, and belly plates along the other edge.
    for (i in 4 until steps - 4 step 3) {
        val t = i / steps.toFloat()
        val along = atan2(centre[i + 1].y - centre[i].y, centre[i + 1].x - centre[i].x) * 180f / PI.toFloat()
        val r = thick(t) * 0.17f
        val lanes = if ((i / 3) % 2 == 0) listOf(-0.22f, 0.12f) else listOf(-0.05f, 0.29f)
        lanes.forEach { lane ->
            val c = centre[i] + n[i] * (thick(t) * lane)
            drawArc(ink.copy(alpha = 0.7f), along + 90f, 180f, false, c - Offset(r, r), Size(r * 2, r * 2), style = Stroke(fine, cap = StrokeCap.Round))
        }
        val edge = centre[i] - n[i] * (thick(t) * 0.48f)
        drawLine(ink.copy(alpha = 0.5f), edge, edge + n[i] * (thick(t) * 0.16f), fine)
    }

    // Tail: a tuft of flame.
    val tail = centre.last()
    listOf(-35f, 0f, 35f).forEach { d ->
        flameTongue(wavyLine(tail, 100f + d, 70f * unit, 8f * unit, 1.2f, 10f * unit), 18f * unit, ink, 1f, fine * 1.3f, inner = false)
    }

    // Head: lowered and lunging to the right, jaws wide.
    val neck = centre.first()
    val hs = 56f * unit
    val f = Frame(neck + Offset(12f * unit, -6f * unit), -12f, hs)
    // Mane: flames streaming back off the neck.
    repeat(4) { k ->
        val a = 140f + k * 22f
        flameTongue(wavyLine(f.p(-0.35f, 0.15f), a - 12f, 48f * unit, 8f * unit, 1.2f, -14f * unit), 26f * unit, ink, 0.9f, fine * 1.2f, inner = false)
    }
    // Horns: thick at the root, curving back and up to sharp points, ridged.
    listOf(Triple(0.1f, -0.7f, 1f), Triple(-0.2f, -0.45f, 0.75f)).forEach { (x, y, k) ->
        val pts = listOf(f.p(x, y), f.p(x - 0.7f * k, y - 0.35f * k), f.p(x - 1.3f * k, y - 0.95f * k), f.p(x - 1.45f * k, y - 1.55f * k))
        val horn = taper(pts) { t -> hs * 0.34f * k * (1f - t) + 0.6f * unit }
        inked(horn, ink, fine * 1.3f)
        for (j in 1..3) {
            val c = pts[j.coerceAtMost(2)] + (pts[(j).coerceAtMost(3)] - pts[j - 1]) * 0.0f
            drawCircle(ink.copy(alpha = 0.6f), 1.2f * unit, c)
        }
    }
    // Jaw spikes raking back from the cheek.
    listOf(0.15f to 0.30f, -0.15f to 0.55f, -0.4f to 0.78f).forEach { (x, y) ->
        val spike = taper(listOf(f.p(x + 0.2f, y - 0.1f), f.p(x - 0.45f, y + 0.3f))) { t -> hs * 0.22f * (1f - t) + 0.5f * unit }
        inked(spike, ink, fine * 1.2f)
    }
    // Skull and upper jaw: a heavy brow, a long snout with a flared nose.
    val upper = f.path(
        -0.55f to -0.25f, -0.1f to -0.72f, 0.5f to -0.78f, 0.75f to -0.62f, 1.4f to -0.48f, 2.05f to -0.42f,
        2.35f to -0.55f, 2.45f to -0.3f, 2.3f to -0.05f, 1.6f to 0.02f, 0.75f to 0.08f, 0.2f to 0.3f, -0.4f to 0.25f,
    )
    // Lower jaw dropped wide open.
    val lower = f.path(0.7f to 0.2f, 1.25f to 0.55f, 1.85f to 0.95f, 2.05f to 1.05f, 1.95f to 1.2f, 1.2f to 1.0f, 0.5f to 0.65f, 0.2f to 0.42f)
    // Forked tongue lashing out of the mouth.
    val tongue = smooth(wavyLine(f.p(0.9f, 0.35f), 18f - 12f, 110f * unit, 10f * unit, 1.6f, 6f * unit, 20))
    drawPath(tongue, ink, style = Stroke(fine * 1.6f, cap = StrokeCap.Round))
    val tip = f.p(0.9f, 0.35f) + Offset(cos((6f) * PI / 180).toFloat(), sin(6f * PI / 180).toFloat()) * (110f * unit)
    drawLine(ink, tip, tip + Offset(16f * unit, -10f * unit), fine * 1.4f, StrokeCap.Round)
    drawLine(ink, tip, tip + Offset(16f * unit, 8f * unit), fine * 1.4f, StrokeCap.Round)
    inked(lower, ink, line)
    inked(upper, ink, line)
    // Fangs: long ones top and bottom, and a row of smaller teeth.
    listOf(1.05f to 0.55f, 1.95f to 0.5f).forEach { (x, len) -> drawPath(f.path(x - 0.1f to 0.0f, x + 0.1f to -0.02f, x + 0.02f to len), ink) }
    listOf(1.35f, 1.6f).forEach { x -> drawPath(f.path(x - 0.06f to 0.02f, x + 0.06f to 0.01f, x to 0.22f), ink) }
    listOf(1.2f to 0.62f, 1.7f to 0.9f).forEach { (x, y) -> drawPath(f.path(x - 0.09f to y, x + 0.09f to y + 0.06f, x - 0.08f to y - 0.38f), ink) }
    // Nostril, cheek line, the angry brow and a narrow slit eye.
    drawPath(smooth(listOf(f.p(2.15f, -0.36f), f.p(2.3f, -0.42f), f.p(2.28f, -0.28f))), ink, style = Stroke(fine * 1.3f, cap = StrokeCap.Round))
    drawPath(smooth(listOf(f.p(0.2f, 0.12f), f.p(0.6f, -0.05f), f.p(1.3f, -0.12f))), ink.copy(alpha = 0.6f), style = Stroke(fine, cap = StrokeCap.Round))
    drawPath(smooth(listOf(f.p(0.2f, -0.62f), f.p(0.75f, -0.5f), f.p(1.2f, -0.28f))), ink, style = Stroke(line * 1.5f, cap = StrokeCap.Round))
    val eye = f.path(0.55f to -0.36f, 0.82f to -0.42f, 1.05f to -0.26f, 0.8f to -0.25f)
    drawPath(eye, ink)
    drawLine(Shadow, f.p(0.82f, -0.40f), f.p(0.84f, -0.26f), 1.8f * unit, StrokeCap.Round)
    // Whiskers streaming back from the snout.
    listOf(-0.3f to 150f, 0.05f to 125f).forEach { (y, a) ->
        val whisker = smooth(wavyLine(f.p(2.2f, y), a - 12f, 120f * unit, 14f * unit, 1.4f, -26f * unit, 30))
        drawPath(whisker, ink, style = Stroke(fine * 1.5f, cap = StrokeCap.Round))
    }
}

/**
 * Constellation: a star chart: rings and ticks of a celestial grid, a band of the Milky Way,
 * an original figure of a crane picked out in bright stars, a crescent moon and a comet.
 */
private fun DrawScope.drawConstellation(ink: Color) {
    val w = size.width * RowArt.CRESSIDA_SPLIT
    val rnd = Seeded(7)
    onMarkedSide {
        // Celestial grid: rings, meridians and a ticked rim, centred off the card's edge.
        val gc = Offset(w * 0.15f, size.height * 0.58f)
        val gr = size.height * 0.42f
        listOf(1f, 0.72f, 0.44f).forEach { f -> drawCircle(ink.copy(alpha = 0.28f), gr * f, gc, style = Stroke(1.2f * unit)) }
        for (k in 0 until 12) {
            val a = k * PI / 6
            drawLine(ink.copy(alpha = 0.18f), gc, gc + Offset((cos(a) * gr).toFloat(), (sin(a) * gr).toFloat()), 1f * unit)
        }
        for (k in 0 until 72) {
            val a = k * PI / 36
            val len = if (k % 6 == 0) 16f else 8f
            val o = gc + Offset((cos(a) * gr).toFloat(), (sin(a) * gr).toFloat())
            val i = gc + Offset((cos(a) * (gr - len * unit)).toFloat(), (sin(a) * (gr - len * unit)).toFloat())
            drawLine(ink.copy(alpha = 0.4f), o, i, 1.2f * unit)
        }
        // The Milky Way: a soft band of dust across the marked side.
        repeat(420) {
            val t = rnd.next()
            val spread = ((rnd.next() + rnd.next() + rnd.next()) / 3f - 0.5f) * size.height * 0.16f
            val p = Offset(w * (-0.1f + 1.2f * t), size.height * (0.92f - 0.75f * t) + spread)
            drawCircle(ink.copy(alpha = 0.12f + rnd.next() * 0.3f), (0.6f + rnd.next() * 1.4f) * unit, p)
        }
    }

    // The crane: neck stretched up, wings raised, legs trailing. Coordinates on the marked side.
    val crane = listOf(
        0.78f to 0.30f, 0.70f to 0.37f, 0.60f to 0.46f, // beak, head, neck
        0.48f to 0.52f, // body
        0.20f to 0.40f, 0.10f to 0.28f, // left wing
        0.66f to 0.62f, 0.86f to 0.56f, // right wing
        0.38f to 0.66f, 0.30f to 0.80f, 0.42f to 0.84f, // legs
    ).map { (x, y) -> Offset(w * x, size.height * y) }
    val links = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4, 4 to 5, 3 to 6, 6 to 7, 3 to 8, 8 to 9, 8 to 10)
    links.forEach { (a, b) -> drawLine(ink.copy(alpha = 0.75f), crane[a], crane[b], 1.6f * unit, StrokeCap.Round) }
    val bright = setOf(1, 3, 5, 7, 9)
    crane.forEachIndexed { i, p ->
        if (i in bright) {
            val r = 30f * unit
            drawCircle(Brush.radialGradient(listOf(ink.copy(alpha = 0.55f), Color.Transparent), center = p, radius = r), r, p)
            // A four-point glint: long thin rays.
            drawLine(ink, p - Offset(r * 0.9f, 0f), p + Offset(r * 0.9f, 0f), 1.4f * unit, StrokeCap.Round)
            drawLine(ink, p - Offset(0f, r * 0.9f), p + Offset(0f, r * 0.9f), 1.4f * unit, StrokeCap.Round)
            drawCircle(Color.White, 4.5f * unit, p)
        } else {
            drawCircle(ink, 3.5f * unit, p)
        }
    }

    // Crescent moon with a glow, near the top.
    val moon = Offset(w * 0.30f, size.height * 0.12f)
    val r = 48f * unit
    drawCircle(Brush.radialGradient(listOf(ink.copy(alpha = 0.35f), Color.Transparent), center = moon, radius = r * 2.2f), r * 2.2f, moon)
    val disc = Path().apply { addOval(androidx.compose.ui.geometry.Rect(moon - Offset(r, r), Size(r * 2, r * 2))) }
    val bite = Path().apply { addOval(androidx.compose.ui.geometry.Rect(moon - Offset(r * 0.45f, r * 1.15f), Size(r * 2, r * 2))) }
    drawPath(Path.combine(androidx.compose.ui.graphics.PathOperation.Difference, disc, bite), ink)

    // A comet streaking down towards the seam.
    val head = Offset(w * 0.86f, size.height * 0.82f)
    val tail = taper(listOf(head, head + Offset(-w * 0.25f, -size.height * 0.10f), head + Offset(-w * 0.55f, -size.height * 0.17f))) { t -> 18f * unit * (1f - t) }
    drawPath(tail, Brush.linearGradient(listOf(ink.copy(alpha = 0.9f), Color.Transparent), start = head, end = head + Offset(-w * 0.55f, -size.height * 0.17f)))
    drawCircle(Color.White, 6f * unit, head)

    // Faint background stars.
    repeat(40) { drawCircle(ink.copy(alpha = 0.5f + rnd.next() * 0.4f), (1.2f + rnd.next() * 1.8f) * unit, Offset(w * rnd.next() * 1.05f, size.height * rnd.next())) }
}

/** One swallow in flight, from above: scythe wings, forked tail, solid ink with feather lines. */
private fun DrawScope.swallow(f: Frame, ink: Color, key: Float) {
    listOf(1f, -1f).forEach { side ->
        // Wing: leading edge bows forward, trailing edge cut into feathers.
        val wing = f.path(
            0.25f to 0.05f * side, 0.05f to 0.55f * side, -0.35f to 1.05f * side, -0.75f to 1.35f * side,
            -0.55f to 1.0f * side, -0.45f to 0.75f * side, -0.25f to 0.45f * side, -0.15f to 0.12f * side,
        )
        inked(wing, ink, key)
        for (k in 1..4) {
            val t = k / 5f
            drawLine(ink.copy(alpha = 0.6f), f.p(-0.05f - 0.5f * t, (0.15f + 0.8f * t) * side), f.p(-0.25f - 0.45f * t, (0.12f + 0.62f * t) * side), 1.2f * unit, StrokeCap.Round)
        }
    }
    // Body and forked tail.
    val body = f.path(0.75f to 0f, 0.55f to -0.14f, 0.1f to -0.16f, -0.45f to -0.08f, -1.15f to -0.42f, -0.75f to 0f, -1.15f to 0.42f, -0.45f to 0.08f, 0.1f to 0.16f, 0.55f to 0.14f)
    inked(body, ink, key)
    // Head: the cap's edge and an eye.
    drawPath(smooth(listOf(f.p(0.35f, -0.12f), f.p(0.42f, 0f), f.p(0.35f, 0.12f))), ink.copy(alpha = 0.7f), style = Stroke(1.2f * unit, cap = StrokeCap.Round))
    drawCircle(ink, 2.4f * unit, f.p(0.58f, -0.05f))
}

/** An eighth note, tilted. */
private fun DrawScope.note(at: Offset, s: Float, ink: Color, tilt: Float) {
    rotate(tilt, at) {
        drawOval(ink, at - Offset(s * 0.55f, s * 0.38f), Size(s * 1.1f, s * 0.76f), style = Stroke(0.14f * s))
        drawLine(ink, at + Offset(s * 0.5f, 0f), at + Offset(s * 0.5f, -s * 2.4f), 0.16f * s, StrokeCap.Round)
        drawPath(smooth(listOf(at + Offset(s * 0.5f, -s * 2.4f), at + Offset(s * 1.2f, -s * 1.8f), at + Offset(s * 1.0f, -s * 1.2f))), ink, style = Stroke(0.16f * s, cap = StrokeCap.Round))
    }
}

/** Songbird: two swallows, one diving, one wheeling above, a looping flight line and drifting notes. */
private fun DrawScope.drawSongbird(ink: Color) {
    val w = size.width * RowArt.CRESSIDA_SPLIT
    val key = 2f * unit
    // The flight line: a dashed loop between the birds.
    val trail = smooth(listOf(Offset(w * 0.75f, size.height * 0.17f), Offset(w * 0.15f, size.height * 0.30f), Offset(w * 0.30f, size.height * 0.44f), Offset(w * 0.70f, size.height * 0.40f), Offset(w * 0.55f, size.height * 0.58f)))
    drawPath(trail, ink.copy(alpha = 0.6f), style = Stroke(2f * unit, cap = StrokeCap.Round, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f * unit, 10f * unit))))
    swallow(Frame(Offset(w * 0.58f, size.height * 0.70f), 60f, 95f * unit), ink, 2.2f * unit)
    swallow(Frame(Offset(w * 0.60f, size.height * 0.15f), -15f, 70f * unit), ink, 1.8f * unit)
    note(Offset(w * 0.18f, size.height * 0.50f), 22f * unit, ink, -12f)
    note(Offset(w * 0.80f, size.height * 0.36f), 16f * unit, ink.copy(alpha = 0.8f), 10f)
    note(Offset(w * 0.14f, size.height * 0.88f), 18f * unit, ink.copy(alpha = 0.7f), -20f)
}

/**
 * Flames: tattoo-style fire rising up the marked side: rows of curling tongues with dark inner
 * cuts, wisps breaking free above them, and sparks.
 */
private fun DrawScope.drawFlames(ink: Color) {
    val w = size.width * RowArt.CRESSIDA_SPLIT
    val h = size.height
    val rnd = Seeded(5)
    val line = 2.4f * unit
    // The fire's edge: peaks that rise and hook over at the tip, valleys between.
    val peaks = 3
    val heights = List(peaks) { k -> h * (if (k == 1) 0.48f else 0.30f + rnd.next() * 0.08f) }
    val hooks = List(peaks) { if (rnd.next() > 0.5f) 1f else -1f }
    fun edge(scale: Float, inset: Float): Path {
        val pts = mutableListOf(Offset(-w * 0.05f, h + 10f))
        val step = w * 1.15f / peaks
        for (k in 0 until peaks) {
            val x0 = -w * 0.05f + k * step
            val tipH = heights[k] * scale
            val hook = hooks[k] * step * 0.22f
            pts += Offset(x0 + step * 0.04f + inset, h - tipH * 0.22f) // valley
            pts += Offset(x0 + step * 0.16f + inset - hook * 0.4f, h - tipH * 0.55f) // belly swelling out
            pts += Offset(x0 + step * 0.42f + hook * 0.1f, h - tipH * 0.86f)
            pts += Offset(x0 + step * 0.5f + hook, h - tipH) // the tip, flicked over
            pts += Offset(x0 + step * 0.58f, h - tipH * 0.8f)
            pts += Offset(x0 + step * 0.78f - inset + hook * 0.3f, h - tipH * 0.48f)
        }
        pts += Offset(w * 1.05f, h + 10f)
        return smooth(pts, closed = true)
    }
    val outer = edge(1f, 0f)
    drawPath(outer, Shadow.copy(alpha = 0.35f))
    drawPath(outer, ink, style = Stroke(line, join = StrokeJoin.Round))
    // Inner contours, each lower and fainter.
    drawPath(edge(0.66f, w * 0.03f), ink.copy(alpha = 0.7f), style = Stroke(line * 0.75f, join = StrokeJoin.Round))
    drawPath(edge(0.36f, w * 0.06f), ink.copy(alpha = 0.45f), style = Stroke(line * 0.6f, join = StrokeJoin.Round))
    // Wisps broken free above the fire.
    repeat(4) {
        val start = Offset(w * (0.12f + rnd.next() * 0.76f), h * (0.32f + rnd.next() * 0.14f))
        val dir = if (rnd.next() > 0.5f) 1f else -1f
        flameTongue(wavyLine(start, -90f + (rnd.next() - 0.5f) * 30f, h * (0.07f + rnd.next() * 0.04f), 20f * unit * dir, 1.2f, 26f * unit * dir, 22), 26f * unit, ink, 0.9f, line * 0.8f, inner = false)
    }
    // Sparks.
    repeat(26) { drawCircle(ink.copy(alpha = 0.5f + rnd.next() * 0.5f), (1.5f + rnd.next() * 2.5f) * unit, Offset(w * rnd.next(), h * (0.05f + rnd.next() * 0.45f))) }
}

/**
 * Phoenix: an original phoenix rising inside a broken ring, wings raised in a V of flame-tipped
 * feathers, crested head turned to the side, and a long tail of fire breaking out of the ring.
 */
private fun DrawScope.drawPhoenix(ink: Color) {
    // Sized so the raised wings and the tail fit the card, tall (phone) or wide (TV).
    val r = minOf(size.width * RowArt.CRESSIDA_SPLIT * 0.5f, size.height * 0.24f)
    val c = Offset(size.width * RowArt.CRESSIDA_SPLIT * 0.56f, maxOf(size.height * 0.40f, r * 1.75f + size.height * 0.04f))
    val key = 2.2f * unit
    // Glow behind.
    drawCircle(Brush.radialGradient(listOf(ink.copy(alpha = 0.3f), Color.Transparent), center = c, radius = r * 1.4f), r * 1.4f, c)
    // The ring, open at the bottom where the tail breaks through.
    val box = c - Offset(r, r)
    drawArc(ink, 120f, 300f, false, box, Size(r * 2, r * 2), style = Stroke(3f * unit, cap = StrokeCap.Round))
    val inner = r * 0.9f
    drawArc(ink.copy(alpha = 0.6f), 125f, 290f, false, c - Offset(inner, inner), Size(inner * 2, inner * 2), style = Stroke(1.6f * unit))

    // The bird is bigger than the ring: its wings break out over the top.
    val b = r * 1.3f
    // Tail: three long feathers of fire pouring down out of the ring.
    listOf(-16f, 0f, 16f).forEachIndexed { i, d ->
        val dir = if (i == 0) -1f else 1f
        val centre = wavyLine(c + Offset(0f, b * 0.28f), 90f + d, b * (1.4f - 0.15f * kotlin.math.abs(i - 1)), b * 0.14f * dir, 1.6f, b * 0.22f * dir, 36)
        flameTongue(centre, b * 0.30f, ink, 1f, key)
        val end = centre[centre.size - 6]
        drawCircle(ink, b * 0.045f, end, style = Stroke(1.4f * unit))
        drawCircle(ink, b * 0.015f, end)
    }

    // Wings raised in a V: a solid wing, then a fan of flame feathers along its outer edge.
    listOf(-1f, 1f).forEach { side ->
        val shoulder = c + Offset(side * b * 0.06f, -b * 0.08f)
        val wf = Frame(shoulder, 0f, b, mirror = false)
        fun q(x: Float, y: Float) = wf.p(side * x, y)
        val wing = smooth(listOf(q(0f, 0.05f), q(0.18f, -0.25f), q(0.30f, -0.62f), q(0.42f, -0.78f), q(0.70f, -0.55f), q(0.72f, -0.20f), q(0.40f, 0.02f)), closed = true)
        inked(wing, ink, key)
        // Feathers fanning from the wing's edge, longest at the top.
        for (k in 0 until 6) {
            val t = k / 5f
            val root = q(0.36f + 0.34f * t, -0.74f + 0.62f * t)
            val ang = -100f + side * (30f + k * 14f) + (if (side < 0) 0f else 0f)
            val len = b * (0.42f - k * 0.04f)
            val centre = wavyLine(root, if (side > 0) ang + 20f else ang - 20f, len, b * 0.04f * side, 1.0f, b * 0.10f * side, 16)
            flameTongue(centre, b * (0.15f - k * 0.008f), ink, 1f, key * 0.9f, inner = k < 3)
        }
        // Covert lines across the wing.
        for (k in 1..3) drawPath(smooth(listOf(q(0.12f + 0.06f * k, -0.10f * k), q(0.40f + 0.05f * k, -0.25f - 0.08f * k))), ink.copy(alpha = 0.6f), style = Stroke(1.2f * unit, cap = StrokeCap.Round))
    }

    // Body: a slim teardrop from chest to rump.
    val body = taper(listOf(c + Offset(b * 0.04f, -b * 0.30f), c + Offset(0f, 0f), c + Offset(0f, b * 0.32f))) { t -> b * (0.22f - 0.12f * t) }
    inked(body, ink, key)
    // Head, turned to the right, with a hooked beak and a crest of three plumes.
    val f = Frame(c + Offset(b * 0.06f, -b * 0.40f), 0f, b * 0.13f)
    listOf(-150f, -125f, -100f).forEachIndexed { i, a ->
        flameTongue(wavyLine(f.p(-0.3f, -0.4f), a, b * (0.30f - i * 0.04f), b * 0.03f, 1.4f, -b * 0.06f, 14), b * 0.07f, ink, 1f, key * 0.8f, inner = false)
    }
    val head = f.path(-0.9f to 0.3f, -0.8f to -0.6f, 0f to -0.95f, 0.8f to -0.6f, 1.6f to -0.2f, 1.9f to 0.35f, 1.3f to 0.15f, 0.7f to 0.55f, -0.2f to 0.8f)
    inked(head, ink, key)
    drawCircle(ink, 3.5f * unit, f.p(0.4f, -0.25f))

    // Embers rising.
    val rnd = Seeded(31)
    repeat(18) {
        val a = rnd.next() * 2 * PI
        val d = r * (1.05f + rnd.next() * 0.35f)
        drawCircle(ink.copy(alpha = 0.4f + rnd.next() * 0.6f), (1.5f + rnd.next() * 2.5f) * unit, c + Offset((cos(a) * d).toFloat(), (sin(a) * d - r * 0.2f).toFloat()))
    }
}

/**
 * Western dragon: an original winged dragon rearing on its hind legs, in fine line: a bat wing
 * raised behind it (finger bones, scalloped membrane), a long arched neck with spines, a horned
 * wedge head with jaws open and smoke curling out, a foreleg raised with claws out, and a spiked
 * tail ending in a spade.
 */
private fun DrawScope.drawWesternDragon(ink: Color) {
    val w = size.width * RowArt.CRESSIDA_SPLIT
    val h = size.height
    // Drawn in a box 0.55 wide by 1 tall, scaled to fit the marked side (tall phone or wide TV card).
    val s = minOf(h * 0.9f, w * 1.2f / 0.55f)
    val origin = Offset(w * 0.62f - 0.275f * s, h * 0.06f)
    fun d(u: Float, v: Float) = origin + Offset(u * s, v * s)
    fun shape(vararg pts: Pair<Float, Float>) = smooth(pts.map { d(it.first, it.second) }, closed = true)
    val line = 2.3f * unit
    val fine = 1.3f * unit

    // Wing, raised behind: a strong arm to the wrist, three long fingers, membrane scalloped between.
    val root = d(0.22f, 0.47f)
    val elbow = d(0.10f, 0.30f)
    val wrist = d(0.20f, 0.06f)
    val tips = listOf(d(0.03f, 0.08f), d(0.0f, 0.24f), d(0.04f, 0.40f))
    val trail = d(0.18f, 0.55f)
    val membrane = Path().apply {
        moveTo(root.x, root.y); lineTo(elbow.x, elbow.y); lineTo(wrist.x, wrist.y)
        var from = wrist
        (tips + trail).forEach { to ->
            val mid = Offset((from.x + to.x) / 2, (from.y + to.y) / 2)
            val pull = (wrist - mid) * 0.32f
            quadraticTo(mid.x + pull.x, mid.y + pull.y, to.x, to.y)
            from = to
        }
        close()
    }
    drawPath(membrane, Shadow.copy(alpha = 0.35f))
    drawPath(membrane, ink, style = Stroke(fine, join = StrokeJoin.Round))
    inked(taper(listOf(root, elbow, wrist)) { t -> s * (0.035f - 0.02f * t) }, ink, fine * 1.2f)
    tips.forEach { tip -> drawLine(ink, wrist, tip, line * 0.8f, StrokeCap.Round) }
    drawPath(smooth(listOf(wrist, wrist + Offset(5f * unit, -11f * unit), wrist + Offset(13f * unit, -9f * unit))), ink, style = Stroke(line, cap = StrokeCap.Round))

    // Tail: back and down from the hips, curling up to a spade, spikes along its top.
    val tailPts = smoothPoints(listOf(d(0.25f, 0.70f), d(0.15f, 0.76f), d(0.10f, 0.86f), d(0.14f, 0.94f), d(0.22f, 0.94f), d(0.25f, 0.89f)), 10)
    inked(taper(tailPts) { t -> s * 0.06f * (1f - t) + 1.5f * unit }, ink, line)
    val tn = normals(tailPts)
    for (i in 4 until tailPts.size - 6 step 6) {
        val base = tailPts[i] + tn[i] * (s * 0.06f * (1f - i / tailPts.size.toFloat()) * 0.5f)
        val tip = base + tn[i] * 9f * unit
        drawPath(Path().apply { moveTo(base.x - (tailPts[i + 1].x - tailPts[i].x) * 2, base.y - (tailPts[i + 1].y - tailPts[i].y) * 2); lineTo(tip.x, tip.y); lineTo(base.x + (tailPts[i + 1].x - tailPts[i].x) * 2, base.y + (tailPts[i + 1].y - tailPts[i].y) * 2) }, ink, style = Stroke(fine, join = StrokeJoin.Round))
    }
    inked(shape(0.25f to 0.89f, 0.22f to 0.84f, 0.27f to 0.78f, 0.31f to 0.85f), ink, line)

    // Hind leg: a heavy thigh, the shin angled back, a clawed foot planted.
    inked(shape(0.22f to 0.62f, 0.33f to 0.63f, 0.41f to 0.72f, 0.40f to 0.80f, 0.33f to 0.80f, 0.24f to 0.74f), ink, line)
    inked(taper(listOf(d(0.38f, 0.78f), d(0.33f, 0.88f), d(0.36f, 0.95f))) { t -> s * (0.05f - 0.025f * t) }, ink, line)
    val foot = d(0.36f, 0.95f)
    repeat(3) { k -> drawPath(smooth(wavyLine(foot, -10f + k * 25f, (16f - k * 2f) * unit, 0f, 0f, 5f * unit, 6)), ink, style = Stroke(line, cap = StrokeCap.Round)) }

    // Body: deep chest, belly drawing in to the hips, plates across the belly.
    inked(shape(0.23f to 0.43f, 0.32f to 0.44f, 0.37f to 0.52f, 0.36f to 0.62f, 0.32f to 0.69f, 0.25f to 0.69f, 0.21f to 0.57f), ink, line)
    for (k in 0 until 6) {
        val v = 0.49f + k * 0.032f
        drawPath(smooth(listOf(d(0.33f - k * 0.004f, v), d(0.355f - k * 0.006f, v + 0.004f), d(0.38f - k * 0.008f, v))), ink.copy(alpha = 0.55f), style = Stroke(fine, cap = StrokeCap.Round))
    }

    // Foreleg raised, claws out.
    inked(taper(listOf(d(0.34f, 0.50f), d(0.43f, 0.56f), d(0.47f, 0.48f))) { t -> s * (0.045f - 0.02f * t) }, ink, line)
    val paw = d(0.47f, 0.48f)
    listOf(-70f, -40f, -10f).forEach { a -> drawPath(smooth(wavyLine(paw, a, 13f * unit, 0f, 0f, 5f * unit, 6)), ink, style = Stroke(line, cap = StrokeCap.Round)) }

    // Neck: arching up from the shoulders, a few spines along its back.
    val neckPts = smoothPoints(listOf(d(0.27f, 0.46f), d(0.35f, 0.36f), d(0.32f, 0.27f), d(0.39f, 0.19f)), 12)
    inked(taper(neckPts) { t -> s * (0.10f - 0.045f * t) }, ink, line)
    val nn = normals(neckPts)
    for (i in 3 until neckPts.size - 4 step 7) {
        val half = s * (0.10f - 0.045f * i / neckPts.size) * 0.5f
        val base = neckPts[i] + nn[i] * half
        val along = neckPts[i + 2] - neckPts[i]
        val tip = base + nn[i] * 11f * unit - along * 0.6f
        drawPath(Path().apply { moveTo(base.x - along.x * 0.5f, base.y - along.y * 0.5f); lineTo(tip.x, tip.y); lineTo(base.x + along.x * 0.5f, base.y + along.y * 0.5f) }, ink, style = Stroke(fine, join = StrokeJoin.Round))
    }

    // Head: a wedge facing right, jaws open, horns swept back.
    val f = Frame(d(0.40f, 0.18f), 8f, s * 0.05f)
    listOf(-0.9f to 1f, -0.6f to 0.7f).forEach { (y, k) ->
        val horn = taper(listOf(f.p(-0.2f, y), f.p(-1.4f * k, y - 0.6f * k), f.p(-2.4f * k, y - 0.4f * k))) { t -> s * 0.05f * 0.5f * k * (1f - t) + 0.5f * unit }
        inked(horn, ink, fine * 1.2f)
    }
    val skull = f.path(-0.6f to -0.6f, 0.2f to -1.0f, 1.0f to -0.75f, 2.6f to -0.45f, 2.9f to -0.15f, 2.4f to 0.05f, 1.0f to 0.1f, -0.2f to 0.45f, -0.7f to 0.2f)
    val jaw = f.path(0.9f to 0.25f, 2.3f to 0.65f, 2.5f to 0.9f, 1.6f to 0.95f, 0.4f to 0.75f)
    inked(jaw, ink, line)
    inked(skull, ink, line)
    listOf(1.6f, 2.2f).forEach { x -> drawPath(f.path(x - 0.1f to 0.05f, x + 0.1f to 0.04f, x to 0.35f), ink) }
    drawPath(f.path(1.8f to 0.62f, 1.95f to 0.66f, 1.85f to 0.38f), ink)
    drawPath(smooth(listOf(f.p(0.4f, -0.85f), f.p(1.0f, -0.65f), f.p(1.4f, -0.45f))), ink, style = Stroke(line * 1.3f, cap = StrokeCap.Round))
    drawPath(f.path(0.75f to -0.5f, 1.05f to -0.55f, 1.25f to -0.38f, 1.0f to -0.38f), ink)
    drawCircle(ink, 1.6f * unit, f.p(2.55f, -0.3f))
    // Smoke curling from the jaws.
    listOf(0f, 1f).forEach { k ->
        val smoke = smooth(wavyLine(f.p(2.6f, 0.45f + k * 0.2f), -10f - k * 15f, s * (0.14f + 0.05f * k), 7f * unit, 1.6f, -10f * unit, 20))
        drawPath(smoke, ink.copy(alpha = 0.6f - k * 0.2f), style = Stroke(fine * 1.2f, cap = StrokeCap.Round))
    }
}

/** [points] smoothed into [perSpan] steps per span (Catmull-Rom), for tapering along. */
private fun smoothPoints(points: List<Offset>, perSpan: Int): List<Offset> {
    val out = mutableListOf<Offset>()
    for (i in 0 until points.lastIndex) {
        val p0 = points[(i - 1).coerceAtLeast(0)]; val p1 = points[i]; val p2 = points[i + 1]; val p3 = points[(i + 2).coerceAtMost(points.lastIndex)]
        for (k in 0 until perSpan) {
            val t = k / perSpan.toFloat(); val t2 = t * t; val t3 = t2 * t
            out += (p1 * 2f + (p2 - p0) * t + (p0 * 2f - p1 * 5f + p2 * 4f - p3) * t2 + (p1 * 3f - p0 - p2 * 3f + p3) * t3) * 0.5f
        }
    }
    out += points.last()
    return out
}
