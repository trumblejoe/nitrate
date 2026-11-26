package dev.nitrate.showviews

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.nitrate.core.model.ImageKind
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Panes: light; each title a tall sheet of frosted glass with its artwork on it, lined up one behind
 * another, paler and softer into the distance. ▶/▼ fly you through the front pane (it grows past you
 * and is gone; the next sharpens with its name and story on the glass), ◀/▲ fly back; OK opens the
 * title. Frosting is a pale wash, so it looks the same on every device. Touch: drag to fly, tap to open.
 */
@Composable
internal fun Panes(source: ShowSource, style: ShowStyle, host: ShowHost, modifier: Modifier) {
    val items = source.items
    val index = rememberChoice(source)
    val pos by animateFloatAsState(index.intValue.toFloat(), spring(dampingRatio = 0.92f, stiffness = 110f), label = "panes")
    var focused by remember { mutableStateOf(false) }
    val current = items.getOrNull(index.intValue)
    fun step(by: Int): Boolean {
        val next = index.intValue + by
        if (next !in items.indices) return false
        index.intValue = next
        return true
    }
    Row(modifier) {
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxHeight().clipToBounds()
                .background(Brush.linearGradient(listOf(Color(0xFFF1F4F7), Color(0xFFD3DCE5), Color(0xFFE7ECF1)), start = Offset.Zero, end = Offset(1400f, 1200f)))
                .onFocusChanged { focused = it.hasFocus }
                .showInput(current?.let { source.titleOf(it) }, false, host.focus) { k ->
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
            val paneW = wPx * (if (wide) 0.46f else 0.76f)
            val paneH = hPx * (if (wide) 0.7f else 0.56f)
            val first = (floor(pos).toInt() - 1).coerceAtLeast(0)
            val last = (floor(pos).toInt() + 6).coerceAtMost(items.lastIndex)
            for (i in last downTo first) {
                val item = items[i]
                val d = i - pos
                if (d < -0.6f || d > 6.5f) continue
                // Further panes smaller and higher; a passing one swells past you and fades.
                val s = if (d >= 0) 1f / (1f + 0.42f * d) else 1f - d * 2.4f
                val fade = if (d < 0) (1f + d / 0.6f).coerceIn(0f, 1f) else (1.15f - d * 0.16f).coerceIn(0f, 1f)
                val frost = (d * 0.16f).coerceIn(0f, 0.7f)
                val side = if (i % 2 == 0) -1f else 1f
                // The row runs off to the right and up, each pane stepping aside so its edge shows.
                val dd = d.coerceAtLeast(0f)
                val x = wPx * (if (wide) 0.42f else 0.5f) + wPx * 0.16f * dd * s + side * wPx * 0.03f * dd * s - paneW * s / 2
                val y = hPx * 0.52f - hPx * 0.08f * dd * s - paneH * s / 2
                val front = abs(d) < 0.5f
                Box(
                    Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                        .size(with(density) { (paneW * s).toDp() }, with(density) { (paneH * s).toDp() })
                        .zIndex(-d)
                        .graphicsLayer { alpha = fade; rotationY = side * 8f * d.coerceIn(0f, 3f); cameraDistance = 16f * density.density }
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.25f))
                        .border(if (front && (focused || !source.tv)) 2.5.dp else 1.dp, if (front && (focused || !source.tv)) style.accent else Color.White.copy(alpha = 0.85f), RoundedCornerShape(6.dp)),
                ) {
                    Picture(item, source.server, style, Modifier.fillMaxSize().graphicsLayer { alpha = 0.92f }, kind = ImageKind.BACKDROP, heightPx = (paneH * s).roundToInt().coerceIn(240, 1080), corner = 0)
                    // The glass: a pale frost deepening with distance, and a sheen across it.
                    Box(Modifier.fillMaxSize().background(Color(0xFFEFF3F7).copy(alpha = 0.18f + frost)))
                    Box(Modifier.fillMaxSize().background(Brush.linearGradient(
                        0f to Color.White.copy(alpha = 0.32f), 0.25f to Color.Transparent, 0.62f to Color.Transparent, 0.7f to Color.White.copy(alpha = 0.14f), 0.78f to Color.Transparent,
                        start = Offset.Zero, end = Offset(paneW * s, paneH * s),
                    )))
                    if (front) Column(
                        Modifier.align(Alignment.BottomStart).fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC0E1420))))
                            .padding(horizontal = 26.dp, vertical = 20.dp)
                            .graphicsLayer { alpha = (1f - abs(d) * 2f).coerceIn(0f, 1f) },
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        BasicText(source.titleOf(item), style = style.display.copy(color = Color.White), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        BasicText(facts(item), style = style.label.copy(color = Color.White.copy(alpha = 0.8f)))
                        source.overviewOf(item)?.let { BasicText(it, style = style.body.copy(color = Color.White.copy(alpha = 0.88f)), maxLines = 3, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
            BasicText(
                if (source.tv) "▶ fly through · ◀ back · OK open" else "Drag to fly through, tap to open",
                style = style.label.copy(color = Color(0xFF2A3442).copy(alpha = 0.5f)),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
            )
        }
        host.letters?.invoke(Modifier.padding(start = 10.dp)) { i -> index.intValue = i; runCatching { host.focus?.requestFocus() } }
    }
}
