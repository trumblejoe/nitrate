package dev.nitrate.tv.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import dev.nitrate.android.storage.DisplaySettingsStore
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.ImageKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.pages.BrowseView
import dev.nitrate.core.pages.TabView
import dev.nitrate.core.rows.MediaRow
import dev.nitrate.tv.ui.components.LocalDisplayPrefs
import dev.nitrate.tv.ui.components.MediaCard
import kotlinx.coroutines.launch

private val PagePadding = 58.dp

/**
 * A page of rows (Home's tabs, a library's Recommended, Genres, Studios, Decades or own tab) shown as
 * its rows ([rowsContent], each row with its own look) or, chosen with the View button beside the
 * tabs, its titles together in one of the All tab's views; saved per page under [viewKey]. In rows
 * the button joins [header], which [rowsContent] places as the page's tabs.
 */
@Composable
fun RowsOrTitles(
    viewKey: String,
    rows: List<MediaRow>,
    repository: MediaServer,
    displaySettings: DisplaySettingsStore,
    header: @Composable () -> Unit,
    onOpen: (MediaItem) -> Unit,
    onPlay: (MediaItem) -> Unit,
    music: Boolean = false,
    rowsContent: @Composable (header: @Composable () -> Unit) -> Unit,
) {
    val user = repository.userId
    val look by remember(user, viewKey) {
        user?.let { displaySettings.view(it, viewKey, TabView()) } ?: kotlinx.coroutines.flow.flowOf(TabView())
    }.collectAsStateWithLifecycle(TabView())
    val scope = rememberCoroutineScope()
    fun save(next: TabView) { val u = user ?: return; scope.launch { displaySettings.saveView(u, viewKey, next) } }
    var choosing by remember { mutableStateOf(false) }
    val viewButton = remember { FocusRequester() }
    var changed by remember { mutableStateOf(0) }
    // Rows and a view are laid out differently: focus goes back to the View button after a change.
    // (Asked again until it has it: a request made before the new layout is placed can come to nothing.)
    var buttonFocused by remember { mutableStateOf(false) }
    // Keyed on the look too: the choice is saved and comes back a moment later, swapping the layout
    // (and the button) again after focus first landed.
    LaunchedEffect(changed, look.rows, look.view) {
        if (changed == 0) return@LaunchedEffect
        buttonFocused = false
        repeat(40) {
            kotlinx.coroutines.android.awaitFrame()
            if (buttonFocused) return@LaunchedEffect
            runCatching { viewButton.requestFocus() }
            kotlinx.coroutines.delay(30)
        }
    }
    val columns = look.columnsFor(music)
    val button: @Composable () -> Unit = {
        BarButton(
            if (look.rows) "View: Rows" else "View: ${look.view.label}" + if (look.view.columnChoices.isEmpty()) "" else " · $columns",
            Modifier.focusRequester(viewButton).onFocusChanged { buttonFocused = it.isFocused },
        ) { choosing = true }
    }
    if (choosing) ViewDialog(
        look.view, columns,
        onPick = { v -> save(look.copy(rows = false, view = v)); choosing = false; changed++ },
        onColumns = { n -> save(look.copy(columns = look.columns + (look.view to n))) },
        onDismiss = { choosing = false; changed++ },
        rows = look.rows,
        onRows = { save(look.copy(rows = true)); choosing = false; changed++ },
    )
    if (look.rows) {
        rowsContent { Row(verticalAlignment = Alignment.CenterVertically) { header(); Row(Modifier.padding(end = PagePadding)) { button() } } }
    } else {
        val items = remember(rows) { rows.flatMap { it.items }.distinctBy { it.id } }
        TitlesInView(items, look.view, columns, repository, header, button, onOpen, onPlay, music)
    }
}

/**
 * [items] in one of the All tab's views under [header] and [bar]: a grid ([columns] across: posters,
 * artwork or a list) or a show view filling the page.
 */
@Composable
internal fun TitlesInView(
    items: List<MediaItem>?,
    view: BrowseView,
    columns: Int,
    repository: MediaServer,
    header: @Composable () -> Unit,
    bar: @Composable () -> Unit,
    onOpen: (MediaItem) -> Unit,
    onPlay: (MediaItem) -> Unit,
    music: Boolean = false,
    empty: String = "Nothing here yet.",
) {
    if (view.tvOnly) {
        Column(Modifier.fillMaxSize().padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            header()
            Row(Modifier.padding(start = PagePadding)) { bar() }
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = PagePadding)) {
                when {
                    items == null -> dev.nitrate.tv.ui.components.GridPlaceholder()
                    items.isEmpty() -> Text(empty)
                    else -> {
                        val args = ShowArgs(items, repository, music, titleOf = { it.name }, onOpen = onOpen, onPlay = { if (it.isPlayable) onPlay(it) else onOpen(it) }, onNeedMore = {}, total = items.size)
                        if (view == BrowseView.BOUNCE) BounceBox(args, Modifier.weight(1f).fillMaxWidth())
                        else ShowView(view, args, Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(start = PagePadding, end = PagePadding, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { header() }
        item(span = { GridItemSpan(maxLineSpan) }) { bar() }
        when {
            items == null -> item(span = { GridItemSpan(maxLineSpan) }) { dev.nitrate.tv.ui.components.GridPlaceholder() }
            items.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) { Text(empty) }
            else -> items(items, key = { it.id }) { item ->
                val artwork = view == BrowseView.ARTWORK && !music
                val list = view == BrowseView.LIST
                MediaCard(
                    item = item,
                    imageUrl = when {
                        list || artwork -> repository.imageUrl(item, ImageKind.THUMB, 360) ?: repository.backdropUrl(item, 640) ?: repository.imageUrl(item, ImageKind.PRIMARY, 360)
                        else -> repository.imageUrl(item, ImageKind.PRIMARY, 360)
                    },
                    width = null,
                    aspectRatio = if (music) 1f else if (list || artwork) 16f / 9f else 2f / 3f,
                    title = item.name,
                    subtitle = if (list) item.overview?.take(90) else item.productionYear?.takeIf { LocalDisplayPrefs.current.year }?.toString(),
                    onClick = { onOpen(item) },
                    onPlay = { if (item.isPlayable) onPlay(item) else onOpen(item) },
                )
            }
        }
    }
}
