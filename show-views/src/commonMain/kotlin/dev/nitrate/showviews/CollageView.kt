package dev.nitrate.showviews

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.nitrate.core.model.ImageKind
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.pages.Collage
import dev.nitrate.core.pages.CollageBoard
import dev.nitrate.core.pages.CollageLines
import dev.nitrate.core.pages.CollageMove
import dev.nitrate.core.pages.CollageTile
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val Board = Color(0xFFF3EFE7)
private val Hairline = Color(0xFF3A342B)

/** A tile as drawn now: which copy of the board it's in, and where on screen (px). */
private class Placed(val tile: CollageTile, val x: Float, val y: Float, val w: Float, val h: Float, val bx: Float, val by: Float)

/**
 * Collage: an endless board of posters and wide artwork in mixed sizes, joined by hairlines for what
 * they share ([ShowSource.lines]: a genre, a collection, a director, a writer, or at random), the thing
 * written on the line. The D-pad hops to the nearest picture that way and the board slides to keep it
 * in the middle, round and round sideways; ▲ from the top row leaves for the bar. OK opens the lightbox
 * (the picture large with its details; ◀ ▶ step through, OK opens, Back closes). Touch: drag to pan,
 * tap a picture to choose it, tap again for the lightbox.
 */
@Composable
internal fun CollageView(source: ShowSource, style: ShowStyle, host: ShowHost, modifier: Modifier) {
    val items = source.items
    val index = rememberChoice(source)
    val board = remember(items.size) { Collage.layout(items.size) }
    val scope = rememberCoroutineScope()
    val cam = remember { Animatable(board.tiles.getOrNull(index.intValue)?.let { Offset(it.cx, it.cy) } ?: Offset.Zero, Offset.VectorConverter) }
    var lightbox by remember { mutableStateOf(false) }
    var button by remember { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    val crew = remember { mutableStateMapOf<String, List<dev.nitrate.core.model.Person>>() }
    val sagas = remember { mutableStateMapOf<String, List<String>>() }
    val current = items.getOrNull(index.intValue)
    // What was drawn last (not state: written while drawing, read by touch and the facts fetch).
    val drawn = remember { arrayOf(emptyList<Placed>()) }
    val unitPx = remember { floatArrayOf(1f) }

    /** Where tile [i]'s nearest copy is from the camera, in board units. */
    fun nearCopy(board: CollageBoard, i: Int): Offset? {
        val t = board.tiles.getOrNull(i) ?: return null
        val c = cam.targetValue
        return Offset(
            t.cx + board.width * ((c.x - t.cx) / board.width).roundToInt(),
            t.cy + board.height * ((c.y - t.cy) / board.height).roundToInt(),
        )
    }
    fun choose(i: Int, to: Offset? = nearCopy(board, i)) {
        index.intValue = i
        to?.let { scope.launch { cam.animateTo(it, spring(dampingRatio = 0.85f, stiffness = 160f)) } }
    }
    fun move(m: CollageMove): Boolean {
        val step = Collage.neighbour(board, index.intValue, m) ?: return false
        val from = board.tiles[index.intValue]
        val home = nearCopy(board, index.intValue) ?: return false
        // Across the top edge: leave for the bar. Past the bottom before everything's loaded: load, stay.
        if (m == CollageMove.UP && step.y > from.cy) return false
        if (m == CollageMove.UP && step.y < 0f) return false
        if (m == CollageMove.DOWN && step.y > board.height && !source.complete) { source.onNeedMore(); return true }
        choose(step.index, Offset(home.x + step.x - from.cx, home.y + step.y - from.cy))
        return true
    }
    host.backHandler(lightbox) { lightbox = false }
    // Chosen from outside (the page's jump bar, a change of view): the board slides to it.
    LaunchedEffect(index.intValue, board) {
        val to = nearCopy(board, index.intValue) ?: return@LaunchedEffect
        if ((cam.targetValue - to).getDistance() > 0.01f) cam.animateTo(to, spring(dampingRatio = 0.85f, stiffness = 160f))
    }
    // The facts the lines need, for the titles in sight, asked once each (directors and writers in one request).
    LaunchedEffect(index.intValue, source.lines, items.size) {
        delay(300)
        val near = drawn[0].map { items[it.tile.index] }.distinctBy { it.id }
        when (source.lines) {
            CollageLines.DIRECTOR, CollageLines.WRITER -> {
                val missing = near.map { it.id }.filter { it !in crew }.take(60)
                if (missing.isNotEmpty()) {
                    val got = runCatching { source.server.crewOf(missing) }.getOrDefault(emptyMap())
                    missing.forEach { crew[it] = got[it].orEmpty() }
                }
            }
            CollageLines.COLLECTION -> near.filter { it.kind == ItemKind.MOVIE && it.id !in sagas }.take(24).forEach { item ->
                sagas[item.id] = runCatching { source.server.collectionsOf(item.id).map { it.name } }.getOrDefault(emptyList())
            }
            else -> Unit
        }
    }
    Box(
        modifier.clipToBounds().background(Board)
            .onFocusChanged { focused = it.hasFocus }
            .showInput(current?.let { source.titleOf(it) + if (lightbox) ", in the lightbox" else "" }, lightbox, host.focus, host.repeats) { k ->
                when (k) {
                    ShowKey.LEFT -> if (lightbox) { if (index.intValue > 0) choose(index.intValue - 1); true } else move(CollageMove.LEFT)
                    ShowKey.RIGHT -> if (lightbox) { if (index.intValue < items.lastIndex) choose(index.intValue + 1); true } else move(CollageMove.RIGHT)
                    ShowKey.UP -> if (lightbox) { button = 0; true } else move(CollageMove.UP)
                    ShowKey.DOWN -> if (lightbox) { button = 1; true } else move(CollageMove.DOWN)
                    ShowKey.OK -> {
                        val item = current
                        when {
                            item == null -> Unit
                            !lightbox -> { button = 0; lightbox = true }
                            button == 0 -> source.onOpen(item)
                            else -> source.onPlay(item)
                        }
                        true
                    }
                    ShowKey.BACK -> { lightbox = false; true }
                    ShowKey.PLAY -> { current?.let(source.onPlay); true }
                }
            }
            .pointerInput(board) {
                detectDragGestures(
                    onDragEnd = {
                        // The picture nearest the middle becomes the chosen one.
                        val c = cam.value
                        val best = drawn[0].minByOrNull { p -> (p.bx - c.x) * (p.bx - c.x) + (p.by - c.y) * (p.by - c.y) }
                        best?.let { choose(it.tile.index, Offset(it.bx, it.by)) }
                    },
                ) { change, amount ->
                    change.consume()
                    if (lightbox) return@detectDragGestures
                    scope.launch { cam.snapTo(cam.value - amount / unitPx[0]) }
                }
            }
            .pointerInput(board) {
                detectTapGestures { at ->
                    if (lightbox) { lightbox = false; return@detectTapGestures }
                    val hit = drawn[0].lastOrNull { at.x in it.x..(it.x + it.w) && at.y in it.y..(it.y + it.h) } ?: return@detectTapGestures
                    if (hit.tile.index == index.intValue) { button = 0; lightbox = true } else choose(hit.tile.index, Offset(hit.bx, hit.by))
                }
            },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val wPx = with(density) { maxWidth.toPx() }
            val hPx = with(density) { maxHeight.toPx() }
            val u = if (source.tv) wPx / 8.6f else wPx / 3.4f
            unitPx[0] = u
            val c = cam.value
            // Every copy of every tile that's in sight (the board repeats both ways).
            val inSight = ArrayList<Placed>()
            val margin = u * 0.5f
            for (t in board.tiles) {
                val kx = ((c.x - t.cx) / board.width).roundToInt()
                val ky = ((c.y - t.cy) / board.height).roundToInt()
                for (ox in kx - 1..kx + 1) for (oy in ky - 1..ky + 1) {
                    val bx = t.cx + ox * board.width
                    val by = t.cy + oy * board.height
                    val x = (t.x + ox * board.width - c.x) * u + wPx / 2
                    val y = (t.y + oy * board.height - c.y) * u + hPx / 2
                    val w = t.w * u
                    val h = t.h * u
                    if (x + w < -margin || x > wPx + margin || y + h < -margin || y > hPx + margin) continue
                    inSight += Placed(t, x, y, w, h, bx, by)
                }
            }
            drawn[0] = inSight
            // The hairlines, under the pictures, with what's shared written along them.
            val shown = inSight.map { it.tile.index }.toSet()
            val keysOf: (Int) -> List<String> = { i ->
                val item = items[i]
                when (source.lines) {
                    CollageLines.GENRE -> item.genres
                    CollageLines.COLLECTION -> sagas[item.id].orEmpty()
                    CollageLines.DIRECTOR -> crew[item.id].orEmpty().filter { it.type == "Director" }.map { it.name }
                    CollageLines.WRITER -> crew[item.id].orEmpty().filter { it.type == "Writer" }.map { it.name }
                    CollageLines.RANDOM -> emptyList()
                }
            }
            val links = remember(shown, source.lines, crew.size, sagas.size) {
                if (source.lines == CollageLines.RANDOM) Collage.randomLinks(board, shown) else Collage.links(board, shown, keysOf)
            }
            val measurer = rememberTextMeasurer()
            val chosen = index.intValue
            val labelStyle = style.label.copy(color = Hairline)
            Canvas(Modifier.fillMaxSize()) {
                val firstOf = HashMap<Int, Placed>()
                inSight.forEach { p -> if (p.tile.index !in firstOf || near(p, wPx, hPx) < near(firstOf[p.tile.index]!!, wPx, hPx)) firstOf[p.tile.index] = p }
                links.forEach { l ->
                    val a = firstOf[l.from] ?: return@forEach
                    val b = firstOf[l.to] ?: return@forEach
                    val pa = Offset(a.x + a.w / 2, a.y + a.h / 2)
                    val pb = Offset(b.x + b.w / 2, b.y + b.h / 2)
                    val touches = l.from == chosen || l.to == chosen
                    drawLine(Hairline.copy(alpha = if (touches) 0.8f else 0.35f), pa, pb, strokeWidth = if (touches) 2.4f else 1.3f)
                }
            }
            // What's shared, on the lines touching the chosen picture (and every third other), over the pictures.
            Canvas(Modifier.fillMaxSize().zIndex(3f)) {
                val firstOf = HashMap<Int, Placed>()
                inSight.forEach { p -> if (p.tile.index !in firstOf || near(p, wPx, hPx) < near(firstOf[p.tile.index]!!, wPx, hPx)) firstOf[p.tile.index] = p }
                var labels = 0
                links.forEachIndexed { n, l ->
                    val label = l.label ?: return@forEachIndexed
                    val touches = l.from == chosen || l.to == chosen
                    if (!(touches || n % 3 == 0) || labels >= 12) return@forEachIndexed
                    val a = firstOf[l.from] ?: return@forEachIndexed
                    val b = firstOf[l.to] ?: return@forEachIndexed
                    // In the gap: the middle of the stretch of line between the two pictures' edges.
                    val pa = Offset(a.x + a.w / 2, a.y + a.h / 2)
                    val pb = Offset(b.x + b.w / 2, b.y + b.h / 2)
                    val ta = exitAt(a, pa, pb)
                    val tb = 1f - exitAt(b, pb, pa)
                    if (tb <= ta) return@forEachIndexed
                    val t = (ta + tb) / 2
                    val c = Offset(pa.x + (pb.x - pa.x) * t, pa.y + (pb.y - pa.y) * t)
                    // Never written over a picture (a line can pass under one).
                    if (inSight.any { q -> c.x in q.x..(q.x + q.w) && c.y in q.y..(q.y + q.h) }) return@forEachIndexed
                    labels++
                    val text = measurer.measure(label, labelStyle)
                    val mid = Offset(c.x - text.size.width / 2f, c.y - text.size.height / 2f)
                    drawRoundRect(Board, topLeft = mid - Offset(10f, 4f), size = Size(text.size.width + 20f, text.size.height + 8f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(24f))
                    drawRoundRect(Hairline.copy(alpha = if (touches) 0.7f else 0.35f), topLeft = mid - Offset(10f, 4f), size = Size(text.size.width + 20f, text.size.height + 8f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(24f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.2f))
                    drawText(text, topLeft = mid)
                }
            }
            // The pictures: prints with a white margin; the chosen one lifted.
            inSight.forEach { p -> androidx.compose.runtime.key(p.tile.index, p.bx.roundToInt(), p.by.roundToInt()) {
                val item = items[p.tile.index]
                val isChosen = p.tile.index == chosen
                Box(
                    Modifier.offset { IntOffset(p.x.roundToInt(), p.y.roundToInt()) }
                        .size(with(density) { p.w.toDp() }, with(density) { p.h.toDp() })
                        .zIndex(if (isChosen) 2f else 1f)
                        .graphicsLayer {
                            val s = if (isChosen) 1.06f else 1f
                            scaleX = s; scaleY = s
                            shadowElevation = if (isChosen) 18f else 4f
                        }
                        .background(Color.White)
                        .then(if (isChosen && (focused || !source.tv)) Modifier.border(3.dp, style.accent) else Modifier)
                        .padding(5.dp),
                ) {
                    Picture(item, source.server, style, Modifier.fillMaxSize(), kind = if (p.tile.wide) ImageKind.BACKDROP else ImageKind.PRIMARY, heightPx = (p.h * 1.1f).roundToInt().coerceIn(200, 900), corner = 0)
                }
            } }
            // What the lines mean, and how to move.
            Row(
                Modifier.align(Alignment.TopStart).zIndex(5f).padding(14.dp).background(Board.copy(alpha = 0.92f), RoundedCornerShape(50))
                    .border(1.dp, Hairline.copy(alpha = 0.3f), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BasicText("Lines: " + source.lines.label, style = style.label.copy(color = Hairline))
                current?.let { BasicText(source.titleOf(it), style = style.label.copy(color = Hairline.copy(alpha = 0.6f)), maxLines = 1) }
            }
        }
        // The lightbox: the picture large, its details and buttons.
        if (lightbox && current != null) {
            val wide = board.tiles.getOrNull(index.intValue)?.wide == true
            Row(
                Modifier.fillMaxSize().zIndex(10f).background(Color(0xF0101114)).padding(horizontal = 48.dp, vertical = 36.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(36.dp),
            ) {
                Picture(current, source.server, style, (if (wide) Modifier.weight(1.4f).aspectRatio(16f / 9f) else Modifier.fillMaxHeight().aspectRatio(2f / 3f)).border(6.dp, Color.White),
                    kind = if (wide) ImageKind.BACKDROP else ImageKind.PRIMARY, heightPx = 900, corner = 0)
                PeelDetails(current, source, style, button, dark = true, modifier = Modifier.weight(1f),
                    hint = if (source.tv) "◀ ▶ another · ▲ ▼ choose · OK · Back closes" else "Tap outside to close")
            }
        }
    }
}

/** Where (0..1 along [from]→[to]) the line from a tile's middle leaves its edge. */
private fun exitAt(p: Placed, from: Offset, to: Offset): Float {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val tx = if (dx != 0f) (p.w / 2) / kotlin.math.abs(dx) else Float.MAX_VALUE
    val ty = if (dy != 0f) (p.h / 2) / kotlin.math.abs(dy) else Float.MAX_VALUE
    return minOf(tx, ty).coerceAtMost(1f)
}

/** How far a placed tile is from the middle of the screen (to pick the copy to draw lines to). */
private fun near(p: Placed, w: Float, h: Float): Float {
    val dx = p.x + p.w / 2 - w / 2
    val dy = p.y + p.h / 2 - h / 2
    return dx * dx + dy * dy
}
