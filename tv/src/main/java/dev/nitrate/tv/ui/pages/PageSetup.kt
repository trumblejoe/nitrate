package dev.nitrate.tv.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import dev.nitrate.core.pages.CustomPage
import dev.nitrate.core.effects.PageTheme
import dev.nitrate.tv.ui.components.TvTextField
import dev.nitrate.tv.ui.lists.TvDialog
import dev.nitrate.tv.ui.rows.PageEditor
import dev.nitrate.tv.ui.effects.withTheme
import kotlinx.coroutines.launch

private val Padding = 58.dp

/**
 * Settings → Display → Pages: where pages are arranged, rather than on the pages themselves. Home and
 * each library open the same setup ([SetupPage]): its theme, then its tabs and their rows. A row's look
 * stays a press away on the page (Fast-forward/Rewind, or holding OK).
 */
@Composable
fun PageSetupScreen(container: AppContainer, onHome: () -> Unit, onLibrary: (MediaItem) -> Unit) {
    val first = remember { FocusRequester() }
    val prefs = dev.nitrate.tv.ui.components.LocalDisplayPrefs.current
    val libraries by produceState(emptyList<MediaItem>()) { value = runCatching { container.server.views() }.getOrDefault(emptyList()) }
    val shown = libraries.filter { it.collection != CollectionKind.LIVE_TV && it.collection != CollectionKind.PLAYLISTS }
    fun themeOf(key: String) = (prefs.pageThemes[key] ?: PageTheme.NONE).label
    var moreFrom by remember { mutableStateOf<String?>(null) }
    LazyColumn(contentPadding = PaddingValues(horizontal = Padding, vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxSize()) {
        item { SetupHeader("Pages", "Each page's theme, its tabs and their rows. Home and every library are set up the same way.") }
        item {
            ListItem(
                selected = false, onClick = onHome,
                headlineContent = { Text("Home") },
                supportingContent = { Text("Theme: ${themeOf(PageKeys.HOME)} · tabs and their rows") },
                trailingContent = { Text("›", color = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.focusRequester(first),
            )
        }
        items(shown, key = { it.id }) { lib ->
            ListItem(
                selected = false, onClick = { onLibrary(lib) },
                headlineContent = { Text(lib.name) },
                supportingContent = { Text("Theme: ${themeOf(PageKeys.library(lib.id))} · tabs and their rows") },
                trailingContent = { Text("›", color = MaterialTheme.colorScheme.primary) },
            )
        }
        // The pages a title's studio, year or cast open: one look for them all.
        item { Text("More from… pages", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp)) }
        item { ThemeItem(prefs.pageThemes[PageKeys.MORE_FROM], null) { moreFrom = "theme" } }
        item { BackgroundItem(prefs.pageBackdrops[PageKeys.MORE_FROM]) { moreFrom = "background" } }
    }
    when (moreFrom) {
        "theme" -> ThemeChoice(container, PageKeys.MORE_FROM, "More from… pages") { moreFrom = null }
        "background" -> BackgroundChoice(container, PageKeys.MORE_FROM, "More from… pages") { moreFrom = null }
    }
    LaunchedEffect(Unit) { kotlinx.coroutines.android.awaitFrame(); runCatching { first.requestFocus() } }
}

/**
 * Home's setup, laid out like a library's: its theme, then its tabs. "Home" (its own rows) comes
 * first and always shows; the user's own pages follow, shown as tabs when switched on (pinned), and
 * "Add your own tab" makes a new one.
 */
@Composable
fun HomeSetupScreen(container: AppContainer) {
    val server = container.server
    val user = server.userId
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    val prefs = dev.nitrate.tv.ui.components.LocalDisplayPrefs.current
    val pages by remember(user) { user?.let { container.displaySettings.customPages(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList()) }.collectAsState(emptyList())
    fun save(next: List<CustomPage>) { val u = user ?: return; scope.launch { container.displaySettings.saveCustomPages(u, next) } }
    var choosingTheme by remember { mutableStateOf(false) }
    var actingHome by remember { mutableStateOf(false) }
    var acting by remember { mutableStateOf<CustomPage?>(null) }
    var renaming by remember { mutableStateOf<CustomPage?>(null) }
    var adding by remember { mutableStateOf(false) }
    var editingHome by remember { mutableStateOf(false) }
    var editingPage by remember { mutableStateOf<CustomPage?>(null) }
    if (choosingTheme) ThemeChoice(container, PageKeys.HOME, "Home") { choosingTheme = false }
    var choosingBackground by remember { mutableStateOf(false) }
    if (choosingBackground) BackgroundChoice(container, PageKeys.HOME, "Home") { choosingBackground = false }

    SetupPage("Home", first) {
        item { ThemeItem(prefs.pageThemes[PageKeys.HOME], first) { choosingTheme = true } }
        item { BackgroundItem(prefs.pageBackdrops[PageKeys.HOME]) { choosingBackground = true } }
        item { TabsLabel() }
        item { TabItem("Home", "Its rows: order, hide, rename, and add your own", shown = null) { actingHome = true } }
        items(pages, key = { it.id }) { page ->
            TabItem(page.title, if (page.pinned) "Your page, a tab beside Home" else "Your page, not on Home (in Your pages)", shown = page.pinned) { acting = page }
        }
        item { AddTabItem { adding = true } }
    }

    if (actingHome) TabActions("Home", hidden = null, upEnabled = false, downEnabled = false, onDismiss = { actingHome = false },
        onRows = { actingHome = false; editingHome = true })
    acting?.let { page ->
        val i = pages.indexOfFirst { it.id == page.id }
        TabActions(
            page.title, hidden = !page.pinned, upEnabled = i > 0, downEnabled = i in 0 until pages.lastIndex,
            onDismiss = { acting = null },
            onToggle = { save(pages.map { if (it.id == page.id) it.copy(pinned = !it.pinned) else it }); acting = null },
            onMove = { by -> save(movePage(pages, page.id, by)); acting = null },
            onRename = { renaming = page; acting = null },
            onRows = { editingPage = page; acting = null },
            removeLabel = "Delete this page",
            onRemove = { user?.let { scope.launch { container.displaySettings.deleteCustomPage(it, page.id) } }; acting = null },
        )
    }
    renaming?.let { page ->
        NameDialog("Rename tab", page.title, onDismiss = { renaming = null }) { name ->
            save(pages.map { if (it.id == page.id) it.copy(title = name.trim().ifEmpty { it.title }) else it })
            renaming = null
        }
    }
    if (adding) {
        NameDialog("Your own tab", "", onDismiss = { adding = false }) { name ->
            val made = CustomPage(dev.nitrate.tv.ui.rows.newId(), name.trim().ifEmpty { "My page" }, pinned = true)
            save(pages + made)
            adding = false
            // Straight on to its rows: an empty tab shows nothing.
            editingPage = made
        }
    }
    if (editingHome) {
        val vm = viewModel(key = "home-setup") {
            RowsViewModel(PageKeys.HOME, server::homeRows, container.displaySettings, server, container.serverChanges, cache = container.dataCache)
        }
        PageEditor("Home", vm, server, libraryId = null, onDone = { editingHome = false })
    }
    editingPage?.let { page ->
        val vm = viewModel(key = "custom-setup-${page.id}") {
            RowsViewModel(PageKeys.custom(page.id), { emptyList() }, container.displaySettings, server, container.serverChanges, cache = container.dataCache)
        }
        PageEditor(page.title, vm, server, libraryId = null, onDone = { editingPage = null })
    }
}

private fun movePage(pages: List<CustomPage>, id: String, by: Int): List<CustomPage> {
    val i = pages.indexOfFirst { it.id == id }
    val j = i + by
    if (i < 0 || j !in pages.indices) return pages
    return pages.toMutableList().apply { add(j, removeAt(i)) }
}

// ---- The frame every page's setup shares ----

@Composable
private fun SetupHeader(title: String, intro: String) {
    Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.displaySmall)
        Text(intro, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One page's setup: its name, then [content] (theme, tabs), the same for Home and every library. */
@Composable
private fun SetupPage(title: String, @Suppress("UNUSED_PARAMETER") first: FocusRequester, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(contentPadding = PaddingValues(horizontal = Padding, vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxSize()) {
        item { SetupHeader(title, "Its theme, then its tabs in order. OK on a tab to show or hide it, move it, rename it or change its rows.") }
        content()
    }
}

@Composable
private fun ThemeItem(theme: PageTheme?, focus: FocusRequester?, onClick: () -> Unit) {
    // Focus starts here, once: asked for after the row is built (asking earlier does nothing), and
    // never again (a list loading later used to pull focus back here, even out of an open picker).
    var taken by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (focus != null) LaunchedEffect(Unit) { if (!taken) { kotlinx.coroutines.android.awaitFrame(); runCatching { focus.requestFocus() }; taken = true } }
    val modifier = if (focus != null) Modifier.focusRequester(focus) else Modifier
    ListItem(
        selected = false, onClick = onClick,
        headlineContent = { Text("Theme") },
        supportingContent = { Text("An animated background over this page: thunderstorm, safari, roses…") },
        trailingContent = { Text("${(theme ?: PageTheme.NONE).label}  ›", color = MaterialTheme.colorScheme.primary) },
        modifier = modifier,
    )
}

@Composable
private fun TabsLabel() {
    Text("Tabs", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp))
}

/** A tab: [shown] is its switch (null for one that always shows). */
@Composable
private fun TabItem(label: String, detail: String, shown: Boolean?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    ListItem(
        selected = false, onClick = onClick,
        headlineContent = { Text(label) },
        supportingContent = { Text(detail) },
        trailingContent = { if (shown != null) Switch(checked = shown, onCheckedChange = null) else Text("Always", color = MaterialTheme.colorScheme.onSurfaceVariant) },
        modifier = modifier,
    )
}

@Composable
private fun AddTabItem(onClick: () -> Unit) {
    ListItem(
        selected = false, onClick = onClick,
        headlineContent = { Text("Add your own tab") },
        supportingContent = { Text("A page of rows you build: \"Kids\", \"4K\", \"Unwatched horror\"…") },
        trailingContent = { Text("+", color = MaterialTheme.colorScheme.primary) },
    )
}

/** What can be done to a tab, the same choices on Home and in libraries; null leaves a choice out. */
@Composable
private fun TabActions(
    label: String,
    hidden: Boolean?,
    upEnabled: Boolean,
    downEnabled: Boolean,
    onDismiss: () -> Unit,
    onToggle: (() -> Unit)? = null,
    onMove: ((Int) -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    onRows: (() -> Unit)? = null,
    removeLabel: String = "Remove this tab",
    onRemove: (() -> Unit)? = null,
) {
    val focus = remember { FocusRequester() }
    TvDialog(onDismiss = onDismiss, width = 520) {
        Text(label, style = MaterialTheme.typography.headlineSmall)
        // The first choice takes focus, whichever it is.
        var focusTaken = false
        fun first(): Modifier = if (focusTaken) Modifier else { focusTaken = true; Modifier.focusRequester(focus) }
        if (hidden != null && onToggle != null) Button(onClick = onToggle, modifier = first()) { Text(if (hidden) "Show this tab" else "Hide this tab") }
        if (onRows != null) OutlinedButton(onClick = onRows, modifier = first()) { Text("Rows: order, hide, add") }
        if (onMove != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onMove(-1) }, enabled = upEnabled, modifier = if (upEnabled) first() else Modifier) { Text("Move up") }
            OutlinedButton(onClick = { onMove(1) }, enabled = downEnabled, modifier = if (downEnabled) first() else Modifier) { Text("Move down") }
        }
        if (onRename != null) OutlinedButton(onClick = onRename, modifier = first()) { Text("Rename") }
        if (onRemove != null) OutlinedButton(onClick = onRemove, modifier = first()) { Text(removeLabel) }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
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
    var choosingBackground by remember { mutableStateOf(false) }
    if (choosingBackground) BackgroundChoice(container, PageKeys.library(libraryId), lib?.name ?: "Library") { choosingBackground = false }

    SetupPage(lib?.name ?: "Library", first) {
        item { ThemeItem(prefs.pageThemes[PageKeys.library(libraryId)], first) { choosingTheme = true } }
        item { BackgroundItem(prefs.pageBackdrops[PageKeys.library(libraryId)]) { choosingBackground = true } }
        item { TabsLabel() }
        items(tabs, key = { it.id }) { tab ->
            TabItem(tab.label, if (tab.title != null && tab.kind != LibraryTabKind.OWN) "${tab.kind.label} · ${tab.kind.detail}" else tab.kind.detail, shown = !tab.hidden) { acting = tab }
        }
        item { AddTabItem { adding = true } }
    }

    acting?.let { tab ->
        val rows = tab.kind in setOf(LibraryTabKind.RECOMMENDED, LibraryTabKind.GENRES, LibraryTabKind.STUDIOS, LibraryTabKind.DECADES, LibraryTabKind.OWN)
        fun act(change: (List<LibraryTab>) -> List<LibraryTab>) { save(change(tabs)); acting = null }
        TabActions(
            tab.label, hidden = tab.hidden, upEnabled = tabs.first().id != tab.id, downEnabled = tabs.last().id != tab.id,
            onDismiss = { acting = null },
            onToggle = { act { list -> list.map { if (it.id == tab.id) it.copy(hidden = !it.hidden) else it } } },
            onMove = { by -> act { LibraryTabs.move(it, tab.id, by) } },
            onRename = { renaming = tab; acting = null },
            onRows = if (rows) ({ editingRows = tab; acting = null }) else null,
            onRemove = if (tab.kind == LibraryTabKind.OWN) ({ act { list -> list.filterNot { it.id == tab.id } } }) else null,
        )
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

/** The page's background: the title's picture, a colour from it, a gradient or a flat colour. */
@Composable
private fun BackgroundItem(choice: dev.nitrate.core.effects.PageBackdrop?, onClick: () -> Unit) {
    val c = choice ?: dev.nitrate.core.effects.PageBackdrop.ARTWORK
    ListItem(
        selected = false, onClick = onClick,
        headlineContent = { Text("Background") },
        supportingContent = { Text("Behind everything (and any theme): the title's picture, a smart colour from it, a gradient or a flat colour") },
        leadingContent = { Swatch(c) },
        trailingContent = { Text("${c.label}  ›", color = MaterialTheme.colorScheme.primary) },
    )
}

/** A small sample of a background (the smart ones in a stand-in colour). */
@Composable
private fun Swatch(choice: dev.nitrate.core.effects.PageBackdrop) {
    Box(Modifier.size(width = 54.dp, height = 32.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2A2D36))) {
        when (choice.kind) {
            dev.nitrate.core.effects.PageBackdrop.Kind.ARTWORK -> Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF6B4A2B), Color(0xFF24324A)))))
            dev.nitrate.core.effects.PageBackdrop.Kind.SMART_GRADIENT -> Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF7A3B52), Color(0xFF2E1520), Color(0xFF050608)))))
            dev.nitrate.core.effects.PageBackdrop.Kind.SMART_COLOUR -> Box(Modifier.fillMaxSize().background(Color(0xFF3A2131)))
            else -> dev.nitrate.tv.ui.effects.PageBackground(choice, null)
        }
    }
}

/** Picks [pageKey]'s background and saves it in the display profile. */
@Composable
private fun BackgroundChoice(container: AppContainer, pageKey: String, name: String, onDone: () -> Unit) {
    val prefs = dev.nitrate.tv.ui.components.LocalDisplayPrefs.current
    val scope = rememberCoroutineScope()
    val now = prefs.pageBackdrops[pageKey] ?: dev.nitrate.core.effects.PageBackdrop.ARTWORK
    val focus = remember { FocusRequester() }
    TvDialog(onDismiss = onDone, width = 640) {
        Text("$name: background", style = MaterialTheme.typography.headlineSmall)
        dev.nitrate.core.effects.PageBackdrop.entries.forEach { b ->
            ListItem(
                selected = b == now,
                onClick = {
                    val u = container.server.userId
                    if (u != null) scope.launch {
                        container.displaySettings.savePrefs(u, prefs.copy(pageBackdrops = if (b == dev.nitrate.core.effects.PageBackdrop.ARTWORK) prefs.pageBackdrops - pageKey else prefs.pageBackdrops + (pageKey to b)))
                    }
                    onDone()
                },
                headlineContent = { Text(b.label) },
                supportingContent = { Text(b.detail) },
                leadingContent = { Swatch(b) },
                modifier = if (b == now) Modifier.focusRequester(focus) else Modifier,
            )
        }
    }
    LaunchedEffect(Unit) { kotlinx.coroutines.android.awaitFrame(); runCatching { focus.requestFocus() } }
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
