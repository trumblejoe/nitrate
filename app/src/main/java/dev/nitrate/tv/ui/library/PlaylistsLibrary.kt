package dev.nitrate.tv.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Card
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.nitrate.tv.data.JellyfinRepository
import dev.nitrate.tv.data.LibrarySort
import dev.nitrate.tv.data.rows.DisplaySettingsStore
import dev.nitrate.tv.data.rows.PlaylistLibraryView
import dev.nitrate.tv.data.rows.PlaylistProgress
import dev.nitrate.tv.data.rows.PlaylistTileImage
import dev.nitrate.tv.ui.components.onPlayKey
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields

private val PagePadding = 58.dp
private const val OTHER_GROUP = "Other"

/** A tag heading and the playlists under it. A playlist with several tags is in each group. */
private data class PlaylistGroup(val name: String, val items: List<BaseItemDto>)

/** Groups by tag, sorted case-insensitively, with untagged playlists last under "Other". */
private fun groupByTag(items: List<BaseItemDto>): List<PlaylistGroup> {
    val byTag = sortedMapOf<String, MutableList<BaseItemDto>>(String.CASE_INSENSITIVE_ORDER)
    val untagged = mutableListOf<BaseItemDto>()
    for (item in items) {
        val tags = item.tags.orEmpty().map(String::trim).filter(String::isNotEmpty).distinctBy(String::lowercase)
        if (tags.isEmpty()) untagged += item else tags.forEach { byTag.getOrPut(it) { mutableListOf() } += item }
    }
    return byTag.map { (tag, list) -> PlaylistGroup(tag, list) } +
        listOfNotNull(untagged.takeIf { it.isNotEmpty() }?.let { PlaylistGroup(OTHER_GROUP, it) })
}

@Composable
fun PlaylistsLibrary(
    repository: JellyfinRepository,
    settings: DisplaySettingsStore,
    library: BaseItemDto,
    onOpen: (BaseItemDto) -> Unit,
    onPlay: (BaseItemDto) -> Unit,
) {
    val userId = repository.userId.orEmpty()
    val pageKey = "library/${library.id}"
    val view by remember(pageKey) { settings.view(userId, pageKey, PlaylistLibraryView()) }
        .collectAsState(PlaylistLibraryView())
    val scope = rememberCoroutineScope()
    val save: (PlaylistLibraryView) -> Unit = { scope.launch { settings.saveView(userId, pageKey, it) } }
    var showOptions by remember { mutableStateOf(false) }

    val vm = viewModel(key = "playlists-${library.id}") {
        LibraryGridViewModel(repository, library, LibrarySort.NAME, extraFields = listOf(ItemFields.TAGS))
    }
    val state by vm.state.collectAsStateWithLifecycle()
    // Grouping needs every playlist, not just the first page.
    LaunchedEffect(view.groupByTag) { if (view.groupByTag) vm.loadAll() }

    val header: @Composable () -> Unit = {
        Row(
            Modifier.padding(top = 24.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(library.name.orEmpty(), style = MaterialTheme.typography.headlineMedium)
            OutlinedButton(onClick = { showOptions = true }) { Text("View options") }
        }
    }

    if (view.groupByTag) {
        GroupedGrid(repository, view, groupByTag(state.items), header, onOpen, onPlay)
    } else {
        val gridState = rememberLazyGridState()
        LaunchedEffect(gridState) {
            snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                .collect { last -> if (last >= state.items.size - 20) vm.loadMore() }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(view.image.columns),
            state = gridState,
            contentPadding = PaddingValues(start = PagePadding, end = PagePadding, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }) { header() }
            items(state.items, key = { it.id }) { PlaylistTile(it, repository, view, onOpen, onPlay) }
        }
    }

    if (showOptions) PlaylistViewOptions(view, save) { showOptions = false }
}

@Composable
private fun GroupedGrid(
    repository: JellyfinRepository,
    view: PlaylistLibraryView,
    groups: List<PlaylistGroup>,
    header: @Composable () -> Unit,
    onOpen: (BaseItemDto) -> Unit,
    onPlay: (BaseItemDto) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf<Pair<String, Int>?>(null) }

    // Grid index of each group's heading: header item, then per group a heading plus its items.
    val headingIndex = remember(groups) {
        var index = 1
        groups.map { group -> index.also { index += 1 + group.items.size } }
    }
    val currentGroup by remember(groups) {
        derivedStateOf {
            val first = gridState.firstVisibleItemIndex
            headingIndex.indexOfLast { it <= first + 1 }.coerceAtLeast(0)
        }
    }
    fun jumpTo(group: Int) {
        val target = headingIndex.getOrNull(group.coerceIn(0, groups.lastIndex)) ?: return
        scope.launch { gridState.animateScrollToItem(target) }
    }

    Row(Modifier.fillMaxSize()) {
        // Side list of groups: shows where you are and jumps on select.
        LazyColumn(
            Modifier.width(200.dp).fillMaxHeight().padding(start = 24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            itemsIndexed(groups, key = { _, g -> g.name }) { i, group ->
                ListItem(
                    selected = i == currentGroup,
                    onClick = { jumpTo(i) },
                    headlineContent = { Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("${group.items.size}") },
                )
            }
        }
        Box(Modifier.weight(1f)) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(view.image.columns),
                state = gridState,
                contentPadding = PaddingValues(start = 24.dp, end = PagePadding, bottom = 72.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                    // Fast-forward / rewind jump between groups.
                    val up = event.type == KeyEventType.KeyUp
                    when (event.key) {
                        Key.MediaFastForward -> { if (up) jumpTo(currentGroup + 1); true }
                        Key.MediaRewind -> { if (up) jumpTo(currentGroup - 1); true }
                        else -> false
                    }
                },
            ) {
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) { header() }
                groupedItems(groups, repository, view, onOpen, onPlay) { group, index -> focused = group to index }
            }
            focused?.let { (name, index) ->
                val size = groups.firstOrNull { it.name == name }?.items?.size ?: 0
                Text(
                    "$name · ${index + 1} / $size",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = PagePadding, bottom = 24.dp),
                )
            }
        }
    }
}

private fun LazyGridScope.groupedItems(
    groups: List<PlaylistGroup>,
    repository: JellyfinRepository,
    view: PlaylistLibraryView,
    onOpen: (BaseItemDto) -> Unit,
    onPlay: (BaseItemDto) -> Unit,
    onFocus: (group: String, index: Int) -> Unit,
) {
    for (group in groups) {
        // Each heading spans the line, so a group always starts on a fresh row.
        item(key = "group-${group.name}", span = { GridItemSpan(maxLineSpan) }) {
            Text(group.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
        }
        items(group.items.size, key = { "${group.name}-${group.items[it].id}" }) { i ->
            val item = group.items[i]
            PlaylistTile(item, repository, view, onOpen, onPlay, Modifier.onFocusChanged { if (it.hasFocus) onFocus(group.name, i) })
        }
    }
}

@Composable
private fun PlaylistTile(
    item: BaseItemDto,
    repository: JellyfinRepository,
    view: PlaylistLibraryView,
    onOpen: (BaseItemDto) -> Unit,
    onPlay: (BaseItemDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    val wide = view.image == PlaylistTileImage.WIDE
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Card(
            onClick = { onOpen(item) },
            modifier = Modifier.fillMaxWidth().aspectRatio(if (wide) 16f / 9f else 1f).onPlayKey { onPlay(item) },
        ) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
                val ownWide = repository.imageUrl(item, ImageType.THUMB, 400) ?: repository.backdropUrl(item)
                val primary = repository.imageUrl(item, ImageType.PRIMARY, 400)
                when {
                    wide && ownWide != null -> TileImage(ownWide)
                    wide && primary != null -> StylizedArt(primary)
                    primary != null -> TileImage(primary)
                    else -> Text(item.name.orEmpty(), Modifier.align(Alignment.Center).padding(12.dp), textAlign = TextAlign.Center)
                }
                if (item.userData?.isFavorite == true) {
                    Text("♥", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.align(Alignment.TopEnd).padding(10.dp))
                }
                if (view.progress == PlaylistProgress.BAR) ProgressBar(item, Modifier.align(Alignment.BottomStart))
            }
        }
        Text(
            item.name.orEmpty(),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp),
        )
        if (view.progress == PlaylistProgress.COUNT) {
            val unwatched = item.userData?.unplayedItemCount
            val text = when {
                unwatched == null -> null
                unwatched == 0 -> "All watched"
                else -> "$unwatched unwatched"
            }
            text?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun TileImage(url: String) {
    AsyncImage(url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
}

/**
 * For playlists without wide art: the square cover, sharp and centred, over a blurred, dimmed
 * copy of itself. Blur needs Android 12+; older devices just get the dimmed copy.
 */
@Composable
private fun StylizedArt(url: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AsyncImage(url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(24.dp))
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
        AsyncImage(
            url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxHeight(0.8f).aspectRatio(1f).clip(RoundedCornerShape(10.dp)),
        )
    }
}

@Composable
private fun ProgressBar(item: BaseItemDto, modifier: Modifier) {
    val fraction = (item.userData?.playedPercentage ?: 0.0).toFloat() / 100f
    if (fraction <= 0f) return
    val accent = MaterialTheme.colorScheme.primary
    Column(modifier.fillMaxWidth()) {
        // Soft glow above the bar so it reads over bright artwork.
        Box(
            Modifier.fillMaxWidth(fraction).height(10.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.35f)))),
        )
        Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(accent))
    }
}

@Composable
private fun PlaylistViewOptions(view: PlaylistLibraryView, onChange: (PlaylistLibraryView) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(460.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(24.dp),
        ) {
            Text("View options", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 8.dp))
            ListItem(
                selected = false,
                onClick = { onChange(view.copy(image = PlaylistTileImage.entries[(view.image.ordinal + 1) % PlaylistTileImage.entries.size])) },
                headlineContent = { Text("Tiles") },
                trailingContent = { Text("${view.image.label}  ›", color = MaterialTheme.colorScheme.primary) },
            )
            ListItem(
                selected = false,
                onClick = { onChange(view.copy(progress = PlaylistProgress.entries[(view.progress.ordinal + 1) % PlaylistProgress.entries.size])) },
                headlineContent = { Text("Progress") },
                trailingContent = { Text("${view.progress.label}  ›", color = MaterialTheme.colorScheme.primary) },
            )
            ListItem(
                selected = false,
                onClick = { onChange(view.copy(groupByTag = !view.groupByTag)) },
                headlineContent = { Text("Group by tag") },
                supportingContent = { Text("Untagged playlists go under \"$OTHER_GROUP\"") },
                trailingContent = { Switch(checked = view.groupByTag, onCheckedChange = null) },
            )
        }
    }
}
