package dev.nitrate.tv.ui.library

import dev.nitrate.tv.ui.rows.PageEditor
import dev.nitrate.core.pages.PageKeys
import dev.nitrate.tv.ui.components.tabKeys
import dev.nitrate.android.viewmodel.LibraryGridViewModel
import dev.nitrate.android.viewmodel.RowsState
import dev.nitrate.android.viewmodel.RowsViewModel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.nitrate.core.MediaServer
import dev.nitrate.core.LibrarySort
import dev.nitrate.android.storage.DisplaySettingsStore
import dev.nitrate.tv.ui.components.MediaCard
import dev.nitrate.tv.ui.rows.RowsPage
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.CollectionKind
import dev.nitrate.core.model.ImageKind

private val PagePadding = 58.dp


@Composable
fun LibraryScreen(
    repository: MediaServer,
    displaySettings: DisplaySettingsStore,
    libraryId: String,
    onOpen: (MediaItem) -> Unit,
    onPlay: (MediaItem) -> Unit,
    changes: kotlinx.coroutines.flow.Flow<Unit> = kotlinx.coroutines.flow.emptyFlow(),
    /** Rows as last loaded, shown at once while fresh ones load. */
    dataCache: dev.nitrate.android.storage.DataCache? = null,
    /** Admins can bulk-edit titles from the All tab. */
    admin: Boolean = false,
    /** Recommended as one title at a time across the screen (as Home can be), else rows. */
    fullArt: Boolean = false,
    /** Room the menu takes over the page (full art only; elsewhere it has its own space). */
    insets: dev.nitrate.tv.ui.home.MenuInsets = dev.nitrate.tv.ui.home.MenuInsets(),
) {
    val library by produceState<Result<MediaItem>?>(null, libraryId) {
        value = runCatching { repository.getItem(libraryId) }
    }
    val lib = library?.getOrNull()
    if (lib == null) {
        val failed = library?.exceptionOrNull()
        if (failed != null) Box(Modifier.fillMaxSize(), Alignment.Center) { Text(failed.message ?: "Couldn't open the library") }
        else dev.nitrate.tv.ui.components.RowsPlaceholder()
        return
    }

    if (lib.collection == CollectionKind.LIVE_TV) {
        dev.nitrate.tv.ui.livetv.LiveTvScreen(repository, header = {
            Text(lib.name, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = PagePadding, top = 24.dp, bottom = 8.dp))
        }, onPlay = onPlay)
        return
    }

    if (lib.collection == CollectionKind.PLAYLISTS) {
        PlaylistsLibrary(repository, displaySettings, lib, onOpen, onPlay)
        return
    }

    val hasRecommended = lib.collection == CollectionKind.MOVIES || lib.collection == CollectionKind.TV_SHOWS
    val music = lib.collection == CollectionKind.MUSIC
    // The tabs as arranged in Settings → Display → Pages (Recommended and All until then).
    val userId = repository.userId
    val saved by remember(userId, lib.id) {
        userId?.let { displaySettings.libraryTabs(it, lib.id) } ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())
    val shownTabs = dev.nitrate.core.pages.LibraryTabs.shown(dev.nitrate.core.pages.LibraryTabs.resolve(saved, hasRecommended))
    var tabId by rememberSaveable { mutableStateOf<String?>(null) }
    val tab = shownTabs.firstOrNull { it.id == tabId } ?: shownTabs.first()
    var artists by rememberSaveable { mutableStateOf(false) }
    // Switching tabs replaces the page, the tabs with it: focus goes to the tab just chosen
    // (else it fell through to the menu).
    val tabFocus = remember { mutableMapOf<String, androidx.compose.ui.focus.FocusRequester>() }
    fun tabFocus(key: String) = tabFocus.getOrPut(key) { androidx.compose.ui.focus.FocusRequester() }
    var switched by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(switched) {
        val key = switched?.substringBefore('#') ?: return@LaunchedEffect
        // The new page and its tabs are composed a frame or two later.
        repeat(10) {
            kotlinx.coroutines.delay(32)
            if (runCatching { tabFocus(key).requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    fun switchTo(key: String, change: () -> Unit) { change(); switched = key + "#" + System.nanoTime() }
    // Opening the library: focus starts on its current tab, on the page (not left in the menu). Coming
    // back to it (from a details page) leaves focus to the page, which puts it on the title left.
    var opened by rememberSaveable(lib.id) { mutableStateOf(false) }
    var pageHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(lib.id) {
        if (!opened) { opened = true; switched = tab.id + "#open" }
        else {
            // A page that can't say where it was (a grid view) still gets its tab rather than nothing.
            kotlinx.coroutines.delay(1_500)
            if (!pageHasFocus) switched = tab.id + "#back"
        }
    }
    // Back on a tab other than the first goes to the first (the library's own page); Back there leaves.
    val firstTab = shownTabs.first()
    androidx.activity.compose.BackHandler(enabled = tab.id != firstTab.id) {
        switchTo(firstTab.id) { tabId = firstTab.id }
    }

    val tabs: @Composable () -> Unit = {
        Row(
            Modifier.padding(start = PagePadding, end = PagePadding, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(lib.name.orEmpty(), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(end = 16.dp))
            if (shownTabs.size > 1) {
                dev.nitrate.tv.ui.components.PageTabs(
                    shownTabs.map { dev.nitrate.tv.ui.components.PageTab(it.id, it.label) }, selected = tab.id,
                    onSelect = { id -> switchTo(id) { tabId = id } }, focusFor = ::tabFocus,
                )
            }
            if (music && tab.kind == dev.nitrate.core.pages.LibraryTabKind.ALL) {
                dev.nitrate.tv.ui.components.PageTabs(
                    listOf(dev.nitrate.tv.ui.components.PageTab("albums", "Albums"), dev.nitrate.tv.ui.components.PageTab("artists", "Artists")),
                    selected = if (artists) "artists" else "albums",
                    onSelect = { id -> switchTo(id) { artists = id == "artists" } }, focusFor = ::tabFocus,
                )
            }
        }
    }
    val padded = Modifier.padding(start = insets.start, top = insets.top, end = insets.end, bottom = insets.bottom)
    val types = dev.nitrate.core.pages.LibraryTabs.typesFor(lib.collection)

    // Channel/Page keys step through the library's tabs from anywhere on the page.
    androidx.compose.foundation.layout.Box(Modifier
        .tabKeys(shownTabs.map { it.id }, tab.id) { id -> switchTo(id) { tabId = id } }
        .onFocusChanged { pageHasFocus = it.hasFocus }) {
        when (tab.kind) {
            dev.nitrate.core.pages.LibraryTabKind.RECOMMENDED -> TabRows(
                "library-rows-$libraryId", PageKeys.library(lib.id), { repository.libraryRows(lib) }, { skip -> repository.libraryRowsProgress(lib, skip) },
                lib, repository, displaySettings, changes, dataCache, fullArt, insets, tabs, onOpen, onPlay,
            )
            dev.nitrate.core.pages.LibraryTabKind.GENRES, dev.nitrate.core.pages.LibraryTabKind.STUDIOS, dev.nitrate.core.pages.LibraryTabKind.DECADES -> TabRows(
                "library-rows-$libraryId-${tab.id}", dev.nitrate.core.pages.LibraryTabs.pageKey(lib.id, tab),
                { emptyList() },
                // Hidden genre/studio/decade rows are never asked for.
                { skip ->
                    kotlinx.coroutines.flow.flow {
                        emit(dev.nitrate.core.rows.RowsProgress(dev.nitrate.core.pages.LibraryTabs.rows(
                            lib.id, tab.kind, types,
                            genres = { repository.genres(lib.id) },
                            studios = { repository.studios(lib.id) },
                            years = { repository.libraryFilters(lib.id, types).years },
                            load = repository::queryItems,
                            skip = skip,
                            more = repository::moreOfQuery,
                        ), complete = true))
                    }
                },
                lib, repository, displaySettings, changes, dataCache, fullArt, insets, tabs, onOpen, onPlay,
            )
            // The user's own page of rows (made in Settings → Display → Pages).
            dev.nitrate.core.pages.LibraryTabKind.OWN -> TabRows(
                "library-rows-$libraryId-${tab.id}", dev.nitrate.core.pages.LibraryTabs.pageKey(lib.id, tab), { emptyList() }, null,
                lib, repository, displaySettings, changes, dataCache, fullArt, insets, tabs, onOpen, onPlay,
                empty = "No rows here yet. Add some in Settings → Display → Pages → ${lib.name}.",
            )
            dev.nitrate.core.pages.LibraryTabKind.COLLECTIONS -> Box(padded) { CollectionsGrid(repository, displaySettings, lib, tabs, onOpen, onPlay) }
            dev.nitrate.core.pages.LibraryTabKind.UNWATCHED -> Box(padded) {
                BrowseLibrary(repository, displaySettings, lib, tabs, onOpen, onPlay, admin = admin, tabFocus = tabFocus(tab.id),
                    viewKey = "browse/${lib.id}/unwatched",
                    startWith = dev.nitrate.core.pages.BrowseOptions(watched = dev.nitrate.core.pages.WatchFilter.UNWATCHED, sort = dev.nitrate.core.pages.QuerySort.DATE_ADDED, descending = true))
            }
            dev.nitrate.core.pages.LibraryTabKind.ALL -> Box(padded) { if (music && artists) ArtistsGrid(repository, lib, tabs, onOpen) else BrowseLibrary(repository, displaySettings, lib, tabs, onOpen, onPlay, admin = admin, tabFocus = tabFocus(tab.id)) }
        }
    }
}

/**
 * A tab that's a page of rows (Recommended, Genres, Studios, Decades, the user's own): as Home is
 * laid out (full art with the tabs top right, or rows under them), each row's look its own.
 */
@Composable
private fun TabRows(
    vmKey: String,
    pageKey: String,
    loadBuiltIn: suspend () -> List<dev.nitrate.core.rows.MediaRow>,
    loadProgress: ((Set<String>) -> kotlinx.coroutines.flow.Flow<dev.nitrate.core.rows.RowsProgress>)?,
    lib: MediaItem,
    repository: MediaServer,
    displaySettings: DisplaySettingsStore,
    changes: kotlinx.coroutines.flow.Flow<Unit>,
    dataCache: dev.nitrate.android.storage.DataCache?,
    fullArt: Boolean,
    insets: dev.nitrate.tv.ui.home.MenuInsets,
    tabs: @Composable () -> Unit,
    onOpen: (MediaItem) -> Unit,
    onPlay: (MediaItem) -> Unit,
    empty: String = "Nothing here yet.",
) {
    val vm = viewModel(key = vmKey) {
        RowsViewModel(pageKey, loadBuiltIn, displaySettings, repository, changes, cache = dataCache, loadProgressSkipping = loadProgress)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val overrides by vm.overrides.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    if (editing) PageEditor(lib.name, vm, repository, libraryId = lib.id, onDone = { editing = false })
    when (val s = state) {
        is RowsState.Loaded -> when {
            s.rows.none { it.items.isNotEmpty() } -> Column { tabs(); Text(empty, Modifier.padding(PagePadding), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            // As its rows (laid out as Home can be), or its titles in one of the All tab's views.
            else -> {
                val page: @Composable (header: @Composable () -> Unit) -> Unit = { header ->
                    androidx.compose.runtime.CompositionLocalProvider(dev.nitrate.tv.ui.pages.LocalPageRows provides vm) {
                    if (fullArt) dev.nitrate.tv.ui.home.FullArtPage(
                        rows = s.rows, overrides = overrides, repository = repository, onOpen = onOpen, onPlay = onPlay,
                        onSaveDisplay = vm::save, onEditPage = { editing = true }, insets = insets, header = header,
                    ) else RowsPage(
                        onEditPage = { editing = true }, rows = s.rows, overrides = overrides, repository = repository,
                        onOpen = onOpen, onPlay = onPlay, onSaveDisplay = vm::save, header = header,
                    )
                    }
                }
                // The library's main page (Recommended) is always its rows; the other row tabs get the
                // View button (as their rows, or their titles in one of the All tab's views).
                if (pageKey == dev.nitrate.core.pages.PageKeys.library(lib.id)) page(tabs)
                else RowsOrTitles("rows/$pageKey", s.rows, repository, displaySettings, tabs, onOpen, onPlay, music = lib.collection == CollectionKind.MUSIC) { header -> page(header) }
            }
        }
        is RowsState.Error -> Column { tabs(); Text(s.message, Modifier.padding(PagePadding), color = MaterialTheme.colorScheme.error) }
        RowsState.Loading -> Column { tabs(); dev.nitrate.tv.ui.components.RowsPlaceholder(top = 40.dp) }
    }
}

/**
 * The server's collections (box sets), under the tabs, in any of the All tab's views (the grids with
 * their column choice, or a show view), saved for the tab.
 */
@Composable
private fun CollectionsGrid(
    repository: MediaServer,
    displaySettings: DisplaySettingsStore,
    lib: MediaItem,
    tabs: @Composable () -> Unit,
    onOpen: (MediaItem) -> Unit,
    onPlay: (MediaItem) -> Unit,
) {
    val collections by produceState<List<MediaItem>?>(null) { value = runCatching { repository.collections() }.getOrDefault(emptyList()) }
    val userId = repository.userId
    val key = "browse/${lib.id}/collections"
    val options by remember(userId, key) {
        userId?.let { displaySettings.view(it, key, dev.nitrate.core.pages.BrowseOptions()) } ?: kotlinx.coroutines.flow.flowOf(dev.nitrate.core.pages.BrowseOptions())
    }.collectAsStateWithLifecycle(dev.nitrate.core.pages.BrowseOptions())
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun save(change: (dev.nitrate.core.pages.BrowseOptions) -> dev.nitrate.core.pages.BrowseOptions) {
        val user = userId ?: return
        scope.launch { displaySettings.saveView(user, key, change(options)) }
    }
    var choosing by remember { mutableStateOf(false) }
    val viewButton = remember { androidx.compose.ui.focus.FocusRequester() }
    var changed by remember { mutableStateOf(0) }
    var buttonFocused by remember { mutableStateOf(false) }
    // Also after the saved choice comes back (it swaps the layout, and the button, again).
    LaunchedEffect(changed, options.view) {
        if (changed == 0) return@LaunchedEffect
        buttonFocused = false
        repeat(40) {
            kotlinx.coroutines.android.awaitFrame()
            if (buttonFocused) return@LaunchedEffect
            runCatching { viewButton.requestFocus() }
            kotlinx.coroutines.delay(30)
        }
    }
    val view = options.view
    val columns = options.columnsFor(view, music = false)
    val bar: @Composable () -> Unit = {
        androidx.tv.material3.OutlinedButton(onClick = { choosing = true }, modifier = Modifier.focusRequester(viewButton).onFocusChanged { buttonFocused = it.isFocused }) {
            Text("View: ${view.label}" + if (view.columnChoices.isEmpty()) "" else " · $columns")
        }
    }
    if (choosing) ViewDialog(view, columns,
        onPick = { v -> save { it.copy(view = v) }; choosing = false; changed++ },
        onColumns = { n -> save { it.copy(columns = it.columns + (it.view to n)) } },
        onDismiss = { choosing = false; changed++ })
    val list = collections
    if (view.tvOnly) {
        Column(Modifier.fillMaxSize().padding(start = PagePadding, end = PagePadding, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            tabs(); bar()
            when {
                list == null -> dev.nitrate.tv.ui.components.GridPlaceholder()
                list.isEmpty() -> Text("No collections on this server yet.")
                else -> {
                    val args = ShowArgs(list, repository, music = false, titleOf = { it.name }, onOpen = onOpen, onPlay = onPlay, onNeedMore = {}, total = list.size)
                    if (view == dev.nitrate.core.pages.BrowseView.BOUNCE) BounceBox(args, Modifier.weight(1f).fillMaxWidth())
                    else ShowView(view, args, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(start = PagePadding, end = PagePadding, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { tabs() }
        item(span = { GridItemSpan(maxLineSpan) }) { bar() }
        when {
            list == null -> item(span = { GridItemSpan(maxLineSpan) }) { dev.nitrate.tv.ui.components.GridPlaceholder() }
            list.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) { Text("No collections on this server yet.") }
            else -> items(list, key = { it.id }) { c ->
                val artwork = view == dev.nitrate.core.pages.BrowseView.ARTWORK
                if (view == dev.nitrate.core.pages.BrowseView.LIST) {
                    MediaCard(item = c, imageUrl = repository.backdropUrl(c, 640) ?: repository.imageUrl(c, ImageKind.PRIMARY, 360), width = null, aspectRatio = 16f / 9f, title = c.name, subtitle = c.overview?.take(80), onClick = { onOpen(c) })
                } else MediaCard(
                    item = c,
                    imageUrl = if (artwork) repository.imageUrl(c, ImageKind.THUMB, 360) ?: repository.backdropUrl(c, 640) else repository.imageUrl(c, ImageKind.PRIMARY, 360),
                    width = null, aspectRatio = if (artwork) 16f / 9f else 2f / 3f, title = c.name, onClick = { onOpen(c) },
                )
            }
        }
    }
}
