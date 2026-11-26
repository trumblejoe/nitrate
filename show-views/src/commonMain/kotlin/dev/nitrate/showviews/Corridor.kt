package dev.nitrate.showviews

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

private val Wire = Color(0xFF6E86B8)
private const val STEP = 0.7f

/** A slice of the corridor at depth [z]: its walls' x, ceiling and floor y, and how near (scale). */
private class Slice(val left: Float, val right: Float, val top: Float, val bottom: Float, val s: Float)

/**
 * Corridor: dark, a gallery corridor drawn in thin grid lines, bending gently left and right ahead,
 * posters hung on both walls. ▶/▼ walk forward, ◀/▲ back; as a poster comes level with you the grid
 * round it lights up. OK peels it off the wall to face you with its details (◀▶ Details or Play, OK
 * opens), Back hangs it up again. Touch: drag to walk, tap to peel.
 */
@Composable
internal fun Corridor(source: ShowSource, style: ShowStyle, host: ShowHost, modifier: Modifier) {
    val items = source.items
    val index = rememberChoice(source)
    val pos by animateFloatAsState(index.intValue.toFloat(), spring(dampingRatio = 0.9f, stiffness = 130f), label = "corridor")
    var peeled by remember { mutableStateOf(false) }
    val peel = remember { Animatable(0f) }
    var button by remember { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(peeled) { peel.animateTo(if (peeled) 1f else 0f, tween(if (peeled) 520 else 380)) }
    val current = items.getOrNull(index.intValue)
    fun step(by: Int): Boolean {
        val next = index.intValue + by
        if (next !in items.indices) return false
        index.intValue = next
        return true
    }
    host.backHandler(peeled) { peeled = false }
    Row(modifier) {
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxHeight().clipToBounds()
                .background(Brush.verticalGradient(listOf(Color(0xFF05070C), Color(0xFF0C1220), Color(0xFF05070C))))
                .onFocusChanged { focused = it.hasFocus }
                .showInput(current?.let { source.titleOf(it) + if (peeled) ", details open" else "" }, peeled, host.focus) { k ->
                    when (k) {
                        ShowKey.RIGHT -> if (peeled) { button = 1; true } else step(1)
                        ShowKey.LEFT -> if (peeled) { button = 0; true } else step(-1)
                        ShowKey.DOWN -> if (peeled) true else step(1) || true
                        ShowKey.UP -> if (peeled) true else step(-1)
                        ShowKey.OK -> {
                            val item = current
                            when {
                                item == null -> Unit
                                !peeled -> { button = 0; peeled = true }
                                button == 0 -> source.onOpen(item)
                                else -> source.onPlay(item)
                            }
                            true
                        }
                        ShowKey.BACK -> { peeled = false; true }
                        ShowKey.PLAY -> { current?.let(source.onPlay); true }
                    }
                }
                .pointerInput(items.size) {
                    var drag = 0f
                    detectDragGestures(onDragEnd = { drag = 0f }) { change, amount ->
                        change.consume()
                        if (peeled) return@detectDragGestures
                        drag += amount.y + amount.x
                        val stepPx = 80.dp.toPx()
                        while (drag <= -stepPx) { step(1); drag += stepPx }
                        while (drag >= stepPx) { step(-1); drag -= stepPx }
                    }
                }
                .pointerInput(Unit) { detectTapGestures { if (current != null) { button = 0; peeled = !peeled } } },
        ) {
            val density = LocalDensity.current
            val wPx = with(density) { maxWidth.toPx() }
            val hPx = with(density) { maxHeight.toPx() }
            val wide = wPx > hPx
            val p = peel.value
            // Depth z from the viewer (z = 0 is where you stand); the corridor bends with how far along you are.
            fun slice(z: Float): Slice {
                val s = 1f / (1f + 0.55f * z.coerceAtLeast(-0.6f))
                val bend = sin((pos + z) * 0.45f) * wPx * 0.16f * (1f - s)
                val cx = wPx / 2 + bend
                val cy = hPx * 0.48f
                val halfW = wPx * (if (wide) 0.46f else 0.6f) * s
                val halfH = hPx * 0.5f * s
                return Slice(cx - halfW, cx + halfW, cy - halfH, cy + halfH, s)
            }
            val base = pos - floor(pos)
            val chosenZ = (index.intValue - pos) * STEP + STEP
            // The wireframe: a frame every half step into the dark, joined along the walls; the ones round the chosen poster lit.
            Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - 0.5f * p }) {
                var prev: Slice? = null
                var z = -base * STEP * 0.5f
                while (z < 14f) {
                    val sl = slice(z)
                    val lit = kotlin.math.abs(z - chosenZ) < STEP * 0.26f
                    val a = (sl.s * 0.9f).coerceIn(0.05f, 0.6f)
                    val c = if (lit) style.accent.copy(alpha = 0.85f) else Wire.copy(alpha = a)
                    val w = if (lit) 2.4f else 1.1f
                    drawLine(c, Offset(sl.left, sl.top), Offset(sl.right, sl.top), w)
                    drawLine(c, Offset(sl.left, sl.bottom), Offset(sl.right, sl.bottom), w)
                    drawLine(c, Offset(sl.left, sl.top), Offset(sl.left, sl.bottom), w)
                    drawLine(c, Offset(sl.right, sl.top), Offset(sl.right, sl.bottom), w)
                    prev?.let { pr ->
                        val la = Wire.copy(alpha = a * 0.7f)
                        for (k in 0..4) {
                            val f = k / 4f
                            // Along the floor, ceiling and both walls.
                            drawLine(la, Offset(lerp(pr.left, pr.right, f), pr.bottom), Offset(lerp(sl.left, sl.right, f), sl.bottom), 1f)
                            drawLine(la, Offset(lerp(pr.left, pr.right, f), pr.top), Offset(lerp(sl.left, sl.right, f), sl.top), 1f)
                            drawLine(la, Offset(pr.left, lerp(pr.top, pr.bottom, f)), Offset(sl.left, lerp(sl.top, sl.bottom, f)), 1f)
                            drawLine(la, Offset(pr.right, lerp(pr.top, pr.bottom, f)), Offset(sl.right, lerp(sl.top, sl.bottom, f)), 1f)
                        }
                    }
                    prev = sl
                    z += STEP * 0.5f
                }
                // The far end's glow.
                val end = slice(14f)
                drawCircle(Brush.radialGradient(listOf(Wire.copy(alpha = 0.35f), Color.Transparent), center = Offset((end.left + end.right) / 2, (end.top + end.bottom) / 2), radius = wPx * 0.12f),
                    wPx * 0.12f, Offset((end.left + end.right) / 2, (end.top + end.bottom) / 2))
            }
            // The posters, on alternate walls, nearest drawn last.
            val first = (floor(pos).toInt() - 1).coerceAtLeast(0)
            val last = (floor(pos).toInt() + 14).coerceAtMost(items.lastIndex)
            for (i in last downTo first) {
                val z = (i - pos) * STEP + STEP
                if (z < -0.3f) continue
                androidx.compose.runtime.key(items[i].id) {
                val chosen = i == index.intValue
                val sl = slice(z)
                val leftWall = i % 2 == 0
                val h = (sl.bottom - sl.top) * 0.46f
                val w = h * 2f / 3f
                val wallX = if (leftWall) sl.left + w * 0.38f else sl.right - w * 0.38f
                val k = if (chosen) p else 0f
                val fh = hPx * (if (wide) 0.72f else 0.5f)
                val hh = lerp(h, fh, k)
                val ww = hh * 2f / 3f
                val x = lerp(wallX, if (wide) wPx * 0.26f else wPx * 0.5f, k)
                val y = lerp((sl.top + sl.bottom) / 2 - h * 0.05f, if (wide) hPx * 0.5f else hPx * 0.32f, k)
                val fade = if (z < 0) (1f + z / 0.3f).coerceIn(0f, 1f) else (sl.s * 2.2f).coerceIn(0.15f, 1f)
                // A fixed-size card scaled on screen (its picture never asked for again); scaled about its wall edge.
                val baseH = fh
                val baseW = fh * 2f / 3f
                val sc = hh / baseH
                val left = if (leftWall) x - ww / 2 else x + ww / 2 - baseW
                Box(
                    Modifier.offset { IntOffset(left.roundToInt(), (y - baseH / 2).roundToInt()) }
                        .size(with(density) { baseW.toDp() }, with(density) { baseH.toDp() })
                        .zIndex(if (chosen && p > 0f) 100f else -z)
                        .graphicsLayer {
                            scaleX = sc
                            scaleY = sc
                            // Flat on the wall, turned towards the corridor; peeled, square to you.
                            rotationY = lerp(if (leftWall) 62f else -62f, 0f, k)
                            transformOrigin = TransformOrigin(if (leftWall) 0f else 1f, 0.5f)
                            cameraDistance = 10f * density.density
                            alpha = fade * (if (!chosen) 1f - 0.75f * p else 1f)
                        },
                ) {
                    Picture(items[i], source.server, style, Modifier.fillMaxSize()
                        .then(if (chosen && (focused || !source.tv)) Modifier.border(3.dp, style.accent, RoundedCornerShape(4.dp)) else Modifier), corner = 4)
                }
                }
            }
            if (current != null && p > 0.02f) {
                Box(Modifier.fillMaxSize().zIndex(50f).background(Color.Black.copy(alpha = 0.55f * p)))
                PeelDetails(
                    current, source, style, button, dark = true,
                    modifier = (if (wide) Modifier.align(Alignment.CenterEnd).width(maxWidth * 0.5f).padding(end = 40.dp)
                    else Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 24.dp)).zIndex(120f)
                        .graphicsLayer { alpha = p; translationX = (1f - p) * 60f },
                )
            } else if (current != null) {
                BasicText(
                    source.titleOf(current) + "  ·  " + facts(current),
                    style = style.title.copy(color = Color.White.copy(alpha = 0.85f)), maxLines = 1,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 28.dp, bottom = 16.dp),
                )
            }
        }
        host.letters?.invoke(Modifier.padding(start = 10.dp)) { i -> index.intValue = i; peeled = false; runCatching { host.focus?.requestFocus() } }
    }
}
