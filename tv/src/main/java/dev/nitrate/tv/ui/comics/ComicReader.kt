package dev.nitrate.tv.ui.comics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.nitrate.android.AppContainer
import dev.nitrate.android.comics.ComicBook
import dev.nitrate.core.comics.ComicProgress
import dev.nitrate.core.comics.ReaderKeys
import dev.nitrate.core.comics.ReadingDirection
import dev.nitrate.core.comics.Spreads
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Reads a comic on the TV: two pages side by side (the screen is wide; the cover and double-page
 * images alone), ◀▶ turning in reading order (right to left for manga), OK zooming in and the
 * arrows moving round the zoomed page, Menu (or Down) for the page strip and reading settings, and
 * Back stepping out of zoom, then the strip, then the reader. Fetched once to the device; where
 * the user is goes back to Jellyfin as they read.
 */
@Composable
fun ComicReaderScreen(container: AppContainer, itemId: String, onExit: () -> Unit) {
    var book by remember { mutableStateOf<ComicBook?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(itemId) {
        runCatching { book = container.comics.open(container.server.getItem(itemId)) { progress = it } }
            .onFailure { error = it.message ?: "Couldn't open this comic." }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val b = book
        when {
            error != null -> {
                val back = remember { FocusRequester() }
                Column(Modifier.align(Alignment.Center).width(720.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(error!!, style = MaterialTheme.typography.titleLarge, color = Color.White)
                    androidx.tv.material3.Button(onClick = onExit, modifier = Modifier.focusRequester(back)) { Text("Back") }
                }
                LaunchedEffect(Unit) { delay(50); runCatching { back.requestFocus() } }
            }
            b == null -> Text(
                if (progress > 0f) "Getting the comic… ${(progress * 100).toInt()}%" else "Getting the comic…",
                style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.align(Alignment.Center),
            )
            else -> TvComicPages(container, b, onExit)
        }
    }
}

@Composable
private fun TvComicPages(container: AppContainer, book: ComicBook, onExit: () -> Unit) {
    val scope = rememberCoroutineScope()
    var direction by remember { mutableStateOf(book.info?.direction ?: ReadingDirection.LEFT_TO_RIGHT) }
    var twoUp by remember { mutableStateOf(true) }
    val spreads = remember(book.pageCount, twoUp, direction) { Spreads.of(book.pageCount, twoUp && direction != ReadingDirection.VERTICAL, book.info?.doublePages.orEmpty(), direction) }
    var index by remember(spreads) { mutableIntStateOf(Spreads.indexOf(spreads, book.startPage)) }
    var zoom by remember { mutableStateOf(false) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var strip by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val page = spreads.getOrNull(index)?.first ?: 0

    LaunchedEffect(page) { delay(1_500); container.comics.saveProgress(book, page) }
    DisposableEffect(book) { onDispose { scope.launch { container.comics.saveProgress(book, page) } } }
    // The next spreads are taken out of the file before they're reached.
    LaunchedEffect(index, spreads) { for (i in index..index + 2) spreads.getOrNull(i)?.pages?.forEach { runCatching { book.page(it) } } }
    LaunchedEffect(strip) { if (!strip) { delay(50); runCatching { focus.requestFocus() } } }

    fun turn(right: Boolean) {
        val next = (index + ReaderKeys.step(right, direction)).coerceIn(0, spreads.size - 1)
        if (next != index) { index = next; zoom = false; pan = Offset.Zero }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().focusRequester(focus).focusable().onPreviewKeyEvent { e ->
            if (strip || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val stepX = 0.25f
            when (e.key) {
                Key.DirectionLeft -> { if (zoom && pan.x < 0.5f) pan = pan.copy(x = pan.x + stepX) else turn(right = false); true }
                Key.DirectionRight -> { if (zoom && pan.x > -0.5f) pan = pan.copy(x = pan.x - stepX) else turn(right = true); true }
                Key.DirectionUp -> { if (zoom) pan = pan.copy(y = (pan.y + stepX).coerceAtMost(0.5f)); zoom }
                Key.DirectionDown -> { if (zoom) pan = pan.copy(y = (pan.y - stepX).coerceAtLeast(-0.5f)) else strip = true; true }
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                    // Zoom in from the top corner reading starts at (left, or right for manga).
                    zoom = !zoom
                    pan = if (zoom) Offset(if (direction == ReadingDirection.RIGHT_TO_LEFT) -0.5f else 0.5f, 0.5f) else Offset.Zero
                    true
                }
                Key.Menu -> { strip = true; true }
                Key.MediaFastForward, Key.PageDown -> { turn(right = direction != ReadingDirection.RIGHT_TO_LEFT); true }
                Key.MediaRewind, Key.PageUp -> { turn(right = direction == ReadingDirection.RIGHT_TO_LEFT); true }
                Key.Back -> { if (zoom) { zoom = false; pan = Offset.Zero } else onExit(); true }
                else -> false
            }
        },
    ) {
        val scale by animateFloatAsState(if (zoom) 2f else 1f, label = "zoom")
        val shift by animateOffsetAsState(pan, label = "pan")
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val spread = spreads.getOrNull(index)
        Row(
            Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = shift.x * w; translationY = shift.y * h },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            spread?.pages?.forEach { p ->
                val two = spread.pages.size == 2
                TvPage(book, p, Modifier.weight(1f).fillMaxSize(), if (two) (if (p == spread.pages.first()) Alignment.CenterEnd else Alignment.CenterStart) else Alignment.Center)
            }
        }
        if (!strip) Text(
            ComicProgress.label(page, book.pageCount) + if (zoom) "   ·   arrows move, OK zooms out" else "",
            style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).background(Color(0x99000000), MaterialTheme.shapes.small).padding(horizontal = 10.dp, vertical = 4.dp),
        )
        AnimatedVisibility(strip, modifier = Modifier.align(Alignment.BottomCenter)) {
            PageStrip(
                book = book,
                current = page,
                direction = direction,
                twoUp = twoUp,
                onPick = { p -> index = Spreads.indexOf(spreads, p); zoom = false; pan = Offset.Zero; strip = false },
                onDirection = { direction = it },
                onTwoUp = { twoUp = it },
                onClose = { strip = false },
            )
        }
    }
}

/** One page, taken out of the file when first shown. */
@Composable
private fun TvPage(book: ComicBook, index: Int, modifier: Modifier, alignment: Alignment) {
    val path by produceState<String?>(null, book, index) { value = runCatching { book.page(index) }.getOrNull() }
    Box(modifier, contentAlignment = Alignment.Center) {
        path?.let { AsyncImage(model = it, contentDescription = "Page ${index + 1}", contentScale = ContentScale.Fit, alignment = alignment, modifier = Modifier.fillMaxSize()) }
    }
}

/** The page strip (OK jumps to a page; it opens on the current one) and the reading settings above it. */
@Composable
private fun PageStrip(
    book: ComicBook,
    current: Int,
    direction: ReadingDirection,
    twoUp: Boolean,
    onPick: (Int) -> Unit,
    onDirection: (ReadingDirection) -> Unit,
    onTwoUp: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (current - 3).coerceAtLeast(0))
    val here = remember { FocusRequester() }
    Column(
        Modifier.fillMaxWidth().background(Color(0xE6000000)).padding(vertical = 16.dp).onPreviewKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown && (e.key == Key.Back || e.key == Key.Menu)) { onClose(); true } else false
        },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.padding(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(listOfNotNull(book.info?.series ?: book.item.name, book.info?.number?.let { "#$it" }).joinToString(" "), style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(360.dp))
            ReadingDirection.entries.forEach { d -> FilterChip(direction == d, { onDirection(d) }) { Text(d.label) } }
            FilterChip(twoUp, { onTwoUp(!twoUp) }) { Text("Two pages") }
        }
        LazyRow(state = list, contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), reverseLayout = direction == ReadingDirection.RIGHT_TO_LEFT) {
            itemsIndexed(book.pages) { i, _ ->
                val path by produceState<String?>(null, book, i) { value = runCatching { book.page(i) }.getOrNull() }
                Card(onClick = { onPick(i) }, modifier = Modifier.height(150.dp).aspectRatio(0.66f).then(if (i == current) Modifier.focusRequester(here).border(2.dp, Color.White, MaterialTheme.shapes.small) else Modifier)) {
                    Box(Modifier.fillMaxSize()) {
                        path?.let { AsyncImage(model = it, contentDescription = "Page ${i + 1}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = Color.White, modifier = Modifier.align(Alignment.BottomEnd).background(Color(0x99000000)).padding(horizontal = 4.dp))
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(80); runCatching { here.requestFocus() } }
}
