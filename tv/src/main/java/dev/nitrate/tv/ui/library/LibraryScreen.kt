package dev.nitrate.tv.ui.library

import dev.nitrate.tv.ui.rows.PageEditor
import dev.nitrate.core.pages.PageKeys
import dev.nitrate.android.viewmodel.LibraryGridViewModel
import dev.nitrate.android.viewmodel.RowsState
import dev.nitrate.android.viewmodel.RowsViewModel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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

private enum class LibraryTab(val label: String) { RECOMMENDED("Recommended"), ALL("All") }

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
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text(library?.exceptionOrNull()?.message ?: "Loading…")
        }
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
    var tab by rememberSaveable { mutableStateOf(if (hasRecommended) LibraryTab.RECOMMENDED else LibraryTab.ALL) }
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

    val tabs: @Composable () -> Unit = {
        Row(
            Modifier.padding(start = PagePadding, end = PagePadding, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(lib.name.orEmpty(), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(end = 16.dp))
            if (hasRecommended) {
                for (t in LibraryTab.entries) {
                    FilterChip(selected = tab == t, onClick = { switchTo(t.name) { tab = t } }, modifier = Modifier.focusRequester(tabFocus(t.name))) { Text(t.label) }
                }
            }
            if (music) {
                FilterChip(selected = !artists, onClick = { switchTo("albums") { artists = false } }, modifier = Modifier.focusRequester(tabFocus("albums"))) { Text("Albums") }
                FilterChip(selected = artists, onClick = { switchTo("artists") { artists = true } }, modifier = Modifier.focusRequester(tabFocus("artists"))) { Text("Artists") }
            }
        }
    }

    when (tab) {
        LibraryTab.RECOMMENDED -> {
            val vm = viewModel(key = "library-rows-$libraryId") {
                RowsViewModel(PageKeys.library(lib.id), { repository.libraryRows(lib) }, displaySettings, repository, changes, cache = dataCache, loadProgress = { repository.libraryRowsProgress(lib) })
            }
            val state by vm.state.collectAsStateWithLifecycle()
            val overrides by vm.overrides.collectAsStateWithLifecycle()
            var editing by remember { mutableStateOf(false) }
            if (editing) PageEditor(lib.name, vm, repository, libraryId = lib.id, onDone = { editing = false })
            when (val s = state) {
                // As Home can be: one title at a time, the tabs top right.
                is RowsState.Loaded -> if (fullArt) dev.nitrate.tv.ui.home.FullArtPage(
                    rows = s.rows,
                    overrides = overrides,
                    repository = repository,
                    onOpen = onOpen,
                    onPlay = onPlay,
                    onSaveDisplay = vm::save,
                    onEditPage = { editing = true },
                    insets = insets,
                    header = tabs,
                ) else RowsPage(
                    onEditPage = { editing = true },
                    rows = s.rows,
                    overrides = overrides,
                    repository = repository,
                    onOpen = onOpen,
                    onPlay = onPlay,
                    onSaveDisplay = vm::save,
                    header = tabs,
                )
                is RowsState.Error -> Column { tabs(); Text(s.message, Modifier.padding(PagePadding), color = MaterialTheme.colorScheme.error) }
                RowsState.Loading -> Column { tabs(); Text("Loading…", Modifier.padding(PagePadding)) }
            }
        }
        LibraryTab.ALL -> Box(Modifier.padding(start = insets.start, top = insets.top, end = insets.end, bottom = insets.bottom)) { if (music && artists) ArtistsGrid(repository, lib, tabs, onOpen) else BrowseLibrary(repository, displaySettings, lib, tabs, onOpen, onPlay, admin = admin) }
    }
}
