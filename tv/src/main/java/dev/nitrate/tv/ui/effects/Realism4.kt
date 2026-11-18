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
import androidx.compose.ui.graphics.drawscope.clipPath
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
    // Far reef ridges across the whole width, lost in the blue.
    for (r in 0 until 2) {
        val top = horizon - h * (0.07f - r * 0.03f)
        val pts = buildList {
            add(Offset(-20f, horizon + h * 0.08f))
            var x = -20f
            while (x <= w + 20f) { add(Offset(x, top - fbm(x / (260f - r * 60f) + r * 5f, 2300 + r) * h * (0.1f - r * 0.03f) + rnd((x / 20).toInt(), 2302 + r) * 6f)); x += 18f }
            add(Offset(w + 20f, horizon + h * 0.08f))
        }
        rockMass(pts, 0.82f - r * 0.18f, 2310 + r, t, life = false)
    }
    // The ridges' feet sink into the haze over the bed.
    drawRect(Brush.verticalGradient(listOf(Color(0x000E4D66), Color(0xEE1C5A6C), Color(0x00315F66)), startY = horizon - h * 0.02f, endY = horizon + h * 0.12f), topLeft = Offset(0f, horizon - h * 0.02f), size = Size(w, h * 0.14f))
    // Rubble on the open bed between the reefs.
    for (i in 0 until 120) {
        val z = rnd(i, 2000).let { sqrt(it) }
        val y = horizon + (h - horizon) * z * z + 6f
        val x = ((rnd(i, 2001) * (w + 200f) - drift * (0.5f + z * 2f)) % (w + 200f) + w + 200f) % (w + 200f) - 100f
        val r = (3f + rnd(i, 2002) * 10f) * (0.3f + z * 2f)
        val haze = (1 - z * 1.15f).coerceIn(0f, 1f)
        val tone = lerp(listOf(Color(0xFF8A8478), Color(0xFF6E7068), Color(0xFF8E7E66), Color(0xFF5E5A54))[i % 4], Color(0xFF2A6A7A), haze * 0.9f)
        drawOval(Color.Black.copy(alpha = 0.28f * z), topLeft = Offset(x - r * 1.1f, y - r * 0.12f), size = Size(r * 2.4f, r * 0.45f))
        drawOval(Brush.verticalGradient(listOf(lerp(tone, Color(0xFFF0F4E8), 0.4f * (1 - haze)), tone, lerp(tone, Color.Black, 0.5f)), startY = y - r * 0.9f, endY = y), topLeft = Offset(x - r, y - r * 0.8f), size = Size(r * 2f, r * 0.85f))
    }
    // A rocky reef across the middle, with an arch through it.
    val reefTop = h * 0.56f
    val reef = buildList {
        add(Offset(w * 0.3f, h * 0.74f))
        var x = w * 0.3f
        while (x <= w * 0.82f) {
            val f = (x - w * 0.3f) / (w * 0.52f)
            val hump = sin(f * PI.toFloat()).let { sqrt(it) }
            add(Offset(x, h * 0.74f - (h * 0.74f - reefTop) * hump - fbm(x / 90f, 2320) * h * 0.06f))
            x += 14f
        }
        // A ragged foot half buried in sand.
        var xb = w * 0.82f
        while (xb >= w * 0.3f) { add(Offset(xb, h * 0.74f + (fbm(xb / 60f, 2324) - 0.3f) * h * 0.03f)); xb -= 20f }
    }
    rockMass(reef, 0.35f, 2321, t, life = true)
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0x669DA88C), Color.Transparent), startY = h * 0.715f, endY = h * 0.77f), topLeft = Offset(w * 0.28f, h * 0.715f), size = Size(w * 0.56f, h * 0.055f))
    // The arch: a dark mouth through the reef with the blue showing through it.
    val arch = Offset(w * 0.6f, h * 0.705f)
    val mouth = Path().apply {
        for (i in 0..24) {
            val a = PI.toFloat() + i / 24f * PI.toFloat()
            val rr = 1f + (fbm(i * 0.6f, 2325) - 0.5f) * 0.35f
            val p = arch + Offset(cos(a) * h * 0.075f * rr, sin(a) * h * 0.1f * rr)
            if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
        }
        lineTo(arch.x + h * 0.08f, arch.y + h * 0.04f); lineTo(arch.x - h * 0.08f, arch.y + h * 0.04f); close()
    }
    // The blue beyond, seen through it, darkening into the rock's inner walls.
    drawPath(mouth, Brush.radialGradient(listOf(Color(0xFF2A7A90), Color(0xFF0B3A4C), Color(0xFF020A0E)), center = arch, radius = h * 0.1f))
    drawPath(mouth, Color(0xFFDDEDE8).copy(alpha = 0.15f), style = Stroke(2f))
    // The left wall: a cliff rising out of frame, ledges and an overhang.
    val left = buildList {
        add(Offset(-30f, h + 30f))
        var y = h + 30f
        while (y >= -30f) {
            val f = y / h
            val overhang = if (f in 0.18f..0.36f) sin((f - 0.18f) / 0.18f * PI.toFloat()) * w * 0.08f else 0f
            val ledge = if (((y / 70f).toInt()) % 3 == 0) w * 0.02f else 0f
            add(Offset(w * (0.17f + 0.05f * fbm(y / 160f, 2330)) + overhang + ledge + (rnd((y / 10).toInt(), 2331) - 0.5f) * 10f, y))
            y -= 16f
        }
        add(Offset(-30f, -30f))
    }
    rockMass(left, 0.05f, 2332, t, life = true)
    // The right wall, lower, its top broken into pinnacles.
    val right = buildList {
        add(Offset(w + 30f, h + 30f))
        var y = h + 30f
        while (y >= h * 0.34f) {
            add(Offset(w * (0.86f - 0.04f * fbm(y / 140f, 2340)) - (if (((y / 60f).toInt()) % 3 == 1) w * 0.02f else 0f) + (rnd((y / 10).toInt(), 2341) - 0.5f) * 10f, y))
            y -= 16f
        }
        var x = w * 0.84f
        while (x <= w + 30f) { add(Offset(x, h * 0.34f - fbm(x / 50f, 2342) * h * 0.12f)); x += 14f }
    }
    rockMass(right, 0.1f, 2343, t, life = true)
    // A ledge right in front, which we look over.
    val front = buildList {
        add(Offset(-30f, h + 30f))
        var x = -30f
        while (x <= w + 30f) { add(Offset(x, h * 0.93f - fbm(x / 160f, 2350) * h * 0.06f - rnd((x / 14).toInt(), 2351) * 5f)); x += 14f }
        add(Offset(w + 30f, h + 30f))
    }
    rockMass(front, 0f, 2352, t, life = true)
    // Sea fans and kelp growing from the walls, swaying with the swell.
    seaFan(Offset(w * 0.2f, h * 0.62f), h * 0.16f, Color(0xFFB04A8A), t, 1)
    seaFan(Offset(w * 0.55f, h * 0.6f), h * 0.1f, Color(0xFFE07A3A), t, 2)
    seaFan(Offset(w * 0.86f, h * 0.5f), h * 0.13f, Color(0xFF8A5AD0), t, 3)
    for (g in 0 until 8) {
        val base = listOf(Offset(w * 0.19f, h * 0.45f), Offset(w * 0.21f, h * 0.8f), Offset(w * 0.36f, h * 0.68f), Offset(w * 0.86f, h * 0.7f), Offset(w * 0.9f, h * 0.38f), Offset(w * 0.5f, h * 0.6f), Offset(w * 0.7f, h * 0.92f), Offset(w * 0.12f, h * 0.92f))[g]
        kelp(base, h * (0.2f + rnd(g, 2020) * 0.25f), t, g)
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


/**
 * A boulder of real rock: a ragged outline cut into facets around an inner ridge, each facet shaded
 * by how it faces the light from above (tops pale, flanks dark), then pitted, cracked, crusted with
 * algae on top and dappled by the caustics, all inside its own outline. [haze] blues it with distance.
 */
private fun DrawScope.boulder(base: Offset, wdt: Float, hgt: Float, seed: Int, haze: Float, t: Float) {
    val n = 34
    // A full outline: the domed top, and a shallow underside so stacked stones sit into each other.
    val outer = ArrayList<Offset>(n + 1)
    for (i in 0..n) {
        val a = PI.toFloat() + i / n.toFloat() * 2 * PI.toFloat()
        val top = sin(a) < 0
        val rough = 0.8f + 0.28f * fbm(i * 0.4f + seed, seed) + (rnd(i, seed + 1) - 0.5f) * 0.06f
        val ry = if (top) hgt * rough.coerceAtMost(1.05f) else hgt * 0.16f * rough
        outer += Offset(base.x + cos(a) * wdt * 0.5f * rough, base.y - hgt * 0.06f + sin(a) * ry)
    }
    val ctr = Offset(base.x + (rnd(seed, 3) - 0.5f) * wdt * 0.2f, base.y - hgt * (0.55f + rnd(seed, 4) * 0.2f))
    val mid = outer.mapIndexed { i, p -> ctr + (p - ctr) * (0.62f + 0.12f * rnd(i, seed + 2)) + Offset((rnd(i, seed + 5) - 0.5f) * wdt * 0.04f, 0f) }
    val inner = outer.mapIndexed { i, p -> ctr + (p - ctr) * (0.3f + 0.1f * rnd(i, seed + 6)) }
    val outline = Path().apply {
        moveTo(outer[0].x, outer[0].y)
        outer.drop(1).forEach { lineTo(it.x, it.y) }
        close()
    }
    val stone = lerp(listOf(Color(0xFF6A665C), Color(0xFF5A5E56), Color(0xFF726656))[(seed % 3 + 3) % 3], Color(0xFF155A70), haze * 0.85f)
    val lightDir = Offset(-0.35f, -0.94f)
    // Contact shadow on the bed.
    drawOval(Color.Black.copy(alpha = 0.45f * (1 - haze)), topLeft = Offset(base.x - wdt * 0.6f, base.y - hgt * 0.08f), size = Size(wdt * 1.2f, hgt * 0.22f))
    drawPath(outline, lerp(stone, Color.Black, 0.45f))
    fun facet(points: List<Offset>, ring: Float) {
        val c = points.reduce { a, b -> a + b } / points.size.toFloat()
        val d = c - ctr
        val len = d.getDistance().coerceAtLeast(1f)
        val facing = ((d.x / len) * lightDir.x + (d.y / len) * lightDir.y) * ring + (1 - ring) * 0.5f
        // Gentle steps between facets: rock planes, not crystal.
        val v = (0.42f + 0.32f * facing + (rnd((c.x * 0.7f + c.y).toInt(), seed + 9) - 0.5f) * 0.12f).coerceIn(0f, 1f)
        val col = lerp(lerp(stone, Color.Black, 0.55f), lerp(stone, Color(0xFFE6EEE0), 0.42f * (1 - haze)), v)
        drawPath(Path().apply { moveTo(points[0].x, points[0].y); points.drop(1).forEach { lineTo(it.x, it.y) }; close() }, col)
    }
    for (i in 0 until n) facet(listOf(mid[i], outer[i], outer[i + 1], mid[i + 1]), 1f)
    for (i in 0 until n) facet(listOf(inner[i], mid[i], mid[i + 1], inner[i + 1]), 0.75f)
    for (i in 0 until n) facet(listOf(ctr, inner[i], inner[i + 1]), 0.45f)
    // Soften it into one mass: light from above, mottled tones that cross the facet edges.
    clipPath(outline) {
        drawRect(Brush.verticalGradient(listOf(Color(0x33FFFFFF), Color.Transparent, Color(0x55000000)), startY = base.y - hgt, endY = base.y))
        for (i in 0 until 18) {
            val p = Offset(base.x + (rnd(i, seed + 60) - 0.5f) * wdt, base.y - rnd(i, seed + 61) * hgt)
            glow(p, wdt * (0.08f + rnd(i, seed + 62) * 0.16f), if (i % 2 == 0) Color.Black else lerp(stone, Color.White, 0.3f), 0.18f, additive = false)
        }
    }
    if (haze > 0.85f) return
    clipPath(outline) {
        // Pits and grain.
        for (i in 0 until 60) {
            val p = Offset(base.x + (rnd(i, seed + 20) - 0.5f) * wdt, base.y - rnd(i, seed + 21) * hgt)
            val r = wdt * (0.002f + rnd(i, seed + 22) * 0.005f)
            drawCircle(if (i % 3 == 0) Color(0x22FFFFFF) else Color(0x40000000), r, p)
        }
        // Cracks: jagged dark lines running down the flanks.
        for (c in 0 until 4) {
            var p = Offset(base.x + (rnd(c, seed + 30) - 0.5f) * wdt * 0.7f, base.y - hgt * (0.4f + rnd(c, seed + 31) * 0.5f))
            for (s in 0 until 7) {
                val q = p + Offset((rnd(c * 9 + s, seed + 32) - 0.5f) * wdt * 0.08f, hgt * (0.05f + rnd(c * 9 + s, seed + 33) * 0.07f))
                drawLine(Color(0xAA0A0C0C), p, q, 1.6f, StrokeCap.Round)
                drawLine(Color(0x22FFFFFF), p + Offset(1.5f, 0f), q + Offset(1.5f, 0f), 0.8f)
                p = q
            }
        }
        // Algae crusting the lit top, and the caustics playing over it.
        for (i in 0 until 14) {
            val p = Offset(base.x + (rnd(i, seed + 40) - 0.5f) * wdt * 0.8f, base.y - hgt * (0.7f + rnd(i, seed + 41) * 0.4f))
            glow(p, wdt * (0.05f + rnd(i, seed + 42) * 0.08f), if (i % 2 == 0) Color(0xFF5E7A3A) else Color(0xFF8A6A3E), 0.5f * (1 - haze), additive = false)
        }
        for (i in 0 until 10) {
            val p = Offset(base.x + (fbm(t * 0.2f + i * 1.7f, seed + 50) - 0.5f) * wdt, base.y - hgt * (0.55f + fbm(t * 0.17f + i, seed + 51) * 0.5f))
            glow(p, wdt * 0.09f, Color(0xFFFFF6D8), 0.16f * (1 - haze))
        }
        // The flank in shadow towards the bed.
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000)), startY = base.y - hgt * 0.45f, endY = base.y))
    }
}

/** A gorgonian sea fan: a flat lattice of branches fanning up from a holdfast, swaying. */
private fun DrawScope.seaFan(base: Offset, hgt: Float, color: Color, t: Float, seed: Int) {
    val sway = sin(t * 0.6f + seed) * 0.08f
    fun branch(p: Offset, angle: Float, len: Float, depth: Int, id: Int) {
        if (depth == 0 || len < 3f) return
        val end = p + Offset(cos(angle) * len, sin(angle) * len)
        drawLine(lerp(color, Color.Black, 0.35f + depth * 0.05f).copy(alpha = 0.9f), p, end, (depth * 0.7f).coerceAtLeast(0.6f), StrokeCap.Round)
        branch(end, angle - 0.32f + (rnd(id, seed) - 0.5f) * 0.2f + sway, len * 0.78f, depth - 1, id * 2 + 1)
        branch(end, angle + 0.32f + (rnd(id, seed + 1) - 0.5f) * 0.2f + sway, len * 0.78f, depth - 1, id * 2 + 2)
    }
    glow(base - Offset(0f, hgt * 0.5f), hgt * 0.6f, color, 0.08f)
    branch(base, -PI.toFloat() / 2 + sway, hgt * 0.28f, 7, 1)
}

/** A kelp frond: a stipe rising with the swell, blades along it lit through. */
private fun DrawScope.kelp(base: Offset, hgt: Float, t: Float, seed: Int) {
    val pts = (0..18).map { s ->
        val f = s / 18f
        base + Offset(sin(t * 0.55f + f * 3f + seed) * 40f * f + sin(t * 1.3f + f * 7f) * 6f * f, -hgt * f)
    }
    for (i in 0 until pts.size - 1) drawLine(Color(0xFF4A5A22), pts[i], pts[i + 1], 3.5f, StrokeCap.Round)
    for (i in 2 until pts.size step 2) {
        val p = pts[i]
        val side = if (i % 4 == 0) 1f else -1f
        val blade = Path().apply {
            moveTo(p.x, p.y)
            quadraticBezierTo(p.x + side * 26f, p.y - 10f, p.x + side * 46f + sin(t + i) * 6f, p.y - 4f)
            quadraticBezierTo(p.x + side * 24f, p.y + 6f, p.x, p.y)
            close()
        }
        drawPath(blade, Brush.linearGradient(listOf(Color(0xEE5A6A26), Color(0xAA9AA84A)), start = p, end = p + Offset(side * 46f, 0f)))
    }
}


/**
 * A mass of rock (a cliff, a reef, a ledge) inside [outline]: lit from above and darkening down,
 * layered in strata whose ledges catch the light and throw a shadow, split by cracks, pocked with
 * dark crevices, mottled, dappled by caustics, and crusted with sponges and anemones on the ledges
 * ([life]). [haze] blues it with distance.
 */
private fun DrawScope.rockMass(outline: List<Offset>, haze: Float, seed: Int, t: Float, life: Boolean) {
    val path = Path().apply { moveTo(outline[0].x, outline[0].y); outline.drop(1).forEach { lineTo(it.x, it.y) }; close() }
    val minX = outline.minOf { it.x }; val maxX = outline.maxOf { it.x }
    val minY = outline.minOf { it.y }; val maxY = outline.maxOf { it.y }
    val bw = maxX - minX
    val bh = maxY - minY
    val stone = lerp(Color(0xFF5E5C54), Color(0xFF155A70), haze * 0.9f)
    val water = Color(0xFF0E4D66)
    drawPath(path, Brush.verticalGradient(listOf(lerp(stone, Color(0xFFDCE6DA), 0.35f * (1 - haze)), stone, lerp(stone, Color.Black, 0.55f)), startY = minY, endY = maxY))
    clipPath(path) {
        // Strata: ragged ledges across the rock, each lit on top with a shadow below.
        val step = (26f + bh * 0.04f).coerceAtMost(70f)
        var y = minY + step * rnd(seed, 1)
        var j = 0
        while (y < maxY) {
            val ledge = Path().apply {
                moveTo(minX, y)
                var x = minX
                while (x <= maxX) { lineTo(x, y + (fbm(x / 70f + j * 3.1f, seed + 2) - 0.5f) * step * 0.9f + (x - minX) * (rnd(j, seed + 22) - 0.5f) * 0.12f); x += 12f }
            }
            // Each band a slightly different stone: some warmer, some greener with growth.
            val tint = listOf(Color(0xFF8A7A62), Color(0xFF5E6A52), Color(0xFF6A6A66), Color(0xFF4A4C48))[(j + seed).mod(4)]
            drawRect(tint.copy(alpha = 0.16f * (1 - haze)), topLeft = Offset(minX, y), size = Size(bw, step))
            withTransform({ translate(0f, step * 0.12f) }) { drawPath(ledge, Color.Black.copy(alpha = 0.3f * (1 - haze * 0.6f)), style = Stroke(step * (0.15f + 0.2f * rnd(j, seed + 20)))) }
            drawPath(ledge, Color(0xFFE6EEE2).copy(alpha = (0.08f + 0.12f * rnd(j, seed + 21)) * (1 - haze)), style = Stroke(1.5f))
            // Cracks splitting the band into blocks.
            for (c in 0 until (bw / 90f).toInt()) {
                val cx = minX + rnd(c + j * 31, seed + 3) * bw
                drawLine(Color.Black.copy(alpha = 0.4f * (1 - haze * 0.6f)), Offset(cx, y + 2f), Offset(cx + (rnd(c, seed + 4) - 0.5f) * 14f, y + step * (0.6f + rnd(c, seed + 5) * 0.4f)), 1.6f)
            }
            y += step * (0.5f + rnd(j, seed + 6) * 1.1f)
            j++
        }
        // Mottling across the blocks, and crevices going dark.
        for (i in 0 until (bw * bh / 9000f).toInt().coerceIn(12, 120)) {
            val p = Offset(minX + rnd(i, seed + 7) * bw, minY + rnd(i, seed + 8) * bh)
            glow(p, 20f + rnd(i, seed + 9) * 60f, if (i % 2 == 0) Color.Black else Color(0xFF9A9888), 0.16f, additive = false)
        }
        for (i in 0 until (bw * bh / 60000f).toInt().coerceIn(2, 14)) {
            val p = Offset(minX + rnd(i, seed + 10) * bw, minY + rnd(i, seed + 11) * bh)
            glow(p, 18f + rnd(i, seed + 12) * 40f, Color.Black, 0.7f * (1 - haze * 0.5f), additive = false)
        }
        // Light dappling the rock as the surface moves.
        for (i in 0 until 14) {
            val p = Offset(minX + fbm(t * 0.15f + i * 1.3f, seed + 13) * bw, minY + fbm(t * 0.12f + i * 2.1f, seed + 14) * bh * 0.7f)
            glow(p, 40f + bw * 0.03f, Color(0xFFFFF6D8), 0.10f * (1 - haze))
        }
        if (life) {
            // Sponges, encrusting coral and anemones along the ledges.
            val colours = listOf(Color(0xFFE8743A), Color(0xFFB04A8A), Color(0xFFF2C14E), Color(0xFF7A5AD0), Color(0xFFE04A4A))
            for (i in 0 until (bw * bh / 9000f).toInt().coerceIn(6, 50)) {
                val p = Offset(minX + rnd(i, seed + 15) * bw, minY + rnd(i, seed + 16) * bh)
                val col = lerp(colours[i % colours.size], water, haze * 0.7f)
                val r = 4f + rnd(i, seed + 17) * 10f
                when (i % 3) {
                    // An encrusting sponge: a flat irregular patch, soft-edged.
                    0 -> for (b in 0 until 5) glow(p + Offset((rnd(b + i * 7, seed + 18) - 0.5f) * r * 2f, (rnd(b + i * 7, seed + 19) - 0.5f) * r * 0.8f), r * 0.9f, lerp(col, Color(0xFF6A5A4A), 0.35f), 0.55f, additive = false)
                    // An anemone: fronds waving from a small foot.
                    1 -> for (f in 0 until 9) {
                        val a = -PI.toFloat() / 2 + (f - 4) * 0.24f + sin(t * 1.5f + f + i) * 0.12f
                        drawLine(lerp(col, Color.White, 0.15f).copy(alpha = 0.8f), p, p + Offset(cos(a), sin(a)) * (r * 1.5f), 1.4f, StrokeCap.Round)
                    }
                    // Tube sponges: a few upright, shaded cylinders.
                    else -> for (u in 0 until 3) {
                        val q = p + Offset((u - 1) * r * 0.7f, 0f)
                        val th = r * (1.2f + rnd(u + i, seed + 23) * 0.9f)
                        drawRect(Brush.horizontalGradient(listOf(lerp(col, Color.Black, 0.45f), lerp(col, Color.White, 0.1f), lerp(col, Color.Black, 0.3f)), startX = q.x - r * 0.25f, endX = q.x + r * 0.25f), topLeft = Offset(q.x - r * 0.25f, q.y - th), size = Size(r * 0.5f, th))
                        drawOval(lerp(col, Color.Black, 0.6f), topLeft = Offset(q.x - r * 0.25f, q.y - th - r * 0.1f), size = Size(r * 0.5f, r * 0.22f))
                    }
                }
            }
        }
        // Where the rock meets the bed: darker and softer, not a hard line.
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF061A22).copy(alpha = 0.6f * (1 - haze * 0.5f))), startY = maxY - bh * 0.18f, endY = maxY), topLeft = Offset(minX, maxY - bh * 0.18f), size = Size(bw, bh * 0.18f))
        // Distance: the water between us and the rock.
        if (haze > 0f) drawRect(water.copy(alpha = haze * 0.55f))
    }
    // The rock's edge against the water, catching a little light.
    drawPath(path, Color(0xFFCFE6E0).copy(alpha = 0.12f * (1 - haze)), style = Stroke(2f))
}
