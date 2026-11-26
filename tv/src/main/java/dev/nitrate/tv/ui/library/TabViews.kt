package dev.nitrate.tv.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusGroup
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
 * A page of rows (Home's tabs, a library's Genres, Studios, Decades or own tab) shown as its rows
 * ([rowsContent], each row with its own look) or its titles together in one of the All tab's views,
 * chosen in Customize → View ([dev.nitrate.tv.ui.pages.ReportTabView]); saved per page under [viewKey].
 * [upTo] is the chosen tab: ▲ from a grid's first row goes there.
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
    /** What the focused title's details call the list ("Movies · 3 / 40"). */
    label: String = "",
    /** The tab's name, for Customize → View. */
    tabName: String = "",
    upTo: FocusRequester? = null,
    rowsContent: @Composable (header: @Composable () -> Unit) -> Unit,
) {
    val user = repository.userId
    val look by remember(user, viewKey) {
        user?.let { displaySettings.view(it, viewKey, TabView()) } ?: kotlinx.coroutines.flow.flowOf(TabView())
    }.collectAsStateWithLifecycle(TabView())
    val scope = rememberCoroutineScope()
    fun save(next: TabView) { val u = user ?: return; scope.launch { displaySettings.saveView(u, viewKey, next) } }
    val columns = look.columnsFor(music)
    dev.nitrate.tv.ui.pages.ReportTabView(
        dev.nitrate.tv.ui.pages.TabViewChoice(
            tabName, rows = look.rows, view = look.view, columns = columns,
            onRows = { save(look.copy(rows = true)) },
            onView = { v -> save(look.copy(rows = false, view = v)) },
            onColumns = { n -> save(look.copy(columns = look.columns + (look.view to n))) },
        ),
        refocus = upTo,
    )
    if (look.rows) {
        rowsContent(header)
    } else {
        // Spoiler-safe as the rows themselves show them.
        val items = dev.nitrate.tv.ui.partners.spoilerSafe(remember(rows) { rows.flatMap { it.items }.distinctBy { it.id } })
        TitlesInView(items, look.view, columns, repository, header, onOpen, onPlay, music, upTo = upTo, label = label)
    }
}

/**
 * [items] in one of the All tab's views under [header]: a grid ([columns] across: posters, artwork
 * or a list) or a show view filling the page.
 */
@Composable
internal fun TitlesInView(
    items: List<MediaItem>?,
    view: BrowseView,
    columns: Int,
    repository: MediaServer,
    header: @Composable () -> Unit,
    onOpen: (MediaItem) -> Unit,
    onPlay: (MediaItem) -> Unit,
    music: Boolean = false,
    empty: String = "Nothing here yet.",
    /** Where ▲ from the grid's first row goes (the chosen tab); else into the tabs. */
    upTo: FocusRequester? = null,
    label: String = "",
) {
    if (view.isShow) {
        Column(Modifier.fillMaxSize().padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            header()
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
    // As the All tab: the page theme and the focused title behind, Home's cards, Fixed or Scrolling grid.
    val target = upTo ?: remember { FocusRequester() }
    TitleGrid(
        items, view, columns, repository, label,
        bar = {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().then(if (upTo == null) Modifier.focusRequester(target).focusGroup() else Modifier)) { header() }
        },
        upTo = target, onOpen = onOpen, onPlay = onPlay, music = music, empty = empty,
    )
}
