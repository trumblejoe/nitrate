package dev.nitrate.tv.ui.library

import dev.nitrate.tv.ui.partners.spoilerBlur
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
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.drawWithContent
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.input.key.type
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    /** The chosen tab: ▲ from the options bar goes straight to it (not whichever tab is nearest). */
    tabFocus: FocusRequester? = null,
    /** The tab's name, for Customize → View. */
    tabName: String = "All",
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
    // The title in focus: its picture behind the page (as on Home), and where Back and a held ▲ start from.
    var focusedIndex by remember { mutableStateOf(-1) }
    // The last title focused: its picture stays while focus is up on the bar or the tabs.
    var lastFocused by remember { mutableStateOf(-1) }
    val firstTitle = remember { FocusRequester() }
    // Back deep in the grid (or a held ▲): to the first title; Back there goes on as before (the first tab, then out).
    fun toFirstTitle() {
        scope.launch {
            runCatching { grid.scrollToItem(0) }
            repeat(10) { if (runCatching { firstTitle.requestFocus() }.getOrDefault(false)) return@launch; kotlinx.coroutines.delay(32) }
        }
    }
    // Item focus moves here after a letter jump.
    val jumpFocus = remember { FocusRequester() }
    var jumpedTo by remember { mutableStateOf(-1) }
    // Counts jumps, so jumping to the same letter again still moves focus.
    var jumps by remember { mutableStateOf(0) }
    // The chosen title, shared by every view (the grid's last focused, a drawn view's chosen): the jump
    // bar and ⏪ ⏩ move it and keep it when the view changes.
    val chosen = androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    // The jump bar's place for the chosen title (Back from a view lands here), and a drawn view's focus.
    val stripFocus = remember { FocusRequester() }
    var stripFocused by remember { mutableStateOf(false) }
    val viewFocus = remember { FocusRequester() }
    // "View: Vortex · 7 of 17" for a moment after ⏪ ⏩ (or a number key's letter).
    var note by remember { mutableStateOf<Pair<String, Long>?>(null) }
    // Number keys: which digit, how many presses running, when.
    var keypad by remember { mutableStateOf(Triple(-1, 0, 0L)) }

    LaunchedEffect(grid) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last -> if (last >= state.items.size - 30) vm.loadMore() }
    }
    LaunchedEffect(jumps) { if (jumpedTo >= 0) runCatching { jumpFocus.requestFocus() } }

    // Album covers are square.
    val music = library.collection == dev.nitrate.core.model.CollectionKind.MUSIC
    val columns = options.columnsFor(options.view, music)
    // The A–Z bar: above the titles, down one side (the user's choice, away from the menu by
    // default) or off. Above, it's one of the grid's first items.
    val prefs = LocalDisplayPrefs.current
    // Fixed: the focused title's details held at the top (as on Home), the posters scrolling beneath.
    // Scrolling: the posters fill the page, the tabs bar scrolling away with them.
    val fixed = prefs.tvGridLayout == dev.nitrate.core.pages.GridLayout.FIXED
    // The jump bar follows the sort (letters, decades, rating bands, how recently added); the Bouncing box keeps its own A–Z.
    val stops = remember(options.sort, options.descending) { dev.nitrate.core.pages.JumpBar.stops(options) }
    val letterPlace = if (stops.isNotEmpty() && options.view != BrowseView.BOUNCE) prefs.tvLetterBar.resolve(prefs.tvMenu) else null
    val lettersOnTop = letterPlace == dev.nitrate.core.pages.LetterBarPlace.TOP
    // The grid's items before the titles: the bar (scrolling only) and the A–Z row (above).
    val leading = (if (fixed) 0 else 1) + (if (lettersOnTop) 1 else 0)
    val jumpTo: (String) -> Unit = { stop ->
        scope.launch {
            val index = vm.positionOfStop(stop) ?: return@launch
            chosen.intValue = index
            jumps++
            if (options.view.isShow) {
                repeat(10) { if (runCatching { viewFocus.requestFocus() }.getOrDefault(false)) return@launch; kotlinx.coroutines.delay(32) }
            } else {
                grid.scrollToItem(index + leading)
                jumpedTo = index
            }
        }
    }
    // The stop the chosen title is under: lit on the bar, where Back lands.
    val currentStop = state.items.getOrNull(if (options.view.isShow) chosen.intValue else maxOf(lastFocused, 0))?.let { dev.nitrate.core.pages.JumpBar.stopOf(options, it, dev.nitrate.android.nowMs()) }
    // ⏪ ⏩: the next or previous view (as Home's rows change style), at the same title.
    fun cycleView(by: Int) {
        val all = BrowseView.entries
        val next = all[(all.indexOf(options.view) + by).mod(all.size)]
        if (!options.view.isShow && lastFocused >= 0) chosen.intValue = lastFocused
        vm.update { it.copy(view = next) }
        note = "View: ${next.label}  ·  ${all.indexOf(next) + 1} of ${all.size}  ·  ⏪ ⏩" to System.nanoTime()
        scope.launch {
            kotlinx.coroutines.delay(150)
            if (next.isShow) {
                jumps++
                repeat(12) { if (runCatching { viewFocus.requestFocus() }.getOrDefault(false)) return@launch; kotlinx.coroutines.delay(40) }
            } else {
                runCatching { grid.scrollToItem(chosen.intValue + leading) }
                jumpedTo = chosen.intValue
                jumps++
            }
        }
    }
    // A number key (2 = ABC…; again for the next letter): straight to that letter, when sorted by name.
    fun keyed(digit: Int) {
        if (!options.alphabetical) return
        val now = System.currentTimeMillis()
        val (last, presses, at) = keypad
        val n = if (last == digit && now - at < 1_200) presses + 1 else 1
        keypad = Triple(digit, n, now)
        val letter = dev.nitrate.core.pages.JumpBar.keypad(digit, n) ?: return
        note = "Jump: $letter" to System.nanoTime()
        jumpTo(letter)
    }
    // ▲ from the first titles: to Sort, at the end of the tabs bar (scrolled back into sight first).
    val sortButton = remember { FocusRequester() }
    fun toBar() {
        scope.launch {
            if (!fixed) runCatching { grid.scrollToItem(0) }
            repeat(10) { if (runCatching { sortButton.requestFocus() }.getOrDefault(false)) return@launch; kotlinx.coroutines.delay(32) }
        }
    }
    // The view is chosen in Customize (the menu's pencil), for this tab: it rebuilds the page around
    // it (a show view isn't a grid), and focus goes to Sort once the panel closes.
    dev.nitrate.tv.ui.pages.ReportTabView(
        dev.nitrate.tv.ui.pages.TabViewChoice(
            tabName, rows = null, view = options.view, columns = columns, onRows = {},
            onView = { v -> update { it.copy(view = v) } },
            onColumns = { n -> vm.update { it.copy(columns = it.columns + (it.view to n)) } },
        ),
        refocus = sortButton,
    )
    // Sort, filters, the count and selecting: small, at the end of the tabs bar; ▲ from them to the chosen tab.
    val buttons: @Composable () -> Unit = {
        Row(
            Modifier.focusProperties { if (tabFocus != null) up = tabFocus },
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.total >= 0) Text("${state.total} items", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 4.dp))
            // The view, as ⏪ ⏩ change it: here for remotes without those keys.
            BarButton("View: ${options.view.label}") { dialog = "view" }
            BarButton("Sort: ${options.sort.label}${if (options.descending) " ↓" else " ↑"}", Modifier.focusRequester(sortButton)) { dialog = "sort" }
            BarButton(if (options.filterCount > 0) "Filters · ${options.filterCount}" else "Filters") { dialog = "filters" }
            if (options.filterCount > 0) BarButton("Clear filters") { update { it.clearFilters() } }
            if (canBulkEdit) {
                if (!selecting) BarButton("Select to edit") { selecting = true }
                else {
                    Text("${selected.size} selected", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    BarButton("All shown") { selected = selected + state.items.map { it.id } }
                    BarButton("Edit…", enabled = selected.isNotEmpty()) { dialog = "bulk" }
                    BarButton("Done selecting") { selecting = false; selected = emptySet() }
                }
            }
        }
    }
    // ▼ from the tabs bar: into the grid at the title last used (else the first in sight), not the
    // A–Z bar beside it, which starts higher up the screen and so was "nearer".
    val gridEntry = remember { FocusRequester() }
    val entryIndex by remember(leading) {
        androidx.compose.runtime.derivedStateOf {
            val inSight = grid.layoutInfo.visibleItemsInfo.map { it.index - leading }.filter { it >= 0 && it < state.items.size }
            if (lastFocused in inSight) lastFocused else inSight.firstOrNull() ?: 0
        }
    }
    val downToGrid = !options.view.isShow && !lettersOnTop && state.items.isNotEmpty()
    // The tabs bar as on Home and the other tabs: the library's name and tabs, then the buttons at its end.
    val bar: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().focusProperties { if (downToGrid) down = gridEntry }, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { header() }
            Box(Modifier.padding(end = PagePadding + dev.nitrate.tv.ui.components.LocalClockRoom.current)) { buttons() }
        }
    }
    // The same keys in every view: ⏪ ⏩ the view, number keys a letter; Back (nothing open in the view) to the jump bar.
    Box(Modifier.fillMaxSize().onPreviewKeyEvent { e ->
        val digit = when (e.key) {
            Key.Zero -> 0; Key.One -> 1; Key.Two -> 2; Key.Three -> 3; Key.Four -> 4
            Key.Five -> 5; Key.Six -> 6; Key.Seven -> 7; Key.Eight -> 8; Key.Nine -> 9; else -> -1
        }
        when {
            e.key == Key.MediaFastForward || e.key == Key.MediaRewind -> {
                if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) cycleView(if (e.key == Key.MediaFastForward) 1 else -1)
                true
            }
            digit >= 0 && options.alphabetical -> { if (e.type == KeyEventType.KeyDown) keyed(digit); true }
            else -> false
        }
    }.onKeyEvent { e ->
        // After the view: a peeled card or lightbox takes Back first. The bar itself lets Back go on (out).
        if (e.key != Key.Back || letterPlace == null || stripFocused || state.items.isEmpty()) return@onKeyEvent false
        if (e.type == KeyEventType.KeyUp) scope.launch {
            repeat(10) { if (runCatching { stripFocus.requestFocus() }.getOrDefault(false)) return@launch; kotlinx.coroutines.delay(32) }
        }
        true
    }) {
    if (options.view.isShow) {
        // A show view fills the page under the tabs bar, the jump bar beside or above it as on the grids.
        Column(Modifier.fillMaxSize().padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            bar()
            Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = PagePadding)) {
            if (letterPlace == dev.nitrate.core.pages.LetterBarPlace.LEFT) JumpColumn(stops, currentStop, stripFocus, { stripFocused = it }, jumpTo, Modifier.padding(end = 10.dp))
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (lettersOnTop) JumpRow(stops, currentStop, stripFocus, { stripFocused = it }, jumpTo)
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().focusRequester(viewFocus)) {
            when {
                state.error != null && state.items.isEmpty() -> Column { Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error); Button(onClick = vm::retry) { Text("Try again") } }
                state.loading -> dev.nitrate.tv.ui.components.GridPlaceholder()
                state.items.isEmpty() -> Text(if (options.filterCount > 0) "Nothing matches these filters." else "Nothing here yet.", style = MaterialTheme.typography.titleMedium)
                else -> {
                    val args = ShowArgs(
                        state.items, repository, music,
                        titleOf = { item -> if (selecting) (if (item.id in selected) "✓ " else "○ ") + item.name else item.name },
                        onOpen = ::tap, onPlay = onPlay, onNeedMore = vm::loadMore,
                        // The Bouncing box fills itself by letter; every other view uses the page's jump bar.
                        letterAt = if (options.alphabetical && options.view == BrowseView.BOUNCE) vm::positionOf else null,
                        total = state.total,
                        chosen = chosen, jumped = jumps,
                    )
                    if (options.view == BrowseView.BOUNCE) BounceBox(args, Modifier.fillMaxSize())
                    else ShowView(options.view, args, Modifier.fillMaxSize())
                }
            }
            }
            }
            if (letterPlace == dev.nitrate.core.pages.LetterBarPlace.RIGHT) JumpColumn(stops, currentStop, stripFocus, { stripFocused = it }, jumpTo, Modifier.padding(start = 10.dp))
            }
        }
    } else Box(Modifier.fillMaxSize()) {
    // The title in focus (else the one last focused, else the first): its picture behind the page and,
    // fixed, its details at the top, as on Home.
    val shownIndex = when { focusedIndex >= 0 -> focusedIndex; lastFocused >= 0 -> lastFocused; else -> 0 }
    val shown = state.items.getOrNull(shownIndex)
    val backdropUrl = shown?.let { repository.backdropUrl(it) }
    dev.nitrate.tv.ui.effects.PageBackdropSlot(backdropUrl, clean = true)
    // The picture whole behind the details; darker under the posters so their words read.
    val backdropChoice = dev.nitrate.tv.ui.pages.LocalLayoutPage.current?.let { prefs.pageBackdrops[it] } ?: dev.nitrate.core.effects.PageBackdrop.ARTWORK
    if (backdropChoice.kind == dev.nitrate.core.effects.PageBackdrop.Kind.ARTWORK && backdropUrl != null) GridShade(fixed)
    Row(Modifier.fillMaxSize().onPreviewKeyEvent { e ->
        val deep = focusedIndex >= columns
        when {
            // Back deep in the grid: to the first title (taken as a key: left to Compose, Back only moved focus about).
            e.key == Key.Back && deep && letterPlace == null -> { if (e.type == KeyEventType.KeyUp) toFirstTitle(); true }
            // Holding ▲ in the grid: straight to the first title (the first press has moved up one row).
            e.key == Key.DirectionUp && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 0 && deep -> {
                if (e.nativeKeyEvent.repeatCount == 1) toFirstTitle()
                true
            }
            // ▲ from the first row: to the bar's buttons (the tabs are one more ▲ away), not whichever tab is above.
            e.key == Key.DirectionUp && e.type == KeyEventType.KeyDown && !lettersOnTop && focusedIndex in 0 until columns -> {
                if (e.nativeKeyEvent.repeatCount == 0) toBar()
                true
            }
            // ▼ on the last line (everything loaded): stay put, not off sideways to the A–Z bar's last letters.
            e.key == Key.DirectionDown && e.type == KeyEventType.KeyDown && focusedIndex >= 0 && state.items.size >= state.total &&
                focusedIndex / columns == (state.items.size - 1) / columns -> true
            else -> false
        }
    }) {
    if (letterPlace == dev.nitrate.core.pages.LetterBarPlace.LEFT) JumpColumn(stops, currentStop, stripFocus, { stripFocused = it }, jumpTo, Modifier.padding(start = 10.dp))
    Column(Modifier.weight(1f).fillMaxHeight()) {
    if (fixed) {
        bar()
        val tall = prefs.tvCaptionSize == dev.nitrate.core.pages.CaptionSize.NORMAL
        Box(Modifier.fillMaxWidth().height(if (tall) 156.dp else 112.dp).padding(start = PagePadding, end = PagePadding, bottom = 10.dp), contentAlignment = Alignment.BottomStart) {
            if (shown != null) dev.nitrate.tv.ui.components.ArtCaption(
                shown, label = "${library.name} · ${shownIndex + 1} / ${state.total.coerceAtLeast(state.items.size)}", end = false,
                logoUrl = dev.nitrate.tv.ui.components.captionLogo(repository, shown),
            )
        }
    }
    // A focused title comes whole into sight with a little room round it (not hard against the screen's edge).
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 28.dp.toPx() }
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides remember(gap) { WholeInSight(gap) }) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = grid,
        // Room round the edge, so a focused (grown) card isn't cut off.
        contentPadding = PaddingValues(start = PagePadding, end = PagePadding, top = if (fixed) 10.dp else 0.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(prefs.tvCardSpacing.dp.dp),
        verticalArrangement = Arrangement.spacedBy((prefs.tvCardSpacing.dp + 4).dp),
        modifier = Modifier.weight(1f).fillMaxWidth().then(if (fixed) Modifier.fadeTop(18.dp) { grid.canScrollBackward } else Modifier),
    ) {
        // Scrolling: the tabs bar is the grid's first line, scrolling away with the posters (the
        // page's own edge, not the grid's padding).
        if (!fixed) item(key = "bar", span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.bleed(PagePadding)) { bar() } }
        if (lettersOnTop) {
            item(key = "letters", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.focusProperties { up = sortButton }) { JumpRow(stops, currentStop, stripFocus, { stripFocused = it }, jumpTo) }
            }
        }
        when {
            state.error != null && state.items.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                Column { Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error); Button(onClick = vm::retry) { Text("Try again") } }
            }
            state.loading -> item(span = { GridItemSpan(maxLineSpan) }) { dev.nitrate.tv.ui.components.GridPlaceholder() }
            state.items.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                Text(if (options.filterCount > 0) "Nothing matches these filters." else "Nothing here yet.", style = MaterialTheme.typography.titleMedium)
            }
            else -> items(state.items.size, key = { state.items[it].id }) { i ->
                val item = state.items[i]
                val focus = (if (i == jumpedTo) Modifier.focusRequester(jumpFocus) else Modifier)
                    .then(if (i == entryIndex) Modifier.focusRequester(gridEntry) else Modifier)
                    .then(if (i == 0) Modifier.focusRequester(firstTitle) else Modifier)
                    .onFocusChanged { if (it.hasFocus) { focusedIndex = i; lastFocused = i; chosen.intValue = i } else if (focusedIndex == i) focusedIndex = -1 }
                // Selected titles carry a tick in their name while picking.
                val ticked = if (selecting) (if (item.id in selected) "✓ " else "○ ") + item.name else null
                if (options.view != BrowseView.LIST) {
                    val artwork = options.view == BrowseView.ARTWORK && !music
                    // As Home's rows: posters bare (the details above name the focused one; scrolling,
                    // its name shows under it), artwork and album covers with their words.
                    val words = ticked != null || artwork || music
                    MediaCard(
                        item = item,
                        title = ticked ?: item.displayTitle.takeIf { words || !fixed },
                        imageUrl = if (artwork) repository.imageUrl(item, ImageKind.THUMB, 360) ?: repository.backdropUrl(item, 640) else repository.imageUrl(item, ImageKind.PRIMARY, 360),
                        width = null,
                        aspectRatio = if (music) 1f else if (artwork) 16f / 9f else 2f / 3f,
                        subtitle = (if (music) item.albumArtist else item.productionYear?.takeIf { prefs.year }?.toString()).takeIf { ticked == null && (words || !fixed) },
                        titleOnFocus = !words,
                        onClick = { tap(item) },
                        onPlay = { if (item.isPlayable) onPlay(item) else onOpen(item) },
                        modifier = focus,
                    )
                } else {
                    ListCard(repository, if (ticked != null) item.copy(name = ticked) else item, focus) { tap(item) }
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
    }
    }

    if (letterPlace == dev.nitrate.core.pages.LetterBarPlace.RIGHT) JumpColumn(stops, currentStop, stripFocus, { stripFocused = it }, jumpTo, Modifier.padding(end = 10.dp))
    }
    }
    // What ⏪ ⏩ (or a number key) just did, for a moment.
    note?.let { (text, at) ->
        LaunchedEffect(at) { kotlinx.coroutines.delay(1_800); if (note?.second == at) note = null }
        Text(
            text, style = MaterialTheme.typography.titleMedium, color = Color.White,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp).background(Color(0xE0101114), RoundedCornerShape(50)).padding(horizontal = 22.dp, vertical = 10.dp),
        )
    }
    }

    when (dialog) {
        "bulk" -> BulkEditDialog(repository, state.items.filter { it.id in selected }, genres) { changed ->
            dialog = null
            if (changed) { selecting = false; selected = emptySet(); vm.reload() }
        }
        "sort" -> SortDialog(options, onPick = { change -> update(change); dialog = null }, onDismiss = { dialog = null })
        "view" -> ViewDialog(options.view, onPick = { v ->
            dialog = null
            val all = BrowseView.entries
            cycleView(all.indexOf(v) - all.indexOf(options.view))
        }, onDismiss = { dialog = null })
        "filters" -> FilterDialog(options, genres, libraryFilters, codecs = dev.nitrate.core.pages.Codecs.offeredFor(library.collection), onChange = update, onDismiss = { dialog = null })
    }
}

/**
 * The jump bar down one side, the same for every view: its stops follow the sort (A–Z, decades,
 * rating bands, how recently added); slim and dimmed until it has focus, the chosen title's stop lit;
 * ▲▼ through them, OK jumps. Back from a view lands on the lit stop ([focus]).
 */
@Composable
internal fun JumpColumn(stops: List<dev.nitrate.core.pages.JumpStop>, current: String?, focus: FocusRequester, onFocused: (Boolean) -> Unit, onStop: (String) -> Unit, modifier: Modifier) {
    var inUse by remember { mutableStateOf(false) }
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (inUse) 1f else 0.5f, label = "jump")
    val narrow = stops.all { it.label.length <= 2 }
    Column(
        modifier.fillMaxHeight().padding(vertical = 12.dp).width(if (narrow) 22.dp else 104.dp)
            .onFocusChanged { inUse = it.hasFocus; onFocused(it.hasFocus) }.graphicsLayer { this.alpha = alpha },
        verticalArrangement = Arrangement.spacedBy(if (narrow) 1.dp else 6.dp),
    ) {
        val landing = current?.takeIf { c -> stops.any { it.key == c } } ?: stops.firstOrNull()?.key
        stops.forEach { stop ->
            val m = (if (narrow) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth().height(40.dp))
                .then(if (stop.key == landing) Modifier.focusRequester(focus) else Modifier)
            LetterKey(stop, lit = stop.key == current, m, small = true) { onStop(stop.key) }
        }
    }
}

/** The jump bar above the titles: the same stops in a row. */
@Composable
internal fun JumpRow(stops: List<dev.nitrate.core.pages.JumpStop>, current: String?, focus: FocusRequester, onFocused: (Boolean) -> Unit, onStop: (String) -> Unit) {
    val landing = current?.takeIf { c -> stops.any { it.key == c } } ?: stops.firstOrNull()?.key
    Row(Modifier.onFocusChanged { onFocused(it.hasFocus) }, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        stops.forEach { stop ->
            val narrow = stop.label.length <= 2
            LetterKey(stop, lit = stop.key == current, Modifier.size(width = if (narrow) 28.dp else 104.dp, height = 34.dp).then(if (stop.key == landing) Modifier.focusRequester(focus) else Modifier)) { onStop(stop.key) }
        }
    }
}

@Composable
private fun LetterKey(stop: dev.nitrate.core.pages.JumpStop, lit: Boolean, modifier: Modifier, small: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        // No box round each stop until it's focused: a quiet strip; the chosen title's stop in the accent.
        colors = if (small) ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, contentColor = if (lit) MaterialTheme.colorScheme.primary else Color.White) else ClickableSurfaceDefaults.colors(),
        modifier = modifier.semantics { contentDescription = stop.spoken },
    ) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text(stop.label, style = MaterialTheme.typography.labelSmall.let { if (small) it.copy(fontSize = it.fontSize * 0.9f) else MaterialTheme.typography.labelLarge }, maxLines = 1)
        }
    }
}

/** The view, as ⏪ ⏩ change it: every one in a list, the one showing chosen. */
@Composable
private fun ViewDialog(current: BrowseView, onPick: (BrowseView) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    dev.nitrate.tv.ui.lists.TvDialog(onDismiss, width = 460) {
        Text("View", style = MaterialTheme.typography.headlineSmall)
        Text("⏪ ⏩ on the remote change it from anywhere on the page.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BrowseView.entries.forEach { v ->
                FilterChip(selected = v == current, onClick = { onPick(v) }, modifier = if (v == current) Modifier.focusRequester(first) else Modifier) { Text(v.label) }
            }
        }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    }
}

/** A small button at the end of the tabs bar (Sort, Filters and the like). */
@Composable
internal fun BarButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
        modifier = modifier.height(32.dp),
    ) { Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
}

/**
 * The shade over the focused title's picture: light behind the details at the top (fixed), darker
 * under the posters, so their words and focus read over any picture.
 */
@Composable
internal fun GridShade(fixed: Boolean) {
    val dark = Color.Black
    val brush = if (fixed) androidx.compose.ui.graphics.Brush.verticalGradient(
        0f to dark.copy(alpha = 0.55f), 0.3f to dark.copy(alpha = 0.25f), 0.45f to dark.copy(alpha = 0.55f), 1f to dark.copy(alpha = 0.88f),
    ) else androidx.compose.ui.graphics.Brush.verticalGradient(0f to dark.copy(alpha = 0.6f), 1f to dark.copy(alpha = 0.85f))
    Box(Modifier.fillMaxSize().background(brush))
    if (fixed && !LocalDisplayPrefs.current.tvCaptionBox) Box(
        Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.horizontalGradient(0f to dark.copy(alpha = 0.45f), 0.55f to Color.Transparent)),
    )
}

/** Scrolls just enough to show what took focus whole, with [gap] px to spare above and below (when it fits). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal class WholeInSight(private val gap: Float) : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val room = if (size + 2 * gap <= containerSize) gap else 0f
        return when {
            offset - room < 0f -> offset - room
            offset + size + room > containerSize -> offset + size + room - containerSize
            else -> 0f
        }
    }
}

/** Fades out what scrolls up under the details over its top [by] (rather than a hard edge). */
internal fun Modifier.fadeTop(by: androidx.compose.ui.unit.Dp, scrolled: () -> Boolean = { true }) = graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        // Only once the grid has moved: the first row, and its focus frame, sit whole at the start.
        if (scrolled()) {
            val fade = (by.toPx() / size.height).coerceIn(0f, 1f)
            drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(0f to Color.Transparent, fade to Color.Black), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
        }
    }

/** Lays [this] out [by] wider on each side than its slot: the tabs bar inside the padded grid, at the page's own edge. */
internal fun Modifier.bleed(by: androidx.compose.ui.unit.Dp) = layout { measurable, constraints ->
    val px = by.roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + 2 * px, maxWidth = constraints.maxWidth + 2 * px))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-px, 0) }
}

/** A wide row for the list view: poster, title, facts and overview (as display settings allow). */
@Composable
internal fun ListCard(repository: MediaServer, item: MediaItem, modifier: Modifier, onClick: () -> Unit) {
    // Spoiler-safe: an episode not seen yet is "Episode 5", blurred, without its description.
    @Suppress("NAME_SHADOWING") val item = dev.nitrate.tv.ui.partners.spoilerSafe(item)
    val prefs = LocalDisplayPrefs.current
    // The page's card corners, see-through over the picture behind, framed when focused.
    val square = prefs.tvCardCorners == dev.nitrate.core.pages.CardCorners.SQUARE
    val shape = if (square) androidx.compose.ui.graphics.RectangleShape else RoundedCornerShape(12.dp)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), contentColor = Color.White, focusedContainerColor = Color(0x33FFFFFF), focusedContentColor = Color.White),
        border = ClickableSurfaceDefaults.border(focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.dp, Color.White), shape = shape)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (prefs.tvFocusStyle == dev.nitrate.core.pages.FocusStyle.GROW) 1.02f else 1f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.width(90.dp).aspectRatio(2f / 3f).clip(if (square) androidx.compose.ui.graphics.RectangleShape else RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.08f))) {
                repository.imageUrl(item, ImageKind.PRIMARY, 270)?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().spoilerBlur(item)) }
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
    dev.nitrate.tv.ui.lists.TvDialog(onDismiss, width = 420) {
        Column(
            Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
private fun FilterDialog(options: BrowseOptions, genres: List<String>, available: dev.nitrate.core.model.LibraryFilters, codecs: Boolean, onChange: ((BrowseOptions) -> BrowseOptions) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    dev.nitrate.tv.ui.lists.TvPanel(onDismiss, width = 860) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
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
