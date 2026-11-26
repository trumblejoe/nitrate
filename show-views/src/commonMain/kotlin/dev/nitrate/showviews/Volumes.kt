package dev.nitrate.showviews

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.nitrate.core.model.ImageKind
import dev.nitrate.core.model.MediaItem
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sign

private val Paper = Color(0xFFFBF6EC)
private val PaperEdge = Color(0xFFE9DFCC)
private val Ink = Color(0xFF221E18)
private const val PAGES = 3

/**
 * Volumes: each title a book drifting through slanted window light. The chosen one comes to the middle
 * and opens to a spread (its pages flicking as it opens): its artwork on the left page, its words on
 * the right. ◀ ▶ close it and bring the next; ▲ ▼ turn its pages (the story; facts and cast; more like
 * this); OK opens the title. Touch: swipe sideways for books, up and down for pages, tap to open.
 */
@Composable
internal fun Volumes(source: ShowSource, style: ShowStyle, host: ShowHost, modifier: Modifier) {
    val items = source.items
    val index = rememberChoice(source)
    val pos by animateFloatAsState(index.intValue.toFloat(), spring(dampingRatio = 0.9f, stiffness = 120f), label = "volumes")
    val open = remember { Animatable(0f) }
    var page by remember { mutableIntStateOf(0) }
    // A page turning: from [turnFrom] to [page], 0..1.
    val turn = remember { Animatable(1f) }
    var turnFrom by remember { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    val current = items.getOrNull(index.intValue)
    LaunchedEffect(index.intValue) {
        page = 0
        turnFrom = 0
        turn.snapTo(1f)
        open.snapTo(0f)
        delay(260)
        open.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(page) { if (turnFrom != page) { turn.snapTo(0f); turn.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) } }
    fun book(by: Int): Boolean {
        val next = index.intValue + by
        if (next !in items.indices) return false
        index.intValue = next
        return true
    }
    fun turnTo(p: Int): Boolean {
        if (p !in 0 until PAGES || p == page) return p == page
        turnFrom = page
        page = p
        return true
    }
    Row(modifier) {
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxHeight().clipToBounds()
                .background(Brush.linearGradient(listOf(Color(0xFFF2EBDD), Color(0xFFD9CCB3)), start = Offset.Zero, end = Offset(1600f, 1100f)))
                .onFocusChanged { focused = it.hasFocus }
                .showInput(current?.let { source.titleOf(it) + ", page ${page + 1} of $PAGES" }, false, host.focus) { k ->
                    when (k) {
                        ShowKey.RIGHT -> book(1)
                        ShowKey.LEFT -> book(-1)
                        ShowKey.DOWN -> { turnTo(page + 1); true }
                        // ▲ on the first page leaves the view (to the bar).
                        ShowKey.UP -> if (page > 0) turnTo(page - 1) else false
                        ShowKey.OK -> { current?.let(source.onOpen); true }
                        ShowKey.PLAY -> { current?.let(source.onPlay); true }
                        ShowKey.BACK -> false
                    }
                }
                .pointerInput(items.size) {
                    var dx = 0f
                    var dy = 0f
                    detectDragGestures(
                        onDragStart = { dx = 0f; dy = 0f },
                        onDragEnd = {
                            val limit = 60.dp.toPx()
                            if (abs(dx) > abs(dy) && abs(dx) > limit) book(if (dx < 0) 1 else -1)
                            else if (abs(dy) > limit) turnTo(page + if (dy < 0) 1 else -1)
                        },
                    ) { change, amount -> change.consume(); dx += amount.x; dy += amount.y }
                }
                .pointerInput(Unit) { detectTapGestures { current?.let(source.onOpen) } },
        ) {
            val density = LocalDensity.current
            val wPx = with(density) { maxWidth.toPx() }
            // Sized by the height on a wide screen, by the width on a tall one (a phone held upright).
            val pageW = minOf(maxHeight * 0.78f * 0.66f, maxWidth * 0.46f)
            val pageH = pageW / 0.66f
            val pageWPx = with(density) { pageW.toPx() }
            val pageHPx = with(density) { pageH.toPx() }
            val mid = wPx / 2
            val top = with(density) { ((maxHeight - pageH) / 2).toPx() }
            WindowLight(pos)
            // The books either side, closed, drifting past.
            val first = (floor(pos).toInt() - 3).coerceAtLeast(0)
            val last = (floor(pos).toInt() + 3).coerceAtMost(items.lastIndex)
            for (i in first..last) {
                if (i == index.intValue) continue
                val d = i - pos
                if (abs(d) > 3.2f) continue
                val s = 0.5f - 0.04f * abs(d)
                val x = mid + d * pageWPx * 0.95f + sign(d) * pageWPx * 0.55f
                ClosedBook(
                    items[i], source, style, pageW * s, pageH * s,
                    Modifier.offset { IntOffset((x - pageWPx * s / 2).roundToInt(), (top + pageHPx * (1 - s) / 2 + d * 10f).roundToInt()) }
                        .zIndex(-abs(d))
                        .graphicsLayer {
                            rotationY = -d * 16f
                            rotationZ = d * 3.5f
                            cameraDistance = 14f * density.density
                            alpha = (1.2f - abs(d) * 0.3f).coerceIn(0f, 1f)
                        },
                )
            }
            val item = current ?: return@BoxWithConstraints
            val d = index.intValue - pos
            val o = open.value
            // The chosen book: it slides in closed, then opens about its spine at the middle.
            val slide = d * pageWPx * 1.2f
            val spine = mid + slide
            val bookTop = top
            Box(Modifier.offset { IntOffset((spine - pageWPx).roundToInt(), bookTop.roundToInt()) }.size(pageW * 2, pageH).zIndex(10f)) {
                // Left page: the title's artwork, framed in a margin, there once the cover has swung over.
                Box(
                    Modifier.size(pageW, pageH).graphicsLayer { alpha = ((o - 0.5f) * 3f).coerceIn(0f, 1f) }
                        .clip(RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                        .background(Brush.horizontalGradient(listOf(PaperEdge, Paper, Paper, Color(0xFFE4D9C4)))),
                ) { LeftPage(item, source, style) }
                // Right page: the words of the page open now (the one before turning over on top of it).
                Box(
                    Modifier.offset(x = pageW).size(pageW, pageH)
                        .clip(RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                        .background(Brush.horizontalGradient(listOf(Color(0xFFE4D9C4), Paper, Paper, PaperEdge)))
                        .then(if (focused || !source.tv) Modifier.border(2.dp, style.accent.copy(alpha = 0.7f), RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)) else Modifier),
                ) { RightPage(item, page, source, style) }
                // A page turning over (▲▼): the page being left, swinging about the spine.
                val t = turn.value
                if (t < 1f && turnFrom != page) {
                    val forward = page > turnFrom
                    val angle = if (forward) -180f * t else -180f * (1f - t)
                    val shown = if (forward) turnFrom else page
                    Box(
                        Modifier.offset(x = pageW).size(pageW, pageH).zIndex(5f)
                            .graphicsLayer {
                                transformOrigin = TransformOrigin(0f, 0.5f)
                                rotationY = angle
                                cameraDistance = 18f * density.density
                            }
                            .background(Paper),
                    ) { if (angle > -90f) RightPage(item, shown, source, style) }
                }
                // Pages flicking past as it opens.
                for (k in 0 until 3) {
                    val a = ((o * 1.5f - 0.25f - k * 0.12f) / 0.55f).coerceIn(0f, 1f)
                    if (a <= 0f || a >= 1f) continue
                    Box(
                        Modifier.offset(x = pageW).size(pageW, pageH).zIndex(6f + k)
                            .graphicsLayer { transformOrigin = TransformOrigin(0f, 0.5f); rotationY = -180f * a; cameraDistance = 18f * density.density }
                            .background(Paper.copy(alpha = 0.96f)),
                    )
                }
                // The cover, swinging open about the spine.
                val coverAngle = -180f * o
                if (coverAngle > -90f) Box(
                    Modifier.offset(x = pageW).size(pageW, pageH).zIndex(9f)
                        .graphicsLayer { transformOrigin = TransformOrigin(0f, 0.5f); rotationY = coverAngle; cameraDistance = 18f * density.density },
                ) { Cover(item, source, style, Modifier.fillMaxSize()) }
            }
            BasicText(
                if (source.tv) "◀ ▶ another book · ▲ ▼ turn the page · OK open" else "Swipe sideways for another book, up or down to turn the page",
                style = style.label.copy(color = Ink.copy(alpha = 0.45f)),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
            )
        }
        host.letters?.invoke(Modifier.padding(start = 10.dp)) { i -> index.intValue = i; runCatching { host.focus?.requestFocus() } }
    }
}

/** Slanted bands of sunlight through a window, and the shadows of its bars, sliding a little as books pass. */
@Composable
private fun WindowLight(pos: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val shift = (pos % 8f) * 18f
        rotate(-24f, Offset(size.width / 2, size.height / 2)) {
            for (k in -2..5) {
                val x = k * size.width * 0.28f + shift
                drawRect(Color.White.copy(alpha = 0.16f), topLeft = Offset(x, -size.height), size = androidx.compose.ui.geometry.Size(size.width * 0.16f, size.height * 3f))
                drawRect(Color(0xFF5A4A30).copy(alpha = 0.05f), topLeft = Offset(x + size.width * 0.17f, -size.height), size = androidx.compose.ui.geometry.Size(size.width * 0.012f, size.height * 3f))
            }
        }
    }
}

/** A closed book: the poster as its cover, a darker spine down the left and the pages' edge on the right. */
@Composable
private fun ClosedBook(item: MediaItem, source: ShowSource, style: ShowStyle, w: Dp, h: Dp, modifier: Modifier) {
    Row(modifier.size(w * 1.1f, h)) {
        Box(Modifier.width(w * 0.08f).fillMaxHeight().background(Color(0xFF3B2F25), RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)))
        Cover(item, source, style, Modifier.width(w * 0.92f).fillMaxHeight())
        Box(Modifier.width(w * 0.1f).fillMaxHeight().padding(vertical = 3.dp).background(Brush.horizontalGradient(listOf(PaperEdge, Paper, PaperEdge, Paper))))
    }
}

/** A book's cover: the poster, with a fold of shade by the spine. */
@Composable
private fun Cover(item: MediaItem, source: ShowSource, style: ShowStyle, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(topEnd = 5.dp, bottomEnd = 5.dp))) {
        Picture(item, source.server, style, Modifier.fillMaxSize(), corner = 0)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.35f), 0.08f to Color.Transparent, 0.12f to Color.White.copy(alpha = 0.08f), 0.2f to Color.Transparent)))
    }
}

/** The left page: the title's artwork in a margin, its name in small capitals under it. */
@Composable
private fun LeftPage(item: MediaItem, source: ShowSource, style: ShowStyle) {
    Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Picture(item, source.server, style, Modifier.fillMaxWidth().weight(1f), kind = ImageKind.BACKDROP, heightPx = 540, corner = 2)
        Picture(item, source.server, style, Modifier.fillMaxWidth().weight(1.25f), corner = 2)
        BasicText(source.titleOf(item).uppercase(), style = style.label.copy(color = Ink.copy(alpha = 0.6f), fontFamily = FontFamily.Serif), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The right page [page]: the story; facts and cast; more like this. Asked of the server as each is opened. */
@Composable
private fun RightPage(item: MediaItem, page: Int, source: ShowSource, style: ShowStyle) {
    val serif = FontFamily.Serif
    Column(Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (page) {
            0 -> {
                BasicText(source.titleOf(item), style = style.display.copy(color = Ink, fontFamily = serif, fontWeight = FontWeight.Bold), maxLines = 3, overflow = TextOverflow.Ellipsis)
                BasicText(facts(item), style = style.label.copy(color = Ink.copy(alpha = 0.65f), fontFamily = FontFamily.Monospace))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.copy(alpha = 0.2f)))
                source.overviewOf(item)?.let { BasicText(it, style = style.body.copy(color = Ink.copy(alpha = 0.88f), fontFamily = serif), overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)) }
                    ?: Box(Modifier.weight(1f))
            }
            1 -> {
                val full by produceState<MediaItem?>(null, item.id) { value = runCatching { source.server.getItem(item.id) }.getOrNull() }
                val it = full ?: item
                BasicText("Facts & cast", style = style.title.copy(color = Ink, fontFamily = serif, fontWeight = FontWeight.Bold))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.copy(alpha = 0.2f)))
                fun line(label: String, value: String?) = value?.takeIf { v -> v.isNotBlank() }?.let { label to it }
                val crew = it.people.filter { p -> p.type == "Director" }.joinToString(", ") { p -> p.name }
                val writers = it.people.filter { p -> p.type == "Writer" }.joinToString(", ") { p -> p.name }
                val cast = it.people.filter { p -> p.type == "Actor" || p.type == null }.take(6).joinToString("\n") { p -> p.name + (p.role?.let { r -> " — $r" } ?: "") }
                listOfNotNull(
                    line("Genres", it.genres.joinToString(", ")), line("Studio", it.studios.take(2).joinToString(", ")),
                    line("Directed by", crew), line("Written by", writers), line("Cast", cast),
                ).forEach { (label, value) ->
                    BasicText(label.uppercase(), style = style.label.copy(color = Ink.copy(alpha = 0.5f)))
                    BasicText(value, style = style.body.copy(color = Ink, fontFamily = serif), maxLines = 6, overflow = TextOverflow.Ellipsis)
                }
                if (full == null) BasicText("…", style = style.body.copy(color = Ink.copy(alpha = 0.4f)))
                Box(Modifier.weight(1f))
            }
            else -> {
                val like by produceState<List<MediaItem>?>(null, item.id) { value = runCatching { source.server.similar(item.id).take(7) }.getOrDefault(emptyList()) }
                BasicText("More like this", style = style.title.copy(color = Ink, fontFamily = serif, fontWeight = FontWeight.Bold))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.copy(alpha = 0.2f)))
                when {
                    like == null -> BasicText("…", style = style.body.copy(color = Ink.copy(alpha = 0.4f)))
                    like!!.isEmpty() -> BasicText("Nothing the server thinks is close.", style = style.body.copy(color = Ink.copy(alpha = 0.6f), fontFamily = serif))
                    else -> like!!.forEach { o ->
                        BasicText(o.name + (o.productionYear?.let { y -> "  ($y)" } ?: ""), style = style.body.copy(color = Ink, fontFamily = serif), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box(Modifier.weight(1f))
            }
        }
        BasicText("— ${page + 1} of $PAGES —", style = style.label.copy(color = Ink.copy(alpha = 0.45f), fontFamily = serif), modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}
