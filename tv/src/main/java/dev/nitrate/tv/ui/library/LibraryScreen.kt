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
    changes: kotlinx.coroutines.flow.Flow<Unit> = kotlinx.coroutines.flow.emptyFlow(),
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
            if (music) {
                FilterChip(selected = !artists, onClick = { artists = false }) { Text("Albums") }
                FilterChip(selected = artists, onClick = { artists = true }) { Text("Artists") }
            }
        }
    }

    when (tab) {
        LibraryTab.RECOMMENDED -> {
            val vm = viewModel(key = "library-rows-$libraryId") {
                RowsViewModel(PageKeys.library(lib.id), { repository.libraryRows(lib) }, displaySettings, repository, changes)
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
        LibraryTab.ALL -> if (music && artists) ArtistsGrid(repository, lib, tabs, onOpen) else BrowseLibrary(repository, displaySettings, lib, tabs, onOpen, onPlay)
    }
}
