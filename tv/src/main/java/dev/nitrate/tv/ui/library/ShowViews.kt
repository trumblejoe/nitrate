package dev.nitrate.tv.ui.library

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyHorizontalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.ImageKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.pages.BrowseView
import dev.nitrate.core.pages.hidesDescription
import dev.nitrate.tv.ui.components.ItemMetaLine
import dev.nitrate.tv.ui.components.LocalDisplayPrefs
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** What every show view gets: the titles loaded so far, how to draw and open them, and more on demand. */
class ShowArgs(
    val items: List<MediaItem>,
    val repository: MediaServer,
    val music: Boolean,
    val titleOf: (MediaItem) -> String,
    val onOpen: (MediaItem) -> Unit,
    val onPlay: (MediaItem) -> Unit,
    val onNeedMore: () -> Unit,
)

/** The All tab's show views (everything but the plain grids and list), filling the space under the bar. */
@Composable
fun ShowView(view: BrowseView, args: ShowArgs, modifier: Modifier = Modifier) {
    when (view) {
        BrowseView.COVERFLOW -> CoverFlow(args, modifier)
        BrowseView.SHELF -> Bookshelf(args, modifier)
        BrowseView.TIMELINE -> Timeline(args, modifier)
        BrowseView.WALL -> WallOfArt(args, modifier)
        BrowseView.ROLODEX -> Rolodex(args, modifier)
        BrowseView.STARFIELD -> Starfield(args, modifier)
        BrowseView.DECK -> Deck(args, modifier)
        else -> Unit
    }
}

// ---- Shared pieces ----

private val Card = Color(0xFF1E2230)

/** A poster (or square cover), its title on a plain card when there's no picture. */
@Composable
private fun Poster(item: MediaItem, args: ShowArgs, modifier: Modifier = Modifier, height: Int = 480) {
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(Card), contentAlignment = Alignment.Center) {
        Text(item.name, color = Color(0xB3FFFFFF), fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp))
        AsyncImage(args.repository.imageUrl(item, ImageKind.PRIMARY, height), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** The chosen title's name, facts and (as display settings allow) a few lines of its story. */
@Composable
private fun Details(item: MediaItem, args: ShowArgs, modifier: Modifier = Modifier, lines: Int = 3) {
    val prefs = LocalDisplayPrefs.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(args.titleOf(item), style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        item.episodeLabel?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
        ItemMetaLine(item)
        if (prefs.overview && !prefs.hidesDescription(item)) item.overview?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = lines, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * One focusable for a whole view that steps through titles itself: [onKey] gets each arrow (true =
 * handled); OK opens on release, ▶ plays. An arrow it doesn't handle leaves the view (Up to the bar).
 * Its name is the chosen title's, so the TV (and tests) can tell which one it's on.
 */
@Composable
private fun Modifier.showKeys(args: ShowArgs, current: MediaItem?, onKey: (Key) -> Boolean): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this
        .onPreviewKeyEvent { e ->
            val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
            when {
                ok -> { if (e.type == KeyEventType.KeyUp) current?.let(args.onOpen); true }
                e.key == Key.MediaPlay || e.key == Key.MediaPlayPause -> {
                    if (e.type == KeyEventType.KeyUp) current?.let { if (it.isPlayable) args.onPlay(it) else args.onOpen(it) }; true
                }
                e.key in setOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown) ->
                    if (e.type == KeyEventType.KeyDown) onKey(e.key) else false
                else -> false
            }
        }
        .focusable(interactionSource = interaction)
        .semantics { current?.let { text = AnnotatedString(args.titleOf(it)) } }
}

/** The view's chosen title, kept across visits, and more titles asked for near the end. */
@Composable
private fun rememberChoice(args: ShowArgs, count: Int = args.items.size): androidx.compose.runtime.MutableIntState {
    val index = rememberSaveable { mutableIntStateOf(0) }
    if (index.intValue > count - 1) index.intValue = (count - 1).coerceAtLeast(0)
    LaunchedEffect(index.intValue, count) { if (index.intValue >= count - 8) args.onNeedMore() }
    return index
}

/** Steps [index] by [by] within 0 until [count]; false at an end (the key leaves the view). */
private fun androidx.compose.runtime.MutableIntState.step(by: Int, count: Int): Boolean {
    val next = intValue + by
    if (next !in 0 until count) return false
    intValue = next
    return true
}

@Composable
private fun focusedState(): Pair<MutableInteractionSource, Boolean> {
    val i = remember { MutableInteractionSource() }
    val f by i.collectIsFocusedAsState()
    return i to f
}

private fun rnd(i: Int, salt: Int): Float {
    var x = i * 374761393 + salt * 668265263
    x = (x xor (x ushr 13)) * 1274126177
    return ((x xor (x ushr 16)) and 0x7fffffff) / 2147483648f
}

// ---- Cover flow ----

/** One big cover in the middle, the rest tilted back either side, each with a faint reflection. ◀▶ flips. */
@Composable
private fun CoverFlow(args: ShowArgs, modifier: Modifier) {
    val items = args.items
    val index = rememberChoice(args)
    val pos by animateFloatAsState(index.intValue.toFloat(), spring(dampingRatio = 0.85f, stiffness = 300f), label = "flow")
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val current = items.getOrNull(index.intValue)
    BoxWithConstraints(
        modifier.onFocusChanged { focused = it.hasFocus }
            .showKeys(args, current) { k -> when (k) { Key.DirectionRight -> index.step(1, items.size); Key.DirectionLeft -> index.step(-1, items.size); else -> false } },
    ) {
        val density = LocalDensity.current
        val h = maxHeight * 0.62f
        val w = if (args.music) h else h * 2f / 3f
        val wPx = with(density) { w.toPx() }
        val center = with(density) { (maxWidth / 2).toPx() } - wPx / 2
        val first = (floor(pos).toInt() - 6).coerceAtLeast(0)
        val last = (floor(pos).toInt() + 7).coerceAtMost(items.lastIndex)
        for (i in first..last) {
            val item = items[i]
            val d = i - pos
            val near = d.coerceIn(-1f, 1f)
            val x = center + d * wPx * 0.32f + near * wPx * 0.5f
            Column(
                Modifier.offset { IntOffset(x.roundToInt(), 0) }.zIndex(-abs(d)).width(w)
                    .graphicsLayer {
                        rotationY = -near * 52f
                        cameraDistance = 14 * density.density
                        val s = 1f - 0.22f * abs(near)
                        scaleX = s; scaleY = s
                        alpha = (1.4f - abs(d) * 0.12f).coerceIn(0f, 1f)
                    },
            ) {
                Poster(item, args, Modifier.fillMaxWidth().height(h).then(if (abs(d) < 0.5f && focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(10.dp)) else Modifier))
                // The floor's reflection: the cover upside down, fading away.
                Poster(item, args, Modifier.fillMaxWidth().height(h * 0.22f).graphicsLayer { scaleY = -1f; alpha = 0.22f }
                    .drawWithContent { drawContent(); drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF0D0F14)))) })
            }
        }
        current?.let { Details(it, args, Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.6f).padding(bottom = 4.dp), lines = 2) }
    }
}

// ---- Rolodex ----

/** Cards on a drum: the chosen one faces you with its details, the others tip away above and below. ▲▼ (or ◀▶) flips. */
@Composable
private fun Rolodex(args: ShowArgs, modifier: Modifier) {
    val items = args.items
    val index = rememberChoice(args)
    val pos by animateFloatAsState(index.intValue.toFloat(), spring(dampingRatio = 0.8f, stiffness = 260f), label = "drum")
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val current = items.getOrNull(index.intValue)
    BoxWithConstraints(
        modifier.onFocusChanged { focused = it.hasFocus }
            .showKeys(args, current) { k ->
                when (k) {
                    Key.DirectionDown, Key.DirectionRight -> index.step(1, items.size) || k == Key.DirectionDown
                    Key.DirectionUp, Key.DirectionLeft -> index.step(-1, items.size)
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val cardH = maxHeight * 0.5f
        val cardW = (maxWidth * 0.62f)
        val hPx = with(density) { cardH.toPx() }
        // The drum's spindle and its rings.
        Box(Modifier.width(cardW * 1.08f).height(10.dp).background(Color(0xFF3A3F4C), RoundedCornerShape(5.dp)))
        val first = (floor(pos).toInt() - 3).coerceAtLeast(0)
        val last = (floor(pos).toInt() + 3).coerceAtMost(items.lastIndex)
        for (i in first..last) {
            val item = items[i]
            val d = i - pos
            if (abs(d) > 3f) continue
            Row(
                Modifier.width(cardW).height(cardH).zIndex(-abs(d))
                    .graphicsLayer {
                        rotationX = d * -32f
                        translationY = d * hPx * 0.42f
                        cameraDistance = 18 * density.density
                        transformOrigin = TransformOrigin(0.5f, if (d < 0) 1f else 0f)
                        alpha = (1f - abs(d) * 0.5f).coerceIn(0f, 1f)
                    }
                    .background(Brush.verticalGradient(listOf(Color(0xFFF4EEDF), Color(0xFFE2D8C2))), RoundedCornerShape(12.dp))
                    // Cards tipped away are in the drum's shade.
                    .drawWithContent { drawContent(); if (abs(d) > 0.05f) drawRect(Color.Black.copy(alpha = (abs(d) * 0.45f).coerceAtMost(0.7f))) }
                    .then(if (abs(d) < 0.5f && focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(12.dp)) else Modifier)
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Poster(item, args, Modifier.fillMaxHeight().aspectRatio(if (args.music) 1f else 2f / 3f))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // A card's index tab and typed details.
                    Text(item.name.take(1).uppercase(), color = Color(0xFFB0412E), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(args.titleOf(item), color = Color(0xFF1C1A16), fontSize = 24.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(item.productionYear?.toString(), item.officialRating, item.runtimeMs?.let { "${it / 60_000} min" }).joinToString("  ·  "),
                        color = Color(0xFF4A453C), fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                    val prefs = LocalDisplayPrefs.current
                    if (prefs.overview && !prefs.hidesDescription(item)) item.overview?.let {
                        Text(it, color = Color(0xFF3A362E), fontSize = 15.sp, fontFamily = FontFamily.Serif, maxLines = 5, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

// ---- Shuffle deck ----

/** A deck of cards: the top one large with its details; ▶ deals it off with a flick, ◀ brings the last one back. */
@Composable
private fun Deck(args: ShowArgs, modifier: Modifier) {
    val items = args.items
    val index = rememberChoice(args)
    val pos by animateFloatAsState(index.intValue.toFloat(), tween(420), label = "deal")
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val current = items.getOrNull(index.intValue)
    BoxWithConstraints(
        modifier.onFocusChanged { focused = it.hasFocus }
            .showKeys(args, current) { k -> when (k) { Key.DirectionRight -> index.step(1, items.size); Key.DirectionLeft -> index.step(-1, items.size); else -> false } },
    ) {
        val density = LocalDensity.current
        val cardH = maxHeight * 0.86f
        val cardW = if (args.music) cardH else cardH * 2f / 3f
        val left = maxWidth * 0.12f
        val wPx = with(density) { maxWidth.toPx() }
        val first = (floor(pos).toInt() - 1).coerceAtLeast(0)
        val last = (floor(pos).toInt() + 6).coerceAtMost(items.lastIndex)
        for (i in last downTo first) {
            val d = i - pos
            Box(
                Modifier.offset(x = left).padding(top = 8.dp).width(cardW).height(cardH).zIndex(-d)
                    .graphicsLayer {
                        if (d < 0f) {
                            // Dealt: flicked off to the right, turning as it goes.
                            translationX = -d * wPx * 0.75f
                            translationY = d * 40f
                            rotationZ = -d * 26f
                            alpha = (1f + d).coerceIn(0f, 1f)
                        } else {
                            // In the deck: each a little askew, further down the pile a little smaller.
                            val k = min(d, 5f)
                            translationX = k * 10f
                            translationY = k * 8f
                            rotationZ = (rnd(i, 7) * 8f - 4f) * min(d, 1f)
                            scaleX = 1f - k * 0.03f; scaleY = scaleX
                        }
                    }
                    .background(Color(0xFFF7F2E7), RoundedCornerShape(14.dp))
                    .then(if (abs(d) < 0.5f && focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(14.dp)) else Modifier)
                    .padding(8.dp),
            ) { Poster(items[i], args, Modifier.fillMaxSize()) }
        }
        current?.let {
            Column(Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.42f).padding(end = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Details(it, args, lines = 6)
                Text("▶ deals the next card · ◀ takes one back · ${index.intValue + 1} of ${items.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---- Starfield ----

/** Deep space: titles drift towards you out of the dark, the chosen one parked up front with its details. ◀▶ calls the next one in. */
@Composable
private fun Starfield(args: ShowArgs, modifier: Modifier) {
    val items = args.items
    val index = rememberChoice(args)
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val calm = LocalDisplayPrefs.current.effects.reduceMotion
    val t by produceState(0f, calm) {
        if (calm) return@produceState
        val start = System.nanoTime()
        while (true) { kotlinx.coroutines.delay(33); value = (System.nanoTime() - start) / 1e9f }
    }
    val arrive = remember { Animatable(1f) }
    LaunchedEffect(index.intValue) { arrive.snapTo(0f); arrive.animateTo(1f, tween(650)) }
    val current = items.getOrNull(index.intValue)
    BoxWithConstraints(
        modifier.clip(RoundedCornerShape(18.dp))
            .onFocusChanged { focused = it.hasFocus }
            .showKeys(args, current) { k -> when (k) { Key.DirectionRight -> index.step(1, items.size); Key.DirectionLeft -> index.step(-1, items.size); else -> false } },
    ) {
        Canvas(Modifier.fillMaxSize()) { with(dev.nitrate.tv.ui.effects.ShowStars) { draw(t * 0.35f) } }
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val small = maxHeight * 0.34f
        // Those still to come, drifting out from the far middle and growing as they near.
        for (k in 1..10) {
            val i = index.intValue + k
            val item = items.getOrNull(i) ?: break
            val z = ((rnd(i, 3) + t * 0.05f) % 1f)
            val a = rnd(i, 4) * 6.283f
            val reach = 0.15f + z * 0.55f
            // From a vanishing point on the right, clear of the chosen title and its details.
            val x = wPx * 0.8f + cos(a) * wPx * 0.17f * reach - wPx * 0.04f
            val y = hPx * 0.42f + sin(a) * hPx * 0.4f * reach
            Poster(item, args, Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }.width(if (args.music) small else small * 2f / 3f).height(small)
                .graphicsLayer { val s = 0.2f + z * 0.7f; scaleX = s; scaleY = s; alpha = (z * 1.6f).coerceAtMost(1f) * (1f - ((z - 0.85f) / 0.15f).coerceIn(0f, 1f)) * 0.85f }, height = 240)
        }
        current?.let {
            val big = maxHeight * 0.78f
            Row(Modifier.align(Alignment.CenterStart).padding(start = 28.dp).fillMaxWidth(0.62f), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Poster(it, args, Modifier.height(big).width(if (args.music) big else big * 2f / 3f)
                    .graphicsLayer { val s = 0.35f + 0.65f * arrive.value; scaleX = s; scaleY = s; alpha = arrive.value }
                    .then(if (focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(10.dp)) else Modifier))
                Details(it, args, Modifier.weight(1f).background(Brush.horizontalGradient(listOf(Color(0xCC02030A), Color(0x9902030A), Color.Transparent)), RoundedCornerShape(12.dp)).padding(14.dp), lines = 5)
            }
        }
    }
}

// ---- Timeline ----

/** A line through the years: titles in release order above and below it, the years marked. ◀▶ travel through time. */
@Composable
private fun Timeline(args: ShowArgs, modifier: Modifier) {
    val byYear = remember(args.items) { args.items.sortedWith(compareBy<MediaItem> { it.productionYear ?: Int.MAX_VALUE }.thenBy { it.sortName ?: it.name }) }
    val index = rememberChoice(args, byYear.size)
    val pos by animateFloatAsState(index.intValue.toFloat(), spring(dampingRatio = 0.85f, stiffness = 260f), label = "time")
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val current = byYear.getOrNull(index.intValue)
    BoxWithConstraints(
        modifier.onFocusChanged { focused = it.hasFocus }
            .showKeys(args, current) { k -> when (k) { Key.DirectionRight -> index.step(1, byYear.size); Key.DirectionLeft -> index.step(-1, byYear.size); else -> false } },
    ) {
        val density = LocalDensity.current
        val posterH = maxHeight * 0.3f
        val posterW = if (args.music) posterH else posterH * 2f / 3f
        val spacing = with(density) { posterW.toPx() } * 0.72f
        val wPx = with(density) { maxWidth.toPx() }
        val line = with(density) { (maxHeight * 0.6f).toPx() }
        val shift = wPx / 2 - pos * spacing
        val first = (floor(pos).toInt() - 14).coerceAtLeast(0)
        val last = (floor(pos).toInt() + 14).coerceAtMost(byYear.lastIndex)
        // The line, with a tick and the year wherever a new year begins.
        Canvas(Modifier.fillMaxSize()) {
            drawLine(Brush.horizontalGradient(listOf(Color.Transparent, Color(0x99FFD58A), Color(0x99FFD58A), Color.Transparent)), Offset(0f, line), Offset(size.width, line), 3f)
            for (i in first..last) {
                val year = byYear[i].productionYear
                if (i > 0 && byYear[i - 1].productionYear == year) continue
                val x = shift + i * spacing
                drawLine(Color(0xCCFFD58A), Offset(x, line - 12f), Offset(x, line + 12f), 3f)
            }
        }
        for (i in first..last) {
            val year = byYear[i].productionYear
            if (i > 0 && byYear[i - 1].productionYear == year) continue
            val x = shift + i * spacing
            Text(year?.toString() ?: "Undated", color = Color(0xFFFFD58A), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.offset { IntOffset((x - 18f).roundToInt(), (line + 16f).roundToInt()) })
        }
        for (i in first..last) {
            val above = i % 2 == 0
            val x = shift + i * spacing
            val chosen = i == index.intValue
            val hPx = with(density) { posterH.toPx() }
            val y = if (above) line - hPx - 24f else line + 46f
            Poster(byYear[i], args, Modifier.offset { IntOffset((x - spacing * 0.65f).roundToInt(), y.roundToInt()) }.width(posterW).height(posterH)
                .zIndex(if (chosen) 1f else 0f)
                .graphicsLayer { val s = if (chosen) 1.18f else 0.9f; scaleX = s; scaleY = s; alpha = if (chosen) 1f else 0.7f }
                .then(if (chosen && focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(10.dp)) else Modifier), height = 240)
        }
        current?.let { Details(it, args, Modifier.align(Alignment.TopStart).fillMaxWidth(0.5f), lines = 2) }
    }
}

// ---- Bookshelf ----

private val SpineColors = listOf(Color(0xFF6B1E1E), Color(0xFF1E3B5C), Color(0xFF2E4A2A), Color(0xFF5A3A1A), Color(0xFF3E2450), Color(0xFF1F1F24), Color(0xFF7A5A1A), Color(0xFF244848))

/**
 * Titles as book spines on wooden shelves; the chosen one slides up off its shelf and its cover and
 * details show beside the shelves. ◀▶ along a shelf, ▲▼ between shelves.
 */
@Composable
private fun Bookshelf(args: ShowArgs, modifier: Modifier) {
    val items = args.items
    val index = rememberChoice(args)
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val current = items.getOrNull(index.intValue)
    BoxWithConstraints(modifier) {
        val shelvesW = maxWidth * 0.66f
        val spineW = 44.dp
        val perShelf = ((shelvesW.value - 24f) / (spineW.value + 4f)).toInt().coerceAtLeast(1)
        val shelfH = maxHeight / 3f
        val shelf = index.intValue / perShelf
        val firstShelf = (shelf - 1).coerceAtLeast(0)
        Column(
            Modifier.width(shelvesW).fillMaxHeight()
                .onFocusChanged { focused = it.hasFocus }
                .showKeys(args, current) { k ->
                    when (k) {
                        Key.DirectionRight -> index.step(1, items.size)
                        Key.DirectionLeft -> index.intValue % perShelf != 0 && index.step(-1, items.size)
                        Key.DirectionDown -> index.step(perShelf, items.size) || (items.size - 1 > index.intValue && (items.size - 1) / perShelf > shelf).also { if (it) index.intValue = items.size - 1 }
                        Key.DirectionUp -> index.step(-perShelf, items.size)
                        else -> false
                    }
                },
        ) {
            for (s in firstShelf until firstShelf + 3) {
                val books = (s * perShelf until min((s + 1) * perShelf, items.size)).toList()
                if (books.isEmpty() && s > shelf) break
                Box(Modifier.fillMaxWidth().height(shelfH)) {
                    // The back of the case, then the books standing on the plank.
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF241810), Color(0xFF3A2616)))))
                    Row(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                        books.forEach { i -> Spine(items[i], i, chosen = i == index.intValue, focused = focused, width = spineW, height = shelfH - 26.dp) }
                    }
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(14.dp).background(Brush.verticalGradient(listOf(Color(0xFF9A6A3A), Color(0xFF5A3A1E)))))
                }
            }
        }
        current?.let {
            Column(Modifier.align(Alignment.TopEnd).fillMaxWidth(0.31f).fillMaxHeight().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Poster(it, args, Modifier.fillMaxWidth(0.62f).aspectRatio(if (args.music) 1f else 2f / 3f))
                Details(it, args, lines = 4)
            }
        }
    }
}

@Composable
private fun Spine(item: MediaItem, i: Int, chosen: Boolean, focused: Boolean, width: Dp, height: Dp) {
    val lift by animateFloatAsState(if (chosen) 1f else 0f, tween(220), label = "lift")
    val color = SpineColors[(item.name.hashCode() and 0x7fffffff) % SpineColors.size]
    val tall = height * (0.8f + rnd(i, 11) * 0.2f)
    Box(
        Modifier.width(width).height(tall)
            .graphicsLayer { translationY = -lift * 26f * density }
            .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.85f), color, color.copy(red = color.red * 0.7f, green = color.green * 0.7f, blue = color.blue * 0.7f))), RoundedCornerShape(3.dp))
            .then(if (chosen && focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(3.dp)) else Modifier),
    ) {
        // Gilt bands top and bottom, the title running up the spine.
        Box(Modifier.align(Alignment.TopCenter).padding(top = 10.dp).fillMaxWidth().height(2.dp).background(Color(0xCCD9B45A)))
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).fillMaxWidth().height(2.dp).background(Color(0xCCD9B45A)))
        Text(
            item.name, color = Color(0xFFF2E6C8), fontSize = 13.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center).graphicsLayer { rotationZ = -90f }.width(tall - 28.dp),
        )
    }
}

// ---- Wall of art ----

/**
 * A gallery wall: backdrops of mixed widths in three rows, panning slowly by itself until the remote
 * is on it; the one focused lifts out with its name. The arrows move from picture to picture.
 */
@Composable
private fun WallOfArt(args: ShowArgs, modifier: Modifier) {
    val items = args.items
    val state = rememberLazyStaggeredGridState()
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val calm = LocalDisplayPrefs.current.effects.reduceMotion
    LaunchedEffect(focused, calm) {
        if (focused || calm) return@LaunchedEffect
        while (true) { kotlinx.coroutines.delay(33); state.scrollBy(1.2f) }
    }
    LaunchedEffect(state, items.size) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { if (it >= items.size - 12) args.onNeedMore() }
    }
    BoxWithConstraints(modifier.onFocusChanged { focused = it.hasFocus }) {
        val rowH = (maxHeight - 24.dp) / 3
        LazyHorizontalStaggeredGrid(
            rows = StaggeredGridCells.Fixed(3),
            state = state,
            horizontalItemSpacing = 12.dp,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items.size, key = { items[it].id }) { i ->
                val item = items[i]
                val wide = rnd(i, 21) > 0.6f
                val (interaction, isFocused) = focusedState()
                Surface(
                    onClick = { args.onOpen(item) },
                    onLongClick = null,
                    interactionSource = interaction,
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    modifier = Modifier.width(rowH * (if (wide) 2.4f else 16f / 9f)).fillMaxHeight()
                        .onPreviewKeyEvent { e ->
                            if ((e.key == Key.MediaPlay || e.key == Key.MediaPlayPause) && e.type == KeyEventType.KeyUp) { if (item.isPlayable) args.onPlay(item) else args.onOpen(item); true } else false
                        },
                ) {
                    Box(Modifier.fillMaxSize().background(Card)) {
                        Text(item.name, color = Color(0x99FFFFFF), modifier = Modifier.align(Alignment.Center).padding(8.dp), maxLines = 2)
                        AsyncImage(args.repository.imageUrl(item, ImageKind.THUMB, 360) ?: args.repository.backdropUrl(item, 720), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        if (isFocused) {
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xD9000000))))
                            Text(args.titleOf(item), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
                        }
                    }
                }
            }
        }
    }
}

