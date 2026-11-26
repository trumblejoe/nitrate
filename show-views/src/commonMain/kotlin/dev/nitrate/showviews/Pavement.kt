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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.nitrate.core.pages.Collage
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.roundToInt

private val Asphalt = Color(0xFF5E5852)
private val Sun = Color(0xFFFFD9A0)

/**
 * Pavement: posters lying in a street in low sun, scattered and askew, long shadows, the road running
 * to a hazy horizon. ▶/▼ move you on down the street: the nearest poster peels up to stand facing you
 * with its name and story beside it, and the one you passed lifts and blows away, tumbling. ◀/▲ go
 * back; OK opens the title. Touch: drag to move on, tap to open.
 */
@Composable
internal fun Pavement(source: ShowSource, style: ShowStyle, host: ShowHost, modifier: Modifier) {
    val items = source.items
    val index = rememberChoice(source)
    val pos by animateFloatAsState(index.intValue.toFloat(), spring(dampingRatio = 0.95f, stiffness = 90f), label = "pavement")
    val stand = remember { Animatable(0f) }
    var focused by remember { mutableStateOf(false) }
    val current = items.getOrNull(index.intValue)
    LaunchedEffect(index.intValue) {
        stand.snapTo(0f)
        delay(240)
        stand.animateTo(1f, tween(750, easing = FastOutSlowInEasing))
    }
    fun step(by: Int): Boolean {
        val next = index.intValue + by
        if (next !in items.indices) return false
        index.intValue = next
        return true
    }
    Row(modifier) {
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxHeight().clipToBounds()
                .onFocusChanged { focused = it.hasFocus }
                .showInput(current?.let { source.titleOf(it) }, false, host.focus, host.repeats) { k ->
                    when (k) {
                        ShowKey.RIGHT, ShowKey.DOWN -> step(1) || k == ShowKey.DOWN
                        ShowKey.LEFT, ShowKey.UP -> step(-1)
                        ShowKey.OK -> { current?.let(source.onOpen); true }
                        ShowKey.PLAY -> { current?.let(source.onPlay); true }
                        ShowKey.BACK -> false
                    }
                }
                .pointerInput(items.size) {
                    var drag = 0f
                    detectDragGestures(onDragEnd = { drag = 0f }) { change, amount ->
                        change.consume()
                        drag += amount.y + amount.x
                        val stepPx = 90.dp.toPx()
                        while (drag <= -stepPx) { step(1); drag += stepPx }
                        while (drag >= stepPx) { step(-1); drag -= stepPx }
                    }
                }
                .pointerInput(Unit) { detectTapGestures { current?.let(source.onOpen) } },
        ) {
            val density = LocalDensity.current
            val wPx = with(density) { maxWidth.toPx() }
            val hPx = with(density) { maxHeight.toPx() }
            val wide = wPx > hPx
            val horizon = hPx * 0.3f
            // Where the ground at depth z (0 at your feet) is on screen, and how near.
            fun groundY(z: Float) = horizon + (hPx * 1.05f - horizon) / (1f + 0.85f * z)
            fun near(z: Float) = 1f / (1f + 0.85f * z)
            // The street: sky haze, the sun low on the right, the road and its markings running to the horizon.
            // Under everything (the posters lie on it at negative depths).
            Canvas(Modifier.fillMaxSize().zIndex(-1000f)) {
                drawRect(Brush.verticalGradient(listOf(Color(0xFFF3D6AE), Color(0xFFF7E6CC)), endY = horizon), size = Size(size.width, horizon))
                drawRect(Brush.verticalGradient(listOf(Color(0xFFB7A894), Asphalt, Color(0xFF3F3A35)), startY = horizon, endY = size.height), topLeft = Offset(0f, horizon), size = Size(size.width, size.height - horizon))
                val sunAt = Offset(size.width * 0.8f, horizon * 0.92f)
                drawCircle(Brush.radialGradient(listOf(Color.White, Sun, Sun.copy(alpha = 0f)), center = sunAt, radius = size.width * 0.3f), size.width * 0.3f, sunAt)
                // Kerbs converging on the horizon, and the centre line's dashes sliding as you move.
                val vp = Offset(size.width * 0.5f, horizon)
                drawLine(Color(0xFFD8CFC2).copy(alpha = 0.5f), vp, Offset(-size.width * 0.4f, size.height), 3f)
                drawLine(Color(0xFFD8CFC2).copy(alpha = 0.5f), vp, Offset(size.width * 1.4f, size.height), 3f)
                val slide = pos - floor(pos)
                var z = -slide
                while (z < 18f) {
                    val y1 = groundY(z + 0.35f)
                    val y0 = groundY(z)
                    val w0 = 10f * near(z)
                    drawLine(Color(0xFFF2E6C9).copy(alpha = 0.55f * near(z)), Offset(vp.x, y0), Offset(vp.x, y1), w0)
                    z += 1f
                }
                // Warm light raking across the ground.
                drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Sun.copy(alpha = 0.14f)), startX = size.width * 0.3f), topLeft = Offset(0f, horizon), size = Size(size.width, size.height - horizon))
            }
            val k = stand.value
            val first = (floor(pos).toInt() - 1).coerceAtLeast(0)
            val last = (floor(pos).toInt() + 9).coerceAtMost(items.lastIndex)
            for (i in last downTo first) {
                val d = i - pos
                if (d < -1.4f) continue
                androidx.compose.runtime.key(items[i].id) {
                val chosen = i == index.intValue
                // Depth along the street: the chosen one at your feet's edge, the rest further on.
                val z = (d * 1.1f + 0.55f).coerceAtLeast(0.05f)
                val s = near(z)
                val scatter = (Collage.rnd(i, 11) - 0.5f) * 2f
                val baseX = wPx / 2 + scatter * wPx * 0.32f * s
                val baseY = groundY(z)
                val w = wPx * (if (wide) 0.17f else 0.36f) * s
                val h = w * 1.5f
                // Standing: the chosen one as it peels up; ones passed stood and are now blowing away.
                val up = when {
                    chosen -> k
                    d < 0 -> 1f
                    else -> 0f
                }
                val blow = (-d).coerceIn(0f, 1.4f)
                val standW = hPx * (if (wide) 0.5f else 0.36f) * 2f / 3f
                val sw = lerp(w, standW, if (chosen) k else if (d < 0) 1f else 0f)
                val sh = sw * 1.5f
                val sx = lerp(baseX, if (wide) wPx * 0.3f else wPx * 0.5f, if (chosen) k else if (d < 0) 1f else 0f) + blow * wPx * 0.7f
                val sy = lerp(baseY, if (wide) hPx * 0.82f else hPx * 0.6f, if (chosen) k else if (d < 0) 1f else 0f) - blow * hPx * 0.5f
                val fade = if (d < 0) (1f - blow / 1.2f).coerceIn(0f, 1f) else (0.25f + s * 1.6f).coerceIn(0f, 1f)
                // The long shadow, cast away from the sun, while it stands.
                if (up > 0.05f && d > -0.5f) Box(
                    Modifier.offset { IntOffset((sx - sw * 1.4f).roundToInt(), (sy - sh * 0.08f).roundToInt()) }
                        .size(with(density) { (sw * 1.4f).toDp() }, with(density) { (sh * 0.22f).toDp() })
                        .zIndex(-z - 0.01f)
                        .graphicsLayer { alpha = 0.35f * up * fade; rotationZ = -8f }
                        .background(Brush.horizontalGradient(listOf(Color.Transparent, Color(0xFF1E1A16))), RoundedCornerShape(50)),
                )
                // A fixed-size poster scaled on screen about its foot: its picture is asked for once.
                val baseW = standW
                val baseH = standW * 1.5f
                Box(
                    Modifier.offset { IntOffset((sx - baseW / 2).roundToInt(), (sy - baseH).roundToInt()) }
                        .size(with(density) { baseW.toDp() }, with(density) { baseH.toDp() })
                        .zIndex(if (chosen) 50f else -z)
                        .graphicsLayer {
                            transformOrigin = TransformOrigin(0.5f, 1f)
                            scaleX = sw / baseW
                            scaleY = sw / baseW
                            // Lying flat (tipped away from you), standing, or tumbling off in the wind.
                            rotationX = lerp(74f, 0f, up) - blow * 40f
                            rotationZ = lerp(scatter * 26f, 0f, up) + blow * 140f
                            rotationY = blow * 50f
                            cameraDistance = 14f * density.density
                            alpha = fade
                        },
                ) {
                    Picture(items[i], source.server, style, Modifier.fillMaxSize()
                        .then(if (chosen && (focused || !source.tv)) Modifier.border(3.dp, Color.White, RoundedCornerShape(2.dp)) else Modifier), corner = 2)
                    // Paper catching the low sun.
                    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Sun.copy(alpha = 0.18f), Color.Transparent, Color.Black.copy(alpha = 0.12f)))))
                }
                }
            }
            // The words of the one standing.
            if (current != null) Column(
                (if (wide) Modifier.align(Alignment.CenterEnd).width(maxWidth * 0.44f).padding(end = 48.dp, bottom = 40.dp)
                else Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 20.dp))
                    .zIndex(60f)
                    .background(Color(0xCC1B1713), RoundedCornerShape(14.dp)).padding(22.dp)
                    .graphicsLayer { alpha = k },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BasicText(source.titleOf(current), style = style.display.copy(color = Color.White), maxLines = 2, overflow = TextOverflow.Ellipsis)
                BasicText(facts(current), style = style.label.copy(color = Sun))
                source.overviewOf(current)?.let { BasicText(it, style = style.body.copy(color = Color.White.copy(alpha = 0.88f)), maxLines = 5, overflow = TextOverflow.Ellipsis) }
                BasicText(if (source.tv) "▶ on down the street · ◀ back · OK open" else "Drag to move on, tap to open", style = style.label.copy(color = Color.White.copy(alpha = 0.5f)))
            }
        }
        host.letters?.invoke(Modifier.padding(start = 10.dp)) { i -> index.intValue = i; runCatching { host.focus?.requestFocus() } }
    }
}
