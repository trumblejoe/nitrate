package dev.nitrate.mobile.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.filled.Edit
import dev.nitrate.core.pages.PageKeys
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import dev.nitrate.android.viewmodel.LibraryGridViewModel
import dev.nitrate.android.viewmodel.RowsState
import dev.nitrate.android.viewmodel.RowsViewModel
import dev.nitrate.core.LibrarySort
import dev.nitrate.core.model.CollectionKind
import dev.nitrate.core.model.ImageKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.MediaKind
import dev.nitrate.core.model.formatRuntime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackScaffold(title: String, onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        content = content,
    )
}

@Composable
fun LibraryScreen(actions: AppActions, libraryId: String) {
    val library by produceState<Result<MediaItem>?>(null, libraryId) { value = runCatching { actions.server.getItem(libraryId) } }
    val lib = library?.getOrNull()
    BackScaffold(lib?.name ?: "", onBack = { actions.nav.popBackStack() }) { padding ->
        val modifier = Modifier.padding(padding)
        when {
            library == null -> Loading(modifier)
            lib == null -> ErrorMessage(library?.exceptionOrNull()?.message ?: "Couldn't load library", modifier = modifier)
            else -> {
                val hasRecommended = lib.collection == CollectionKind.MOVIES || lib.collection == CollectionKind.TV_SHOWS
                var tab by rememberSaveable { mutableIntStateOf(0) }
                Column(modifier.fillMaxSize()) {
                    if (hasRecommended) {
                        PrimaryTabRow(selectedTabIndex = tab) {
                            Tab(tab == 0, onClick = { tab = 0 }, text = { Text("Recommended") })
                            Tab(tab == 1, onClick = { tab = 1 }, text = { Text("All") })
                        }
                    }
                    if (hasRecommended && tab == 0) LibraryRows(actions, lib) else LibraryGrid(actions, lib)
                }
            }
        }
    }
}

@Composable
private fun LibraryRows(actions: AppActions, library: MediaItem) {
    val c = actions.container
    val vm = viewModel(key = "rows-${library.id}") { RowsViewModel(PageKeys.library(library.id), { c.server.libraryRows(library) }, c.displaySettings, c.server, c.serverChanges) }
    val state by vm.state.collectAsStateWithLifecycle()
    val overrides by vm.overrides.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    if (editing) PageEditor(library.name, vm, actions, libraryId = library.id, onDone = { editing = false })
    when (val s = state) {
        RowsState.Loading -> Loading()
        is RowsState.Error -> ErrorMessage(s.message, vm::refresh)
        is RowsState.Loaded -> LazyColumn(contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            items(s.rows, key = { it.key }) { row -> CinematicRow(row, overrides[row.key] ?: row.defaultDisplay, actions) }
            item(key = "edit") {
                Box(Modifier.fillMaxWidth().padding(bottom = 24.dp), Alignment.Center) {
                    PillButton("Edit this page", Icons.Default.Edit, primary = false, onClick = { editing = true })
                }
            }
        }
    }
}

@Composable
private fun LibraryGrid(actions: AppActions, library: MediaItem) {
    val playlists = library.collection == CollectionKind.PLAYLISTS
    val vm = viewModel(key = "grid-${library.id}") { LibraryGridViewModel(actions.server, library, LibrarySort.NAME, includeTags = playlists) }
    val state by vm.state.collectAsStateWithLifecycle()
    val grid = rememberLazyGridState()
    LaunchedEffect(grid) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last -> if (last >= state.items.size - 20) vm.loadMore() }
    }
    val shape = if (playlists) CardShape.SQUARE else CardShape.POSTER
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = if (playlists) 150.dp else 110.dp),
        state = grid,
        contentPadding = PaddingValues(ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(LibrarySort.entries) { sort -> FilterChip(selected = state.sort == sort, onClick = { vm.setSort(sort) }, label = { Text(sort.label) }) }
            }
        }
        items(state.items, key = { it.id }) { item ->
            MediaCard(
                item = item,
                imageUrl = actions.server.cardImage(item, shape),
                shape = shape,
                title = item.name,
                subtitle = if (playlists) item.user.unplayedCount?.let { if (it == 0) "All watched" else "$it unwatched" } else item.productionYear?.toString(),
                onClick = { actions.open(item) },
            )
        }
    }
}

@Composable
fun PlaylistScreen(actions: AppActions, playlistId: String) {
    val playlist by produceState<MediaItem?>(null, playlistId) { value = runCatching { actions.server.getItem(playlistId) }.getOrNull() }
    val items by produceState<List<MediaItem>?>(null, playlistId) { value = runCatching { actions.server.playlistItems(playlistId) }.getOrDefault(emptyList()) }
    BackScaffold(playlist?.name ?: "", onBack = { actions.nav.popBackStack() }) { padding ->
        val list = items
        if (list == null) {
            Loading(Modifier.padding(padding))
            return@BackScaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(Modifier.padding(ScreenPadding), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { actions.playQueue(list.map { it.id }) }, enabled = list.isNotEmpty()) {
                        Icon(Icons.Default.PlayArrow, null)
                        Text("Play all")
                    }
                    OutlinedButton(onClick = { actions.playQueue(list.shuffled().map { it.id }) }, enabled = list.isNotEmpty()) { Text("Shuffle") }
                }
            }
            itemsIndexed(list, key = { i, it -> "$i-${it.id}" }) { index, item ->
                val audio = item.mediaKind == MediaKind.AUDIO
                val thumb = if (audio) actions.server.imageUrl(item, ImageKind.PRIMARY, 120) else actions.server.cardImage(item, CardShape.WIDE, 160)
                ListItem(
                    modifier = Modifier.clickable { actions.playQueue(list.drop(index).map { it.id }) },
                    leadingContent = {
                        Box(Modifier.height(48.dp).aspectRatio(if (audio) 1f else 16f / 9f).clip(RoundedCornerShape(6.dp))) {
                            if (thumb != null) AsyncImage(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    },
                    headlineContent = { Text(item.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(item.episodeLabel ?: item.productionYear?.toString().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (item.user.played) Icon(Icons.Default.Check, "Watched", tint = MaterialTheme.colorScheme.primary)
                            item.runtimeMs?.let { Text(formatRuntime(it), style = MaterialTheme.typography.labelMedium) }
                        }
                    },
                )
            }
        }
    }
}
