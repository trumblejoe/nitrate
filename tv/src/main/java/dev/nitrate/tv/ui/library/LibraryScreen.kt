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

    if (lib.collection == CollectionKind.PLAYLISTS) {
        PlaylistsLibrary(repository, displaySettings, lib, onOpen, onPlay)
        return
    }

    val hasRecommended = lib.collection == CollectionKind.MOVIES || lib.collection == CollectionKind.TV_SHOWS
    var tab by rememberSaveable { mutableStateOf(if (hasRecommended) LibraryTab.RECOMMENDED else LibraryTab.ALL) }

    val tabs: @Composable () -> Unit = {
        Row(
            Modifier.padding(start = PagePadding, end = PagePadding, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(lib.name.orEmpty(), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(end = 16.dp))
            if (hasRecommended) {
                for (t in LibraryTab.entries) {
                    FilterChip(selected = tab == t, onClick = { tab = t }) { Text(t.label) }
                }
            }
        }
    }

    when (tab) {
        LibraryTab.RECOMMENDED -> {
            val vm = viewModel(key = "library-rows-$libraryId") {
                RowsViewModel(PageKeys.library(lib.id), { repository.libraryRows(lib) }, displaySettings, repository)
            }
            val state by vm.state.collectAsStateWithLifecycle()
            val overrides by vm.overrides.collectAsStateWithLifecycle()
            var editing by remember { mutableStateOf(false) }
            if (editing) PageEditor(lib.name, vm, repository, libraryId = lib.id, onDone = { editing = false })
            when (val s = state) {
                is RowsState.Loaded -> RowsPage(
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
        LibraryTab.ALL -> LibraryGrid(repository, lib, tabs, onOpen, onPlay)
    }
}

@Composable
private fun LibraryGrid(
    repository: MediaServer,
    library: MediaItem,
    header: @Composable () -> Unit,
    onOpen: (MediaItem) -> Unit,
    onPlay: (MediaItem) -> Unit,
) {
    val vm = viewModel(key = "library-grid-${library.id}") { LibraryGridViewModel(repository, library, LibrarySort.NAME) }
    val state by vm.state.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()

    // Load the next page when the user gets near the end.
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= state.items.size - 30) vm.loadMore() }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(7),
        state = gridState,
        contentPadding = PaddingValues(start = PagePadding, end = PagePadding, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
            Column {
                header()
                Row(Modifier.padding(top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Sort", style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.CenterVertically))
                    for (sort in LibrarySort.entries) {
                        FilterChip(selected = state.sort == sort, onClick = { vm.setSort(sort) }) { Text(sort.label) }
                    }
                    if (state.total >= 0) {
                        Text("${state.total} items", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically).padding(start = 12.dp))
                    }
                }
            }
        }
        items(state.items, key = { it.id }) { item ->
            MediaCard(
                item = item,
                imageUrl = repository.imageUrl(item, ImageKind.PRIMARY, 360),
                width = null,
                aspectRatio = 2f / 3f,
                title = item.name,
                subtitle = item.productionYear?.toString(),
                onClick = { onOpen(item) },
                onPlay = { if (item.isPlayable) onPlay(item) else onOpen(item) },
            )
        }
    }
}
