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
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/** How far round the spiral each title sits from the next (radians), and how fast it shrinks into the light. */
private const val TURN = 0.6f
private const val SHRINK = 0.38f

/**
 * Vortex: dark, the titles on a spiral curving in to a point of light, each card turned to face the
 * middle. ▶/▼ flies down the tunnel (the spiral turns towards you, the nearest card is the chosen one),
 * ◀/▲ back. OK peels the chosen card out to the front with its details; then ◀▶ pick Details or
 * Play and OK opens it; Back puts it back. Touch: drag to fly, tap to peel.
 */
@Composable
internal fun Vortex(source: ShowSource, style: ShowStyle, host: ShowHost, modifier: Modifier) {
    val items = source.items
    val index = rememberChoice(source)
    val pos by animateFloatAsState(index.intValue.toFloat(), spring(dampingRatio = 0.9f, stiffness = 140f), label = "vortex")
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
                .background(Brush.radialGradient(listOf(Color(0xFF1B2238), Color(0xFF07080C)), radius = 1400f))
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
                        val stepPx = 70.dp.toPx()
                        while (drag <= -stepPx) { step(1); drag += stepPx }
                        while (drag >= stepPx) { step(-1); drag -= stepPx }
                    }
                }
                .pointerInput(Unit) { detectTapGestures { if (current != null) { button = 0; peeled = !peeled } } },
        ) {
            val density = LocalDensity.current
            val wPx = with(density) { maxWidth.toPx() }
            val hPx = with(density) { maxHeight.toPx() }
            // A tall screen (a phone upright): the tunnel centred, the card coming out to the top half.
            val wide = wPx > hPx
            val cx = wPx * (if (wide) 0.58f else 0.5f)
            val cy = hPx * (if (wide) 0.42f else 0.4f)
            val ampX = if (wide) 1.25f else 0.62f
            val reach = minOf(wPx, hPx * 1.6f) * 0.5f
            val cardH = minOf(maxHeight * 0.5f, maxWidth * 0.56f)
            val cardHPx = with(density) { cardH.toPx() }
            val p = peel.value
            fun place(d: Float): Triple<Float, Float, Float> {
                // Angle round the spiral, how near (scale), and where on screen the card's middle goes.
                val theta = (2.45f + d * TURN)
                val s = if (d >= 0) 1f / (1f + SHRINK * d) else 1f - d * 0.9f
                val x = cx + cos(theta) * reach * ampX * s
                val y = cy + sin(theta) * reach * 0.55f * s
                return Triple(x, y, s)
            }
            // The tunnel: a faint spiral thread through where the cards sit, and the light at its end.
            Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - 0.6f * p }) {
                val path = Path()
                var first = true
                var d = -0.8f
                val base = pos - floor(pos)
                while (d < 22f) {
                    val (x, y, _) = place(d - base)
                    if (first) path.moveTo(x, y) else path.lineTo(x, y)
                    first = false
                    d += 0.25f
                }
                drawPath(path, Color.White.copy(alpha = 0.12f), style = Stroke(width = 1.5f))
                for (ring in 1..6) {
                    val r = reach * 1.25f / (1f + SHRINK * (ring * 2.2f - base * 0.5f))
                    drawOval(Color(0xFF8FB7FF).copy(alpha = 0.05f), topLeft = Offset(cx - r, cy - r * 0.62f), size = androidx.compose.ui.geometry.Size(r * 2, r * 1.24f), style = Stroke(1f))
                }
                drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFFBFD4FF).copy(alpha = 0.55f), Color.Transparent), center = Offset(cx, cy), radius = reach * 0.32f), reach * 0.32f, Offset(cx, cy))
            }
            val first = (floor(pos).toInt() - 1).coerceAtLeast(0)
            val last = (floor(pos).toInt() + 16).coerceAtMost(items.lastIndex)
            // Each card keeps its picture as it moves (keyed by title, fixed size, scaled on screen): no reloads.
            val baseScale = if (wide) 1.5f else 1.1f
            val baseH = cardHPx * baseScale
            val baseW = baseH * 2f / 3f
            for (i in last downTo first) {
                val item = items[i]
                val d = i - pos
                if (d < -1.05f) continue
                androidx.compose.runtime.key(items[i].id) {
                val chosen = i == index.intValue
                val (tx, ty, ts) = place(d)
                val theta = 2.45f + d * TURN
                // Peeled: the chosen card comes out to the left third, flat and large; the rest fade back.
                val k = if (chosen) p else 0f
                val s = lerp(ts, if (wide) 1.5f else 1.1f, k)
                val h = cardHPx * s
                val w = h * 2f / 3f
                val x = lerp(tx, if (wide) wPx * 0.24f else wPx * 0.5f, k)
                val y = lerp(ty, if (wide) hPx * 0.5f else hPx * 0.3f, k)
                val depthShade = (d.coerceAtLeast(0f) * 0.07f).coerceAtMost(0.8f)
                val fade = if (d < 0) (1f + d).coerceIn(0f, 1f) else 1f
                Box(
                    Modifier.offset { IntOffset((x - baseW / 2).roundToInt(), (y - baseH / 2).roundToInt()) }
                        .size(with(density) { baseW.toDp() }, with(density) { baseH.toDp() })
                        .zIndex(if (chosen && p > 0f) 100f else -d)
                        .graphicsLayer {
                            scaleX = h / baseH
                            scaleY = h / baseH
                            // Turned to face the middle of the tunnel, a little twisted along it.
                            rotationY = lerp(cos(theta) * -34f, 0f, k)
                            rotationX = lerp(sin(theta) * 26f, 0f, k)
                            rotationZ = lerp(((theta * 180f / PI.toFloat()) % 360f - 140f) * 0.08f, 0f, k)
                            cameraDistance = 12f * density.density
                            alpha = fade * (if (!chosen) 1f - 0.75f * p else 1f)
                        },
                ) {
                    Picture(item, source.server, style, Modifier.fillMaxSize()
                        .then(if (chosen && (focused || !source.tv)) Modifier.border(3.dp, if (p > 0.5f) style.accent else Color.White, RoundedCornerShape(10.dp)) else Modifier),
                        heightPx = 600, corner = 10)
                    if (depthShade > 0.01f) Box(Modifier.fillMaxSize().background(Color(0xFF07080C).copy(alpha = depthShade), RoundedCornerShape(10.dp)))
                }
                }
            }
            if (current != null && p > 0.02f) {
                // A shade over the tunnel so the words read.
                Box(Modifier.fillMaxSize().zIndex(50f).background(Color.Black.copy(alpha = 0.55f * p)))
                PeelDetails(
                    current, source, style, button, dark = true,
                    modifier = (if (wide) Modifier.align(Alignment.CenterEnd).width(maxWidth * 0.48f).padding(end = 40.dp)
                    else Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 24.dp)).zIndex(120f)
                        .graphicsLayer { alpha = p; translationX = (1f - p) * 60f },
                )
            } else if (current != null) {
                // The chosen title's name, low and small, so you know where you are without peeling.
                BasicText(
                    source.titleOf(current) + "  ·  " + facts(current),
                    style = style.title.copy(color = Color.White.copy(alpha = 0.85f)),
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 28.dp, top = 18.dp),
                )
            }
        }
        host.letters?.invoke(Modifier.padding(start = 10.dp)) { i -> index.intValue = i; peeled = false; runCatching { host.focus?.requestFocus() } }
    }
}
