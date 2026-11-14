package dev.nitrate.tv.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.FilterChip
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import dev.nitrate.android.AppContainer
import dev.nitrate.android.viewmodel.RowsState
import dev.nitrate.android.viewmodel.RowsViewModel
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.pages.AccentMode
import dev.nitrate.core.pages.CardTitles
import dev.nitrate.core.pages.CustomPage
import dev.nitrate.core.pages.DisplayPrefs
import dev.nitrate.core.pages.PageKeys
import dev.nitrate.tv.ui.components.LocalDisplayPrefs
import dev.nitrate.tv.ui.components.TvTextField
import dev.nitrate.tv.ui.rows.PageEditor
import dev.nitrate.tv.ui.rows.RowsPage
import dev.nitrate.tv.ui.rows.newId
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

private val PagePadding = 58.dp

@Composable
fun rememberCustomPages(container: AppContainer): List<CustomPage> {
    val user = container.server.userId
    val flow = remember(user) { user?.let { container.displaySettings.customPages(it) } ?: flowOf(emptyList()) }
    return flow.collectAsState(emptyList()).value
}

/** Every custom page, and a way to make one. */
@Composable
fun PagesScreen(container: AppContainer, onOpen: (String, Boolean) -> Unit) {
    val pages = rememberCustomPages(container)
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().padding(PagePadding), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Your pages", style = MaterialTheme.typography.displaySmall)
        Text("Pages of your own rows. Pinned pages appear at the top of Home.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            item(key = "new") { PageCard("+  New page", Modifier.focusRequester(first)) { creating = true } }
            items(pages, key = { it.id }) { page -> PageCard(page.title + if (page.pinned) "\n\nOn Home" else "") { onOpen(page.id, false) } }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    if (creating) NameDialog("New page", "", onDismiss = { creating = false }) { name ->
        creating = false
        val user = container.server.userId ?: return@NameDialog
        val page = CustomPage(newId(), name)
        scope.launch {
            container.displaySettings.saveCustomPages(user, pages + page)
            onOpen(page.id, true)
        }
    }
}

@Composable
private fun PageCard(title: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier.width(280.dp).height(160.dp)) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)).padding(20.dp), Alignment.BottomStart) {
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(480.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            TvTextField(name, { name = it }, placeholder = "e.g. Friday night", modifier = Modifier.focusRequester(first))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { if (name.isNotBlank()) onSave(name.trim()) }) { Text("Save") }
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

/** A custom page: its rows, plus edit, rename, pin and delete. */
@Composable
fun CustomPageScreen(container: AppContainer, pageId: String, editNow: Boolean, onOpen: (MediaItem) -> Unit, onPlay: (MediaItem) -> Unit, onDeleted: () -> Unit) {
    val pages = rememberCustomPages(container)
    val page = pages.firstOrNull { it.id == pageId }
    val vm = viewModel(key = "custom-$pageId") { RowsViewModel(PageKeys.custom(pageId), { emptyList() }, container.displaySettings, container.server, container.serverChanges, cache = container.dataCache) }
    val state by vm.state.collectAsStateWithLifecycle()
    val overrides by vm.overrides.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(editNow) }
    var renaming by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val user = container.server.userId
    fun save(list: List<CustomPage>) {
        user ?: return
        scope.launch { container.displaySettings.saveCustomPages(user, list) }
    }

    if (editing) PageEditor(page?.title.orEmpty(), vm, container.server, libraryId = null, onDone = { editing = false })
    val header: @Composable () -> Unit = {
        Row(Modifier.padding(start = PagePadding, top = 24.dp, end = PagePadding), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(page?.title.orEmpty(), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(end = 16.dp))
            Button(onClick = { editing = true }) { Text("Edit rows") }
            OutlinedButton(onClick = { renaming = true }) { Text("Rename") }
            OutlinedButton(onClick = { save(pages.map { if (it.id == pageId) it.copy(pinned = !it.pinned) else it }) }) {
                Text(if (page?.pinned == true) "Unpin from Home" else "Pin to Home")
            }
            OutlinedButton(onClick = {
                user?.let { scope.launch { container.displaySettings.deleteCustomPage(it, pageId) } }
                onDeleted()
            }) { Text("Delete page") }
        }
    }
    when (val s = state) {
        is RowsState.Loaded -> if (s.rows.isEmpty()) {
            Column(Modifier.fillMaxSize()) {
                header()
                Text("This page is empty. Choose Edit rows to add rows from filters, collections or playlists.",
                    Modifier.padding(PagePadding), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            RowsPage(s.rows, overrides, container.server, onOpen, onPlay, vm::save, header = header, onEditPage = { editing = true })
        }
        is RowsState.Error -> Column { header(); Text(s.message, Modifier.padding(PagePadding), color = MaterialTheme.colorScheme.error) }
        RowsState.Loading -> Column { header(); Text("Loading…", Modifier.padding(PagePadding)) }
    }
    if (renaming && page != null) NameDialog("Rename page", page.title, onDismiss = { renaming = false }) { name ->
        renaming = false
        save(pages.map { if (it.id == pageId) it.copy(title = name) else it })
    }
}

/** What's shown about items, the accent colour and black level; synced for the user. */
@Composable
fun DisplaySettingsScreen(container: AppContainer, onMenuStops: () -> Unit = {}) {
    val prefs = LocalDisplayPrefs.current
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    fun update(change: (DisplayPrefs) -> DisplayPrefs) {
        val user = container.server.userId ?: return
        scope.launch { container.displaySettings.savePrefs(user, change(prefs)) }
    }
    val toggles: List<Triple<String, (DisplayPrefs) -> Boolean, (DisplayPrefs, Boolean) -> DisplayPrefs>> = listOf(
        Triple("Community rating", { p -> p.communityRating }, { p, v -> p.copy(communityRating = v) }),
        Triple("Critic rating", { p -> p.criticRating }, { p, v -> p.copy(criticRating = v) }),
        Triple("Age rating", { p -> p.officialRating }, { p, v -> p.copy(officialRating = v) }),
        Triple("Year", { p -> p.year }, { p, v -> p.copy(year = v) }),
        Triple("Runtime and end time", { p -> p.runtime }, { p, v -> p.copy(runtime = v) }),
        Triple("Genres", { p -> p.genres }, { p, v -> p.copy(genres = v) }),
        Triple("Plot overviews", { p -> p.overview }, { p, v -> p.copy(overview = v) }),
        Triple("Taglines", { p -> p.tagline }, { p, v -> p.copy(tagline = v) }),
        Triple("Quality labels (4K, HDR, Dolby Vision, Atmos)", { p -> p.qualityBadges }, { p, v -> p.copy(qualityBadges = v) }),
        Triple("Progress bars", { p -> p.progressBars }, { p, v -> p.copy(progressBars = v) }),
        Triple("Watched ticks", { p -> p.watchedMarks }, { p, v -> p.copy(watchedMarks = v) }),
        Triple("Unwatched counts", { p -> p.unwatchedCounts }, { p, v -> p.copy(unwatchedCounts = v) }),
        Triple("Logos", { p -> p.logos }, { p, v -> p.copy(logos = v) }),
        Triple("Cast & crew", { p -> p.cast }, { p, v -> p.copy(cast = v) }),
        Triple("More like this", { p -> p.similar }, { p, v -> p.copy(similar = v) }),
        Triple("Hide spoilers (blur unwatched episodes, hide their descriptions)", { p -> p.hideSpoilers }, { p, v -> p.copy(hideSpoilers = v) }),
        Triple("True black (OLED)", { p -> p.trueBlack }, { p, v -> p.copy(trueBlack = v) }),
    )
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(PagePadding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Display", style = MaterialTheme.typography.displaySmall)
                Text("In this TV's display profile (Settings → Display profile). Spoiler hiding is the same on every device.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Accent colour", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AccentMode.entries.forEachIndexed { i, m ->
                        FilterChip(selected = prefs.accent == m, onClick = { update { it.copy(accent = m) } }, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                m.argb?.let { Box(Modifier.width(12.dp).height(12.dp).background(Color(it), RoundedCornerShape(6.dp))) }
                                Text(m.label, Modifier.padding(start = if (m.argb != null) 8.dp else 0.dp))
                            }
                        }
                    }
                }
            }
        }
        item {
            ListItem(
                selected = false,
                onClick = { update { it.copy(tvHome = dev.nitrate.core.pages.TvHomeLayout.entries[(it.tvHome.ordinal + 1) % dev.nitrate.core.pages.TvHomeLayout.entries.size]) } },
                headlineContent = { Text("Home") },
                supportingContent = { Text(if (prefs.tvHome == dev.nitrate.core.pages.TvHomeLayout.FULL_ART) "One title at a time across the screen: ◀ ▶ through a row, ▲ ▼ between rows" else "Rows of cards under the focused title") },
                trailingContent = { Text("${prefs.tvHome.label}  ›", color = MaterialTheme.colorScheme.primary) },
            )
        }
        item {
            ListItem(
                selected = false,
                onClick = {
                    update {
                        // "Fade in" is now a switch below, not a menu of its own.
                        val styles = dev.nitrate.core.pages.TvMenuStyle.entries - dev.nitrate.core.pages.TvMenuStyle.FADE
                        val now = if (it.tvMenu == dev.nitrate.core.pages.TvMenuStyle.FADE) dev.nitrate.core.pages.TvMenuStyle.TOP else it.tvMenu
                        it.copy(tvMenu = styles[(styles.indexOf(now) + 1) % styles.size], tvMenuAutoHide = it.tvMenuAutoHide || it.tvMenu == dev.nitrate.core.pages.TvMenuStyle.FADE)
                    }
                },
                headlineContent = { Text("Menu") },
                supportingContent = { Text((if (prefs.tvMenu == dev.nitrate.core.pages.TvMenuStyle.FADE) dev.nitrate.core.pages.TvMenuStyle.TOP else prefs.tvMenu).detail) },
                trailingContent = { Text("${(if (prefs.tvMenu == dev.nitrate.core.pages.TvMenuStyle.FADE) dev.nitrate.core.pages.TvMenuStyle.TOP else prefs.tvMenu).label}  ›", color = MaterialTheme.colorScheme.primary) },
            )
        }
        if (prefs.tvMenu != dev.nitrate.core.pages.TvMenuStyle.RADIAL) item {
            val on = prefs.tvMenuAutoHide || prefs.tvMenu == dev.nitrate.core.pages.TvMenuStyle.FADE
            ListItem(
                selected = false,
                onClick = {
                    update {
                        val style = if (it.tvMenu == dev.nitrate.core.pages.TvMenuStyle.FADE) dev.nitrate.core.pages.TvMenuStyle.TOP else it.tvMenu
                        it.copy(tvMenu = style, tvMenuAutoHide = !on)
                    }
                },
                headlineContent = { Text("Fade in") },
                supportingContent = { Text("Keep the menu out of sight until you move onto it or press Menu") },
                trailingContent = { Switch(checked = on, onCheckedChange = null) },
            )
        }
        item {
            ListItem(
                selected = false,
                onClick = onMenuStops,
                headlineContent = { Text("Menu stops") },
                supportingContent = { Text(if (prefs.tvMenu == dev.nitrate.core.pages.TvMenuStyle.RADIAL) "What's on the dial and where" else "What's in the menu and in what order") },
                trailingContent = { Text("›", color = MaterialTheme.colorScheme.primary) },
            )
        }
        item {
            ListItem(
                selected = false,
                onClick = { update { it.copy(cardTitles = CardTitles.entries[(it.cardTitles.ordinal + 1) % CardTitles.entries.size]) } },
                headlineContent = { Text("Titles under cards") },
                trailingContent = { Text("${prefs.cardTitles.label}  ›", color = MaterialTheme.colorScheme.primary) },
            )
        }
        items(toggles, key = { it.first }) { (label, get, set) ->
            ListItem(
                selected = false,
                onClick = { update { set(it, !get(it)) } },
                headlineContent = { Text(label) },
                trailingContent = { Switch(checked = get(prefs), onCheckedChange = null) },
            )
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}
