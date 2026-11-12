package dev.nitrate.tv.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.nitrate.android.storage.DisplaySettingsStore
import dev.nitrate.android.viewmodel.BrowseViewModel
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.ImageKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.pages.Browse
import dev.nitrate.core.pages.BrowseOptions
import dev.nitrate.core.pages.BrowseView
import dev.nitrate.core.pages.QuerySort
import dev.nitrate.core.pages.ResolutionFilter
import dev.nitrate.core.pages.WatchFilter
import dev.nitrate.core.pages.facts
import dev.nitrate.tv.ui.components.LocalDisplayPrefs
import dev.nitrate.tv.ui.components.MediaCard
import kotlinx.coroutines.launch

private val PagePadding = 58.dp
private val BROWSE_SORTS = listOf(QuerySort.NAME, QuerySort.DATE_ADDED, QuerySort.RELEASE_DATE, QuerySort.RATING, QuerySort.CRITIC_RATING, QuerySort.RUNTIME, QuerySort.DATE_PLAYED)

/**
 * A library's full list on TV: sort, filters, grid or list, and an A–Z row to jump through
 * when sorted by name. Options are saved per library.
 */
@Composable
fun BrowseLibrary(
    repository: MediaServer,
    displaySettings: DisplaySettingsStore,
    library: MediaItem,
    header: @Composable () -> Unit,
    onOpen: (MediaItem) -> Unit,
    onPlay: (MediaItem) -> Unit,
) {
    val vm = viewModel(key = "browse-${library.id}") { BrowseViewModel(repository, displaySettings, library) }
    val state by vm.state.collectAsStateWithLifecycle()
    val genres by vm.genres.collectAsStateWithLifecycle()
    val options = state.options
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<String?>(null) }
    // Item focus moves here after a letter jump.
    val jumpFocus = remember { FocusRequester() }
    var jumpedTo by remember { mutableStateOf(-1) }

    LaunchedEffect(grid) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last -> if (last >= state.items.size - 30) vm.loadMore() }
    }
    LaunchedEffect(jumpedTo) { if (jumpedTo >= 0) runCatching { jumpFocus.requestFocus() } }

    // Album covers are square.
    val music = library.collection == dev.nitrate.core.model.CollectionKind.MUSIC
    val columns = when (options.view) {
        BrowseView.GRID -> if (music) 6 else 7
        BrowseView.ARTWORK -> if (music) 6 else 4
        BrowseView.LIST -> 2
    }
    // The header, options bar and A–Z row are the grid's first items.
    val leading = if (options.alphabetical) 3 else 2
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = grid,
        contentPadding = PaddingValues(start = PagePadding, end = PagePadding, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) { header() }
        item(key = "bar", span = { GridItemSpan(maxLineSpan) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { dialog = "sort" }) { Text("Sort: ${options.sort.label}${if (options.descending) " ↓" else " ↑"}") }
                OutlinedButton(onClick = { dialog = "filters" }) { Text(if (options.filterCount > 0) "Filters · ${options.filterCount}" else "Filters") }
                OutlinedButton(onClick = { vm.update { it.copy(view = BrowseView.entries[(it.view.ordinal + 1) % BrowseView.entries.size]) } }) {
                    Text("View: ${options.view.label}")
                }
                if (options.filterCount > 0) OutlinedButton(onClick = { vm.update { it.clearFilters() } }) { Text("Clear filters") }
                if (state.total >= 0) Text("${state.total} items", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (options.alphabetical) {
            item(key = "letters", span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Browse.LETTERS.forEach { letter ->
                        Surface(
                            onClick = {
                                scope.launch {
                                    val index = vm.positionOf(letter) ?: return@launch
                                    grid.scrollToItem(index + leading)
                                    jumpedTo = index
                                }
                            },
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                            modifier = Modifier.size(width = 28.dp, height = 34.dp),
                        ) {
                            Box(Modifier.fillMaxSize(), Alignment.Center) { Text(letter, style = MaterialTheme.typography.labelLarge) }
                        }
                    }
                }
            }
        }
        when {
            state.error != null && state.items.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                Column { Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error); Button(onClick = vm::retry) { Text("Try again") } }
            }
            state.loading -> item(span = { GridItemSpan(maxLineSpan) }) { Text("Loading…") }
            state.items.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                Text(if (options.filterCount > 0) "Nothing matches these filters." else "Nothing here yet.", style = MaterialTheme.typography.titleMedium)
            }
            else -> items(state.items.size, key = { state.items[it].id }) { i ->
                val item = state.items[i]
                val focus = if (i == jumpedTo) Modifier.focusRequester(jumpFocus) else Modifier
                if (options.view != BrowseView.LIST) {
                    val artwork = options.view == BrowseView.ARTWORK && !music
                    MediaCard(
                        item = item,
                        imageUrl = if (artwork) repository.imageUrl(item, ImageKind.THUMB, 360) ?: repository.backdropUrl(item) else repository.imageUrl(item, ImageKind.PRIMARY, 360),
                        width = null,
                        aspectRatio = if (music) 1f else if (artwork) 16f / 9f else 2f / 3f,
                        title = item.name,
                        subtitle = if (music) item.albumArtist else item.productionYear?.takeIf { LocalDisplayPrefs.current.year }?.toString(),
                        onClick = { onOpen(item) },
                        onPlay = { if (item.isPlayable) onPlay(item) else onOpen(item) },
                        modifier = focus,
                    )
                } else {
                    ListCard(repository, item, focus) { onOpen(item) }
                }
            }
        }
    }

    when (dialog) {
        "sort" -> SortDialog(options, onPick = { change -> vm.update(change); dialog = null }, onDismiss = { dialog = null })
        "filters" -> FilterDialog(options, genres, codecs = dev.nitrate.core.pages.Codecs.offeredFor(library.collection), onChange = vm::update, onDismiss = { dialog = null })
    }
}

/** A wide row for the list view: poster, title, facts and overview (as display settings allow). */
@Composable
private fun ListCard(repository: MediaServer, item: MediaItem, modifier: Modifier, onClick: () -> Unit) {
    val prefs = LocalDisplayPrefs.current
    Surface(onClick = onClick, shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.width(90.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.08f))) {
                repository.imageUrl(item, ImageKind.PRIMARY, 270)?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                prefs.facts(item).takeIf { it.isNotEmpty() }?.let {
                    Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                if (prefs.overview) item.overview?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                if (prefs.watchedMarks && item.user.played) Text("Watched", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun SortDialog(options: BrowseOptions, onPick: ((BrowseOptions) -> BrowseOptions) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(420.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sort by", style = MaterialTheme.typography.headlineSmall)
            BROWSE_SORTS.forEach { sort ->
                FilterChip(
                    selected = sort == options.sort,
                    onClick = { onPick { it.withSort(sort) } },
                    modifier = if (sort == options.sort) Modifier.focusRequester(first) else Modifier,
                ) { Text(sort.label) }
            }
            Spacer(Modifier.size(4.dp))
            OutlinedButton(onClick = { onPick { it.copy(descending = !it.descending) } }) {
                Text(if (options.descending) "Reverse (lowest/oldest first)" else "Reverse (highest/newest first)")
            }
            LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterDialog(options: BrowseOptions, genres: List<String>, codecs: Boolean, onChange: ((BrowseOptions) -> BrowseOptions) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(860.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filters", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onDismiss, modifier = Modifier.focusRequester(first)) { Text("Done") }
            }
            Group("Watched") { WatchFilter.entries.forEach { w -> FilterChip(options.watched == w, { onChange { it.copy(watched = w) } }) { Text(w.label) } } }
            Group("Favourites") {
                FilterChip(!options.favoritesOnly, { onChange { it.copy(favoritesOnly = false) } }) { Text("Everything") }
                FilterChip(options.favoritesOnly, { onChange { it.copy(favoritesOnly = true) } }) { Text("Favourites only") }
            }
            Group("Quality") { ResolutionFilter.entries.forEach { r -> FilterChip(options.resolution == r, { onChange { it.copy(resolution = r) } }) { Text(r.label) } } }
            if (codecs) {
                // Read from each file's streams (shows by their episodes); the first use takes a moment.
                Group("Video codec (any of)") {
                    dev.nitrate.core.pages.Codecs.VIDEO.forEach { (id, label) -> FilterChip(id in options.videoCodecs, { onChange { it.toggleVideoCodec(id) } }) { Text(label) } }
                }
                Group("Audio codec (any of)") {
                    dev.nitrate.core.pages.Codecs.AUDIO.forEach { (id, label) -> FilterChip(id in options.audioCodecs, { onChange { it.toggleAudioCodec(id) } }) { Text(label) } }
                }
            }
            Group("Rating") {
                listOf<Float?>(null, 6f, 7f, 8f).forEach { r -> FilterChip(options.minRating == r, { onChange { it.copy(minRating = r) } }) { Text(r?.let { "★ ${it.toInt()}+" } ?: "Any") } }
            }
            Group("Decade") {
                FilterChip(options.decade == null, { onChange { it.copy(decade = null) } }) { Text("Any") }
                Browse.decades().forEach { d -> FilterChip(options.decade == d, { onChange { it.copy(decade = if (it.decade == d) null else d) } }) { Text("${d}s") } }
            }
            if (genres.isNotEmpty()) Group("Genres (any of)") {
                genres.forEach { g -> FilterChip(g in options.genres, { onChange { o -> o.copy(genres = if (g in o.genres) o.genres - g else o.genres + g) } }) { Text(g) } }
            }
            if (options.filterCount > 0) OutlinedButton(onClick = { onChange { it.clearFilters() } }) { Text("Clear all") }
            LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}


/** A music library's artists, a page at a time. */
@Composable
fun ArtistsGrid(repository: MediaServer, library: MediaItem, header: @Composable () -> Unit, onOpen: (MediaItem) -> Unit) {
    val vm = viewModel(key = "artists-${library.id}") { dev.nitrate.android.viewmodel.PagedViewModel { start, limit -> repository.albumArtists(library.id, start, limit) } }
    val state by vm.state.collectAsStateWithLifecycle()
    val grid = rememberLazyGridState()
    LaunchedEffect(grid) { snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { if (it >= state.items.size - 30) vm.loadMore() } }
    LazyVerticalGrid(
        columns = GridCells.Fixed(6),
        state = grid,
        contentPadding = PaddingValues(start = PagePadding, end = PagePadding, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) { header() }
        if (state.total < 0) item(span = { GridItemSpan(maxLineSpan) }) { Text(state.error ?: "Loading…") }
        items(state.items, key = { it.id }) { artist ->
            MediaCard(item = artist, imageUrl = repository.imageUrl(artist, ImageKind.PRIMARY, 360), width = null, aspectRatio = 1f, title = artist.name, onClick = { onOpen(artist) })
        }
    }
}
