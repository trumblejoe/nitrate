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

// ---- Petals (Poetry, roses) ----

internal fun DrawScope.fallingPetals(t: Float, k: Float, n: Int, colors: List<Color>, salt: Int, size: Float = 1f) {
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
    back { inkMountains(t) }
    front { fallingPetals(t, k, 26, listOf(Color(0xFFF7C6D0), Color(0xFFF2AFC0), Color(0xFFFFE3EA)), 1420, 0.75f) }
    // One verse at a time: 3 s in, ~22 s up, 3 s out.
    val cycle = 30f
    val n = floor(t / cycle).toLong()
    val u = t - n * cycle
    val alpha = when { u < 3f -> u / 3f; u > cycle - 3f -> (cycle - u) / 3f; else -> 1f }
    val poem = dev.nitrate.core.poetry.Poems.at((n + (System.currentTimeMillis() / 86_400_000L)) * 30_000L)
    mid { verse(poem, Offset(w - 600f, h * 0.6f), alpha * 0.92f, 30f, android.graphics.Paint.Align.LEFT) }
}

// ---- Drawn pre-roll scenes (full screen, opaque) ----

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

/** The pre-roll starfield, softer, behind the library's Starfield view. */
object ShowStars {
    fun DrawScope.draw(t: Float) = starfield(t, 0.6f)
}
