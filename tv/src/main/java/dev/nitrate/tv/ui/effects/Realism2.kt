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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/* The rest of the scenes in the light-first style of Realism.kt. */

private fun frac(x: Float) = x - floor(x)

/** Bloom: a line drawn as a wide faint glow, a tighter one and a bright core. */
private fun DrawScope.neonLine(a: Offset, b: Offset, color: Color, alpha: Float, width: Float = 2f) {
    drawLine(color.copy(alpha = 0.10f * alpha), a, b, width * 9, StrokeCap.Round, blendMode = BlendMode.Plus)
    drawLine(color.copy(alpha = 0.28f * alpha), a, b, width * 3.5f, StrokeCap.Round, blendMode = BlendMode.Plus)
    drawLine(lerp(color, Color.White, 0.5f).copy(alpha = alpha), a, b, width, StrokeCap.Round)
}

// ---- Space ----

internal fun DrawScope.deepSpace(t: Float, k: Float) {
    val w = size.width
    val h = size.height
    // Nebula: big soft clouds of gas, slowly turning.
    val gas = listOf(Color(0xFF6B3FA0), Color(0xFF2A6F97), Color(0xFFB0457A), Color(0xFF3B4FA8))
    for (i in 0 until 26) {
        val x = w * (0.45f + (fbm(i * 0.71f, 801) - 0.5f) * 1.2f) + sin(t * 0.01f + i) * 20f
        val y = h * (0.3f + (fbm(i * 0.53f, 802) - 0.5f) * 0.9f)
        glow(Offset(x, y), 160f + rnd(i, 803) * 260f, gas[i % gas.size], 0.10f + rnd(i, 804) * 0.08f)
    }
    // Stars: colour by temperature, twinkling, slowly drifting; a few bright ones with a glow.
    val temps = listOf(Color(0xFFAFC6FF), Color.White, Color(0xFFFFF1D6), Color(0xFFFFD2A1))
    for (i in 0 until (260 * k).toInt()) {
        val x = frac(rnd(i, 811) - t * 0.0015f * (0.4f + rnd(i, 812))) * w
        val y = rnd(i, 813) * h
        val tw = 0.55f + 0.45f * sin(t * (0.8f + rnd(i, 814) * 2.5f) + i)
        val big = rnd(i, 815) > 0.97f
        val c = temps[(rnd(i, 816) * temps.size).toInt().coerceAtMost(temps.size - 1)]
        if (big) glow(Offset(x, y), 14f, c, 0.6f * tw)
        drawCircle(c.copy(alpha = (0.35f + 0.6f * tw).coerceIn(0f, 1f)), if (big) 1.8f else 0.6f + rnd(i, 817) * 0.9f, Offset(x, y))
    }
    // A planet, lit from the left, with a thin bright atmosphere on its lit edge.
    val pc = Offset(w * 0.86f, h * 0.2f)
    val pr = h * 0.13f
    drawCircle(Brush.radialGradient(listOf(Color(0xFF9DB6E0), Color(0xFF3D5A8A), Color(0xFF0D1626), Color(0xFF05080F)), center = pc - Offset(pr * 0.5f, pr * 0.35f), radius = pr * 1.7f), pr, pc)
    drawCircle(Color(0xFF8FB8FF).copy(alpha = 0.5f), pr * 1.02f, pc, style = Stroke(3f), blendMode = BlendMode.Plus)
    glow(pc - Offset(pr * 0.3f, pr * 0.2f), pr * 1.7f, Color(0xFF6E9BFF), 0.18f)
    // A comet now and then: glowing head, a long tail that tapers and fades.
    val cycle = floor(t / 21f).toInt()
    val u = (t - cycle * 21f) / 6f
    if (u in 0f..1f) {
        val from = Offset(w * (1.05f - rnd(cycle, 820) * 0.2f), h * (0.05f + rnd(cycle, 821) * 0.2f))
        val dir = Offset(-w * 0.9f, h * 0.35f)
        val head = from + dir * u
        val back = Offset(-dir.x, -dir.y) / dir.getDistance()
        for (s in 0 until 30) {
            val f = s / 30f
            glow(head + back * (f * 340f), 18f * (1 - f) + 4f, Color(0xFFCFE6FF), 0.5f * (1 - f) * (1 - f))
        }
        glow(head, 30f, Color.White, 0.9f)
    }
}

// ---- Underwater ----

internal fun DrawScope.underwater(t: Float, k: Float) {
    val w = size.width
    val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color(0x5520A4C8), Color(0x44064A6E), Color(0x66021826))))
    // Light shafts from the surface, swaying, brighter at the top.
    for (i in 0 until 7) {
        val x = w * (0.05f + i * 0.15f) + (fbm(t * 0.08f + i, 830) - 0.5f) * 160f
        val spread = 90f + rnd(i, 831) * 120f
        val ray = Path().apply { moveTo(x - 30f, 0f); lineTo(x + 30f, 0f); lineTo(x + spread + 140f, h); lineTo(x - spread + 140f, h); close() }
        drawPath(ray, Brush.verticalGradient(listOf(Color(0x30CFF6FF), Color(0x0ACFF6FF), Color.Transparent)), blendMode = BlendMode.Plus)
    }
    // Caustics: the surface's light rippling across the top.
    for (i in 0 until 40) {
        val x = w * rnd(i, 832) + (fbm(t * 0.3f + i, 833) - 0.5f) * 120f
        val y = h * 0.12f * rnd(i, 834) + (fbm(t * 0.25f + i, 835) - 0.5f) * 30f
        glow(Offset(x, y), 50f + rnd(i, 836) * 60f, Color(0xFFBFF4FF), 0.08f + 0.06f * sin(t * 1.3f + i))
    }
    // Marine snow drifting down, bubbles rising with a highlight.
    for (i in 0 until (120 * k).toInt()) {
        val y = frac(rnd(i, 837) + t * 0.01f * (0.5f + rnd(i, 838))) * h
        val x = rnd(i, 839) * w + sin(t * 0.4f + i) * 12f
        drawCircle(Color(0xFFD8EEF4).copy(alpha = 0.25f * rnd(i, 840)), 0.8f + rnd(i, 841) * 1.4f, Offset(x, y))
    }
    for (i in 0 until (36 * k).toInt()) {
        val p = frac(rnd(i, 842) + t * 0.06f * (0.6f + rnd(i, 843)))
        val y = h * (1.05f - p * 1.1f)
        val x = w * rnd(i, 844) + sin(t * 2f + i) * 10f * p
        val r = 3f + rnd(i, 845) * 9f
        drawCircle(Color(0x30CFF4FF), r, Offset(x, y))
        drawCircle(Color(0x88E6FAFF), r, Offset(x, y), style = Stroke(1.2f))
        drawCircle(Color(0xCCFFFFFF), r * 0.25f, Offset(x - r * 0.35f, y - r * 0.35f))
    }
    // A school of small fish turning together in the distance.
    val school = Offset(w * (0.3f + fbm(t * 0.04f, 846) * 0.5f), h * (0.35f + fbm(t * 0.05f, 847) * 0.3f))
    val heading = (fbm(t * 0.04f + 0.1f, 846) - fbm(t * 0.04f, 846)) * 2000f
    for (i in 0 until 40) {
        val off = Offset((rnd(i, 848) - 0.5f) * 260f + sin(t * 1.5f + i) * 8f, (rnd(i, 849) - 0.5f) * 110f + cos(t * 1.3f + i) * 6f)
        val c = school + off
        rotate(if (heading >= 0) 0f else 180f, c) {
            drawOval(Color(0x9910293A), topLeft = c - Offset(9f, 3f), size = Size(18f, 6f))
            val tail = Path().apply { moveTo(c.x - 8f, c.y); lineTo(c.x - 14f, c.y - 4f); lineTo(c.x - 14f, c.y + 4f); close() }
            drawPath(tail, Color(0x9910293A))
            drawCircle(Color(0x55BFE6F0), 2f, c + Offset(3f, -1f), blendMode = BlendMode.Plus)
        }
    }
}

// ---- Haunted / Halloween ----

private fun DrawScope.moon(c: Offset, r: Float) {
    glow(c, r * 5f, Color(0xFFE8E4C9), 0.25f)
    drawCircle(Brush.radialGradient(listOf(Color(0xFFF7F3DF), Color(0xFFD9D3B8), Color(0xFFB3AD93)), center = c - Offset(r * 0.3f, r * 0.3f), radius = r * 1.4f), r, c)
    // Maria: soft darker patches.
    for (i in 0 until 7) glow(c + Offset((rnd(i, 850) - 0.5f) * r * 1.2f, (rnd(i, 851) - 0.5f) * r * 1.2f), r * (0.2f + rnd(i, 852) * 0.25f), Color(0xFF8E8A76), 0.35f, additive = false)
}

private fun DrawScope.fogBands(t: Float, from: Float, color: Color, salt: Int) {
    val w = size.width
    val h = size.height
    for (i in 0 until 24) {
        val x = frac(rnd(i, salt) + t * 0.006f * (0.6f + rnd(i, salt + 1))) * (w + 800f) - 400f
        val y = h * (from + rnd(i, salt + 2) * (1 - from)) + sin(t * 0.2f + i) * 10f
        glow(Offset(x, y), 220f + rnd(i, salt + 3) * 200f, color, 0.16f, additive = false)
    }
}

private fun DrawScope.realBat(c: Offset, s: Float, phase: Float, alpha: Float = 1f) {
    val flap = sin(phase)
    val col = Color(0xFF07070A).copy(alpha = alpha)
    val p = Path().apply {
        moveTo(c.x, c.y - s * 0.15f)
        // Each wing: three finger points, the membrane scalloped between them.
        for (side in listOf(-1f, 1f)) {
            moveTo(c.x, c.y - s * 0.1f)
            val tipY = c.y - s * 0.6f * flap
            cubicTo(c.x + side * s * 0.6f, c.y - s * 0.5f * flap - s * 0.2f, c.x + side * s * 1.4f, tipY - s * 0.2f, c.x + side * s * 2f, tipY)
            quadraticBezierTo(c.x + side * s * 1.7f, tipY + s * 0.35f, c.x + side * s * 1.45f, tipY + s * 0.55f + s * 0.1f * flap)
            quadraticBezierTo(c.x + side * s * 1.1f, c.y + s * 0.1f, c.x + side * s * 0.95f, c.y + s * 0.35f)
            quadraticBezierTo(c.x + side * s * 0.55f, c.y + s * 0.1f, c.x + side * s * 0.2f, c.y + s * 0.3f)
            close()
        }
    }
    drawPath(p, col)
    drawOval(col, topLeft = c - Offset(s * 0.18f, s * 0.3f), size = Size(s * 0.36f, s * 0.65f))
    drawCircle(col, s * 0.16f, c - Offset(0f, s * 0.32f))
}

private fun DrawScope.bats(t: Float, k: Float, n: Int, salt: Int) {
    val w = size.width
    val h = size.height
    for (b in 0 until (n * k).toInt().coerceAtLeast(2)) {
        // Erratic flight: a wandering path, quick wingbeats, the far ones smaller and fainter.
        val depth = 0.4f + rnd(b, salt) * 0.6f
        val x = frac(rnd(b, salt + 1) + t * 0.03f * (0.5f + depth) * (if (b % 2 == 0) 1 else -1)) * (w + 200f) - 100f
        val y = h * (0.08f + fbm(t * 0.4f + b * 3.1f, salt + 2) * 0.45f)
        realBat(Offset(x, y), 10f + 14f * depth, t * (16f + b) + b, 0.6f + 0.4f * depth)
    }
}

private fun DrawScope.deadTree(base: Offset, len: Float, angle: Float, depth: Int, seed: Int) {
    if (depth == 0 || len < 4f) return
    val end = base + Offset(cos(angle) * len, sin(angle) * len)
    drawLine(Color(0xF0050507), base, end, max(1.2f, len * 0.09f), StrokeCap.Round)
    val forks = if (depth > 5) 2 else 3
    for (i in 0 until forks) {
        val turn = (rnd(seed * 7 + i, 860) - 0.5f) * 1.3f
        deadTree(end, len * (0.62f + rnd(seed * 3 + i, 861) * 0.18f), angle + turn, depth - 1, seed * 5 + i + 1)
    }
}

internal fun DrawScope.haunted(t: Float, k: Float) {
    val w = size.width
    val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color(0x660A1020), Color.Transparent), endY = h * 0.5f))
    val mc = Offset(w * 0.84f, h * 0.17f)
    moon(mc, h * 0.075f)
    // Thin cloud passing over the moon.
    val cx = frac(t * 0.01f) * (w + 800f) - 400f
    glow(Offset(cx, mc.y + 10f), 260f, Color(0xFF2A2E3A), 0.5f, additive = false)
    deadTree(Offset(w * 0.04f, h), h * 0.22f, -PI.toFloat() / 2 + 0.12f, 8, 3)
    deadTree(Offset(w * 0.97f, h), h * 0.18f, -PI.toFloat() / 2 - 0.15f, 8, 9)
    bats(t, k, 7, 870)
    fogBands(t, 0.72f, Color(0xFFB8C0CC), 880)
}

private fun DrawScope.jackOLantern(c: Offset, s: Float, t: Float, seed: Int) {
    val flicker = 0.75f + 0.25f * fbm(t * 6f + seed, 890)
    glow(c, s * 3.2f, Color(0xFFFF8A1E), 0.35f * flicker)
    for (lobe in listOf(-0.55f, 0.55f, -0.25f, 0.25f, 0f)) {
        drawOval(Brush.radialGradient(listOf(Color(0xFFE8731A), Color(0xFFB4470C), Color(0xFF5A2105)), center = Offset(c.x + lobe * s - s * 0.2f, c.y - s * 0.5f), radius = s * 1.2f),
            topLeft = Offset(c.x + lobe * s - s * 0.55f, c.y - s), size = Size(s * 1.1f, s * 1.75f))
    }
    drawRoundRect(Color(0xFF3B2A12), topLeft = Offset(c.x - s * 0.08f, c.y - s * 1.22f), size = Size(s * 0.16f, s * 0.32f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f))
    val inner = Color(0xFFFFD36B).copy(alpha = flicker)
    fun hole(path: Path) { drawPath(path, inner); glow(path.getBounds().center, s * 0.35f, Color(0xFFFFB347), 0.6f * flicker) }
    hole(Path().apply { moveTo(c.x - s * 0.5f, c.y - s * 0.35f); lineTo(c.x - s * 0.22f, c.y - s * 0.35f); lineTo(c.x - s * 0.36f, c.y - s * 0.6f); close() })
    hole(Path().apply { moveTo(c.x + s * 0.5f, c.y - s * 0.35f); lineTo(c.x + s * 0.22f, c.y - s * 0.35f); lineTo(c.x + s * 0.36f, c.y - s * 0.6f); close() })
    hole(Path().apply {
        moveTo(c.x - s * 0.55f, c.y - s * 0.1f)
        quadraticBezierTo(c.x, c.y + s * 0.5f, c.x + s * 0.55f, c.y - s * 0.1f)
        lineTo(c.x + s * 0.3f, c.y + s * 0.02f); lineTo(c.x + s * 0.18f, c.y - s * 0.12f); lineTo(c.x, c.y + s * 0.05f)
        lineTo(c.x - s * 0.18f, c.y - s * 0.12f); lineTo(c.x - s * 0.3f, c.y + s * 0.02f); close()
    })
}

internal fun DrawScope.halloween(t: Float, k: Float) {
    val w = size.width
    val h = size.height
    moon(Offset(w * 0.86f, h * 0.15f), h * 0.06f)
    bats(t, k, 9, 900)
    fogBands(t, 0.78f, Color(0xFF9AA4B4), 910)
    for (p in 0 until 4) jackOLantern(Offset(w * (0.12f + p * 0.25f + rnd(p, 911) * 0.05f), h - 30f), 44f + rnd(p, 912) * 12f, t, p)
}

// ---- Seasons ----

private fun DrawScope.leaf(c: Offset, s: Float, spin: Float, flip: Float, color: Color) {
    withTransform({ rotate(spin, c); scale(flip, 1f, c) }) {
        // A maple-ish leaf: five lobes and a stem, shaded down its middle.
        val p = Path()
        val lobes = 5
        for (i in 0..lobes * 2) {
            val a = -PI.toFloat() / 2 + (i - lobes) * PI.toFloat() / (lobes + 0.5f) * 0.9f
            val r = if (i % 2 == 0) s else s * 0.62f
            val pt = c + Offset(cos(a) * r, sin(a) * r)
            if (i == 0) p.moveTo(pt.x, pt.y) else p.lineTo(pt.x, pt.y)
        }
        p.lineTo(c.x, c.y + s * 0.3f)
        p.close()
        drawPath(p, Brush.linearGradient(listOf(color, lerp(color, Color.Black, 0.35f)), start = c - Offset(s, 0f), end = c + Offset(s, 0f)))
        drawLine(lerp(color, Color.Black, 0.5f), c + Offset(0f, s * 0.6f), c - Offset(0f, s * 0.8f), 1.2f)
    }
}

internal fun DrawScope.autumn(t: Float, k: Float) {
    val w = size.width
    val h = size.height
    val colors = listOf(Color(0xFFC8461B), Color(0xFFE08A1E), Color(0xFF9E2B19), Color(0xFFD9B23A), Color(0xFF8A5A1E))
    for (i in 0 until (34 * k).toInt()) {
        val depth = 0.4f + rnd(i, 920) * 0.6f
        val y = frac(rnd(i, 921) + t * 0.03f * depth) * (h + 80f) - 40f
        val x = frac(rnd(i, 922) + t * 0.01f * depth) * w + sin(t * 1.2f + i) * 40f * depth
        val flip = cos(t * (1.5f + rnd(i, 923) * 2f) + i)
        leaf(Offset(x, y), 16f + 22f * depth, t * 40f * (rnd(i, 924) - 0.5f) + i * 37f, flip, colors[i % colors.size].copy(alpha = 0.6f + 0.4f * depth))
    }
}

internal fun DrawScope.christmasLights(t: Float) {
    val w = size.width
    val colors = listOf(Color(0xFFFF3B30), Color(0xFF34C759), Color(0xFFFFCC00), Color(0xFF0A84FF), Color(0xFFFF9500))
    val wire = Path().apply { moveTo(0f, 10f); var x = 0f; while (x <= w) { lineTo(x, 14f + abs(sin(x / 240f * PI.toFloat())) * 34f); x += 10f } }
    drawPath(wire, Color(0xFF14261A), style = Stroke(2.5f))
    var i = 0
    var x = 30f
    while (x < w) {
        val y = 14f + abs(sin(x / 240f * PI.toFloat())) * 34f + 12f
        val c = colors[i % colors.size]
        val on = 0.65f + 0.35f * fbm(t * 1.3f + i * 0.7f, 930)
        glow(Offset(x, y + 6f), 46f, c, 0.45f * on)
        drawOval(lerp(c, Color.White, 0.25f * on), topLeft = Offset(x - 5f, y - 2f), size = Size(10f, 16f))
        drawCircle(Color.White.copy(alpha = 0.7f * on), 2f, Offset(x - 1.5f, y + 3f))
        drawRect(Color(0xFF2E3A2E), topLeft = Offset(x - 3.5f, y - 7f), size = Size(7f, 6f))
        x += 64f
        i++
    }
}

internal fun DrawScope.christmas(t: Float, k: Float) {
    val w = size.width
    val h = size.height
    // Warm bokeh low down, as from lit windows and trees out of focus.
    for (i in 0 until 18) glow(Offset(w * rnd(i, 940), h * (0.75f + rnd(i, 941) * 0.25f)), 40f + rnd(i, 942) * 50f, listOf(Color(0xFFFFB04D), Color(0xFFFF5A4D), Color(0xFF7DFF8A))[i % 3], 0.18f + 0.06f * sin(t + i))
    realSnow(t, k, 950)
    christmasLights(t)
    // Now and then, far off, a sleigh crossing the moon.
    val mc = Offset(w * 0.8f, h * 0.2f)
    moon(mc, h * 0.07f)
    crossingX(t, 30f, w)?.let { x ->
        val y = mc.y + (x - mc.x) * 0.08f
        val dark = Color(0xEE07080C)
        for (r in 0 until 4) {
            val rx = x + 26f + r * 22f
            drawOval(dark, topLeft = Offset(rx - 8f, y - 4f), size = Size(16f, 7f))
            drawLine(dark, Offset(rx + 6f, y - 2f), Offset(rx + 10f, y - 10f), 1.6f)
            drawLine(dark, Offset(rx - 4f, y + 2f), Offset(rx - 5f + sin(t * 12 + r) * 3f, y + 9f), 1.4f)
        }
        drawRoundRect(dark, topLeft = Offset(x - 22f, y - 8f), size = Size(34f, 12f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f))
    }
}

private fun crossingX(t: Float, period: Float, w: Float): Float? {
    val p = frac(t / period)
    return if (p < 0.5f) -200f + p / 0.5f * (w + 400f) else null
}

internal fun DrawScope.newYear(t: Float, k: Float) {
    val w = size.width
    val h = size.height
    fireworkShow(t, k, listOf(Color(0xFFFFD27A), Color(0xFFF2F4FF), Color(0xFFFFB85C), Color(0xFFCFE0FF)), 960, floorY = 0.45f)
    // Confetti: foil squares tumbling (they flash as they turn to the light).
    val colors = listOf(Color(0xFFFFD700), Color(0xFFC0C0C0), Color(0xFFFF4D8D), Color(0xFF4DD2FF))
    for (i in 0 until (110 * k).toInt()) {
        val y = frac(rnd(i, 961) + t * 0.05f * (0.6f + rnd(i, 962))) * (h + 40f) - 20f
        val x = rnd(i, 963) * w + sin(t * 1.4f + i) * 30f
        val turn = cos(t * (3f + rnd(i, 964) * 4f) + i)
        val c = colors[i % colors.size]
        withTransform({ rotate(t * 60f * (rnd(i, 965) - 0.5f) + i * 23f, Offset(x, y)); scale(turn, 1f, Offset(x, y)) }) {
            drawRect(lerp(lerp(c, Color.Black, 0.4f), lerp(c, Color.White, 0.5f), abs(turn)), topLeft = Offset(x - 5f, y - 3f), size = Size(10f, 6f))
        }
    }
}

private fun DrawScope.softHeart(c: Offset, s: Float, color: Color, alpha: Float) {
    val p = Path().apply {
        moveTo(c.x, c.y + s * 0.35f)
        cubicTo(c.x - s, c.y - s * 0.3f, c.x - s * 0.45f, c.y - s, c.x, c.y - s * 0.45f)
        cubicTo(c.x + s * 0.45f, c.y - s, c.x + s, c.y - s * 0.3f, c.x, c.y + s * 0.35f)
        close()
    }
    glow(c, s * 2.4f, color, alpha * 0.35f)
    drawPath(p, Brush.radialGradient(listOf(lerp(color, Color.White, 0.45f), color, lerp(color, Color.Black, 0.3f)), center = c - Offset(s * 0.3f, s * 0.4f), radius = s * 1.4f), alpha = alpha)
}

internal fun DrawScope.valentines(t: Float, k: Float) {
    val w = size.width
    val h = size.height
    for (i in 0 until 16) glow(Offset(w * rnd(i, 970), h * rnd(i, 971)), 60f + rnd(i, 972) * 80f, Color(0xFFFF6B9A), 0.07f + 0.04f * sin(t * 0.7f + i))
    for (i in 0 until (22 * k).toInt()) {
        val depth = 0.35f + rnd(i, 973) * 0.65f
        val p = frac(rnd(i, 974) + t * 0.025f * depth)
        val y = h * (1.08f - p * 1.2f)
        val x = rnd(i, 975) * w + sin(t * 0.8f + i) * 30f
        softHeart(Offset(x, y), 14f + 28f * depth, if (i % 3 == 0) Color(0xFFFF8FB1) else Color(0xFFE0245E), (0.35f + 0.55f * depth) * (1 - abs(p - 0.5f) * 1.2f).coerceIn(0f, 1f))
    }
}

internal fun DrawScope.shamrocks(t: Float, k: Float) {
    val w = size.width
    val h = size.height
    for (i in 0 until 12) glow(Offset(w * rnd(i, 980), h * rnd(i, 981)), 60f + rnd(i, 982) * 60f, Color(0xFF3DDC84), 0.06f)
    for (i in 0 until (28 * k).toInt()) {
        val depth = 0.4f + rnd(i, 983) * 0.6f
        val y = frac(rnd(i, 984) + t * 0.025f * depth) * (h + 60f) - 30f
        val x = rnd(i, 985) * w + sin(t + i) * 30f
        val s = 12f + 16f * depth
        val c = Offset(x, y)
        val turn = cos(t * 1.8f + i)
        withTransform({ rotate(t * 30f + i * 40f, c); scale(turn, 1f, c) }) {
            val green = lerp(Color(0xFF0F5C2E), Color(0xFF3BB46A), abs(turn))
            for (q in 0 until 3) {
                val a = q * 2.094f - PI.toFloat() / 2
                softHeart(c + Offset(cos(a) * s * 0.75f, sin(a) * s * 0.75f), s * 0.8f, green, 0.6f + 0.4f * depth)
            }
            drawLine(green, c, c + Offset(s * 0.3f, s * 1.4f), 2f)
        }
    }
}

// ---- Desert, city, visitors, arcade, Festivus ----

internal fun DrawScope.desert(t: Float, k: Float) {
    val w = size.width
    val h = size.height
    glow(Offset(w * 0.8f, h * 0.12f), h * 0.7f, Color(0xFFFFC874), 0.18f)
    glow(Offset(w * 0.8f, h * 0.12f), h * 0.08f, Color(0xFFFFF4D6), 0.9f)
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0x33E6A35A), Color(0x55B9773A)), startY = h * 0.55f))
    // Dunes in three ranks, lit on the windward side.
    for (rank in 0 until 3) {
        val base = h * (0.82f + rank * 0.06f)
        val path = Path().apply {
            moveTo(0f, h)
            var x = 0f
            while (x <= w) { lineTo(x, base - fbm(x / (420f - rank * 90f) + rank * 5f, 990 + rank) * h * (0.09f - rank * 0.015f)); x += 16f }
            lineTo(w, h); close()
        }
        val lit = lerp(Color(0xFFE2B077), Color(0xFFC78A4C), rank / 2f)
        drawPath(path, Brush.verticalGradient(listOf(lit.copy(alpha = 0.55f + rank * 0.15f), lerp(lit, Color(0xFF5A3618), 0.5f).copy(alpha = 0.7f + rank * 0.1f)), startY = base - h * 0.1f, endY = h))
    }
    // Sand blowing off the crests, and a dust devil wandering.
    val wind = 260f + fbm(t * 0.2f, 995) * 200f
    for (i in 0 until (160 * k).toInt()) {
        val x = frac(rnd(i, 996) + t * wind / w * (0.5f + rnd(i, 997))) * w
        val y = h * (0.78f + rnd(i, 998) * 0.2f) + sin(t * 3 + i) * 4f
        drawLine(Color(0x66F0D2A0), Offset(x, y), Offset(x - 8f - rnd(i, 999) * 10f, y + 1f), 1f)
    }
    val dc = Offset(w * (0.2f + fbm(t * 0.03f, 1001) * 0.6f), h * 0.9f)
    for (i in 0 until 90) {
        val hgt = rnd(i, 1002)
        val a = t * 6f + i * 0.7f
        val r = 8f + hgt * 70f
        val p = dc + Offset(cos(a) * r, -hgt * h * 0.3f + sin(a) * r * 0.2f)
        drawCircle(Color(0xFFCFA87A).copy(alpha = 0.35f * (1 - hgt)), 2f + 3f * (1 - hgt), p)
    }
}

internal fun DrawScope.heroCity(t: Float, k: Float) {
    val w = size.width
    val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color(0x66101830), Color.Transparent, Color(0x55301810)), endY = h))
    // Searchlights sweeping the clouds.
    for (s in 0 until 3) {
        val base = Offset(w * (0.2f + s * 0.3f), h)
        val angle = -PI.toFloat() / 2 + sin(t * (0.25f + s * 0.07f) + s * 2f) * 0.5f
        val len = h * 1.3f
        val tip = base + Offset(cos(angle) * len, sin(angle) * len)
        val side = Offset(-sin(angle), cos(angle)) * 90f
        val beam = Path().apply { moveTo(base.x - 6f, base.y); lineTo(tip.x - side.x, tip.y - side.y); lineTo(tip.x + side.x, tip.y + side.y); lineTo(base.x + 6f, base.y); close() }
        drawPath(beam, Brush.linearGradient(listOf(Color(0x40FFF6D8), Color(0x08FFF6D8)), start = base, end = tip), blendMode = BlendMode.Plus)
    }
    // Skyline in two depths, windows lit and going on and off.
    for (depth in 0 until 2) {
        var x = -20f
        var i = depth * 500
        val shade = if (depth == 0) Color(0xFF1A1F2E) else Color(0xFF0B0D14)
        while (x < w) {
            val bw = 60f + rnd(i, 1010) * 110f
            val bh = h * (0.12f + rnd(i, 1011) * (0.2f + depth * 0.08f))
            val top = h - bh
            drawRect(shade, topLeft = Offset(x, top), size = Size(bw, bh))
            if (rnd(i, 1012) > 0.7f) drawLine(shade, Offset(x + bw / 2, top), Offset(x + bw / 2, top - 40f), 3f)
            for (wy in 0 until (bh / 22f).toInt()) for (wx in 0 until (bw / 18f).toInt()) {
                val id = i * 997 + wy * 31 + wx
                val on = rnd(id, 1013) > 0.55f && noise(t * 0.05f + id, 1014) > 0.3f
                if (on) drawRect(Color(0xFFFFD98A).copy(alpha = 0.5f + 0.3f * depth), topLeft = Offset(x + 5f + wx * 18f, top + 8f + wy * 22f), size = Size(7f, 10f))
            }
            x += bw + 4f
            i++
        }
    }
    // Something fast and bright crossing the sky, now and then.
    val c = floor(t / 9f).toInt()
    val u = (t - c * 9f) / 1.4f
    if (u in 0f..1f) {
        val from = Offset(-100f, h * (0.15f + rnd(c, 1015) * 0.2f))
        val head = from + Offset((w + 200f) * u, -h * 0.08f * u)
        for (s in 0 until 24) glow(head - Offset(s * 14f, -s * 1.2f), 16f - s * 0.5f, Color(0xFF9FD0FF), 0.6f * (1 - s / 24f))
        glow(head, 40f, Color.White, 0.9f)
    }
}

internal fun DrawScope.visitors(t: Float, k: Float) {
    val w = size.width
    val h = size.height
    var y = 0f
    while (y < h) { drawLine(Color(0x08FFFFFF), Offset(0f, y), Offset(w, y), 1f); y += 3f }
    // The craft: a metallic disc with running lights, hovering on noise, a beam sweeping the ground.
    val c = Offset(w * (0.15f + fbm(t * 0.05f, 1020) * 0.7f), h * (0.16f + fbm(t * 0.07f, 1021) * 0.12f))
    val beamOn = noise(t * 0.25f, 1022) > 0.45f
    if (beamOn) {
        val beam = Path().apply { moveTo(c.x - 30f, c.y + 10f); lineTo(c.x + 30f, c.y + 10f); lineTo(c.x + 170f, h); lineTo(c.x - 170f, h); close() }
        drawPath(beam, Brush.verticalGradient(listOf(Color(0x5562FFB0), Color(0x1462FFB0)), startY = c.y, endY = h), blendMode = BlendMode.Plus)
        for (i in 0 until 30) {
            val p = frac(rnd(i, 1023) - t * 0.25f)
            drawCircle(Color(0xFFB8FFD8).copy(alpha = 0.5f * (1 - p)), 1.5f, Offset(c.x + (rnd(i, 1024) - 0.5f) * 300f * p, c.y + (h - c.y) * p), blendMode = BlendMode.Plus)
        }
    }
    withTransform({ scale(1.7f, 1.7f, c) }) { craft(c, t) }
    // Now and then the flash that makes everyone forget.
    val f = frac(t / 23f)
    if (f < 0.05f) drawRect(Color.White.copy(alpha = 0.22f * (1 - f / 0.05f)), blendMode = BlendMode.Plus)
}

private fun DrawScope.craft(c: Offset, t: Float) {
    glow(c, 160f, Color(0xFF62FFB0), 0.25f)
    drawOval(Brush.verticalGradient(listOf(Color(0xFFB9C2CC), Color(0xFF4A525C), Color(0xFF1C2026)), startY = c.y - 14f, endY = c.y + 14f), topLeft = c - Offset(80f, 14f), size = Size(160f, 28f))
    drawOval(Brush.radialGradient(listOf(Color(0xCCE6FFF4), Color(0x8862FFB0), Color(0x4420604A)), center = c - Offset(8f, 22f), radius = 40f), topLeft = c - Offset(34f, 34f), size = Size(68f, 40f))
    for (l in 0 until 8) {
        val a = t * 3f + l * PI.toFloat() / 4
        val x = c.x + cos(a) * 70f
        if (sin(a) > -0.2f) glow(Offset(x, c.y + 4f), 10f, if (l % 2 == 0) Color(0xFFFF5252) else Color(0xFFFFF59D), 0.9f)
    }
}

internal fun DrawScope.arcade(t: Float, k: Float) {
    val w = size.width
    val h = size.height
    val horizon = h * 0.7f
    // A striped sun sinking into haze.
    val sc = Offset(w / 2, horizon - 30f)
    glow(sc, 420f, Color(0xFFFF4FA3), 0.3f)
    val sun = Path().apply { addOval(androidx.compose.ui.geometry.Rect(sc - Offset(130f, 130f), Size(260f, 260f))) }
    drawPath(sun, Brush.verticalGradient(listOf(Color(0xFFFFE27A), Color(0xFFFF8A3D), Color(0xFFFF3DA1)), startY = sc.y - 130f, endY = sc.y + 130f))
    for (b in 0 until 6) drawRect(Color(0xFF0A0614), topLeft = Offset(sc.x - 140f, sc.y + 10f + b * 22f - (t * 6f) % 22f), size = Size(280f, 4f + b * 1.6f))
    drawRect(Color(0xFF0A0614), topLeft = Offset(0f, horizon), size = Size(w, h - horizon))
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0x88FF3DA1), Color.Transparent), startY = horizon - 40f, endY = horizon + 30f))
    val grid = Color(0xFFB44DFF)
    for (i in -14..14) neonLine(Offset(w / 2 + i * 26f, horizon), Offset(w / 2 + i * w / 9f, h), grid, 0.8f, 1.5f)
    for (j in 0 until 10) {
        val p = frac(j / 10f + t * 0.12f)
        val y = horizon + (h - horizon) * p * p
        neonLine(Offset(0f, y), Offset(w, y), grid, 0.25f + p * 0.75f, 1.5f)
    }
}

internal fun DrawScope.festivusPole(t: Float) {
    val w = size.width
    val h = size.height
    val x = w - 120f
    val top = h * 0.16f
    // Brushed aluminium: a cool gradient across, a hard specular line, no decoration at all.
    drawRect(Brush.horizontalGradient(listOf(Color(0xFF5E6670), Color(0xFFD9DEE4), Color(0xFFFFFFFF), Color(0xFFAAB2BB), Color(0xFF4A515A)), startX = x - 12f, endX = x + 12f), topLeft = Offset(x - 12f, top), size = Size(24f, h - top))
    drawLine(Color.White.copy(alpha = 0.6f + 0.3f * sin(t)), Offset(x - 3f, top), Offset(x - 3f, h), 1.5f)
    drawOval(Brush.radialGradient(listOf(Color(0xFFE6EAEE), Color(0xFF6A727C)), center = Offset(x, top), radius = 16f), topLeft = Offset(x - 14f, top - 6f), size = Size(28f, 12f))
    drawOval(Color(0xFF3A4048), topLeft = Offset(x - 60f, h - 18f), size = Size(120f, 22f))
    glow(Offset(x, h * 0.5f), h * 0.4f, Color(0xFFDDE6F0), 0.08f)
}

// ---- Labor Day ----

/**
 * A flag of cloth on a pole: a travelling wave pinned at the hoist, cut into strips each shaded by
 * the wave's slope (folds catching and losing the light), the gold emblem riding the folds.
 */
private fun DrawScope.clothFlag(hoist: Offset, fw: Float, fh: Float, t: Float, seed: Int, emblem: Boolean) {
    val strips = 36
    val amp = fh * 0.09f
    fun disp(x: Float) = sin(x / fw * 7f - t * 4.2f - seed) * amp * (x / fw) + sin(x / fw * 13f - t * 6.3f + seed) * amp * 0.3f * (x / fw)
    fun slope(x: Float) = cos(x / fw * 7f - t * 4.2f - seed)
    val red = Color(0xFFC8102E)
    for (i in 0 until strips) {
        val x0 = i * fw / strips
        val x1 = (i + 1) * fw / strips + 0.8f
        // Strips nearer the fly end come in a little: the cloth's slack.
        val sag0 = x0 / fw * fh * 0.04f
        val sag1 = x1 / fw * fh * 0.04f
        val p = Path().apply {
            moveTo(hoist.x + x0, hoist.y + disp(x0) + sag0)
            lineTo(hoist.x + x1, hoist.y + disp(x1) + sag1)
            lineTo(hoist.x + x1, hoist.y + fh + disp(x1) - sag1)
            lineTo(hoist.x + x0, hoist.y + fh + disp(x0) - sag0)
            close()
        }
        val light = 0.72f + 0.28f * slope((x0 + x1) / 2)
        drawPath(p, lerp(Color(0xFF3A0008), lerp(red, Color(0xFFFF4D5E), 0.25f), light))
    }
    if (emblem) {
        val ex = fw * 0.24f
        val shade = 0.75f + 0.25f * slope(ex)
        val gold = lerp(Color(0xFF7A5A00), Color(0xFFFFD54A), shade)
        val c = Offset(hoist.x + ex, hoist.y + fh * 0.42f + disp(ex))
        withTransform({ scale(0.9f + 0.1f * slope(ex), 1f, c) }) {
            sickleAndHammer(c, fh * 0.17f, gold)
            fivePoint(c + Offset(0f, -fh * 0.3f), fh * 0.07f, gold)
        }
    }
    // The pole, lit down one side.
    drawRect(Brush.horizontalGradient(listOf(Color(0xFF3A2A1A), Color(0xFFB08A5A), Color(0xFF2A1C10)), startX = hoist.x - 6f, endX = hoist.x + 2f), topLeft = Offset(hoist.x - 6f, hoist.y - 10f), size = Size(8f, size.height - hoist.y + 10f))
    glow(Offset(hoist.x - 2f, hoist.y - 12f), 10f, Color(0xFFFFD54A), 0.8f, additive = false)
}

private fun DrawScope.fivePoint(c: Offset, r: Float, color: Color) {
    val p = Path()
    for (i in 0 until 10) {
        val a = PI.toFloat() * i / 5 - PI.toFloat() / 2
        val rr = if (i % 2 == 0) r else r * 0.4f
        if (i == 0) p.moveTo(c.x + cos(a) * rr, c.y + sin(a) * rr) else p.lineTo(c.x + cos(a) * rr, c.y + sin(a) * rr)
    }
    p.close()
    drawPath(p, color)
}

private fun DrawScope.sickleAndHammer(c: Offset, s: Float, color: Color) {
    // The sickle: a crescent blade (two arcs) with a short handle.
    val blade = Path().apply {
        addArc(androidx.compose.ui.geometry.Rect(c - Offset(s, s), Size(s * 2, s * 2)), 160f, 250f)
        arcTo(androidx.compose.ui.geometry.Rect(c - Offset(s * 0.78f, s * 0.86f), Size(s * 1.56f, s * 1.62f)), 50f, -250f, false)
        close()
    }
    drawPath(blade, color)
    drawLine(color, c + Offset(-s * 0.62f, s * 0.7f), c + Offset(-s * 1.0f, s * 1.08f), s * 0.2f, StrokeCap.Round)
    withTransform({ rotate(-45f, c) }) {
        drawLine(color, Offset(c.x, c.y - s * 0.45f), Offset(c.x, c.y + s * 1.15f), s * 0.17f, StrokeCap.Round)
        drawRoundRect(color, topLeft = Offset(c.x - s * 0.42f, c.y - s * 0.75f), size = Size(s * 0.84f, s * 0.32f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.05f))
    }
}

internal fun DrawScope.laborDay(t: Float, k: Float) {
    val w = size.width
    val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color(0x40600010), Color.Transparent, Color(0x55400008)), endY = h))
    // The great banner over the square, and flags carried above the crowd.
    clothFlag(Offset(w * 0.72f, h * 0.06f), w * 0.22f, h * 0.24f, t, 1, emblem = true)
    for (i in 0 until 7) {
        val x = w * (0.04f + i * 0.14f) + (rnd(i, 1100) - 0.5f) * 40f
        val march = (t * 18f) % (w * 0.14f)
        clothFlag(Offset(x + march - w * 0.07f, h * (0.66f + rnd(i, 1101) * 0.08f)), w * 0.075f, h * 0.075f, t * 1.3f + i, i + 3, emblem = i % 2 == 0)
    }
    // The marchers: shoulders and heads in the haze, bobbing in step.
    for (i in 0 until 60) {
        val x = frac(rnd(i, 1102) + t * 0.01f) * (w + 80f) - 40f
        val depth = rnd(i, 1103)
        val y = h - 20f - depth * 40f + abs(sin(t * 4f + i)) * 4f
        val shade = lerp(Color(0xFF0A0607), Color(0xFF3A2A2E), depth)
        drawOval(shade, topLeft = Offset(x - 22f, y), size = Size(44f, 60f))
        drawCircle(shade, 11f, Offset(x, y - 4f))
    }
    fogBands(t, 0.8f, Color(0xFF8A6A70), 1104)
}
