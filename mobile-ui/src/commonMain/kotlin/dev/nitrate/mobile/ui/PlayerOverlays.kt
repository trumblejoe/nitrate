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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
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
fun BoxScope.GestureFeedbackView(feedback: GestureFeedback?, onMiddle: (brightness: Boolean) -> Unit = {}) {
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
            // A big target back to the middle, for after an overshoot.
            if (kotlin.math.abs(feedback.level - 0.5f) > 0.05f) {
                androidx.compose.material3.FilledTonalButton(
                    onClick = { onMiddle(feedback.brightness) },
                    modifier = Modifier.padding(top = 12.dp).heightIn(min = 48.dp),
                ) { Text("Set to 50%") }
            }
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
 * It kept buffering: "Lower the quality" in the corner ([toBps] null hides it). The press plays on
 * from here at that quality for this title; ✕ puts it aside (it comes back if the buffering does).
 */
@Composable
fun BoxScope.LowerQualityPrompt(toBps: Int?, onLower: (Int) -> Unit, onDismiss: () -> Unit) {
    AnimatedVisibility(
        toBps != null,
        enter = slideInHorizontally { it / 2 } + fadeIn(),
        exit = slideOutHorizontally { it / 2 } + fadeOut(),
        modifier = Modifier.align(Alignment.BottomEnd).padding(start = 16.dp, end = 16.dp, bottom = 96.dp),
    ) {
        val to = toBps ?: return@AnimatedVisibility
        Row(
            Modifier.clip(RoundedCornerShape(24.dp)).background(Color(0xE6101216)).padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("It keeps buffering", color = Color.White, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(12.dp))
            Text(
                "Lower to ${dev.nitrate.core.playback.formatBitrate(to)}",
                color = Color.Black, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White).pressable({ onLower(to) }).padding(horizontal = 16.dp, vertical = 10.dp),
            )
            androidx.compose.material3.IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, "Not now", tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * The next episode's card with a countdown ring; plays it when the ring completes.
 * [seconds] counts down from when the card appears; null (the next film in a collection) waits
 * for "Play now", since a film can have scenes during its credits.
 */
@Composable
fun BoxScope.UpNextCard(next: MediaItem, server: MediaServer, seconds: Int?, onPlay: () -> Unit, onHide: () -> Unit, onCountdownEnd: () -> Unit = onPlay) {
    // Spoiler-safe: an episode not seen yet shows as "S1:E5", blurred.
    @Suppress("NAME_SHADOWING") val next = spoilerSafe(next)
    var left by androidx.compose.runtime.remember(next.id) { mutableIntStateOf(seconds ?: 0) }
    LaunchedEffect(next.id) {
        if (seconds == null) return@LaunchedEffect
        while (left > 0) {
            delay(1_000)
            left--
        }
        onCountdownEnd()
    }
    val accent = accentColor()
    androidx.compose.animation.AnimatedVisibility(true, enter = slideInHorizontally { it } + fadeIn(), modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 88.dp)) {
        Row(
            Modifier.width(380.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xE6101216))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(20.dp)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Color.DarkGray).pressable(onPlay), Alignment.Center) {
                server.cardImage(next, CardShape.WIDE, 240)?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().spoilerBlur(next)) }
                Box(Modifier.fillMaxSize().background(Color(0x55000000)))
                // Countdown ring around a play icon.
                Canvas(Modifier.size(46.dp)) {
                    val stroke = 4.dp.toPx()
                    drawCircle(Color.White.copy(alpha = 0.25f), style = Stroke(stroke))
                    if (seconds != null) drawArc(accent, -90f, 360f * left / seconds, false, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Icon(Icons.Default.PlayArrow, "Play now", tint = Color.White)
            }
            Column(Modifier.padding(start = 14.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (seconds == null) "NEXT IN THE COLLECTION" else "UP NEXT · ${left}s", color = accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text(next.episodeLabel ?: (next.name + (next.productionYear?.let { " ($it)" } ?: "")), color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
    val context = coil3.compose.LocalPlatformContext.current
    val cache = remember { HashMap<String, ImageBitmap>() }
    val state = produceState(cache[url], url) {
        cache[url]?.let { value = it; return@produceState }
        val request = dev.nitrate.mobile.platform.softwareImage(ImageRequest.Builder(context).data(url))
            .httpHeaders(NetworkHeaders.Builder().set("Authorization", server.authorizationHeader()).build())
            .build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return@produceState
        value = dev.nitrate.mobile.platform.toImageBitmap(result.image).also { cache[url] = it }
    }
    return state.value
}

/**
 * A scene during or after the credits: "Skipped the credits" with "Watch credits" (undo), the
 * scene offered with "Skip to it", or a heads-up to stay. Goes away by itself after a while.
 */
@Composable
fun BoxScope.CreditSceneBanner(action: dev.nitrate.core.playback.CreditSkipper.Action?, onAct: (dev.nitrate.core.playback.CreditSkipper.Action) -> Unit, onDone: () -> Unit) {
    LaunchedEffect(action) {
        if (action == null) return@LaunchedEffect
        delay(if (action is dev.nitrate.core.playback.CreditSkipper.Action.HeadsUp) 6_000 else 10_000)
        onDone()
    }
    AnimatedVisibility(
        action != null,
        enter = slideInHorizontally { -it / 2 } + fadeIn(),
        exit = slideOutHorizontally { -it / 2 } + fadeOut(),
        modifier = Modifier.align(Alignment.BottomStart).padding(start = 32.dp, bottom = 96.dp),
    ) {
        val a = action ?: return@AnimatedVisibility
        val button = when (a) {
            is dev.nitrate.core.playback.CreditSkipper.Action.Skip -> "Watch credits"
            is dev.nitrate.core.playback.CreditSkipper.Action.Offer -> "Skip to it"
            is dev.nitrate.core.playback.CreditSkipper.Action.HeadsUp -> null
        }
        Row(
            Modifier.clip(RoundedCornerShape(24.dp)).background(Color(0xE6101216)).border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(24.dp))
                .padding(start = 18.dp, end = if (button != null) 6.dp else 18.dp, top = 6.dp, bottom = 6.dp).heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(dev.nitrate.core.playback.CreditSkipper.message(a), color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 2)
            if (button != null) {
                Spacer(Modifier.width(12.dp))
                Text(
                    button, color = Color.Black, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White).pressable({ onAct(a); onDone() }).padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/**
 * "Still watching?": episodes have played on with nothing pressed, so the next one waits. Keep
 * watching, go back to where the last press was (undoing what played since), or stop. No answer
 * for a while: back to the last press, and stop.
 */
@Composable
fun StillWatchingPrompt(
    next: MediaItem,
    rewind: dev.nitrate.core.playback.StillWatching.Rewind?,
    onKeepWatching: () -> Unit,
    onGoBack: () -> Unit,
    onStop: () -> Unit,
    onNoAnswer: () -> Unit,
) {
    LaunchedEffect(next.id) {
        delay(dev.nitrate.core.playback.StillWatching.PROMPT_MS)
        onNoAnswer()
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = {},
        title = { Text("Still watching?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Up next: ${next.displayTitle}" + (if (next.isEpisode) " · S${next.seasonNumber ?: "?"}:E${next.indexNumber ?: "?"}" else ""))
                if (rewind != null) Text(
                    "Nothing's been pressed since ${dev.nitrate.core.model.formatClock(rewind.back.positionMs)} into an earlier episode. " +
                        "If you dozed off, go back there" + if (rewind.unwatch.isNotEmpty()) " and what played since is marked unwatched again." else ".",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onKeepWatching) { Text("Keep watching") } },
        dismissButton = {
            Row {
                if (rewind != null) androidx.compose.material3.TextButton(onClick = onGoBack) { Text("Go back") }
                androidx.compose.material3.TextButton(onClick = onStop) { Text("Stop") }
            }
        },
    )
}

