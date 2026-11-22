package dev.nitrate.tv.ui.home

import dev.nitrate.tv.ui.rows.PageEditor
import dev.nitrate.core.pages.PageKeys
import dev.nitrate.tv.ui.components.tabKeys
import dev.nitrate.android.viewmodel.RowsState
import dev.nitrate.android.viewmodel.RowsViewModel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
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
    /** The user's custom pages: pinned ones show above the classic rows. */
    customPages: List<dev.nitrate.core.pages.CustomPage> = emptyList(),
    onOpenPage: (String) -> Unit = {},
    /** Server pushes (new items, watch changes) that reload the rows. */
    changes: kotlinx.coroutines.flow.Flow<Unit> = kotlinx.coroutines.flow.emptyFlow(),
    /** Rows as last loaded, shown at once while fresh ones load. */
    dataCache: dev.nitrate.android.storage.DataCache? = null,
) {
    val vm = viewModel(key = "home") { RowsViewModel(PageKeys.HOME, repository::homeRows, displaySettings, repository, changes, cache = dataCache, loadProgressSkipping = dev.nitrate.android.viewmodel.homeRowsLoader(repository, displaySettings)) }
    val state by vm.state.collectAsStateWithLifecycle()
    // Continue Watching and Next Up as one row or two: the page is loaded again when that changes.
    val combineRows = dev.nitrate.tv.ui.components.LocalDisplayPrefs.current.combineContinueNextUp
    var combineSeen by remember { mutableStateOf(combineRows) }
    LaunchedEffect(combineRows) { if (combineRows != combineSeen) { combineSeen = combineRows; vm.refresh() } }
    val overrides by vm.overrides.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    if (editing) PageEditor("Home", vm, repository, libraryId = null, onDone = { editing = false })
    val signOut: () -> Unit = onSignOut

    // Refresh "Continue Watching" when returning from the player or details.
    // Saved with the screen, so coming back (from the player) counts as coming back.
    var firstResume by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        if (!firstResume) vm.refresh()
        firstResume = false
        onPauseOrDispose { }
    }

    val content = remember { FocusRequester() }
    // Saved with the page: coming back (from a details page) leaves focus to the rows, which put it on the card left.
    var focusedOnce by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var homeHasFocus by remember { mutableStateOf(false) }
    // Home's own layout (it may differ from every page's: core PageLooks).
    val prefs = dev.nitrate.core.pages.PageLooks.forPage(dev.nitrate.tv.ui.components.LocalDisplayPrefs.current, PageKeys.HOME)
    val fullArt = prefs.tvArrangement.fullArt
    val pinnedPages = customPages.filter { it.pinned }
    // Pinned pages are Home's other tabs (as a library's tabs are): "Home" first, then each page.
    var pageTab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val openPage = pinnedPages.firstOrNull { it.id == pageTab }
    val tabFocus = remember { mutableMapOf<String, FocusRequester>() }
    fun tabFocus(key: String) = tabFocus.getOrPut(key) { FocusRequester() }
    var switched by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(switched) {
        val key = switched?.substringBefore('#') ?: return@LaunchedEffect
        // The new page and its tabs are composed a frame or two later: focus stays on the tab chosen.
        repeat(10) {
            kotlinx.coroutines.delay(32)
            if (runCatching { tabFocus(key).requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    // Back on a pinned page goes to Home's own tab first (as on a library's other tabs).
    androidx.activity.compose.BackHandler(enabled = openPage != null) {
        pageTab = null; switched = "home#" + System.nanoTime()
    }
    val tabs: (@Composable () -> Unit)? = if (pinnedPages.isEmpty()) null else {
        {
            Row(Modifier.padding(start = 58.dp, end = 58.dp, top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                dev.nitrate.tv.ui.components.PageTabs(
                    listOf(dev.nitrate.tv.ui.components.PageTab("home", "Home")) + pinnedPages.map { dev.nitrate.tv.ui.components.PageTab(it.id, it.title) },
                    selected = openPage?.id ?: "home",
                    onSelect = { id -> pageTab = id.takeIf { it != "home" }; switched = id + "#" + System.nanoTime() },
                    focusFor = ::tabFocus,
                )
            }
        }
    }
    MainMenu(dev.nitrate.core.pages.MenuStops.HOME, overlay = fullArt) { insets ->
    // Channel/Page keys step through Home and its pinned pages from anywhere on the page.
    val tabIds = listOf("home") + pinnedPages.map { it.id }
    Box(Modifier.fillMaxSize()
        .tabKeys(tabIds, openPage?.id ?: "home") { id -> pageTab = id.takeIf { it != "home" }; switched = id + "#" + System.nanoTime() }
        .onFocusChanged { homeHasFocus = it.hasFocus }.focusRequester(content).focusGroup()) {
    if (openPage != null) {
        // The page draws its tabs again as it goes from loading to its rows: keep focus on the tab
        // chosen through that (only just after choosing it, so later reloads never take focus).
        var chosenAt by remember(openPage.id) { mutableStateOf(System.currentTimeMillis()) }
        PinnedPage(openPage, repository, displaySettings, changes, dataCache, fullArt, insets, tabs!!, onOpen, onPlay,
            onRedrawn = { if (System.currentTimeMillis() - chosenAt < 4_000) switched = openPage.id + "#" + System.nanoTime() })
    } else when (val s = state) {
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
        is RowsState.Loaded -> {
            val page: @Composable (header: (@Composable () -> Unit)?) -> Unit = { header ->
                if (fullArt) FullArtPage(
                    rows = s.rows, overrides = overrides, repository = repository, onOpen = onOpen, onPlay = onPlay,
                    onSaveDisplay = vm::save, onEditPage = { editing = true }, insets = insets, header = header,
                ) else RowsPage(
                    rows = s.rows, overrides = overrides, repository = repository, onOpen = onOpen, onPlay = onPlay,
                    onSaveDisplay = vm::save, onEditPage = { editing = true }, header = header, startOnRows = true,
                )
            }
            // Home itself is always its rows, under the tabs (pinned pages) when it has them: the View
            // button (titles in a view) is only on the other tabs.
            androidx.compose.runtime.CompositionLocalProvider(dev.nitrate.tv.ui.pages.LocalPageRows provides vm) { page(tabs) }
        }
    }
    }
    }
    // Start on the rows, not the menu (the menu is a press to the left).
    LaunchedEffect(state is RowsState.Loaded) {
        if (state !is RowsState.Loaded) return@LaunchedEffect
        if (!focusedOnce) {
            kotlinx.coroutines.delay(50)
            focusedOnce = runCatching { content.requestFocus() }.getOrDefault(false)
        } else {
            // Back on Home: the rows put focus on the card left; with none to go back to, the first row.
            kotlinx.coroutines.delay(1_200)
            if (!homeHasFocus) runCatching { content.requestFocus() }
        }
    }
}

/** A pinned page as one of Home's tabs: its rows, laid out as Home is, the tabs above them. */
@Composable
private fun PinnedPage(
    page: dev.nitrate.core.pages.CustomPage,
    repository: MediaServer,
    displaySettings: DisplaySettingsStore,
    changes: kotlinx.coroutines.flow.Flow<Unit>,
    dataCache: dev.nitrate.android.storage.DataCache?,
    fullArt: Boolean,
    insets: MenuInsets,
    tabs: @Composable () -> Unit,
    onOpen: (MediaItem) -> Unit,
    onPlay: (MediaItem) -> Unit,
    onRedrawn: () -> Unit,
) {
    val vm = viewModel(key = "custom-${page.id}") { RowsViewModel(PageKeys.custom(page.id), { emptyList() }, displaySettings, repository, changes, cache = dataCache) }
    val state by vm.state.collectAsStateWithLifecycle()
    val overrides by vm.overrides.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    if (editing) PageEditor(page.title, vm, repository, libraryId = null, onDone = { editing = false })
    val shape = when (val s = state) { is RowsState.Loaded -> if (s.rows.isEmpty()) "empty" else "rows"; is RowsState.Error -> "error"; RowsState.Loading -> "loading" }
    LaunchedEffect(shape) { onRedrawn() }
    // The tabs where they are on the full page: top right with full art, else top left.
    val placedTabs: @Composable () -> Unit = {
        Box(Modifier.fillMaxWidth().padding(end = insets.end), contentAlignment = if (fullArt) Alignment.TopEnd else Alignment.TopStart) { tabs() }
    }
    when (val s = state) {
        is RowsState.Loaded -> when {
            s.rows.isEmpty() -> Column(Modifier.fillMaxSize().padding(top = insets.top)) {
                placedTabs()
                Text("This page is empty. Add rows to it from Your pages, or Settings → Display → Pages.",
                    Modifier.padding(horizontal = 58.dp, vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { editing = true }, modifier = Modifier.padding(horizontal = 58.dp)) { Text("Edit rows") }
            }
            else -> dev.nitrate.tv.ui.library.RowsOrTitles("rows/${PageKeys.custom(page.id)}", s.rows, repository, displaySettings, tabs, onOpen, onPlay) { header ->
                androidx.compose.runtime.CompositionLocalProvider(dev.nitrate.tv.ui.pages.LocalPageRows provides vm) {
                if (fullArt) FullArtPage(s.rows, overrides, repository, onOpen, onPlay, vm::save, onEditPage = { editing = true }, insets = insets, header = header)
                else RowsPage(s.rows, overrides, repository, onOpen, onPlay, vm::save, header = header, onEditPage = { editing = true })
                }
            }
        }
        is RowsState.Error -> Column(Modifier.padding(top = insets.top)) { placedTabs(); Text(s.message, Modifier.padding(58.dp), color = MaterialTheme.colorScheme.error) }
        RowsState.Loading -> Column(Modifier.padding(top = insets.top)) { placedTabs(); Text("Loading…", Modifier.padding(58.dp)) }
    }
}
