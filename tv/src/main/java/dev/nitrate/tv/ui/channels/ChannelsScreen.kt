package dev.nitrate.tv.ui.channels

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import dev.nitrate.android.viewmodel.ChannelsViewModel
import dev.nitrate.core.MediaServer
import dev.nitrate.core.channels.ChannelGuide
import dev.nitrate.core.channels.ChannelProgram
import dev.nitrate.core.channels.Channels
import dev.nitrate.core.livetv.Guide
import dev.nitrate.tv.ui.lists.TvDialog
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private val PagePadding = 58.dp
private val ChannelColumn = 190.dp
private fun time(ms: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

/**
 * The household's channels on TV: a guide of the next three hours. Selecting a channel (or
 * what's on) tunes in: what's on plays from its beginning, then the rest of the lineup in order.
 * A later program can be watched from there on. Admins hold OK on a channel to edit it.
 */
@Composable
fun ChannelsScreen(
    server: MediaServer,
    admin: Boolean,
    onPlayQueue: (List<String>) -> Unit,
    onEdit: (channelId: String?) -> Unit,
) {
    val vm = viewModel(key = "house-channels") { ChannelsViewModel(server) }
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var chosen by remember { mutableStateOf<Pair<ChannelGuide, ChannelProgram>?>(null) }
    val first = remember { FocusRequester() }
    val guides = state.guides
    val windowMs = ChannelsViewModel.WINDOW_MS
    val slots = (windowMs / Guide.SLOT_MS).toInt()
    // Back from the editor (or the player): the guide may have changed.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose {}
    }
    fun tuneIn(g: ChannelGuide) = scope.launch { vm.tune(g.channel)?.let(onPlayQueue) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 32.dp, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Column(Modifier.padding(horizontal = PagePadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Channels", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Tune in and what's on starts from its beginning, then the channel carries on in order. Nothing plays until you do." +
                        if (admin) " Hold OK on a channel to change it." else "",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Row(Modifier.padding(horizontal = PagePadding, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { vm.shift(-slots / 2) }, enabled = state.windowStartMs > Guide.windowStart(state.nowMs)) { Text("‹ Earlier") }
                OutlinedButton(onClick = { vm.shift(slots / 2) }) { Text("Later ›") }
                if (admin) OutlinedButton(onClick = { onEdit(null) }) { Text("New channel") }
                Text("${time(state.windowStartMs)} – ${time(state.windowStartMs + windowMs)}", style = MaterialTheme.typography.titleMedium)
                state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
        }
        when {
            guides == null -> item { Text("Loading…", Modifier.padding(horizontal = PagePadding)) }
            guides.isEmpty() -> item {
                Text(
                    state.error ?: if (admin) "No channels yet. Make one from your shows and movies with New channel." else "No channels yet. Ask the server's admin to make one.",
                    Modifier.padding(horizontal = PagePadding),
                )
            }
            else -> {
                item {
                    BoxWithConstraints(Modifier.fillMaxWidth().padding(start = PagePadding + ChannelColumn, end = PagePadding)) {
                        val slotWidth = maxWidth / slots
                        Row { repeat(slots) { i -> Text(time(state.windowStartMs + i * Guide.SLOT_MS), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(slotWidth)) } }
                    }
                }
                items(guides, key = { it.channel.id }) { g ->
                    ChannelRow(
                        guide = g,
                        fromMs = state.windowStartMs,
                        toMs = state.windowStartMs + windowMs,
                        nowMs = state.nowMs,
                        focus = if (g == guides.first()) first else null,
                        onChannel = { tuneIn(g) },
                        onEdit = if (admin) ({ onEdit(g.channel.id) }) else null,
                        onProgram = { p -> if (p.isOn(state.nowMs)) tuneIn(g) else chosen = g to p },
                    )
                }
            }
        }
    }
    LaunchedEffect(guides?.isNotEmpty()) { if (guides?.isNotEmpty() == true) runCatching { first.requestFocus() } }

    chosen?.let { (g, p) ->
        val focus = remember { FocusRequester() }
        val now = Channels.onNow(g.programs, state.nowMs)
        TvDialog({ chosen = null }, width = 680) {
            Text(p.title, style = MaterialTheme.typography.headlineSmall)
            p.subtitle?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            Text("${g.channel.name} · ${time(p.startMs)} – ${time(p.endMs)}" + (p.block?.let { " · $it" } ?: ""), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!p.available) Text("This one isn't available to you; tuning in skips it.", color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (p.available) Button(onClick = {
                    chosen = null
                    // From this program on, in the channel's order.
                    onPlayQueue(g.programs.filter { it.startMs >= p.startMs && it.available }.map { it.itemId })
                }, modifier = Modifier.focusRequester(focus)) { Text("Watch from here now") }
                OutlinedButton(onClick = { chosen = null; tuneIn(g) }, modifier = if (p.available) Modifier else Modifier.focusRequester(focus)) {
                    Text(now?.let { "Tune in (${it.title})" } ?: "Tune in")
                }
                OutlinedButton(onClick = { chosen = null }) { Text("Close") }
            }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        }
    }
}

@Composable
private fun ChannelRow(
    guide: ChannelGuide,
    fromMs: Long,
    toMs: Long,
    nowMs: Long,
    focus: FocusRequester?,
    onChannel: () -> Unit,
    onEdit: (() -> Unit)?,
    onProgram: (ChannelProgram) -> Unit,
) {
    val programs = remember(guide) { Channels.asTvPrograms(guide) }
    Row(Modifier.padding(horizontal = PagePadding).height(76.dp)) {
        Surface(
            onClick = onChannel,
            onLongClick = onEdit,
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            modifier = Modifier.width(ChannelColumn - 8.dp).fillMaxHeight().then(if (focus != null) Modifier.focusRequester(focus) else Modifier),
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.Center) {
                Text(guide.channel.number.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(guide.channel.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        BoxWithConstraints(Modifier.padding(start = 8.dp).fillMaxSize()) {
            val total = maxWidth
            val cells = Guide.cells(programs, fromMs, toMs)
            if (cells.isEmpty()) Text("Off air", Modifier.align(Alignment.CenterStart), color = MaterialTheme.colorScheme.onSurfaceVariant)
            cells.forEach { cell ->
                val program = guide.programs.first { it.startMs == cell.program.startMs }
                val on = program.isOn(nowMs)
                Surface(
                    onClick = { onProgram(program) },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    modifier = Modifier.offset(x = total * cell.startFraction).width(total * cell.widthFraction).fillMaxHeight().padding(end = 4.dp),
                ) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.Center) {
                        Text(program.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (program.available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(program.subtitle ?: "${time(program.startMs)} – ${time(program.endMs)}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
