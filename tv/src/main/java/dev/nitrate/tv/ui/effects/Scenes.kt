package dev.nitrate.tv.ui.effects

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import dev.nitrate.core.effects.EffectsPrefs
import dev.nitrate.core.effects.Occasion
import dev.nitrate.core.effects.PageTheme
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * A page's theme and the day's occasion, drawn over the page without taking focus or touches:
 * everything is a function of time (no stored particles), so it's cheap, and it's original
 * linework drawn in code. ~30 frames a second; with "Reduce motion" a still frame.
 */
@Composable
fun EffectsLayer(theme: PageTheme?, occasion: Occasion?, prefs: EffectsPrefs, modifier: Modifier = Modifier, at: Float? = null, depths: Set<Depth>? = null) {
    if (!prefs.on || (theme == null || theme == PageTheme.NONE) && occasion == null) return
    // Film and photos are the scene's back: only in the pass that draws it.
    val backPass = depths == null || Depth.BACK in depths
    val k = prefs.intensity.scale
    // Real subjects play as film when this build has the clip (and not with Reduce motion), else drawn.
    val shipped = rememberShippedClips()
    val themeClip = theme?.let { dev.nitrate.core.effects.EffectClips.forTheme(it) }?.takeIf { it.name in shipped && !prefs.reduceMotion && at == null }
    val occasionClip = occasion?.let { dev.nitrate.core.effects.EffectClips.forOccasion(it) }?.takeIf { it.name in shipped && !prefs.reduceMotion && at == null }
    androidx.compose.foundation.layout.Box(modifier.fillMaxSize()) {
        if (backPass) (occasionClip ?: themeClip)?.let { ClipBand(it, Modifier.align(androidx.compose.ui.Alignment.BottomCenter)) }
        // No film clip: the app's own photos, if this theme has a set and the build ships it.
        val context = androidx.compose.ui.platform.LocalContext.current
        val themePhotos = if (themeClip == null && occasionClip == null && !prefs.reduceMotion)
            theme?.let { dev.nitrate.core.effects.EffectClips.photosForTheme(it) }?.takeIf { it in remember { shippedPhotoSets(context) } } else null
        if (backPass) themePhotos?.let { PhotoBand(it, 0.45f, Modifier.align(androidx.compose.ui.Alignment.BottomCenter), at = at) }
        val drawTheme = theme != null && themeClip == null && themePhotos == null
        val drawOccasion = occasion != null && occasionClip == null
        if (drawTheme || drawOccasion) {
            DrawnScene(Triple(theme, occasion, depths), at = at, still = prefs.reduceMotion) { t ->
                Pass.with(depths) {
                    if (drawTheme) drawTheme(theme!!, t, k)
                    if (drawOccasion) drawOccasion(occasion!!, t, k)
                }
            }
        }
    }
}

/**
 * A drawn scene over the page. A held moment ([at], screenshots) is drawn in place. Playing, each
 * frame is drawn in software on a background thread into a bitmap and the page shows the latest one:
 * the scenes are thousands of shapes a frame, too much for a TV's main thread and its graphics
 * driver, which stalled or dropped them; this way they look the same on every device (and as in the
 * tests) and run as fast as the device allows, up to ~30 fps, without slowing the page.
 */
@Composable
private fun DrawnScene(key: Any, at: Float?, still: Boolean, draw: DrawScope.(Float) -> Unit) {
    if (at != null) {
        Canvas(Modifier.fillMaxSize()) { draw(at) }
        return
    }
    val latest = androidx.compose.runtime.rememberUpdatedState(draw)
    var area by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    val onScreen = remember { java.util.concurrent.atomic.AtomicReference<ImageBitmap?>(null) }
    LaunchedEffect(key, area, still) {
        if (area.width <= 0 || area.height <= 0) return@LaunchedEffect
        val full = Size(area.width.toFloat(), area.height.toFloat())
        // Drawn at up to 1280 wide and scaled up: the scenes are soft light, and it keeps each frame quick.
        val scale = (RENDER_WIDTH / full.width).coerceAtMost(1f)
        val w = (full.width * scale).toInt().coerceAtLeast(1)
        val h = (full.height * scale).toInt().coerceAtLeast(1)
        // Three bitmaps in turn, so the one on screen is never the one being drawn.
        val buffers = List(3) { ImageBitmap(w, h) }
        onScreen.set(null)
        val scope = androidx.compose.ui.graphics.drawscope.CanvasDrawScope()
        val start = System.nanoTime()
        withContext(Dispatchers.Default) {
            while (isActive) {
                val began = System.nanoTime()
                val t = if (still) 0f else (began - start) / 1e9f
                // Never the one on screen, nor the one waiting to be shown (the screen can be frames behind).
                val bitmap = buffers.first { it !== onScreen.get() && it !== frame }
                bitmap.asAndroidBitmap().eraseColor(android.graphics.Color.TRANSPARENT)
                val canvas = androidx.compose.ui.graphics.Canvas(bitmap)
                canvas.scale(scale, scale)
                runCatching {
                    scope.draw(androidx.compose.ui.unit.Density(1f), androidx.compose.ui.unit.LayoutDirection.Ltr, canvas, full) { latest.value(this, t) }
                }
                frame = bitmap
                if (still) break
                delay((FRAME_MS - (System.nanoTime() - began) / 1_000_000).coerceAtLeast(4))
            }
        }
    }
    Canvas(Modifier.fillMaxSize().onSizeChanged { area = it }) {
        frame?.also { onScreen.set(it) }?.let { drawImage(it, dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Low) }
    }
}

private const val RENDER_WIDTH = 1280f
private const val FRAME_MS = 33L

/** Today's occasion (in the device's time zone), checked again every hour. */
@Composable
fun rememberOccasion(prefs: EffectsPrefs): Occasion? {
    val today by produceState(todayDay()) {
        while (true) { delay(3_600_000); value = todayDay() }
    }
    return dev.nitrate.core.effects.Occasions.active(today, prefs)
}

internal fun todayDay(): dev.nitrate.core.effects.Day =
    java.time.LocalDate.now().let { dev.nitrate.core.effects.Day(it.year, it.monthValue, it.dayOfMonth) }

// ---- Small helpers ----

/** A stable pseudo-random number in [0, 1) for [i] and [salt]. */
internal fun rnd(i: Int, salt: Int = 0): Float {
    var x = i * 374761393 + salt * 668265263
    x = (x xor (x ushr 13)) * 1274126177
    return ((x xor (x ushr 16)) and 0x7fffffff) / 2147483648f
}

private fun frac(x: Float) = x - floor(x)

private fun count(n: Int, k: Float) = (n * k).toInt().coerceAtLeast(1)

/** Things falling (or rising, with a negative [speed]) across the screen, drifting side to side. */
private fun DrawScope.fallers(n: Int, t: Float, speed: Float, salt: Int, draw: DrawScope.(Offset, Float, Int) -> Unit) {
    for (i in 0 until n) {
        val x = rnd(i, salt) * size.width + sin(t * 0.7f + i) * 18f
        val p = frac(rnd(i, salt + 1) + t * speed * (0.6f + rnd(i, salt + 2) * 0.8f) / size.height * 100f)
        val y = if (speed >= 0) p * (size.height + 40f) - 20f else (1 - p) * (size.height + 40f) - 20f
        draw(Offset(x, y), rnd(i, salt + 3), i)
    }
}

/** Something crossing the screen once every [period] seconds, left to right ([dir] 1) or back. */
private fun crossing(t: Float, period: Float, width: Float, span: Float, dir: Int = 1, phase: Float = 0f): Float? {
    val p = frac((t + phase) / period)
    val travel = 0.55f // the share of the period it's on screen
    if (p > travel) return null
    val q = p / travel
    return if (dir > 0) -span + q * (width + 2 * span) else width + span - q * (width + 2 * span)
}

internal fun DrawScope.star(center: Offset, r: Float, color: Color, points: Int = 5, inner: Float = 0.45f, rotation: Float = 0f) {
    val path = Path()
    for (i in 0 until points * 2) {
        val a = rotation + PI.toFloat() * i / points - PI.toFloat() / 2
        val rr = if (i % 2 == 0) r else r * inner
        val p = Offset(center.x + cos(a) * rr, center.y + sin(a) * rr)
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    path.close()
    drawPath(path, color)
}

private fun DrawScope.heart(c: Offset, s: Float, color: Color) {
    val p = Path().apply {
        moveTo(c.x, c.y + s * 0.35f)
        cubicTo(c.x - s, c.y - s * 0.3f, c.x - s * 0.45f, c.y - s, c.x, c.y - s * 0.45f)
        cubicTo(c.x + s * 0.45f, c.y - s, c.x + s, c.y - s * 0.3f, c.x, c.y + s * 0.35f)
        close()
    }
    drawPath(p, color)
}

/** A firework: sparks out from [c], falling a little, fading over [life] (0..1). */
internal fun DrawScope.burst(c: Offset, life: Float, radius: Float, color: Color, sparks: Int = 22) {
    if (life !in 0f..1f) return
    val r = radius * (1 - (1 - life) * (1 - life))
    val alpha = (1 - life).coerceIn(0f, 1f)
    for (i in 0 until sparks) {
        val a = 2 * PI.toFloat() * i / sparks
        val p = Offset(c.x + cos(a) * r, c.y + sin(a) * r + life * life * radius * 0.35f)
        drawCircle(color.copy(alpha = alpha), radius = 3.2f * (1.2f - life), center = p)
    }
}

/** A jagged bolt from [from] down to [to]. */
internal fun DrawScope.bolt(from: Offset, to: Offset, seed: Int, color: Color, width: Float = 4f) {
    val path = Path().apply { moveTo(from.x, from.y) }
    val steps = 7
    for (s in 1..steps) {
        val f = s / steps.toFloat()
        val jitter = if (s == steps) 0f else (rnd(seed * 31 + s) - 0.5f) * 60f
        path.lineTo(from.x + (to.x - from.x) * f + jitter, from.y + (to.y - from.y) * f)
    }
    drawPath(path, color.copy(alpha = color.alpha * 0.35f), style = Stroke(width * 4, cap = StrokeCap.Round))
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round))
}

// ---- Page themes ----

private fun DrawScope.drawTheme(theme: PageTheme, t: Float, k: Float) {
    val w = size.width
    val h = size.height
    when (theme) {
        PageTheme.NONE -> Unit
        PageTheme.THUNDERSTORM -> thunderstorm(t, k)
        PageTheme.SAFARI -> {
            back {
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0x55E07A2E), Color(0x99582812)), startY = h * 0.62f), topLeft = Offset(0f, h * 0.62f), size = Size(w, h * 0.38f))
                drawCircle(Color(0x66FFB45A), radius = h * 0.11f, center = Offset(w * 0.78f, h * 0.84f))
            }
            val ground = h * 0.93f
            mid {
                drawRect(Color(0xCC2A1608), topLeft = Offset(0f, ground), size = Size(w, h - ground))
                acacia(Offset(w * 0.12f, ground), h * 0.16f)
                acacia(Offset(w * 0.9f, ground), h * 0.12f)
                crossing(t, 46f, w, 200f, 1)?.let { x -> giraffe(Offset(x, ground), h * 0.2f, t) }
                crossing(t, 38f, w, 220f, -1, 14f)?.let { x -> elephant(Offset(x, ground), h * 0.11f, t) }
                crossing(t, 30f, w, 120f, 1, 7f)?.let { x -> zebraish(Offset(x, ground), h * 0.07f, t) }
            }
        }
        PageTheme.DEEP_SPACE -> deepSpace(t, k)
        PageTheme.UNDERWATER -> underwater(t, k)
        PageTheme.HAUNTED -> haunted(t, k)
        PageTheme.DESERT -> desert(t, k)
        PageTheme.SNOWY_FOREST -> snowyForest(t, k)
        PageTheme.SUPERHERO_CITY -> heroCity(t, k)
        PageTheme.ALIEN_AGENTS -> visitors(t, k)
        PageTheme.NEON_ARCADE -> arcade(t, k)
        PageTheme.ROSES -> rosesAndThorns(t, k)
        PageTheme.FLOWERS -> flowerGarden(t, k)
        PageTheme.POETRY -> poetry(t, k)
    }
}

// ---- Occasions ----

private fun DrawScope.drawOccasion(o: Occasion, t: Float, k: Float) {
    val w = size.width
    val h = size.height
    when (o) {
        Occasion.WINTER -> realSnow(t, k, 71, layered = true)
        Occasion.CHRISTMAS -> christmas(t, k)
        Occasion.FESTIVUS -> {
            festivusPole(t)
            val c = floor(t / 6f).toInt()
            val life = (t - c * 6f) / 5f
            if (life in 0f..1f) {
                val words = listOf("I got a lot of problems with you people!", "Feats of strength!", "A Festivus for the rest of us!", "The airing of grievances")
                mid { drawWord(words[c.mod(words.size)], Offset(w * 0.5f, h * 0.1f), 30f, Color(0xFFF2F4F7).copy(alpha = (1 - abs(life - 0.5f) * 2).coerceIn(0f, 1f))) }
            }
        }
        Occasion.NEW_YEAR -> newYear(t, k)
        Occasion.VALENTINES -> valentines(t, k)
        Occasion.ST_PATRICKS -> shamrocks(t, k)
        Occasion.ARMY_BIRTHDAY -> {
            val ground = h * 0.95f
            mid { for (j in 0 until 2) crossing(t, 26f, w, 220f, 1, j * 12f)?.let { x -> tank(Offset(x, ground), 1f) } }
            back { shellBursts(t, k, 111, ground) }
        }
        Occasion.INDEPENDENCE_DAY -> {
            back { fireworkShow(t, k, listOf(Color(0xFFFF3B4E), Color(0xFFF4F6FF), Color(0xFF3D6BFF)), 121, floorY = 0.5f) }
        }
        Occasion.AIR_FORCE_BIRTHDAY -> {
            val ground = h * 0.97f
            mid { for (j in 0 until 3) {
                val period = 14f + j * 3
                val phase = j * 4f
                crossing(t, period, w, 160f, 1, phase)?.let { x ->
                    val y = h * (0.1f + j * 0.06f)
                    bomber(Offset(x, y))
                    // Bombs dropped behind it, falling to bursts on the ground.
                    for (b in 0 until 3) {
                        val dropT = frac((t + phase) / period) * period - b * 1.2f - 1f
                        if (dropT in 0f..2.2f) {
                            val bx = x - 60f - b * 70f - dropT * 40f
                            val by = y + 20f + dropT * dropT * (ground - y) / 4.84f
                            if (by < ground - 10f) {
                                drawOval(Color(0xFF15181C), topLeft = Offset(bx - 5f, by - 11f), size = Size(10f, 22f))
                                drawOval(Color(0xAAE0E4E8), topLeft = Offset(bx - 5f, by - 11f), size = Size(10f, 22f), style = Stroke(1.5f))
                            } else fireball(Offset(bx, ground - 10f), (dropT - 2f) / 0.2f, 70f)
                        }
                    }
                }
            } }
            back { shellBursts(t, k, 131, ground) }
        }
        Occasion.NAVY_BIRTHDAY -> {
            val sea = h * 0.93f
            mid {
                drawRect(Color(0x66103050), topLeft = Offset(0f, sea), size = Size(w, h - sea))
                for (j in 0 until 2) crossing(t, 34f, w, 260f, if (j == 0) 1 else -1, j * 15f)?.let { x ->
                    battleship(Offset(x, sea), if (j == 0) 1 else -1)
                    val c = frac(t / 2.5f + j * 0.4f)
                    if (c < 0.25f) drawCircle(Color(0xAAE0E0E0).copy(alpha = 0.6f * (1 - c * 4)), radius = 20f + c * 120f, center = Offset(x + 90f * (if (j == 0) 1 else -1), sea - 60f))
                }
            }
        }
        Occasion.MARINE_CORPS_BIRTHDAY -> {
            // Down range: the nests face us, tracers coming out of the screen.
            val ground = h
            mid { for (n in 0 until 3) {
                val c = Offset(w * (0.2f + n * 0.3f), ground)
                drawArc(Color(0xEE6B5A3C), 180f, 180f, true, topLeft = Offset(c.x - 150f, c.y - 80f), size = Size(300f, 160f))
                for (s in 0 until 6) drawOval(Color(0xFFA08C64), topLeft = Offset(c.x - 150f + s * 50f, c.y - 92f), size = Size(54f, 26f))
                for (s in 0 until 5) drawOval(Color(0xFF8C7852), topLeft = Offset(c.x - 125f + s * 50f, c.y - 112f), size = Size(54f, 26f))
                // The barrel pointing straight at us, and the gunner's helmet behind it.
                drawArc(Color(0xFF3B4A2C), 180f, 180f, true, topLeft = Offset(c.x + 30f, c.y - 150f), size = Size(70f, 60f))
                drawCircle(Color(0xFF2A2A2A), 20f, Offset(c.x, c.y - 112f))
                drawCircle(Color(0xFF050505), 10f, Offset(c.x, c.y - 112f))
                val fire = frac(t * 6f + n * 0.33f) < 0.3f
                if (fire) star(Offset(c.x, c.y - 112f), 46f, Color(0xFFFFC107), points = 7, inner = 0.4f, rotation = t * 10)
                for (r in 0 until count(5, k)) {
                    val p = frac(t * 1.6f + r * 0.2f + n * 0.13f)
                    val dir = Offset((rnd(r + n * 7, 141) - 0.5f) * w * 0.9f, -h * 0.9f)
                    val a = Offset(c.x + dir.x * p, c.y - 112f + dir.y * p)
                    val b = Offset(c.x + dir.x * (p + 0.08f), c.y - 112f + dir.y * (p + 0.08f))
                    drawLine(Color(0xFFFFB300).copy(alpha = 0.9f), a, b, strokeWidth = 2f + p * 7f, cap = StrokeCap.Round)
                }
            } }
        }
        Occasion.VETERANS_DAY -> {
            back {
                waveFlag(Offset(30f, 30f), 220f, 120f, t)
                waveFlag(Offset(w - 250f, 30f), 220f, 120f, t + 1f)
            }
            mid { fallers(count(20, k), t, 40f, 151) { p, r, _ -> star(p, 6f + r * 6f, Color(0x99FFFFFF)) } }
        }
        Occasion.THANKSGIVING -> autumn(t, k)
        Occasion.LABOR_DAY -> laborDay(t, k)
        Occasion.EASTER -> {
            val ground = h
            mid {
                for (b in 0 until 3) crossing(t, 16f + b * 4, w, 60f, if (b % 2 == 0) 1 else -1, b * 5f)?.let { x ->
                    val hop = abs(sin(t * 3.2f + b)) * 60f
                    rabbit(Offset(x, ground - hop), 30f, if (b % 2 == 0) 1 else -1)
                }
                val eggs = listOf(Color(0xCCF8BBD0), Color(0xCCB3E5FC), Color(0xCCFFF59D), Color(0xCCC8E6C9))
                for (e in 0 until 9) drawOval(eggs[e % 4], topLeft = Offset(w * (e + 0.5f) / 9f - 14f, ground - 40f), size = Size(28f, 38f))
            }
        }
        Occasion.HALLOWEEN -> halloween(t, k)
    }
}

// ---- Drawings ----

private fun DrawScope.snow(t: Float, k: Float, salt: Int) = realSnow(t, k, salt)

private fun DrawScope.fireworks(t: Float, k: Float, salt: Int, w: Float, maxY: Float) =
    fireworkShow(t, k, listOf(Color(0xFFFF4D5E), Color(0xFFF4F6FF), Color(0xFF4D7CFF), Color(0xFFFFC94D), Color(0xFF5CFFB0), Color(0xFFD06BFF)), salt, floorY = maxY / size.height)

private fun DrawScope.shellBursts(t: Float, k: Float, salt: Int, ground: Float) {
    for (s in 0 until count(3, k)) {
        val period = 3.2f + s
        val cycle = floor((t + s) / period).toInt()
        val life = ((t + s) - cycle * period) / 0.8f
        val c = Offset(size.width * (0.1f + rnd(cycle * 5 + s, salt) * 0.8f), ground - 20f)
        fireball(c, life, 60f)
    }
}

/** An explosion on the ground: a fireball with a hot core, sparks, then smoke rising. */
private fun DrawScope.fireball(c: Offset, life: Float, radius: Float) {
    if (life !in 0f..1f) return
    val grow = 1 - (1 - life) * (1 - life)
    drawCircle(Color(0x88555555).copy(alpha = 0.55f * (1 - life)), radius = radius * (0.5f + life), center = c - Offset(0f, life * radius))
    drawCircle(Color(0xFFFF6D00).copy(alpha = 1 - life), radius = radius * 0.7f * grow, center = c)
    drawCircle(Color(0xFFFFD54F).copy(alpha = (1 - life * 1.5f).coerceIn(0f, 1f)), radius = radius * 0.4f * grow, center = c)
    burst(c, life, radius * 1.3f, Color(0xFFFFAB40), 14)
}

private fun DrawScope.drawWord(text: String, center: Offset, sizePx: Float, color: Color) {
    drawContext.canvas.nativeCanvas.apply {
        val paint = android.graphics.Paint().apply {
            this.color = android.graphics.Color.argb((color.alpha * 255).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
            textSize = sizePx
            isFakeBoldText = true
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            setShadowLayer(6f, 0f, 2f, android.graphics.Color.BLACK)
        }
        drawText(text, center.x, center.y + sizePx / 3, paint)
    }
}

private val Silhouette = Color(0xE6140C06)

private fun DrawScope.acacia(base: Offset, hgt: Float) {
    drawLine(Silhouette, base, base - Offset(0f, hgt * 0.7f), strokeWidth = hgt * 0.06f)
    drawOval(Silhouette, topLeft = base - Offset(hgt * 0.6f, hgt), size = Size(hgt * 1.2f, hgt * 0.28f))
}

private fun DrawScope.giraffe(foot: Offset, hgt: Float, t: Float) {
    val body = Offset(foot.x, foot.y - hgt * 0.45f)
    drawOval(Silhouette, topLeft = body - Offset(hgt * 0.22f, hgt * 0.08f), size = Size(hgt * 0.44f, hgt * 0.2f))
    for (l in 0 until 4) {
        val lx = body.x - hgt * 0.17f + l * hgt * 0.11f + sin(t * 4 + l) * 4f
        drawLine(Silhouette, Offset(lx, body.y + hgt * 0.05f), Offset(lx, foot.y), strokeWidth = hgt * 0.035f)
    }
    drawLine(Silhouette, body + Offset(hgt * 0.16f, 0f), body + Offset(hgt * 0.3f, -hgt * 0.5f), strokeWidth = hgt * 0.06f)
    drawOval(Silhouette, topLeft = body + Offset(hgt * 0.26f, -hgt * 0.56f), size = Size(hgt * 0.14f, hgt * 0.07f))
}

private fun DrawScope.elephant(foot: Offset, hgt: Float, t: Float) {
    val body = Offset(foot.x, foot.y - hgt * 0.75f)
    drawOval(Silhouette, topLeft = body - Offset(hgt * 0.7f, hgt * 0.45f), size = Size(hgt * 1.4f, hgt * 0.9f))
    drawCircle(Silhouette, hgt * 0.32f, body + Offset(-hgt * 0.7f, -hgt * 0.15f))
    drawLine(Silhouette, body + Offset(-hgt * 0.95f, 0f), body + Offset(-hgt * 1.0f, hgt * 0.6f + sin(t * 2) * 6f), strokeWidth = hgt * 0.12f, cap = StrokeCap.Round)
    for (l in 0 until 4) {
        val lx = body.x - hgt * 0.5f + l * hgt * 0.33f
        drawLine(Silhouette, Offset(lx, body.y + hgt * 0.3f), Offset(lx + sin(t * 3 + l) * 4f, foot.y), strokeWidth = hgt * 0.18f)
    }
}

private fun DrawScope.zebraish(foot: Offset, hgt: Float, t: Float) {
    val body = Offset(foot.x, foot.y - hgt * 0.9f)
    drawOval(Silhouette, topLeft = body - Offset(hgt * 0.6f, hgt * 0.3f), size = Size(hgt * 1.2f, hgt * 0.6f))
    drawLine(Silhouette, body + Offset(hgt * 0.5f, 0f), body + Offset(hgt * 0.8f, -hgt * 0.5f), strokeWidth = hgt * 0.2f)
    for (l in 0 until 4) {
        val lx = body.x - hgt * 0.45f + l * hgt * 0.3f + sin(t * 8 + l) * 5f
        drawLine(Silhouette, Offset(lx, body.y + hgt * 0.2f), Offset(lx, foot.y), strokeWidth = hgt * 0.1f)
    }
}

private fun DrawScope.fish(c: Offset, len: Float, dir: Int) {
    val color = Color(0x88214A6B)
    drawOval(color, topLeft = c - Offset(len / 2, len / 4), size = Size(len, len / 2))
    val tail = Path().apply {
        val back = c.x - dir * len / 2
        moveTo(back, c.y); lineTo(back - dir * len / 3, c.y - len / 4); lineTo(back - dir * len / 3, c.y + len / 4); close()
    }
    drawPath(tail, color)
}

private fun DrawScope.bat(c: Offset, s: Float, phase: Float) {
    val flap = sin(phase) * s * 0.6f
    val p = Path().apply {
        moveTo(c.x, c.y)
        quadraticBezierTo(c.x - s, c.y - s - flap, c.x - s * 2, c.y + flap * 0.2f)
        quadraticBezierTo(c.x - s, c.y - s * 0.2f, c.x, c.y + s * 0.3f)
        quadraticBezierTo(c.x + s, c.y - s * 0.2f, c.x + s * 2, c.y + flap * 0.2f)
        quadraticBezierTo(c.x + s, c.y - s - flap, c.x, c.y)
        close()
    }
    drawPath(p, Color(0xEE0D0D12))
}

private fun DrawScope.pine(base: Offset, hgt: Float) {
    val p = Path().apply {
        moveTo(base.x, base.y - hgt)
        lineTo(base.x - hgt * 0.32f, base.y - hgt * 0.1f)
        lineTo(base.x + hgt * 0.32f, base.y - hgt * 0.1f)
        close()
    }
    drawPath(p, Color(0xDD0E2418))
    drawPath(p, Color(0x66FFFFFF), style = Stroke(1f))
    drawRect(Color(0xDD2B1A10), topLeft = Offset(base.x - hgt * 0.03f, base.y - hgt * 0.1f), size = Size(hgt * 0.06f, hgt * 0.1f))
}

private fun DrawScope.skyline(top: Float, color: Color) {
    var x = 0f
    var i = 0
    while (x < size.width) {
        val bw = 50f + rnd(i, 63) * 90f
        val bh = (size.height - top) * (0.4f + rnd(i, 64) * 0.9f)
        drawRect(color, topLeft = Offset(x, size.height - bh), size = Size(bw, bh))
        for (wy in 0 until (bh / 26f).toInt()) for (wx in 0 until (bw / 20f).toInt()) {
            if (rnd(i * 97 + wy * 13 + wx, 65) > 0.6f) drawRect(Color(0x88F2C14E), topLeft = Offset(x + 6f + wx * 20f, size.height - bh + 8f + wy * 26f), size = Size(8f, 10f))
        }
        x += bw + 6f
        i++
    }
}

private fun DrawScope.caped(c: Offset, s: Float, t: Float) {
    val cape = Path().apply {
        moveTo(c.x, c.y)
        lineTo(c.x - s * 2.4f, c.y - s * 0.3f + sin(t * 8) * s * 0.3f)
        lineTo(c.x - s * 2.2f, c.y + s * 0.5f + sin(t * 8 + 1) * s * 0.3f)
        close()
    }
    drawPath(cape, Color(0xDDD62828))
    drawLine(Color(0xEE1B2A6B), c - Offset(s, 0f), c + Offset(s * 0.8f, 0f), strokeWidth = s * 0.4f, cap = StrokeCap.Round)
    drawCircle(Color(0xEEF1C27D), s * 0.25f, c + Offset(s * 1.05f, 0f))
}

private fun DrawScope.agent(foot: Offset, hgt: Float) {
    val suit = Color(0xF0050506)
    drawRect(suit, topLeft = Offset(foot.x - hgt * 0.16f, foot.y - hgt * 0.7f), size = Size(hgt * 0.32f, hgt * 0.7f))
    drawCircle(Color(0xF02A2420), hgt * 0.1f, Offset(foot.x, foot.y - hgt * 0.8f))
    drawRect(Color(0xFF000000), topLeft = Offset(foot.x - hgt * 0.09f, foot.y - hgt * 0.83f), size = Size(hgt * 0.18f, hgt * 0.035f))
    val shirt = Path().apply { moveTo(foot.x - hgt * 0.05f, foot.y - hgt * 0.7f); lineTo(foot.x + hgt * 0.05f, foot.y - hgt * 0.7f); lineTo(foot.x, foot.y - hgt * 0.55f); close() }
    drawPath(shirt, Color(0xFFF2F2F2))
}

private fun DrawScope.sleigh(c: Offset) {
    val dark = Color(0xEE140C06)
    for (r in 0 until 3) {
        val rx = c.x + 70f + r * 50f
        drawOval(dark, topLeft = Offset(rx - 18f, c.y - 8f), size = Size(36f, 16f))
        drawLine(dark, Offset(rx + 12f, c.y - 4f), Offset(rx + 20f, c.y - 20f), 4f)
    }
    drawRoundRect(dark, topLeft = Offset(c.x - 50f, c.y - 18f), size = Size(80f, 26f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f))
    drawLine(dark, Offset(c.x - 60f, c.y + 12f), Offset(c.x + 40f, c.y + 12f), 3f)
}

private fun DrawScope.tank(foot: Offset, scale: Float) {
    val c = Color(0xF0283018)
    drawRoundRect(c, topLeft = Offset(foot.x - 90f * scale, foot.y - 30f * scale), size = Size(180f * scale, 30f * scale), cornerRadius = androidx.compose.ui.geometry.CornerRadius(14f))
    drawRect(c, topLeft = Offset(foot.x - 60f * scale, foot.y - 55f * scale), size = Size(110f * scale, 26f * scale))
    drawRect(c, topLeft = Offset(foot.x - 30f * scale, foot.y - 72f * scale), size = Size(60f * scale, 20f * scale))
    drawLine(c, Offset(foot.x + 30f * scale, foot.y - 62f * scale), Offset(foot.x + 120f * scale, foot.y - 66f * scale), 8f * scale)
}

private fun DrawScope.bomber(c: Offset) {
    val body = Color(0xF0A7B0BA)
    drawOval(body, topLeft = c - Offset(70f, 10f), size = Size(140f, 20f))
    val wing = Path().apply { moveTo(c.x - 10f, c.y); lineTo(c.x - 50f, c.y - 50f); lineTo(c.x - 25f, c.y - 50f); lineTo(c.x + 20f, c.y); close() }
    drawPath(wing, body)
    val wing2 = Path().apply { moveTo(c.x - 10f, c.y); lineTo(c.x - 50f, c.y + 50f); lineTo(c.x - 25f, c.y + 50f); lineTo(c.x + 20f, c.y); close() }
    drawPath(wing2, body)
    drawLine(Color(0x33FFFFFF), c - Offset(75f, 0f), c - Offset(260f, 0f), 6f)
}

private fun DrawScope.battleship(water: Offset, dir: Int) {
    val c = Color(0xF0404850)
    val hull = Path().apply {
        moveTo(water.x - 130f, water.y - 24f); lineTo(water.x + 130f * dir.coerceAtLeast(1) + (if (dir < 0) 0f else 0f), water.y - 24f)
        lineTo(water.x + 110f, water.y); lineTo(water.x - 110f, water.y); close()
    }
    drawPath(hull, c)
    drawRect(c, topLeft = Offset(water.x - 40f, water.y - 56f), size = Size(80f, 32f))
    drawRect(c, topLeft = Offset(water.x - 10f, water.y - 86f), size = Size(20f, 30f))
    drawLine(c, Offset(water.x + 40f * dir, water.y - 44f), Offset(water.x + 100f * dir, water.y - 52f), 6f)
}

private fun DrawScope.stripes(at: Offset, w: Float, hgt: Float, t: Float) {
    for (s in 0 until 7) {
        val color = if (s % 2 == 0) Color(0x66B22234) else Color(0x55FFFFFF)
        val path = Path().apply {
            moveTo(0f, at.y + s * hgt / 7)
            var x = 0f
            while (x <= w) { lineTo(x, at.y + s * hgt / 7 + sin(x / 120f + t * 2) * 4f); x += 20f }
            x = w
            while (x >= 0f) { lineTo(x, at.y + (s + 1) * hgt / 7 + sin(x / 120f + t * 2) * 4f); x -= 20f }
            close()
        }
        drawPath(path, color)
    }
}

private fun DrawScope.waveFlag(topLeft: Offset, fw: Float, fh: Float, t: Float) {
    val stripeH = fh / 13
    for (s in 0 until 13) {
        val color = if (s % 2 == 0) Color(0xDDB22234) else Color(0xDDFFFFFF)
        val path = Path().apply {
            var x = 0f
            moveTo(topLeft.x, topLeft.y + s * stripeH)
            while (x <= fw) { lineTo(topLeft.x + x, topLeft.y + s * stripeH + sin(x / 40f + t * 3) * x / fw * 8f); x += 10f }
            x = fw
            while (x >= 0f) { lineTo(topLeft.x + x, topLeft.y + (s + 1) * stripeH + sin(x / 40f + t * 3) * x / fw * 8f); x -= 10f }
            close()
        }
        drawPath(path, color)
    }
    drawRect(Color(0xDD3C3B6E), topLeft = topLeft, size = Size(fw * 0.4f, stripeH * 7))
    for (r in 0 until 4) for (q in 0 until 5) star(Offset(topLeft.x + 10f + q * fw * 0.075f, topLeft.y + 8f + r * stripeH * 1.6f), 3.5f, Color.White)
    drawLine(Color(0xDD8A8A8A), topLeft, topLeft + Offset(0f, fh + 60f), 4f)
}

private fun DrawScope.banner(top: Offset, bw: Float, bh: Float, t: Float) {
    val red = Color(0xDDC8102E)
    val path = Path().apply {
        moveTo(top.x, top.y)
        lineTo(top.x + bw, top.y)
        lineTo(top.x + bw + sin(t * 1.6f) * 6f, top.y + bh)
        lineTo(top.x + bw / 2, top.y + bh - 24f)
        lineTo(top.x + sin(t * 1.6f + 1) * 6f, top.y + bh)
        close()
    }
    drawPath(path, red)
    hammerAndSickle(Offset(top.x + bw / 2, top.y + bh * 0.5f), bw * 0.28f, Color(0xFFFFD700))
    star(Offset(top.x + bw / 2, top.y + bh * 0.17f), bw * 0.1f, Color(0xFFFFD700))
}

private fun DrawScope.hammerAndSickle(c: Offset, s: Float, color: Color) {
    // The sickle: an open arc with a short handle.
    drawArc(color, startAngle = 200f, sweepAngle = 250f, useCenter = false, topLeft = Offset(c.x - s, c.y - s), size = Size(s * 2, s * 2), style = Stroke(s * 0.22f, cap = StrokeCap.Round))
    drawLine(color, Offset(c.x - s * 0.55f, c.y + s * 0.75f), Offset(c.x - s * 0.95f, c.y + s * 1.15f), s * 0.24f, cap = StrokeCap.Round)
    // The hammer, crossed over it.
    withTransform({ rotate(-45f, c) }) {
        drawLine(color, Offset(c.x, c.y - s * 0.6f), Offset(c.x, c.y + s * 1.2f), s * 0.2f, cap = StrokeCap.Round)
        drawRect(color, topLeft = Offset(c.x - s * 0.45f, c.y - s * 0.85f), size = Size(s * 0.9f, s * 0.35f))
    }
}

private fun DrawScope.marcher(foot: Offset, hgt: Float, phase: Float, flag: Boolean) {
    val c = Color(0xE6C9B79C)
    val step = sin(phase) * hgt * 0.12f
    drawLine(c, Offset(foot.x, foot.y - hgt * 0.45f), Offset(foot.x + step, foot.y), hgt * 0.08f)
    drawLine(c, Offset(foot.x, foot.y - hgt * 0.45f), Offset(foot.x - step, foot.y), hgt * 0.08f)
    drawLine(c, Offset(foot.x, foot.y - hgt * 0.45f), Offset(foot.x, foot.y - hgt * 0.85f), hgt * 0.12f)
    drawCircle(c, hgt * 0.08f, Offset(foot.x, foot.y - hgt * 0.95f))
    if (flag) {
        drawLine(c, Offset(foot.x + hgt * 0.1f, foot.y - hgt * 0.7f), Offset(foot.x + hgt * 0.1f, foot.y - hgt * 1.6f), 3f)
        drawRect(Color(0xEEC8102E), topLeft = Offset(foot.x + hgt * 0.1f, foot.y - hgt * 1.6f), size = Size(hgt * 0.5f, hgt * 0.3f))
    }
}

private fun DrawScope.rabbit(foot: Offset, s: Float, dir: Int) {
    val c = Color(0xEEF5F0E8)
    drawOval(c, topLeft = foot - Offset(s, s * 1.2f), size = Size(s * 2f, s * 1.2f))
    val head = foot + Offset(s * 1.0f * dir, -s * 1.3f)
    drawCircle(c, s * 0.5f, head)
    drawOval(c, topLeft = head + Offset(-s * 0.25f, -s * 1.3f), size = Size(s * 0.24f, s * 0.95f))
    drawOval(c, topLeft = head + Offset(s * 0.05f, -s * 1.25f), size = Size(s * 0.24f, s * 0.9f))
    drawCircle(Color.White, s * 0.3f, foot + Offset(-s * 1.0f * dir, -s * 0.8f))
}

private fun DrawScope.pumpkin(c: Offset, s: Float, t: Float) {
    for (lobe in -1..1) drawOval(Color(0xEEE65100), topLeft = Offset(c.x + lobe * s * 0.5f - s * 0.6f, c.y - s), size = Size(s * 1.2f, s * 1.6f))
    drawRect(Color(0xEE4E342E), topLeft = Offset(c.x - s * 0.08f, c.y - s * 1.25f), size = Size(s * 0.16f, s * 0.3f))
    val glow = Color(0xFFFFD54F).copy(alpha = 0.7f + 0.3f * sin(t * 5))
    star(Offset(c.x - s * 0.35f, c.y - s * 0.3f), s * 0.18f, glow, points = 3, inner = 0.5f)
    star(Offset(c.x + s * 0.35f, c.y - s * 0.3f), s * 0.18f, glow, points = 3, inner = 0.5f)
    drawArc(glow, 20f, 140f, true, topLeft = Offset(c.x - s * 0.5f, c.y - s * 0.4f), size = Size(s, s * 0.6f))
}


internal fun DrawScope.drawThemeForTest(theme: PageTheme, t: Float, k: Float, depths: Set<Depth>? = null) = Pass.with(depths) { drawTheme(theme, t, k) }
internal fun DrawScope.drawOccasionForTest(o: Occasion, t: Float, k: Float, depths: Set<Depth>? = null) = Pass.with(depths) { drawOccasion(o, t, k) }
