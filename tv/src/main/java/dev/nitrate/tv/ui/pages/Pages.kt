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
import androidx.compose.foundation.lazy.itemsIndexed
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
        Text("Pages of your own rows. Pinned pages are tabs on Home, beside it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            item(key = "new") { PageCard("+  New page", Modifier.focusRequester(first)) { creating = true } }
            items(pages, key = { it.id }) { page -> PageCard(page.title + if (page.pinned) "\n\nA tab on Home" else "") { onOpen(page.id, false) } }
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
                Text(if (page?.pinned == true) "Remove from Home's tabs" else "Add as a tab on Home")
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

/** Settings pages over the display profile: Display (how things look), While watching, What's shown. */
enum class DisplayPage { LOOK, WATCHING, SHOWN }

private typealias PrefToggle = Triple<String, (DisplayPrefs) -> Boolean, (DisplayPrefs, Boolean) -> DisplayPrefs>

/** Display settings, in this TV's display profile and synced for the user; [page] picks which. */
@Composable
fun DisplaySettingsScreen(container: AppContainer, page: DisplayPage = DisplayPage.LOOK, onMenuStops: () -> Unit = {}, onShown: () -> Unit = {}, onPages: () -> Unit = {}, onEffects: () -> Unit = {}, onPrerolls: () -> Unit = {}) {
    val prefs = LocalDisplayPrefs.current
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    val user = container.server.userId
    val profileName by remember(user) { user?.let { container.displaySettings.activeProfileName(it) } ?: kotlinx.coroutines.flow.flowOf(null) }.collectAsState(null)
    fun update(change: (DisplayPrefs) -> DisplayPrefs) {
        val u = container.server.userId ?: return
        scope.launch { container.displaySettings.savePrefs(u, change(prefs)) }
    }
    var pickingTabStyle by remember { mutableStateOf(false) }
    if (pickingTabStyle) dev.nitrate.tv.ui.components.TabStylePicker(prefs.tvTabStyle, onPick = { style -> update { it.copy(tvTabStyle = style) }; pickingTabStyle = false }, onDismiss = { pickingTabStyle = false })
    val shown: List<PrefToggle> = listOf(
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
    )
    @Composable
    fun Toggle(label: String, detail: String?, on: Boolean, modifier: Modifier = Modifier, flip: () -> Unit) = ListItem(
        selected = false,
        onClick = flip,
        headlineContent = { Text(label) },
        supportingContent = detail?.let { { Text(it) } },
        trailingContent = { Switch(checked = on, onCheckedChange = null) },
        modifier = modifier,
    )
    @Composable
    fun Heading(title: String, scope: dev.nitrate.core.settings.SettingScope? = null) = Column(Modifier.padding(top = 20.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        scope?.let { dev.nitrate.tv.ui.settings.ScopeTag(it, profileName) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(PagePadding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (page) {
            DisplayPage.LOOK -> {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Display", style = MaterialTheme.typography.displaySmall)
                        dev.nitrate.tv.ui.settings.ScopeTag(dev.nitrate.core.settings.SettingScope.PROFILE, profileName)
                    }
                }
                item {
                    Toggle("Easy mode", "Bigger text, only the essential menu stops and player buttons, and a question before signing out", prefs.easyMode, Modifier.focusRequester(first)) {
                        update { it.copy(easyMode = !it.easyMode) }
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
            // Like Home, or each its own way.
            val layouts = listOf<dev.nitrate.core.pages.TvHomeLayout?>(null) + dev.nitrate.core.pages.TvHomeLayout.entries
            val now = prefs.tvLibraryLayout
            ListItem(
                selected = false,
                onClick = { update { it.copy(tvLibraryLayout = layouts[(layouts.indexOf(it.tvLibraryLayout) + 1) % layouts.size]) } },
                headlineContent = { Text("Library pages") },
                supportingContent = { Text("How a library's Recommended rows are shown") },
                trailingContent = { Text("${now?.label ?: "Same as Home"}  ›", color = MaterialTheme.colorScheme.primary) },
            )
        }
        item {
            ListItem(
                selected = false,
                onClick = { pickingTabStyle = true },
                headlineContent = { Text("Tabs") },
                supportingContent = { Text("How Home's and each library's tabs look: ${prefs.tvTabStyle.detail.lowercase()}") },
                trailingContent = { Text("${prefs.tvTabStyle.label}  ›", color = MaterialTheme.colorScheme.primary) },
            )
        }
        item {
            val places = dev.nitrate.core.pages.LetterBarPlace.entries
            ListItem(
                selected = false,
                onClick = { update { it.copy(tvLetterBar = places[(it.tvLetterBar.ordinal + 1) % places.size]) } },
                headlineContent = { Text("A–Z bar") },
                supportingContent = { Text("Where the letters to jump through a library's All view go (Automatic: the side away from the menu)") },
                trailingContent = { Text("${prefs.tvLetterBar.label}  ›", color = MaterialTheme.colorScheme.primary) },
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
                onClick = { update { it.copy(tvMenuBlack = !it.tvMenuBlack) } },
                headlineContent = { Text("Black menu") },
                supportingContent = { Text("The menu on solid black, so it stands out over page themes and artwork") },
                trailingContent = { Switch(checked = prefs.tvMenuBlack, onCheckedChange = null) },
            )
        }
        item {
            ListItem(
                selected = false,
                onClick = onPages,
                headlineContent = { Text("Pages") },
                supportingContent = { Text("Home's rows, and each library's tabs (Genres, Collections, Unwatched, your own…)") },
                trailingContent = { Text("›", color = MaterialTheme.colorScheme.primary) },
            )
        }
        item {
            ListItem(
                selected = false,
                onClick = onEffects,
                headlineContent = { Text("Effects") },
                supportingContent = { Text(if (prefs.effects.on) "Page themes, card effects and the calendar's occasions" else "Off") },
                trailingContent = { Text("›", color = MaterialTheme.colorScheme.primary) },
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
                item(key = "accent") {
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Accent colour", style = MaterialTheme.typography.titleMedium, )
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
                item { Toggle("True black", "Pure black backgrounds, for OLED screens", prefs.trueBlack) { update { it.copy(trueBlack = !it.trueBlack) } } }
                item(key = "ambient") {
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Screensaver: artwork from your library, the time and what's next, when nothing's pressed", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            dev.nitrate.core.ambient.Ambient.CHOICES.forEach { m ->
                                FilterChip(selected = prefs.tvAmbientMinutes == m, onClick = { update { it.copy(tvAmbientMinutes = m) } }) { Text(if (m == 0) "Off" else "$m min") }
                            }
                        }
                    }
                }
                item(key = "shown") {
                    ListItem(
                        selected = false,
                        onClick = onShown,
                        headlineContent = { Text("What's shown") },
                        supportingContent = { Text("Ratings, year, genres, descriptions, logos, progress and the rest on cards and pages") },
                        trailingContent = { Text("›", color = MaterialTheme.colorScheme.primary) },
                    )
                }
                item { Heading("Your server account", dev.nitrate.core.settings.SettingScope.EVERYWHERE) }
                item { MissingEpisodesToggle(container) }
            }
            DisplayPage.SHOWN -> {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("What's shown", style = MaterialTheme.typography.displaySmall)
                        Text("About titles on cards, Home and details pages.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        dev.nitrate.tv.ui.settings.ScopeTag(dev.nitrate.core.settings.SettingScope.PROFILE, profileName)
                    }
                }
                itemsIndexed(shown, key = { _, t -> t.first }) { i, (label, get, set) ->
                    Toggle(label, null, get(prefs), if (i == 0) Modifier.focusRequester(first) else Modifier) { update { set(it, !get(it)) } }
                }
            }
            DisplayPage.WATCHING -> {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("While watching", style = MaterialTheme.typography.displaySmall)
                        Text("What Nitrate does around what you play.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item { Heading("Your display profile", dev.nitrate.core.settings.SettingScope.PROFILE) }
                item {
                    ListItem(
                        selected = false, onClick = onPrerolls,
                        headlineContent = { Text("Before it plays") },
                        supportingContent = { Text(if (prefs.prerolls.on) "A verse on black, a scene, the server's intros or your own videos before titles" else "Off · verses, scenes and intros before titles") },
                        trailingContent = { Text("›", color = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.focusRequester(first),
                    )
                }
                item {
                    Toggle("Skip to scenes in the credits", "Jumps past the credits to a scene during or after them when it's known; otherwise only offers it", prefs.creditScenes) {
                        update { it.copy(creditScenes = !it.creditScenes) }
                    }
                }
                item(key = "recaps") {
                    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("\"Last time\" recap for shows, after a break of at least", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            dev.nitrate.core.recap.RECAP_CHOICES.forEach { (days, label) ->
                                FilterChip(selected = prefs.recapAfterDays == days, onClick = { update { it.copy(recapAfterDays = days) } }) { Text(label) }
                            }
                        }
                        Text("For playlists in story order, after a break of at least", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            dev.nitrate.core.recap.RECAP_CHOICES.forEach { (days, label) ->
                                FilterChip(selected = prefs.playlistRecapAfterDays == days, onClick = { update { it.copy(playlistRecapAfterDays = days) } }) { Text(label) }
                            }
                        }
                    }
                }
                item { Toggle("Audio description", "Start with the described track when a title has one in its language", prefs.describedAudio) { update { it.copy(describedAudio = !it.describedAudio) } } }
                item { Toggle("Theme music", "Plays a title's theme quietly on its details page", prefs.themeMusic ?: true) { update { it.copy(themeMusic = !(it.themeMusic ?: true)) } } }
                item { Heading("All your devices", dev.nitrate.core.settings.SettingScope.EVERYWHERE) }
                item { Toggle("Hide spoilers", "Blur stills and hold back descriptions of episodes and films you haven't seen", prefs.hideSpoilers) { update { it.copy(hideSpoilers = !it.hideSpoilers) } } }
                item { Heading("This TV", dev.nitrate.core.settings.SettingScope.DEVICE) }
                item { StillWatchingToggle(container) }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

/** "Still watching?" is a playback setting: this TV only. */
@Composable
private fun StillWatchingToggle(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val settings by container.playbackSettings.settings.collectAsState(dev.nitrate.core.playback.PlaybackSettings())
    ListItem(
        selected = false,
        onClick = { scope.launch { container.playbackSettings.update { it.copy(askStillWatching = !it.askStillWatching) } } },
        headlineContent = { Text("Ask \"Still watching?\"") },
        supportingContent = { Text("After two episodes play on by themselves with nothing pressed, stop and ask. No answer: go back to where you last pressed something") },
        trailingContent = { Switch(checked = settings.askStillWatching, onCheckedChange = null) },
    )
}

/** Season lists include episodes you don't have (marked Missing, or with their air date): your server account's setting. */
@Composable
private fun MissingEpisodesToggle(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { on = runCatching { container.server.showsMissingEpisodes() }.getOrNull() }
    ListItem(
        selected = false,
        onClick = {
            val next = !(on ?: return@ListItem)
            on = next
            scope.launch { if (runCatching { container.server.setShowMissingEpisodes(next) }.isFailure) on = !next }
        },
        headlineContent = { Text("Show missing episodes") },
        supportingContent = { Text("Seasons list episodes you don't have, marked Missing or with their air date. Saved to your server account") },
        trailingContent = { Switch(checked = on == true, onCheckedChange = null) },
    )
}
