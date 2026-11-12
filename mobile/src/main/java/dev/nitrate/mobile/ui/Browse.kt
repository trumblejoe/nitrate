package dev.nitrate.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import dev.nitrate.android.viewmodel.BrowseViewModel
import dev.nitrate.core.model.ImageKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.pages.Browse
import dev.nitrate.core.pages.BrowseOptions
import dev.nitrate.core.pages.BrowseView
import dev.nitrate.core.pages.QuerySort
import dev.nitrate.core.pages.ResolutionFilter
import dev.nitrate.core.pages.WatchFilter
import dev.nitrate.core.pages.facts
import kotlinx.coroutines.launch

/** Sorts offered when browsing a library (Random and Last watched suit rows better). */
private val BROWSE_SORTS = listOf(QuerySort.NAME, QuerySort.DATE_ADDED, QuerySort.RELEASE_DATE, QuerySort.RATING, QuerySort.CRITIC_RATING, QuerySort.RUNTIME, QuerySort.DATE_PLAYED)

/**
 * A library's full list: sort, filters (watched, favourites, genres, decade, rating, 4K/HD),
 * grid or list, and an A–Z strip to jump through when sorted by name.
 */
@Composable
fun BrowseLibrary(actions: AppActions, library: MediaItem) {
    val vm = viewModel(key = "browse-${library.id}") { BrowseViewModel(actions.server, actions.container.displaySettings, library) }
    val state by vm.state.collectAsStateWithLifecycle()
    val genres by vm.genres.collectAsStateWithLifecycle()
    val options = state.options
    val scope = rememberCoroutineScope()
    val grid = rememberLazyGridState()
    val list = rememberLazyListState()
    // New sort, filters or view: a new list, from its top.
    val update: ((dev.nitrate.core.pages.BrowseOptions) -> dev.nitrate.core.pages.BrowseOptions) -> Unit = { change ->
        vm.update(change)
        scope.launch { runCatching { grid.scrollToItem(0) }; runCatching { list.scrollToItem(0) } }
    }
    var filtering by remember { mutableStateOf(false) }
    // Album covers are square.
    val square = library.collection == dev.nitrate.core.model.CollectionKind.MUSIC
    val shape = if (square) CardShape.SQUARE else CardShape.POSTER
    // Artwork view: wide pictures (backdrops and thumbs) instead of posters.
    val artwork = options.view == BrowseView.ARTWORK && !square
    val cardShape = if (artwork) CardShape.WIDE else shape

    // Load the next page near the end.
    LaunchedEffect(grid, list, options.view) {
        snapshotFlow {
            if (options.view != BrowseView.LIST) grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            else list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        }.collect { last -> if (last >= state.items.size - 20) vm.loadMore() }
    }
    fun jump(letter: String) {
        scope.launch {
            val index = vm.positionOf(letter) ?: return@launch
            if (options.view != BrowseView.LIST) grid.scrollToItem(index) else list.scrollToItem(index)
        }
    }

    Column(Modifier.fillMaxSize()) {
        BrowseBar(options, state.total, onChange = update, onFilters = { filtering = true })
        Box(Modifier.fillMaxSize()) {
            val padding = PaddingValues(start = ScreenPadding, end = if (options.alphabetical) 34.dp else ScreenPadding, top = 4.dp, bottom = 24.dp)
            when {
                state.error != null && state.items.isEmpty() -> ErrorMessage(state.error ?: "", vm::retry)
                state.loading -> Loading()
                state.items.isEmpty() -> EmptyResults(options) { update { it.clearFilters() } }
                options.view != BrowseView.LIST -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = if (artwork) 240.dp else if (square) 140.dp else 110.dp),
                    state = grid,
                    contentPadding = padding,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.items, key = { it.id }) { item ->
                        MediaCard(
                            item = item,
                            imageUrl = actions.server.cardImage(item, cardShape),
                            shape = cardShape,
                            title = item.name,
                            subtitle = if (square) item.albumArtist else item.productionYear?.takeIf { LocalDisplayPrefs.current.year }?.toString(),
                            onClick = { actions.open(item) },
                        )
                    }
                    if (state.error != null) item(span = { GridItemSpan(maxLineSpan) }) { LoadMoreFailed(state.error.orEmpty(), vm::retry) }
                }
                else -> LazyColumn(state = list, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
                    items(state.items, key = { it.id }) { item -> ListRow(actions, item) }
                    if (state.error != null) item { LoadMoreFailed(state.error.orEmpty(), vm::retry) }
                }
            }
            if (options.alphabetical && state.items.isNotEmpty()) {
                AlphabetStrip(Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 8.dp), ::jump)
            }
        }
    }
    if (filtering) FilterSheet(options, genres, codecs = dev.nitrate.core.pages.Codecs.offeredFor(library.collection), onChange = update, onDismiss = { filtering = false })
}

@Composable
private fun BrowseBar(options: BrowseOptions, total: Int, onChange: ((BrowseOptions) -> BrowseOptions) -> Unit, onFilters: () -> Unit) {
    var sortMenu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            FilterChip(
                selected = false,
                onClick = { sortMenu = true },
                label = { Text(options.sort.label) },
                trailingIcon = { Icon(if (options.descending) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp, null) },
            )
            DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                BROWSE_SORTS.forEach { sort ->
                    DropdownMenuItem(
                        text = { Text(sort.label) },
                        trailingIcon = { if (sort == options.sort) Icon(Icons.Default.Check, null) },
                        onClick = { onChange { it.withSort(sort) }; sortMenu = false },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(if (options.descending) "Reverse: lowest/oldest first" else "Reverse: highest/newest first") },
                    onClick = { onChange { it.copy(descending = !it.descending) }; sortMenu = false },
                )
            }
        }
        FilterChip(
            selected = options.filterCount > 0,
            onClick = onFilters,
            label = { Text(if (options.filterCount > 0) "Filters · ${options.filterCount}" else "Filters") },
        )
        Spacer(Modifier.weight(1f))
        if (total >= 0) Text("$total", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        var viewMenu by remember { mutableStateOf(false) }
        Box {
            FilterChip(
                selected = false,
                onClick = { viewMenu = true },
                label = { Text(options.view.label) },
                leadingIcon = { if (options.view == BrowseView.LIST) Icon(Icons.AutoMirrored.Filled.List, null) },
            )
            DropdownMenu(viewMenu, onDismissRequest = { viewMenu = false }) {
                BrowseView.entries.forEach { v ->
                    DropdownMenuItem(
                        text = { Text(v.label) },
                        trailingIcon = { if (v == options.view) Icon(Icons.Default.Check, null) },
                        onClick = { onChange { it.copy(view = v) }; viewMenu = false },
                    )
                }
            }
        }
    }
}

/** One row of the list view: poster, title, facts and overview, as the display settings allow. */
@Composable
private fun ListRow(actions: AppActions, item: MediaItem) {
    val prefs = LocalDisplayPrefs.current
    Row(Modifier.fillMaxWidth().pressable({ actions.open(item) }), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.width(72.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.08f))) {
            actions.server.imageUrl(item, ImageKind.PRIMARY, 240)?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            prefs.facts(item).takeIf { it.isNotEmpty() }?.let {
                Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (prefs.overview) item.overview?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (prefs.watchedMarks && item.user.played) Icon(Icons.Default.Check, "Watched", tint = accentColor())
    }
}

/** # and A–Z down the edge: tap or drag to jump. */
@Composable
private fun AlphabetStrip(modifier: Modifier, onLetter: (String) -> Unit) {
    var height by remember { mutableStateOf(1) }
    var last by remember { mutableStateOf<String?>(null) }
    fun at(y: Float) {
        val i = (y / height * Browse.LETTERS.size).toInt().coerceIn(0, Browse.LETTERS.lastIndex)
        val letter = Browse.LETTERS[i]
        if (letter != last) {
            last = letter
            onLetter(letter)
        }
    }
    Column(
        modifier.width(30.dp)
            .onSizeChanged { height = it.height.coerceAtLeast(1) }
            .pointerInput(Unit) { detectVerticalDragGestures(onDragEnd = { last = null }) { change, _ -> at(change.position.y) } }
            .pointerInput(Unit) { detectTapGestures { at(it.y); last = null } },
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Browse.LETTERS.forEach { Text(it, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = accentColor()) }
    }
}

@Composable
private fun EmptyResults(options: BrowseOptions, onClear: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(if (options.filterCount > 0) "Nothing matches these filters" else "Nothing here yet", style = MaterialTheme.typography.titleMedium)
        if (options.filterCount > 0) TextButton(onClick = onClear) { Text("Clear filters") }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(options: BrowseOptions, genres: List<String>, codecs: Boolean, onChange: ((BrowseOptions) -> BrowseOptions) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filters", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (options.filterCount > 0) TextButton(onClick = { onChange { it.clearFilters() } }) { Text("Clear all") }
            }
            Group("Watched") {
                WatchFilter.entries.forEach { w -> FilterChip(options.watched == w, { onChange { it.copy(watched = w) } }, label = { Text(w.label) }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Favourites only", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(options.favoritesOnly, { on -> onChange { it.copy(favoritesOnly = on) } })
            }
            Group("Quality") {
                ResolutionFilter.entries.forEach { r -> FilterChip(options.resolution == r, { onChange { it.copy(resolution = r) } }, label = { Text(r.label) }) }
            }
            if (codecs) {
                // Read from each file's streams (shows by their episodes); the first use takes a moment.
                // Video choices combine per stream: codec, bit depth and range all on the same video.
                Group("Video codec (any of)") {
                    dev.nitrate.core.pages.Codecs.VIDEO.forEach { (id, label) -> FilterChip(id in options.videoCodecs, { onChange { it.toggleVideoCodec(id) } }, label = { Text(label) }) }
                }
                Group("Bit depth") {
                    dev.nitrate.core.pages.Codecs.BIT_DEPTHS.forEach { d -> FilterChip(d in options.bitDepths, { onChange { it.toggleBitDepth(d) } }, label = { Text("$d-bit") }) }
                }
                Group("Dynamic range") {
                    dev.nitrate.core.pages.Codecs.RANGES.forEach { (id, label) -> FilterChip(id in options.videoRanges, { onChange { it.toggleRange(id) } }, label = { Text(label) }) }
                }
                Group("Audio codec (any of)") {
                    dev.nitrate.core.pages.Codecs.AUDIO.forEach { (id, label) -> FilterChip(id in options.audioCodecs, { onChange { it.toggleAudioCodec(id) } }, label = { Text(label) }) }
                }
            }
            Group("Rating") {
                listOf<Float?>(null, 6f, 7f, 8f).forEach { r -> FilterChip(options.minRating == r, { onChange { it.copy(minRating = r) } }, label = { Text(r?.let { "★ ${it.toInt()}+" } ?: "Any") }) }
            }
            Group("Decade") {
                FilterChip(options.decade == null, { onChange { it.copy(decade = null) } }, label = { Text("Any") })
                Browse.decades().forEach { d -> FilterChip(options.decade == d, { onChange { it.copy(decade = if (it.decade == d) null else d) } }, label = { Text("${d}s") }) }
            }
            if (genres.isNotEmpty()) Group("Genres (any of)") {
                genres.forEach { g ->
                    FilterChip(g in options.genres, { onChange { o -> o.copy(genres = if (g in o.genres) o.genres - g else o.genres + g) } }, label = { Text(g) })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}


/** A music library's artists, a page at a time. */
@Composable
fun ArtistsGrid(actions: AppActions, library: MediaItem) {
    val vm = viewModel(key = "artists-${library.id}") { dev.nitrate.android.viewmodel.PagedViewModel { start, limit -> actions.server.albumArtists(library.id, start, limit) } }
    val state by vm.state.collectAsStateWithLifecycle()
    val grid = rememberLazyGridState()
    LaunchedEffect(grid) { snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { if (it >= state.items.size - 20) vm.loadMore() } }
    when {
        state.error != null && state.items.isEmpty() -> ErrorMessage(state.error ?: "", vm::loadMore)
        state.total < 0 -> Loading()
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 120.dp),
            state = grid,
            contentPadding = PaddingValues(ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(state.items, key = { it.id }) { artist ->
                MediaCard(item = artist, imageUrl = actions.server.cardImage(artist, CardShape.SQUARE), shape = CardShape.SQUARE, title = artist.name, onClick = { actions.open(artist) })
            }
        }
    }
}

/** A later page failed: said under the list, with a retry (it doesn't retry by itself). */
@Composable
private fun LoadMoreFailed(error: String, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Couldn't load more: $error", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onRetry) { Text("Try again") }
    }
}
