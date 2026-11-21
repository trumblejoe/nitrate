package dev.nitrate.tv.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.ImageKind
import dev.nitrate.core.model.formatClock
import dev.nitrate.core.music.Listening
import dev.nitrate.core.music.Repeat
import kotlinx.coroutines.delay

/**
 * The listening player on TV: the cover large beside the song, how far through, the controls
 * (Shuffle, Previous, Play/Pause, Next, Repeat; ◀◀/▶▶ and the remote's media keys too), then what's
 * up next or the lyrics. Back leaves the music playing; the menu's "Now playing" comes back here.
 */
@Composable
fun NowPlayingScreen(listening: Listening, server: MediaServer, onBack: () -> Unit) {
    val state by listening.state.collectAsState()
    val song = state.current
    if (song == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val position by produceState(listening.positionMs(), listening) { while (true) { value = listening.positionMs(); delay(500) } }
    val lyrics by produceState<dev.nitrate.core.music.Lyrics?>(null, song.id) { value = runCatching { server.lyrics(song.id) }.getOrNull() }
    var lyricsShown by remember { mutableStateOf(false) }
    val play = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { play.requestFocus() } }

    Box(
        Modifier.fillMaxSize().background(Color(0xFF0E0F13))
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { listening.toggle(); true }
                    Key.MediaNext, Key.MediaFastForward -> { listening.next(); true }
                    Key.MediaPrevious, Key.MediaRewind -> { listening.previous(); true }
                    else -> false
                }
            },
    ) {
        // The cover, blurred large behind everything.
        server.imageUrl(song, ImageKind.PRIMARY, 600)?.let {
            AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().androidBlur())
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xE6000000), Color(0xB3000000)))))
        Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 40.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(Modifier.width(420.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(168.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.06f))) {
                        server.imageUrl(song, ImageKind.PRIMARY, 500)?.let { AsyncImage(it, song.album ?: song.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    }
                    Column(Modifier.padding(start = 20.dp)) {
                        Text("Now playing", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(song.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(song.artists.joinToString(", ").ifEmpty { null }, song.album).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                val fraction = if (state.durationMs > 0) (position.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.15f))) {
                    Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text(formatClock(position), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text(formatClock(state.durationMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // The controls step left and right among themselves only, never off into the list.
                val controls = remember { List(5) { FocusRequester() } }
                fun Modifier.inRow(i: Int) = this.focusRequester(controls[i]).focusProperties {
                    left = if (i > 0) controls[i - 1] else FocusRequester.Cancel
                    right = if (i < controls.lastIndex) controls[i + 1] else FocusRequester.Default
                }
                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { listening.setShuffle(!state.shuffle) }, modifier = Modifier.inRow(0)) { Text(if (state.shuffle) "Shuffle on" else "Shuffle") }
                    OutlinedButton(onClick = listening::previous, modifier = Modifier.inRow(1).semantics { contentDescription = "Previous song" }) { Text("⏮") }
                    Button(onClick = listening::toggle, modifier = Modifier.inRow(2).focusRequester(play)) { Text(if (state.playing) "Pause" else "Play") }
                    OutlinedButton(onClick = listening::next, modifier = Modifier.inRow(3).semantics { contentDescription = "Next song" }) { Text("⏭") }
                    val next = Repeat.entries[(state.repeat.ordinal + 1) % Repeat.entries.size]
                    OutlinedButton(onClick = { listening.setRepeat(next) }, modifier = Modifier.inRow(4)) { Text(state.repeat.label) }
                }
                if (lyrics != null) OutlinedButton(onClick = { lyricsShown = !lyricsShown }, modifier = Modifier.padding(top = 10.dp)) {
                    Text(if (lyricsShown) "Show what's next" else "Lyrics")
                }
                state.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            }
            val shown = lyrics
            if (lyricsShown && shown != null) {
                dev.nitrate.tv.ui.player.LyricsView(shown, position, Modifier.weight(1f).fillMaxHeight())
            } else {
                Column(Modifier.weight(1f)) {
                    Text("Up next", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(state.queue, key = { i, s -> "$i-${s.id}" }) { i, s ->
                            if (i <= state.index) return@itemsIndexed
                            ListItem(
                                selected = false, onClick = { listening.skipTo(i) },
                                headlineContent = { Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(s.artists.joinToString(", "), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                trailingContent = { s.runtimeMs?.let { Text(formatClock(it)) } },
                            )
                        }
                    }
                    if (state.index >= state.queue.lastIndex) Text("Nothing after this song.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** A heavy blur where the platform has one (Android 12+); a plain dim picture before that. */
private fun Modifier.androidBlur(): Modifier = if (android.os.Build.VERSION.SDK_INT >= 31) this.blur(60.dp) else this
