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
import androidx.compose.foundation.layout.fillMaxHeight
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
    /** Admins can select titles and edit them together. */
    admin: Boolean = false,
    /** A tab of its own (Unwatched): its own saved options, starting from these. */
    viewKey: String? = null,
    startWith: dev.nitrate.core.pages.BrowseOptions = dev.nitrate.core.pages.BrowseOptions(),
) {
    val vm = viewModel(key = "browse-${library.id}-${viewKey.orEmpty()}") {
        if (viewKey == null) BrowseViewModel(repository, displaySettings, library) else BrowseViewModel(repository, displaySettings, library, viewKey, startWith)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val genres by vm.genres.collectAsStateWithLifecycle()
    val libraryFilters by vm.filters.collectAsStateWithLifecycle()
    val options = state.options
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    // New sort, filters or view: a new list, from its top.
    val update: ((dev.nitrate.core.pages.BrowseOptions) -> dev.nitrate.core.pages.BrowseOptions) -> Unit = { change ->
        vm.update(change)
        scope.launch { runCatching { grid.scrollToItem(0) } }
    }
    var dialog by remember { mutableStateOf<String?>(null) }
    val canBulkEdit = admin && library.collection != dev.nitrate.core.model.CollectionKind.MUSIC
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    fun tap(item: MediaItem) {
        if (selecting) selected = if (item.id in selected) selected - item.id else selected + item.id else onOpen(item)
    }
    // Item focus moves here after a letter jump.
    val jumpFocus = remember { FocusRequester() }
    var jumpedTo by remember { mutableStateOf(-1) }
    // Counts jumps, so jumping to the same letter again still moves focus.
    var jumps by remember { mutableStateOf(0) }

    LaunchedEffect(grid) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last -> if (last >= state.items.size - 30) vm.loadMore() }
    }
    LaunchedEffect(jumps) { if (jumpedTo >= 0) runCatching { jumpFocus.requestFocus() } }

    // Album covers are square.
    val music = library.collection == dev.nitrate.core.model.CollectionKind.MUSIC
    val columns = when (options.view) {
        BrowseView.GRID -> if (music) 6 else 7
        BrowseView.ARTWORK -> if (music) 6 else 4
        BrowseView.LIST -> 2
        else -> 7 // the show views lay themselves out
    }
    // The A–Z bar: above the titles, down one side (the user's choice, away from the menu by
    // default) or off. Above, it's one of the grid's first items with the header and options bar.
    val prefs = LocalDisplayPrefs.current
    val letterPlace = if (options.alphabetical) prefs.tvLetterBar.resolve(prefs.tvMenu) else null
    val leading = if (letterPlace == dev.nitrate.core.pages.LetterBarPlace.TOP) 3 else 2
    val jumpTo: (String) -> Unit = { letter ->
        scope.launch {
            val index = vm.positionOf(letter) ?: return@launch
            grid.scrollToItem(index + leading)
            jumpedTo = index
            jumps++
        }
    }
    // Sort, filters, view, the count and selecting: above the titles in every view.
    val bar: @Composable () -> Unit = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { dialog = "sort" }) { Text("Sort: ${options.sort.label}${if (options.descending) " ↓" else " ↑"}") }
                OutlinedButton(onClick = { dialog = "filters" }) { Text(if (options.filterCount > 0) "Filters · ${options.filterCount}" else "Filters") }
                OutlinedButton(onClick = { dialog = "view" }) {
                    Text("View: ${options.view.label}")
                }
                if (options.filterCount > 0) OutlinedButton(onClick = { update { it.clearFilters() } }) { Text("Clear filters") }
                if (state.total >= 0) Text("${state.total} items", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (canBulkEdit) {
                    if (!selecting) OutlinedButton(onClick = { selecting = true }) { Text("Select to edit") }
                    else {
                        Text("${selected.size} selected", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        OutlinedButton(onClick = { selected = selected + state.items.map { it.id } }) { Text("All shown") }
                        Button(onClick = { dialog = "bulk" }, enabled = selected.isNotEmpty()) { Text("Edit…") }
                        OutlinedButton(onClick = { selecting = false; selected = emptySet() }) { Text("Done selecting") }
                    }
                }
            }
    }
    if (options.view.tvOnly) {
        // A show view fills the page under the header and bar; no A–Z (nothing to scroll through).
        Column(Modifier.fillMaxSize().padding(start = PagePadding, end = PagePadding, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            header()
            bar()
            when {
                state.error != null && state.items.isEmpty() -> Column { Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error); Button(onClick = vm::retry) { Text("Try again") } }
                state.loading -> Text("Loading…")
                state.items.isEmpty() -> Text(if (options.filterCount > 0) "Nothing matches these filters." else "Nothing here yet.", style = MaterialTheme.typography.titleMedium)
                options.view == BrowseView.BOUNCE -> BounceBox(
                    state.items, state.total, repository, music,
                    titleOf = { item -> if (selecting) (if (item.id in selected) "✓ " else "○ ") + item.name else item.name },
                    onOpen = ::tap, onPlay = onPlay,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                else -> ShowView(
                    options.view,
                    ShowArgs(
                        state.items, repository, music,
                        titleOf = { item -> if (selecting) (if (item.id in selected) "✓ " else "○ ") + item.name else item.name },
                        onOpen = ::tap, onPlay = onPlay, onNeedMore = vm::loadMore,
                    ),
                    Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
    } else
    Row(Modifier.fillMaxSize()) {
    if (letterPlace == dev.nitrate.core.pages.LetterBarPlace.LEFT) LetterColumn(jumpTo, Modifier.padding(start = 12.dp))
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = grid,
        contentPadding = PaddingValues(start = PagePadding, end = PagePadding, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        modifier = Modifier.weight(1f).fillMaxSize(),
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) { header() }
        item(key = "bar", span = { GridItemSpan(maxLineSpan) }) { bar() }
        if (letterPlace == dev.nitrate.core.pages.LetterBarPlace.TOP) {
            item(key = "letters", span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Browse.LETTERS.forEach { letter -> LetterKey(letter, Modifier.size(width = 28.dp, height = 34.dp)) { jumpTo(letter) } }
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
                        // Selected titles carry a tick in their name while picking.
                        title = if (selecting) (if (item.id in selected) "✓ " else "○ ") + item.name else item.name,
                        imageUrl = if (artwork) repository.imageUrl(item, ImageKind.THUMB, 360) ?: repository.backdropUrl(item, 640) else repository.imageUrl(item, ImageKind.PRIMARY, 360),
                        width = null,
                        aspectRatio = if (music) 1f else if (artwork) 16f / 9f else 2f / 3f,
                        subtitle = if (music) item.albumArtist else item.productionYear?.takeIf { LocalDisplayPrefs.current.year }?.toString(),
                        onClick = { tap(item) },
                        onPlay = { if (item.isPlayable) onPlay(item) else onOpen(item) },
                        modifier = focus,
                    )
                } else {
                    ListCard(repository, if (selecting) item.copy(name = (if (item.id in selected) "✓ " else "○ ") + item.name) else item, focus) { tap(item) }
                }
            }
        }
        // A later page failed: say so, with a retry (it doesn't retry by itself).
        if (state.error != null && state.items.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Couldn't load more: ${state.error}", color = MaterialTheme.colorScheme.error)
                Button(onClick = vm::retry) { Text("Try again") }
            }
        }
    }

    if (letterPlace == dev.nitrate.core.pages.LetterBarPlace.RIGHT) LetterColumn(jumpTo, Modifier.padding(end = 12.dp))
    }

    when (dialog) {
        "bulk" -> BulkEditDialog(repository, state.items.filter { it.id in selected }, genres) { changed ->
            dialog = null
            if (changed) { selecting = false; selected = emptySet(); vm.reload() }
        }
        "view" -> ViewDialog(options.view, onPick = { v -> update { it.copy(view = v) }; dialog = null }, onDismiss = { dialog = null })
        "sort" -> SortDialog(options, onPick = { change -> update(change); dialog = null }, onDismiss = { dialog = null })
        "filters" -> FilterDialog(options, genres, libraryFilters, codecs = dev.nitrate.core.pages.Codecs.offeredFor(library.collection), onChange = update, onDismiss = { dialog = null })
    }
}

/** The A–Z bar down one side: every letter in the screen's height, ▲▼ through them, OK jumps. */
@Composable
private fun LetterColumn(onLetter: (String) -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxHeight().padding(vertical = 12.dp).width(30.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Browse.LETTERS.forEach { letter -> LetterKey(letter, Modifier.weight(1f).fillMaxWidth(), small = true) { onLetter(letter) } }
    }
}

@Composable
private fun LetterKey(letter: String, modifier: Modifier, small: Boolean = false, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)), modifier = modifier) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Text(letter, style = if (small) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge) }
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

/** Every way to lay the titles out: the plain ones, then the show views. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ViewDialog(current: BrowseView, onPick: (BrowseView) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(620.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("View", style = MaterialTheme.typography.headlineSmall)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BrowseView.entries.forEach { v ->
                    FilterChip(selected = v == current, onClick = { onPick(v) }, modifier = if (v == current) Modifier.focusRequester(first) else Modifier) { Text(v.label) }
                }
            }
            LaunchedEffect(Unit) { kotlinx.coroutines.android.awaitFrame(); runCatching { first.requestFocus() } }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterDialog(options: BrowseOptions, genres: List<String>, available: dev.nitrate.core.model.LibraryFilters, codecs: Boolean, onChange: ((BrowseOptions) -> BrowseOptions) -> Unit, onDismiss: () -> Unit) {
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
                // Video choices combine per stream: codec, bit depth and range all on the same video.
                Group("Video codec (any of)") {
                    dev.nitrate.core.pages.Codecs.VIDEO.forEach { (id, label) -> FilterChip(id in options.videoCodecs, { onChange { it.toggleVideoCodec(id) } }) { Text(label) } }
                }
                Group("Bit depth") {
                    dev.nitrate.core.pages.Codecs.BIT_DEPTHS.forEach { d -> FilterChip(d in options.bitDepths, { onChange { it.toggleBitDepth(d) } }) { Text("$d-bit") } }
                }
                Group("Dynamic range") {
                    dev.nitrate.core.pages.Codecs.RANGES.forEach { (id, label) -> FilterChip(id in options.videoRanges, { onChange { it.toggleRange(id) } }) { Text(label) } }
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
                Browse.decadesFor(available.years).forEach { d -> FilterChip(options.decade == d, { onChange { it.copy(decade = if (it.decade == d) null else d) } }) { Text("${d}s") } }
            }
            if (available.officialRatings.isNotEmpty()) Group("Age rating (any of)") {
                available.officialRatings.forEach { r -> FilterChip(r in options.officialRatings, { onChange { it.toggleRating(r) } }) { Text(r) } }
            }
            if (genres.isNotEmpty()) Group("Genres (any of)") {
                genres.forEach { g -> FilterChip(g in options.genres, { onChange { o -> o.copy(genres = if (g in o.genres) o.genres - g else o.genres + g) } }) { Text(g) } }
            }
            if (available.tags.isNotEmpty()) Group("Tags (any of)") {
                available.tags.take(60).forEach { t -> FilterChip(t in options.tags, { onChange { it.toggleTag(t) } }) { Text(t) } }
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
