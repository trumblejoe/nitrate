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
import androidx.compose.ui.graphics.nativeCanvas
import dev.nitrate.core.poetry.Poem
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/* Roses, flowers, poetry, and the drawn pre-roll scenes: same light-first style as Realism.kt. */

private fun frac(x: Float) = x - floor(x)

// ---- Roses and thorns ----

/** A rose seen from a little above: rings of cupped petals, each shaded darker at its base, a tight curl at the heart. */
private fun DrawScope.rose(c: Offset, r: Float, hue: Color, turn: Float) {
    glow(c, r * 2.2f, hue, 0.12f)
    val rings = 4
    for (ring in 0 until rings) {
        val rr = r * (1f - ring * 0.2f)
        val petals = 5 + ring
        for (p in 0 until petals) {
            val a = turn + ring * 0.6f + p * 2 * PI.toFloat() / petals
            val pc = c + Offset(cos(a) * rr * 0.45f, sin(a) * rr * 0.45f)
            withTransform({ rotate(a * 180f / PI.toFloat() + 90f, pc) }) {
                drawOval(
                    Brush.radialGradient(
                        listOf(lerp(hue, Color.White, 0.18f - ring * 0.04f), hue, lerp(hue, Color.Black, 0.55f)),
                        center = pc - Offset(0f, rr * 0.25f), radius = rr * 0.75f,
                    ),
                    topLeft = pc - Offset(rr * 0.42f, rr * 0.5f), size = Size(rr * 0.84f, rr * 0.95f),
                )
                // The petal's rolled lip catches the light.
                drawArc(lerp(hue, Color.White, 0.35f).copy(alpha = 0.5f), 200f, 140f, false, topLeft = pc - Offset(rr * 0.4f, rr * 0.5f), size = Size(rr * 0.8f, rr * 0.5f), style = Stroke(1.2f))
            }
        }
    }
    // The bud at the centre: a tight spiral.
    val spiral = Path()
    for (i in 0..40) {
        val f = i / 40f
        val a = turn * 2 + f * 5 * PI.toFloat()
        val p = c + Offset(cos(a) * r * 0.22f * f, sin(a) * r * 0.22f * f)
        if (i == 0) spiral.moveTo(p.x, p.y) else spiral.lineTo(p.x, p.y)
    }
    drawCircle(lerp(hue, Color.Black, 0.45f), r * 0.24f, c)
    drawPath(spiral, lerp(hue, Color.White, 0.2f).copy(alpha = 0.7f), style = Stroke(1.6f))
}

private fun DrawScope.leafAt(c: Offset, len: Float, angle: Float, green: Color) {
    withTransform({ rotate(angle, c) }) {
        val p = Path().apply {
            moveTo(c.x, c.y)
            // A serrated edge: small teeth along both sides.
            for (side in listOf(-1f, 1f)) {
                moveTo(c.x, c.y)
                for (i in 1..8) {
                    val f = i / 8f
                    val wdt = sin(f * PI.toFloat()) * len * 0.32f * side
                    lineTo(c.x + f * len, c.y + wdt * (if (i % 2 == 0) 1f else 0.85f))
                }
                lineTo(c.x + len, c.y)
            }
        }
        drawPath(p, Brush.linearGradient(listOf(lerp(green, Color.Black, 0.3f), green), start = c, end = c + Offset(len, 0f)))
        drawLine(lerp(green, Color.White, 0.25f).copy(alpha = 0.6f), c, c + Offset(len, 0f), 1f)
    }
}

/** A briar: a curving cane with thorns, leaves and roses along it. */
private fun DrawScope.briar(from: Offset, to: Offset, bend: Float, t: Float, seed: Int, roses: Int) {
    val ctrl = (from + to) / 2f + Offset(-(to.y - from.y), to.x - from.x) * bend
    fun at(f: Float) = from * ((1 - f) * (1 - f)) + ctrl * (2 * (1 - f) * f) + to * (f * f)
    val cane = Path().apply { moveTo(from.x, from.y); quadraticBezierTo(ctrl.x, ctrl.y, to.x, to.y) }
    drawPath(cane, Color(0xFF233A1C), style = Stroke(7f, cap = StrokeCap.Round))
    drawPath(cane, Color(0xFF4A6B33).copy(alpha = 0.6f), style = Stroke(2f, cap = StrokeCap.Round))
    for (i in 1 until 22) {
        val f = i / 22f
        val p = at(f)
        val d = at(f + 0.01f) - p
        val n = Offset(-d.y, d.x) / (d.getDistance().coerceAtLeast(0.001f))
        val side = if (i % 2 == 0) 1f else -1f
        // A thorn: hooked, reddish at the tip.
        val tip = p + n * side * 16f + d / d.getDistance().coerceAtLeast(0.001f) * 7f
        val thorn = Path().apply { moveTo(p.x - d.x * 0.6f, p.y - d.y * 0.6f); lineTo(tip.x, tip.y); lineTo(p.x + d.x * 0.6f, p.y + d.y * 0.6f); close() }
        drawPath(thorn, Brush.linearGradient(listOf(Color(0xFF2E4421), Color(0xFF8A3A2A)), start = p, end = tip))
        if (i % 3 == 1) leafAt(p, 34f + rnd(i, seed) * 14f, (kotlin.math.atan2(n.y * side, n.x * side) * 180f / PI.toFloat()) + sin(t * 0.8f + i) * 6f, Color(0xFF3F6B2E))
    }
    for (k in 0 until roses) {
        val f = 0.3f + k * 0.6f / roses.coerceAtLeast(1) + rnd(k, seed + 1) * 0.1f
        val sway = Offset(sin(t * 0.6f + k + seed) * 3f, cos(t * 0.5f + k) * 2f)
        rose(at(f) + sway, 40f + rnd(k, seed + 2) * 22f, if (k % 3 == 2) Color(0xFF8E0F24) else Color(0xFFB3122E), t * 0.05f + k)
    }
}

private fun DrawScope.fallingPetals(t: Float, k: Float, n: Int, colors: List<Color>, salt: Int, size: Float = 1f) {
    val w = this.size.width
    val h = this.size.height
    for (i in 0 until (n * k).toInt().coerceAtLeast(3)) {
        val depth = 0.4f + rnd(i, salt) * 0.6f
        val y = frac(rnd(i, salt + 1) + t * 0.022f * depth) * (h + 60f) - 30f
        val x = frac(rnd(i, salt + 2) + t * 0.006f * depth) * w + sin(t * 1.1f + i) * 40f * depth
        val turn = cos(t * (1.2f + rnd(i, salt + 3) * 1.6f) + i)
        val c = colors[i % colors.size]
        val s = (7f + 9f * depth) * size
        withTransform({ rotate(t * 50f * (rnd(i, salt + 4) - 0.5f) + i * 47f, Offset(x, y)); scale(turn, 1f, Offset(x, y)) }) {
            val petal = Path().apply {
                moveTo(x, y - s)
                cubicTo(x + s * 0.9f, y - s * 0.6f, x + s * 0.7f, y + s * 0.7f, x, y + s * 0.6f)
                cubicTo(x - s * 0.7f, y + s * 0.7f, x - s * 0.9f, y - s * 0.6f, x, y - s)
                close()
            }
            drawPath(petal, Brush.radialGradient(listOf(lerp(c, Color.White, 0.3f), c, lerp(c, Color.Black, 0.35f)), center = Offset(x, y + s * 0.4f), radius = s * 1.4f), alpha = 0.55f + 0.45f * depth)
        }
    }
}

internal fun DrawScope.rosesAndThorns(t: Float, k: Float) {
    val w = size.width
    val h = size.height
    drawRect(Brush.radialGradient(listOf(Color.Transparent, Color(0x55200308)), center = Offset(w / 2, h / 2), radius = w * 0.7f))
    // Briars climbing in from the corners and along the bottom.
    briar(Offset(-20f, h + 20f), Offset(w * 0.22f, h * 0.55f), 0.22f, t, 1, 2)
    briar(Offset(-30f, h * 0.7f), Offset(w * 0.12f, h * 0.18f), -0.18f, t, 2, 1)
    briar(Offset(w + 20f, h + 20f), Offset(w * 0.78f, h * 0.6f), -0.2f, t, 3, 2)
    briar(Offset(w + 30f, h * 0.65f), Offset(w * 0.9f, h * 0.12f), 0.2f, t, 4, 1)
    briar(Offset(w * 0.3f, h + 30f), Offset(w * 0.7f, h + 10f), 0.09f, t, 5, 3)
    fallingPetals(t, k, 22, listOf(Color(0xFFB3122E), Color(0xFF8E0F24), Color(0xFFD23A4F)), 1200)
}

// ---- Flower garden ----

private fun DrawScope.daisy(c: Offset, r: Float, turn: Float) {
    for (p in 0 until 14) {
        val a = turn + p * 2 * PI.toFloat() / 14
        withTransform({ rotate(a * 180f / PI.toFloat(), c) }) {
            drawOval(Brush.linearGradient(listOf(Color(0xFFDCDCE0), Color.White), start = c, end = c + Offset(r, 0f)), topLeft = c + Offset(r * 0.2f, -r * 0.13f), size = Size(r * 0.85f, r * 0.26f))
        }
    }
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE066), Color(0xFFE0A100), Color(0xFF8A5A00)), center = c - Offset(r * 0.08f, r * 0.08f), radius = r * 0.32f), r * 0.27f, c)
}

private fun DrawScope.poppy(c: Offset, r: Float, turn: Float) {
    for (p in 0 until 4) {
        val a = turn + p * PI.toFloat() / 2
        val pc = c + Offset(cos(a) * r * 0.35f, sin(a) * r * 0.35f)
        drawCircle(Brush.radialGradient(listOf(Color(0xFFFF5A3C), Color(0xFFD9230F), Color(0xFF7A0E05)), center = pc - Offset(r * 0.2f, r * 0.25f), radius = r * 0.9f), r * 0.62f, pc)
    }
    drawCircle(Color(0xFF1A1410), r * 0.2f, c)
    for (i in 0 until 10) { val a = i * 0.63f; drawCircle(Color(0xFF2B2016), 1.6f, c + Offset(cos(a) * r * 0.28f, sin(a) * r * 0.28f)) }
}

private fun DrawScope.lavender(base: Offset, hgt: Float, sway: Float) {
    val tip = base + Offset(sway, -hgt)
    drawLine(Color(0xFF4F6B3A), base, tip, 2f)
    for (i in 0 until 12) {
        val f = 0.55f + i * 0.04f
        val p = base + (tip - base) * f
        drawOval(Color(0xFF8E6FCF).copy(alpha = 0.9f), topLeft = p - Offset(4f, 3f), size = Size(8f, 6f))
        drawOval(Color(0xFFB79CF0).copy(alpha = 0.6f), topLeft = p - Offset(2.5f, 3.5f), size = Size(4f, 3f))
    }
}

private fun DrawScope.butterfly(c: Offset, s: Float, flap: Float, hue: Color) {
    val open = 0.25f + 0.75f * abs(sin(flap))
    for (side in listOf(-1f, 1f)) {
        withTransform({ scale(open * side, 1f, c) }) {
            drawOval(Brush.radialGradient(listOf(lerp(hue, Color.White, 0.3f), hue, Color(0xFF1A1208)), center = c + Offset(s * 0.5f, -s * 0.3f), radius = s * 1.1f), topLeft = c + Offset(0f, -s), size = Size(s * 1.1f, s * 1.0f))
            drawOval(Brush.radialGradient(listOf(hue, Color(0xFF1A1208)), center = c + Offset(s * 0.4f, s * 0.35f), radius = s * 0.8f), topLeft = c + Offset(0f, -s * 0.05f), size = Size(s * 0.75f, s * 0.7f))
        }
    }
    drawOval(Color(0xFF1A1208), topLeft = c - Offset(s * 0.08f, s * 0.6f), size = Size(s * 0.16f, s * 1.2f))
}

internal fun DrawScope.flowerGarden(t: Float, k: Float) {
    val w = size.width
    val h = size.height
    glow(Offset(w * 0.15f, h * 0.05f), h * 0.9f, Color(0xFFFFE9B8), 0.16f)
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0x553A5A2A)), startY = h * 0.68f))
    // Three ranks: far ones small and hazed, near ones large.
    for (rank in 0 until 3) {
        val n = (40 - rank * 10)
        val scale = 0.55f + rank * 0.3f
        val baseY = h + 10f - (2 - rank) * 14f
        for (i in 0 until n) {
            val id = i + rank * 100
            val x = w * (i + rnd(id, 1300) * 0.9f) / n
            val hgt = h * (0.1f + rnd(id, 1301) * 0.1f) * scale
            val sway = sin(t * 0.9f + x * 0.01f + rank) * 10f * scale + (fbm(t * 0.2f + x * 0.002f, 1302) - 0.5f) * 14f
            val base = Offset(x, baseY)
            val head = base + Offset(sway, -hgt)
            when ((rnd(id, 1303) * 3).toInt()) {
                0 -> {
                    drawPath(Path().apply { moveTo(base.x, base.y); quadraticBezierTo(base.x + sway * 0.2f, base.y - hgt * 0.5f, head.x, head.y) }, Color(0xFF3E6B2C), style = Stroke(2.2f * scale))
                    daisy(head, 16f * scale, rnd(id, 1304) * 6f)
                }
                1 -> {
                    drawPath(Path().apply { moveTo(base.x, base.y); quadraticBezierTo(base.x + sway * 0.2f, base.y - hgt * 0.5f, head.x, head.y) }, Color(0xFF4C7A33), style = Stroke(2f * scale))
                    poppy(head, 17f * scale, rnd(id, 1305) * 6f)
                }
                else -> lavender(base, hgt * 1.1f, sway)
            }
            if (rank < 2) drawRect(Color(0xFF8FA6C0).copy(alpha = 0.06f), topLeft = Offset(x - 20f, head.y - 20f), size = Size(40f, hgt + 20f))
        }
    }
    // Grass blades in front.
    for (i in 0 until 160) {
        val x = w * rnd(i, 1306)
        val hgt = 20f + rnd(i, 1307) * 50f
        val sway = sin(t * 1.3f + x * 0.02f) * 8f
        drawLine(lerp(Color(0xFF2E5220), Color(0xFF6E9B48), rnd(i, 1308)), Offset(x, h), Offset(x + sway, h - hgt), 2f, StrokeCap.Round)
    }
    for (b in 0 until 3) {
        val c = Offset(w * (0.2f + fbm(t * 0.05f + b * 4f, 1309) * 0.6f), h * (0.35f + fbm(t * 0.07f + b * 7f, 1310) * 0.35f))
        butterfly(c, 16f, t * 9f + b, listOf(Color(0xFFFF9F1C), Color(0xFF4FA3FF), Color(0xFFF2D14B))[b])
    }
    fallingPetals(t, k, 14, listOf(Color(0xFFFFFFFF), Color(0xFFFF6B4A), Color(0xFFB79CF0)), 1311, 0.8f)
}

// ---- Poetry ----

private fun DrawScope.inkMountains(t: Float) {
    val w = size.width
    val h = size.height
    for (r in 0 until 4) {
        val base = h * (0.55f + r * 0.1f)
        val tone = lerp(Color(0xFF9AA6B8), Color(0xFF2A3240), r / 3f).copy(alpha = 0.18f + r * 0.1f)
        val p = Path().apply {
            moveTo(0f, h)
            var x = 0f
            while (x <= w) { lineTo(x, base - fbm(x / (500f - r * 80f) + r * 9f + t * 0.002f, 1400 + r) * h * (0.32f - r * 0.05f)); x += 12f }
            lineTo(w, h); close()
        }
        drawPath(p, Brush.verticalGradient(listOf(tone, tone.copy(alpha = 0f)), startY = base - h * 0.3f, endY = h))
        // Mist lying in the valley in front of each ridge.
        for (m in 0 until 6) glow(Offset(frac(rnd(m, 1410 + r) + t * 0.004f) * (w + 600f) - 300f, base + 10f), 240f, Color(0xFFD6DCE6), 0.08f, additive = false)
    }
}

/** A verse set in a serif italic, fading in and out on a slow cycle, its poet beneath. */
internal fun DrawScope.verse(poem: Poem, at: Offset, alpha: Float, sizePx: Float, align: android.graphics.Paint.Align) {
    if (alpha <= 0f) return
    val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        textSize = sizePx
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.ITALIC)
        color = android.graphics.Color.argb(a, 240, 236, 226)
        textAlign = align
        setShadowLayer(8f, 0f, 2f, android.graphics.Color.argb(a, 0, 0, 0))
    }
    val small = android.graphics.Paint(paint).apply {
        textSize = sizePx * 0.68f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.NORMAL)
        color = android.graphics.Color.argb((a * 0.7f).toInt(), 220, 214, 200)
        letterSpacing = 0.06f
    }
    drawContext.canvas.nativeCanvas.apply {
        poem.lines.forEachIndexed { i, line ->
            // Tanka indentation: the second and fourth lines set in.
            val indent = if (i % 2 == 1) sizePx * 0.9f * (if (align == android.graphics.Paint.Align.RIGHT) -1 else 1) else 0f
            drawText(line, at.x + (if (align == android.graphics.Paint.Align.CENTER) 0f else indent), at.y + i * sizePx * 1.45f, paint)
        }
        drawText("— ${poem.poet}", at.x, at.y + poem.lines.size * sizePx * 1.45f + sizePx * 0.6f, small)
    }
}

internal fun DrawScope.poetry(t: Float, k: Float) {
    val w = size.width
    val h = size.height
    inkMountains(t)
    fallingPetals(t, k, 26, listOf(Color(0xFFF7C6D0), Color(0xFFF2AFC0), Color(0xFFFFE3EA)), 1420, 0.75f)
    // One verse at a time: 3 s in, ~22 s up, 3 s out.
    val cycle = 30f
    val n = floor(t / cycle).toLong()
    val u = t - n * cycle
    val alpha = when { u < 3f -> u / 3f; u > cycle - 3f -> (cycle - u) / 3f; else -> 1f }
    val poem = dev.nitrate.core.poetry.Poems.at((n + (System.currentTimeMillis() / 86_400_000L)) * 30_000L)
    verse(poem, Offset(w - 600f, h * 0.6f), alpha * 0.92f, 30f, android.graphics.Paint.Align.LEFT)
}

// ---- Drawn pre-roll scenes (full screen, opaque) ----

internal fun DrawScope.oceanFloor(t: Float, k: Float) {
    val w = size.width
    val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color(0xFF0E5470), Color(0xFF06324A), Color(0xFF021522))))
    underwater(t, k)
    // The sea bed: rippled sand lit from above, rocks and swaying kelp, drifting slowly past.
    val drift = t * 12f
    val bed = Path().apply {
        moveTo(0f, h)
        var x = 0f
        while (x <= w) { lineTo(x, h * 0.8f - fbm((x + drift) / 300f, 1500) * h * 0.08f); x += 10f }
        lineTo(w, h); close()
    }
    drawPath(bed, Brush.verticalGradient(listOf(Color(0xFF6E8A7E), Color(0xFF2F4A46), Color(0xFF0F1E20)), startY = h * 0.72f, endY = h))
    for (i in 0 until 60) {
        val x = ((rnd(i, 1501) * (w + 400f) - drift * 1.0f) % (w + 400f) + w + 400f) % (w + 400f) - 200f
        drawLine(Color(0x22FFFFFF), Offset(x, h * 0.86f + rnd(i, 1502) * h * 0.1f), Offset(x + 30f, h * 0.86f + rnd(i, 1502) * h * 0.1f - 3f), 1.5f)
    }
    for (r in 0 until 7) {
        val x = ((rnd(r, 1503) * (w + 600f) - drift * 1.2f) % (w + 600f) + w + 600f) % (w + 600f) - 300f
        val rs = 40f + rnd(r, 1504) * 80f
        drawOval(Brush.radialGradient(listOf(Color(0xFF4A5A55), Color(0xFF18221F)), center = Offset(x - rs * 0.3f, h * 0.84f - rs * 0.4f), radius = rs * 1.3f), topLeft = Offset(x - rs, h * 0.86f - rs * 0.8f), size = Size(rs * 2, rs * 1.1f))
    }
    for (kelp in 0 until 14) {
        val x = ((rnd(kelp, 1505) * (w + 400f) - drift * 1.4f) % (w + 400f) + w + 400f) % (w + 400f) - 200f
        val hgt = h * (0.3f + rnd(kelp, 1506) * 0.35f)
        val p = Path().apply {
            moveTo(x, h)
            for (s in 1..20) {
                val f = s / 20f
                lineTo(x + sin(t * 0.8f + f * 4f + kelp) * 30f * f, h - hgt * f)
            }
        }
        drawPath(p, Color(0xCC2E5A2A), style = Stroke(9f, cap = StrokeCap.Round))
        drawPath(p, Color(0x557FA35A), style = Stroke(2.5f, cap = StrokeCap.Round))
    }
}

internal fun DrawScope.northernLights(t: Float, k: Float) {
    val w = size.width
    val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color(0xFF020611), Color(0xFF071530), Color(0xFF0B1E33))))
    for (i in 0 until 260) {
        val tw = 0.5f + 0.5f * sin(t * (1 + rnd(i, 1600) * 2) + i)
        drawCircle(Color.White.copy(alpha = 0.25f + 0.55f * tw * rnd(i, 1601)), 0.6f + rnd(i, 1602) * 1.2f, Offset(rnd(i, 1603) * w, rnd(i, 1604) * h * 0.7f))
    }
    // Curtains: columns of light hanging from a wandering line, green below, violet at the top, rippling.
    for (band in 0 until 3) {
        val baseY = h * (0.42f + band * 0.07f)
        var x = -20f
        while (x < w + 20f) {
            val y = baseY + (fbm(x / 600f + t * 0.03f + band * 3f, 1610 + band) - 0.5f) * h * 0.3f
            val strength = (fbm(x / 140f - t * 0.25f + band * 5f, 1620 + band)).let { it * it } * 1.6f
            val tall = h * (0.18f + 0.22f * fbm(x / 300f + t * 0.05f, 1630 + band))
            if (strength > 0.05f) {
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF9B5CFF).copy(alpha = 0.18f * strength), Color(0xFF3DFFA0).copy(alpha = 0.45f * strength), Color(0xFF3DFFA0).copy(alpha = 0f)), startY = y - tall, endY = y + 10f),
                    topLeft = Offset(x, y - tall), size = Size(7f, tall + 10f), blendMode = BlendMode.Plus)
            }
            x += 5f
        }
    }
    // Mountains and their reflection in a still lake.
    val shore = h * 0.78f
    val ridge = Path().apply {
        moveTo(0f, shore)
        var x = 0f
        while (x <= w) { lineTo(x, shore - fbm(x / 380f, 1640) * h * 0.2f); x += 10f }
        lineTo(w, shore); close()
    }
    drawPath(ridge, Color(0xFF03060C))
    drawRect(Brush.verticalGradient(listOf(Color(0xFF0A1A2A), Color(0xFF02050A)), startY = shore, endY = h), topLeft = Offset(0f, shore), size = Size(w, h - shore))
    for (i in 0 until 40) {
        val x = rnd(i, 1650) * w
        val y = shore + rnd(i, 1651) * (h - shore)
        drawLine(Color(0xFF3DFFA0).copy(alpha = 0.10f * (1 - (y - shore) / (h - shore))), Offset(x, y), Offset(x + 40f + rnd(i, 1652) * 80f, y), 1.5f, blendMode = BlendMode.Plus)
    }
}

internal fun DrawScope.starfield(t: Float, k: Float) {
    val w = size.width
    val h = size.height
    drawRect(Color(0xFF01020A))
    val c = Offset(w / 2, h / 2)
    glow(c, h * 0.5f, Color(0xFF3A4FA8), 0.15f)
    // Stars rushing past: each comes from far off near the centre and streaks outward as it nears.
    val speed = 0.18f + 0.12f * (sin(t * 0.2f) * 0.5f + 0.5f)
    for (i in 0 until (420 * k).toInt()) {
        val a = rnd(i, 1700) * 2 * PI.toFloat()
        val z = frac(rnd(i, 1701) + t * speed)
        val depth = z * z * z
        val r = depth * w * 0.75f
        val prev = (z - 0.03f).coerceAtLeast(0f).let { it * it * it } * w * 0.75f
        val dir = Offset(cos(a), sin(a))
        val col = lerp(Color(0xFFAFC6FF), Color.White, rnd(i, 1702))
        drawLine(col.copy(alpha = (0.15f + z).coerceAtMost(1f)), c + dir * prev, c + dir * r, 0.6f + z * 2.6f, StrokeCap.Round, blendMode = BlendMode.Plus)
    }
}

/** The drawn pre-roll scenes; those with no drawing say their film clip isn't in this build. */
object PrerollDrawing {
    fun DrawScope.draw(scene: dev.nitrate.core.preroll.PrerollScene, t: Float) {
        when (scene) {
            dev.nitrate.core.preroll.PrerollScene.OCEAN_FLOOR -> oceanFloor(t, 1f)
            dev.nitrate.core.preroll.PrerollScene.NORTHERN_LIGHTS -> northernLights(t, 1f)
            dev.nitrate.core.preroll.PrerollScene.STARFIELD -> starfield(t, 1f)
            else -> {
                drawRect(Color.Black)
                drawContext.canvas.nativeCanvas.drawText(
                    "${scene.label}: its film clip isn't in this build", size.width / 2, size.height / 2,
                    android.graphics.Paint().apply { color = android.graphics.Color.GRAY; textSize = 28f; isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER },
                )
            }
        }
    }
}
