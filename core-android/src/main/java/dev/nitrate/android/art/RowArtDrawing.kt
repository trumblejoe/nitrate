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
        CressidaInk.CONSTELLATION -> drawConstellation(ink)
        CressidaInk.WAVES -> drawWaves(ink)
        CressidaInk.DECO -> drawDeco(ink)
        CressidaInk.MAGIC -> drawMagic(ink)
        CressidaInk.SONGBIRD -> drawSongbird(ink)
        CressidaInk.FLAMES -> drawFlames(ink)
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

/** Dragon: a scaled serpent winding down the marked side, spined back, horned head, curled tail. */
private fun DrawScope.drawDragon(ink: Color) {
    val steps = 90
    // Centre line of the body, head at the top.
    val centre = List(steps + 1) { i ->
        val t = i / steps.toFloat()
        at(0.27f + 0.15f * sin(t * 2.7f * PI).toFloat(), 0.10f + 0.80f * t)
    }
    fun thickness(i: Int) = (58f - 46f * i / steps) * unit
    val normals = centre.indices.map { i ->
        val a = centre[(i - 1).coerceAtLeast(0)]
        val b = centre[(i + 1).coerceAtMost(steps)]
        val ang = atan2(b.y - a.y, b.x - a.x)
        Offset(-sin(ang), cos(ang))
    }
    val left = centre.indices.map { centre[it] + normals[it] * (thickness(it) / 2) }
    val right = centre.indices.map { centre[it] - normals[it] * (thickness(it) / 2) }
    val body = Path().apply {
        moveTo(left[0].x, left[0].y)
        left.drop(1).forEach { lineTo(it.x, it.y) }
        right.asReversed().forEach { lineTo(it.x, it.y) }
        close()
    }
    drawPath(body, Color.Black.copy(alpha = 0.35f))
    drawPath(body, ink, style = Stroke(3f * unit))
    // Scales: little arcs across the body.
    for (i in 4 until steps - 4 step 3) {
        val c = centre[i]
        val r = thickness(i) * 0.32f
        drawArc(ink.copy(alpha = 0.75f), (atan2(normals[i].y, normals[i].x) * 180 / PI).toFloat(), 180f, false, c - Offset(r, r), Size(r * 2, r * 2), style = Stroke(1.6f * unit))
    }
    // Spines along the back.
    for (i in 6 until steps - 10 step 5) {
        val base = left[i]
        val n = normals[i]
        val along = centre[i + 1] - centre[i]
        val spike = Path().apply {
            moveTo(base.x - along.x * 1.2f, base.y - along.y * 1.2f)
            lineTo(base.x + n.x * thickness(i) * 0.55f, base.y + n.y * thickness(i) * 0.55f)
            lineTo(base.x + along.x * 1.2f, base.y + along.y * 1.2f)
            close()
        }
        drawPath(spike, ink)
    }
    // Head: a snout pointing up and right, two swept horns and an eye.
    val head = centre[0]
    val snout = Path().apply {
        moveTo(head.x - 30f * unit, head.y + 10f * unit)
        lineTo(head.x + 70f * unit, head.y - 40f * unit)
        lineTo(head.x + 30f * unit, head.y + 22f * unit)
        close()
    }
    drawPath(snout, Color.Black.copy(alpha = 0.4f))
    drawPath(snout, ink, style = Stroke(3f * unit, join = StrokeJoin.Round))
    listOf(-1f, 1f).forEach { s ->
        val horn = Path().apply {
            moveTo(head.x - 10f * unit, head.y - 6f * unit)
            quadraticTo(head.x - 40f * unit, head.y - (60f + 15f * s) * unit, head.x - (20f - 25f * s) * unit, head.y - 95f * unit)
        }
        drawPath(horn, ink, style = Stroke(4f * unit, cap = StrokeCap.Round))
    }
    drawCircle(ink, 6f * unit, head + Offset(18f * unit, -8f * unit))
    // Tail: a tight curl.
    val tail = centre.last()
    val curl = Path()
    for (k in 0..80) {
        val ang = k / 80f * 3 * PI
        val r = 40f * unit * (1 - k / 80f)
        val p = tail + Offset((cos(ang) * r).toFloat(), (sin(ang) * r).toFloat())
        if (k == 0) curl.moveTo(p.x, p.y) else curl.lineTo(p.x, p.y)
    }
    drawPath(curl, ink, style = Stroke(3f * unit, cap = StrokeCap.Round))
}

/** Constellation: stars joined by fine lines, sparkles, and a crescent moon. */
private fun DrawScope.drawConstellation(ink: Color) {
    // Fixed "random" stars, so a card always looks the same.
    var seed = 7L
    fun rnd(): Float { seed = (seed * 1103515245 + 12345) and 0x7fffffff; return (seed % 10000) / 10000f }
    val stars = List(26) { at(0.03f + rnd() * 0.52f, 0.04f + rnd() * 0.92f) }
    // Chains: each star joins its nearest unvisited neighbour, a few chains in all.
    listOf(0..7, 8..15, 16..25).forEach { range ->
        val chain = range.map { stars[it] }.sortedBy { it.y }
        for (i in 0 until chain.lastIndex) drawLine(ink.copy(alpha = 0.55f), chain[i], chain[i + 1], 1.4f * unit)
    }
    stars.forEachIndexed { i, p ->
        if (i % 4 == 0) {
            val r = (16f + rnd() * 10f) * unit
            val spark = Path().apply {
                moveTo(p.x, p.y - r); lineTo(p.x + r * 0.18f, p.y - r * 0.18f); lineTo(p.x + r, p.y); lineTo(p.x + r * 0.18f, p.y + r * 0.18f)
                lineTo(p.x, p.y + r); lineTo(p.x - r * 0.18f, p.y + r * 0.18f); lineTo(p.x - r, p.y); lineTo(p.x - r * 0.18f, p.y - r * 0.18f); close()
            }
            drawPath(spark, ink)
        } else {
            drawCircle(ink, (3f + rnd() * 3f) * unit, p)
        }
    }
    // Crescent moon: a disc with a bite taken out.
    val moon = at(0.16f, 0.14f)
    val r = 44f * unit
    val crescent = Path().apply {
        addOval(androidx.compose.ui.geometry.Rect(moon - Offset(r, r), Size(r * 2, r * 2)))
    }
    val bite = Path().apply { addOval(androidx.compose.ui.geometry.Rect(moon - Offset(r * 0.55f, r * 1.1f), Size(r * 2, r * 2))) }
    drawPath(Path.combine(androidx.compose.ui.graphics.PathOperation.Difference, crescent, bite), ink)
    // A scatter of dust.
    repeat(60) { drawCircle(ink.copy(alpha = 0.35f), 1.4f * unit, at(rnd() * 0.5f, rnd())) }
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

/** Songbird: an original bird in flight, wings fanned in feathers, with a few drifting feathers. */
private fun DrawScope.drawSongbird(ink: Color) {
    val c = motifCentre
    val r = motifRadius
    val line = Stroke(2.4f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)
    // Body: a teardrop leaning up and to the right, head at the top.
    val body = Path().apply {
        moveTo(c.x - r * 0.35f, c.y + r * 0.35f)
        cubicTo(c.x - r * 0.1f, c.y - r * 0.05f, c.x + r * 0.15f, c.y - r * 0.35f, c.x + r * 0.32f, c.y - r * 0.38f)
        cubicTo(c.x + r * 0.45f, c.y - r * 0.40f, c.x + r * 0.42f, c.y - r * 0.22f, c.x + r * 0.30f, c.y - r * 0.16f)
        cubicTo(c.x + r * 0.10f, c.y + r * 0.05f, c.x - r * 0.10f, c.y + r * 0.30f, c.x - r * 0.35f, c.y + r * 0.35f)
        close()
    }
    drawPath(body, Color.Black.copy(alpha = 0.35f))
    drawPath(body, ink, style = line)
    // Beak and eye.
    val beak = Path().apply { moveTo(c.x + r * 0.42f, c.y - r * 0.36f); lineTo(c.x + r * 0.58f, c.y - r * 0.40f); lineTo(c.x + r * 0.42f, c.y - r * 0.28f); close() }
    drawPath(beak, ink)
    drawCircle(ink, 4f * unit, c + Offset(r * 0.33f, -r * 0.31f))
    // Wings: fans of feathers sweeping up and back from the shoulder.
    val shoulder = c + Offset(r * 0.02f, -r * 0.05f)
    listOf(-1f, 1f).forEach { side ->
        for (k in 0 until 7) {
            val ang = (if (side < 0) -150.0 else -70.0) + side * k * 9.0
            val len = r * (1.05f - k * 0.08f)
            val tip = shoulder + Offset((cos(ang * PI / 180) * len).toFloat(), (sin(ang * PI / 180) * len).toFloat())
            val mid = Offset((shoulder.x + tip.x) / 2, (shoulder.y + tip.y) / 2)
            val feather = Path().apply {
                moveTo(shoulder.x, shoulder.y)
                quadraticTo(mid.x + side * 10f * unit, mid.y - 18f * unit, tip.x, tip.y)
            }
            drawPath(feather, ink.copy(alpha = if (side < 0) 1f else 0.6f), style = line)
        }
    }
    // Tail: three long feathers fanning down and back.
    val rump = c + Offset(-r * 0.32f, r * 0.32f)
    listOf(110.0, 125.0, 140.0).forEach { deg ->
        val tip = rump + Offset((cos(deg * PI / 180) * r * 0.7f).toFloat(), (sin(deg * PI / 180) * r * 0.7f).toFloat())
        drawLine(ink, rump, tip, 2.4f * unit, StrokeCap.Round)
    }
    // Drifting feathers.
    val rnd = Seeded(23)
    repeat(4) {
        val p = at(0.05f + rnd.next() * 0.45f, 0.70f + rnd.next() * 0.25f)
        val s = (24f + rnd.next() * 18f) * unit
        rotate(rnd.next() * 120f - 60f, p) {
            drawOval(ink, p - Offset(s, s * 0.22f), Size(s * 2, s * 0.44f), style = Stroke(1.6f * unit))
            drawLine(ink, p - Offset(s * 1.2f, 0f), p + Offset(s, 0f), 1.2f * unit)
        }
    }
}

/** Flames: tongues of fire rising up the marked side, with embers above. */
private fun DrawScope.drawFlames(ink: Color) {
    val rnd = Seeded(5)
    for (k in 0 until 9) {
        val x = size.width * (0.02f + k * 0.055f)
        val height = size.height * (0.35f + rnd.next() * 0.35f)
        val w = size.width * (0.05f + rnd.next() * 0.04f)
        val sway = (rnd.next() - 0.5f) * w * 2
        val flame = Path().apply {
            moveTo(x - w, size.height)
            cubicTo(x - w, size.height - height * 0.45f, x + sway - w * 0.4f, size.height - height * 0.7f, x + sway, size.height - height)
            cubicTo(x + sway + w * 0.3f, size.height - height * 0.65f, x + w, size.height - height * 0.4f, x + w, size.height)
            close()
        }
        drawPath(flame, Brush.verticalGradient(listOf(Color.Transparent, ink.copy(alpha = 0.55f), ink.copy(alpha = 0.9f)), startY = size.height - height, endY = size.height))
        drawPath(flame, ink, style = Stroke(1.6f * unit))
    }
    repeat(30) { drawCircle(ink.copy(alpha = 0.5f + rnd.next() * 0.5f), (1.5f + rnd.next() * 3f) * unit, at(rnd.next() * 0.55f, 0.15f + rnd.next() * 0.5f)) }
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
