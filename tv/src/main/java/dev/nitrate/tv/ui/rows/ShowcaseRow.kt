package dev.nitrate.tv.ui.rows

import dev.nitrate.tv.ui.components.LocalDisplayPrefs
import dev.nitrate.core.pages.hidesDescription
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
import dev.nitrate.core.MediaServer
import dev.nitrate.core.rows.MediaRow
import dev.nitrate.core.rows.RowDisplay
import dev.nitrate.tv.ui.components.Backdrop
import dev.nitrate.tv.ui.effects.cardFx
import dev.nitrate.tv.ui.components.ItemMetaLine
import dev.nitrate.tv.ui.components.TitleOrLogo
import kotlinx.coroutines.delay
import dev.nitrate.core.model.MediaItem

private const val ROTATE_INTERVAL_MS = 8_000L

/**
 * One item at a time. Collapsed it is a banner; focused it fills the screen with the item's
 * artwork and details, and Left/Right step through the row without moving focus.
 */
@Composable
fun ShowcaseRow(
    row: MediaRow,
    display: RowDisplay,
    repository: MediaServer,
    actions: RowActions,
    index: Int,
    onIndexChange: (Int) -> Unit,
    active: Boolean,
    onActiveChange: (Boolean) -> Unit,
    /** Full Showcase: the full-screen view even without focus. */
    alwaysExpanded: Boolean = false,
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

    // The titles either side of this one, so stepping shows their artwork and logo at once.
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(current, items) {
        val near = (listOf(current - 1) + (current + 1..current + 3)).mapNotNull { items.getOrNull(it) }
        ImagePrefetch.enqueue(context, near.flatMap { n ->
            listOf(repository.backdropUrls(n).firstOrNull() ?: repository.backdropUrl(n), wideImage(repository, n), if (options.showLogo) repository.logoUrl(n) else null)
        })
    }

    LaunchedEffect(active, item.id) { if (active) actions.onFocus(item) }
    val menuSide = dev.nitrate.tv.ui.home.LocalMenuSide.current
    val reachMenu = dev.nitrate.tv.ui.home.LocalMenuEdge.current

    Box(
        modifier
            .onFocusChanged { onActiveChange(it.isFocused) }
            .onPreviewKeyEvent { event ->
                val down = event.type == KeyEventType.KeyDown
                when (event.key) {
                    // Off either end: to the menu if it's that way, else round to the other end (unless
                    // wrapping is off, when focus leaves the row).
                    Key.DirectionRight -> when {
                        current < items.lastIndex -> { if (down) onIndexChange(current + 1); true }
                        options.infiniteScroll && items.size > 1 && !menuSide(Key.DirectionRight) -> { if (down) onIndexChange(0); true }
                        // Straight to the menu: a screen-wide picture has nothing "nearest" that way to search for.
                        menuSide(Key.DirectionRight) -> { if (down) reachMenu(Key.DirectionRight); true }
                        else -> false
                    }
                    Key.DirectionLeft -> when {
                        current > 0 -> { if (down) onIndexChange(current - 1); true }
                        options.infiniteScroll && items.size > 1 && !menuSide(Key.DirectionLeft) -> { if (down) onIndexChange(items.lastIndex); true }
                        menuSide(Key.DirectionLeft) -> { if (down) reachMenu(Key.DirectionLeft); true }
                        else -> false
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        // A fresh press starts clean: a long press's release goes to the dialog it opened.
                        if (down && event.nativeKeyEvent.repeatCount == 0) longPressHandled = false
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
                    else -> false
                }
            }
            .returnFocus(item.id)
            .focusable(),
    ) {
        if (active || alwaysExpanded) {
            // Full Showcase has no thumbnail strip: the picture is the whole screen.
            Expanded(row, item, current, artwork, display, repository, strip = !alwaysExpanded)
        } else {
            Collapsed(row, item, artwork, display)
        }
    }
}

@Composable
private fun Collapsed(row: MediaRow, item: MediaItem, artwork: String?, display: RowDisplay) {
    Column {
        Text(row.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = RowPadding, bottom = 12.dp))
        Box(
            Modifier
                .padding(horizontal = RowPadding)
                .fillMaxWidth()
                .height(display.size.tvBannerDp().dp)
                .cardFx(item.id)
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
                item.displayTitle,
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
    item: MediaItem,
    index: Int,
    artwork: String?,
    display: RowDisplay,
    repository: MediaServer,
    strip: Boolean = true,
) {
    val options = display.showcase
    // Room for the strip below the text, or just a margin without it.
    val bottom = if (strip) 150.dp else 56.dp
    Box(Modifier.fillMaxSize()) {
        // The picture is the item here: sparks go over it, and the click plays on the title.
        Backdrop(artwork, Modifier.fillMaxSize().cardFx(item.id, click = false))

        // Captioned as the screensaver is: the row and place as a small label, the title, a quiet line
        // or two, in a bottom corner that alternates side from one title to the next.
        val (capTop, end) = dev.nitrate.tv.ui.components.LocalDisplayPrefs.current.tvCaptionCorner.resolve(index)
        dev.nitrate.tv.ui.components.ArtCaptionShade()
        if (capTop) dev.nitrate.tv.ui.components.ArtCaptionShade(top = true)
        dev.nitrate.tv.ui.components.ArtCaption(
            item, label = "${row.title}  ·  ${index + 1} / ${row.items.size}", end = end,
            logoUrl = if (options.showLogo) repository.logoUrl(item) else null,
            showDetails = options.showDetails, showRatings = options.showRatings, showOverview = options.showOverview,
            modifier = Modifier.align(when { capTop && end -> Alignment.TopEnd; capTop -> Alignment.TopStart; end -> Alignment.BottomEnd; else -> Alignment.BottomStart })
                .padding(start = RowPadding, end = RowPadding, top = if (capTop) 72.dp else 0.dp, bottom = if (capTop) 0.dp else bottom).cardFx(item.id, spark = false),
        )

        if (strip) Filmstrip(row, index, repository, Modifier.align(Alignment.BottomStart).padding(bottom = 36.dp))
    }
}

/** Slim, non-focusable strip of thumbnails showing where you are in the row. */
@Composable
private fun Filmstrip(row: MediaRow, index: Int, repository: MediaServer, modifier: Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(index) { state.animateScrollToItem((index - 2).coerceAtLeast(0)) }
    LazyRow(
        state = state,
        userScrollEnabled = false,
        contentPadding = PaddingValues(horizontal = RowPadding),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        itemsIndexed(row.items, key = { i, it -> "$i:${it.id}" }) { i, it ->
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
