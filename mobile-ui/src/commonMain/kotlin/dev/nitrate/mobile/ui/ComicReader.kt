package dev.nitrate.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.nitrate.android.comics.ComicBook
import dev.nitrate.core.comics.ComicProgress
import dev.nitrate.core.comics.ReadingDirection
import dev.nitrate.core.comics.Spreads
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How many pages to show side by side: by the screen (two when it's wider than tall), or always one or two. */
enum class TwoUp(val label: String) { AUTO("Automatic"), ONE("One page"), TWO("Two pages") }

/**
 * Reads a comic from the user's Books library: fetched once to the device, then page by page
 * (swipe, or a tap on either edge; pinch or double-tap to zoom; a tap in the middle shows the
 * controls), two pages side by side when the screen is wide, right to left for manga, or one long
 * strip for webtoons. Where the user is goes back to Jellyfin as they read.
 */
@Composable
fun ComicReaderScreen(actions: AppActions, itemId: String, onExit: () -> Unit) {
    val container = actions.container
    var book by remember { mutableStateOf<ComicBook?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(itemId) {
        runCatching {
            val item = actions.server.getItem(itemId)
            book = container.comics.open(item) { progress = it }
        }.onFailure { error = it.message ?: "Couldn't open this comic." }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val b = book
        when {
            error != null -> Column(Modifier.align(Alignment.Center).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(error!!, color = Color.White)
                TextButton(onClick = onExit) { Text("Back") }
            }
            b == null -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Getting the comic…", color = Color.White)
                if (progress > 0f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(0.6f)) else CircularProgressIndicator()
            }
            else -> ComicPages(actions, b, onExit)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ComicPages(actions: AppActions, book: ComicBook, onExit: () -> Unit) {
    val scope = rememberCoroutineScope()
    var direction by remember { mutableStateOf(book.info?.direction ?: ReadingDirection.LEFT_TO_RIGHT) }
    var twoUpChoice by remember { mutableStateOf(TwoUp.AUTO) }
    var chrome by remember { mutableStateOf(true) }
    var settings by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(book.startPage) }
    val wide = book.widePages

    // Once the first pages are up, the rest of the book in one burst.
    LaunchedEffect(book) { delay(1_000); book.fetchAll(from = book.startPage) }
    // Where the user is goes back to the server a moment after they stop turning, and on leaving.
    LaunchedEffect(page) {
        delay(1_500)
        actions.container.comics.saveProgress(book, page)
    }
    DisposableEffect(book) { onDispose { actions.scope.launch { actions.container.comics.saveProgress(book, page) } } }
    LaunchedEffect(chrome) { if (chrome) { delay(4_000); if (!settings) chrome = false } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val twoUp = when (twoUpChoice) {
            TwoUp.AUTO -> maxWidth > maxHeight && maxWidth >= 600.dp
            TwoUp.ONE -> false
            TwoUp.TWO -> true
        }
        if (direction == ReadingDirection.VERTICAL) {
            val list = rememberLazyListState(initialFirstVisibleItemIndex = page)
            LaunchedEffect(list) { snapshotFlow { list.firstVisibleItemIndex }.collect { page = it } }
            LazyColumn(state = list, modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { chrome = !chrome }) }) {
                itemsIndexed(book.pages) { i, _ -> PageImage(book, i, Modifier.fillMaxWidth(), ContentScale.FillWidth) }
            }
        } else {
            val spreads = remember(book.pageCount, twoUp, direction) { Spreads.of(book.pageCount, twoUp, wide, direction) }
            val pager = rememberPagerState(initialPage = Spreads.indexOf(spreads, page)) { spreads.size }
            var zoomed by remember { mutableStateOf(false) }
            LaunchedEffect(pager, spreads) { snapshotFlow { pager.currentPage }.collect { page = spreads.getOrNull(it)?.first ?: page } }
            // The pages either side are taken out of the file before they're reached.
            LaunchedEffect(pager.currentPage, spreads) {
                for (i in pager.currentPage - 1..pager.currentPage + 2) spreads.getOrNull(i)?.pages?.forEach { runCatching { book.page(it) } }
            }
            HorizontalPager(
                state = pager,
                reverseLayout = direction == ReadingDirection.RIGHT_TO_LEFT,
                userScrollEnabled = !zoomed,
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize(),
            ) { index ->
                val spread = spreads[index]
                ZoomBox(
                    onZoomed = { zoomed = it },
                    onTap = { x, width ->
                        // The outer fifths turn the page (in reading order on screen); the middle shows the controls.
                        val step = when {
                            x < width / 5 -> dev.nitrate.core.comics.ReaderKeys.step(right = false, direction)
                            x > width * 4 / 5 -> dev.nitrate.core.comics.ReaderKeys.step(right = true, direction)
                            else -> 0
                        }
                        if (step == 0) chrome = !chrome
                        else scope.launch { pager.animateScrollToPage((pager.currentPage + step).coerceIn(0, spreads.size - 1)) }
                    },
                ) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        spread.pages.forEach { p -> PageImage(book, p, Modifier.weight(1f).fillMaxSize(), ContentScale.Fit, if (spread.pages.size == 2) (if (p == spread.pages.first()) Alignment.CenterEnd else Alignment.CenterStart) else Alignment.Center) }
                    }
                }
            }
            AnimatedVisibility(chrome, modifier = Modifier.align(Alignment.BottomCenter)) {
                Column(Modifier.fillMaxWidth().background(Color(0xCC000000)).windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(ComicProgress.label(page, book.pageCount), color = Color.White, style = MaterialTheme.typography.labelLarge)
                    if (spreads.size > 1) Slider(
                        value = pager.currentPage.toFloat(),
                        onValueChange = { v -> scope.launch { pager.scrollToPage(v.toInt()) } },
                        valueRange = 0f..(spreads.size - 1).toFloat(),
                        modifier = Modifier.graphicsLayer { if (direction == ReadingDirection.RIGHT_TO_LEFT) scaleX = -1f },
                    )
                }
            }
        }

        AnimatedVisibility(chrome, modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth().background(Color(0xCC000000)).windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Text(
                    listOfNotNull(book.info?.series ?: book.title, book.info?.number?.let { "#$it" }).joinToString(" "),
                    color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { settings = true }) { Icon(Icons.Default.Settings, "Reading settings", tint = Color.White) }
            }
        }
    }

    if (settings) ModalBottomSheet(onDismissRequest = { settings = false }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Reading", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReadingDirection.entries.forEach { d -> FilterChip(direction == d, { direction = d }, label = { Text(d.label) }) }
            }
            Text("Pages", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TwoUp.entries.forEach { t -> FilterChip(twoUpChoice == t, { twoUpChoice = t }, label = { Text(t.label) }) }
            }
        }
    }
}

/** One page's picture, taken out of the file when first shown. */
@Composable
private fun PageImage(book: ComicBook, index: Int, modifier: Modifier, scale: ContentScale, alignment: Alignment = Alignment.Center) {
    val path by produceState<String?>(null, book, index) { value = runCatching { book.page(index) }.getOrNull() }
    Box(modifier, contentAlignment = Alignment.Center) {
        val p = path
        if (p == null) CircularProgressIndicator(color = Color.White.copy(alpha = 0.5f))
        else AsyncImage(model = p, contentDescription = "Page ${index + 1}", contentScale = scale, alignment = alignment, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Pinch or double-tap to zoom (up to 4×), and one finger to move around while zoomed; at normal
 * size a one-finger drag is left to the pager, so swiping turns pages. [onTap] gets where the tap
 * landed across the width.
 */
@Composable
private fun ZoomBox(onZoomed: (Boolean) -> Unit, onTap: (x: Float, width: Float) -> Unit, content: @Composable () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    fun clamp(o: Offset, s: Float): Offset {
        val maxX = (size.width * (s - 1)) / 2
        val maxY = (size.height * (s - 1)) / 2
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }
    fun set(s: Float, o: Offset) {
        scale = s.coerceIn(1f, 4f)
        offset = if (scale == 1f) Offset.Zero else clamp(o, scale)
        onZoomed(scale > 1f)
    }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) { size = this.size.let { IntSize(it.width, it.height) } }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { at ->
                        if (scale > 1f) set(1f, Offset.Zero)
                        else {
                            // Zoom towards where the tap landed.
                            val center = Offset(this.size.width / 2f, this.size.height / 2f)
                            set(2.5f, (center - at) * 1.5f)
                        }
                    },
                    onTap = { at -> onTap(at.x, this.size.width.toFloat()) },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        // Two fingers always zoom; one finger moves the page only while zoomed (else the pager swipes).
                        if (fingers >= 2 || scale > 1f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid()
                            val newScale = (scale * zoom).coerceIn(1f, 4f)
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val focus = centroid - center
                            set(newScale, (offset - focus) * (newScale / scale) + focus + pan)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
    ) { content() }
}
