package dev.nitrate.tv.ui.rows

import kotlinx.coroutines.launch
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import dev.nitrate.tv.ui.components.LocalDisplayPrefs
import dev.nitrate.core.pages.hidesDescription
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
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
import dev.nitrate.core.rows.isShowcase
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    PrefetchRows(listState, rows, overrides, repository)

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

    // "Row: style" for a moment after Fast-forward/Rewind changes one.
    var styleNote by remember { mutableStateOf<Pair<String, Long>?>(null) }
    LaunchedEffect(styleNote) { if (styleNote != null) { kotlinx.coroutines.delay(2_500); styleNote = null } }
    Box(modifier.fillMaxSize()) {
        if (activeShowcase == null) Backdrop(focused?.let(repository::backdropUrl), Modifier.fillMaxSize())
        androidx.compose.animation.AnimatedVisibility(
            visible = styleNote != null, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = RowPadding).zIndex(2f),
        ) {
            Text(
                (styleNote?.first ?: "") + "   ◀◀ ▶▶",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }

        Column(Modifier.fillMaxSize()) {
            AnimatedVisibility(visible = activeShowcase == null) {
                // A clear gap below, so the first row's title never runs into the description.
                // Just tall enough for its title, facts and two lines (it took nearly half the screen).
                FocusedSummary(focused, Modifier.padding(start = RowPadding, top = 20.dp, end = RowPadding, bottom = 14.dp).height(148.dp))
            }
            // TVs scroll focused content to a fixed "pivot", which pushes a tall row's title off the
            // top: up and down, scroll just enough to show the whole row; rows keep the TV's own way.
            val rowsScroll = androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides WholeRowInView) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (header != null) item(key = "page-header") { header() }
                itemsIndexed(rows, key = { _, row -> row.key }) { _, row ->
                    // Rows added or removed by a reload slide and fade rather than jump.
                    // Moving into a row scrolls the whole row into view, its title included (a tall
                    // row otherwise brings only the focused card in, leaving its title out of sight).
                    val wholeRow = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
                    val rowScope = androidx.compose.runtime.rememberCoroutineScope()
                    // Changing a row's style swaps its cards for others: focus waits on a holder in the
                    // row meanwhile (else it falls to the menu), then goes into the new style.
                    val holder = remember { androidx.compose.ui.focus.FocusRequester() }
                    val content = remember { androidx.compose.ui.focus.FocusRequester() }
                    var holding by remember { mutableStateOf(false) }
                    androidx.compose.foundation.layout.Box(
                        Modifier.animateItem()
                            .bringIntoViewRequester(wholeRow)
                            .onFocusChanged { if (it.hasFocus) rowScope.launch { wholeRow.bringIntoView() } }
                            // Fast-forward / Rewind on a row: its next / previous artwork style, there and then.
                            .onPreviewKeyEvent { e ->
                                val by = when (e.key) { Key.MediaFastForward -> 1; Key.MediaRewind -> -1; else -> 0 }
                                if (by == 0) return@onPreviewKeyEvent false
                                if (e.type == KeyEventType.KeyDown) {
                                    val now = overrides[row.key] ?: row.defaultDisplay
                                    val next = now.copy(style = now.style.step(by))
                                    if (activeShowcase == row.key) activeShowcase = null
                                    holding = true
                                    runCatching { holder.requestFocus() }
                                    onSaveDisplay(row, next)
                                    styleNote = "${row.title}: ${next.style.label}" to System.nanoTime()
                                }
                                true
                            },
                    ) {
                        androidx.compose.foundation.layout.Box(
                            Modifier.size(1.dp).focusRequester(holder)
                                .focusProperties { canFocus = holding }.focusable(),
                        )
                        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides rowsScroll) {
                        val display = overrides[row.key] ?: row.defaultDisplay
                        // The new style is on screen: into it, and the whole row back in view at its new height.
                        LaunchedEffect(display.style) {
                            if (!holding) return@LaunchedEffect
                            kotlinx.coroutines.delay(80)
                            runCatching { content.requestFocus() }
                            holding = false
                            kotlinx.coroutines.delay(60)
                            wholeRow.bringIntoView()
                        }
                        val prefs = LocalDisplayPrefs.current
                        val fx = dev.nitrate.tv.ui.effects.rememberCardFx(display, prefs.effects, prefs.easyMode)
                        val actions = if (fx != null && !display.style.isShowcase) fx.wrap(actions) else actions
                        androidx.compose.runtime.CompositionLocalProvider(dev.nitrate.tv.ui.effects.LocalCardFx provides fx) {
                        androidx.compose.foundation.layout.Box(Modifier.focusRequester(content).focusGroup()) {
                        val hidden = activeShowcase != null && activeShowcase != row.key
                        if (display.style.isShowcase) {
                            val active = activeShowcase == row.key
                            // Full Showcase fills the screen even before it has focus.
                            val full = display.style == RowStyle.FULL_SHOWCASE
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
                                alwaysExpanded = full,
                                modifier = Modifier.alpha(if (hidden) 0f else 1f)
                                    .then(if (active || full) Modifier.fillParentMaxHeight() else Modifier),
                            )
                        } else {
                            Box(Modifier.alpha(if (hidden) 0f else 1f)) {
                                when (display.style) {
                                    // The page's own summary names the focused title: no second caption.
                                    RowStyle.FILM_STRIP -> FilmStripRow(row, repository, actions, display.size, caption = false)
                                    RowStyle.LIGHTNING -> LightningRow(row, repository, actions, display.size)
                                    RowStyle.CRESSIDA -> CressidaRow(row, repository, actions, display)
                                    else -> CardRow(row, display, repository, actions)
                                }
                            }
                        }
                        }
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
        Text(item.displayTitle, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        item.episodeLabel?.let { Text(it, style = MaterialTheme.typography.titleMedium, maxLines = 1) }
        ItemMetaLine(item)
        dev.nitrate.tv.ui.components.groupStanding(item)?.let { st ->
            Text("👥 ${st.long}", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (st.catchUp) Color(0xFFFFC94D) else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item.overview?.takeIf { LocalDisplayPrefs.current.overview && !LocalDisplayPrefs.current.hidesDescription(item) }?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Scrolls only as far as needed to show what asked to be seen (all of it, when it fits). Something
 * taller than the list (an art card drawn at its design size reports that size, not the shrunk
 * one) is left alone while its top is in the upper part of the list, else brought to the top.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private object WholeRowInView : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
        offset < 0 -> offset
        size <= containerSize -> if (offset + size > containerSize) offset + size - containerSize else 0f
        offset <= containerSize / 3 -> 0f
        else -> offset
    }
}
