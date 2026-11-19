package dev.nitrate.tv.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.OutlinedButton
import coil3.compose.AsyncImage
import dev.nitrate.core.pages.Browse
import dev.nitrate.core.pages.hidesDescription
import dev.nitrate.tv.ui.components.ItemMetaLine
import kotlinx.coroutines.launch
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

/** How many titles fly at once: small posters, so plenty to pick from. */
const val BOUNCE_MAX = 50

/** One poster's place and speed in the box (px, px per second). */
private class Flyer(var x: Float, var y: Float, var vx: Float, var vy: Float)

/**
 * The library as small posters flying round a box: each drifts its own way, bouncing off the walls
 * and each other. The one with focus stops still and is shown large beside the box with its details;
 * while focus is in the box the rest slow down, so one can be caught. ◀▶▲▼ go to the nearest poster
 * that way (they're laid out where they're drawn, so the TV's focus search finds them); on past the
 * right-hand ones is the A–Z, and a letter fills the box with that letter's titles. Above the box:
 * the previous and next set of [BOUNCE_MAX] (of everything, or of the letter) and a fresh random set.
 * OK opens, ▶ plays. With "Reduce motion" they barely drift.
 */
@Composable
fun BounceBox(args: ShowArgs, modifier: Modifier = Modifier) {
    val items = args.items
    var letter by rememberSaveable { mutableStateOf<String?>(null) }
    var start by rememberSaveable { mutableIntStateOf(0) }
    // A random set (by id) in place of the next in order, until the next/previous set is asked for.
    var picked by rememberSaveable { mutableStateOf<List<String>?>(null) }
    val pool = remember(items, letter) { letter?.let { l -> items.filter { Browse.letterOf(it) == l } } ?: items }
    val byId = remember(items) { items.associateBy { it.id } }
    val shown = picked?.mapNotNull { byId[it] } ?: pool.drop(start).take(BOUNCE_MAX)
    // Titles still to come for this set (or letter): asked for from the server as they're needed.
    LaunchedEffect(start, pool.size, letter, items.size) {
        val lastLetter = items.lastOrNull()?.let(Browse::letterOf)
        val more = if (letter == null) start + BOUNCE_MAX * 2 > pool.size else lastLetter == letter
        if (more && (args.total < 0 || items.size < args.total)) args.onNeedMore()
    }
    val scope = rememberCoroutineScope()
    var focusedIndex by remember { mutableStateOf(-1) }
    val featured = shown.getOrNull(focusedIndex) ?: shown.firstOrNull()
    val calm = LocalDisplayPrefs.current.effects.reduceMotion
    val total = if (letter == null && args.total >= 0) args.total else pool.size
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { picked = null; start = (start - BOUNCE_MAX).coerceAtLeast(0) }, enabled = picked != null || start > 0) { Text("◀ Previous ${BOUNCE_MAX}") }
            OutlinedButton(onClick = { picked = pool.shuffled().take(BOUNCE_MAX).map { it.id } }) { Text("New set") }
            OutlinedButton(onClick = { picked = null; if (start + BOUNCE_MAX < pool.size) start += BOUNCE_MAX }, enabled = picked != null || start + BOUNCE_MAX < pool.size) { Text("Next ${BOUNCE_MAX} ▶") }
            if (letter != null) OutlinedButton(onClick = { letter = null; picked = null; start = 0 }) { Text("All letters") }
            Text(
                when {
                    picked != null -> "A random ${shown.size}" + (letter?.let { " from $it" } ?: "")
                    shown.isEmpty() -> "Nothing here"
                    else -> (letter?.let { "$it · " } ?: "") + "${start + 1}–${start + shown.size} of $total"
                },
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Flyers(shown, args, calm, onFocused = { focusedIndex = it }, modifier = Modifier.weight(1f).fillMaxHeight())
            // On past the box: the A–Z (only when sorted by name).
            args.letterAt?.let { at ->
                ShowLetters(args, vertical = true, onJump = { _ -> }, modifier = Modifier, onLetter = { l ->
                    scope.launch { at(l); letter = l; picked = null; start = 0 }
                })
            }
            featured?.let { Featured(it, args, Modifier.width(300.dp).fillMaxHeight()) }
        }
    }
}

/** The chosen (or first) title large beside the box, with its details. */
@Composable
private fun Featured(item: MediaItem, args: ShowArgs, modifier: Modifier) {
    Column(modifier.background(Color(0x99080A10), RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // As large as the room left by the text allows, at the poster's own shape.
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.fillMaxHeight().aspectRatio(if (args.music) 1f else 2f / 3f, matchHeightConstraintsFirst = true).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1E2230))) {
                AsyncImage(args.repository.imageUrl(item, ImageKind.PRIMARY, 480), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Text(args.titleOf(item), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        ItemMetaLine(item)
        val prefs = LocalDisplayPrefs.current
        if (prefs.overview && !prefs.hidesDescription(item)) item.overview?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The box itself, its posters flying. */
@Composable
private fun Flyers(shown: List<MediaItem>, args: ShowArgs, calm: Boolean, onFocused: (Int) -> Unit, modifier: Modifier) {
    BoxWithConstraints(
        modifier
            .background(Brush.radialGradient(listOf(Color(0x22FFFFFF), Color(0x08FFFFFF))), RoundedCornerShape(18.dp))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(18.dp)),
    ) {
        val density = LocalDensity.current
        // Small enough for fifty with room to fly.
        val cardW = if (args.music) 46.dp else 40.dp
        val cardH = if (args.music) 46.dp else 60.dp
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val cw = with(density) { cardW.toPx() }
        val ch = with(density) { cardH.toPx() }
        val pad = with(density) { 8.dp.toPx() }
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
                val speed = with(density) { (40 + r.nextInt(60)).dp.toPx() }
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
                    .onFocusChanged {
                        if (it.hasFocus) { focusedIndex = i; onFocused(i) }
                        else if (focusedIndex == i) { focusedIndex = -1; onFocused(-1) }
                    },
            ) {
                MediaCard(
                    item = item,
                    title = null,
                    imageUrl = args.repository.imageUrl(item, ImageKind.PRIMARY, 160),
                    width = cardW,
                    aspectRatio = if (args.music) 1f else 2f / 3f,
                    onClick = { args.onOpen(item) },
                    onPlay = { if (item.isPlayable) args.onPlay(item) else args.onOpen(item) },
                    showProgress = false,
                    modifier = Modifier.semantics { contentDescription = args.titleOf(item) },
                )
            }
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
