package dev.nitrate.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.nitrate.android.viewmodel.ChannelsViewModel
import dev.nitrate.core.MediaServer
import dev.nitrate.core.channels.ChannelBlock
import dev.nitrate.core.channels.ChannelGuide
import dev.nitrate.core.channels.ChannelMix
import dev.nitrate.core.channels.ChannelProgram
import dev.nitrate.core.channels.ChannelSource
import dev.nitrate.core.channels.Channels
import dev.nitrate.core.channels.HouseChannel
import dev.nitrate.core.model.MediaItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun clock(ms: Long) = dev.nitrate.mobile.platform.formatTime(ms)

private val ALIGN_CHOICES = listOf(0 to "Back to back", 15 to "Quarter hours", 30 to "Half hours", 60 to "On the hour")

/**
 * The household's channels on the phone: each channel with what's on and what's next. Tune in
 * plays what's on from its beginning, then the channel in order; a later program can be watched
 * from there on. Admins can make and change channels.
 */
@Composable
fun ChannelsScreen(actions: AppActions) {
    val vm = viewModel(key = "house-channels") { ChannelsViewModel(actions.server) }
    val state by vm.state.collectAsStateWithLifecycle()
    val account by actions.container.account.collectAsStateWithLifecycle()
    val admin = account?.isAdmin == true
    val scope = rememberCoroutineScope()
    var chosen by remember { mutableStateOf<Pair<ChannelGuide, ChannelProgram>?>(null) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose {}
    }
    fun tuneIn(g: ChannelGuide) = scope.launch { vm.tune(g.channel)?.let(actions::playQueue) }
    val guides = state.guides
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Channels", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                Text(
                    "Tune in and what's on starts from its beginning, then the channel carries on in order. Nothing plays until you do.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (admin) OutlinedButton(onClick = { actions.nav.navigate(ChannelEditorRoute()) }) { Text("New channel") }
                state.message?.let { Text(it, color = accentColor()) }
            }
        }
        when {
            guides == null -> item { Text("Loading…", Modifier.padding(horizontal = ScreenPadding)) }
            guides.isEmpty() -> item {
                Text(
                    state.error ?: if (admin) "No channels yet. Make one from your shows and movies." else "No channels yet. Ask the server's admin to make one.",
                    Modifier.padding(horizontal = ScreenPadding), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> items(guides, key = { it.channel.id }) { g ->
                ChannelCard(g, state.nowMs, admin, onTune = { tuneIn(g) }, onEdit = { actions.nav.navigate(ChannelEditorRoute(g.channel.id)) }, onProgram = { chosen = g to it })
            }
        }
        item { Spacer(Modifier.padding(bottom = NavBarClearance)) }
    }
    chosen?.let { (g, p) ->
        AlertDialog(
            onDismissRequest = { chosen = null },
            title = { Text(p.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    p.subtitle?.let { Text(it) }
                    Text("${g.channel.name} · ${clock(p.startMs)} – ${clock(p.endMs)}" + (p.block?.let { " · $it" } ?: ""), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!p.available) Text("This one isn't available to you; tuning in skips it.", color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                if (p.available) TextButton(onClick = {
                    chosen = null
                    actions.playQueue(g.programs.filter { it.startMs >= p.startMs && it.available }.map { it.itemId })
                }) { Text("Watch from here now") }
            },
            dismissButton = { TextButton(onClick = { chosen = null; tuneIn(g) }) { Text("Tune in") } },
        )
    }
}

@Composable
private fun ChannelCard(g: ChannelGuide, nowMs: Long, admin: Boolean, onTune: () -> Unit, onEdit: () -> Unit, onProgram: (ChannelProgram) -> Unit) {
    val now = Channels.onNow(g.programs, nowMs)
    val later = g.programs.filter { it.startMs > (now?.startMs ?: nowMs) }.take(4)
    Card(
        Modifier.fillMaxWidth().padding(horizontal = ScreenPadding).clip(RoundedCornerShape(18.dp)).pressable(onTune),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${g.channel.number}", style = MaterialTheme.typography.labelLarge, color = accentColor(), modifier = Modifier.width(36.dp))
                Text(g.channel.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (admin) TextButton(onClick = onEdit) { Text("Edit") }
            }
            if (now == null) Text("Off air", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else {
                val on = now.isOn(nowMs)
                Text((if (on) "Now · " else "Next · ${clock(now.startMs)} · ") + now.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                now.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (on) LinearProgressIndicator(
                    progress = { ((nowMs - now.startMs).toFloat() / (now.endMs - now.startMs).coerceAtLeast(1)).coerceIn(0f, 1f) },
                    color = accentColor(), modifier = Modifier.fillMaxWidth(),
                )
                Text("Tap to tune in: starts from the beginning", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            later.forEach { p ->
                Row(Modifier.fillMaxWidth().clickable { onProgram(p) }.padding(vertical = 4.dp)) {
                    Text(clock(p.startMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(72.dp))
                    Text(p.title + (p.subtitle?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (p.available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Makes or changes a channel (admins). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChannelEditorScreen(actions: AppActions, channelId: String?) {
    val server = actions.server
    val scope = rememberCoroutineScope()
    var channel by remember { mutableStateOf<HouseChannel?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var editingBlock by remember { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val users by produceState(emptyList<dev.nitrate.core.admin.UserAccount>()) { value = runCatching { server.users() }.getOrDefault(emptyList()) }
    LaunchedEffect(channelId) {
        runCatching { server.houseChannels() }
            .onSuccess { all -> channel = all.firstOrNull { it.id == channelId } ?: HouseChannel(number = Channels.nextNumber(all)) }
            .onFailure { error = it.message ?: "Couldn't load the channels" }
    }
    fun save(startOver: Boolean) {
        val c = channel ?: return
        Channels.problem(c)?.let { error = it; return }
        busy = true
        error = null
        scope.launch {
            runCatching { server.saveChannel(c, startOver) }.onSuccess { actions.nav.popBackStack() }.onFailure { error = it.message ?: "Couldn't save" }
            busy = false
        }
    }
    val c = channel
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (channelId == null) "New channel" else "Edit channel", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (c == null) {
            if (error == null) Text("Loading…")
            return@Column
        }
        OutlinedTextField(c.name, { channel = c.copy(name = it) }, label = { Text("Channel name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { channel = c.copy(number = (c.number - 1).coerceAtLeast(1)) }) { Text("−") }
            Text("Channel ${c.number}", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { channel = c.copy(number = c.number + 1) }) { Text("+") }
        }
        SectionTitle("Lineup")
        Lineup(server, c.sources) { channel = c.copy(sources = it) }
        SectionTitle("Taking turns")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ChannelMix.entries.forEach { m -> FilterChip(c.mix == m, { channel = c.copy(mix = m) }, label = { Text(m.label) }) } }
        SectionTitle("Programs start")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ALIGN_CHOICES.forEach { (m, l) -> FilterChip(c.alignMinutes == m, { channel = c.copy(alignMinutes = m) }, label = { Text(l) }) } }
        SectionTitle("Time slots")
        Text("A slot plays its own lineup at set times, like Saturday-morning cartoons; the channel's lineup fills the rest.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        c.blocks.forEachIndexed { i, b ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(b.name, style = MaterialTheme.typography.bodyLarge)
                    Text("${Channels.describe(b)} · ${b.sources.size} in lineup", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { editingBlock = i }) { Text("Edit") }
                TextButton(onClick = { channel = c.copy(blocks = c.blocks.filterIndexed { j, _ -> j != i }) }) { Text("Remove") }
            }
        }
        OutlinedButton(onClick = {
            channel = c.copy(blocks = c.blocks + ChannelBlock("Time slot", listOf(6), 8 * 60, 10 * 60, emptyList()))
            editingBlock = c.blocks.size
        }) { Text("Add a time slot") }
        SectionTitle("Who can watch")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(c.everyone, { channel = c.copy(everyone = !c.everyone) }, label = { Text("Everyone") })
            if (!c.everyone) users.forEach { u ->
                val on = u.id in c.users
                FilterChip(on, { channel = c.copy(users = if (on) c.users - u.id else c.users + u.id) }, label = { Text(u.name) })
            }
        }
        Text(
            "Saving keeps what's on now and the place each show had reached; the rest of the guide is made again.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { save(false) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Saving…" else "Save") }
        if (channelId != null) {
            OutlinedButton(onClick = { save(true) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Save and start every show over") }
            TextButton(onClick = { confirmDelete = true }) { Text("Delete channel", color = MaterialTheme.colorScheme.error) }
        }
        Spacer(Modifier.padding(bottom = 24.dp))
    }
    val i = editingBlock
    if (c != null && i != null && i in c.blocks.indices) {
        BlockSheet(server, c.blocks[i], { b -> channel = c.copy(blocks = c.blocks.mapIndexed { j, x -> if (j == i) b else x }) }) { editingBlock = null }
    }
    if (confirmDelete && c != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${c.name}?") },
        text = { Text("Everyone loses the channel and its guide. Nothing in the library changes.") },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                scope.launch { runCatching { server.deleteChannel(c.id) }.onSuccess { actions.nav.popBackStack() }.onFailure { error = it.message } }
            }) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep it") } },
    )
}

@Composable
private fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockSheet(server: MediaServer, block: ChannelBlock, onChange: (ChannelBlock) -> Unit, onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Time slot") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(block.name, { onChange(block.copy(name = it)) }, label = { Text("Name in the guide") }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (0..6).forEach { d ->
                        val on = d in block.days
                        FilterChip(on, { onChange(block.copy(days = if (on) block.days - d else (block.days + d).sorted())) }, label = { Text(Channels.dayName(d)) })
                    }
                }
                TimeStepper("Starts", block.startMinute) { onChange(block.copy(startMinute = it)) }
                TimeStepper("Ends", block.endMinute) { onChange(block.copy(endMinute = it)) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { ChannelMix.entries.forEach { m -> FilterChip(block.mix == m, { onChange(block.copy(mix = m)) }, label = { Text(m.label) }) } }
                Lineup(server, block.sources) { onChange(block.copy(sources = it)) }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Done") } },
    )
}

@Composable
private fun TimeStepper(label: String, minute: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, modifier = Modifier.width(56.dp))
        OutlinedButton(onClick = { onChange((minute + 1410) % 1440) }) { Text("−30m") }
        Text(Channels.time(minute), style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(56.dp))
        OutlinedButton(onClick = { onChange((minute + 30) % 1440) }) { Text("+30m") }
    }
}

/** A lineup: entries with move, shuffle and remove; find things to add, or pick a library or playlist. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Lineup(server: MediaServer, sources: List<ChannelSource>, onChange: (List<ChannelSource>) -> Unit) {
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf(emptyList<MediaItem>()) }
    val more by produceState(emptyList<MediaItem>()) {
        value = runCatching { server.views() }.getOrDefault(emptyList()) + runCatching { server.playlists() }.getOrDefault(emptyList())
    }
    LaunchedEffect(query) {
        delay(400)
        found = runCatching { ChannelsViewModel.findSources(server, query) }.getOrDefault(emptyList()).take(12)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (sources.isEmpty()) Text("Nothing yet: find something below.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        sources.forEachIndexed { i, s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${i + 1}. ${s.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.kind.label + if (s.shuffle) " · shuffled" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { onChange(Channels.move(sources, i, -1)) }, enabled = i > 0) { Text("▲") }
                TextButton(onClick = { onChange(Channels.move(sources, i, 1)) }, enabled = i < sources.lastIndex) { Text("▼") }
                TextButton(onClick = { onChange(sources.mapIndexed { j, x -> if (j == i) x.copy(shuffle = !x.shuffle) else x }) }) { Text(if (s.shuffle) "In order" else "Shuffle") }
                TextButton(onClick = { onChange(sources.filterIndexed { j, _ -> j != i }) }) { Text("✕") }
            }
        }
        OutlinedTextField(query, { query = it }, label = { Text("Find a show, movie or collection") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (found.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            found.forEach { item -> FilterChip(sources.any { it.id == item.id }, { onChange(Channels.add(sources, item)) }, label = { Text(item.name + (item.productionYear?.let { " ($it)" } ?: "")) }) }
        }
        if (more.isNotEmpty()) {
            Text("Whole libraries and playlists", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                more.forEach { item -> FilterChip(sources.any { it.id == item.id }, { onChange(Channels.add(sources, item)) }, label = { Text(item.name) }) }
            }
        }
    }
}
