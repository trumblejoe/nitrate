package dev.nitrate.tv.ui.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.FilterChip
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.nitrate.android.AppContainer
import dev.nitrate.core.model.CollectionKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.playback.OverviewEntry
import dev.nitrate.core.playback.OverviewFilter
import dev.nitrate.core.playback.PlaybackOverview
import dev.nitrate.tv.ui.lists.TvDialog
import kotlinx.coroutines.launch

/**
 * Settings → Library playback overview: every film and episode the user can see and how each would
 * play on this TV (as it is, repackaged, sound or picture converted and why; the decoder; whether
 * surround goes out untouched), worked out here from each file's details. Anyone can open it; "Send
 * to server log" puts the whole list in the server's logs, so the admin can read exactly what this
 * TV sees.
 */
@Composable
fun PlaybackOverviewScreen(container: AppContainer, onOpen: (String) -> Unit, onTest: (List<String>) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val libraries by produceState(emptyList<MediaItem>()) {
        value = runCatching { container.server.views() }.getOrDefault(emptyList()).filter { it.collection == CollectionKind.MOVIES || it.collection == CollectionKind.TV_SHOWS }
    }
    var libraryId by rememberSaveable { mutableStateOf<String?>(null) }
    var overview by remember { mutableStateOf(container.lastOverview?.takeIf { it.first == libraryId }?.second) }
    val tests by container.testPlays.collectAsState()
    LaunchedEffect(Unit) { container.loadTestPlays() }
    // The titles of the test (or sample) that was started, shown together once back here.
    var testing by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var testStarted by rememberSaveable { mutableStateOf(0L) }
    var sample by remember { mutableStateOf<List<OverviewEntry>?>(null) }
    var showTrail by remember { mutableStateOf<dev.nitrate.core.playback.TestPlayResult?>(null) }
    fun startTests(ids: List<String>) {
        testing = ids
        testStarted = System.currentTimeMillis()
        onTest(ids)
    }
    var reading by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf(OverviewFilter.ALL) }
    var detail by remember { mutableStateOf<OverviewEntry?>(null) }
    var sent by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    fun load() {
        error = null
        sent = null
        reading = 0 to 0
        scope.launch {
            val name = libraries.firstOrNull { it.id == libraryId }?.name ?: "All libraries"
            runCatching { container.playbackOverview(libraryId, name) { done, total -> reading = done to total } }
                .onSuccess { overview = it; container.lastOverview = libraryId to it }
                .onFailure { error = it.message ?: "Couldn't read the library" }
            reading = null
        }
    }
    LaunchedEffect(libraryId) { if (overview == null || container.lastOverview?.first != libraryId) load() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 58.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Library playback overview", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "How every film and episode you can see would play on this TV, and why. \"Send to server log\" saves it for your server's admin.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(libraryId == null, { libraryId = null }, modifier = Modifier.focusRequester(first)) { Text("All libraries") } }
                    items(libraries, key = { it.id }) { l -> FilterChip(libraryId == l.id, { libraryId = l.id }) { Text(l.name) } }
                }
                reading?.let { (done, total) -> Text(if (total > 0) "Reading $done of $total…" else "Reading the library…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        overview?.takeIf { reading == null }?.let { o ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    // How this TV is set: what decides the answers below.
                    Text(o.settings.joinToString("  ·  ") { (k, v) -> "$k: $v" }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(OverviewFilter.entries.toList(), key = { it.name }) { f ->
                            FilterChip(filter == f, { filter = f }) { Text("${f.label} · ${o.count(f)}") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = {
                            sent = "Sending…"
                            scope.launch {
                                sent = container.sendPlaybackOverview(o).fold(
                                    { name -> "Saved on your server as $name (Jellyfin Dashboard → Logs)" },
                                    { e -> "Couldn't send it: ${e.message ?: e::class.simpleName}" },
                                )
                            }
                        }) { Text("Send to server log") }
                        OutlinedButton(onClick = ::load) { Text("Read again") }
                        OutlinedButton(onClick = { sample = dev.nitrate.core.playback.TestPlay.sample(o.entries) }) { Text("Test a sample") }
                        sent?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
            val shown = o.entries.filter(filter::matches)
            if (shown.isEmpty()) item { Text("Nothing here.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp)) }
            items(shown, key = { it.item.id }) { e ->
                ListItem(
                    selected = false,
                    onClick = { detail = e },
                    headlineContent = { Text(e.name) },
                    supportingContent = {
                        Column {
                            Text(e.prediction.summary, style = MaterialTheme.typography.bodySmall)
                            e.prediction.reasons.firstOrNull()?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                            tests[e.item.id]?.let { t -> Text("Tested: ${t.summary}", style = MaterialTheme.typography.bodySmall, color = if (t.ok) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color(0xFFFFB74D)) }
                        }
                    },
                )
            }
        }
    }
    detail?.let { e ->
        TvDialog(onDismiss = { detail = null }, width = 760) {
            Text(e.name, style = MaterialTheme.typography.headlineSmall)
            e.prediction.rows().forEach { (label, text) ->
                Row {
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(140.dp))
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                }
            }
            tests[e.item.id]?.let { t ->
                Text("Last test play: ${t.summary}", style = MaterialTheme.typography.bodyMedium, color = if (t.ok) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color(0xFFFFB74D), modifier = Modifier.padding(top = 6.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
                Button(onClick = { detail = null; startTests(listOf(e.item.id)) }) { Text("Test play (30 s)") }
                tests[e.item.id]?.let { t -> OutlinedButton(onClick = { detail = null; showTrail = t }) { Text("See what happened") } }
                OutlinedButton(onClick = { detail = null; onOpen(e.item.id) }) { Text("Open") }
                OutlinedButton(onClick = { detail = null }) { Text("Close") }
            }
        }
    }
    // A sample: one title for each kind of file, tested one after another.
    sample?.let { list ->
        TvDialog(onDismiss = { sample = null }, width = 820) {
            Text("Test a sample", style = MaterialTheme.typography.headlineSmall)
            Text(
                "${list.size} kinds of file in ${overview?.library ?: "the library"}. One title of each plays for 30 seconds, a few minutes in, " +
                    "through the normal path (nothing is marked watched). Back stops the one playing.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                Button(onClick = { sample = null; startTests(list.map { it.item.id }) }) { Text("Start (about ${(list.size * 45 + 59) / 60} min)") }
                OutlinedButton(onClick = { sample = null }) { Text("Cancel") }
            }
            list.forEach { e ->
                Column(Modifier.padding(vertical = 2.dp)) {
                    Text(e.source?.let(dev.nitrate.core.playback.TestPlay::describe) ?: e.name, style = MaterialTheme.typography.bodyMedium)
                    Text("${e.name} · ${e.prediction.delivery.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    // Back from a test: what each one did.
    val finished = testing.mapNotNull { id -> tests[id]?.takeIf { it.atMs >= testStarted } }
    if (testing.isNotEmpty() && finished.isNotEmpty() && showTrail == null) {
        if (finished.size == 1 && testing.size == 1) {
            LaunchedEffect(finished) { showTrail = finished.single(); testing = emptyList() }
        } else TvDialog(onDismiss = { testing = emptyList() }, width = 820) {
            Text("Test plays: ${finished.size} of ${testing.size}", style = MaterialTheme.typography.headlineSmall)
            val off = finished.count { !it.ok }
            Text(if (off == 0) "Everything played as predicted." else "$off didn't go as predicted.", color = if (off == 0) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color(0xFFFFB74D))
            finished.forEach { t ->
                ListItem(selected = false, onClick = { showTrail = t }, headlineContent = { Text(t.name) }, supportingContent = { Text(t.summary) })
            }
            OutlinedButton(onClick = { testing = emptyList() }) { Text("Close") }
        }
    }
    showTrail?.let { t ->
        TvDialog(onDismiss = { showTrail = null }, width = 1000) {
            Text("Test play: ${t.name}", style = MaterialTheme.typography.headlineSmall)
            Text(listOfNotNull(t.summary, "${t.playedMs / 1000} s played", "${t.droppedFrames} dropped frames").joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            t.trail?.let { trail -> ScrollingTrail(trail) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = { showTrail = null }) { Text("Close") }
                OutlinedButton(onClick = { val id = t.itemId; showTrail = null; startTests(listOf(id)) }) { Text("Test again") }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

/** A trail in a box that scrolls with ▲▼ while it has focus (text alone can't take focus on a TV). */
@Composable
private fun ScrollingTrail(trail: dev.nitrate.core.playback.PlaybackTrail) {
    val scroll = androidx.compose.foundation.rememberScrollState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier
            .heightIn(max = 420.dp)
            .border(if (focused) 2.dp else 0.dp, if (focused) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    // Down past the end goes on to the buttons.
                    Key.DirectionDown -> if (scroll.value < scroll.maxValue) { scope.launch { scroll.animateScrollBy(160f) }; true } else false
                    Key.DirectionUp -> if (scroll.value > 0) { scope.launch { scroll.animateScrollBy(-160f) }; true } else false
                    else -> false
                }
            }
            .focusable()
            .verticalScroll(scroll)
            .padding(10.dp),
    ) {
        Text(if (scroll.maxValue > 0) "▲▼ to scroll" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        dev.nitrate.tv.ui.player.TrailView(trail)
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
