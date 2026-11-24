package dev.nitrate.mobile.ui

import dev.nitrate.core.model.ItemKind
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Close
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.filled.Edit
import dev.nitrate.core.pages.PageKeys
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.layout.widthIn
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
fun BackScaffold(
    title: String,
    onBack: () -> Unit,
    topActions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = topActions,
            )
        },
    ) { padding ->
        // A page of one column of lines doesn't stretch across a tablet: centred, up to 1200 dp.
        ReadableWidth(1200.dp) { content(padding) }
    }
}

/** [content] at most [max] wide, centred: single-column pages on a tablet or wide window. */
@Composable
fun ReadableWidth(max: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = max).fillMaxSize()) { content() }
    }
}

/**
 * A library: Home's look throughout (the artwork-tinted background, a light top bar, pill tabs and
 * Home's cards). Recommended is its rows; All (and the other list tabs) the full list, whose tabs
 * and bar stay at the top or scroll away per [DisplayPrefs.phoneGridLayout].
 */
@Composable
fun LibraryScreen(actions: AppActions, libraryId: String, startTab: Int = 0) {
    val library by produceState<Result<MediaItem>?>(null, libraryId) { value = runCatching { actions.server.getItem(libraryId) } }
    val lib = library?.getOrNull()
    // The page takes its colour from the library's own picture, as Home does from its first artwork.
    val tint by dev.nitrate.mobile.platform.rememberArtworkColor(lib?.let { actions.server.backdropUrl(it, 320) ?: actions.server.imageUrl(it) })
    TintedBackdrop(tint) {
        Column(Modifier.fillMaxSize()) {
            PageTopBar(lib?.name ?: "", onBack = { actions.nav.popBackStack() })
            when {
                library == null -> Loading()
                lib == null -> ErrorMessage(library?.exceptionOrNull()?.message ?: "Couldn't load library")
                lib.collection == CollectionKind.LIVE_TV -> Box(Modifier.fillMaxSize()) { LiveTvScreen(actions) }
                else -> {
                    val hasRecommended = lib.collection == CollectionKind.MOVIES || lib.collection == CollectionKind.TV_SHOWS
                    val music = lib.collection == CollectionKind.MUSIC
                    var tab by rememberSaveable { mutableIntStateOf(startTab) }
                    val tabs: (@Composable () -> Unit)? = if (hasRecommended || music) {
                        { PageTabs(listOf(if (music) "Albums" else "Recommended", if (music) "Artists" else "All"), tab, onSelect = { tab = it }) }
                    } else null
                    // The full list carries the tabs itself (pinned or scrolling with it); the others keep them above.
                    val browsing = !(music && tab == 1) && !(hasRecommended && tab == 0) && lib.collection != CollectionKind.PLAYLISTS
                    if (!browsing) tabs?.invoke()
                    when {
                        music && tab == 1 -> ArtistsGrid(actions, lib)
                        hasRecommended && tab == 0 -> LibraryRows(actions, lib)
                        // Playlists keep their own tile grid; every other library gets sort, filters and A–Z.
                        lib.collection == CollectionKind.PLAYLISTS -> LibraryGrid(actions, lib)
                        else -> BrowseLibrary(actions, lib, header = tabs)
                    }
                }
            }
        }
    }
}

/** Home's kind of top: no bar of its own, a back arrow and the page's name in bold, over the tinted background. */
@Composable
fun PageTopBar(title: String, onBack: () -> Unit, actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = androidx.compose.ui.graphics.Color.White) }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 4.dp))
        actions()
    }
}

/** A page's tabs as pills, like the selected tab in Home's floating bar: accent-tinted when chosen. */
@Composable
fun PageTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val accent = accentColor()
    Row(modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (on) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(20.dp))
                    .background(if (on) accent.copy(alpha = 0.22f) else androidx.compose.ui.graphics.Color.Transparent)
                    .selectable(selected = on, role = androidx.compose.ui.semantics.Role.Tab, onClick = { onSelect(i) })
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun LibraryRows(actions: AppActions, library: MediaItem) {
    val c = actions.container
    val vm = viewModel(key = "rows-${library.id}") { RowsViewModel(PageKeys.library(library.id), { c.server.libraryRows(library) }, c.displaySettings, c.server, c.serverChanges, cache = c.dataCache, loadProgressSkipping = { c.server.libraryRowsProgress(library, it) }) }
    val state by vm.state.collectAsStateWithLifecycle()
    val overrides by vm.overrides.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    if (editing) PageEditor(library.name, vm, actions, libraryId = library.id, onDone = { editing = false })
    when (val s = state) {
        RowsState.Loading -> RowsPlaceholder()
        is RowsState.Error -> ErrorMessage(s.message, vm::refresh)
        is RowsState.Loaded -> {
            val list = androidx.compose.foundation.lazy.rememberLazyListState()
            PrefetchRows(list, s.rows, overrides, actions.server)
            LazyColumn(state = list, contentPadding = PaddingValues(top = 16.dp, bottom = bottomInset(16.dp)), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            items(s.rows, key = { it.key }) { row -> Box(Modifier.animateItem()) { androidx.compose.runtime.CompositionLocalProvider(LocalPagePanel provides remember(vm) { PagePanel(library.name, vm, library.id) }) { CinematicRow(row, overrides[row.key] ?: row.defaultDisplay, actions, onDisplay = { vm.save(row, it) }) } } }
            item(key = "edit") {
                Box(Modifier.fillMaxWidth().padding(bottom = 24.dp), Alignment.Center) {
                    PillButton("Edit this page", Icons.Default.Edit, primary = false, onClick = { editing = true })
                }
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
        contentPadding = screenListPadding(),
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
    val server = actions.server
    val scope = rememberCoroutineScope()
    // Entered from a watch group (its card, or Watch groups): each part has the group's own mark too.
    val marker = rememberGroupMarker(actions)
    GroupMarkQuestion(marker)
    var refresh by remember { mutableIntStateOf(0) }
    // Kept while the page is in the back stack: coming back shows it at once, then refreshes quietly.
    var playlist by retained<MediaItem?>("playlist/$playlistId") { null }
    var items by retained<List<MediaItem>?>("playlist-items/$playlistId") { null }
    // A video playlist's whole run (shows put in whole as their episodes), for where the user is.
    var entries by retained<List<MediaItem>?>("playlist-entries/$playlistId") { null }
    LaunchedEffect(playlistId, refresh) {
        runCatching { server.getItem(playlistId) }.getOrNull()?.let { playlist = it }
        items = runCatching { server.playlistItems(playlistId) }.getOrElse { items ?: emptyList() }
        if (items.orEmpty().any { it.mediaKind != MediaKind.AUDIO }) {
            val playing = actions.container.playlists
            if (entries == null) entries = playing.cached(playlistId)
            entries = runCatching { playing.entries(playlistId, fresherThanMs = if (refresh > 0) 0 else dev.nitrate.android.playlists.PlaylistPlaying.KEEP_MS) }.getOrNull() ?: entries
        }
    }
    var menuFor by remember { mutableStateOf<MediaItem?>(null) }
    var editing by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun change(block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }.onFailure { error = it.message ?: "Couldn't change the playlist" }
            refresh++
        }
    }
    BackScaffold(
        playlist?.name ?: "",
        onBack = { actions.nav.popBackStack() },
    ) { padding ->
        val list = items
        if (list == null) {
            ListPlaceholder(Modifier.padding(padding))
            return@BackScaffold
        }
        // A video playlist plays as itself: from where it was left, carrying on in its order
        // (Up Next, Continue Watching, Home) and resuming rather than starting over.
        val video = list.any { it.mediaKind != MediaKind.AUDIO }
        val storyOrder = playlistId in LocalDisplayPrefs.current.recapPlaylists
        fun playFrom(item: MediaItem) {
            scope.launch {
                actions.container.playlists.startedFrom(playlistId, item.id)
                val pl = playlist
                if (storyOrder && pl != null && actions.container.recapBefore(item, pl) != null) actions.nav.navigate(RecapRoute)
                else actions.play(item, false)
            }
        }
        // Where the user is: how much is watched and what's next (carrying on from the entry played most recently, in any app).
        val all = entries ?: list
        val status = if (video) dev.nitrate.core.playlists.PlaylistPlaces.progress(all) else null
        val placeAt = if (video) dev.nitrate.core.playlists.PlaylistPlaces.lastPlayed(all) else null
        val videoNext = if (video) dev.nitrate.core.playlists.PlaylistPlaces.nextUp(all) else null
        // The playlist's own entry holding what's next (a whole show or season stands for its episodes).
        val nextEntry = videoNext?.let { n -> list.indexOfFirst { it.id == n.id || it.id == n.seriesId || it.id == n.seasonId } }?.takeIf { it >= 0 }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(ScreenPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    status?.takeIf { it.total > 0 }?.let { s ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { s.share }, modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                                drawStopIndicator = {},
                            )
                            Text(s.line, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    // As on a details page: one big button for what you most likely want, round ones for the rest, More for editing.
                    val prefs = LocalDisplayPrefs.current
                    val inOrder = playlist != null && playlist!!.id in prefs.recapPlaylists
                    // A video playlist carries on from where it was left; in story order, from its first unwatched.
                    val next = if (video) videoNext else list.firstOrNull { !it.user.played }
                    val notTheStart = if (video) placeAt != null || next?.id != all.firstOrNull()?.id else next != list.firstOrNull()
                    val canContinue = !editing && playlist != null && next != null && notTheStart && (video || inOrder)
                    fun fromStart() { if (video) playFrom(list.first()) else actions.playItems(list) }
                    when {
                        editing -> PillButton("Done editing", Icons.Default.Check, primary = true, onClick = { editing = false }, modifier = Modifier.fillMaxWidth())
                        canContinue -> PillButton("Continue: ${dev.nitrate.core.recap.Recaps.label(next!!)}", Icons.Default.PlayArrow, primary = true, modifier = Modifier.fillMaxWidth(), onClick = {
                            if (video) playFrom(next) else scope.launch {
                                val recap = actions.container.recapBefore(next, playlist)
                                if (recap != null) actions.nav.navigate(RecapRoute)
                                else actions.playQueue(list.dropWhile { it.id != next.id }.map { it.id })
                            }
                        })
                        list.isNotEmpty() -> PillButton(if (video) "Play from the start" else "Play all", Icons.Default.PlayArrow, primary = true, onClick = ::fromStart, modifier = Modifier.fillMaxWidth())
                    }
                    if (!editing) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (canContinue) RoundAction(Icons.Default.Refresh, "From the start", onClick = ::fromStart)
                        if (list.isNotEmpty()) RoundAction(NitrateIcons.Shuffle, "Shuffle", onClick = { actions.playItems(list, shuffle = true) })
                        var more by remember { mutableStateOf(false) }
                        RoundAction(Icons.Default.MoreVert, "More", onClick = { more = true })
                        if (more) PlaylistMoreSheet(
                            storyOrder = inOrder,
                            onStoryOrder = { on -> playlist?.let { pl -> scope.launch { actions.container.setRecapPlaylist(pl.id, on) } } },
                            onEdit = { editing = true },
                            onRename = { renaming = true },
                            onDelete = { deleting = true },
                            onDismiss = { more = false },
                        )
                    }
                    if (!editing && list.isNotEmpty() && list.all { it.kind == ItemKind.AUDIO }) MusicDownloadButton(actions, list, "playlist")
                }
            }
            itemsIndexed(list, key = { i, it -> it.playlistEntryId ?: "$i-${it.id}" }) { index, item ->
                val audio = item.mediaKind == MediaKind.AUDIO
                val thumb = if (audio) server.imageUrl(item, ImageKind.PRIMARY, 120) else server.cardImage(item, CardShape.WIDE, 160)
                val entry = item.playlistEntryId
                ListItem(
                    // A tap plays from there; a long press offers the rest (a video playlist's entries).
                    modifier = Modifier.combinedClickable(
                        enabled = !editing,
                        onLongClick = if (!audio && video) ({ menuFor = item }) else null,
                    ) { if (!audio && video) playFrom(item) else actions.playQueue(list.drop(index).map { it.id }) },
                    leadingContent = {
                        Box(Modifier.height(48.dp).aspectRatio(if (audio) 1f else 16f / 9f).clip(RoundedCornerShape(6.dp))) {
                            if (thumb != null) AsyncImage(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    },
                    headlineContent = { Text(item.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(item.episodeLabel ?: item.productionYear?.toString().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        if (editing && entry != null) {
                            Row {
                                IconButton(onClick = { change { server.movePlaylistItem(playlistId, entry, index - 1) } }, enabled = index > 0) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                                IconButton(onClick = { change { server.movePlaylistItem(playlistId, entry, index + 1) } }, enabled = index < list.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
                                // Easy mode asks first.
                                var asking by remember { mutableStateOf(false) }
                                val easy = LocalDisplayPrefs.current.easyMode
                                IconButton(onClick = { if (easy) asking = true else change { server.removeFromPlaylist(playlistId, listOf(entry)) } }) { Icon(Icons.Default.Close, "Remove") }
                                if (asking) androidx.compose.material3.AlertDialog(
                                    onDismissRequest = { asking = false },
                                    title = { Text("Remove from the playlist?") },
                                    text = { Text(item.displayTitle) },
                                    confirmButton = { androidx.compose.material3.TextButton(onClick = { asking = false; change { server.removeFromPlaylist(playlistId, listOf(entry)) } }) { Text("Remove") } },
                                    dismissButton = { androidx.compose.material3.TextButton(onClick = { asking = false }) { Text("Cancel") } },
                                )
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (index == nextEntry) Text(
                                    "NEXT", style = MaterialTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color.Black,
                                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(androidx.compose.ui.graphics.Color.White).padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                                if (item.user.played && LocalDisplayPrefs.current.watchedMarks) Icon(Icons.Default.Check, "Watched", tint = MaterialTheme.colorScheme.primary)
                                GroupMarkToggle(actions, item, marker)
                                item.runtimeMs?.takeIf { LocalDisplayPrefs.current.runtime }?.let { Text(formatRuntime(it), style = MaterialTheme.typography.labelMedium) }
                                // The visible way to what a long press offers (play from here, mark watched up to here…).
                                if (!audio && video) IconButton(onClick = { menuFor = item }) { Icon(Icons.Default.MoreVert, "More for ${item.spokenTitle}") }
                            }
                        }
                    },
                )
            }
        }
    }
    menuFor?.let { item ->
        val playing = actions.container.playlists
        ChoiceSheet(item.displayTitle, onDismiss = { menuFor = null }) { choice ->
            choice("Play from here", null) { scope.launch { playing.startedFrom(playlistId, item.id); actions.play(item, false) } }
            choice("Open it", null) { actions.open(item) }
            choice("Mark watched up to here", "Everything before it too") { change { playing.markUpTo(playlistId, item.id, true) } }
            choice("Mark unwatched from here on", "Everything after it too") { change { playing.markUpTo(playlistId, item.id, false) } }
        }
    }
    if (renaming) {
        var name by remember { mutableStateOf(playlist?.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename playlist") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { renaming = false; change { server.renamePlaylist(playlistId, name.trim()) } }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete “${playlist?.name}”?") },
            text = { Text("The playlist goes; the movies and episodes in it stay.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    scope.launch {
                        runCatching { server.deleteList(playlistId) }
                            .onSuccess { actions.nav.popBackStack() }
                            .onFailure { error = it.message ?: "Couldn't delete it" }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

/** A playlist's More: editing it, story order, renaming and deleting. */
@Composable
private fun PlaylistMoreSheet(
    storyOrder: Boolean,
    onStoryOrder: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    ChoiceSheet("Playlist", onDismiss) { choice ->
        choice("Edit order", "Move or remove what's in it", onEdit)
        choice(if (storyOrder) "In story order: on" else "In story order: off", "A recap of last time when you come back after a break") { onStoryOrder(!storyOrder) }
        choice("Rename", null, onRename)
        choice("Delete playlist", "What's in it stays", onDelete)
    }
}
