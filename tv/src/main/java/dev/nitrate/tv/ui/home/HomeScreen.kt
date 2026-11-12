package dev.nitrate.tv.ui.home

import dev.nitrate.tv.ui.rows.PageEditor
import dev.nitrate.core.pages.PageKeys
import dev.nitrate.android.viewmodel.RowsState
import dev.nitrate.android.viewmodel.RowsViewModel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.nitrate.core.MediaServer
import dev.nitrate.android.storage.DisplaySettingsStore
import dev.nitrate.tv.ui.rows.RowsPage
import kotlinx.coroutines.launch
import dev.nitrate.core.model.MediaItem

@Composable
fun HomeScreen(
    repository: MediaServer,
    displaySettings: DisplaySettingsStore,
    onOpen: (MediaItem) -> Unit,
    onPlay: (MediaItem) -> Unit,
    /** Opens the settings list (playback, display, plugins, account...). */
    onSettings: () -> Unit,
    onSignOut: () -> Unit,
    /** Switches to the offline home when the server can't be reached. */
    onOffline: () -> Unit = {},
    /** Where the menu's other stops go (Search, pages, Watch together, Switch user...). */
    menuActions: MenuActions = MenuActions(),
    /** The user's custom pages: pinned ones show above the rows, and any can be a menu stop. */
    customPages: List<dev.nitrate.core.pages.CustomPage> = emptyList(),
    /** Server pushes (new items, watch changes) that reload the rows. */
    changes: kotlinx.coroutines.flow.Flow<Unit> = kotlinx.coroutines.flow.emptyFlow(),
) {
    val vm = viewModel(key = "home") { RowsViewModel(PageKeys.HOME, repository::homeRows, displaySettings, repository, changes) }
    val state by vm.state.collectAsStateWithLifecycle()
    val overrides by vm.overrides.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    if (editing) PageEditor("Home", vm, repository, libraryId = null, onDone = { editing = false })
    val signOut: () -> Unit = onSignOut

    // Refresh "Continue Watching" when returning from the player or details.
    var firstResume by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        if (!firstResume) vm.refresh()
        firstResume = false
        onPauseOrDispose { }
    }

    val libraries by androidx.compose.runtime.produceState(emptyList<MediaItem>(), repository) { value = runCatching { repository.views() }.getOrDefault(emptyList()) }
    val content = remember { FocusRequester() }
    var focusedOnce by remember { mutableStateOf(false) }
    val prefs = dev.nitrate.tv.ui.components.LocalDisplayPrefs.current
    val fullArt = prefs.tvHome == dev.nitrate.core.pages.TvHomeLayout.FULL_ART
    val catalog = remember(libraries, customPages, menuActions, onSettings) {
        menuCatalog(libraries, customPages, MenuActions(
            onSearch = menuActions.onSearch, onHome = {}, onLibrary = onOpen, onPage = menuActions.onPage, onPages = menuActions.onPages,
            onTogether = menuActions.onTogether, onSettings = onSettings, onProfiles = menuActions.onProfiles,
        ))
    }
    val pinnedPages = customPages.filter { it.pinned }
    TvMenu(
        style = prefs.tvMenu,
        stops = catalog.stops(prefs),
        dial = catalog.dial(prefs),
        overlay = fullArt,
    ) { insets ->
    Box(Modifier.fillMaxSize().focusRequester(content).focusGroup()) {
    when (val s = state) {
        RowsState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Loading…") }
        is RowsState.Error -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(s.message, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = vm::refresh) { Text("Retry") }
                    OutlinedButton(onClick = onOffline) { Text("Work offline") }
                    OutlinedButton(onClick = signOut) { Text("Sign out") }
                }
            }
        }
        is RowsState.Loaded -> if (fullArt) FullArtPage(
            rows = s.rows,
            overrides = overrides,
            repository = repository,
            onOpen = onOpen,
            onPlay = onPlay,
            onSaveDisplay = vm::save,
            onEditPage = { editing = true },
            insets = insets,
        ) else RowsPage(
            rows = s.rows,
            overrides = overrides,
            repository = repository,
            onOpen = onOpen,
            onPlay = onPlay,
            onSaveDisplay = vm::save,
            onEditPage = { editing = true },
            header = if (pinnedPages.isEmpty()) null else {
                {
                    Row(Modifier.padding(start = 58.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (p in pinnedPages) OutlinedButton(onClick = { menuActions.onPage(p.id) }) { Text(p.title) }
                    }
                }
            },
        )
    }
    }
    }
    // Start on the rows, not the menu (the menu is a press to the left).
    LaunchedEffect(state is RowsState.Loaded) {
        if (state is RowsState.Loaded && !focusedOnce) {
            kotlinx.coroutines.delay(50)
            focusedOnce = runCatching { content.requestFocus() }.getOrDefault(false)
        }
    }
}
