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

/**
 * A fixed list of titles in one of the grid views (posters, artwork or list) the way the All tab
 * shows them, so every library tab looks alike: the page theme with the focused title's picture
 * behind, Home's cards, and per the page's [dev.nitrate.core.pages.GridLayout] either the focused
 * title's details held under [bar] (Fixed) or [bar] scrolling away with the posters (Scrolling).
 * [bar] is the whole tabs bar (the tabs and the buttons at its end); [upTo] is where ▲ from the
 * first row goes (the bar's first button).
 */
private val PagePadding = 58.dp

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun TitleGrid(
    items: List<MediaItem>?,
    view: BrowseView,
    columns: Int,
    repository: MediaServer,
    label: String,
    bar: @Composable () -> Unit,
    upTo: FocusRequester,
    onOpen: (MediaItem) -> Unit,
    onPlay: (MediaItem) -> Unit,
    music: Boolean = false,
    empty: String = "Nothing here yet.",
) {
    val prefs = LocalDisplayPrefs.current
    val fixed = prefs.tvGridLayout == dev.nitrate.core.pages.GridLayout.FIXED
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var focusedIndex by remember { mutableStateOf(-1) }
    var lastFocused by remember { mutableStateOf(-1) }
    val list = items.orEmpty()
    val leading = if (fixed) 0 else 1
    // ▼ from the bar: into the grid at the title last used, else the first in sight.
    val gridEntry = remember { FocusRequester() }
    val count by androidx.compose.runtime.rememberUpdatedState(list.size)
    val entryIndex by remember(leading) {
        androidx.compose.runtime.derivedStateOf {
            val inSight = grid.layoutInfo.visibleItemsInfo.map { it.index - leading }.filter { it >= 0 && it < count }
            if (lastFocused in inSight) lastFocused else inSight.firstOrNull() ?: 0
        }
    }
    val wrappedBar: @Composable () -> Unit = {
        // ▼ anywhere on the bar (a tab or a button): into the grid (taken as a key: the tabs keep their own ▼).
        Box(Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
            if (e.key == Key.DirectionDown && list.isNotEmpty()) {
                if (e.type == KeyEventType.KeyDown) runCatching { gridEntry.requestFocus() }.getOrDefault(false) else true
            } else false
        }) { bar() }
    }
    val shownIndex = when { focusedIndex >= 0 -> focusedIndex; lastFocused >= 0 -> lastFocused; else -> 0 }
    val shown = list.getOrNull(shownIndex)
    val backdropUrl = shown?.let { repository.backdropUrl(it) }
    Box(Modifier.fillMaxSize()) {
        dev.nitrate.tv.ui.effects.PageBackdropSlot(backdropUrl, clean = true)
        val backdropChoice = dev.nitrate.tv.ui.pages.LocalLayoutPage.current?.let { prefs.pageBackdrops[it] } ?: dev.nitrate.core.effects.PageBackdrop.ARTWORK
        if (backdropChoice.kind == dev.nitrate.core.effects.PageBackdrop.Kind.ARTWORK && backdropUrl != null) GridShade(fixed)
        Column(Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            when {
                // ▲ from the first row: to the bar's buttons (scrolled back into sight first), not whichever tab is above.
                e.key == Key.DirectionUp && e.type == KeyEventType.KeyDown && focusedIndex in 0 until columns -> {
                    if (e.nativeKeyEvent.repeatCount == 0) scope.launch {
                        if (!fixed) runCatching { grid.scrollToItem(0) }
                        repeat(10) { if (runCatching { upTo.requestFocus() }.getOrDefault(false)) return@launch; kotlinx.coroutines.delay(32) }
                    }
                    true
                }
                // ▼ on the last line: stay put.
                e.key == Key.DirectionDown && e.type == KeyEventType.KeyDown && focusedIndex >= 0 && focusedIndex / columns == (list.size - 1) / columns -> true
                else -> false
            }
        }) {
            if (fixed) {
                wrappedBar()
                val tall = prefs.tvCaptionSize == dev.nitrate.core.pages.CaptionSize.NORMAL
                Box(Modifier.fillMaxWidth().height(if (tall) 156.dp else 112.dp).padding(start = PagePadding, end = PagePadding, bottom = 10.dp), contentAlignment = Alignment.BottomStart) {
                    if (shown != null) dev.nitrate.tv.ui.components.ArtCaption(
                        shown, label = "$label · ${shownIndex + 1} / ${list.size}", end = false,
                        logoUrl = dev.nitrate.tv.ui.components.captionLogo(repository, shown),
                    )
                }
            }
            val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 28.dp.toPx() }
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides remember(gap) { WholeInSight(gap) }) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = grid,
                    contentPadding = PaddingValues(start = PagePadding, end = PagePadding, top = if (fixed) 10.dp else 0.dp, bottom = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(prefs.tvCardSpacing.dp.dp),
                    verticalArrangement = Arrangement.spacedBy((prefs.tvCardSpacing.dp + 4).dp),
                    modifier = Modifier.weight(1f).fillMaxWidth().then(if (fixed) Modifier.fadeTop(18.dp) { grid.canScrollBackward } else Modifier),
                ) {
                    if (!fixed) item(key = "bar", span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.bleed(PagePadding)) { wrappedBar() } }
                    when {
                        items == null -> item(span = { GridItemSpan(maxLineSpan) }) { dev.nitrate.tv.ui.components.GridPlaceholder() }
                        list.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) { Text(empty, style = MaterialTheme.typography.titleMedium) }
                        else -> items(list.size, key = { list[it].id }) { i ->
                            val item = list[i]
                            val focus = (if (i == entryIndex) Modifier.focusRequester(gridEntry) else Modifier)
                                .onFocusChanged { if (it.hasFocus) { focusedIndex = i; lastFocused = i } else if (focusedIndex == i) focusedIndex = -1 }
                            if (view == BrowseView.LIST) ListCard(repository, item, focus) { onOpen(item) }
                            else {
                                val artwork = view == BrowseView.ARTWORK && !music
                                // As the All tab: posters bare under the details (Fixed); artwork and album covers with their words.
                                val words = artwork || music
                                MediaCard(
                                    item = item,
                                    title = item.displayTitle.takeIf { words || !fixed },
                                    imageUrl = if (artwork) repository.imageUrl(item, ImageKind.THUMB, 360) ?: repository.backdropUrl(item, 640) else repository.imageUrl(item, ImageKind.PRIMARY, 360),
                                    width = null,
                                    aspectRatio = if (music) 1f else if (artwork) 16f / 9f else 2f / 3f,
                                    subtitle = (if (music) item.albumArtist else item.productionYear?.takeIf { prefs.year }?.toString()).takeIf { words || !fixed },
                                    titleOnFocus = !words,
                                    onClick = { onOpen(item) },
                                    onPlay = { if (item.isPlayable) onPlay(item) else onOpen(item) },
                                    modifier = focus,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
