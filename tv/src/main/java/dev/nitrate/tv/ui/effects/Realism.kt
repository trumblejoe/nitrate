package dev.nitrate.tv.ui.effects

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The realistic layer: light rather than shapes. Soft glows from one cached radial gradient per
 * colour (scaled into place, so nothing is allocated per particle), additive blending for anything
 * that gives off light, depth layers (far = small, slow, faint; near = big, fast, soft), smooth
 * noise for clouds and drift, and real motion for fireworks (drag and gravity, trails sampled from
 * the path). Everything is still a function of time.
 */

// ---- Noise ----

private fun hash(i: Int, salt: Int): Float = rnd(i, salt)

private fun smooth(x: Float) = x * x * (3 - 2 * x)

/** Smooth 1D value noise in [0, 1). */
internal fun noise(x: Float, salt: Int = 0): Float {
    val i = floor(x).toInt()
    val f = smooth(x - i)
    return hash(i, salt) * (1 - f) + hash(i + 1, salt) * f
}

/** A few octaves of [noise]: natural-looking wander. */
internal fun fbm(x: Float, salt: Int = 0): Float =
    (noise(x, salt) * 0.55f + noise(x * 2.03f, salt + 7) * 0.3f + noise(x * 4.1f, salt + 13) * 0.15f)

// ---- Glows ----

// Shared by scenes drawing on background threads at once (a page and a preview).
private val glows = java.util.concurrent.ConcurrentHashMap<Int, Brush>()

/** A soft round light of [radius] at [c]: bright middle, long falloff, nothing allocated per call. */
internal fun DrawScope.glow(c: Offset, radius: Float, color: Color, alpha: Float = 1f, additive: Boolean = true) {
    if (alpha <= 0.003f || radius <= 0.3f) return
    val argb = color.copy(alpha = 1f).toArgb()
    val brush = glows.getOrPut(argb) {
        Brush.radialGradient(
            0f to color.copy(alpha = 1f),
            0.18f to color.copy(alpha = 0.55f),
            0.45f to color.copy(alpha = 0.16f),
            1f to color.copy(alpha = 0f),
            center = Offset.Zero, radius = 1f,
        )
    }
    withTransform({ translate(c.x, c.y); scale(radius, radius, Offset.Zero) }) {
        drawCircle(brush, radius = 1f, center = Offset.Zero, alpha = alpha.coerceIn(0f, 1f), blendMode = if (additive) BlendMode.Plus else BlendMode.SrcOver)
    }
}

// ---- Weather ----

/**
 * A heavy cloud deck along the top: dozens of soft puffs drifting on noise, darker underneath.
 * [lit] (0..1) lights them from [litAt], as lightning does from inside the cloud.
 */
internal fun DrawScope.cloudDeck(t: Float, depth: Float, lit: Float = 0f, litAt: Offset = Offset.Zero, salt: Int = 300) {
    val w = size.width
    val n = 46
    for (i in 0 until n) {
        val layer = i % 3 // 0 = far and high, 2 = low and near
        val speed = 6f + layer * 5f
        val x = ((rnd(i, salt) * (w + 900f) + t * speed) % (w + 900f)) - 450f
        val y = depth * (0.05f + rnd(i, salt + 1) * 0.55f + layer * 0.18f) + (fbm(t * 0.05f + i, salt) - 0.5f) * 30f
        val r = (180f + rnd(i, salt + 2) * 260f) * (0.8f + layer * 0.2f)
        // Lighter on top where the sky lights them, darker underneath.
        val shade = 0.22f + layer * 0.04f
        glow(Offset(x, y - r * 0.2f), r, Color(shade, shade + 0.02f, shade + 0.06f), alpha = 0.7f, additive = false)
        glow(Offset(x, y + r * 0.25f), r * 0.8f, Color(0.07f, 0.08f, 0.11f), alpha = 0.6f, additive = false)
        if (lit > 0f) {
            val d = (Offset(x, y) - litAt).getDistance()
            val near = (1f - d / (w * 0.55f)).coerceIn(0f, 1f)
            if (near > 0f) glow(Offset(x, y + r * 0.15f), r * 0.9f, Color(0xFFB9C8FF), alpha = lit * near * 0.7f)
        }
    }
    // The deck's underside fading into the page.
    drawRect(Brush.verticalGradient(listOf(Color(0xE60A0D14), Color(0x990A0D14), Color.Transparent), endY = depth * 1.25f))
}

/** Rain in three depths, slanted by a gusting wind, splashes and spray along the bottom. */
/** [layered]: far drops behind, middle ones in the middle, the few nearest in front (callers then don't gate it). */
internal fun DrawScope.rain(t: Float, k: Float, salt: Int = 400, layered: Boolean = false) {
    val w = size.width
    val h = size.height
    val wind = 0.16f + (fbm(t * 0.15f, salt) - 0.5f) * 0.18f
    data class Layer(val n: Int, val len: Float, val width: Float, val alpha: Float, val speed: Float)
    val layers = listOf(
        Layer((220 * k).toInt(), 16f, 1f, 0.16f, 1300f),
        Layer((120 * k).toInt(), 30f, 1.4f, 0.24f, 1900f),
        Layer((26 * k).toInt(), 70f, 2.6f, 0.2f, 2700f),
    )
    layers.forEachIndexed { li, l ->
        val color = Color(0xFFBFD3EE)
        for (i in 0 until l.n) {
            val id = i + li * 1000
            val speed = l.speed * (0.85f + rnd(id, salt) * 0.3f)
            val span = h + l.len + 60f
            val p = (rnd(id, salt + 1) * span + t * speed) % span
            val y = p - l.len
            val x = (rnd(id, salt + 2) * (w + 400f) - 200f + p * wind) % (w + 200f)
            val dx = l.len * wind
            atDepth(layered, li) {
                drawLine(color.copy(alpha = l.alpha * 0.35f), Offset(x - dx, y - l.len), Offset(x - dx * 0.4f, y - l.len * 0.4f), l.width, StrokeCap.Round)
                drawLine(color.copy(alpha = l.alpha), Offset(x - dx * 0.4f, y - l.len * 0.4f), Offset(x, y), l.width, StrokeCap.Round)
            }
        }
    }
    // Gusts: broad sheets of heavier rain sweeping across (far off).
    if (!layered || Pass.on(Depth.BACK)) for (g in 0 until 3) {
        val x = ((t * (120f + g * 40f) + g * 700f) % (w + 1200f)) - 600f
        drawRect(
            Brush.horizontalGradient(listOf(Color.Transparent, Color(0x14C8D8F0), Color.Transparent), startX = x, endX = x + 600f),
        )
    }
    // Splashes: little crowns that open and fade.
    for (s in 0 until (60 * k).toInt()) {
        val life = 0.35f
        val cycle = floor((t + rnd(s, salt + 5) * life) / life).toInt()
        val u = ((t + rnd(s, salt + 5) * life) - cycle * life) / life
        val x = rnd(s * 131 + cycle, salt + 6) * w
        val y = h - 6f - rnd(s * 17 + cycle, salt + 7) * 40f
        val r = 3f + u * 12f
        drawOval(Color(0xFFDCE8F8).copy(alpha = 0.35f * (1 - u)), topLeft = Offset(x - r, y - r * 0.3f), size = androidx.compose.ui.geometry.Size(r * 2, r * 0.6f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.2f))
    }
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0x2EAFC2DC)), startY = h * 0.8f))
}

/** A bolt by midpoint displacement, with branches; segments as (from, to, strength). */
internal fun boltSegments(from: Offset, to: Offset, seed: Int, detail: Int = 7): List<Triple<Offset, Offset, Float>> {
    val out = ArrayList<Triple<Offset, Offset, Float>>()
    fun split(a: Offset, b: Offset, depth: Int, strength: Float, s: Int) {
        if (depth == 0) { out += Triple(a, b, strength); return }
        val len = (b - a).getDistance()
        val mid = (a + b) / 2f
        val normal = Offset(-(b.y - a.y), b.x - a.x) / max(len, 1f)
        val m = mid + normal * ((rnd(s, 1) - 0.5f) * len * 0.42f)
        split(a, m, depth - 1, strength, s * 2 + 1)
        split(m, b, depth - 1, strength, s * 2 + 2)
        // Now and then a branch, angled off and weaker, that dies out.
        if (strength > 0.3f && depth in 3..5 && rnd(s, 2) < 0.38f) {
            val dir = (b - a)
            val angle = (rnd(s, 3) - 0.5f) * 1.6f
            val bend = Offset(dir.x * cos(angle) - dir.y * sin(angle), dir.x * sin(angle) + dir.y * cos(angle))
            split(m, m + bend * (0.7f + rnd(s, 4) * 0.6f), depth - 1, strength * 0.45f, s * 7 + 3)
        }
    }
    split(from, to, detail, 1f, seed)
    return out
}

/** Draws a bolt as light: a wide faint halo, a blue glow and a white-hot core. */
internal fun DrawScope.lightning(segments: List<Triple<Offset, Offset, Float>>, brightness: Float) {
    if (brightness <= 0f) return
    for ((a, b, s) in segments) drawLine(Color(0xFF6E84FF).copy(alpha = 0.10f * s * brightness), a, b, 26f * s, StrokeCap.Round, blendMode = BlendMode.Plus)
    for ((a, b, s) in segments) drawLine(Color(0xFFA9BCFF).copy(alpha = 0.35f * s * brightness), a, b, 7f * s, StrokeCap.Round, blendMode = BlendMode.Plus)
    for ((a, b, s) in segments) drawLine(Color.White.copy(alpha = (0.5f + 0.5f * s) * brightness), a, b, 1.2f + 1.8f * s, StrokeCap.Round)
}

/** A thunderstorm: clouds, rain, and every few seconds a strike that flickers and lights the deck. */
internal fun DrawScope.thunderstorm(t: Float, k: Float) {
    val w = size.width
    val h = size.height
    val period = 7.5f
    val cycle = floor(t / period).toInt()
    val u = t - cycle * period
    val strikes = rnd(cycle, 3) > 0.2f
    // The flicker of a real strike: a stroke, a dip, return strokes, then a fade.
    val env = when {
        !strikes -> 0f
        u < 0.05f -> 1f
        u < 0.09f -> 0.25f
        u < 0.14f -> 0.9f
        u < 0.18f -> 0.35f
        u < 0.22f -> 0.7f
        u < 0.7f -> 0.7f * exp(-(u - 0.22f) * 7f)
        else -> 0f
    }
    val top = Offset(w * (0.15f + rnd(cycle, 4) * 0.7f), h * 0.08f)
    // Distant sheet lightning between strikes, inside the cloud only.
    val sheetPhase = (t * 0.37f) % 1f
    val sheet = if (sheetPhase < 0.03f) 0.35f * (1 - sheetPhase / 0.03f) else 0f
    val sheetAt = Offset(w * noise(floor(t * 0.37f), 77), h * 0.1f)
    back {
        cloudDeck(t, h * 0.42f, lit = max(env, sheet), litAt = if (env > 0f) top else sheetAt)
        if (env > 0f) {
            drawRect(Color(0xFFCAD6FF).copy(alpha = 0.10f * env), blendMode = BlendMode.Plus)
            glow(top, h * 0.9f, Color(0xFF9FB4FF), alpha = 0.35f * env)
            if (u < 0.45f) {
                val ground = Offset(top.x + (rnd(cycle, 8) - 0.5f) * w * 0.35f, h * (0.75f + rnd(cycle, 9) * 0.25f))
                lightning(boltSegments(top, ground, cycle * 97 + 11), env)
            }
        }
    }
    // Dense, with its depths inside the helper: kept behind the cards.
    rain(t, k, layered = true)
}

// ---- Fireworks ----

internal enum class Shell { PEONY, WILLOW, RING, CRACKLE }

/**
 * A firework display: shells launch with a sparking trail, burst at the top into stars that slow
 * with drag and fall with gravity, leaving streaks; willows hang in gold, crackles flicker out,
 * bursts light the sky and leave smoke that drifts.
 */
internal fun DrawScope.fireworkShow(t: Float, k: Float, palette: List<Color>, salt: Int = 500, ceiling: Float = 0.12f, floorY: Float = 0.55f) {
    val w = size.width
    val h = size.height
    val shells = (5 * k).toInt().coerceAtLeast(2)
    for (s in 0 until shells) {
        val period = 4.2f + rnd(s, salt) * 2.5f
        val phase = rnd(s, salt + 1) * period
        val cycle = floor((t + phase) / period).toInt()
        val u = (t + phase) - cycle * period
        val id = s * 1009 + cycle
        val x0 = w * (0.08f + rnd(id, salt + 2) * 0.84f)
        val burst = Offset(x0 + (rnd(id, salt + 3) - 0.5f) * 120f, h * (ceiling + rnd(id, salt + 4) * (floorY - ceiling)))
        val color = palette[(s + cycle * 2).mod(palette.size)]
        // Mostly coloured peonies; now and then a ring, a crackle or a gold willow.
        val kind = listOf(Shell.PEONY, Shell.PEONY, Shell.RING, Shell.PEONY, Shell.CRACKLE, Shell.PEONY, Shell.WILLOW)[(s * 3 + cycle).mod(7)]
        val launch = 1.1f
        if (u < launch) {
            // Rising: decelerating, a sparking tail behind.
            val p = u / launch
            val y = h + (burst.y - h) * (1 - (1 - p) * (1 - p))
            val x = x0 + (burst.x - x0) * p
            glow(Offset(x, y), 7f, Color(0xFFFFD9A0), 0.9f)
            for (sp in 1..10) {
                val back = sp * 0.025f
                val pb = ((u - back) / launch).coerceAtLeast(0f)
                val yb = h + (burst.y - h) * (1 - (1 - pb) * (1 - pb)) + rnd(id * 31 + sp + (u * 30).toInt(), salt) * 8f
                drawCircle(Color(0xFFFFC37A).copy(alpha = 0.6f * (1 - sp / 11f)), 1.6f, Offset(x0 + (burst.x - x0) * pb + (rnd(sp, id) - 0.5f) * 6f, yb), blendMode = BlendMode.Plus)
            }
            continue
        }
        val tau = u - launch
        val life = when (kind) { Shell.WILLOW -> 3.4f; Shell.CRACKLE -> 2.2f; else -> 2.0f }
        // Sky lit by the burst, and the smoke it leaves.
        if (tau < 0.8f) glow(burst, h * 0.55f, color, 0.22f * (1 - tau / 0.8f))
        if (tau < 4f) glow(burst + Offset(tau * 12f, tau * 10f), 90f + tau * 60f, Color(0xFF8C8C96), 0.10f * (1 - tau / 4f), additive = false)
        if (tau > life) continue
        val n = (if (kind == Shell.RING) 48 else 80)
        val speed = (if (kind == Shell.WILLOW) 380f else 560f) * (0.8f + rnd(id, salt + 6) * 0.5f)
        val drag = if (kind == Shell.WILLOW) 1.6f else 2.3f
        val gravity = if (kind == Shell.WILLOW) 160f else 110f
        val starColor = if (kind == Shell.WILLOW) Color(0xFFFFC46B) else color
        fun pos(i: Int, time: Float): Offset {
            val a = 2 * PI.toFloat() * i / n + rnd(i, id) * 0.12f
            // Spread over a sphere seen side on (a ring stays a ring).
            val v = speed * if (kind == Shell.RING) 1f else sqrt(0.25f + 0.75f * rnd(i + 7, id))
            val e = (1 - exp(-drag * time)) / drag
            val fall = gravity / drag * time - gravity / (drag * drag) * (1 - exp(-drag * time))
            return burst + Offset(cos(a) * v * e, sin(a) * v * e * (if (kind == Shell.RING) 0.45f else 1f) + fall)
        }
        val fade = (1 - tau / life).let { sqrt(it) }
        for (i in 0 until n) {
            var a = fade
            if (kind == Shell.CRACKLE && tau > 0.9f) a *= if (rnd(i * 53 + (tau * 24).toInt(), id) > 0.45f) 1.3f else 0.1f
            val head = pos(i, tau)
            // The streak: where it was a moment ago, fading back.
            val trail = if (kind == Shell.WILLOW) 14 else 9
            var prev = head
            for (s2 in 1..trail) {
                val back = pos(i, (tau - s2 * 0.035f).coerceAtLeast(0f))
                drawLine(starColor.copy(alpha = (a * (1 - s2 / (trail + 1f)) * 0.85f).coerceIn(0f, 1f)), prev, back, 2.6f, StrokeCap.Round, blendMode = BlendMode.Plus)
                prev = back
            }
            // Hot white at first, the shell's colour as it cools.
            val hot = (1 - tau / 0.35f).coerceIn(0f, 1f)
            glow(head, 16f + 10f * hot, starColor, (a * 0.9f).coerceIn(0f, 1f))
            drawCircle(lerp(starColor, Color.White, 0.35f + 0.65f * hot).copy(alpha = a.coerceIn(0f, 1f)), 2.4f + 1.4f * hot, head)
        }
    }
}

// ---- Snow ----

/** Snow in depth: far specks, mid flakes and a few big soft out-of-focus ones close by, on a wandering breeze. */
/** [layered]: far flakes behind, middle ones in the middle, the few big near ones in front (callers then don't gate it). */
internal fun DrawScope.realSnow(t: Float, k: Float, salt: Int = 600, layered: Boolean = false) {
    val w = size.width
    val h = size.height
    val breeze = (fbm(t * 0.08f, salt) - 0.5f) * 80f
    data class Layer(val n: Int, val r: Float, val speed: Float, val alpha: Float, val soft: Boolean)
    val layers = listOf(
        Layer((200 * k).toInt(), 1.3f, 26f, 0.55f, false),
        Layer((90 * k).toInt(), 2.8f, 48f, 0.75f, true),
        Layer((14 * k).toInt(), 14f, 95f, 0.32f, true),
    )
    layers.forEachIndexed { li, l ->
        for (i in 0 until l.n) {
            val id = i + li * 1000
            val speed = l.speed * (0.75f + rnd(id, salt) * 0.5f)
            val span = h + 60f
            val y = (rnd(id, salt + 1) * span + t * speed) % span - 30f
            val sway = (fbm(t * 0.3f + id * 0.37f, salt + 2) - 0.5f) * 70f * (1 + li)
            val x = ((rnd(id, salt + 3) * w + sway + breeze * (1 + li) * t * 0.02f) % w + w) % w
            atDepth(layered, li) {
                if (l.soft) glow(Offset(x, y), l.r * 2.2f, Color.White, l.alpha, additive = false)
                else drawCircle(Color.White.copy(alpha = l.alpha * (0.6f + 0.4f * rnd(id, salt + 4))), l.r, Offset(x, y))
            }
        }
    }
}

// ---- Forest ----

/**
 * A pine as a silhouette: tiers of drooping branches with ragged tips (noise, so each tree differs),
 * snow lying on top of each tier, darker towards the trunk. [haze] (0..1) fades it into the distance.
 */
internal fun DrawScope.pineTree(base: Offset, height: Float, seed: Int, haze: Float, snow: Boolean = true) {
    val tiers = 7
    val body = lerp(Color(0xFF0B1A14), Color(0xFF3A4A5C), haze)
    val snowColor = lerp(Color(0xFFE8EEF6), Color(0xFF8A98AC), haze)
    drawRect(lerp(Color(0xFF1E140E), Color(0xFF3A4450), haze), topLeft = Offset(base.x - height * 0.025f, base.y - height * 0.12f), size = androidx.compose.ui.geometry.Size(height * 0.05f, height * 0.12f))
    for (i in 0 until tiers) {
        val f = i / (tiers - 1f) // 0 = top
        val tierTop = base.y - height + f * height * 0.78f
        val tierBottom = tierTop + height * (0.14f + f * 0.1f)
        val halfW = height * (0.07f + f * 0.26f)
        val path = androidx.compose.ui.graphics.Path()
        path.moveTo(base.x, tierTop)
        val steps = 14
        // Right side drooping out, ragged along the bottom edge, back up the left.
        for (j in 1..steps) {
            val q = j / steps.toFloat()
            path.lineTo(base.x + halfW * q, tierTop + (tierBottom - tierTop) * (q * q * 0.9f + 0.1f) + (noise(q * 9f + i * 3f, seed) - 0.5f) * halfW * 0.12f)
        }
        for (j in steps downTo -steps) {
            val q = j / steps.toFloat()
            val jag = if (j % 2 == 0) 0f else halfW * 0.09f * (0.5f + noise(j * 0.7f + i, seed + 1))
            path.lineTo(base.x + halfW * q, tierBottom - jag + (noise(q * 5f + i, seed + 2) - 0.5f) * halfW * 0.1f)
        }
        for (j in -steps..0) {
            val q = -j / steps.toFloat()
            path.lineTo(base.x - halfW * q, tierTop + (tierBottom - tierTop) * (q * q * 0.9f + 0.1f) + (noise(q * 9f + i * 3f + 50f, seed) - 0.5f) * halfW * 0.12f)
        }
        path.close()
        drawPath(path, body)
        if (snow) {
            // Snow resting on the upper slopes of the tier.
            val cap = androidx.compose.ui.graphics.Path()
            cap.moveTo(base.x, tierTop)
            for (j in 1..steps) {
                val q = j / steps.toFloat()
                cap.lineTo(base.x + halfW * q * 0.85f, tierTop + (tierBottom - tierTop) * (q * q * 0.9f + 0.1f) * 0.85f)
            }
            for (j in steps downTo -steps) {
                val q = j / steps.toFloat()
                val sag = (tierBottom - tierTop) * (0.35f + 0.25f * noise(q * 6f + i, seed + 3)) * (1 - kotlin.math.abs(q) * 0.3f)
                cap.lineTo(base.x + halfW * q * 0.85f, tierTop + (tierBottom - tierTop) * (q * q * 0.9f + 0.1f) * 0.85f + sag * 0.35f)
            }
            cap.close()
            drawPath(cap, snowColor.copy(alpha = 0.85f))
        }
    }
}

/** A snowy forest along the bottom: three ranks of pines fading into mist, drifts in front. */
internal fun DrawScope.snowyForest(t: Float, k: Float) {
    val w = size.width
    val h = size.height
    back { drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0x553C4C66)), startY = h * 0.55f)) }
    for (rank in 0 until 3) {
        val haze = 0.75f - rank * 0.35f
        val count = 16 - rank * 4
        // The far rank (and the mist over it) is distant scenery; the nearer ranks are the subject.
        val drawRank = {
            for (i in 0 until count) {
                val x = w * (i + rnd(i, 700 + rank) * 0.8f) / (count - 1f) - 40f
                val hgt = h * (0.18f + rank * 0.07f) * (0.8f + rnd(i, 710 + rank) * 0.45f)
                pineTree(Offset(x, h + 8f - rank * 2f), hgt, i * 13 + rank * 101, haze.coerceAtLeast(0f))
            }
            // Mist between the ranks.
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0x30B8C6DA), Color.Transparent), startY = h * (0.72f + rank * 0.06f), endY = h))
        }
        if (rank == 0) back { drawRank() } else mid { drawRank() }
    }
    // Drifts across the front.
    val drift = androidx.compose.ui.graphics.Path().apply {
        moveTo(0f, h)
        var x = 0f
        while (x <= w) { lineTo(x, h - 26f - fbm(x / 260f, 720) * 34f); x += 24f }
        lineTo(w, h); close()
    }
    mid { drawPath(drift, Brush.verticalGradient(listOf(Color(0xFFDCE4EE), Color(0xFFA9B6C8)), startY = h - 60f, endY = h)) }
    // Dense, with its depths inside the shared helper: kept behind the cards.
    realSnow(t, k, layered = true)
}

/** For particle helpers drawn in three depth layers: layer 0 behind, 1 in the middle, 2 (nearest) in front; ungated when not [layered]. */
internal inline fun atDepth(layered: Boolean, layer: Int, block: () -> Unit) {
    if (!layered) return block()
    when (layer) {
        0 -> back(block)
        1 -> mid(block)
        else -> front(block)
    }
}
