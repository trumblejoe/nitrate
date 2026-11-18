package dev.nitrate.tv.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import dev.nitrate.android.AppContainer
import dev.nitrate.android.viewmodel.RowsViewModel
import dev.nitrate.core.model.CollectionKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.pages.LibraryTab
import dev.nitrate.core.pages.LibraryTabKind
import dev.nitrate.core.pages.LibraryTabs
import dev.nitrate.core.pages.PageKeys
import dev.nitrate.tv.ui.components.TvTextField
import dev.nitrate.tv.ui.lists.TvDialog
import dev.nitrate.tv.ui.rows.PageEditor
import dev.nitrate.tv.ui.effects.withTheme
import kotlinx.coroutines.launch

private val Padding = 58.dp

/**
 * Settings → Display → Pages: where pages are arranged, rather than on the pages themselves. Home's
 * rows (order, hide, rename, add your own), and each library's tabs. A row's look stays a press away
 * on the page (Fast-forward/Rewind, or holding OK).
 */
@Composable
fun PageSetupScreen(container: AppContainer, onLibrary: (MediaItem) -> Unit) {
    val first = remember { FocusRequester() }
    var editingHome by remember { mutableStateOf(false) }
    var choosingTheme by remember { mutableStateOf<Pair<String, String>?>(null) }
    val prefs = dev.nitrate.tv.ui.components.LocalDisplayPrefs.current
    val libraries = dev.nitrate.tv.ui.home.rememberLibraries(container)
        .filter { it.collection != CollectionKind.LIVE_TV && it.collection != CollectionKind.PLAYLISTS }
    LazyColumn(contentPadding = PaddingValues(horizontal = Padding, vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Pages", style = MaterialTheme.typography.displaySmall)
                Text("Home's rows and each library's tabs. A row's look changes on the page itself: Fast-forward/Rewind on a row, or hold OK.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            ListItem(
                selected = false, onClick = { editingHome = true },
                headlineContent = { Text("Home") },
                supportingContent = { Text("Its rows: order, hide, rename, and add your own") },
                trailingContent = { Text("›", color = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.focusRequester(first),
            )
        }
        item {
            val theme = prefs.pageThemes[PageKeys.HOME] ?: dev.nitrate.core.effects.PageTheme.NONE
            ListItem(
                selected = false, onClick = { choosingTheme = PageKeys.HOME to "Home" },
                headlineContent = { Text("Home's theme") },
                supportingContent = { Text("An animated background: thunderstorm, safari, deep space…") },
                trailingContent = { Text("${theme.label}  ›", color = MaterialTheme.colorScheme.primary) },
            )
        }
        items(libraries, key = { it.id }) { lib ->
            ListItem(
                selected = false, onClick = { onLibrary(lib) },
                headlineContent = { Text(lib.name) },
                supportingContent = { Text("Its tabs: which show, their order and names, your own tabs and their rows") },
                trailingContent = { Text("›", color = MaterialTheme.colorScheme.primary) },
            )
        }
    }
    choosingTheme?.let { (key, name) -> ThemeChoice(container, key, name) { choosingTheme = null } }
    if (editingHome) {
        val vm = viewModel(key = "home-setup") {
            RowsViewModel(PageKeys.HOME, container.server::homeRows, container.displaySettings, container.server, container.serverChanges, cache = container.dataCache)
        }
        PageEditor("Home", vm, container.server, libraryId = null, onDone = { editingHome = false })
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

/**
 * One library's tabs: every kind it offers and the user's own, in order. OK on a tab: show or hide
 * it, move it, rename it, edit its rows (row tabs), or remove it (your own). "Add your own tab" at
 * the end.
 */
@Composable
fun LibraryTabsScreen(container: AppContainer, libraryId: String) {
    val server = container.server
    val user = server.userId
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    val library by produceState<MediaItem?>(null, libraryId) { value = runCatching { server.getItem(libraryId) }.getOrNull() }
    val lib = library
    val saved by remember(user, libraryId) {
        user?.let { container.displaySettings.libraryTabs(it, libraryId) } ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(emptyList())
    val hasRecommended = lib?.collection == CollectionKind.MOVIES || lib?.collection == CollectionKind.TV_SHOWS
    val tabs = LibraryTabs.resolve(saved, hasRecommended)
    fun save(next: List<LibraryTab>) { val u = user ?: return; scope.launch { container.displaySettings.saveLibraryTabs(u, libraryId, next) } }
    var acting by remember { mutableStateOf<LibraryTab?>(null) }
    var renaming by remember { mutableStateOf<LibraryTab?>(null) }
    var adding by remember { mutableStateOf(false) }
    var editingRows by remember { mutableStateOf<LibraryTab?>(null) }
    var choosingTheme by remember { mutableStateOf(false) }
    val prefs = dev.nitrate.tv.ui.components.LocalDisplayPrefs.current
    if (choosingTheme) ThemeChoice(container, PageKeys.library(libraryId), lib?.name ?: "Library") { choosingTheme = false }

    LazyColumn(contentPadding = PaddingValues(horizontal = Padding, vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(lib?.name ?: "Library", style = MaterialTheme.typography.displaySmall)
                Text("Its tabs, in order. OK on one to show or hide it, move it, rename it or change its rows.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            val theme = prefs.pageThemes[PageKeys.library(libraryId)] ?: dev.nitrate.core.effects.PageTheme.NONE
            ListItem(
                selected = false, onClick = { choosingTheme = true },
                headlineContent = { Text("Theme") },
                supportingContent = { Text("An animated background over this library's pages") },
                trailingContent = { Text("${theme.label}  ›", color = MaterialTheme.colorScheme.primary) },
            )
        }
        items(tabs, key = { it.id }) { tab ->
            ListItem(
                selected = false,
                onClick = { acting = tab },
                headlineContent = { Text(tab.label) },
                supportingContent = { Text(if (tab.title != null && tab.kind != LibraryTabKind.OWN) "${tab.kind.label} · ${tab.kind.detail}" else tab.kind.detail) },
                trailingContent = { Switch(checked = !tab.hidden, onCheckedChange = null) },
                modifier = if (tab == tabs.first()) Modifier.focusRequester(first) else Modifier,
            )
        }
        item {
            ListItem(
                selected = false, onClick = { adding = true },
                headlineContent = { Text("Add your own tab") },
                supportingContent = { Text("A page of rows you build: \"Kids\", \"4K\", \"Unwatched horror\"…") },
                trailingContent = { Text("+", color = MaterialTheme.colorScheme.primary) },
            )
        }
    }
    LaunchedEffect(tabs.isNotEmpty()) { if (tabs.isNotEmpty()) runCatching { first.requestFocus() } }

    acting?.let { tab ->
        val rows = tab.kind in setOf(LibraryTabKind.RECOMMENDED, LibraryTabKind.GENRES, LibraryTabKind.STUDIOS, LibraryTabKind.DECADES, LibraryTabKind.OWN)
        val focus = remember { FocusRequester() }
        TvDialog(onDismiss = { acting = null }, width = 520) {
            Text(tab.label, style = MaterialTheme.typography.headlineSmall)
            fun act(change: (List<LibraryTab>) -> List<LibraryTab>) { save(change(tabs)); acting = null }
            Button(onClick = { act { list -> list.map { if (it.id == tab.id) it.copy(hidden = !it.hidden) else it } } }, modifier = Modifier.focusRequester(focus)) {
                Text(if (tab.hidden) "Show this tab" else "Hide this tab")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { act { LibraryTabs.move(it, tab.id, -1) } }, enabled = tabs.first().id != tab.id) { Text("Move up") }
                OutlinedButton(onClick = { act { LibraryTabs.move(it, tab.id, 1) } }, enabled = tabs.last().id != tab.id) { Text("Move down") }
            }
            OutlinedButton(onClick = { renaming = tab; acting = null }) { Text("Rename") }
            if (rows) OutlinedButton(onClick = { editingRows = tab; acting = null }) { Text("Rows: order, hide, add") }
            if (tab.kind == LibraryTabKind.OWN) OutlinedButton(onClick = { act { list -> list.filterNot { it.id == tab.id } } }) { Text("Remove this tab") }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        }
    }

    renaming?.let { tab ->
        NameDialog("Rename tab", tab.label, onDismiss = { renaming = null }) { name ->
            save(tabs.map { if (it.id == tab.id) it.copy(title = name.trim().takeIf { n -> n.isNotEmpty() && n != it.kind.label }) else it })
            renaming = null
        }
    }
    if (adding) {
        NameDialog("Your own tab", "", onDismiss = { adding = false }) { name ->
            val made = LibraryTabs.newOwn(tabs, name.trim().ifEmpty { "My tab" })
            save(tabs + made)
            adding = false
            // Straight on to its rows: an empty tab shows nothing.
            editingRows = made
        }
    }
    editingRows?.let { tab ->
        if (lib != null) {
            val types = LibraryTabs.typesFor(lib.collection)
            val vm = viewModel(key = "tab-setup-${lib.id}-${tab.id}") {
                RowsViewModel(
                    LibraryTabs.pageKey(lib.id, tab),
                    {
                        when (tab.kind) {
                            LibraryTabKind.RECOMMENDED -> server.libraryRows(lib)
                            LibraryTabKind.OWN -> emptyList()
                            else -> LibraryTabs.rows(lib.id, tab.kind, types, { server.genres(lib.id) }, { server.studios(lib.id) }, { server.libraryFilters(lib.id, types).years }, server::queryItems)
                        }
                    },
                    container.displaySettings, server, container.serverChanges,
                )
            }
            PageEditor("${lib.name} · ${tab.label}", vm, server, libraryId = lib.id, onDone = { editingRows = null })
        }
    }
}

/** Picks [pageKey]'s theme and saves it in the display profile. */
@Composable
private fun ThemeChoice(container: AppContainer, pageKey: String, name: String, onDone: () -> Unit) {
    val prefs = dev.nitrate.tv.ui.components.LocalDisplayPrefs.current
    val scope = rememberCoroutineScope()
    dev.nitrate.tv.ui.effects.PageThemePicker(name, prefs.pageThemes[pageKey] ?: dev.nitrate.core.effects.PageTheme.NONE, onPick = { theme ->
        val u = container.server.userId
        if (u != null) scope.launch { container.displaySettings.savePrefs(u, prefs.withTheme(pageKey, theme)) }
        onDone()
    }, onDismiss = onDone)
}

/** A name to type (TV keyboard on OK), with Save and Cancel. */
@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    TvDialog(onDismiss = onDismiss, width = 560) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        TvTextField(value = name, onValueChange = { name = it }, placeholder = "Name", modifier = Modifier.focusRequester(focus))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onSave(name) }) { Text("Save") }
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
}
