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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.heightIn
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
import dev.nitrate.core.pages.GridLayout
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
fun BrowseLibrary(actions: AppActions, library: MediaItem, header: (@Composable () -> Unit)? = null) {
    val prefs = LocalDisplayPrefs.current
    val vm = viewModel(key = "browse-${library.id}") { BrowseViewModel(actions.server, actions.container.displaySettings, library) }
    val state by vm.state.collectAsStateWithLifecycle()
    val genres by vm.genres.collectAsStateWithLifecycle()
    val libraryFilters by vm.filters.collectAsStateWithLifecycle()
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
    // Admins: pick titles to edit together.
    val account by actions.container.account.collectAsState()
    val canBulkEdit = account?.isAdmin == true && library.collection != dev.nitrate.core.model.CollectionKind.MUSIC
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var editing by remember { mutableStateOf(false) }
    fun tap(item: MediaItem) {
        if (selecting) selected = if (item.id in selected) selected - item.id else selected + item.id else actions.open(item)
    }
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
    // The title a letter jump landed on, outlined for a moment: in a grid it's often mid-row,
    // after the previous letter's last titles.
    var jumpedTo by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(jumpedTo) { if (jumpedTo != null) { kotlinx.coroutines.delay(1_800); jumpedTo = null } }
    fun jump(letter: String) {
        scope.launch {
            val index = vm.positionOf(letter) ?: return@launch
            if (options.view != BrowseView.LIST) grid.scrollToItem(index) else list.scrollToItem(index)
            jumpedTo = vm.state.value.items.getOrNull(index)?.id
        }
    }

    // Fixed grid: the tabs and the bar stay at the top; Scrolling grid: they scroll away with the posters.
    val pinned = prefs.phoneGridLayout == GridLayout.FIXED
    // The tabs (from the library page), the Sort/Filters/View bar and, for admins, picking titles to edit.
    val top: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth()) {
            header?.invoke()
            BrowseBar(options, state.total, onChange = update, onFilters = { filtering = true })
            if (canBulkEdit) Row(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding), verticalAlignment = Alignment.CenterVertically) {
                if (!selecting) {
                    TextButton(onClick = { selecting = true }) { Text("Select to edit") }
                } else {
                    Text("${selected.size} selected", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { selected = selected + state.items.map { it.id } }) { Text("All shown") }
                    TextButton(onClick = { editing = true }, enabled = selected.isNotEmpty()) { Text("Edit…") }
                    TextButton(onClick = { selecting = false; selected = emptySet() }) { Text("Cancel") }
                }
            }
        }
    }
    // Scrolling grid: how tall the top is, so the A–Z strip starts below it.
    var topHeight by remember { mutableStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    // In the grid or list, the top spans the whole width (the cards keep their side padding).
    val endPad = if (options.alphabetical) 34.dp else ScreenPadding
    val scrollingTop: @Composable () -> Unit = {
        Box(Modifier.fullBleed(ScreenPadding, endPad).onSizeChanged { topHeight = it.height }) { top() }
    }

    Column(Modifier.fillMaxSize()) {
        if (pinned) top()
        Box(Modifier.fillMaxSize()) {
            val padding = PaddingValues(start = ScreenPadding, end = endPad, top = 4.dp, bottom = bottomInset(24.dp))
            // Loading, empty and failed pages: the top above them either way.
            val withTop: @Composable (@Composable () -> Unit) -> Unit = { content ->
                if (pinned) content() else Column(Modifier.fillMaxSize()) { top(); Box(Modifier.weight(1f)) { content() } }
            }
            when {
                state.error != null && state.items.isEmpty() -> withTop { ErrorMessage(state.error ?: "", vm::retry) }
                state.loading -> withTop { GridPlaceholder(minWidth = if (artwork) 240.dp else if (square) 140.dp else 110.dp, aspect = if (artwork) 16f / 9f else if (square) 1f else 2f / 3f) }
                state.items.isEmpty() -> withTop { EmptyResults(options) { update { it.clearFilters() } } }
                options.view != BrowseView.LIST -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = if (artwork) 240.dp else if (square) 140.dp else 110.dp),
                    state = grid,
                    contentPadding = padding,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize().then(if (pinned) Modifier.fadeUnderBar { grid.canScrollBackward } else Modifier),
                ) {
                    if (!pinned) item(key = "top", span = { GridItemSpan(maxLineSpan) }, contentType = "top") { scrollingTop() }
                    items(state.items, key = { it.id }) { item ->
                        Box(if (item.id == jumpedTo) Modifier.border(2.dp, accentColor(), RoundedCornerShape(14.dp)) else Modifier) {
                            // Home's card: the same titles, marks and progress, and the same hold.
                            MediaCard(
                                item = item,
                                imageUrl = actions.server.cardImage(item, cardShape),
                                shape = cardShape,
                                title = if (artwork) item.displayTitle else item.name,
                                subtitle = when {
                                    square -> item.albumArtist
                                    artwork -> listOfNotNull(item.episodeLabel ?: item.productionYear?.takeIf { prefs.year }?.toString(), timeLeft(item)).joinToString(" · ").ifEmpty { null }
                                    else -> item.productionYear?.takeIf { prefs.year }?.toString()
                                },
                                onClick = { tap(item) },
                                onLongClick = actions.cards?.takeIf { !selecting && it.shares(item) }?.let { c -> { c.hold(item) } },
                            )
                            if (selecting) SelectMark(item.id in selected, Modifier.align(Alignment.TopEnd).padding(6.dp))
                        }
                    }
                    if (state.error != null) item(span = { GridItemSpan(maxLineSpan) }) { LoadMoreFailed(state.error.orEmpty(), vm::retry) }
                }
                else -> LazyColumn(state = list, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize().then(if (pinned) Modifier.fadeUnderBar { list.canScrollBackward } else Modifier)) {
                    if (!pinned) item(key = "top", contentType = "top") { scrollingTop() }
                    items(state.items, key = { it.id }) { item ->
                        Box {
                            ListRow(actions, item, onClick = { tap(item) })
                            if (selecting) SelectMark(item.id in selected, Modifier.align(Alignment.TopEnd))
                        }
                    }
                    if (state.error != null) item { LoadMoreFailed(state.error.orEmpty(), vm::retry) }
                }
            }
            if (options.alphabetical && state.items.isNotEmpty()) {
                // Scrolling grid: the strip starts where the posters do, below the tabs and the bar.
                val below = if (pinned) 0.dp else with(density) { topHeight.toDp() }
                AlphabetStrip(Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(top = below + 8.dp, bottom = 8.dp), ::jump)
            }
        }
    }
    if (editing) BulkEditSheet(actions, state.items.filter { it.id in selected }, genres) { changed ->
        editing = false
        if (changed) { selecting = false; selected = emptySet(); vm.reload() }
    }
    if (filtering) FilterSheet(options, genres, libraryFilters, codecs = dev.nitrate.core.pages.Codecs.offeredFor(library.collection), onChange = update, onDismiss = { filtering = false })
}

@Composable
private fun BrowseBar(options: BrowseOptions, total: Int, onChange: ((BrowseOptions) -> BrowseOptions) -> Unit, onFilters: () -> Unit) {
    var sortMenu by remember { mutableStateOf(false) }
    // Compact, like Home's top: small glass pills and the count as small text.
    Row(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box {
            BarPill(
                options.sort.label,
                onClick = { sortMenu = true },
                trailing = if (options.descending) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                state = if (options.descending) "Sorted highest or newest first" else "Sorted lowest or oldest first",
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
        BarPill(if (options.filterCount > 0) "Filters · ${options.filterCount}" else "Filters", onClick = onFilters, active = options.filterCount > 0)
        var viewMenu by remember { mutableStateOf(false) }
        Box {
            BarPill(
                (if (options.view.tvOnly) BrowseView.GRID else options.view).label,
                onClick = { viewMenu = true },
                leading = if (options.view == BrowseView.LIST) Icons.AutoMirrored.Filled.List else null,
            )
            DropdownMenu(viewMenu, onDismissRequest = { viewMenu = false }) {
                BrowseView.entries.filterNot { it.tvOnly }.forEach { v ->
                    DropdownMenuItem(
                        text = { Text(v.label) },
                        trailingIcon = { if (v == options.view) Icon(Icons.Default.Check, null) },
                        onClick = { onChange { it.copy(view = v) }; viewMenu = false },
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (total >= 0) Text(if (total == 1) "1 title" else "$total titles", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** A small glass pill in the bar: its words, an optional arrow or icon, accent-tinted when [active]. */
@Composable
private fun BarPill(label: String, onClick: () -> Unit, active: Boolean = false, leading: androidx.compose.ui.graphics.vector.ImageVector? = null, trailing: androidx.compose.ui.graphics.vector.ImageVector? = null, state: String? = null) {
    val accent = accentColor()
    val fg = if (active) accent else Color.White
    Row(
        Modifier.clip(RoundedCornerShape(16.dp))
            .background(if (active) accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.10f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(16.dp))
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .then(if (state != null) Modifier.semantics { stateDescription = state } else Modifier)
            .heightIn(min = 32.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (leading != null) Icon(leading, null, tint = fg, modifier = Modifier.size(16.dp))
        Text(label, color = fg, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (trailing != null) Icon(trailing, null, tint = fg, modifier = Modifier.size(16.dp))
    }
}

/**
 * Widens a full-span item in a padded grid or list back out to the page's edges, so a bar inside it
 * lines up with the same bar pinned above the grid.
 */
private fun Modifier.fullBleed(start: androidx.compose.ui.unit.Dp, end: androidx.compose.ui.unit.Dp): Modifier = layout { measurable, constraints ->
    val extra = (start + end).roundToPx()
    val wide = constraints.copy(minWidth = (constraints.minWidth + extra), maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else constraints.maxWidth)
    val placeable = measurable.measure(wide)
    val width = (placeable.width - extra).coerceAtLeast(0)
    layout(width, placeable.height) { placeable.place(-start.roundToPx(), 0) }
}

/** One row of the list view: poster, title, facts and overview, as the display settings allow. */
@Composable
private fun ListRow(actions: AppActions, item: MediaItem, onClick: () -> Unit = { actions.open(item) }) {
    val prefs = LocalDisplayPrefs.current
    Row(Modifier.fillMaxWidth().pressable(onClick), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.width(72.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.08f))) {
            actions.server.imageUrl(item, ImageKind.PRIMARY, 240)?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            prefs.facts(item).takeIf { it.isNotEmpty() }?.let {
                Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (prefs.overview && !descriptionHidden(item)) item.overview?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.copyOnLongPress(it, onClick))
            }
        }
        if (prefs.watchedMarks && item.user.played) Icon(Icons.Default.Check, "Watched", tint = accentColor())
    }
}

/** A tick on a title picked for editing (an empty ring when not). */
@Composable
private fun SelectMark(on: Boolean, modifier: Modifier) {
    Box(
        modifier.size(26.dp).background(if (on) accentColor() else Color.Black.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape)
            .border(2.dp, Color.White, androidx.compose.foundation.shape.CircleShape),
        Alignment.Center,
    ) { if (on) Icon(Icons.Default.Check, "Selected", tint = Color.Black, modifier = Modifier.size(16.dp)) }
}

/**
 * # and A–Z down the edge: tap or drag to jump. Each letter has an equal slice of the strip and
 * sits in the middle of it, so the slice a touch falls in is always the letter drawn there.
 */
@Composable
fun AlphabetStrip(modifier: Modifier, onLetter: (String) -> Unit) {
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
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Browse.LETTERS.forEach {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(it, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = accentColor())
            }
        }
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
private fun FilterSheet(options: BrowseOptions, genres: List<String>, available: dev.nitrate.core.model.LibraryFilters, codecs: Boolean, onChange: ((BrowseOptions) -> BrowseOptions) -> Unit, onDismiss: () -> Unit) {
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
                Switch(options.favoritesOnly, { on -> onChange { it.copy(favoritesOnly = on) } }, Modifier.labelled("Favourites only"))
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
                Browse.decadesFor(available.years).forEach { d -> FilterChip(options.decade == d, { onChange { it.copy(decade = if (it.decade == d) null else d) } }, label = { Text("${d}s") }) }
            }
            if (available.officialRatings.isNotEmpty()) Group("Age rating (any of)") {
                available.officialRatings.forEach { r -> FilterChip(r in options.officialRatings, { onChange { it.toggleRating(r) } }, label = { Text(r) }) }
            }
            if (genres.isNotEmpty()) Group("Genres (any of)") {
                genres.forEach { g ->
                    FilterChip(g in options.genres, { onChange { o -> o.copy(genres = if (g in o.genres) o.genres - g else o.genres + g) } }, label = { Text(g) })
                }
            }
            if (available.tags.isNotEmpty()) Group("Tags (any of)") {
                available.tags.take(60).forEach { t -> FilterChip(t in options.tags, { onChange { it.toggleTag(t) } }, label = { Text(t) }) }
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
        state.total < 0 -> GridPlaceholder(minWidth = 120.dp, aspect = 1f)
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 120.dp),
            state = grid,
            contentPadding = screenListPadding(),
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

/**
 * Fixed grid: what scrolls up under the pinned bar fades out over [FADE] rather than being cut off
 * at a hard edge; nothing fades until the list has moved ([scrolled]), so the first row sits whole.
 */
private fun Modifier.fadeUnderBar(scrolled: () -> Boolean) = graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        if (scrolled()) {
            val fade = (FADE.toPx() / size.height).coerceIn(0f, 1f)
            drawRect(Brush.verticalGradient(0f to Color.Transparent, fade to Color.Black), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
        }
    }

private val FADE = 28.dp
