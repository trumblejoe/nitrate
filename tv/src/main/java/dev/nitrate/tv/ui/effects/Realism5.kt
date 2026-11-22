package dev.nitrate.tv.ui.effects

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/*
 * Mood scenes for franchise hubs (and any page): Lightning run, Night watch, Cosmic rift, Arena at
 * dusk, High kingdom, Spellbound. Original drawings in the light-first style of Realism.kt, with more
 * care for depth: every scene is built from far to near with haze growing with distance, light from
 * one place in the scene rimming what faces it, and soft glows near the lens. No emblems, characters
 * or anything taken from a film: moods only. Sizes follow the screen's height (u = h / 1080).
 */

private fun frac(x: Float) = x - floor(x)
private fun DrawScope.u() = size.height / 1080f
private fun smoothstep(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
private fun mix(a: Offset, b: Offset, t: Float) = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

// ---- Shared parts ----

/** A sky laid over the page: [colors] top to bottom, strongest at the top, gone by [to] (share of height). */
private fun DrawScope.skyWash(colors: List<Color>, to: Float) {
    drawRect(Brush.verticalGradient(colors + Color.Transparent, endY = size.height * to), size = Size(size.width, size.height * to))
}

/**
 * A row of buildings standing on [baseY]: setbacks, spires, masts and rooftop boxes, a rim of [rim]
 * light along their tops, and lit windows (a few flickering). [haze] (0 near, 1 far) fades them
 * towards [hazeColor]. Deterministic per [salt]; [tallAtEdges] frames the middle.
 */
private fun DrawScope.skyline(
    baseY: Float, minH: Float, maxH: Float, salt: Int, haze: Float, hazeColor: Color, rim: Color, windows: Color,
    t: Float, tallAtEdges: Boolean = false, windowShare: Float = 0.35f, body: Color = Color(0xFF07090E),
) {
    val w = size.width
    val s = u()
    val fill = lerp(body, hazeColor, haze)
    var x = -40f * s
    var i = 0
    while (x < w + 40f * s) {
        val bw = (60f + rnd(i, salt) * 120f) * s * (1f - haze * 0.35f)
        val edge = if (tallAtEdges) (abs((x + bw / 2) / w - 0.5f) * 2f).pow(1.6f) else 0.5f
        val bh = minH + (maxH - minH) * (0.25f + 0.75f * rnd(i, salt + 1)) * (if (tallAtEdges) 0.35f + 0.9f * edge else 1f)
        val top = baseY - bh
        val shape = Path().apply {
            moveTo(x, baseY)
            lineTo(x, top + bh * 0.12f)
            // A setback now and then.
            if (rnd(i, salt + 2) < 0.45f) { lineTo(x + bw * 0.12f, top + bh * 0.12f); lineTo(x + bw * 0.12f, top) ; lineTo(x + bw * 0.88f, top); lineTo(x + bw * 0.88f, top + bh * 0.12f) }
            else lineTo(x, top).also { lineTo(x + bw, top) }
            // A spire.
            if (rnd(i, salt + 3) < 0.18f) { lineTo(x + bw * 0.5f + 6f * s, top); lineTo(x + bw * 0.5f, top - bh * 0.22f); lineTo(x + bw * 0.5f - 6f * s, top) }
            lineTo(x + bw, top + bh * 0.12f)
            lineTo(x + bw, baseY)
            close()
        }
        drawPath(shape, Brush.verticalGradient(listOf(lerp(fill, rim, 0.18f * (1 - haze)), fill, fill), startY = top, endY = baseY))
        // Rim light along the top edge.
        drawLine(rim.copy(alpha = 0.55f * (1 - haze * 0.6f)), Offset(x, top), Offset(x + bw, top), 1.6f * s, blendMode = BlendMode.Plus)
        // A mast with a slow red light.
        if (rnd(i, salt + 4) < 0.22f) {
            val mx = x + bw * (0.3f + rnd(i, salt + 5) * 0.4f)
            val mt = top - (20f + rnd(i, salt + 6) * 50f) * s
            drawLine(fill, Offset(mx, top), Offset(mx, mt), 2f * s)
            val blink = (sin(t * 2.2f + i) * 0.5f + 0.5f).pow(6)
            glow(Offset(mx, mt), 14f * s, Color(0xFFFF3B2F), 0.35f + 0.65f * blink)
        }
        // Windows: a grid, some lit, a few flickering, warmer near, cooler far.
        val cols = (bw / (9f * s)).toInt().coerceAtLeast(2)
        val rows = (bh * 0.85f / (13f * s)).toInt()
        val ww = 3.2f * s * (1 - haze * 0.4f)
        val wh = 5f * s * (1 - haze * 0.4f)
        val step = max(1, (cols * rows) / 160)
        var n = 0
        while (n < cols * rows) {
            val c = n % cols
            val r = n / cols
            val seed = i * 977 + n
            if (rnd(seed, salt + 7) < windowShare) {
                val lit = if (rnd(seed, salt + 8) < 0.04f) (0.4f + 0.6f * (sin(t * 7f + seed) * 0.5f + 0.5f)) else 1f
                val tone = lerp(windows, Color(0xFFBFD7FF), rnd(seed, salt + 9) * 0.35f)
                drawRect(tone.copy(alpha = (0.75f - haze * 0.45f) * lit), Offset(x + 6f * s + c * (bw - 12f * s) / cols, top + bh * 0.14f + r * 13f * s), Size(ww, wh))
            }
            n += step
        }
        x += bw + (2f + rnd(i, salt + 10) * 10f) * s
        i++
    }
    // Haze pooling at their feet (only the far rows: near ones stand clear).
    if (haze > 0.3f) drawRect(Brush.verticalGradient(listOf(Color.Transparent, hazeColor.copy(alpha = 0.45f * haze)), startY = baseY - maxH * 0.35f, endY = baseY), topLeft = Offset(0f, baseY - maxH * 0.35f), size = Size(w, maxH * 0.35f))
}

/** A light beam from [from] at [angle] (radians, 0 = up): bright at its source, soft at the edges, dying away. */
private fun DrawScope.beam(from: Offset, angle: Float, length: Float, spread: Float, color: Color, alpha: Float) {
    val dir = Offset(sin(angle), -cos(angle))
    val side = Offset(dir.y, -dir.x)
    val end = from + dir * length
    for ((width, a) in listOf(1f to 0.35f, 0.6f to 0.45f, 0.25f to 0.6f)) {
        val half = length * spread * width
        val p = Path().apply {
            moveTo(from.x + side.x * 3f, from.y + side.y * 3f)
            lineTo(end.x + side.x * half, end.y + side.y * half)
            lineTo(end.x - side.x * half, end.y - side.y * half)
            lineTo(from.x - side.x * 3f, from.y - side.y * 3f)
            close()
        }
        drawPath(p, Brush.linearGradient(listOf(color.copy(alpha = alpha * a), color.copy(alpha = alpha * a * 0.25f), Color.Transparent), start = from, end = end), blendMode = BlendMode.Plus)
    }
    glow(from, length * 0.05f, color, alpha * 0.8f)
}

/** Embers or sparks drifting up on a breeze, flickering, with short trails. */
private fun DrawScope.embers(t: Float, k: Float, n: Int, salt: Int, colors: List<Color>, rise: Float = 0.05f, fromY: Float = 1.02f, size: Float = 1f) {
    val w = this.size.width
    val h = this.size.height
    val s = u()
    for (i in 0 until (n * k).toInt().coerceAtLeast(4)) {
        val depth = 0.35f + rnd(i, salt) * 0.65f
        val life = frac(rnd(i, salt + 1) + t * rise * (0.6f + depth))
        val y = h * fromY - life * h * 1.1f
        val sway = (fbm(t * 0.4f + i * 1.7f, salt + 2) - 0.5f) * 160f * s
        val x = frac(rnd(i, salt + 3) + t * 0.01f * depth) * w + sway + life * 120f * s
        val flick = 0.55f + 0.45f * sin(t * (6f + rnd(i, salt + 4) * 9f) + i)
        val fade = smoothstep(0f, 0.08f, life) * (1f - smoothstep(0.65f, 1f, life))
        val c = colors[i % colors.size]
        val r = (1.4f + 2.6f * depth) * s * size
        val prev = Offset(x - 4f * s * depth, y + 8f * s * depth)
        drawLine(c.copy(alpha = 0.35f * fade * flick), prev, Offset(x, y), r * 0.8f, StrokeCap.Round, blendMode = BlendMode.Plus)
        glow(Offset(x, y), r * 6f, c, 0.45f * fade * flick * depth)
        drawCircle(lerp(c, Color.White, 0.6f).copy(alpha = fade * flick), r * 0.7f, Offset(x, y), blendMode = BlendMode.Plus)
    }
}

/** A ridge line of hills or mountains along [base], [height] tall, hazed by [haze] towards [hazeColor]. */
private fun DrawScope.ridge(base: Float, height: Float, scale: Float, salt: Int, color: Color, hazeColor: Color, haze: Float, jag: Float = 0f, rim: Color? = null) {
    val w = size.width
    val p = Path()
    val top = ArrayList<Offset>()
    var x = -10f
    while (x <= w + 10f) {
        val n = fbm(x / scale + salt * 0.37f, salt)
        val sharp = if (jag > 0f) abs(noise(x / (scale * 0.21f), salt + 5) - 0.5f) * 2f * jag else 0f
        top += Offset(x, base - height * (n * 1.25f - 0.15f + sharp))
        x += 8f
    }
    p.moveTo(-10f, size.height)
    top.forEach { p.lineTo(it.x, it.y) }
    p.lineTo(w + 10f, size.height)
    p.close()
    val c = lerp(color, hazeColor, haze)
    drawPath(p, Brush.verticalGradient(listOf(c, lerp(c, Color.Black, 0.25f)), startY = base - height, endY = size.height))
    if (rim != null) for (j in 1 until top.size) {
        val a = top[j - 1]
        val b = top[j]
        // Slopes facing the light catch it.
        if (b.y < a.y) drawLine(rim.copy(alpha = 0.5f * (1 - haze)), a, b, 1.8f * u(), blendMode = BlendMode.Plus)
    }
}

// ---- Lightning run: a streak racing through a city at night ----

private const val RUN_PERIOD = 7.5f
private const val RUN_TIME = 1.1f

/** Where the runner is [a] seconds into a pass (0..RUN_TIME), on a gently weaving line across the street. */
private fun DrawScope.runnerAt(a: Float, pass: Long): Offset {
    val w = size.width
    val h = size.height
    val dir = if (pass % 2L == 0L) 1 else -1
    val p = (a / RUN_TIME).coerceIn(0f, 1f)
    val x = if (dir > 0) -0.15f * w + p * 1.3f * w else 1.15f * w - p * 1.3f * w
    val y = h * (0.86f + 0.025f * sin(p * 9f + pass)) - h * 0.03f * sin(p * PI.toFloat())
    return Offset(x, y)
}

internal fun DrawScope.lightningRun(t: Float, k: Float) {
    val w = size.width
    val h = size.height
    val s = u()
    val pass = floor(t / RUN_PERIOD).toLong()
    val a = t - pass * RUN_PERIOD
    // How lit the street is: the pass, then a glow dying away.
    val flare = if (a < RUN_TIME) 1f else exp(-(a - RUN_TIME) * 2.2f)
    back {
        skyWash(listOf(Color(0xDD04050B), Color(0x9907070F)), 0.62f)
        // A low cloud deck lit orange from below by the city.
        for (i in 0 until 26) {
            val x = frac(rnd(i, 5100) + t * 0.004f * (1 + i % 3)) * (w + 500f * s) - 250f * s
            val y = h * (0.06f + rnd(i, 5101) * 0.28f)
            val r = (160f + rnd(i, 5102) * 240f) * s
            glow(Offset(x, y), r, Color(0xFF0E0F18), 0.55f, additive = false)
            glow(Offset(x, y + r * 0.35f), r * 0.7f, Color(0xFFFF8A3C), 0.035f + 0.05f * flare)
        }
        glow(Offset(w * 0.5f, h * 0.8f), h * 0.9f, Color(0xFFFF9A4A), 0.10f)
        skyline(h * 0.80f, 120f * s, 320f * s, 5110, 0.72f, Color(0xFF2A2433), Color(0xFFFFA25A), Color(0xFFFFC78A), t)
    }
    mid {
        skyline(h * 0.90f, 160f * s, 560f * s, 5120, 0.15f, Color(0xFF1A1420), Color(0xFFFFB066), Color(0xFFFFD39A), t, tallAtEdges = true, windowShare = 0.28f)
        // The street: wet asphalt catching the light.
        val street = h * 0.885f
        drawRect(Brush.verticalGradient(listOf(Color(0xF0100D12), Color(0xFF050407)), startY = street, endY = h), topLeft = Offset(0f, street), size = Size(w, h - street))
        // Reflections of windows and signs, smeared down the wet street.
        for (i in 0 until 34) {
            val x = rnd(i, 5130) * w
            val c = if (i % 5 == 0) Color(0xFF4FD2FF) else Color(0xFFFFB866)
            val len = (40f + rnd(i, 5132) * 80f) * s
            val wob = sin(t * 1.5f + i) * 2f * s
            drawRect(Brush.verticalGradient(listOf(c.copy(alpha = 0.14f + 0.12f * rnd(i, 5131)), Color.Transparent), startY = street, endY = street + len),
                Offset(x + wob, street + 2f * s), Size((3f + rnd(i, 5133) * 6f) * s, len), blendMode = BlendMode.Plus)
        }
        // Lane markings running away into the dark.
        for (i in 0 until 9) drawLine(Color(0x1EFFFFFF), Offset(i * w / 8f, h * 0.955f), Offset(i * w / 8f + 90f * s, h * 0.955f), 3f * s)
        // The trail: jagged light along where the runner went, newest brightest, crackling as it dies.
        val head = runnerAt(min(a, RUN_TIME), pass)
        val shown = a < RUN_TIME + 1.6f
        if (shown) {
            val steps = 34
            val tail = 0.55f
            var prev: Offset? = null
            for (j in 0..steps) {
                val at = min(a, RUN_TIME) - tail * j / steps
                if (at < 0f) break
                val p = runnerAt(at, pass)
                val age = (a - at)
                val bright = exp(-age * 2.8f)
                if (prev != null && bright > 0.02f) {
                    val crackle = floor(t * 24f).toInt()
                    val segs = boltSegments(prev, p, (pass * 131 + j * 17 + crackle).toInt(), 3)
                    for ((x0, x1, st) in segs) {
                        drawLine(Color(0xFFFF7A1A).copy(alpha = 0.16f * bright * st), x0, x1, 22f * s * st, StrokeCap.Round, blendMode = BlendMode.Plus)
                        drawLine(Color(0xFFFFC23A).copy(alpha = 0.5f * bright * st), x0, x1, 6f * s * st, StrokeCap.Round, blendMode = BlendMode.Plus)
                        drawLine(Color(0xFFFFF6D8).copy(alpha = 0.9f * bright * st), x0, x1, 1.8f * s, StrokeCap.Round, blendMode = BlendMode.Plus)
                    }
                    // Arcs leaping off the trail now and then.
                    if (rnd(j + crackle * 41, 5140) < 0.12f * bright) {
                        val off = Offset((rnd(j + crackle, 5141) - 0.5f) * 140f * s, -(30f + rnd(j + crackle, 5142) * 110f) * s)
                        lightning(boltSegments(p, p + off, j * 7 + crackle, 4), 0.55f * bright, s * 0.6f)
                    }
                }
                prev = p
            }
            if (a < RUN_TIME) {
                // The runner itself: too fast to see, a white-hot knot of light, lighting the street.
                glow(head, 220f * s, Color(0xFFFFB347), 0.35f)
                glow(head, 70f * s, Color(0xFFFFE7A8), 0.85f)
                glow(head, 22f * s, Color.White, 1f)
                glow(Offset(head.x, street + 30f * s), 300f * s, Color(0xFFFFA040), 0.22f)
                // Mirrored in the wet road, stretched and broken by ripples.
                for (r in 0 until 6) drawLine(Color(0xFFFFD080).copy(alpha = 0.28f - r * 0.04f), Offset(head.x - (60f - r * 6f) * s, street + (14f + r * 11f) * s), Offset(head.x + (60f - r * 6f) * s, street + (14f + r * 11f) * s), 3f * s, StrokeCap.Round, blendMode = BlendMode.Plus)
            }
        }
        // The whole street's light pulses with the pass.
        if (flare > 0.02f) glow(Offset(head.x.coerceIn(0f, w), h * 0.86f), w * 0.5f, Color(0xFFFF9030), 0.07f * flare)
        // Papers and dust lifted in the wake, settling.
        if (a < RUN_TIME + 3f) for (i in 0 until 16) {
            val born = rnd(i, 5150) * RUN_TIME
            val life = a - born
            if (life < 0f) continue
            val p0 = runnerAt(born, pass)
            val x = p0.x + (rnd(i, 5151) - 0.5f) * 90f * s * life
            val y = p0.y - 120f * s * life * exp(-life * 0.9f) + 30f * s * life
            withTransform({ rotate(life * 220f * (rnd(i, 5152) - 0.5f), Offset(x, y)) }) {
                drawRect(Color(0xFFD9D2C2).copy(alpha = 0.55f * exp(-life * 0.7f)), Offset(x - 7f * s, y - 5f * s), Size(14f * s, 10f * s))
            }
        }
    }
    front {
        // Sparks thrown from the run, falling under their own weight.
        if (a < RUN_TIME + 1.4f) for (i in 0 until (70 * k).toInt()) {
            val born = rnd(i, 5160) * RUN_TIME
            val life = a - born
            if (life < 0f || life > 1.3f) continue
            val p0 = runnerAt(born, pass)
            val v = Offset((rnd(i, 5161) - 0.5f) * 900f * s, -(200f + rnd(i, 5162) * 700f) * s)
            val p = p0 + v * life + Offset(0f, 1400f * s * life * life)
            val q = p0 + v * (life - 0.03f) + Offset(0f, 1400f * s * (life - 0.03f).coerceAtLeast(0f).pow(2))
            val fade = 1f - life / 1.3f
            drawLine(Color(0xFFFFD27A).copy(alpha = fade), q, p, 2.2f * s, StrokeCap.Round, blendMode = BlendMode.Plus)
        }
    }
}

// ---- Night watch: rooftops in the rain, searchlights over a humming city ----

internal fun DrawScope.nightWatch(t: Float, k: Float) {
    val w = size.width
    val h = size.height
    val s = u()
    back {
        skyWash(listOf(Color(0xE0020807), Color(0xA0061411)), 0.7f)
        // Clouds lit green-white from the city underneath.
        for (i in 0 until 30) {
            val x = frac(rnd(i, 5200) + t * 0.006f * (1 + i % 3)) * (w + 600f * s) - 300f * s
            val y = h * (0.04f + rnd(i, 5201) * 0.3f)
            val r = (140f + rnd(i, 5202) * 260f) * s
            glow(Offset(x, y), r, Color(0xFF081210), 0.6f, additive = false)
            glow(Offset(x, y + r * 0.3f), r * 0.75f, Color(0xFF7FE0B0), 0.04f)
        }
        // Searchlights sweeping from behind the city.
        for (b in 0 until 3) {
            val ang = sin(t * (0.13f + b * 0.04f) + b * 2.1f) * 0.55f + (b - 1) * 0.25f
            beam(Offset(w * (0.22f + b * 0.3f), h * 0.74f), ang, h * 0.95f, 0.09f, Color(0xFFCFF5E6), 0.16f)
        }
        skyline(h * 0.74f, 140f * s, 380f * s, 5210, 0.7f, Color(0xFF1C3A33), Color(0xFF9CF0CB), Color(0xFFE6F7C9), t, windowShare = 0.42f)
        skyline(h * 0.80f, 120f * s, 300f * s, 5211, 0.42f, Color(0xFF12241F), Color(0xFF8CE6BE), Color(0xFFFFE2A0), t, windowShare = 0.3f)
        // A harbour bridge's lights strung across the distance.
        val by = h * 0.705f
        for (i in 0 until 40) {
            val x = w * 0.05f + i * w * 0.022f
            val sag = sin(i / 39f * PI.toFloat()) * 36f * s
            glow(Offset(x, by + sag), 7f * s, Color(0xFFFFE6A8), 0.6f)
        }
    }
    mid {
        // The near rooftop: a parapet across the bottom, a water tower, a mast and a lit stair hut.
        val roof = h * 0.86f
        drawRect(Brush.verticalGradient(listOf(Color(0xFF0A110F), Color(0xFF030605)), startY = roof, endY = h), topLeft = Offset(0f, roof), size = Size(w, h - roof))
        drawLine(Color(0x559CF0CB), Offset(0f, roof), Offset(w, roof), 2f * s)
        // A parapet with its coping stones, and gravel catching the glow.
        drawRect(Color(0xFF050908), Offset(0f, roof - 18f * s), Size(w, 18f * s))
        var cx = 0f
        while (cx < w) { drawLine(Color(0xFF0E1A16), Offset(cx, roof - 18f * s), Offset(cx, roof), 1.5f * s); cx += 64f * s }
        drawLine(Color(0x449CF0CB), Offset(0f, roof - 18f * s), Offset(w, roof - 18f * s), 1.5f * s)
        for (g in 0 until 220) drawCircle(Color(0xFF9CF0CB).copy(alpha = 0.05f + 0.06f * rnd(g, 5225)), (0.8f + rnd(g, 5226)) * s, Offset(rnd(g, 5227) * w, roof + rnd(g, 5228) * (h - roof)))
        // Vents with steam curling off them.
        for (v in 0 until 2) {
            val vx = w * (0.38f + v * 0.18f)
            drawRect(Color(0xFF0A1311), Offset(vx - 22f * s, roof - 46f * s), Size(44f * s, 46f * s))
            drawRect(Color(0xFF13211C), Offset(vx - 28f * s, roof - 52f * s), Size(56f * s, 8f * s))
            for (q in 0 until 7) {
                val life = frac(q / 7f + t * 0.08f + v * 0.5f)
                glow(Offset(vx + sin(t * 0.8f + q) * 20f * s + life * 60f * s, roof - 60f * s - life * 200f * s), (24f + life * 70f) * s, Color(0xFFBFD8CF), 0.12f * (1 - life), additive = false)
            }
        }
        // Puddles mirroring the sky's glow.
        for (i in 0 until 6) {
            val c = Offset(rnd(i, 5220) * w, roof + (20f + rnd(i, 5221) * 100f) * s)
            drawOval(Color(0x2296E8C4), topLeft = c - Offset(120f * s, 8f * s), size = Size(240f * s, 16f * s), blendMode = BlendMode.Plus)
        }
        // Water tower on the left: legs, a barrel of boards, a cone roof.
        val tx = w * 0.12f
        val legsTop = roof - 150f * s
        for (l in listOf(-60f, -20f, 20f, 60f)) drawLine(Color(0xFF050807), Offset(tx + l * s, roof), Offset(tx + l * 0.8f * s, legsTop), 6f * s)
        drawLine(Color(0xFF050807), Offset(tx - 60f * s, roof - 70f * s), Offset(tx + 60f * s, roof - 110f * s), 3f * s)
        drawLine(Color(0xFF050807), Offset(tx + 60f * s, roof - 70f * s), Offset(tx - 60f * s, roof - 110f * s), 3f * s)
        val barrel = Rect(tx - 80f * s, legsTop - 170f * s, tx + 80f * s, legsTop)
        drawRect(Brush.horizontalGradient(listOf(Color(0xFF0B1512), Color(0xFF152620), Color(0xFF060A09)), startX = barrel.left, endX = barrel.right), barrel.topLeft, barrel.size)
        for (b in 1 until 5) drawLine(Color(0xFF030504), Offset(barrel.left, barrel.top + b * barrel.height / 5), Offset(barrel.right, barrel.top + b * barrel.height / 5), 2f * s)
        val cone = Path().apply { moveTo(barrel.left - 10f * s, barrel.top); lineTo(tx, barrel.top - 90f * s); lineTo(barrel.right + 10f * s, barrel.top); close() }
        drawPath(cone, Brush.horizontalGradient(listOf(Color(0xFF0B1512), Color(0xFF1C3029), Color(0xFF050807)), startX = barrel.left, endX = barrel.right))
        drawLine(Color(0x889CF0CB), Offset(tx, barrel.top - 90f * s), Offset(barrel.right + 10f * s, barrel.top), 1.5f * s, blendMode = BlendMode.Plus)
        // A mast on the right with its slow warning light.
        val mx = w * 0.9f
        drawLine(Color(0xFF040706), Offset(mx, roof), Offset(mx, roof - 420f * s), 5f * s)
        for (r in 1..6) drawLine(Color(0xFF040706), Offset(mx - 16f * s, roof - r * 62f * s), Offset(mx + 16f * s, roof - r * 62f * s - 30f * s), 2f * s)
        val blink = (sin(t * 1.6f) * 0.5f + 0.5f).pow(5)
        glow(Offset(mx, roof - 420f * s), 40f * s, Color(0xFFFF2E2E), 0.25f + 0.75f * blink)
        // A stair hut with a lit door.
        val hut = Rect(w * 0.66f, roof - 120f * s, w * 0.66f + 150f * s, roof)
        drawRect(Color(0xFF070C0A), hut.topLeft, hut.size)
        val door = Rect(hut.left + 50f * s, hut.top + 30f * s, hut.left + 100f * s, roof)
        drawRect(Brush.verticalGradient(listOf(Color(0xFFFFD58A), Color(0xFFFFA64A)), startY = door.top, endY = door.bottom), door.topLeft, door.size)
        glow(door.center, 140f * s, Color(0xFFFFB45A), 0.25f)
        // Light from the door spilling across the wet roof.
        drawPath(Path().apply { moveTo(door.left, roof); lineTo(door.right, roof); lineTo(door.right + 160f * s, h); lineTo(door.left - 160f * s, h); close() },
            Brush.verticalGradient(listOf(Color(0x55FFB45A), Color.Transparent), startY = roof, endY = h), blendMode = BlendMode.Plus)
        // An arrow now and then, a green-white streak across the sky from roof to roof.
        val period = 11f
        val n = floor(t / period).toLong()
        val p = (t - n * period) / 0.9f
        if (p in 0f..1.6f) {
            val from = Offset(w * 0.95f, h * (0.3f + rnd(n.toInt(), 5230) * 0.2f))
            val to = Offset(w * 0.05f, h * (0.35f + rnd(n.toInt(), 5231) * 0.25f))
            fun at(q: Float) = mix(from, to, q) - Offset(0f, sin(q * PI.toFloat()) * h * 0.08f)
            val q = p.coerceAtMost(1f)
            val fade = if (p > 1f) 1f - (p - 1f) / 0.6f else 1f
            for (j in 0 until 18) {
                val q0 = (q - j * 0.02f).coerceAtLeast(0f)
                val q1 = (q - (j + 1) * 0.02f).coerceAtLeast(0f)
                drawLine(Color(0xFFBFFFE2).copy(alpha = 0.5f * fade * (1 - j / 18f)), at(q0), at(q1), (3f - j * 0.12f) * s, StrokeCap.Round, blendMode = BlendMode.Plus)
            }
            if (p <= 1f) {
                val tip = at(q)
                val back = at((q - 0.015f).coerceAtLeast(0f))
                drawLine(Color(0xFFEFFFF6), back, tip, 2.4f * s, StrokeCap.Round)
                glow(tip, 30f * s, Color(0xFFBFFFE2), 0.6f)
            }
        }
    }
    front { rain(t, k * 0.7f, 5240, layered = true) }
}

// ---- Cosmic rift: a nebula torn open by a turning ring of sparks ----

internal fun DrawScope.cosmicRift(t: Float, k: Float) {
    val w = size.width
    val h = size.height
    val s = u()
    val c = Offset(w * 0.7f, h * 0.42f)
    val ringR = h * 0.24f * (0.92f + 0.08f * sin(t * 0.5f))
    back {
        skyWash(listOf(Color(0xE0020109), Color(0xB0050212)), 0.95f)
        // Nebula: soft clouds in three families of colour, wandering slowly, with dark dust lanes.
        val families = listOf(Color(0xFF7A2BD6), Color(0xFF1C5BFF), Color(0xFFE0407A), Color(0xFF1BB8C9))
        for (i in 0 until 70) {
            val fx = rnd(i, 5300)
            val fy = rnd(i, 5301)
            val drift = Offset((fbm(t * 0.01f + i, 5302) - 0.5f) * 80f * s, (fbm(t * 0.012f + i * 2, 5303) - 0.5f) * 60f * s)
            // Gathered along a sweeping arm through the ring.
            val arm = Offset(w * (fx), h * (0.25f + 0.45f * fy + 0.25f * sin(fx * 3.4f)))
            glow(arm + drift, (90f + rnd(i, 5304) * 260f) * s, families[i % families.size], 0.10f + 0.08f * rnd(i, 5305))
        }
        for (i in 0 until 14) glow(Offset(rnd(i, 5306) * w, h * (0.3f + rnd(i, 5307) * 0.5f)), (120f + rnd(i, 5308) * 200f) * s, Color(0xFF020108), 0.5f, additive = false)
        // Stars, a few big ones with soft crosses.
        for (i in 0 until 320) {
            val tw = 0.6f + 0.4f * sin(t * (0.7f + rnd(i, 5310) * 2f) + i)
            val p = Offset(rnd(i, 5311) * w, rnd(i, 5312) * h)
            drawCircle(Color.White.copy(alpha = (0.25f + 0.6f * rnd(i, 5313)) * tw), (0.6f + rnd(i, 5314) * 1.2f) * s, p)
            if (i % 40 == 0) {
                glow(p, 18f * s, Color(0xFFCFD8FF), 0.5f * tw)
                drawLine(Color.White.copy(alpha = 0.35f * tw), p - Offset(16f * s, 0f), p + Offset(16f * s, 0f), 1f * s, blendMode = BlendMode.Plus)
                drawLine(Color.White.copy(alpha = 0.35f * tw), p - Offset(0f, 16f * s), p + Offset(0f, 16f * s), 1f * s, blendMode = BlendMode.Plus)
            }
        }
    }
    mid {
        // Through the ring: another sky, brighter and stranger, with a ringed world.
        val inside = Path().apply { addOval(Rect(c - Offset(ringR, ringR), Size(ringR * 2, ringR * 2))) }
        clipPath(inside) {
            drawRect(Brush.radialGradient(listOf(Color(0xFF2A0E46), Color(0xFF0A0420)), center = c, radius = ringR), topLeft = c - Offset(ringR, ringR), size = Size(ringR * 2, ringR * 2))
            for (i in 0 until 20) glow(c + Offset((rnd(i, 5320) - 0.5f) * ringR * 1.6f, (rnd(i, 5321) - 0.5f) * ringR * 1.6f), (40f + rnd(i, 5322) * 120f) * s, if (i % 2 == 0) Color(0xFFFF7AD0) else Color(0xFF7AE2FF), 0.12f)
            val planet = c + Offset(-ringR * 0.25f, ringR * 0.2f)
            val pr = ringR * 0.33f
            val ringBox = Rect(planet - Offset(pr * 1.9f, pr * 0.32f), Size(pr * 3.8f, pr * 0.64f))
            withTransform({ rotate(-18f, planet) }) {
                drawArc(Color(0x66FFE0B8), 180f, 180f, false, ringBox.topLeft, ringBox.size, style = Stroke(4f * s))
            }
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFD6A0), Color(0xFFB8572E), Color(0xFF2A0A08)), center = planet - Offset(pr * 0.4f, pr * 0.4f), radius = pr * 1.6f), pr, planet)
            // Bands across the planet.
            clipPath(Path().apply { addOval(Rect(planet - Offset(pr, pr), Size(pr * 2, pr * 2))) }) {
                for (b in 0 until 6) drawRect(Color(0x22301010), Offset(planet.x - pr, planet.y - pr + b * pr * 0.36f + pr * 0.1f), Size(pr * 2, pr * 0.12f))
            }
            withTransform({ rotate(-18f, planet) }) {
                drawArc(Color(0xAAFFE6C4), 0f, 180f, false, ringBox.topLeft, ringBox.size, style = Stroke(4f * s))
            }
            // The ring's own fire lighting the edge of the opening.
            drawCircle(Brush.radialGradient(0.78f to Color.Transparent, 1f to Color(0x66FF8A2A), center = c, radius = ringR), ringR, c)
            for (i in 0 until 60) drawCircle(Color.White.copy(alpha = 0.6f * rnd(i, 5323)), 1.2f * s, c + Offset((rnd(i, 5324) - 0.5f) * ringR * 2, (rnd(i, 5325) - 0.5f) * ringR * 2))
        }
        // The ring: hundreds of sparks racing round it, each a short arc, thrown off at a tangent.
        glow(c, ringR * 1.5f, Color(0xFFFF8A2A), 0.10f)
        val n = (520 * k).toInt().coerceAtLeast(160)
        for (i in 0 until n) {
            val speed = 1.6f + rnd(i, 5330) * 1.2f
            val ang = rnd(i, 5331) * 2 * PI.toFloat() + t * speed
            val rr = ringR * (0.97f + (rnd(i, 5332) - 0.5f) * 0.08f + 0.015f * sin(t * 3f + i))
            val p = c + Offset(cos(ang), sin(ang)) * rr
            val q = c + Offset(cos(ang - 0.09f), sin(ang - 0.09f)) * rr
            val hot = rnd(i, 5333)
            val col = lerp(Color(0xFFFF6A1A), Color(0xFFFFE9A6), hot)
            drawLine(col.copy(alpha = 0.75f), q, p, (1.2f + hot * 1.8f) * s, StrokeCap.Round, blendMode = BlendMode.Plus)
        }
        // Sparks flung off, falling away.
        for (i in 0 until (90 * k).toInt()) {
            val life = frac(rnd(i, 5340) + t * 0.6f)
            val ang = rnd(i, 5341) * 2 * PI.toFloat() + (t - life / 0.6f) * 2.2f
            val start = c + Offset(cos(ang), sin(ang)) * ringR
            val tangent = Offset(-sin(ang), cos(ang))
            val p = start + tangent * (life * 240f * s) + Offset(0f, life * life * 320f * s)
            val q = start + tangent * ((life - 0.05f).coerceAtLeast(0f) * 240f * s) + Offset(0f, (life - 0.05f).coerceAtLeast(0f).pow(2) * 320f * s)
            drawLine(Color(0xFFFFB050).copy(alpha = 0.8f * (1 - life)), q, p, 1.6f * s, StrokeCap.Round, blendMode = BlendMode.Plus)
        }
        glow(c + Offset(ringR, 0f) * cos(t * 2.2f), 50f * s, Color(0xFFFFD27A), 0.25f)
        // Shards of something broken drifting past the ring, catching its light on their faces.
        for (i in 0 until 12) {
            val orbit = t * (0.02f + rnd(i, 5350) * 0.03f) + rnd(i, 5351) * 6.28f
            val dist = ringR * (1.5f + rnd(i, 5352) * 1.6f)
            val p = c + Offset(cos(orbit) * dist * 1.3f, sin(orbit) * dist * 0.55f)
            if (p.x < -100f || p.x > w + 100f) continue
            val sz = (18f + rnd(i, 5353) * 46f) * s
            val turn = t * (10f + rnd(i, 5354) * 30f) + i * 40f
            withTransform({ rotate(turn, p) }) {
                val pts = List(5) { j -> val a2 = j / 5f * 6.28f + rnd(i * 5 + j, 5355) * 0.6f; p + Offset(cos(a2), sin(a2) * 0.6f) * sz * (0.6f + rnd(i * 5 + j, 5356) * 0.6f) }
                val toRing = (c - p) / max((c - p).getDistance(), 1f)
                for (j in pts.indices) {
                    val face = Path().apply { moveTo(p.x, p.y); lineTo(pts[j].x, pts[j].y); lineTo(pts[(j + 1) % pts.size].x, pts[(j + 1) % pts.size].y); close() }
                    val mid2 = (pts[j] + pts[(j + 1) % pts.size]) / 2f - p
                    val lit = ((mid2.x * toRing.x + mid2.y * toRing.y) / sz).coerceIn(-1f, 1f) * 0.5f + 0.5f
                    drawPath(face, lerp(Color(0xFF0A0916), Color(0xFFFFB46A), lit.pow(2.2f) * 0.85f))
                }
                for (j in pts.indices) drawLine(Color(0x55FFE0B0), pts[j], pts[(j + 1) % pts.size], 1f * s, blendMode = BlendMode.Plus)
            }
        }
    }
    front {
        for (i in 0 until (14 * k).toInt()) {
            val p = Offset(frac(rnd(i, 5360) + t * 0.01f) * w, frac(rnd(i, 5361) - t * 0.006f) * h)
            glow(p, (10f + rnd(i, 5362) * 20f) * s, Color(0xFFFFD9A0), 0.25f)
        }
    }
}

// ---- Arena at dusk: forest and smoke under a burning sky, embers on the wind ----

private fun DrawScope.conifer(base: Offset, height: Float, color: Color, rim: Color?, seed: Int, sway: Float) {
    val s = u()
    // Ragged boughs: each side's outline wanders in and out on noise, drooping at the tips.
    val p = Path()
    val steps = 26
    fun half(q: Float, side: Int): Float {
        val taper = (1f - q).pow(1.15f)
        val bough = noise(q * 11f + seed * 3.1f + side * 17f, 5400) * 0.55f + 0.55f
        val notch = if ((q * 13f + seed) % 1f < 0.3f) 0.72f else 1f
        return height * 0.2f * taper * bough * notch
    }
    p.moveTo(base.x - 3f * s, base.y)
    for (j in 0..steps) {
        val q = j / steps.toFloat()
        p.lineTo(base.x - half(q, 0) + sway * q, base.y - height * q + height * 0.02f * (1 - q))
    }
    p.lineTo(base.x + sway, base.y - height * 1.03f)
    for (j in steps downTo 0) {
        val q = j / steps.toFloat()
        p.lineTo(base.x + half(q, 1) + sway * q, base.y - height * q + height * 0.02f * (1 - q))
    }
    p.lineTo(base.x + 3f * s, base.y)
    p.close()
    drawPath(p, color)
    if (rim != null) drawPath(p, rim, style = Stroke(1.4f * s), blendMode = BlendMode.Plus)
}

internal fun DrawScope.arenaDusk(t: Float, k: Float) {
    val w = size.width
    val h = size.height
    val s = u()
    val sun = Offset(w * 0.62f, h * 0.66f)
    // A far-off flash now and then: something going off beyond the trees.
    val flashN = floor(t / 13f).toLong()
    val flashA = t - flashN * 13f
    val flash = if (flashA < 2.5f) exp(-flashA * 3f) * (0.6f + 0.4f * sin(flashA * 40f)) else 0f
    val flashAt = Offset(w * (0.2f + rnd(flashN.toInt(), 5410) * 0.6f), h * 0.66f)
    back {
        drawRect(Brush.verticalGradient(listOf(Color(0xCC1A0610), Color(0xAA6A1414), Color(0x99D9521C), Color(0x66FFB050), Color.Transparent), endY = h * 0.78f), size = Size(w, h * 0.78f))
        glow(sun, h * 0.75f, Color(0xFFFF7A2A), 0.35f)
        glow(sun, h * 0.14f, Color(0xFFFFE0A0), 0.8f)
        // Smoke rising in columns, leaning on the wind, lit from below.
        for (col in 0 until 4) {
            val bx = w * (0.1f + col * 0.27f + rnd(col, 5420) * 0.08f)
            for (i in 0 until 16) {
                val life = frac(i / 16f + t * 0.012f)
                val y = h * 0.7f - life * h * 0.75f
                val x = bx + life * life * 260f * s + (fbm(t * 0.1f + i + col * 9, 5421) - 0.5f) * 90f * s
                val r = (60f + life * 220f) * s
                glow(Offset(x, y), r, Color(0xFF1A0B0A), 0.35f * (1 - life), additive = false)
                glow(Offset(x, y + r * 0.3f), r * 0.6f, Color(0xFFFF6A2A), 0.05f * (1 - life))
            }
        }
        if (flash > 0f) { glow(flashAt, h * 0.6f, Color(0xFFFFD08A), 0.4f * flash); glow(flashAt, h * 0.08f, Color.White, 0.8f * flash) }
        ridge(h * 0.74f, h * 0.1f, 520f * s, 5430, Color(0xFF1C0A12), Color(0xFF7A3036), 0.4f, rim = Color(0xFFFFA060))
    }
    mid {
        // Three bands of forest, the nearest darkest; the trees rimmed on the side facing the sun.
        for (band in 0 until 3) {
            val base = h * (0.8f + band * 0.075f)
            val haze = 0.6f - band * 0.28f
            val color = lerp(Color(0xFF07050A), Color(0xFF5A2430), haze)
            val count = 40 - band * 9
            for (i in 0 until count) {
                val x = (i + rnd(i, 5440 + band) * 0.8f) / count * (w + 200f * s) - 100f * s
                // Lower in the middle, so the page's titles keep the sky; taller towards the edges.
                val edge = 0.55f + 0.9f * (abs(x / w - 0.5f) * 2f).pow(1.5f)
                val ht = (70f + rnd(i, 5450 + band) * 110f) * s * (1f + band * 0.45f) * edge
                val sway = sin(t * 0.6f + i * 1.3f + band) * 6f * s * (band + 1)
                val rimC = if (x < sun.x) null else Color(0xFFFF8A40).copy(alpha = 0.35f * (1 - haze))
                conifer(Offset(x, base), ht, color, rimC, i * 3 + band, sway)
            }
            drawRect(color, topLeft = Offset(0f, base), size = Size(w, h - base))
            // Mist lying between the bands, lit by the low sun.
            for (m in 0 until 8) glow(Offset(frac(rnd(m, 5460 + band) + t * 0.006f * (band + 1)) * (w + 600f * s) - 300f * s, base - 10f * s), 220f * s, Color(0xFFFF9A5A), 0.06f * (1 - band * 0.3f))
        }
        // The flash lights the trees' edges for a moment.
        if (flash > 0f) drawRect(Brush.radialGradient(listOf(Color(0xFFFFC080).copy(alpha = 0.18f * flash), Color.Transparent), center = flashAt, radius = h * 0.7f), blendMode = BlendMode.Plus)
    }
    front {
        embers(t, k, 46, 5470, listOf(Color(0xFFFF7A1A), Color(0xFFFFB13A), Color(0xFFFF4A10)), rise = 0.045f)
        // Ash drifting down, slowly, grey.
        for (i in 0 until (18 * k).toInt()) {
            val y = frac(rnd(i, 5480) + t * 0.015f) * h
            val x = frac(rnd(i, 5481) + t * 0.008f) * w + sin(t + i) * 30f * s
            drawCircle(Color(0x66B8AFA8), (1.5f + rnd(i, 5482) * 2f) * s, Offset(x, y))
        }
    }
}

// ---- High kingdom: a castle above the mist at sunrise ----

private fun DrawScope.tower(x: Float, base: Float, wd: Float, ht: Float, body: Color, rim: Color, lightFromLeft: Boolean, seed: Int, t: Float, roof: Boolean) {
    val s = u()
    val top = base - ht
    drawRect(Brush.horizontalGradient(if (lightFromLeft) listOf(lerp(body, rim, 0.35f), body, lerp(body, Color.Black, 0.3f)) else listOf(lerp(body, Color.Black, 0.3f), body, lerp(body, rim, 0.35f)), startX = x - wd / 2, endX = x + wd / 2),
        Offset(x - wd / 2, top), Size(wd, ht))
    if (roof) {
        val cone = Path().apply { moveTo(x - wd * 0.62f, top); lineTo(x, top - wd * 1.6f); lineTo(x + wd * 0.62f, top); close() }
        drawPath(cone, Brush.horizontalGradient(listOf(lerp(body, rim, 0.25f), lerp(body, Color.Black, 0.2f)), startX = x - wd, endX = x + wd))
        drawLine(rim.copy(alpha = 0.6f), Offset(x - wd * 0.62f, top), Offset(x, top - wd * 1.6f), 1.4f * s, blendMode = BlendMode.Plus)
        // A pennant on the spire, rippling.
        val pole = Offset(x, top - wd * 1.6f)
        drawLine(body, pole, pole - Offset(0f, 30f * s), 1.5f * s)
        val f = Path().apply {
            moveTo(pole.x, pole.y - 30f * s)
            for (j in 0..10) { val q = j / 10f; lineTo(pole.x + q * 46f * s, pole.y - 30f * s + q * 5f * s + sin(t * 5f - q * 6f + seed) * 4f * s * q) }
            for (j in 10 downTo 0) { val q = j / 10f; lineTo(pole.x + q * 46f * s, pole.y - 22f * s + sin(t * 5f - q * 6f + seed) * 4f * s * q - q * 3f * s) }
            close()
        }
        drawPath(f, Color(0xFFB0283A))
    } else {
        // Battlements.
        val n = (wd / (14f * s)).toInt().coerceAtLeast(2)
        for (j in 0 until n step 2) drawRect(body, Offset(x - wd / 2 + j * wd / n, top - 10f * s), Size(wd / n, 10f * s))
    }
    // Windows, warm, a few.
    for (j in 0 until 4) if (rnd(seed * 7 + j, 5500) < 0.5f) {
        val wy = top + ht * (0.2f + j * 0.18f)
        drawRect(Color(0xFFFFC46A).copy(alpha = 0.85f), Offset(x - 3f * s, wy), Size(6f * s, 11f * s))
        glow(Offset(x, wy + 5f * s), 18f * s, Color(0xFFFFB050), 0.35f)
    }
}

internal fun DrawScope.highKingdom(t: Float, k: Float) {
    val w = size.width
    val h = size.height
    val s = u()
    val sun = Offset(w * 0.2f, h * 0.42f)
    back {
        drawRect(Brush.verticalGradient(listOf(Color(0xB0101A38), Color(0x80385080), Color(0x70E8A068), Color(0x40FFD9A0), Color.Transparent), endY = h * 0.8f), size = Size(w, h * 0.8f))
        glow(sun, h * 0.9f, Color(0xFFFFB866), 0.28f)
        glow(sun, h * 0.09f, Color(0xFFFFF2D0), 0.9f)
        // Rays through the gaps in the clouds, shimmering slowly.
        for (r in 0 until 9) {
            val ang = 1.2f + r * 0.18f + sin(t * 0.07f + r) * 0.03f
            beam(sun, ang, w * 0.9f, 0.04f + rnd(r, 5510) * 0.03f, Color(0xFFFFE2A8), 0.06f + 0.04f * (sin(t * 0.3f + r * 1.7f) * 0.5f + 0.5f))
        }
        // Long clouds catching the dawn on their undersides.
        for (i in 0 until 22) {
            val x = frac(rnd(i, 5520) + t * 0.003f * (1 + i % 2)) * (w + 800f * s) - 400f * s
            val y = h * (0.12f + rnd(i, 5521) * 0.22f)
            for (j in 0 until 5) glow(Offset(x + j * 70f * s, y + sin(j + i * 1f) * 10f * s), (70f + rnd(i * 5 + j, 5522) * 80f) * s, Color(0xFFFFC9A0), 0.09f, additive = false)
        }
        // Mountain ranges, far to near, each hazier with distance.
        ridge(h * 0.62f, h * 0.22f, 340f * s, 5530, Color(0xFF3A4A72), Color(0xFFB9A8C4), 0.75f, jag = 0.35f, rim = Color(0xFFFFD8A0))
        ridge(h * 0.7f, h * 0.18f, 300f * s, 5531, Color(0xFF263252), Color(0xFF8E86A8), 0.5f, jag = 0.3f, rim = Color(0xFFFFC890))
        // Mist filling the valleys.
        for (m in 0 until 30) glow(Offset(frac(rnd(m, 5532) + t * 0.004f) * (w + 900f * s) - 450f * s, h * (0.72f + rnd(m, 5533) * 0.08f)), (380f + rnd(m, 5534) * 200f) * s, Color(0xFFF3E6E0), 0.08f, additive = false)
    }
    mid {
        // The crag and its castle, the sun behind on the left rimming every left edge.
        val body = Color(0xFF1B1E2C)
        val rim = Color(0xFFFFC27A)
        val crag = Path().apply {
            moveTo(w * 0.48f, h)
            lineTo(w * 0.52f, h * 0.78f); lineTo(w * 0.56f, h * 0.7f); lineTo(w * 0.6f, h * 0.66f); lineTo(w * 0.82f, h * 0.64f)
            lineTo(w * 0.88f, h * 0.7f); lineTo(w * 0.93f, h * 0.8f); lineTo(w, h * 0.85f); lineTo(w, h); close()
        }
        drawPath(crag, Brush.horizontalGradient(listOf(lerp(body, rim, 0.25f), body, lerp(body, Color.Black, 0.35f)), startX = w * 0.5f, endX = w))
        drawPath(Path().apply { moveTo(w * 0.52f, h * 0.78f); lineTo(w * 0.56f, h * 0.7f); lineTo(w * 0.6f, h * 0.66f) }, rim.copy(alpha = 0.6f), style = Stroke(2f * s), blendMode = BlendMode.Plus)
        // Strata and cracks in the rock, catching the light on their upper lips.
        clipPath(crag) {
            for (l in 0 until 7) {
                val y0 = h * (0.69f + l * 0.045f + rnd(l, 5544) * 0.02f)
                val path = Path().apply {
                    moveTo(w * 0.45f, y0)
                    var x = w * 0.45f
                    while (x < w) { x += 14f * s; lineTo(x, y0 + (fbm(x / (90f * s) + l * 3f, 5545) - 0.5f) * 26f * s + (x - w * 0.45f) * 0.04f) }
                }
                drawPath(path, Color(0x33000000), style = Stroke(2.2f * s))
                withTransform({ translate(0f, -2f * s) }) { drawPath(path, rim.copy(alpha = 0.08f), style = Stroke(1.2f * s), blendMode = BlendMode.Plus) }
            }
            for (c2 in 0 until 7) {
                val x0 = w * (0.56f + rnd(c2, 5546) * 0.38f)
                drawLine(Color(0x66000000), Offset(x0, h * 0.68f), Offset(x0 + (rnd(c2, 5547) - 0.5f) * 60f * s, h), 2f * s)
            }
        }
        val base = h * 0.655f
        // Curtain wall, then towers of every height.
        drawRect(Brush.horizontalGradient(listOf(lerp(body, rim, 0.2f), body), startX = w * 0.6f, endX = w * 0.84f), Offset(w * 0.6f, base - 70f * s), Size(w * 0.24f, 70f * s))
        val n = 16
        for (j in 0 until n) drawRect(body, Offset(w * 0.6f + j * w * 0.24f / n, base - 80f * s), Size(w * 0.24f / n * 0.55f, 10f * s))
        val towers = listOf(0.605f to (70f to 230f), 0.655f to (50f to 170f), 0.7f to (90f to 360f), 0.745f to (60f to 280f), 0.79f to (54f to 200f), 0.835f to (70f to 250f))
        towers.forEachIndexed { i, (fx, dims) ->
            tower(w * fx, base, dims.first * s, dims.second * s, body, rim, true, i, t, roof = i % 2 == 0 || i == 2)
        }
        // A bridge across to a lone tower on a second spur.
        val spur = Path().apply { moveTo(w * 0.9f, h); lineTo(w * 0.93f, h * 0.7f); lineTo(w * 0.97f, h * 0.69f); lineTo(w, h * 0.74f); lineTo(w, h); close() }
        drawPath(spur, body)
        tower(w * 0.955f, h * 0.69f, 46f * s, 190f * s, body, rim, true, 9, t, roof = true)
        for (a in 0 until 4) {
            val x0 = w * (0.84f + a * 0.028f)
            drawArc(body, 180f, 180f, false, Offset(x0, h * 0.66f), Size(w * 0.028f, 50f * s), style = Stroke(9f * s))
        }
        // Birds crossing in a loose skein.
        val period = 34f
        val bn = floor(t / period).toLong()
        val bp = (t - bn * period) / period
        for (b in 0 until 9) {
            val lead = Offset(-0.1f * w + bp * 1.25f * w, h * (0.3f + rnd(bn.toInt(), 5540) * 0.1f))
            val side = if (b % 2 == 0) 1 else -1
            val p = lead + Offset(-(b + 1) / 2 * 26f * s, side * (b + 1) / 2 * 14f * s)
            val flap = sin(t * 9f + b) * 6f * s
            drawPath(Path().apply { moveTo(p.x - 9f * s, p.y - flap); quadraticTo(p.x - 3f * s, p.y - 2f * s, p.x, p.y); quadraticTo(p.x + 3f * s, p.y - 2f * s, p.x + 9f * s, p.y - flap) }, Color(0xCC1A1C26), style = Stroke(1.8f * s, cap = StrokeCap.Round))
        }
        // Mist curling round the crag's foot.
        for (m in 0 until 22) glow(Offset(frac(rnd(m, 5550) + t * 0.006f) * (w + 800f * s) - 400f * s, h * (0.88f + rnd(m, 5551) * 0.12f)), (360f + rnd(m, 5552) * 200f) * s, Color(0xFFE8DEE0), 0.1f, additive = false)
    }
    front {
        for (i in 0 until (20 * k).toInt()) {
            val p = Offset(frac(rnd(i, 5560) + t * 0.008f) * w, frac(rnd(i, 5561) - t * 0.01f) * h)
            val tw = 0.5f + 0.5f * sin(t * 2f + i)
            glow(p, (6f + rnd(i, 5562) * 10f) * s, Color(0xFFFFE6B0), 0.35f * tw)
        }
    }
}

// ---- Spellbound: floating candles under a starry vault ----

private fun DrawScope.candle(c: Offset, ht: Float, haze: Float, t: Float, seed: Int) {
    val s = u()
    val wd = ht * 0.22f
    val body = Rect(c.x - wd / 2, c.y, c.x + wd / 2, c.y + ht)
    val wax = lerp(Color(0xFFF2E6CC), Color(0xFF6B6470), haze)
    drawRoundRect(Brush.horizontalGradient(listOf(lerp(wax, Color.White, 0.25f), wax, lerp(wax, Color.Black, 0.45f)), startX = body.left, endX = body.right),
        body.topLeft, body.size, androidx.compose.ui.geometry.CornerRadius(wd * 0.2f))
    // The top lit through the wax, and a drip or two.
    drawOval(lerp(Color(0xFFFFE2A0), wax, 0.3f).copy(alpha = 0.9f), topLeft = Offset(body.left, body.top - wd * 0.12f), size = Size(wd, wd * 0.24f))
    for (d in 0 until 2) if (rnd(seed * 3 + d, 5600) < 0.6f) {
        val dx = body.left + wd * (0.2f + rnd(seed * 3 + d, 5601) * 0.6f)
        drawLine(wax, Offset(dx, body.top), Offset(dx, body.top + ht * (0.1f + rnd(seed * 3 + d, 5602) * 0.25f)), wd * 0.16f, StrokeCap.Round)
    }
    // The flame: a teardrop that leans and flickers, blue at the root, with its halo.
    val flick = fbm(t * 3f + seed, 5603)
    val fh = ht * (0.3f + 0.08f * flick)
    val lean = (fbm(t * 1.3f + seed * 2, 5604) - 0.5f) * wd * 0.5f
    val root = Offset(c.x, body.top - wd * 0.08f)
    val tip = root + Offset(lean, -fh)
    val flame = Path().apply {
        moveTo(root.x, root.y)
        cubicTo(root.x - wd * 0.42f, root.y - fh * 0.2f, tip.x - wd * 0.12f, tip.y + fh * 0.4f, tip.x, tip.y)
        cubicTo(tip.x + wd * 0.12f, tip.y + fh * 0.4f, root.x + wd * 0.42f, root.y - fh * 0.2f, root.x, root.y)
        close()
    }
    val bright = 1f - haze * 0.5f
    glow(root - Offset(0f, fh * 0.4f), ht * 1.3f, Color(0xFFFFA84A), 0.22f * bright)
    drawPath(flame, Brush.verticalGradient(listOf(Color(0xFFFFF6D6), Color(0xFFFFC35A), Color(0xFF6A8CFF)), startY = tip.y, endY = root.y), alpha = 0.95f * bright)
    glow(root - Offset(0f, fh * 0.35f), wd * 0.6f, Color.White, 0.6f * bright)
    // Warm light on the candle's own top.
    glow(Offset(c.x, body.top + ht * 0.08f), wd * 1.2f, Color(0xFFFFB060), 0.25f * bright)
}

internal fun DrawScope.spellbound(t: Float, k: Float) {
    val w = size.width
    val h = size.height
    val s = u()
    back {
        // A vault of night sky that is also a ceiling: deep blue, stars, slow cloud.
        drawRect(Brush.verticalGradient(listOf(Color(0xE0050816), Color(0xC00B1430), Color(0x80101830), Color.Transparent), endY = h * 0.9f), size = Size(w, h * 0.9f))
        for (i in 0 until 240) {
            val tw = 0.5f + 0.5f * sin(t * (0.8f + rnd(i, 5610) * 2.5f) + i)
            drawCircle(Color(0xFFE8EEFF).copy(alpha = (0.2f + 0.6f * rnd(i, 5611)) * tw), (0.6f + rnd(i, 5612) * 1.3f) * s, Offset(rnd(i, 5613) * w, rnd(i, 5614) * h * 0.6f))
        }
        for (i in 0 until 12) glow(Offset(frac(rnd(i, 5615) + t * 0.004f) * (w + 600f * s) - 300f * s, h * (0.1f + rnd(i, 5616) * 0.3f)), (160f + rnd(i, 5617) * 200f) * s, Color(0xFF6A7BB0), 0.06f)
        // Tall pointed windows on either side, moonlight pouring through in shafts.
        for (side in listOf(0, 1)) for (j in 0 until 2) {
            val cx = if (side == 0) w * (0.06f + j * 0.12f) else w * (0.94f - j * 0.12f)
            val top = h * 0.12f
            val bottom = h * 0.62f
            val half = 46f * s
            val arch = Path().apply {
                moveTo(cx - half, bottom); lineTo(cx - half, top + half * 2)
                quadraticTo(cx - half, top, cx, top - half * 0.6f); quadraticTo(cx + half, top, cx + half, top + half * 2)
                lineTo(cx + half, bottom); close()
            }
            drawPath(arch, Brush.verticalGradient(listOf(Color(0x80A8C8FF), Color(0x403A5A9A)), startY = top, endY = bottom), blendMode = BlendMode.Plus)
            drawLine(Color(0xCC05070F), Offset(cx, top), Offset(cx, bottom), 5f * s)
            for (b in 1 until 4) drawLine(Color(0xCC05070F), Offset(cx - half, top + half * 2 + b * (bottom - top - half * 2) / 4), Offset(cx + half, top + half * 2 + b * (bottom - top - half * 2) / 4), 4f * s)
            drawPath(arch, Color(0xCC05070F), style = Stroke(8f * s))
            // Its shaft of light falling into the hall.
            val toward = if (side == 0) 1f else -1f
            drawPath(Path().apply { moveTo(cx - half, bottom); lineTo(cx + half, bottom); lineTo(cx + half + toward * 360f * s, h); lineTo(cx - half + toward * 260f * s, h); close() },
                Brush.verticalGradient(listOf(Color(0x30A8C8FF), Color.Transparent), startY = bottom, endY = h), blendMode = BlendMode.Plus)
            // Dust turning in it.
            for (d in 0 until 14) {
                val q = frac(rnd(d + j * 20 + side * 40, 5620) + t * 0.02f)
                val p = Offset(cx + toward * q * 300f * s + (rnd(d, 5621) - 0.5f) * 80f * s, bottom + q * (h - bottom))
                drawCircle(Color(0x88DDE8FF), 1.3f * s, p, blendMode = BlendMode.Plus)
            }
        }
    }
    mid {
        // Candles hanging in the air at every depth, bobbing slowly, far ones small and hazy.
        val n = (46 * k).toInt().coerceIn(18, 80)
        val order = (0 until n).sortedBy { rnd(it, 5630) }
        for (i in order) {
            val depth = rnd(i, 5630)
            val haze = 1f - depth
            val x = rnd(i, 5631) * w
            val y = h * (0.08f + rnd(i, 5632) * 0.5f) + sin(t * (0.5f + rnd(i, 5633) * 0.4f) + i) * 12f * s
            candle(Offset(x, y), (24f + depth * 70f) * s, haze * 0.8f, t, i)
        }
    }
    front {
        // Sparkles drifting down, each a tiny four-pointed glint.
        for (i in 0 until (22 * k).toInt()) {
            val p = Offset(frac(rnd(i, 5640) + t * 0.006f) * w + sin(t * 0.7f + i) * 20f * s, frac(rnd(i, 5641) + t * 0.02f) * h)
            val tw = (0.5f + 0.5f * sin(t * 4f + i * 1.7f)).pow(2)
            val r = (5f + rnd(i, 5642) * 7f) * s * tw
            drawLine(Color(0xFFFFF1C8).copy(alpha = 0.8f * tw), p - Offset(r, 0f), p + Offset(r, 0f), 1.1f * s, blendMode = BlendMode.Plus)
            drawLine(Color(0xFFFFF1C8).copy(alpha = 0.8f * tw), p - Offset(0f, r), p + Offset(0f, r), 1.1f * s, blendMode = BlendMode.Plus)
            glow(p, r * 1.6f, Color(0xFFFFD98A), 0.3f * tw)
        }
    }
}
