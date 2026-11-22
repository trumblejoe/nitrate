package dev.nitrate.tv.ui.rows

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.nitrate.core.MediaServer
import dev.nitrate.core.rows.MediaRow
import dev.nitrate.core.rows.RowDisplay
import dev.nitrate.core.rows.RowStyle
import dev.nitrate.tv.ui.components.MediaCard
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.ImageKind

internal val RowPadding = 58.dp

/**
 * [RowSize]'s heights are the phone's; on a TV screen (960×540 dp, the summary above the rows) they
 * left room for barely one row, so TV cards take this share of them (art rows have their own
 * `designScale`). Pictures are still asked for at the full size (`rowCardImages` matches).
 */
internal const val TV_CARD_SCALE = 0.65f
internal const val TV_BANNER_SCALE = 0.75f
internal fun dev.nitrate.core.rows.RowSize.tvPosterDp() = kotlin.math.round(posterHeightDp * TV_CARD_SCALE).toInt()
internal fun dev.nitrate.core.rows.RowSize.tvWideDp() = kotlin.math.round(wideHeightDp * TV_CARD_SCALE).toInt()
internal fun dev.nitrate.core.rows.RowSize.tvBannerDp() = kotlin.math.round(bannerHeightDp * TV_BANNER_SCALE).toInt()

/** Whether rows show their own title above their cards (off where a caption beside them names the row). */
val LocalRowTitle = androidx.compose.runtime.staticCompositionLocalOf { true }

/** A classic horizontal row of poster or wide cards. */
@Composable
fun CardRow(
    row: MediaRow,
    display: RowDisplay,
    repository: MediaServer,
    actions: RowActions,
) {
    val wide = display.style == RowStyle.WIDE
    val height = if (wide) display.size.tvWideDp() else display.size.tvPosterDp()
    val ratio = if (wide) 16f / 9f else 2f / 3f
    val width = (height * ratio).dp

    // The next cards' pictures, and the full-screen backdrop of the next two (shown behind on focus).
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    PrefetchAlongRow(state, row.items) { repository.rowCardImages(it, display) }
    PrefetchAlongRow(state, row.items, ahead = 2) { listOf(repository.backdropUrl(it)) }
    Column {
        if (LocalRowTitle.current) Text(row.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = RowPadding, bottom = 12.dp))
        LazyRow(
            state = state,
            // A few dp above and below, so a focused card's growth isn't cut off by the row's edge.
            contentPadding = PaddingValues(horizontal = RowPadding, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            itemsIndexed(row.items, key = { i, it -> "$i:${it.id}" }) { _, item ->
                MediaCard(
                    item = item,
                    imageUrl = if (wide) wideImage(repository, item) else repository.imageUrl(item, ImageKind.PRIMARY, display.size.posterHeightDp * 2),
                    width = width,
                    aspectRatio = ratio,
                    title = if (display.showTitles) item.displayTitle else null,
                    subtitle = if (display.showTitles) item.subtitleLine() else null,
                    onFocus = { actions.onFocus(item) },
                    onClick = { actions.onOpen(item) },
                    onPlay = { actions.onPlay(item) },
                    onLongClick = { actions.onEditRow(row) },
                )
            }
        }
    }
}

internal fun wideImage(repository: MediaServer, item: MediaItem): String? =
    repository.imageUrl(item, ImageKind.THUMB, 360)
        ?: repository.backdropUrl(item, 640)
        ?: repository.imageUrl(item, ImageKind.PRIMARY, 360)

private fun MediaItem.subtitleLine(): String? =
    dev.nitrate.core.model.AirDates.status(this, System.currentTimeMillis())?.let { "S${seasonNumber ?: "?"}:E${indexNumber ?: "?"} · $it" }
        ?: if (seriesName != null) "S${seasonNumber ?: "?"}:E${indexNumber ?: "?"} · ${name.orEmpty()}" else productionYear?.toString()

/**
 * A row as a column (one row at a time, down the left or right): posters two abreast, every other
 * style as wide cards one under another. ▲▼ move along it by hand (card by card, the list scrolled
 * to it first when it's out of sight), and past its ends [onPastEnd] (-1 up, +1 down) takes the
 * rows above and below: the focus search between a lazy column and a row a screen away found
 * nothing and on a TV fell to the menu. Returns false (unhandled) when there's no row that way.
 */
@Composable
fun ColumnCards(
    row: MediaRow,
    display: RowDisplay,
    repository: MediaServer,
    actions: RowActions,
    modifier: Modifier = Modifier,
    onPastEnd: (Int) -> Boolean = { false },
) {
    val posters = display.style == RowStyle.POSTER
    val across = if (posters) 2 else 1
    val pad = PaddingValues(vertical = 24.dp, horizontal = 8.dp)
    val count = row.items.size
    val requesters = remember(count) { List(count) { androidx.compose.ui.focus.FocusRequester() } }
    var at by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    fun visible(i: Int) = if (posters) gridState.layoutInfo.visibleItemsInfo.any { it.index == i } else listState.layoutInfo.visibleItemsInfo.any { it.index == i }
    fun go(i: Int) {
        at = i
        scope.launch {
            if (!visible(i)) { if (posters) gridState.scrollToItem(i) else listState.scrollToItem(i) }
            repeat(20) {
                if (runCatching { requesters[i].requestFocus() }.getOrDefault(false)) return@launch
                kotlinx.coroutines.delay(16)
            }
        }
    }
    val keys = Modifier.onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (e.key) {
            Key.DirectionDown -> {
                val next = at + across
                val lastLine = (count - 1) / across
                when {
                    next < count -> { go(next); true }
                    at / across < lastLine -> { go(count - 1); true }
                    else -> onPastEnd(1)
                }
            }
            Key.DirectionUp -> if (at - across >= 0) { go(at - across); true } else onPastEnd(-1)
            else -> false
        }
    }
    @Composable
    fun card(i: Int, wideCard: Boolean, cardModifier: Modifier = Modifier) {
        val item = row.items[i]
        MediaCard(
            item = item,
            imageUrl = if (wideCard) wideImage(repository, item) else repository.imageUrl(item, ImageKind.PRIMARY, display.size.posterHeightDp * 2),
            width = null,
            aspectRatio = if (wideCard) 16f / 9f else 2f / 3f,
            title = if (display.showTitles) item.displayTitle else null,
            subtitle = if (wideCard && display.showTitles) item.subtitleLine() else null,
            onFocus = { at = i; actions.onFocus(item) },
            onClick = { actions.onOpen(item) },
            onPlay = { actions.onPlay(item) },
            onLongClick = { actions.onEditRow(row) },
            modifier = cardModifier.focusRequester(requesters[i]),
        )
    }
    if (posters) {
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            state = gridState,
            columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),
            contentPadding = pad,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = modifier.then(keys),
        ) {
            items(count, key = { i -> "$i:${row.items[i].id}" }) { i -> card(i, wideCard = false) }
        }
    } else {
        androidx.compose.foundation.lazy.LazyColumn(
            state = listState,
            contentPadding = pad,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = modifier.then(keys),
        ) {
            items(count, key = { i -> "$i:${row.items[i].id}" }) { i -> card(i, wideCard = true) }
        }
    }
}

/** How wide a row's column is, down the left or right. */
internal val ColumnWidth = 300.dp
