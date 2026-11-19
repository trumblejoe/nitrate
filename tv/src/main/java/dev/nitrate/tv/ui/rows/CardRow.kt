package dev.nitrate.tv.ui.rows

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
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
 * style as wide cards one under another. ▲▼ move along it; past its ends they go to the rows above
 * and below, as from a row along the bottom.
 */
@Composable
fun ColumnCards(
    row: MediaRow,
    display: RowDisplay,
    repository: MediaServer,
    actions: RowActions,
    modifier: Modifier = Modifier,
) {
    val posters = display.style == RowStyle.POSTER
    val pad = PaddingValues(vertical = 24.dp, horizontal = 8.dp)
    if (posters) {
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),
            contentPadding = pad,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = modifier,
        ) {
            items(row.items.size, key = { i -> "$i:${row.items[i].id}" }) { i ->
                val item = row.items[i]
                MediaCard(
                    item = item,
                    imageUrl = repository.imageUrl(item, ImageKind.PRIMARY, display.size.posterHeightDp * 2),
                    width = null,
                    aspectRatio = 2f / 3f,
                    title = if (display.showTitles) item.displayTitle else null,
                    onFocus = { actions.onFocus(item) },
                    onClick = { actions.onOpen(item) },
                    onPlay = { actions.onPlay(item) },
                    onLongClick = { actions.onEditRow(row) },
                )
            }
        }
    } else {
        androidx.compose.foundation.lazy.LazyColumn(
            contentPadding = pad,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = modifier,
        ) {
            itemsIndexed(row.items, key = { i, it -> "$i:${it.id}" }) { _, item ->
                MediaCard(
                    item = item,
                    imageUrl = wideImage(repository, item),
                    width = null,
                    aspectRatio = 16f / 9f,
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

/** How wide a row's column is, down the left or right. */
internal val ColumnWidth = 300.dp
