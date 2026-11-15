package dev.nitrate.tv.ui.channels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.nitrate.android.viewmodel.ChannelsViewModel
import dev.nitrate.core.MediaServer
import dev.nitrate.core.channels.ChannelBlock
import dev.nitrate.core.channels.ChannelMix
import dev.nitrate.core.channels.ChannelSource
import dev.nitrate.core.channels.Channels
import dev.nitrate.core.channels.HouseChannel
import dev.nitrate.core.model.MediaItem
import dev.nitrate.tv.ui.components.TvTextField
import dev.nitrate.tv.ui.lists.TvDialog
import dev.nitrate.tv.ui.rows.ignoreHeldSelect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ALIGN_CHOICES = listOf(0 to "Back to back", 5 to ":05s", 15 to "Quarter hours", 30 to "Half hours", 60 to "On the hour")

/**
 * Makes or changes a channel (admins), D-pad first: name and number, its lineup (shows, movies,
 * collections, playlists, libraries; order, shuffle), how they take turns, where programs start,
 * time slots with their own lineups, and who can watch it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChannelEditorScreen(server: MediaServer, channelId: String?, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var channel by remember { mutableStateOf<HouseChannel?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var editingBlock by remember { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val users by produceState(emptyList<dev.nitrate.core.admin.UserAccount>()) { value = runCatching { server.users() }.getOrDefault(emptyList()) }
    LaunchedEffect(channelId) {
        runCatching { server.houseChannels() }
            .onSuccess { all -> channel = all.firstOrNull { it.id == channelId } ?: HouseChannel(name = "", number = Channels.nextNumber(all)) }
            .onFailure { error = it.message ?: "Couldn't load the channels" }
    }
    fun save(startOver: Boolean) {
        val c = channel ?: return
        Channels.problem(c)?.let { error = it; return }
        busy = true
        error = null
        scope.launch {
            runCatching { server.saveChannel(c, startOver) }.onSuccess { onDone() }.onFailure { error = it.message ?: "Couldn't save" }
            busy = false
        }
    }
    val c = channel
    LazyColumn(Modifier.fillMaxSize().ignoreHeldSelect(), contentPadding = PaddingValues(horizontal = 58.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(if (channelId == null) "New channel" else "Edit channel", style = MaterialTheme.typography.headlineMedium)
        }
        if (c == null) {
            item { Text(error ?: "Loading…") }
            return@LazyColumn
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { save(false) }, enabled = !busy, modifier = Modifier.focusRequester(first)) { Text(if (busy) "Saving…" else "Save") }
                if (channelId != null) OutlinedButton(onClick = { save(true) }, enabled = !busy) { Text("Save and start every show over") }
                OutlinedButton(onClick = onDone) { Text("Cancel") }
                if (channelId != null) OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete") }
            }
        }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        item {
            Text(
                "Saving keeps what's on now and the place each show had reached; the rest of the guide is made again.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                TvTextField(c.name, { channel = c.copy(name = it) }, "Channel name", modifier = Modifier.width(420.dp))
                OutlinedButton(onClick = { channel = c.copy(number = (c.number - 1).coerceAtLeast(1)) }) { Text("−") }
                Text("Channel ${c.number}", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { channel = c.copy(number = c.number + 1) }) { Text("+") }
            }
        }
        item { Text("Lineup", style = MaterialTheme.typography.titleLarge) }
        item { LineupEditor(server, c.sources) { channel = c.copy(sources = it) } }
        item {
            Text("Taking turns", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChannelMix.entries.forEach { m -> FilterChip(c.mix == m, { channel = c.copy(mix = m) }) { Text(m.label) } }
            }
        }
        item {
            Text("Programs start", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ALIGN_CHOICES.forEach { (m, label) -> FilterChip(c.alignMinutes == m, { channel = c.copy(alignMinutes = m) }) { Text(label) } }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Time slots", style = MaterialTheme.typography.titleLarge)
                Text("A slot plays its own lineup at set times, like Saturday-morning cartoons; the channel's lineup fills the rest.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                c.blocks.forEachIndexed { i, b ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${b.name} · ${Channels.describe(b)} · ${b.sources.size} in lineup", modifier = Modifier.width(620.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        OutlinedButton(onClick = { editingBlock = i }) { Text("Edit") }
                        OutlinedButton(onClick = { channel = c.copy(blocks = c.blocks.filterIndexed { j, _ -> j != i }) }) { Text("Remove") }
                    }
                }
                OutlinedButton(onClick = {
                    channel = c.copy(blocks = c.blocks + ChannelBlock("Time slot", listOf(6), 8 * 60, 10 * 60, emptyList()))
                    editingBlock = c.blocks.size
                }) { Text("Add a time slot") }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Who can watch", style = MaterialTheme.typography.titleLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(c.everyone, { channel = c.copy(everyone = !c.everyone) }) { Text("Everyone") }
                    if (!c.everyone) users.forEach { u ->
                        val on = u.id in c.users
                        FilterChip(on, { channel = c.copy(users = if (on) c.users - u.id else c.users + u.id) }) { Text(u.name) }
                    }
                }
                if (!c.everyone) Text("Admins can always see every channel.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    LaunchedEffect(c != null) { delay(80); runCatching { first.requestFocus() } }

    val blockIndex = editingBlock
    if (c != null && blockIndex != null && blockIndex in c.blocks.indices) {
        BlockDialog(server, c.blocks[blockIndex], onChange = { b -> channel = c.copy(blocks = c.blocks.mapIndexed { j, x -> if (j == blockIndex) b else x }) }, onDone = { editingBlock = null })
    }
    if (confirmDelete && c != null) {
        val focus = remember { FocusRequester() }
        TvDialog({ confirmDelete = false }) {
            Text("Delete ${c.name}?", style = MaterialTheme.typography.headlineSmall)
            Text("Everyone loses the channel and its guide. Nothing in the library changes.")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { confirmDelete = false }, modifier = Modifier.focusRequester(focus)) { Text("Keep it") }
                Button(onClick = {
                    confirmDelete = false
                    scope.launch { runCatching { server.deleteChannel(c.id) }.onSuccess { onDone() }.onFailure { error = it.message } }
                }) { Text("Delete") }
            }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        }
    }
}

/** A time slot: its name, days, hours, lineup and turns. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockDialog(server: MediaServer, block: ChannelBlock, onChange: (ChannelBlock) -> Unit, onDone: () -> Unit) {
    val focus = remember { FocusRequester() }
    TvDialog(onDone, width = 1000) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Time slot", style = MaterialTheme.typography.headlineSmall)
                    Button(onClick = onDone, modifier = Modifier.focusRequester(focus)) { Text("Done") }
                }
            }
            item { TvTextField(block.name, { onChange(block.copy(name = it)) }, "Name in the guide") }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (0..6).forEach { d ->
                        val on = d in block.days
                        FilterChip(on, { onChange(block.copy(days = if (on) block.days - d else (block.days + d).sorted())) }) { Text(Channels.dayName(d)) }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Starts", modifier = Modifier.width(80.dp))
                    OutlinedButton(onClick = { onChange(block.copy(startMinute = (block.startMinute + 1410) % 1440)) }) { Text("−30m") }
                    Text(Channels.time(block.startMinute), style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(80.dp))
                    OutlinedButton(onClick = { onChange(block.copy(startMinute = (block.startMinute + 30) % 1440)) }) { Text("+30m") }
                    Text("Ends", modifier = Modifier.padding(start = 24.dp).width(60.dp))
                    OutlinedButton(onClick = { onChange(block.copy(endMinute = (block.endMinute + 1410) % 1440)) }) { Text("−30m") }
                    Text(Channels.time(block.endMinute), style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(80.dp))
                    OutlinedButton(onClick = { onChange(block.copy(endMinute = (block.endMinute + 30) % 1440)) }) { Text("+30m") }
                }
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChannelMix.entries.forEach { m -> FilterChip(block.mix == m, { onChange(block.copy(mix = m)) }) { Text(m.label) } }
                }
            }
            item { LineupEditor(server, block.sources) { onChange(block.copy(sources = it)) } }
        }
    }
    LaunchedEffect(Unit) { delay(50); runCatching { focus.requestFocus() } }
}

/** A lineup: each entry with move, shuffle and remove; then find shows, movies and collections, or pick a library or playlist. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LineupEditor(server: MediaServer, sources: List<ChannelSource>, onChange: (List<ChannelSource>) -> Unit) {
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf(emptyList<MediaItem>()) }
    val more by produceState(emptyList<MediaItem>()) {
        value = runCatching { server.views() }.getOrDefault(emptyList()) + runCatching { server.playlists() }.getOrDefault(emptyList())
    }
    LaunchedEffect(query) {
        delay(400)
        found = runCatching { ChannelsViewModel.findSources(server, query) }.getOrDefault(emptyList()).take(12)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (sources.isEmpty()) Text("Nothing yet: find something below.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        sources.forEachIndexed { i, s ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}. ${s.name}", modifier = Modifier.width(340.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(s.kind.label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(100.dp))
                OutlinedButton(onClick = { onChange(Channels.move(sources, i, -1)) }, enabled = i > 0) { Text("▲") }
                OutlinedButton(onClick = { onChange(Channels.move(sources, i, 1)) }, enabled = i < sources.lastIndex) { Text("▼") }
                FilterChip(s.shuffle, { onChange(sources.mapIndexed { j, x -> if (j == i) x.copy(shuffle = !x.shuffle) else x }) }) { Text("Shuffle") }
                OutlinedButton(onClick = { onChange(sources.filterIndexed { j, _ -> j != i }) }) { Text("Remove") }
            }
        }
        TvTextField(query, { query = it }, "Find a show, movie or collection to add", modifier = Modifier.width(620.dp))
        if (found.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            found.forEach { item -> FilterChip(sources.any { it.id == item.id }, { onChange(Channels.add(sources, item)) }) { Text("+ ${item.name}" + (item.productionYear?.let { " ($it)" } ?: "")) } }
        }
        if (more.isNotEmpty()) {
            Text("Whole libraries and playlists", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                more.forEach { item -> FilterChip(sources.any { it.id == item.id }, { onChange(Channels.add(sources, item)) }) { Text("+ ${item.name}") } }
            }
        }
    }
}
