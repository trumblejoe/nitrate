package dev.nitrate.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.MediaSegment
import dev.nitrate.core.model.TrickplayInfo
import dev.nitrate.core.model.formatClock
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** What a gesture just did, shown briefly in the middle or at the side of the picture. */
sealed interface GestureFeedback {
    data class Seek(val totalMs: Long) : GestureFeedback
    data class Level(val brightness: Boolean, val level: Float) : GestureFeedback
    data class Zoom(val fill: Boolean) : GestureFeedback
}

@Composable
fun BoxScope.GestureFeedbackView(feedback: GestureFeedback?) {
    when (feedback) {
        null -> Unit
        is GestureFeedback.Seek -> {
            // A soft half-moon on the side that was tapped, with the running total.
            val forward = feedback.totalMs > 0
            Box(
                Modifier.align(if (forward) Alignment.CenterEnd else Alignment.CenterStart).fillMaxHeight().fillMaxWidth(0.3f)
                    .clip(if (forward) RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50) else RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50))
                    .background(Color.White.copy(alpha = 0.12f)),
                Alignment.Center,
            ) {
                Text((if (forward) "+" else "−") + "${kotlin.math.abs(feedback.totalMs) / 1000}s", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
            }
        }
        is GestureFeedback.Level -> Column(
            Modifier.align(Alignment.Center).clip(RoundedCornerShape(20.dp)).background(Color(0xCC000000)).padding(horizontal = 22.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(if (feedback.brightness) "Brightness" else "Volume", color = Color.White, style = MaterialTheme.typography.labelLarge)
            Box(Modifier.padding(top = 10.dp).width(160.dp).height(6.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f))) {
                Box(Modifier.fillMaxWidth(feedback.level).height(6.dp).background(accentColor()))
            }
            Text("${(feedback.level * 100).roundToInt()}%", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
        }
        is GestureFeedback.Zoom -> Text(
            if (feedback.fill) "Fill screen" else "Fit picture",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(20.dp)).background(Color(0xCC000000)).padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

/** "Skip intro" / "Skip credits" in the corner while a segment plays. */
@Composable
fun BoxScope.SkipButton(segment: MediaSegment?, onSkip: (MediaSegment) -> Unit) {
    AnimatedVisibility(
        segment != null,
        enter = slideInHorizontally { it / 2 } + fadeIn(),
        exit = slideOutHorizontally { it / 2 } + fadeOut(),
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 32.dp, bottom = 96.dp),
    ) {
        val s = segment ?: return@AnimatedVisibility
        Row(
            Modifier.clip(RoundedCornerShape(24.dp)).background(Color.White).pressable({ onSkip(s) }).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(s.kind.skipLabel, color = Color.Black, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * The next episode's card with a countdown ring; plays it when the ring completes.
 * [seconds] counts down from when the card appears.
 */
@Composable
fun BoxScope.UpNextCard(next: MediaItem, server: MediaServer, seconds: Int, onPlay: () -> Unit, onHide: () -> Unit) {
    var left by androidx.compose.runtime.remember(next.id) { mutableIntStateOf(seconds) }
    LaunchedEffect(next.id) {
        while (left > 0) {
            delay(1_000)
            left--
        }
        onPlay()
    }
    val accent = accentColor()
    androidx.compose.animation.AnimatedVisibility(true, enter = slideInHorizontally { it } + fadeIn(), modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 88.dp)) {
        Row(
            Modifier.width(380.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xE6101216))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(20.dp)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Color.DarkGray).pressable(onPlay), Alignment.Center) {
                server.cardImage(next, CardShape.WIDE, 240)?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                Box(Modifier.fillMaxSize().background(Color(0x55000000)))
                // Countdown ring around a play icon.
                Canvas(Modifier.size(46.dp)) {
                    val stroke = 4.dp.toPx()
                    drawCircle(Color.White.copy(alpha = 0.25f), style = Stroke(stroke))
                    drawArc(accent, -90f, 360f * left / seconds, false, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Icon(Icons.Default.PlayArrow, "Play now", tint = Color.White)
            }
            Column(Modifier.padding(start = 14.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("UP NEXT · ${left}s", color = accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text(next.episodeLabel ?: next.name, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Text("Play now", color = contentOn(accent), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(accent).pressable(onPlay).padding(horizontal = 12.dp, vertical = 6.dp))
                    Text("Hide", color = Color.White, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.14f)).pressable(onHide).padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        }
    }
}

/** A frame preview above the seek bar while scrubbing, from the server's trickplay sheets. */
@Composable
fun BoxScope.TrickplayPreview(server: MediaServer, itemId: String, info: TrickplayInfo?, positionMs: Long, x: Float, barTop: Float) {
    val density = LocalDensity.current
    val width = 200.dp
    val widthPx = with(density) { width.toPx() }
    val tile = info?.tileFor(positionMs)
    val sheet = tile?.let { rememberTrickplaySheet(server, server.trickplaySheetUrl(itemId, info, it.sheet)) }
    val heightPx = if (info != null) widthPx * info.height / info.width else 0f
    val left = (x - widthPx / 2).coerceIn(with(density) { 8.dp.toPx() }, Float.MAX_VALUE)
    val top = barTop - heightPx - with(density) { 36.dp.toPx() }
    Column(Modifier.offset { IntOffset(left.roundToInt(), top.roundToInt()) }, horizontalAlignment = Alignment.CenterHorizontally) {
        if (info != null && tile != null) {
            Canvas(
                Modifier.width(width).aspectRatio(info.width.toFloat() / info.height).clip(RoundedCornerShape(10.dp))
                    .background(Color.Black).border(2.dp, Color.White, RoundedCornerShape(10.dp)),
            ) {
                sheet?.let { img ->
                    drawImage(img, srcOffset = IntOffset(tile.column * info.width, tile.row * info.height), srcSize = IntSize(info.width, info.height),
                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
                }
            }
        }
        Text(formatClock(positionMs), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xCC000000)).padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

/** One trickplay sheet as a bitmap (sheets need the auth header, so they go through Coil by hand). */
@Composable
private fun rememberTrickplaySheet(server: MediaServer, url: String): ImageBitmap? {
    val context = LocalContext.current
    val cache = remember { HashMap<String, ImageBitmap>() }
    val state = produceState(cache[url], url) {
        cache[url]?.let { value = it; return@produceState }
        val request = ImageRequest.Builder(context).data(url).allowHardware(false)
            .httpHeaders(NetworkHeaders.Builder().set("Authorization", server.authorizationHeader()).build())
            .build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return@produceState
        value = result.image.toBitmap().asImageBitmap().also { cache[url] = it }
    }
    return state.value
}
