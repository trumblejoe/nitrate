package dev.nitrate.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nitrate.android.viewmodel.RowsState
import dev.nitrate.android.viewmodel.RowsViewModel
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
import dev.nitrate.core.rows.RowDisplay
import dev.nitrate.core.rows.RowStyle
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Full-screen editor for one page's rows: order, show/hide, rename, card style, and rows of
 * the user's own. [libraryId] is the library a library page belongs to (new rows default to it).
 */
@Composable
fun PageEditor(pageTitle: String, vm: RowsViewModel, actions: AppActions, libraryId: String?, onDone: () -> Unit) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val overrides by vm.overrides.collectAsStateWithLifecycle()
    val loaded = (state as? RowsState.Loaded)?.rows.orEmpty()
    var editing by remember { mutableStateOf<RowEntry?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        TintedBackdrop(null, strength = 0.2f) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDone) { Icon(Icons.Default.Close, "Done") }
                    Column(Modifier.weight(1f)) {
                        Text("Edit $pageTitle", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Changes follow you to your other devices", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { confirmReset = true }) { Text("Reset") }
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(ScreenPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (entries.isEmpty()) item {
                        Text("No rows yet. Add one to start this page.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
                    }
                    itemsIndexed(entries, key = { _, e -> e.id }) { i, e ->
                        EntryCard(
                            title = vm.titleOf(e),
                            detail = when (val s = e.source) {
                                is RowSource.BuiltIn -> if (loaded.none { it.key == e.rowKey } && !e.hidden) "Built in · empty right now" else "Built in"
                                is RowSource.Query -> s.query.describe()
                                is RowSource.Collection -> "Collection"
                                is RowSource.Playlist -> "Playlist"
                            },
                            shown = !e.hidden,
                            first = i == 0,
                            last = i == entries.lastIndex,
                            onUp = { vm.move(e.id, -1) },
                            onDown = { vm.move(e.id, 1) },
                            onShown = { vm.setHidden(e.id, !it) },
                            onEdit = { editing = e },
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().padding(ScreenPadding), Alignment.Center) {
                    PillButton("Add row", Icons.Default.Add, primary = true, onClick = { adding = true }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    editing?.let { e ->
        val row = loaded.firstOrNull { it.key == e.rowKey }
        EntryDialog(
            entry = e,
            title = vm.titleOf(e),
            display = e.display ?: overrides[e.rowKey] ?: row?.defaultDisplay ?: PageComposer.defaultDisplay(e.source),
            actions = actions,
            libraryId = libraryId,
            onSave = { updated, display ->
                vm.replace(updated)
                if (!updated.isCustom) vm.save(row ?: MediaRow(updated.rowKey, "", emptyList(), RowDisplay()), display)
                editing = null
            },
            onDelete = { vm.remove(e.id); editing = null },
            onDismiss = { editing = null },
        )
    }
    if (adding) {
        RowBuilderDialog(actions, libraryId, initial = null, initialTitle = null, onSave = { source, title ->
            vm.add(RowEntry(newId(), source, title, display = PageComposer.defaultDisplay(source)))
            adding = false
        }, onDismiss = { adding = false })
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset $pageTitle?") },
            text = { Text("Puts the app's rows back in their usual order and removes rows you added.") },
            confirmButton = { TextButton(onClick = { vm.reset(); confirmReset = false }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

internal fun newId() = Random.nextBytes(8).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

@Composable
private fun EntryCard(
    title: String,
    detail: String,
    shown: Boolean,
    first: Boolean,
    last: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onShown: (Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp)).padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            IconButton(onClick = onUp, enabled = !first) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
            IconButton(onClick = onDown, enabled = !last) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
        }
        Column(Modifier.weight(1f).clickable(onClick = onEdit).padding(vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (shown) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit row") }
        Switch(checked = shown, onCheckedChange = onShown)
    }
}

/** Rename, card style, and (for rows the user made) what's in it or delete. */
@Composable
private fun EntryDialog(
    entry: RowEntry,
    title: String,
    display: RowDisplay,
    actions: AppActions,
    libraryId: String?,
    onSave: (RowEntry, RowDisplay) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(entry.title ?: "") }
    var style by remember { mutableStateOf(display.style) }
    var ink by remember { mutableStateOf(display.ink) }
    var titles by remember { mutableStateOf(display.showTitles) }
    var source by remember { mutableStateOf(entry.source) }
    var changing by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit row") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, placeholder = { Text(title) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Style", style = MaterialTheme.typography.titleSmall)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RowStyle.entries.forEach { s -> FilterChip(selected = style == s, onClick = { style = s }, label = { Text(s.label) }) }
                }
                if (style == RowStyle.CRESSIDA) {
                    Text("Ink", style = MaterialTheme.typography.titleSmall)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        dev.nitrate.core.rows.CressidaInk.entries.forEach { i -> FilterChip(selected = ink == i, onClick = { ink = i }, label = { Text(i.label) }) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Titles under cards", modifier = Modifier.weight(1f))
                    Switch(checked = titles, onCheckedChange = { titles = it })
                }
                if (entry.isCustom) {
                    Text(
                        when (val s = source) {
                            is RowSource.Query -> s.query.describe()
                            is RowSource.Collection -> "Collection: ${s.name}"
                            is RowSource.Playlist -> "Playlist: ${s.name}"
                            is RowSource.BuiltIn -> ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { changing = true }) { Text("Change contents") }
                        TextButton(onClick = onDelete) { Text("Delete row", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val d = display.copy(style = style, showTitles = titles, ink = ink)
                onSave(entry.copy(title = name.ifBlank { null }, source = source, display = if (entry.isCustom) d else entry.display), d)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (changing) {
        RowBuilderDialog(actions, libraryId, initial = source, initialTitle = name, onSave = { s, t ->
            source = s
            if (name.isBlank() && t != null) name = t
            changing = false
        }, onDismiss = { changing = false })
    }
}

private enum class BuilderTab(val label: String) { FILTER("Filter"), COLLECTION("Collection"), PLAYLIST("Playlist") }

private val DECADES = listOf(2020, 2010, 2000, 1990, 1980, 1970, 1960, 1950)

/** Builds a custom row: a filter over the library, or a collection or playlist. */
@Composable
fun RowBuilderDialog(
    actions: AppActions,
    libraryId: String?,
    initial: RowSource?,
    initialTitle: String?,
    onSave: (RowSource, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val server = actions.server
    var tab by remember {
        mutableStateOf(
            when (initial) {
                is RowSource.Collection -> BuilderTab.COLLECTION
                is RowSource.Playlist -> BuilderTab.PLAYLIST
                else -> BuilderTab.FILTER
            },
        )
    }
    var query by remember { mutableStateOf((initial as? RowSource.Query)?.query ?: ItemQuery(libraryId = libraryId)) }
    var picked by remember { mutableStateOf<MediaItem?>(null) }
    var name by remember { mutableStateOf(initialTitle.orEmpty()) }

    val libraries by produceState<List<MediaItem>>(emptyList()) { value = runCatching { server.views() }.getOrDefault(emptyList()) }
    val genres by produceState<List<String>>(emptyList(), query.libraryId) { value = runCatching { server.genres(query.libraryId) }.getOrDefault(emptyList()) }
    val collections by produceState<List<MediaItem>?>(null, tab) { if (tab == BuilderTab.COLLECTION) value = runCatching { server.collections() }.getOrDefault(emptyList()) }
    val playlists by produceState<List<MediaItem>?>(null, tab) { if (tab == BuilderTab.PLAYLIST) value = runCatching { server.playlists() }.getOrDefault(emptyList()) }
    // A few matching items, updated as the filter changes.
    val preview by produceState<List<MediaItem>?>(null, query, tab) {
        if (tab != BuilderTab.FILTER) return@produceState
        delay(350)
        value = runCatching { server.queryItems(query.copy(limit = 12)) }.getOrNull()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Cancel") }
                Text(if (initial == null) "New row" else "Row contents", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                val canSave = tab == BuilderTab.FILTER || picked != null || initial is RowSource.Collection && tab == BuilderTab.COLLECTION || initial is RowSource.Playlist && tab == BuilderTab.PLAYLIST
                TextButton(enabled = canSave, onClick = {
                    val p = picked
                    val source = when (tab) {
                        BuilderTab.FILTER -> RowSource.Query(query)
                        BuilderTab.COLLECTION -> p?.let { RowSource.Collection(it.id, it.name) } ?: initial!!
                        BuilderTab.PLAYLIST -> p?.let { RowSource.Playlist(it.id, it.name) } ?: initial!!
                    }
                    onSave(source, name.ifBlank { null })
                }) { Text("Save") }
            }
            PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                BuilderTab.entries.forEach { t -> Tab(tab == t, onClick = { tab = t; picked = null }, text = { Text(t.label) }) }
            }
            when (tab) {
                BuilderTab.FILTER -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(ScreenPadding), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Row name") }, placeholder = { Text(query.genres.firstOrNull() ?: "e.g. Late-night horror") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Section("Preview") {
                        val p = preview
                        when {
                            p == null -> Box(Modifier.fillMaxWidth().height(90.dp), Alignment.Center) { CircularProgressIndicator() }
                            p.isEmpty() -> Text("Nothing matches yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(p, key = { it.id }) { item -> MediaCard(item, server.cardImage(item, CardShape.POSTER, 240), CardShape.POSTER, onClick = {}, width = 64.dp) }
                            }
                        }
                    }
                    Section("Show") {
                        Chips(listOf(ItemKind.MOVIE to "Movies", ItemKind.SERIES to "Shows", ItemKind.EPISODE to "Episodes", ItemKind.BOX_SET to "Collections"), selected = { it in query.types }) { k ->
                            query = query.copy(types = if (k in query.types) query.types - k else query.types + k)
                        }
                    }
                    Section("From") {
                        Chips(listOf<Pair<String?, String>>(null to "All libraries") + libraries.map { it.id to it.name }, selected = { it == query.libraryId }) { id ->
                            query = query.copy(libraryId = id, genres = emptyList())
                        }
                    }
                    if (genres.isNotEmpty()) Section("Genres") {
                        Chips(genres.map { it to it }, selected = { it in query.genres }) { g ->
                            query = query.copy(genres = if (g in query.genres) query.genres - g else query.genres + g)
                        }
                    }
                    Section("Watched") { Chips(WatchFilter.entries.map { it to it.label }, selected = { it == query.watched }) { query = query.copy(watched = it) } }
                    Section("Released") {
                        Chips(listOf<Int?>(null).plus(DECADES).map { it to (it?.let { d -> "${d}s" } ?: "Any time") }, selected = { it == query.yearFrom && (it == null || query.yearTo == it + 9) }) { d ->
                            query = query.copy(yearFrom = d, yearTo = d?.plus(9))
                        }
                    }
                    Section("Rating") {
                        Chips(listOf<Float?>(null, 6f, 7f, 8f).map { it to (it?.let { r -> "★ ${r.toInt()}+" } ?: "Any") }, selected = { it == query.minRating }) { query = query.copy(minRating = it) }
                    }
                    Section("Picture") { Chips(ResolutionFilter.entries.map { it to it.label }, selected = { it == query.resolution }) { query = query.copy(resolution = it) } }
                    Section("Sort by") {
                        Chips(QuerySort.entries.map { it to it.label }, selected = { it == query.sort }) { query = query.copy(sort = it) }
                        if (query.sort != QuerySort.RANDOM) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            Text("Highest / newest first", modifier = Modifier.weight(1f))
                            Switch(checked = query.descending, onCheckedChange = { query = query.copy(descending = it) })
                        }
                    }
                    Section("Items") { Chips(listOf(12, 24, 48).map { it to "$it" }, selected = { it == query.limit }) { query = query.copy(limit = it) } }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Favorites only", modifier = Modifier.weight(1f))
                        Switch(checked = query.favoritesOnly, onCheckedChange = { query = query.copy(favoritesOnly = it) })
                    }
                }
                BuilderTab.COLLECTION -> PickList(collections, picked?.id ?: (initial as? RowSource.Collection)?.id, "You don't have any collections yet.") { picked = it; if (name.isBlank()) name = it.name }
                BuilderTab.PLAYLIST -> PickList(playlists, picked?.id ?: (initial as? RowSource.Playlist)?.id, "You don't have any playlists yet.") { picked = it; if (name.isBlank()) name = it.name }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun <T> Chips(options: List<Pair<T, String>>, selected: (T) -> Boolean, onClick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        options.forEach { (value, label) -> FilterChip(selected = selected(value), onClick = { onClick(value) }, label = { Text(label) }) }
    }
}

@Composable
private fun PickList(items: List<MediaItem>?, selectedId: String?, empty: String, onPick: (MediaItem) -> Unit) {
    when {
        items == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        items.isEmpty() -> Text(empty, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(ScreenPadding))
        else -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            items(items, key = { it.id }) { item ->
                Row(
                    Modifier.fillMaxWidth().selectable(item.id == selectedId, role = Role.RadioButton) { onPick(item) }.padding(horizontal = ScreenPadding, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = item.id == selectedId, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(item.name, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
