package dev.nitrate.tv.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.ImageKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.tv.ui.components.LocalDisplayPrefs
import dev.nitrate.tv.ui.components.MediaCard
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** How many titles fly at once: enough to be lively, few enough to pick one out. */
const val BOUNCE_MAX = 16

/** One poster's place and speed in the box (px, px per second). */
private class Flyer(var x: Float, var y: Float, var vx: Float, var vy: Float)

/**
 * The library as posters flying round a box: each drifts in its own direction, bouncing off the
 * walls and off each other. The one with focus stops still and grows; while focus is in the box the
 * rest slow down, so one can be caught. ◀▶▲▼ go to the nearest poster that way (they're laid out
 * where they're drawn, so the TV's own focus search finds them), OK opens, ▶ plays. With "Reduce
 * motion" they barely drift. [titleOf] gives each card's name (ticks while selecting).
 */
@Composable
fun BounceBox(
    items: List<MediaItem>,
    total: Int,
    repository: MediaServer,
    music: Boolean,
    titleOf: (MediaItem) -> String,
    onOpen: (MediaItem) -> Unit,
    onPlay: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shown = items.take(BOUNCE_MAX)
    val calm = LocalDisplayPrefs.current.effects.reduceMotion
    BoxWithConstraints(
        modifier
            .background(Brush.radialGradient(listOf(Color(0x22FFFFFF), Color(0x08FFFFFF))), RoundedCornerShape(18.dp))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(18.dp)),
    ) {
        val density = LocalDensity.current
        // Small enough that the box is half empty: room to fly.
        val cardW = if (music) 84.dp else 72.dp
        val cardH = (if (music) 84.dp else 108.dp) + 46.dp // poster plus its caption
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val cw = with(density) { cardW.toPx() }
        val ch = with(density) { cardH.toPx() }
        val pad = with(density) { 10.dp.toPx() }
        // Each title starts somewhere of its own and sets off its own way (the same each time).
        val flyers = remember(shown.map { it.id }, wPx, hPx) {
            // Spread over a loose grid to start (nothing on top of anything), each nudged within its
            // cell and sent off its own way.
            val cols = ((wPx - 2 * pad) / (cw * 1.25f)).toInt().coerceAtLeast(1)
            val rows = (shown.size + cols - 1) / cols
            val cellW = (wPx - 2 * pad) / cols
            val cellH = (hPx - 2 * pad) / rows.coerceAtLeast(1)
            shown.mapIndexed { i, item ->
                val r = Random(item.id.hashCode())
                val speed = with(density) { (55 + r.nextInt(70)).dp.toPx() }
                val a = r.nextDouble(0.0, 2 * Math.PI)
                Flyer(
                    x = pad + (i % cols) * cellW + r.nextFloat() * (cellW - cw).coerceAtLeast(0f),
                    y = pad + (i / cols) * cellH + r.nextFloat() * (cellH - ch).coerceAtLeast(0f),
                    vx = (cos(a) * speed).toFloat(), vy = (sin(a) * speed).toFloat(),
                ).also { keepIn(it, wPx, hPx, cw, ch, pad) }
            }
        }
        var focusedIndex by remember { mutableStateOf(-1) }
        // One tick a frame: the posters read it where they're placed, so nothing recomposes.
        var tick by remember { mutableLongStateOf(0L) }
        LaunchedEffect(flyers, calm) {
            // Stepped on a ~60 Hz timer (as the page effects are), by the time that really passed.
            var last = System.nanoTime()
            while (true) {
                kotlinx.coroutines.delay(16)
                val now = System.nanoTime()
                val dt = ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                step(flyers, dt * when { calm -> 0.15f; focusedIndex >= 0 -> 0.35f; else -> 1f }, focusedIndex, wPx, hPx, cw, ch, pad)
                tick = now
            }
        }
        shown.forEachIndexed { i, item ->
            val f = flyers.getOrNull(i) ?: return@forEachIndexed
            val focused = i == focusedIndex
            Box(
                Modifier
                    .offset { tick.let { IntOffset(f.x.roundToInt(), f.y.roundToInt()) } }
                    .zIndex(if (focused) 1f else 0f)
                    .width(cardW)
                    .onFocusChanged { if (it.hasFocus) focusedIndex = i else if (focusedIndex == i) focusedIndex = -1 },
            ) {
                MediaCard(
                    item = item,
                    title = titleOf(item),
                    imageUrl = repository.imageUrl(item, ImageKind.PRIMARY, 240),
                    width = cardW,
                    aspectRatio = if (music) 1f else 2f / 3f,
                    onClick = { onOpen(item) },
                    onPlay = { if (item.isPlayable) onPlay(item) else onOpen(item) },
                )
            }
        }
        if (total > shown.size) {
            Text(
                "${shown.size} of $total in the box · sort or filter to change which",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            )
        }
    }
}

/**
 * Moves every poster on by [dt] seconds: off the walls they bounce back, and two that meet trade
 * speeds along the way they met (equal weights, so it looks right). The [held] one stays put, and
 * others bounce off it as off a wall.
 */
private fun step(flyers: List<Flyer>, dt: Float, held: Int, w: Float, h: Float, cw: Float, ch: Float, pad: Float) {
    if (dt <= 0f) return
    flyers.forEachIndexed { i, f ->
        if (i == held) return@forEachIndexed
        f.x += f.vx * dt
        f.y += f.vy * dt
        keepIn(f, w, h, cw, ch, pad)
    }
    for (i in flyers.indices) for (j in i + 1 until flyers.size) {
        val a = flyers[i]
        val b = flyers[j]
        val ox = minOf(a.x + cw, b.x + cw) - maxOf(a.x, b.x)
        val oy = minOf(a.y + ch, b.y + ch) - maxOf(a.y, b.y)
        if (ox <= 0f || oy <= 0f) continue
        val aHeld = i == held
        val bHeld = j == held
        if (ox < oy) {
            // Met side to side: part them and trade sideways speeds (a held one is a wall).
            val dir = if (a.x < b.x) -1f else 1f
            when {
                aHeld -> { b.x -= dir * ox; b.vx = -dir * abs(b.vx) }
                bHeld -> { a.x += dir * ox; a.vx = dir * abs(a.vx) }
                else -> { a.x += dir * ox / 2; b.x -= dir * ox / 2; val t = a.vx; a.vx = b.vx; b.vx = t }
            }
        } else {
            val dir = if (a.y < b.y) -1f else 1f
            when {
                aHeld -> { b.y -= dir * oy; b.vy = -dir * abs(b.vy) }
                bHeld -> { a.y += dir * oy; a.vy = dir * abs(a.vy) }
                else -> { a.y += dir * oy / 2; b.y -= dir * oy / 2; val t = a.vy; a.vy = b.vy; b.vy = t }
            }
        }
    }
    // Pushed apart against a wall: back inside it (never out of the box).
    flyers.forEach { keepIn(it, w, h, cw, ch, pad) }
}

/** Inside the walls, heading away from any it touches. */
private fun keepIn(f: Flyer, w: Float, h: Float, cw: Float, ch: Float, pad: Float) {
    if (f.x < pad) { f.x = pad; f.vx = abs(f.vx) }
    if (f.x > w - cw - pad) { f.x = (w - cw - pad).coerceAtLeast(pad); f.vx = -abs(f.vx) }
    if (f.y < pad) { f.y = pad; f.vy = abs(f.vy) }
    if (f.y > h - ch - pad) { f.y = (h - ch - pad).coerceAtLeast(pad); f.vy = -abs(f.vy) }
}
