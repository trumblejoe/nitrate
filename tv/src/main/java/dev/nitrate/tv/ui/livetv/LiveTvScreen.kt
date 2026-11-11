package dev.nitrate.tv.ui.livetv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.nitrate.android.viewmodel.LiveTvViewModel
import dev.nitrate.core.MediaServer
import dev.nitrate.core.livetv.Guide
import dev.nitrate.core.livetv.LiveChannel
import dev.nitrate.core.livetv.TvProgram
import dev.nitrate.core.model.ImageKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.tv.ui.components.LocalDisplayPrefs
import dev.nitrate.tv.ui.components.MediaCard
import dev.nitrate.tv.ui.lists.TvDialog
import java.text.DateFormat
import java.util.Date

private val PagePadding = 58.dp
private val ChannelColumn = 150.dp
private fun time(ms: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

/**
 * Live TV on TV: a guide of the next three hours (Earlier/Later move it), select what's on to
 * watch the channel or a later programme to record it, and recordings below.
 */
@Composable
fun LiveTvScreen(repository: MediaServer, header: @Composable () -> Unit, onPlay: (MediaItem) -> Unit) {
    val vm = viewModel(key = "live-tv") { LiveTvViewModel(repository) }
    val state by vm.state.collectAsStateWithLifecycle()
    var chosen by remember { mutableStateOf<TvProgram?>(null) }
    val first = remember { FocusRequester() }
    val channels = state.channels
    val windowMs = LiveTvViewModel.WINDOW_MS
    val slots = (windowMs / Guide.SLOT_MS).toInt()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { header() }
        item {
            Row(Modifier.padding(horizontal = PagePadding, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { vm.shift(-slots / 2) }, enabled = state.windowStartMs > Guide.windowStart(state.nowMs)) { Text("‹ Earlier") }
                OutlinedButton(onClick = { vm.shift(slots / 2) }) { Text("Later ›") }
                Text("${time(state.windowStartMs)} – ${time(state.windowStartMs + windowMs)}", style = MaterialTheme.typography.titleMedium)
                state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
        }
        when {
            channels == null -> item { Text("Loading…", Modifier.padding(horizontal = PagePadding)) }
            channels.isEmpty() -> item { Text(state.error ?: "No channels. Live TV needs a tuner set up on the server.", Modifier.padding(horizontal = PagePadding)) }
            else -> {
                item {
                    // Time ruler.
                    BoxWithConstraints(Modifier.fillMaxWidth().padding(start = PagePadding + ChannelColumn, end = PagePadding)) {
                        val slotWidth = maxWidth / slots
                        Row { repeat(slots) { i -> Text(time(state.windowStartMs + i * Guide.SLOT_MS), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(slotWidth)) } }
                    }
                }
                items(channels, key = { it.id }) { ch ->
                    GuideRow(
                        channel = ch,
                        programs = state.guide[ch.id].orEmpty(),
                        fromMs = state.windowStartMs,
                        toMs = state.windowStartMs + windowMs,
                        nowMs = state.nowMs,
                        focus = if (ch == channels.first()) first else null,
                        onProgram = { p -> if (p.isAiring(state.nowMs)) onPlay(ch.item) else chosen = p },
                        onChannel = { onPlay(ch.item) },
                    )
                }
            }
        }
        if (state.recordings.isNotEmpty()) item {
            Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Recordings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = PagePadding))
                LazyRow(contentPadding = PaddingValues(horizontal = PagePadding), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    items(state.recordings, key = { it.id }) { r ->
                        MediaCard(item = r, imageUrl = repository.imageUrl(r, ImageKind.PRIMARY, 240) ?: repository.backdropUrl(r), width = 260.dp, aspectRatio = 16f / 9f, title = r.name, subtitle = r.episodeLabel, onClick = { onPlay(r) })
                    }
                }
            }
        }
    }
    LaunchedEffect(channels?.isNotEmpty()) { if (channels?.isNotEmpty() == true) runCatching { first.requestFocus() } }

    chosen?.let { p ->
        val focus = remember { FocusRequester() }
        TvDialog({ chosen = null }, width = 640) {
            Text(p.title, style = MaterialTheme.typography.headlineSmall)
            Text("${time(p.startMs)} – ${time(p.endMs)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            p.episodeTitle?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            p.overview?.takeIf { LocalDisplayPrefs.current.overview }?.let { Text(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { vm.toggleRecording(p); chosen = null }, modifier = Modifier.focusRequester(focus)) { Text(if (p.timerId != null) "Don't record" else "Record") }
                OutlinedButton(onClick = { chosen = null }) { Text("Close") }
            }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        }
    }
}

@Composable
private fun GuideRow(
    channel: LiveChannel,
    programs: List<TvProgram>,
    fromMs: Long,
    toMs: Long,
    nowMs: Long,
    focus: FocusRequester?,
    onProgram: (TvProgram) -> Unit,
    onChannel: () -> Unit,
) {
    Row(Modifier.padding(horizontal = PagePadding).height(72.dp)) {
        Surface(
            onClick = onChannel,
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            modifier = Modifier.width(ChannelColumn - 8.dp).fillMaxHeight(),
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.Center) {
                channel.number?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(channel.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        BoxWithConstraints(Modifier.padding(start = 8.dp).fillMaxSize()) {
            val total = maxWidth
            val cells = Guide.cells(programs, fromMs, toMs)
            if (cells.isEmpty()) Text("No listings", Modifier.align(Alignment.CenterStart), color = MaterialTheme.colorScheme.onSurfaceVariant)
            cells.forEachIndexed { i, cell ->
                val airing = cell.program.isAiring(nowMs)
                Surface(
                    onClick = { onProgram(cell.program) },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (airing) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    modifier = Modifier.offset(x = total * cell.startFraction).width(total * cell.widthFraction).fillMaxHeight().padding(end = 4.dp)
                        .then(if (i == 0 && focus != null) Modifier.focusRequester(focus) else Modifier),
                ) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.Center) {
                        Text((if (cell.program.timerId != null) "● " else "") + cell.program.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${time(cell.program.startMs)} – ${time(cell.program.endMs)}", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
        }
    }
}
