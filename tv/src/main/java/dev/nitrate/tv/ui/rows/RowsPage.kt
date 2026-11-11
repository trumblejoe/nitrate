package dev.nitrate.tv.ui.rows

import dev.nitrate.tv.ui.components.LocalDisplayPrefs
import dev.nitrate.core.pages.isSpoiler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.nitrate.core.MediaServer
import dev.nitrate.core.rows.MediaRow
import dev.nitrate.core.rows.RowDisplay
import dev.nitrate.core.rows.RowStyle
import dev.nitrate.tv.ui.components.Backdrop
import dev.nitrate.tv.ui.components.ItemMetaLine
import dev.nitrate.core.model.MediaItem

/** What rows can ask their page to do. */
class RowActions(
    val onFocus: (MediaItem) -> Unit,
    val onOpen: (MediaItem) -> Unit,
    val onPlay: (MediaItem) -> Unit,
    val onEditRow: (MediaRow) -> Unit,
)

/**
 * A vertical page of rows, shared by Home and library pages. Draws the focused item's
 * backdrop and summary above the rows, except while a showcase row has taken over the screen.
 */
@Composable
fun RowsPage(
    rows: List<MediaRow>,
    overrides: Map<String, RowDisplay>,
    repository: MediaServer,
    onOpen: (MediaItem) -> Unit,
    onPlay: (MediaItem) -> Unit,
    onSaveDisplay: (MediaRow, RowDisplay) -> Unit,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    footer: LazyListScope.() -> Unit = {},
    /** Opens the page editor (order, hide, rename, own rows); without it, only row looks can change. */
    onEditPage: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf<MediaItem?>(null) }
    var activeShowcase by remember { mutableStateOf<String?>(null) }
    val showcaseIndex = remember { mutableStateMapOf<String, Int>() }
    var editing by remember { mutableStateOf<MediaRow?>(null) }
    var listingRows by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val actions = remember(onOpen, onPlay) {
        RowActions(
            onFocus = { focused = it },
            onOpen = onOpen,
            onPlay = { if (it.isPlayable) onPlay(it) else onOpen(it) },
            onEditRow = { editing = it },
        )
    }

    // Header rows (before the media rows) shift row indices in the LazyColumn.
    val headerCount = if (header != null) 1 else 0
    LaunchedEffect(activeShowcase) {
        val key = activeShowcase ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it.key == key }
        if (index >= 0) listState.animateScrollToItem(index + headerCount)
    }

    Box(modifier.fillMaxSize()) {
        if (activeShowcase == null) Backdrop(focused?.let(repository::backdropUrl), Modifier.fillMaxSize())

        Column(Modifier.fillMaxSize()) {
            AnimatedVisibility(visible = activeShowcase == null) {
                FocusedSummary(focused, Modifier.padding(start = RowPadding, top = 36.dp, end = RowPadding).height(190.dp))
            }
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (header != null) item(key = "page-header") { header() }
                itemsIndexed(rows, key = { _, row -> row.key }) { _, row ->
                    val display = overrides[row.key] ?: row.defaultDisplay
                    val hidden = activeShowcase != null && activeShowcase != row.key
                    if (display.style == RowStyle.SHOWCASE) {
                        val active = activeShowcase == row.key
                        ShowcaseRow(
                            row = row,
                            display = display,
                            repository = repository,
                            actions = actions,
                            index = showcaseIndex[row.key] ?: 0,
                            onIndexChange = { showcaseIndex[row.key] = it },
                            active = active,
                            onActiveChange = { isActive ->
                                if (isActive) activeShowcase = row.key
                                else if (activeShowcase == row.key) activeShowcase = null
                            },
                            modifier = Modifier.alpha(if (hidden) 0f else 1f)
                                .then(if (active) Modifier.fillParentMaxHeight() else Modifier),
                        )
                    } else {
                        Box(Modifier.alpha(if (hidden) 0f else 1f)) {
                            when (display.style) {
                                RowStyle.FILM_STRIP -> FilmStripRow(row, repository, actions)
                                RowStyle.LIGHTNING -> LightningRow(row, repository, actions)
                                RowStyle.CRESSIDA -> CressidaRow(row, repository, actions)
                                else -> CardRow(row, display, repository, actions)
                            }
                        }
                    }
                }
                item(key = "customize-rows") {
                    OutlinedButton(onClick = { onEditPage?.invoke() ?: run { listingRows = true } }, modifier = Modifier.padding(start = RowPadding)) {
                        Text(if (onEditPage != null) "Edit page" else "Customize rows")
                    }
                }
                footer()
            }
        }
    }

    editing?.let { row ->
        RowSettingsDialog(
            row = row,
            display = overrides[row.key] ?: row.defaultDisplay,
            onChange = { onSaveDisplay(row, it) },
            // Back returns to the row list if that is where the user came from.
            onDismiss = { editing = null },
        )
    }
    if (listingRows && editing == null) {
        RowListDialog(rows, overrides, onPick = { editing = it }, onDismiss = { listingRows = false })
    }
}

@Composable
private fun FocusedSummary(item: MediaItem?, modifier: Modifier) {
    Column(modifier.fillMaxWidth(0.55f), verticalArrangement = Arrangement.Bottom) {
        if (item == null) return@Column
        Text(item.displayTitle, style = MaterialTheme.typography.displaySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        item.episodeLabel?.let { Text(it, style = MaterialTheme.typography.titleMedium, maxLines = 1) }
        ItemMetaLine(item)
        item.overview?.takeIf { LocalDisplayPrefs.current.overview && !LocalDisplayPrefs.current.isSpoiler(item) }?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
