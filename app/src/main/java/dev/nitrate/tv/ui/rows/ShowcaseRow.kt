package dev.nitrate.tv.ui.rows

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.nitrate.tv.data.JellyfinRepository
import dev.nitrate.tv.data.rows.MediaRow
import dev.nitrate.tv.data.rows.RowDisplay
import dev.nitrate.tv.ui.components.Backdrop
import dev.nitrate.tv.ui.components.ItemMetaLine
import dev.nitrate.tv.ui.components.TitleOrLogo
import dev.nitrate.tv.ui.components.displayTitle
import dev.nitrate.tv.ui.components.episodeLabel
import kotlinx.coroutines.delay
import org.jellyfin.sdk.model.api.BaseItemDto

private const val ROTATE_INTERVAL_MS = 8_000L

/**
 * One item at a time. Collapsed it is a banner; focused it fills the screen with the item's
 * artwork and details, and Left/Right step through the row without moving focus.
 */
@Composable
fun ShowcaseRow(
    row: MediaRow,
    display: RowDisplay,
    repository: JellyfinRepository,
    actions: RowActions,
    index: Int,
    onIndexChange: (Int) -> Unit,
    active: Boolean,
    onActiveChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = row.items
    if (items.isEmpty()) return
    val current = index.coerceIn(0, items.lastIndex)
    val item = items[current]
    val options = display.showcase
    var longPressHandled by remember { mutableStateOf(false) }

    // Rotate through backdrops; restart whenever the item changes.
    val backdrops = remember(item.id) { repository.backdropUrls(item).ifEmpty { listOfNotNull(repository.backdropUrl(item)) } }
    var backdropIndex by remember(item.id) { mutableIntStateOf(0) }
    LaunchedEffect(item.id, active, options.rotateArtwork, backdrops.size) {
        if (!active || !options.rotateArtwork || backdrops.size < 2) return@LaunchedEffect
        while (true) {
            delay(ROTATE_INTERVAL_MS)
            backdropIndex = (backdropIndex + 1) % backdrops.size
        }
    }
    val artwork = backdrops.getOrNull(backdropIndex)

    LaunchedEffect(active, item.id) { if (active) actions.onFocus(item) }

    Box(
        modifier
            .onFocusChanged { onActiveChange(it.isFocused) }
            .onPreviewKeyEvent { event ->
                val down = event.type == KeyEventType.KeyDown
                when (event.key) {
                    Key.DirectionRight -> {
                        if (down) {
                            when {
                                current < items.lastIndex -> onIndexChange(current + 1)
                                options.infiniteScroll -> onIndexChange(0)
                            }
                        }
                        true
                    }
                    // On the first item, let focus leave the row.
                    Key.DirectionLeft -> if (current > 0) { if (down) onIndexChange(current - 1); true } else false
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (down && event.nativeKeyEvent.repeatCount > 0 && !longPressHandled) {
                            longPressHandled = true
                            actions.onEditRow(row)
                        } else if (!down) {
                            if (!longPressHandled) actions.onOpen(item)
                            longPressHandled = false
                        }
                        true
                    }
                    Key.MediaPlay, Key.MediaPlayPause -> { if (!down) actions.onPlay(item); true }
                    Key.Menu -> { if (!down) actions.onEditRow(row); true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        if (active) {
            Expanded(row, item, current, artwork, display, repository)
        } else {
            Collapsed(row, item, artwork, display)
        }
    }
}

@Composable
private fun Collapsed(row: MediaRow, item: BaseItemDto, artwork: String?, display: RowDisplay) {
    Column {
        Text(row.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = RowPadding, bottom = 12.dp))
        Box(
            Modifier
                .padding(horizontal = RowPadding)
                .fillMaxWidth()
                .height(display.size.bannerHeightDp.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (artwork != null) {
                AsyncImage(
                    artwork,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize().drawWithContent {
                        drawContent()
                        drawRect(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color(0xCC000000)))
                    },
                )
            }
            Text(
                item.displayTitle(),
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(20.dp),
            )
        }
    }
}

@Composable
private fun Expanded(
    row: MediaRow,
    item: BaseItemDto,
    index: Int,
    artwork: String?,
    display: RowDisplay,
    repository: JellyfinRepository,
) {
    val options = display.showcase
    Box(Modifier.fillMaxSize()) {
        Backdrop(artwork, Modifier.fillMaxSize())

        Text(
            row.title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.align(Alignment.TopStart).padding(start = RowPadding, top = 36.dp).alpha(0.7f),
        )

        Column(
            Modifier.align(Alignment.BottomStart).padding(start = RowPadding, bottom = 150.dp).fillMaxWidth(0.5f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TitleOrLogo(item, if (options.showLogo) repository.logoUrl(item) else null)
            item.episodeLabel()?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            if (options.showDetails) ItemMetaLine(item, showRatings = options.showRatings)
            if (options.showOverview) {
                item.overview?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Text(
            "${index + 1} / ${row.items.size}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = RowPadding, bottom = 150.dp),
        )

        Filmstrip(row, index, repository, Modifier.align(Alignment.BottomStart).padding(bottom = 36.dp))
    }
}

/** Slim, non-focusable strip of thumbnails showing where you are in the row. */
@Composable
private fun Filmstrip(row: MediaRow, index: Int, repository: JellyfinRepository, modifier: Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(index) { state.animateScrollToItem((index - 2).coerceAtLeast(0)) }
    LazyRow(
        state = state,
        userScrollEnabled = false,
        contentPadding = PaddingValues(horizontal = RowPadding),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        itemsIndexed(row.items, key = { _, it -> it.id }) { i, it ->
            val selected = i == index
            val alpha by animateFloatAsState(if (selected) 1f else 0.45f, label = "filmstrip")
            Box(
                Modifier
                    .height(if (selected) 78.dp else 64.dp)
                    .aspectRatio(16f / 9f)
                    .alpha(alpha)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)) else Modifier),
            ) {
                AsyncImage(wideImage(repository, it), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
