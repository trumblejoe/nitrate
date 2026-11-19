package dev.nitrate.tv.ui.settings

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
fun PlaybackOverviewScreen(container: AppContainer, onOpen: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val libraries by produceState(emptyList<MediaItem>()) {
        value = runCatching { container.server.views() }.getOrDefault(emptyList()).filter { it.collection == CollectionKind.MOVIES || it.collection == CollectionKind.TV_SHOWS }
    }
    var libraryId by rememberSaveable { mutableStateOf<String?>(null) }
    var overview by remember { mutableStateOf<PlaybackOverview?>(null) }
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
                .onSuccess { overview = it }
                .onFailure { error = it.message ?: "Couldn't read the library" }
            reading = null
        }
    }
    LaunchedEffect(libraryId) { load() }

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
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
                Button(onClick = { detail = null; onOpen(e.item.id) }) { Text("Open") }
                OutlinedButton(onClick = { detail = null }) { Text("Close") }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}
