package dev.nitrate.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.nitrate.core.model.ImageKind
import dev.nitrate.core.model.formatClock
import dev.nitrate.core.music.Listening
import dev.nitrate.core.music.Repeat
import dev.nitrate.mobile.platform.rememberArtworkColor
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

/** The listening player's full screen. */
@Serializable object NowPlayingRoute

/** Where the listening player is in its song, read often enough for a progress bar. */
@Composable
private fun rememberPosition(listening: Listening): Long {
    val position by produceState(listening.positionMs(), listening) {
        while (true) { value = listening.positionMs(); delay(500) }
    }
    return position
}

/**
 * The song playing, above the nav pill wherever you are: its cover, title and artist, play/pause
 * and next, a thin line for how far through. A tap opens Now Playing.
 */
@Composable
fun MiniPlayer(actions: AppActions, modifier: Modifier = Modifier, onShown: (Boolean) -> Unit = {}, player: Listening? = actions.container.listening) {
    val listening = player ?: return
    val state by listening.state.collectAsState()
    androidx.compose.runtime.LaunchedEffect(state.active) { onShown(state.active) }
    val song = state.current ?: return
    val position = rememberPosition(listening)
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .pressable({ actions.nav.navigate(NowPlayingRoute) }),
    ) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.fillMaxHeight().aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.08f))) {
                actions.server.imageUrl(song, ImageKind.PRIMARY, 160)?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(song.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artists.joinToString(", ").ifEmpty { song.album ?: "" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = listening::toggle) {
                if (state.playing) Icon(NitrateIcons.Pause, "Pause") else Icon(Icons.Default.PlayArrow, "Play")
            }
            IconButton(onClick = listening::next, enabled = state.index < state.queue.lastIndex || state.repeat != Repeat.OFF) { Icon(NitrateIcons.SkipNext, "Next song") }
        }
        val fraction = if (state.durationMs > 0) (position.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.1f))) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(accentColor()))
        }
    }
}

/** Now Playing: the cover large, the song, a seek bar, the controls, then the queue or the lyrics. */
@Composable
fun NowPlayingScreen(actions: AppActions, onBack: () -> Unit, player: Listening? = actions.container.listening) {
    val listening = player ?: return onBack()
    val state by listening.state.collectAsState()
    val song = state.current
    if (song == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onBack() }
        return
    }
    val position = rememberPosition(listening)
    var lyricsShown by remember { mutableStateOf(false) }
    val lyrics by produceState<dev.nitrate.core.music.Lyrics?>(null, song.id) { value = runCatching { actions.server.lyrics(song.id) }.getOrNull() }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val tint by rememberArtworkColor(actions.server.imageUrl(song, ImageKind.PRIMARY, 300))
    TintedBackdrop(tint) {
        LazyColumn(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.Default.Close, "Close") }
                    Text("Now playing", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (lyrics != null) TextButton(onClick = { lyricsShown = !lyricsShown }) { Text(if (lyricsShown) "Queue" else "Lyrics") }
                }
            }
            item {
                Box(
                    Modifier.padding(horizontal = 32.dp, vertical = 12.dp).widthIn(max = 420.dp).fillMaxWidth().aspectRatio(1f)
                        .clip(RoundedCornerShape(24.dp)).background(Color.White.copy(alpha = 0.06f)),
                ) {
                    actions.server.imageUrl(song, ImageKind.PRIMARY, 900)?.let { AsyncImage(it, song.album ?: song.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
            }
            item {
                Column(Modifier.padding(horizontal = ScreenPadding).widthIn(max = 520.dp).fillMaxWidth()) {
                    Text(song.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(song.artists.joinToString(", ").ifEmpty { null }, song.album).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    val duration = state.durationMs.coerceAtLeast(1)
                    Slider(
                        value = dragging ?: (position.toFloat() / duration).coerceIn(0f, 1f),
                        onValueChange = { dragging = it },
                        onValueChangeFinished = { dragging?.let { listening.seekTo((it * duration).toLong()) }; dragging = null },
                        modifier = Modifier.padding(top = 12.dp).labelled("Position"),
                    )
                    Row {
                        Text(formatClock(dragging?.let { (it * duration).toLong() } ?: position), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        Text(formatClock(state.durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { listening.setShuffle(!state.shuffle) }) {
                            Icon(NitrateIcons.Shuffle, if (state.shuffle) "Shuffle on" else "Shuffle off", tint = if (state.shuffle) accentColor() else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = listening::previous) { Icon(NitrateIcons.SkipPrevious, "Previous song", Modifier.size(32.dp)) }
                        Box(
                            Modifier.size(68.dp).clip(RoundedCornerShape(34.dp)).background(accentColor()).pressable(listening::toggle).labelled(if (state.playing) "Pause" else "Play"),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (state.playing) Icon(NitrateIcons.Pause, null, tint = Color.Black, modifier = Modifier.size(34.dp))
                            else Icon(Icons.Default.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(38.dp))
                        }
                        IconButton(onClick = listening::next) { Icon(NitrateIcons.SkipNext, "Next song", Modifier.size(32.dp)) }
                        val next = Repeat.entries[(state.repeat.ordinal + 1) % Repeat.entries.size]
                        IconButton(onClick = { listening.setRepeat(next) }) {
                            Icon(
                                if (state.repeat == Repeat.ONE) NitrateIcons.RepeatOne else NitrateIcons.Repeat, state.repeat.label,
                                tint = if (state.repeat != Repeat.OFF) accentColor() else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    state.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
            }
            val shownLyrics = lyrics
            if (lyricsShown && shownLyrics != null) {
                item { LyricsView(shownLyrics, position, onSeek = listening::seekTo, modifier = Modifier.fillMaxWidth().height(420.dp)) }
            } else {
                item { Column(Modifier.fillMaxWidth().padding(top = 16.dp)) { SectionHeader("Up next") } }
                itemsIndexed(state.queue, key = { i, s -> "$i-${s.id}" }) { i, s ->
                    if (i <= state.index) return@itemsIndexed
                    Row(
                        Modifier.fillMaxWidth().pressable({ listening.skipTo(i) }).padding(horizontal = ScreenPadding, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(s.artists.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { listening.remove(i) }) { Icon(Icons.Default.Close, "Remove ${s.name} from the queue") }
                    }
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}
