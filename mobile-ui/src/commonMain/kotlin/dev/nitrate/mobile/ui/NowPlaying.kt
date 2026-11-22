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
import kotlinx.coroutines.launch
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
fun NowPlayingScreen(
    actions: AppActions, onBack: () -> Unit, player: Listening? = actions.container.listening,
    bookmarkStore: dev.nitrate.core.music.BookmarkStore = actions.container.bookmarks,
) {
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
    val book = dev.nitrate.core.music.Audiobooks.isBook(song)
    // A book's bookmarks (synced, the same on every device), and the one whose note is being written.
    val marks by remember(song.id) { bookmarkStore.bookmarks(song.id) }.collectAsState(emptyList())
    var noting by remember { mutableStateOf<dev.nitrate.core.music.Bookmark?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun saveMarks(list: List<dev.nitrate.core.music.Bookmark>) { scope.launch { bookmarkStore.save(song.id, list) } }
    noting?.let { mark ->
        var note by remember(mark.id) { mutableStateOf(mark.note) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { noting = null },
            title = { Text("Bookmark at ${dev.nitrate.core.music.Bookmarks.place(mark, song.chapters)}") },
            text = { androidx.compose.material3.OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { saveMarks(dev.nitrate.core.music.Bookmarks.edit(marks, mark.id, note)); noting = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { noting = null }) { Text("Skip") } },
        )
    }
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
                        if (!book) IconButton(onClick = { listening.setShuffle(!state.shuffle) }) {
                            Icon(NitrateIcons.Shuffle, if (state.shuffle) "Shuffle on" else "Shuffle off", tint = if (state.shuffle) accentColor() else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (book) TextButton(onClick = { listening.skipBy(-dev.nitrate.core.music.Audiobooks.BACK_MS) }) { Text("−15 s") }
                        IconButton(onClick = listening::previous) { Icon(NitrateIcons.SkipPrevious, if (book) "Previous chapter" else "Previous song", Modifier.size(32.dp)) }
                        Box(
                            Modifier.size(68.dp).clip(RoundedCornerShape(34.dp)).background(accentColor()).pressable(listening::toggle).labelled(if (state.playing) "Pause" else "Play"),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (state.playing) Icon(NitrateIcons.Pause, null, tint = Color.Black, modifier = Modifier.size(34.dp))
                            else Icon(Icons.Default.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(38.dp))
                        }
                        IconButton(onClick = listening::next) { Icon(NitrateIcons.SkipNext, if (book) "Next chapter" else "Next song", Modifier.size(32.dp)) }
                        if (book) TextButton(onClick = { listening.skipBy(dev.nitrate.core.music.Audiobooks.FORWARD_MS) }) { Text("+30 s") }
                        val next = Repeat.entries[(state.repeat.ordinal + 1) % Repeat.entries.size]
                        if (!book) IconButton(onClick = { listening.setRepeat(next) }) {
                            Icon(
                                if (state.repeat == Repeat.ONE) NitrateIcons.RepeatOne else NitrateIcons.Repeat, state.repeat.label,
                                tint = if (state.repeat != Repeat.OFF) accentColor() else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    state.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    if (book) BookChoices(listening, state, position)
                    if (book) PillButton("Bookmark", null, primary = false, onClick = {
                        val mark = dev.nitrate.core.music.Bookmark(kotlin.random.Random.nextLong().toULong().toString(36), position, createdMs = dev.nitrate.android.nowMs())
                        // One tap marks the place; a note can be added from the list.
                        saveMarks(dev.nitrate.core.music.Bookmarks.add(marks, mark))
                    }, modifier = Modifier.padding(top = 8.dp))
                }
            }
            if (book) {
                // Bookmarks: a tap goes there; the note can be changed, or the bookmark removed.
                if (marks.isNotEmpty()) {
                    item { Column(Modifier.fillMaxWidth().padding(top = 16.dp)) { SectionHeader("Bookmarks") } }
                    items(marks.size, key = { "bm-" + marks[it].id }) { i ->
                        val m = marks[i]
                        Row(
                            Modifier.fillMaxWidth().pressable({ listening.seekTo(m.positionMs) }).padding(start = ScreenPadding, end = 8.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(m.note.ifBlank { "Bookmark" }, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(dev.nitrate.core.music.Bookmarks.place(m, song.chapters), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { noting = m }) { Text("Note") }
                            IconButton(onClick = { saveMarks(dev.nitrate.core.music.Bookmarks.remove(marks, m.id)) }) { Icon(Icons.Default.Close, "Remove bookmark ${m.note.ifBlank { formatClock(m.positionMs) }}") }
                        }
                    }
                }
                // A book's chapters in place of what's up next: the one playing marked, a tap goes there.
                val chapters = song.chapters
                if (chapters.isNotEmpty()) {
                    item { Column(Modifier.fillMaxWidth().padding(top = 16.dp)) { SectionHeader("Chapters") } }
                    val at = dev.nitrate.core.music.Audiobooks.chapterAt(chapters, position)
                    itemsIndexed(chapters, key = { i, c -> "ch$i-${c.startMs}" }) { i, c ->
                        Row(
                            Modifier.fillMaxWidth().pressable({ listening.seekTo(c.startMs) }).padding(horizontal = ScreenPadding, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(c.name.ifBlank { "Chapter ${i + 1}" }, style = MaterialTheme.typography.bodyMedium, fontWeight = if (i == at) FontWeight.Bold else FontWeight.Normal,
                                color = if (i == at) accentColor() else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(formatClock(c.startMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item { Spacer(Modifier.height(32.dp)) }
                return@LazyColumn
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


/**
 * Downloads an album's or playlist's songs as they are (no conversion: music is small), for the
 * listening player to play from the device. Says how many are down; tapping again when all are
 * removes them.
 */
@Composable
fun MusicDownloadButton(actions: AppActions, songs: List<dev.nitrate.core.model.MediaItem>, what: String) {
    val records by actions.container.downloads.downloads.collectAsState(emptyList())
    val ids = remember(songs) { songs.map { it.id }.toSet() }
    val mine = records.filter { it.itemId in ids }
    val done = mine.count { it.state == dev.nitrate.core.downloads.DownloadState.COMPLETED }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val label = when {
        done == songs.size -> "Downloaded"
        mine.isNotEmpty() -> "Downloading $done/${songs.size}"
        else -> "Download"
    }
    var confirming by remember { mutableStateOf(false) }
    PillButton(label, NitrateIcons.Download, primary = false, onClick = {
        if (done == songs.size) confirming = true
        else scope.launch { actions.container.downloads.enqueue(songs, dev.nitrate.core.downloads.DownloadQuality.ORIGINAL, hevc = false) }
    }, modifier = Modifier.labelled(if (done == songs.size) "Remove this $what's downloads" else "Download this $what"))
    if (confirming) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text("Remove the downloaded songs?") },
        text = { Text("The ${songs.size} songs of this $what come off this device. They stay on your server.") },
        confirmButton = { TextButton(onClick = { confirming = false; scope.launch { songs.forEach { actions.container.downloads.remove(it.id) } } }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("Keep") } },
    )
}


/** A book's speed and sleep timer, and how long is left at that speed. */
@Composable
private fun BookChoices(listening: Listening, state: dev.nitrate.core.music.ListenState, position: Long) {
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(dev.nitrate.core.music.Audiobooks.timeLeft(position, state.durationMs, state.speed), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Speed", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
            dev.nitrate.core.music.Audiobooks.SPEEDS.forEach { s ->
                androidx.compose.material3.FilterChip(state.speed == s, { listening.setSpeed(s) }, label = { Text(dev.nitrate.core.music.Audiobooks.speedLabel(s)) })
            }
        }
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Sleep", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
            androidx.compose.material3.FilterChip(state.sleep == null, { listening.setSleep(null) }, label = { Text("Off") })
            dev.nitrate.core.music.Sleep.CHOICES.forEach { s ->
                androidx.compose.material3.FilterChip(state.sleep == s, { listening.setSleep(s) }, label = { Text(s.label) })
            }
        }
    }
}
