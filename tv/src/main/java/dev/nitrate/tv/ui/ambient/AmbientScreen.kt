package dev.nitrate.tv.ui.ambient

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.nitrate.core.MediaServer
import dev.nitrate.core.ambient.Ambient
import dev.nitrate.core.ambient.AmbientSlide
import dev.nitrate.core.model.MediaItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/** When the remote was last pressed (any key, anywhere in the app). */
object TvIdle {
    private val last = MutableStateFlow(System.currentTimeMillis())
    fun press() { last.value = System.currentTimeMillis() }
    fun idleMs(): Long = System.currentTimeMillis() - last.value
}

/**
 * The ambient screensaver: one title's artwork at a time, drifting slowly, with the time and
 * what it is ("Up next · The Night Reel · S2 · E5"). OK opens the title shown; any other press
 * leaves. The caption moves between corners so nothing stays in one place on the screen.
 */
@Composable
fun AmbientScreen(slides: List<AmbientSlide>, server: MediaServer, onOpen: (MediaItem) -> Unit, onDismiss: () -> Unit, startAt: Int = 0) {
    var index by remember { mutableIntStateOf(startAt) }
    LaunchedEffect(slides) {
        while (true) {
            delay(Ambient.SLIDE_MS)
            index = (index + 1) % slides.size
        }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .focusRequester(focus).focusable()
                .onPreviewKeyEvent { e ->
                    // Act on release, so the press doesn't also reach the page underneath.
                    if (e.type == KeyEventType.KeyUp) {
                        if (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter) onOpen(slides[index].item) else onDismiss()
                    }
                    true
                },
        ) {
            Crossfade(index, animationSpec = tween(2_000), label = "ambient") { i ->
                val slide = slides[i]
                // A slow drift across the picture while it's shown.
                val drift by produceState(0f, i) {
                    val start = System.nanoTime()
                    while (true) {
                        value = ((System.nanoTime() - start) / 1_000_000f / Ambient.SLIDE_MS).coerceAtMost(1.2f)
                        delay(50)
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    AsyncImage(
                        server.backdropUrl(slide.item, 1920), null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            val s = 1.04f + 0.06f * drift
                            scaleX = s; scaleY = s
                            translationX = (if (i % 2 == 0) -1 else 1) * 24f * drift
                        },
                    )
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f))))
                    val corner = if (i % 2 == 0) Alignment.BottomStart else Alignment.BottomEnd
                    Column(
                        Modifier.align(corner).padding(horizontal = 72.dp, vertical = 56.dp),
                        horizontalAlignment = if (i % 2 == 0) Alignment.Start else Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(slide.label.uppercase(), style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.75f))
                        Text(slide.title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = Color.White)
                        slide.detail?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.85f)) }
                    }
                }
            }
            val time by produceState(clock()) {
                while (true) {
                    delay(5_000)
                    value = clock()
                }
            }
            Column(Modifier.align(Alignment.TopEnd).padding(horizontal = 72.dp, vertical = 48.dp), horizontalAlignment = Alignment.End) {
                Text(time, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Light, color = Color.White)
                Text("OK to open", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.6f))
            }
        }
    }
}

private fun clock(): String = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())
