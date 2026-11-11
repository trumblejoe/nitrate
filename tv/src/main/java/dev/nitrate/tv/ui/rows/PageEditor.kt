package dev.nitrate.tv.ui.rows

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.FilterChip
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.nitrate.android.viewmodel.RowsState
import dev.nitrate.android.viewmodel.RowsViewModel
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.ImageKind
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.pages.ItemQuery
import dev.nitrate.core.pages.PageComposer
import dev.nitrate.core.pages.QuerySort
import dev.nitrate.core.pages.ResolutionFilter
import dev.nitrate.core.pages.RowEntry
import dev.nitrate.core.pages.RowSource
import dev.nitrate.core.pages.WatchFilter
import dev.nitrate.core.rows.MediaRow
import dev.nitrate.tv.ui.components.TvTextField
import kotlinx.coroutines.delay
import kotlin.random.Random

internal fun newId() = Random.nextBytes(8).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

@Composable
private fun EditorPanel(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.92f)), Alignment.Center) {
        Column(
            Modifier.width(900.dp).fillMaxHeight(0.92f).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

/**
 * Edits one page's rows with the remote: order, show/hide, rename, look, and rows of the user's
 * own (filters, collections, playlists). [libraryId] seeds new rows on a library page.
 */
@Composable
fun PageEditor(pageTitle: String, vm: RowsViewModel, repository: MediaServer, libraryId: String?, onDone: () -> Unit) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val overrides by vm.overrides.collectAsStateWithLifecycle()
    val loaded = (state as? RowsState.Loaded)?.rows.orEmpty()
    var editing by remember { mutableStateOf<RowEntry?>(null) }
    var adding by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }

    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        EditorPanel {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Edit $pageTitle", style = MaterialTheme.typography.headlineSmall)
                    Text("Changes follow you to your other devices", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = { adding = true }, modifier = Modifier.focusRequester(first)) { Text("Add row") }
                OutlinedButton(onClick = { vm.reset() }) { Text("Reset") }
                OutlinedButton(onClick = onDone) { Text("Done") }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                if (entries.isEmpty()) item { Text("No rows yet. Add one to start this page.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                itemsIndexed(entries, key = { _, e -> e.id }) { i, e ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ListItem(
                            selected = false,
                            onClick = { editing = e },
                            headlineContent = { Text(vm.titleOf(e), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    when (val s = e.source) {
                                        is RowSource.BuiltIn -> if (e.hidden) "Built in · hidden" else "Built in"
                                        is RowSource.Query -> s.query.describe() + if (e.hidden) " · hidden" else ""
                                        is RowSource.Collection -> "Collection" + if (e.hidden) " · hidden" else ""
                                        is RowSource.Playlist -> "Playlist" + if (e.hidden) " · hidden" else ""
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = { vm.move(e.id, -1) }, enabled = i > 0) { Text("Up") }
                        OutlinedButton(onClick = { vm.move(e.id, 1) }, enabled = i < entries.lastIndex) { Text("Down") }
                        OutlinedButton(onClick = { vm.setHidden(e.id, !e.hidden) }) { Text(if (e.hidden) "Show" else "Hide") }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    editing?.let { e ->
        val row = loaded.firstOrNull { it.key == e.rowKey }
        EntryDialog(
            entry = e,
            title = vm.titleOf(e),
            row = row,
            display = e.display ?: overrides[e.rowKey] ?: row?.defaultDisplay,
            repository = repository,
            libraryId = libraryId,
            onRename = { vm.rename(e.id, it) },
            onDisplay = { r, d -> vm.save(r, d) },
            onSource = { s -> vm.replace(e.copy(source = s)) },
            onDelete = { vm.remove(e.id); editing = null },
            onDismiss = { editing = null },
        )
    }
    if (adding) {
        RowBuilder(repository, libraryId, initial = null, onSave = { source, name ->
            vm.add(RowEntry(newId(), source, name, display = PageComposer.defaultDisplay(source)))
            adding = false
        }, onDismiss = { adding = false })
    }
}

@Composable
private fun EntryDialog(
    entry: RowEntry,
    title: String,
    row: MediaRow?,
    display: dev.nitrate.core.rows.RowDisplay?,
    repository: MediaServer,
    libraryId: String?,
    onRename: (String?) -> Unit,
    onDisplay: (MediaRow, dev.nitrate.core.rows.RowDisplay) -> Unit,
    onSource: (RowSource) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(entry.title.orEmpty()) }
    var look by remember { mutableStateOf(false) }
    var contents by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = { onRename(name); onDismiss() }) {
        Column(
            Modifier.width(520.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Edit row", style = MaterialTheme.typography.headlineSmall)
            TvTextField(name, { name = it }, placeholder = title, modifier = Modifier.focusRequester(first))
            if (row != null && display != null) {
                ListItem(selected = false, onClick = { look = true }, headlineContent = { Text("Look") }, supportingContent = { Text("${display.style.label} · ${display.size.label}") })
            }
            if (entry.isCustom) {
                ListItem(selected = false, onClick = { contents = true }, headlineContent = { Text("Change contents") })
                ListItem(selected = false, onClick = onDelete, headlineContent = { Text("Delete row", color = MaterialTheme.colorScheme.error) })
            }
            Button(onClick = { onRename(name); onDismiss() }) { Text("Save") }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    if (look && row != null && display != null) {
        RowSettingsDialog(row = row, display = display, onChange = { onDisplay(row, it) }, onDismiss = { look = false })
    }
    if (contents) {
        RowBuilder(repository, libraryId, initial = entry.source, onSave = { s, n ->
            onSource(s)
            if (name.isBlank() && n != null) name = n
            contents = false
        }, onDismiss = { contents = false })
    }
}

private enum class Source(val label: String) { FILTER("Filter"), COLLECTION("Collection"), PLAYLIST("Playlist") }

private val DECADES = listOf(2020, 2010, 2000, 1990, 1980, 1970, 1960)

/** Builds a row from a filter over the library, a collection, or a playlist. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RowBuilder(
    repository: MediaServer,
    libraryId: String?,
    initial: RowSource?,
    onSave: (RowSource, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var source by remember { mutableStateOf(when (initial) { is RowSource.Collection -> Source.COLLECTION; is RowSource.Playlist -> Source.PLAYLIST; else -> Source.FILTER }) }
    var query by remember { mutableStateOf((initial as? RowSource.Query)?.query ?: ItemQuery(libraryId = libraryId)) }
    var picked by remember { mutableStateOf<MediaItem?>(null) }
    var name by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }

    val libraries by produceState<List<MediaItem>>(emptyList()) { value = runCatching { repository.views() }.getOrDefault(emptyList()) }
    val genres by produceState<List<String>>(emptyList(), query.libraryId) { value = runCatching { repository.genres(query.libraryId) }.getOrDefault(emptyList()) }
    val lists by produceState<List<MediaItem>?>(null, source) {
        value = when (source) {
            Source.COLLECTION -> runCatching { repository.collections() }.getOrDefault(emptyList())
            Source.PLAYLIST -> runCatching { repository.playlists() }.getOrDefault(emptyList())
            Source.FILTER -> null
        }
    }
    val preview by produceState<List<MediaItem>?>(null, query, source) {
        if (source != Source.FILTER) return@produceState
        delay(400)
        value = runCatching { repository.queryItems(query.copy(limit = 10)) }.getOrNull()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        EditorPanel {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (initial == null) "New row" else "Row contents", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                Source.entries.forEach { s -> FilterChip(selected = source == s, onClick = { source = s; picked = null }) { Text(s.label) } }
                Button(
                    enabled = source == Source.FILTER || picked != null,
                    onClick = {
                        val p = picked
                        onSave(
                            when (source) {
                                Source.FILTER -> RowSource.Query(query)
                                Source.COLLECTION -> RowSource.Collection(p!!.id, p.name)
                                Source.PLAYLIST -> RowSource.Playlist(p!!.id, p.name)
                            },
                            name.ifBlank { p?.name },
                        )
                    },
                    modifier = Modifier.focusRequester(first),
                ) { Text("Save") }
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
            }
            when (source) {
                Source.FILTER -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    TvTextField(name, { name = it }, placeholder = "Row name (optional)")
                    val p = preview
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(96.dp)) {
                        if (p.isNullOrEmpty()) item { Text(if (p == null) "Finding matches…" else "Nothing matches yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        else items(p, key = { it.id }) { item ->
                            AsyncImage(repository.imageUrl(item, ImageKind.PRIMARY, 200), item.name, modifier = Modifier.width(64.dp).height(96.dp))
                        }
                    }
                    Section("Show") {
                        listOf(ItemKind.MOVIE to "Movies", ItemKind.SERIES to "Shows", ItemKind.EPISODE to "Episodes", ItemKind.BOX_SET to "Collections").forEach { (k, l) ->
                            FilterChip(selected = k in query.types, onClick = { query = query.copy(types = if (k in query.types) query.types - k else query.types + k) }) { Text(l) }
                        }
                    }
                    Section("From") {
                        FilterChip(selected = query.libraryId == null, onClick = { query = query.copy(libraryId = null, genres = emptyList()) }) { Text("All libraries") }
                        libraries.forEach { lib -> FilterChip(selected = query.libraryId == lib.id, onClick = { query = query.copy(libraryId = lib.id, genres = emptyList()) }) { Text(lib.name) } }
                    }
                    if (genres.isNotEmpty()) Section("Genres") {
                        genres.forEach { g -> FilterChip(selected = g in query.genres, onClick = { query = query.copy(genres = if (g in query.genres) query.genres - g else query.genres + g) }) { Text(g) } }
                    }
                    Section("Watched") { WatchFilter.entries.forEach { w -> FilterChip(selected = query.watched == w, onClick = { query = query.copy(watched = w) }) { Text(w.label) } } }
                    Section("Released") {
                        FilterChip(selected = query.yearFrom == null, onClick = { query = query.copy(yearFrom = null, yearTo = null) }) { Text("Any time") }
                        DECADES.forEach { d -> FilterChip(selected = query.yearFrom == d && query.yearTo == d + 9, onClick = { query = query.copy(yearFrom = d, yearTo = d + 9) }) { Text("${d}s") } }
                    }
                    Section("Rating") {
                        listOf<Float?>(null, 6f, 7f, 8f).forEach { r -> FilterChip(selected = query.minRating == r, onClick = { query = query.copy(minRating = r) }) { Text(r?.let { "★ ${it.toInt()}+" } ?: "Any") } }
                    }
                    Section("Picture") { ResolutionFilter.entries.forEach { r -> FilterChip(selected = query.resolution == r, onClick = { query = query.copy(resolution = r) }) { Text(r.label) } } }
                    Section("Sort by") {
                        QuerySort.entries.forEach { s -> FilterChip(selected = query.sort == s, onClick = { query = query.copy(sort = s) }) { Text(s.label) } }
                        if (query.sort != QuerySort.RANDOM) FilterChip(selected = !query.descending, onClick = { query = query.copy(descending = !query.descending) }) { Text("Ascending") }
                    }
                    Section("Items") { listOf(12, 24, 48).forEach { n -> FilterChip(selected = query.limit == n, onClick = { query = query.copy(limit = n) }) { Text("$n") } } }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ListItem(selected = false, onClick = { query = query.copy(favoritesOnly = !query.favoritesOnly) }, headlineContent = { Text("Favorites only") },
                            trailingContent = { Switch(checked = query.favoritesOnly, onCheckedChange = null) })
                    }
                    Spacer(Modifier.height(8.dp))
                }
                else -> {
                    val items = lists
                    LazyColumn(Modifier.weight(1f)) {
                        when {
                            items == null -> item { Text("Loading…") }
                            items.isEmpty() -> item { Text(if (source == Source.COLLECTION) "You don't have any collections yet." else "You don't have any playlists yet.") }
                            else -> items(items, key = { it.id }) { item ->
                                ListItem(
                                    selected = picked?.id == item.id,
                                    onClick = { picked = item },
                                    headlineContent = { Text(item.name) },
                                    trailingContent = { if (picked?.id == item.id) Text("✓", color = MaterialTheme.colorScheme.primary) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Section(title: String, chips: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
    }
}
