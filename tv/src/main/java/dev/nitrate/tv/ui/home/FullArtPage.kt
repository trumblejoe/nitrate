package dev.nitrate.tv.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.rows.MediaRow
import dev.nitrate.core.rows.RowDisplay
import dev.nitrate.core.pages.hidesDescription
import dev.nitrate.tv.ui.components.Backdrop
import dev.nitrate.tv.ui.components.ItemMetaLine
import dev.nitrate.tv.ui.components.LocalDisplayPrefs
import dev.nitrate.tv.ui.components.TitleOrLogo
import dev.nitrate.tv.ui.rows.RowSettingsDialog

/** Space kept clear for the menu at each edge (so text never sits under it). */
data class MenuInsets(val start: Dp = 0.dp, val top: Dp = 0.dp, val end: Dp = 0.dp, val bottom: Dp = 0.dp)

/**
 * Home as one picture at a time: the focused title fills the screen with its details in the
 * corner. Left/Right step through the row, Up/Down move between rows, OK opens, Play plays, and a
 * long press changes the row (Menu opens the main menu). At the first/last item or row the D-pad moves on to the
 * menu, wherever it is.
 */
@Composable
fun FullArtPage(
    rows: List<MediaRow>,
    overrides: Map<String, RowDisplay>,
    repository: MediaServer,
    onOpen: (MediaItem) -> Unit,
    onPlay: (MediaItem) -> Unit,
    onSaveDisplay: (MediaRow, RowDisplay) -> Unit,
    onEditPage: () -> Unit,
    insets: MenuInsets = MenuInsets(),
    modifier: Modifier = Modifier,
    /** Above the first row, top right (a library's tabs): Up from the first row, Down back. */
    header: (@Composable () -> Unit)? = null,
) {
    val pageFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val headerFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    // The page sees keys before the tabs over it: while they have focus, the keys are theirs.
    var headerFocused by remember { mutableStateOf(false) }
    val shown = rows.filter { it.items.isNotEmpty() }
    if (shown.isEmpty()) {
        Box(modifier.fillMaxSize(), Alignment.Center) { Text("Nothing here yet") }
        return
    }
    // Where you are, by row key (rows can come and go on a refresh), kept when you come back
    // from a title or the player.
    var rowKey by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val rowIndex = shown.indexOfFirst { it.key == rowKey }.takeIf { it >= 0 } ?: 0
    val itemIndex = androidx.compose.runtime.saveable.rememberSaveable(saver = PositionsSaver) { mutableStateMapOf<String, Int>() }
    var editing by remember { mutableStateOf<MediaRow?>(null) }
    val customize = dev.nitrate.tv.ui.pages.LocalCustomizeHost.current
    val pageRows = dev.nitrate.tv.ui.pages.LocalPageRows.current
    var longPressHandled by remember { mutableStateOf(false) }
    val reachMenu = LocalMenuEdge.current
    val reportRows = LocalMenuRows.current
    LaunchedEffect(rowIndex, shown.size) { reportRows(rowIndex == 0, rowIndex >= shown.lastIndex) }
    val context = androidx.compose.ui.platform.LocalContext.current

    val row = shown[rowIndex]
    val current = (itemIndex[row.key] ?: 0).coerceIn(0, row.items.lastIndex)
    val item = row.items[current]
    // Fetch the pictures one press away (either side, and the rows above and below) so stepping
    // shows them at once.
    LaunchedEffect(row.key, current) {
        val loader = coil3.SingletonImageLoader.get(context)
        val near = listOfNotNull(
            row.items.getOrNull(current + 1), row.items.getOrNull(current - 1),
            shown.getOrNull(rowIndex + 1)?.let { r -> r.items.getOrNull(itemIndex[r.key] ?: 0) },
            shown.getOrNull(rowIndex - 1)?.let { r -> r.items.getOrNull(itemIndex[r.key] ?: 0) },
        )
        near.forEach { n ->
            listOfNotNull(repository.backdropUrl(n), repository.logoUrl(n)).forEach { url ->
                loader.enqueue(coil3.request.ImageRequest.Builder(context).data(url).build())
            }
        }
    }
    val showcase = (overrides[row.key] ?: row.defaultDisplay).showcase
    val captionSize = (overrides[row.key] ?: row.defaultDisplay).captionSize ?: dev.nitrate.tv.ui.components.LocalDisplayPrefs.current.tvCaptionSize

    Box(
        modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (headerFocused) return@onPreviewKeyEvent false
                val down = event.type == KeyEventType.KeyDown
                when (event.key) {
                    // Past the first/last item: on to the menu if it's that way, else round to the other
                    // end (the row's "Infinite scroll"). Past the first/last row: the menu or stay.
                    Key.DirectionRight -> { if (down) { if (current < row.items.lastIndex) itemIndex[row.key] = current + 1 else if (!reachMenu(event.key) && showcase.infiniteScroll) itemIndex[row.key] = 0 }; true }
                    Key.DirectionLeft -> { if (down) { if (current > 0) itemIndex[row.key] = current - 1 else if (!reachMenu(event.key) && showcase.infiniteScroll) itemIndex[row.key] = row.items.lastIndex }; true }
                    Key.DirectionDown -> { if (down) { if (rowIndex < shown.lastIndex) rowKey = shown[rowIndex + 1].key else reachMenu(event.key) }; true }
                    Key.DirectionUp -> {
                        if (down) when {
                            rowIndex > 0 -> rowKey = shown[rowIndex - 1].key
                            header != null -> runCatching { headerFocus.requestFocus() }
                            else -> reachMenu(event.key)
                        }
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        // A fresh press starts clean: a long press's release goes to the dialog it opened.
                        if (down && event.nativeKeyEvent.repeatCount == 0) longPressHandled = false
                        if (down && event.nativeKeyEvent.repeatCount > 0 && !longPressHandled) {
                            longPressHandled = true
                            // The Customize panel when the page's rows are known, else the row's dialog.
                            customize?.takeIf { pageRows != null }?.openRow(row, pageRows!!) ?: run { editing = row }
                        } else if (!down) {
                            if (!longPressHandled) onOpen(item)
                            longPressHandled = false
                        }
                        true
                    }
                    Key.MediaPlay, Key.MediaPlayPause -> { if (!down) onPlay(item); true }
                    else -> false
                }
            }
            .focusRequester(pageFocus)
            .focusable(),
    ) {
        // The picture (or the page's chosen background) and the theme over it, drawn by the menu.
        dev.nitrate.tv.ui.effects.PageBackdropSlot(repository.backdropUrl(item))

        val pad = Modifier.padding(start = 58.dp + insets.start, end = 58.dp + insets.end)
        // Captioned as the screensaver is: the row and place as a small label, the title, a quiet line
        // or two, in a bottom corner that alternates side from one title to the next.
        val (capTop, capEnd) = dev.nitrate.tv.ui.components.LocalDisplayPrefs.current.tvCaptionCorner.resolve(current)
        dev.nitrate.tv.ui.components.ArtCaptionShade(top = capTop)
        AnimatedContent(item, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "details", contentKey = { it.id },
            modifier = Modifier.fillMaxSize()) { it ->
            Box(Modifier.fillMaxSize()) {
                val end = capEnd
                dev.nitrate.tv.ui.components.ArtCaption(
                    it, label = "${row.title}  ·  ${current + 1} / ${row.items.size}", end = end,
                    logoUrl = dev.nitrate.tv.ui.components.captionLogo(repository, it, showcase.showLogo, captionSize), size = captionSize,
                    showDetails = showcase.showDetails, showRatings = showcase.showRatings, showOverview = showcase.showOverview,
                    modifier = Modifier.align(when { capTop && end -> Alignment.TopEnd; capTop -> Alignment.TopStart; end -> Alignment.BottomEnd; else -> Alignment.BottomStart })
                        .then(pad).padding(top = if (capTop) 84.dp + insets.top else 0.dp, bottom = if (capTop) 0.dp else 56.dp + insets.bottom),
                )
            }
        }

        header?.let { tabs ->
            Box(
                Modifier.align(Alignment.TopEnd).padding(end = insets.end, top = 20.dp + insets.top)
                    .focusRequester(headerFocus)
                    .onFocusChanged { headerFocused = it.hasFocus }
                    .focusGroup()
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionDown -> { runCatching { pageFocus.requestFocus() }; true }
                            Key.DirectionUp -> { reachMenu(e.key); true }
                            else -> false
                        }
                    },
            ) { tabs() }
        }

    }

    editing?.let { r ->
        RowSettingsDialog(
            row = r,
            display = overrides[r.key] ?: r.defaultDisplay,
            onChange = { onSaveDisplay(r, it) },
            onDismiss = { editing = null },
            onEditPage = { editing = null; onEditPage() },
            fullArt = true,
        )
    }
}

/** Saves the position in each row ("row key" → item index) across navigation. */
private val PositionsSaver = androidx.compose.runtime.saveable.Saver<androidx.compose.runtime.snapshots.SnapshotStateMap<String, Int>, ArrayList<String>>(
    save = { map -> ArrayList(map.map { (k, v) -> "$v:$k" }) },
    restore = { list -> mutableStateMapOf<String, Int>().apply { list.forEach { e -> e.substringBefore(':').toIntOrNull()?.let { put(e.substringAfter(':'), it) } } } },
)
