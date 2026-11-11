package dev.nitrate.tv.ui.rows

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
import dev.nitrate.tv.data.JellyfinRepository
import dev.nitrate.tv.data.rows.MediaRow
import dev.nitrate.tv.data.rows.RowDisplay
import dev.nitrate.tv.data.rows.RowStyle
import dev.nitrate.tv.ui.components.MediaCard
import dev.nitrate.tv.ui.components.displayTitle
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType

internal val RowPadding = 58.dp

/** A classic horizontal row of poster or wide cards. */
@Composable
fun CardRow(
    row: MediaRow,
    display: RowDisplay,
    repository: JellyfinRepository,
    actions: RowActions,
) {
    val wide = display.style == RowStyle.WIDE
    val height = if (wide) display.size.wideHeightDp else display.size.posterHeightDp
    val ratio = if (wide) 16f / 9f else 2f / 3f
    val width = (height * ratio).dp

    Column {
        Text(row.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = RowPadding, bottom = 12.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = RowPadding),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(row.items, key = { it.id }) { item ->
                MediaCard(
                    item = item,
                    imageUrl = if (wide) wideImage(repository, item) else repository.imageUrl(item, ImageType.PRIMARY, (height * 2)),
                    width = width,
                    aspectRatio = ratio,
                    title = if (display.showTitles) item.displayTitle() else null,
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

internal fun wideImage(repository: JellyfinRepository, item: BaseItemDto): String? =
    repository.imageUrl(item, ImageType.THUMB, 360)
        ?: repository.backdropUrl(item)
        ?: repository.imageUrl(item, ImageType.PRIMARY, 360)

private fun BaseItemDto.subtitleLine(): String? =
    if (seriesName != null) "S${parentIndexNumber ?: "?"}:E${indexNumber ?: "?"} · ${name.orEmpty()}" else productionYear?.toString()
