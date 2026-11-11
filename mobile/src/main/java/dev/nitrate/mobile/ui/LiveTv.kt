package dev.nitrate.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import dev.nitrate.android.viewmodel.LiveTvViewModel
import dev.nitrate.core.livetv.Guide
import dev.nitrate.core.livetv.LiveChannel
import dev.nitrate.core.livetv.TvProgram
import dev.nitrate.core.model.ImageKind
import java.text.DateFormat
import java.util.Date

private fun time(ms: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

/** Width of half an hour in the guide. */
private val SLOT_WIDTH = 120.dp

/** Live TV: what's on now per channel, a scrolling guide, and recordings. */
@Composable
fun LiveTvScreen(actions: AppActions) {
    val vm = viewModel(key = "live-tv") { LiveTvViewModel(actions.server) }
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var chosen by remember { mutableStateOf<TvProgram?>(null) }
    val channels = state.channels
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            listOf("On now", "Guide", "Recordings").forEachIndexed { i, t -> Tab(tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        state.message?.let {
            Text(it, color = accentColor(), modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp))
        }
        when {
            channels == null -> Loading()
            channels.isEmpty() -> ErrorMessage(state.error ?: "No channels. Live TV needs a tuner set up on the server.", vm::refresh)
            tab == 0 -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                items(channels, key = { it.id }) { ch ->
                    OnNowRow(actions, ch, state.guide[ch.id].orEmpty(), state.nowMs) { actions.play(ch.item, false) }
                }
            }
            tab == 1 -> GuideGrid(actions, channels, state.guide, state.windowStartMs, state.nowMs, onShift = vm::shift) { channel, program ->
                if (program.isAiring(state.nowMs)) actions.play(channel.item, false) else chosen = program
            }
            else -> if (state.recordings.isEmpty()) {
                ErrorMessage("No recordings yet. Pick something in the guide to record it.")
            } else LazyVerticalGrid(GridCells.Adaptive(160.dp), contentPadding = PaddingValues(ScreenPadding), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(state.recordings, key = { it.id }) { r ->
                    MediaCard(r, actions.server.cardImage(r, CardShape.WIDE), CardShape.WIDE, title = r.name, subtitle = r.episodeLabel, onClick = { actions.play(r, false) })
                }
            }
        }
    }
    chosen?.let { p ->
        AlertDialog(
            onDismissRequest = { chosen = null },
            title = { Text(p.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${time(p.startMs)} – ${time(p.endMs)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    p.episodeTitle?.let { Text(it, fontWeight = FontWeight.SemiBold) }
                    p.overview?.takeIf { LocalDisplayPrefs.current.overview }?.let { Text(it) }
                }
            },
            confirmButton = { TextButton(onClick = { vm.toggleRecording(p); chosen = null }) { Text(if (p.timerId != null) "Don't record" else "Record") } },
            dismissButton = { TextButton(onClick = { chosen = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun OnNowRow(actions: AppActions, channel: LiveChannel, programs: List<TvProgram>, nowMs: Long, onPlay: () -> Unit) {
    val now = channel.now ?: programs.firstOrNull { it.isAiring(nowMs) }
    val next = Guide.next(programs, now, nowMs)
    Row(Modifier.fillMaxWidth().pressable(onPlay).padding(horizontal = ScreenPadding, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.width(96.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.08f)), Alignment.Center) {
            Text(channel.number ?: "", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            actions.server.imageUrl(channel.item, ImageKind.PRIMARY, 120)?.let { AsyncImage(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(8.dp)) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(listOfNotNull(channel.number, channel.name).joinToString("  "), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(now?.title ?: "No listings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (now != null) LinearProgressIndicator(progress = { now.progress(nowMs) }, color = accentColor(), modifier = Modifier.fillMaxWidth().height(3.dp))
            next?.let { Text("Next ${time(it.startMs)}  ${it.title}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** Channels down the side, programmes across in time, scrolling sideways together. */
@Composable
private fun GuideGrid(
    actions: AppActions,
    channels: List<LiveChannel>,
    guide: Map<String, List<TvProgram>>,
    windowStartMs: Long,
    nowMs: Long,
    onShift: (Int) -> Unit,
    onProgram: (LiveChannel, TvProgram) -> Unit,
) {
    val windowMs = LiveTvViewModel.WINDOW_MS
    val slots = (windowMs / Guide.SLOT_MS).toInt()
    val totalWidth = SLOT_WIDTH * slots
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = ScreenPadding, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onShift(-slots / 2) }, enabled = windowStartMs > Guide.windowStart(nowMs)) { Text("‹ Earlier") }
            Text("${time(windowStartMs)} – ${time(windowStartMs + windowMs)}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { onShift(slots / 2) }) { Text("Later ›") }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row {
                    Spacer(Modifier.width(88.dp))
                    Row(Modifier.horizontalScroll(scroll)) {
                        repeat(slots) { i -> Text(time(windowStartMs + i * Guide.SLOT_MS), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(SLOT_WIDTH).padding(start = 4.dp)) }
                    }
                }
            }
            items(channels, key = { it.id }) { ch ->
                Row(Modifier.height(64.dp).padding(vertical = 2.dp)) {
                    Column(Modifier.width(88.dp).fillMaxSize().padding(start = 12.dp), verticalArrangement = Arrangement.Center) {
                        ch.number?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text(ch.name, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Box(Modifier.horizontalScroll(scroll).width(totalWidth).fillMaxSize()) {
                        Guide.cells(guide[ch.id].orEmpty(), windowStartMs, windowStartMs + windowMs).forEach { cell ->
                            val airing = cell.program.isAiring(nowMs)
                            Box(
                                Modifier.offset(x = totalWidth * cell.startFraction).width(totalWidth * cell.widthFraction).fillMaxSize().padding(2.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (airing) accentColor().copy(alpha = 0.28f) else Color.White.copy(alpha = 0.07f))
                                    .pressable({ onProgram(ch, cell.program) })
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            ) {
                                Column {
                                    Text((if (cell.program.timerId != null) "● " else "") + cell.program.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(time(cell.program.startMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
