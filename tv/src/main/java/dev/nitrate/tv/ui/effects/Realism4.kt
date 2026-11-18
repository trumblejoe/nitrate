package dev.nitrate.tv.ui.effects

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Roses and thorns, the flower meadow and the ocean floor, drawn for realism: one light source per
 * scene that everything is shaded by, depth of field (what's near the lens is out of focus, drawn
 * as soft glows), haze that thins colour with distance, and form built from many shaded parts
 * rather than flat shapes.
 */

private fun frac(x: Float) = x - floor(x)
private const val TAU = (2 * PI).toFloat()

// =====================================================================================
// Roses and thorns
// =====================================================================================

/**
 * One rose petal pointing up from the origin: a broad cup with a rolled, slightly notched lip.
 * [len] from the heart to the lip, [wid] half its width.
 */
private fun petalPath(len: Float, wid: Float, notch: Float): Path = Path().apply {
    moveTo(0f, 0f)
    cubicTo(-wid * 0.55f, -len * 0.15f, -wid * 1.05f, -len * 0.55f, -wid * 0.8f, -len * 0.88f)
    quadraticBezierTo(-wid * 0.45f, -len * 1.04f, -wid * 0.05f, -len * (0.98f - notch))
    quadraticBezierTo(wid * 0.45f, -len * 1.06f, wid * 0.82f, -len * 0.86f)
    cubicTo(wid * 1.05f, -len * 0.55f, wid * 0.55f, -len * 0.15f, 0f, 0f)
    close()
}

/**
 * A full rose seen from the front and a little above, lit from [light] (a direction): outer petals
 * open and broad, inner ones tighter and cupped, each with a shadow on the petal beneath, a dark
 * base, a lit lip; a tight spiral at the heart; dew on a few petals.
 */
private fun DrawScope.realRose(c: Offset, r: Float, hue: Color, turn: Float, light: Offset, seed: Int, dew: Boolean = true) {
    val deep = lerp(hue, Color.Black, 0.72f)
    val lit = lerp(hue, Color(0xFFFFD7DC), 0.32f)
    // The bloom's own shadow and a faint glow of light through the petals.
    glow(c + Offset(r * 0.12f, r * 0.18f), r * 1.5f, Color.Black, 0.55f, additive = false)
    glow(c, r * 1.9f, hue, 0.10f)
    val rings = listOf(
        // petals, length, width, cup (how much the petal's outer half darkens), offset from the heart
        floatArrayOf(5f, 1.00f, 0.78f, 0.15f, 0.10f),
        floatArrayOf(5f, 0.86f, 0.70f, 0.25f, 0.08f),
        floatArrayOf(6f, 0.70f, 0.58f, 0.35f, 0.06f),
        floatArrayOf(6f, 0.54f, 0.48f, 0.45f, 0.04f),
        floatArrayOf(5f, 0.38f, 0.42f, 0.55f, 0.02f),
    )
    rings.forEachIndexed { ri, ring ->
        val n = ring[0].toInt()
        for (p in 0 until n) {
            val a = turn + ri * 0.62f + p * TAU / n + (rnd(p + ri * 11, seed) - 0.5f) * 0.25f
            val len = r * ring[1] * (0.92f + rnd(p, seed + ri) * 0.16f)
            val wid = r * ring[2] * (0.9f + rnd(p, seed + 7 + ri) * 0.2f)
            val base = c + Offset(cos(a - PI.toFloat() / 2), sin(a - PI.toFloat() / 2)) * (r * ring[4])
            val deg = a * 180f / PI.toFloat()
            // How much this petal faces the light: lit petals brighter, the far side in shade.
            val facing = (cos(a - PI.toFloat() / 2) * light.x + sin(a - PI.toFloat() / 2) * light.y).coerceIn(-1f, 1f)
            val shade = 0.55f + 0.45f * facing
            val path = petalPath(len, wid, 0.04f + rnd(p, seed + 3) * 0.05f)
            withTransform({ translate(base.x, base.y); rotate(deg, Offset.Zero) }) {
                // Shadow cast on what lies beneath.
                withTransform({ translate(len * 0.03f, len * 0.06f) }) { drawPath(path, Color.Black.copy(alpha = 0.45f)) }
                drawPath(path, Brush.verticalGradient(
                    0f to lerp(deep, lerp(hue, lit, 0.4f), shade * (1 - ring[3])),
                    0.55f to lerp(deep, hue, 0.55f + 0.45f * shade),
                    1f to deep,
                    startY = -len, endY = 0f,
                ))
                // The rolled lip catching the light, and the veins' faint lines.
                drawPath(Path().apply {
                    moveTo(-wid * 0.78f, -len * 0.86f)
                    quadraticBezierTo(-wid * 0.42f, -len * 1.03f, -wid * 0.05f, -len * 0.95f)
                    quadraticBezierTo(wid * 0.45f, -len * 1.05f, wid * 0.8f, -len * 0.85f)
                }, lit.copy(alpha = 0.25f + 0.45f * shade), style = Stroke(r * 0.03f, cap = StrokeCap.Round))
                for (v in -2..2) drawLine(deep.copy(alpha = 0.18f), Offset(0f, -len * 0.08f), Offset(v * wid * 0.25f, -len * 0.75f), 0.8f)
            }
        }
    }
    // The heart: tight crescents curling in.
    for (i in 0 until 7) {
        val f = i / 7f
        val rr = r * (0.3f - f * 0.28f)
        val a0 = turn * 3 + i * 1.9f
        drawArc(lerp(deep, hue, 0.4f + f * 0.4f), a0 * 180f / PI.toFloat(), 220f, true, topLeft = c - Offset(rr, rr), size = Size(rr * 2, rr * 2))
        drawArc(lit.copy(alpha = 0.35f), a0 * 180f / PI.toFloat(), 200f, false, topLeft = c - Offset(rr, rr), size = Size(rr * 2, rr * 2), style = Stroke(1.2f))
    }
    glow(c, r * 0.18f, Color.Black, 0.6f, additive = false)
    if (dew) for (d in 0 until 4) {
        val a = rnd(d, seed + 40) * TAU
        val dist = r * (0.45f + rnd(d, seed + 41) * 0.45f)
        val dc = c + Offset(cos(a), sin(a)) * dist
        val dr = r * (0.025f + rnd(d, seed + 42) * 0.03f)
        drawCircle(Color.Black.copy(alpha = 0.3f), dr, dc + Offset(dr * 0.4f, dr * 0.5f))
        drawCircle(Brush.radialGradient(listOf(Color(0x66FFE6EA), Color(0x22FFFFFF), Color(0x88FFFFFF)), center = dc, radius = dr), dr, dc)
        drawCircle(Color.White.copy(alpha = 0.9f), dr * 0.3f, dc - Offset(dr * 0.35f, dr * 0.35f))
    }
}

/** A glossy rose leaf: serrated, a midrib and veins, a sheen where the light falls. */
private fun DrawScope.roseLeaf(base: Offset, len: Float, angleDeg: Float, seed: Int) {
    withTransform({ translate(base.x, base.y); rotate(angleDeg, Offset.Zero) }) {
        val w = len * 0.36f
        val p = Path().apply {
            moveTo(0f, 0f)
            for (side in listOf(-1f, 1f)) {
                moveTo(0f, 0f)
                for (i in 1..14) {
                    val f = i / 14f
                    val half = sin(f * PI.toFloat()).let { sqrt(it) } * w * side
                    val tooth = if (i % 2 == 0) 1f else 0.88f
                    lineTo(f * len, half * tooth)
                }
                lineTo(len, 0f)
            }
        }
        drawPath(p, Brush.linearGradient(listOf(Color(0xFF0B1F0C), Color(0xFF26502A), Color(0xFF0F2A12)), start = Offset(0f, -w), end = Offset(0f, w)))
        drawPath(p, Brush.radialGradient(listOf(Color(0x55CFE8C0), Color.Transparent), center = Offset(len * 0.45f, -w * 0.3f), radius = len * 0.45f))
        drawLine(Color(0xFF4F7A3E).copy(alpha = 0.7f), Offset(0f, 0f), Offset(len * 0.95f, 0f), 1.4f)
        for (v in 1..5) {
            val f = v / 6f
            drawLine(Color(0xFF3E6532).copy(alpha = 0.5f), Offset(len * f, 0f), Offset(len * (f + 0.12f), -w * 0.6f * sin(f * PI.toFloat())), 0.8f)
            drawLine(Color(0xFF3E6532).copy(alpha = 0.5f), Offset(len * f, 0f), Offset(len * (f + 0.12f), w * 0.6f * sin(f * PI.toFloat())), 0.8f)
        }
    }
}

/** A thorny cane as a thick stroke lit on one side, with big hooked thorns. */
private fun DrawScope.thornCane(points: List<Offset>, width: Float, t: Float, seed: Int, leaves: Boolean) {
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size - 1) {
            val mid = (points[i] + points[i + 1]) / 2f
            quadraticBezierTo(points[i].x, points[i].y, mid.x, mid.y)
        }
        lineTo(points.last().x, points.last().y)
    }
    drawPath(path, Color(0xFF0C140A), style = Stroke(width * 1.15f, cap = StrokeCap.Round))
    drawPath(path, Color(0xFF2A3A1E), style = Stroke(width, cap = StrokeCap.Round))
    drawPath(path, Color(0xFF6E7A44).copy(alpha = 0.45f), style = Stroke(width * 0.25f, cap = StrokeCap.Round))
    // Thorns and leaves along it.
    var acc = 0f
    for (i in 0 until points.size - 1) {
        val a = points[i]
        val b = points[i + 1]
        val seg = (b - a).getDistance()
        val dir = (b - a) / seg.coerceAtLeast(0.01f)
        val n = Offset(-dir.y, dir.x)
        var s = 0f
        while (s < seg) {
            val p = a + dir * s
            val side = if (((acc + s) / 70f).toInt() % 2 == 0) 1f else -1f
            val tl = width * (1.5f + rnd(((acc + s) / 10).toInt(), seed) * 1.1f)
            val tip = p + n * side * tl + dir * tl * 0.55f
            val thorn = Path().apply {
                moveTo(p.x - dir.x * width * 0.7f, p.y - dir.y * width * 0.7f)
                quadraticBezierTo(p.x + n.x * side * tl * 0.5f, p.y + n.y * side * tl * 0.5f, tip.x, tip.y)
                lineTo(p.x + dir.x * width * 0.5f, p.y + dir.y * width * 0.5f)
                close()
            }
            drawPath(thorn, Brush.linearGradient(listOf(Color(0xFF243318), Color(0xFF5A2A1A), Color(0xFFB0705A)), start = p, end = tip))
            if (leaves && rnd(((acc + s) / 10).toInt(), seed + 5) > 0.72f) {
                val ang = atan2(n.y * side, n.x * side) * 180f / PI.toFloat() + 25f * side + sin(t * 0.7f + s) * 4f
                roseLeaf(p, width * 9f, ang, seed + i)
            }
            s += 34f + rnd(((acc + s) / 7).toInt(), seed + 2) * 30f
        }
        acc += seg
    }
}

internal fun DrawScope.rosesAndThorns(t: Float, k: Float) {
    val w = size.width
    val h = size.height
    val light = Offset(-0.5f, -0.85f) // from the upper left
    // A dark, warm frame and a pool of red light behind the page.
    drawRect(Brush.radialGradient(listOf(Color.Transparent, Color(0x99140006), Color(0xEE080002)), center = Offset(w * 0.5f, h * 0.45f), radius = w * 0.72f))
    glow(Offset(w * 0.5f, h * 0.35f), w * 0.55f, Color(0xFF7A0A1E), 0.18f + 0.04f * sin(t * 0.5f))
    // Thorny canes arching across the corners, behind the blooms.
    thornCane(listOf(Offset(-60f, h * 0.25f), Offset(w * 0.08f, h * 0.06f), Offset(w * 0.24f, -20f), Offset(w * 0.32f, -80f)), 9f, t, 1, true)
    thornCane(listOf(Offset(w + 60f, h * 0.3f), Offset(w * 0.9f, h * 0.08f), Offset(w * 0.74f, -10f), Offset(w * 0.66f, -80f)), 9f, t, 2, true)
    thornCane(listOf(Offset(-40f, h * 1.05f), Offset(w * 0.06f, h * 0.7f), Offset(w * 0.02f, h * 0.45f), Offset(-30f, h * 0.3f)), 11f, t, 3, true)
    thornCane(listOf(Offset(w + 40f, h * 1.05f), Offset(w * 0.95f, h * 0.72f), Offset(w * 0.99f, h * 0.5f), Offset(w + 40f, h * 0.35f)), 11f, t, 4, true)
    thornCane(listOf(Offset(w * 0.18f, h + 30f), Offset(w * 0.35f, h * 0.93f), Offset(w * 0.55f, h * 0.97f), Offset(w * 0.8f, h + 30f)), 8f, t, 5, false)
    // The blooms: big ones in the lower corners, smaller ones in the upper, all turning very slowly.
    val sway = sin(t * 0.4f)
    realRose(Offset(w * 0.07f, h * 0.86f) + Offset(sway * 3f, 0f), h * 0.17f, Color(0xFFB0102C), t * 0.01f, light, 11)
    realRose(Offset(w * 0.17f, h * 0.99f), h * 0.11f, Color(0xFF8C0A22), 1.3f + t * 0.012f, light, 12)
    realRose(Offset(w * 0.93f, h * 0.84f) - Offset(sway * 3f, 0f), h * 0.15f, Color(0xFFC21632), 2.2f - t * 0.01f, light, 13)
    realRose(Offset(w * 0.83f, h * 1.0f), h * 0.1f, Color(0xFF9E0D27), 0.6f, light, 14)
    realRose(Offset(w * 0.06f, h * 0.12f), h * 0.08f, Color(0xFFA30E28), 0.9f + t * 0.015f, light, 15, dew = false)
    realRose(Offset(w * 0.94f, h * 0.1f), h * 0.075f, Color(0xFFB8122F), 2.8f, light, 16, dew = false)
    // Out of focus close to the lens: soft dark-red shapes in the corners.
    glow(Offset(-w * 0.02f, h * 0.55f), h * 0.32f, Color(0xFF3A0410), 0.75f, additive = false)
    glow(Offset(w * 1.03f, h * 0.45f), h * 0.3f, Color(0xFF3A0410), 0.7f, additive = false)
    glow(Offset(w * 0.02f, h * 0.55f), h * 0.18f, Color(0xFFB0102C), 0.12f)
    // Petals falling, large and curled, catching the light as they turn.
    fallingPetals(t, k, 18, listOf(Color(0xFFB0102C), Color(0xFF8C0A22), Color(0xFFD0263F)), 1700, 1.8f)
}

// =====================================================================================
// Flower meadow at golden hour
// =====================================================================================

/** A cosmos-like flower facing [tilt] (0 = straight at us, 1 = side on), backlit by the low sun. */
private fun DrawScope.meadowBloom(c: Offset, r: Float, hue: Color, turn: Float, tilt: Float, haze: Float) {
    val squash = 1f - tilt * 0.7f
    val far = lerp(hue, Color(0xFFE8D2B0), haze)
    withTransform({ scale(1f, squash, c) }) {
        val n = 8
        for (p in 0 until n) {
            val a = turn + p * TAU / n
            val deg = a * 180f / PI.toFloat()
            withTransform({ translate(c.x, c.y); rotate(deg, Offset.Zero) }) {
                val petal = Path().apply {
                    moveTo(0f, 0f)
                    cubicTo(r * 0.25f, -r * 0.3f, r * 0.42f, -r * 0.8f, r * 0.16f, -r)
                    lineTo(0f, -r * 0.94f)
                    lineTo(-r * 0.16f, -r)
                    cubicTo(-r * 0.42f, -r * 0.8f, -r * 0.25f, -r * 0.3f, 0f, 0f)
                    close()
                }
                drawPath(petal, Brush.verticalGradient(listOf(lerp(far, Color.White, 0.35f), far, lerp(far, Color(0xFF3A1020), 0.35f)), startY = -r, endY = 0f))
                // Light through the thin petal: a warm glow along its edge.
                drawPath(petal, Color(0xFFFFE2A8).copy(alpha = 0.18f * (1 - haze)), style = Stroke(1.2f))
            }
        }
        drawCircle(Brush.radialGradient(listOf(Color(0xFFFFD45A), Color(0xFFB07A10), Color(0xFF4A2A06)), center = c - Offset(r * 0.05f, r * 0.06f), radius = r * 0.24f), r * 0.2f, c)
    }
}

/** A poppy seen from the side: a cup of crinkled, translucent petals lit through by the sun. */
private fun DrawScope.poppyCup(c: Offset, r: Float, haze: Float, lean: Float) {
    val red = lerp(Color(0xFFE0301A), Color(0xFFE8C7A6), haze)
    withTransform({ rotate(lean, c) }) {
        for (p in listOf(-1f, 1f, 0f)) {
            val petal = Path().apply {
                moveTo(c.x, c.y + r * 0.5f)
                cubicTo(c.x + p * r * 1.2f - r * 0.3f, c.y + r * 0.2f, c.x + p * r * 1.0f - r * 0.6f, c.y - r * 0.9f, c.x + p * r * 0.4f, c.y - r * 0.95f)
                cubicTo(c.x + p * r * 0.6f + r * 0.4f, c.y - r * 0.8f, c.x + p * r * 0.7f + r * 0.2f, c.y + r * 0.1f, c.x, c.y + r * 0.5f)
                close()
            }
            drawPath(petal, Brush.verticalGradient(listOf(lerp(red, Color(0xFFFFB070), 0.45f), red, lerp(red, Color(0xFF2A0500), 0.6f)), startY = c.y - r, endY = c.y + r * 0.5f), alpha = if (p == 0f) 0.85f else 1f)
        }
        glow(c - Offset(0f, r * 0.2f), r * 1.2f, Color(0xFFFF7A3A), 0.22f * (1 - haze))
        drawCircle(Color(0xFF2A1A10), r * 0.12f, c + Offset(0f, r * 0.4f))
    }
}

/** A grass blade: a tapering curve, dark at the root, its tip lit gold by the low sun. */
private fun DrawScope.blade(root: Offset, hgt: Float, bend: Float, width: Float, haze: Float) {
    val tip = root + Offset(bend, -hgt)
    val ctrl = root + Offset(bend * 0.15f, -hgt * 0.6f)
    val path = Path().apply {
        moveTo(root.x - width, root.y)
        quadraticBezierTo(ctrl.x - width * 0.5f, ctrl.y, tip.x, tip.y)
        quadraticBezierTo(ctrl.x + width * 0.5f, ctrl.y, root.x + width, root.y)
        close()
    }
    val dark = lerp(Color(0xFF1C3212), Color(0xFF8A9474), haze)
    val gold = lerp(Color(0xFFD8C060), Color(0xFFEADBB8), haze)
    drawPath(path, Brush.verticalGradient(listOf(gold, lerp(dark, gold, 0.4f), dark), startY = tip.y, endY = root.y))
}

internal fun DrawScope.flowerGarden(t: Float, k: Float) {
    val w = size.width
    val h = size.height
    val sun = Offset(w * 0.18f, h * 0.2f)
    // Golden-hour sky glow and haze over the lower page; the sun low on the left, a veil of rays.
    glow(sun, h * 1.2f, Color(0xFFFFB45A), 0.28f)
    glow(sun, h * 0.22f, Color(0xFFFFF2D0), 0.75f)
    glow(sun, h * 0.05f, Color.White, 0.9f)
    // Light through the haze: a few broad, soft-edged beams, each built of widening faint layers.
    for (r in 0 until 3) {
        val a = 0.25f + r * 0.22f + sin(t * 0.05f + r) * 0.02f
        val end = sun + Offset(cos(a), sin(a)) * w * 1.3f
        for (layer in 1..8) {
            val side = Offset(-sin(a), cos(a)) * (24f + r * 8f) * layer.toFloat()
            drawPath(Path().apply { moveTo(sun.x, sun.y); lineTo(end.x + side.x, end.y + side.y); lineTo(end.x - side.x, end.y - side.y); close() },
                Brush.linearGradient(listOf(Color(0x09FFE0A0), Color.Transparent), start = sun, end = end), blendMode = BlendMode.Plus)
        }
    }
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0x55E8B070), Color(0x88302010)), startY = h * 0.55f))
    // Far: the meadow dissolving into haze, specks of colour.
    for (i in 0 until 160) {
        val x = rnd(i, 1800) * w
        val y = h * (0.7f + rnd(i, 1801) * 0.08f)
        val col = listOf(Color(0xFFF4E2C8), Color(0xFFF0A890), Color(0xFFE8D0F0), Color(0xFFFFF0B0))[i % 4]
        drawCircle(col.copy(alpha = 0.45f), 1.6f + rnd(i, 1802) * 2f, Offset(x, y))
    }
    // Three ranks, nearer = larger, sharper and stronger in colour.
    for (rank in 0 until 3) {
        val haze = 0.6f - rank * 0.28f
        val scale = 0.6f + rank * 0.55f
        val baseY = h * (0.8f + rank * 0.08f)
        // Grass first, then flowers standing in it.
        for (g in 0 until 140 - rank * 30) {
            val id = g + rank * 1000
            val x = rnd(id, 1810) * (w + 40f) - 20f
            val sway = sin(t * 1.1f + x * 0.01f + rank) * 6f * scale + (fbm(t * 0.25f + x * 0.003f, 1811) - 0.5f) * 12f * scale
            blade(Offset(x, baseY + rnd(id, 1812) * 20f), (40f + rnd(id, 1813) * 70f) * scale, sway + (rnd(id, 1814) - 0.5f) * 30f * scale, 2.2f * scale, haze.coerceAtLeast(0f))
        }
        for (i in 0 until 24 - rank * 7) {
            val id = i + rank * 500
            val x = w * (i + rnd(id, 1820) * 0.9f) / (24 - rank * 7)
            val hgt = h * (0.1f + rnd(id, 1821) * 0.12f) * scale
            val sway = sin(t * 0.8f + x * 0.008f + rank) * 9f * scale + (fbm(t * 0.2f + x * 0.002f, 1822) - 0.5f) * 14f * scale
            val root = Offset(x, baseY + 20f)
            val head = root + Offset(sway, -hgt)
            val stem = Path().apply { moveTo(root.x, root.y); quadraticBezierTo(root.x + sway * 0.1f, root.y - hgt * 0.5f, head.x, head.y) }
            drawPath(stem, lerp(Color(0xFF2E4A1E), Color(0xFF9AA082), haze.coerceAtLeast(0f)), style = Stroke(2.4f * scale))
            if (rnd(id, 1823) < 0.55f) {
                meadowBloom(head, 26f * scale, listOf(Color(0xFFF2F0F4), Color(0xFFF07AA8), Color(0xFFD94A7A))[i % 3], rnd(id, 1824) * TAU + t * 0.02f, rnd(id, 1825) * 0.6f, haze.coerceAtLeast(0f))
            } else {
                poppyCup(head, 24f * scale, haze.coerceAtLeast(0f), sway * 0.6f)
            }
        }
        // Haze settling between the ranks.
        if (rank < 2) drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0x33F0C890), Color.Transparent), startY = baseY - h * 0.08f, endY = baseY + h * 0.06f))
    }
    // Out of focus right in front of the lens: big soft blooms and blades in the corners.
    glow(Offset(w * 0.03f, h * 0.98f), h * 0.2f, Color(0xFFE0301A), 0.45f, additive = false)
    glow(Offset(w * 0.12f, h * 1.02f), h * 0.16f, Color(0xFFF07AA8), 0.35f, additive = false)
    glow(Offset(w * 0.96f, h * 0.97f), h * 0.22f, Color(0xFF2E4A1E), 0.6f, additive = false)
    glow(Offset(w * 0.9f, h * 1.02f), h * 0.14f, Color(0xFFF2F0F4), 0.3f, additive = false)
    // Pollen and seed fluff drifting through the light.
    for (i in 0 until (70 * k).toInt()) {
        val x = frac(rnd(i, 1830) + t * 0.008f * (0.5f + rnd(i, 1831))) * w
        val y = h * (0.25f + rnd(i, 1832) * 0.6f) + sin(t * 0.7f + i) * 20f
        val near = rnd(i, 1833)
        glow(Offset(x, y), 3f + near * 9f, Color(0xFFFFE7B0), 0.35f + 0.4f * near)
    }
}

// =====================================================================================
// Ocean floor
// =====================================================================================

internal fun DrawScope.oceanFloor(t: Float, k: Float) {
    val w = size.width
    val h = size.height
    val horizon = h * 0.5f
    // Water: bright where the surface is, darkening with depth and towards the edges.
    drawRect(Brush.verticalGradient(listOf(Color(0xFF1C7A92), Color(0xFF0E4D66), Color(0xFF07304A), Color(0xFF041B2C)), startY = 0f, endY = h))
    glow(Offset(w * 0.45f, -h * 0.1f), h * 0.9f, Color(0xFF7FE0F0), 0.25f)
    // The surface, seen from below: a bright rippling sheet at the very top.
    for (i in 0 until 50) {
        val x = rnd(i, 1900) * w + sin(t * 0.6f + i) * 30f
        glow(Offset(x, h * 0.02f + rnd(i, 1901) * h * 0.06f), 40f + rnd(i, 1902) * 70f, Color(0xFFCFF8FF), 0.10f + 0.06f * sin(t * 1.4f + i * 2f))
    }
    // God rays: soft-edged shafts swaying with the surface.
    for (i in 0 until 9) {
        val x = w * (0.02f + i * 0.12f) + (fbm(t * 0.07f + i * 3f, 1903) - 0.5f) * 180f
        val wid = 30f + rnd(i, 1904) * 70f
        val lean = 120f + rnd(i, 1905) * 100f
        val bright = 0.10f + 0.10f * fbm(t * 0.2f + i, 1906)
        for (layer in 0 until 3) {
            val ww = wid * (1f + layer * 0.9f)
            val ray = Path().apply { moveTo(x - ww * 0.3f, 0f); lineTo(x + ww * 0.3f, 0f); lineTo(x + lean + ww, h * 0.9f); lineTo(x + lean - ww, h * 0.9f); close() }
            drawPath(ray, Brush.verticalGradient(listOf(Color(0xFFDFFBFF).copy(alpha = bright / (layer + 1)), Color.Transparent), startY = 0f, endY = h * 0.9f), blendMode = BlendMode.Plus)
        }
    }
    // The sea bed in perspective: far and blue at the horizon, near and sandy at the bottom.
    drawRect(Brush.verticalGradient(listOf(Color(0xFF0E4D66), Color(0xFF3F7A7C), Color(0xFF9DA88C), Color(0xFFB9AE8A)), startY = horizon, endY = h), topLeft = Offset(0f, horizon), size = Size(w, h - horizon))
    // Sand ripples: bands crowding together towards the horizon.
    val drift = t * 6f
    for (j in 1 until 40) {
        val z = (j / 40f).let { it * it }
        val y = horizon + (h - horizon) * z
        val amp = 2f + 6f * z
        val ripple = Path().apply {
            moveTo(0f, y)
            var x = 0f
            while (x <= w) { lineTo(x, y + sin((x + drift * z * 3) / (60f + 120f * z) + j) * amp); x += 12f }
        }
        drawPath(ripple, Color(0xFF2E3A36).copy(alpha = 0.05f + 0.18f * z), style = Stroke(1f + 2.5f * z))
        drawPath(ripple, Color(0xFFF2EAD0).copy(alpha = 0.04f + 0.12f * z), style = Stroke(0.8f + z), blendMode = BlendMode.Plus)
    }
    // Caustics: a net of bright lines rippling over the sand, two families crossing.
    for (fam in 0 until 2) for (i in 0 until 26) {
        val z0 = (i / 26f)
        val y0 = horizon + (h - horizon) * z0 * z0
        var x = 0f
        var prev: Offset? = null
        while (x <= w) {
            val y = y0 + sin(x / (70f + 50f * z0) * (if (fam == 0) 1f else -1.4f) + t * (0.9f + fam * 0.4f) + i * 1.7f) * (6f + 22f * z0) * (0.4f + z0)
            val p = Offset(x, y)
            // Bright only in patches that wander, as the surface focuses the light.
            val patch = fbm(x / 180f + i * 0.9f + t * 0.15f * (if (fam == 0) 1 else -1), 1907 + fam)
            val a = ((patch - 0.45f) * 2.4f).coerceIn(0f, 1f) * (0.05f + 0.16f * z0)
            if (prev != null && a > 0.005f) drawLine(Color(0xFFFFFBE0).copy(alpha = a), prev, p, 1f + 3.5f * z0, StrokeCap.Round, blendMode = BlendMode.Plus)
            prev = p
            x += 14f
        }
    }
    // Far hills of the sea bed, then the fog that hides where it meets the water: no hard horizon.
    for (hill in 0 until 3) {
        val p = Path().apply {
            moveTo(0f, horizon + 20f)
            var x = 0f
            while (x <= w) { lineTo(x, horizon + 10f - fbm(x / (520f - hill * 120f) + hill * 4f, 1950 + hill) * h * (0.07f + hill * 0.03f)); x += 16f }
            lineTo(w, horizon + 20f); close()
        }
        drawPath(p, lerp(Color(0xFF0E4D66), Color(0xFF2A6470), hill / 3f).copy(alpha = 0.7f))
    }
    drawRect(Brush.verticalGradient(0f to Color(0x000E4D66), 0.45f to Color(0xF00E4D66), 1f to Color(0x000E4D66), startY = horizon - h * 0.1f, endY = horizon + h * 0.2f),
        topLeft = Offset(0f, horizon - h * 0.1f), size = Size(w, h * 0.3f))
    // Rocks and coral, far ones lost in the blue, near ones lit from above.
    for (r in 0 until 12) {
        val z = 0.15f + rnd(r, 1910) * 0.85f
        val y = horizon + (h - horizon) * z * z + 10f
        val x = ((rnd(r, 1911) * (w + 600f) - drift * (0.5f + z * 2f)) % (w + 600f) + w + 600f) % (w + 600f) - 300f
        val size = (30f + rnd(r, 1912) * 70f) * (0.3f + z * 1.5f)
        val haze = (1 - z).coerceIn(0f, 1f)
        val rock = lerp(Color(0xFF3A4A44), Color(0xFF155A70), haze * 0.9f)
        drawOval(Color.Black.copy(alpha = 0.35f * z), topLeft = Offset(x - size * 1.2f, y - size * 0.18f), size = Size(size * 2.6f, size * 0.45f))
        // An irregular boulder: an outline wandering on noise, lit from above, pitted.
        val outline = Path()
        for (a in 0..36) {
            val ang = PI.toFloat() + a / 36f * PI.toFloat()
            val rr = size * (0.75f + 0.35f * fbm(a * 0.35f + r * 7f, 1915))
            val px = x + cos(ang) * rr * 1.2f
            val py = y + sin(ang) * rr * 0.95f
            if (a == 0) outline.moveTo(px, py) else outline.lineTo(px, py)
        }
        outline.close()
        drawPath(outline, Brush.verticalGradient(listOf(lerp(rock, Color(0xFFE6F0DC), 0.45f * (1 - haze)), rock, lerp(rock, Color.Black, 0.6f)), startY = y - size, endY = y))
        if (z > 0.3f) for (pit in 0 until 10) {
            val px = x + (rnd(pit + r * 13, 1916) - 0.5f) * size * 1.8f
            val py = y - rnd(pit + r * 13, 1917) * size * 0.75f
            glow(Offset(px, py), size * (0.06f + rnd(pit, 1918) * 0.1f), Color.Black, 0.25f * z, additive = false)
        }
        drawPath(outline, Color(0xFFDFF4F0).copy(alpha = 0.12f * (1 - haze)), style = Stroke(1.5f))
        // Soft coral and anemones on the near rocks.
        if (z > 0.45f) for (c in 0 until 6) {
            val cx = x + (rnd(c + r * 9, 1913) - 0.5f) * size * 1.4f
            val cy = y - size * (0.5f + rnd(c + r * 9, 1914) * 0.4f)
            val col = listOf(Color(0xFFFF7A5A), Color(0xFFFFB0C8), Color(0xFFB07AFF), Color(0xFFFFD27A))[(c + r) % 4]
            for (f in 0 until 9) {
                val a = -PI.toFloat() / 2 + (f - 4) * 0.22f + sin(t * 1.2f + f + c) * 0.08f
                val len = size * (0.18f + rnd(f, c + r) * 0.12f)
                drawLine(lerp(col, Color(0xFF0E4D66), haze * 0.6f).copy(alpha = 0.85f), Offset(cx, cy), Offset(cx + cos(a) * len, cy + sin(a) * len), 2.2f * z, StrokeCap.Round)
                drawCircle(lerp(col, Color.White, 0.4f).copy(alpha = 0.8f), 1.6f * z, Offset(cx + cos(a) * len, cy + sin(a) * len))
            }
        }
    }
    // Sea grass in front: translucent ribbons swaying with the swell.
    for (g in 0 until 26) {
        val x = ((rnd(g, 1920) * (w + 400f) - drift * 3f) % (w + 400f) + w + 400f) % (w + 400f) - 200f
        val hgt = h * (0.18f + rnd(g, 1921) * 0.3f)
        val swell = sin(t * 0.7f + g * 0.4f)
        val ribbon = Path().apply {
            moveTo(x - 9f, h)
            for (s in 1..16) { val f = s / 16f; lineTo(x - 9f + swell * 50f * f * f + sin(t * 1.3f + f * 5 + g) * 8f * f, h - hgt * f) }
            for (s in 16 downTo 0) { val f = s / 16f; lineTo(x + 9f * (1 - f) + swell * 50f * f * f + sin(t * 1.3f + f * 5 + g) * 8f * f, h - hgt * f) }
            close()
        }
        drawPath(ribbon, Brush.verticalGradient(listOf(Color(0xCC8FB85A), Color(0xEE2E5A26), Color(0xFF13280F)), startY = h - hgt, endY = h))
    }
    // A school of fish far off, turning together, blue with distance.
    val school = Offset(w * (0.25f + fbm(t * 0.03f, 1930) * 0.5f), h * (0.32f + fbm(t * 0.04f, 1931) * 0.12f))
    val heading = if (fbm(t * 0.03f + 0.05f, 1930) >= fbm(t * 0.03f, 1930)) 1f else -1f
    for (i in 0 until 70) {
        val off = Offset((rnd(i, 1932) - 0.5f) * 340f + sin(t * 1.4f + i) * 10f, (rnd(i, 1933) - 0.5f) * 120f + cos(t * 1.1f + i) * 8f)
        val c = school + off
        val s = 5f + rnd(i, 1934) * 4f
        withTransform({ scale(heading, 1f, c) }) {
            drawOval(Color(0xAA0B3A50), topLeft = c - Offset(s, s * 0.35f), size = Size(s * 2f, s * 0.7f))
            drawPath(Path().apply { moveTo(c.x - s, c.y); lineTo(c.x - s * 1.7f, c.y - s * 0.45f); lineTo(c.x - s * 1.7f, c.y + s * 0.45f); close() }, Color(0xAA0B3A50))
            drawLine(Color(0x55BFF0FF), c - Offset(s * 0.6f, s * 0.2f), c + Offset(s * 0.6f, -s * 0.2f), 1f, blendMode = BlendMode.Plus)
        }
    }
    // Particles in the water: small and sharp far off, big and soft close to the lens.
    for (i in 0 until (140 * k).toInt()) {
        val near = rnd(i, 1940).let { it * it }
        val y = frac(rnd(i, 1941) + t * 0.006f * (0.5f + near)) * h
        val x = frac(rnd(i, 1942) - t * 0.004f * (0.5f + near * 3f)) * w + sin(t * 0.5f + i) * 6f
        if (near > 0.6f) glow(Offset(x, y), 6f + near * 18f, Color(0xFFCFEFF4), 0.18f) else drawCircle(Color(0xFFD8F0F4).copy(alpha = 0.35f), 0.8f + near * 1.5f, Offset(x, y))
    }
    // Depth: the edges sink into darkness.
    drawRect(Brush.radialGradient(listOf(Color.Transparent, Color(0x99021220)), center = Offset(w / 2, h * 0.45f), radius = w * 0.75f))
}
