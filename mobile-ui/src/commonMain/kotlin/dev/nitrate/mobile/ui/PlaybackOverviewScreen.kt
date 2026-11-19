package dev.nitrate.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.nitrate.core.model.CollectionKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.playback.OverviewEntry
import dev.nitrate.core.playback.OverviewFilter
import dev.nitrate.core.playback.PlaybackOverview
import kotlinx.coroutines.launch

/**
 * Settings → Library playback overview: every film and episode the user can see and how each would
 * play on this phone, and why; "Send to server log" saves the list for the server's admin.
 */
@Composable
fun PlaybackOverviewScreen(actions: AppActions) {
    val container = actions.container
    val scope = rememberCoroutineScope()
    val libraries by produceState(emptyList<MediaItem>()) {
        value = runCatching { actions.server.views() }.getOrDefault(emptyList()).filter { it.collection == CollectionKind.MOVIES || it.collection == CollectionKind.TV_SHOWS }
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
        if (ids.isEmpty()) return
        testing = ids
        testStarted = dev.nitrate.android.nowMs()
        // Straight to the player: no guards, recaps or Up Next.
        actions.nav.navigate(PlayerRoute(ids.first(), queue = ids.drop(1).joinToString(","), test = true))
    }
    var reading by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf(OverviewFilter.ALL) }
    var detail by remember { mutableStateOf<OverviewEntry?>(null) }
    var sent by remember { mutableStateOf<String?>(null) }
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

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item {
            Column(Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Library playback overview", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "How every film and episode you can see would play on this phone, and why. \"Send to server log\" saves it for your server's admin.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(libraryId == null, { libraryId = null }, label = { Text("All") })
                    libraries.forEach { l -> FilterChip(libraryId == l.id, { libraryId = l.id }, label = { Text(l.name) }) }
                }
                reading?.let { (done, total) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.padding(4.dp))
                        Text(if (total > 0) "Reading $done of $total…" else "Reading the library…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        overview?.takeIf { reading == null }?.let { o ->
            item {
                Column(Modifier.padding(horizontal = ScreenPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(o.settings.joinToString("  ·  ") { (k, v) -> "$k: $v" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OverviewFilter.entries.forEach { f -> FilterChip(filter == f, { filter = f }, label = { Text("${f.label} · ${o.count(f)}") }) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                    }
                    OutlinedButton(onClick = { sample = dev.nitrate.core.playback.TestPlay.sample(o.entries) }) { Text("Test a sample") }
                    sent?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = accentColor()) }
                }
            }
            val shown = o.entries.filter(filter::matches)
            if (shown.isEmpty()) item { Text("Nothing here.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(ScreenPadding)) }
            items(shown, key = { it.item.id }) { e ->
                Column(Modifier.fillMaxWidth().clickable { detail = e }.padding(horizontal = ScreenPadding, vertical = 8.dp)) {
                    Text(e.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(e.prediction.summary, style = MaterialTheme.typography.bodySmall)
                    e.prediction.reasons.firstOrNull()?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
                    tests[e.item.id]?.let { t -> Text("Tested: ${t.summary}", style = MaterialTheme.typography.bodySmall, color = if (t.ok) accentColor() else androidx.compose.ui.graphics.Color(0xFFFFB74D)) }
                }
            }
        }
        item { androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = NavBarClearance)) }
    }
    detail?.let { e ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(e.name) },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    e.prediction.rows().forEach { (label, text) -> Text("$label: $text", style = MaterialTheme.typography.bodyMedium) }
                    tests[e.item.id]?.let { t ->
                        Text("Last test play: ${t.summary}", style = MaterialTheme.typography.bodyMedium, color = if (t.ok) accentColor() else androidx.compose.ui.graphics.Color(0xFFFFB74D), modifier = Modifier.padding(top = 6.dp))
                        TextButton(onClick = { detail = null; showTrail = t }) { Text("See what happened") }
                    }
                    if (dev.nitrate.mobile.platform.canTestPlay) TextButton(onClick = { detail = null; startTests(listOf(e.item.id)) }) { Text("Test play (30 s)") }
                }
            },
            confirmButton = { TextButton(onClick = { detail = null; actions.nav.navigate(DetailsRoute(e.item.id)) }) { Text("Open") } },
            dismissButton = { TextButton(onClick = { detail = null }) { Text("Close") } },
        )
    }
    sample?.let { list ->
        AlertDialog(
            onDismissRequest = { sample = null },
            title = { Text("Test a sample") },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${list.size} kinds of file. One title of each plays for 30 seconds, a few minutes in, through the normal path (nothing is marked watched).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    list.forEach { e ->
                        Text(e.source?.let(dev.nitrate.core.playback.TestPlay::describe) ?: e.name, style = MaterialTheme.typography.bodyMedium)
                        Text("${e.name} · ${e.prediction.delivery.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = { if (dev.nitrate.mobile.platform.canTestPlay) TextButton(onClick = { sample = null; startTests(list.map { it.item.id }) }) { Text("Start") } },
            dismissButton = { TextButton(onClick = { sample = null }) { Text("Close") } },
        )
    }
    // Back from a test: what each one did.
    val finished = testing.mapNotNull { id -> tests[id]?.takeIf { it.atMs >= testStarted } }
    if (testing.isNotEmpty() && finished.isNotEmpty() && showTrail == null) {
        if (finished.size == 1 && testing.size == 1) LaunchedEffect(finished) { showTrail = finished.single(); testing = emptyList() }
        else AlertDialog(
            onDismissRequest = { testing = emptyList() },
            title = { Text("Test plays: ${finished.size} of ${testing.size}") },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    finished.forEach { t ->
                        Column(Modifier.fillMaxWidth().clickable { showTrail = t }.padding(vertical = 6.dp)) {
                            Text(t.name, style = MaterialTheme.typography.titleSmall)
                            Text(t.summary, style = MaterialTheme.typography.bodySmall, color = if (t.ok) accentColor() else androidx.compose.ui.graphics.Color(0xFFFFB74D))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { testing = emptyList() }) { Text("Close") } },
        )
    }
    showTrail?.let { t ->
        AlertDialog(
            onDismissRequest = { showTrail = null },
            title = { Text("Test play: ${t.name}") },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(listOf(t.summary, "${t.playedMs / 1000} s played", "${t.droppedFrames} dropped frames").joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                    t.trail?.let { TrailView(it) }
                }
            },
            confirmButton = { TextButton(onClick = { showTrail = null }) { Text("Close") } },
        )
    }
}
