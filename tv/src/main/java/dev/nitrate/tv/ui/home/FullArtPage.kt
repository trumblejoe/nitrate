package dev.nitrate.tv.ui.home

import dev.nitrate.tv.ui.effects.cardFx
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
import androidx.compose.runtime.SideEffect
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
    // Every row style shows titles spoiler-safe (named "Episode 5", blurred, no description).
    @Suppress("NAME_SHADOWING") val rows = dev.nitrate.tv.ui.partners.spoilerSafeRows(rows)
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
    // The menu's Customize stop opens on this row.
    SideEffect { customize?.lastRow = row; customize?.lastRows = pageRows }
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
    // The row's effects at the screen's scale: sparks over the whole picture, the click on the caption.
    val prefs = dev.nitrate.tv.ui.components.LocalDisplayPrefs.current
    val fx = dev.nitrate.tv.ui.effects.rememberCardFx(overrides[row.key] ?: row.defaultDisplay, prefs.effects, prefs.easyMode)
    val plainActions = remember(onOpen, onPlay) { dev.nitrate.tv.ui.rows.RowActions(onFocus = {}, onOpen = onOpen, onPlay = onPlay, onEditRow = {}) }
    val open = (fx?.wrap(plainActions) ?: plainActions).onOpen
    // Home's Watching together row: an invite or the start card answers OK itself, and holding OK on
    // a shared card offers its group's choices (with Customize this row among them), as on rows pages.
    val together = dev.nitrate.tv.ui.partners.LocalTogetherCards.current?.takeIf { row.key in dev.nitrate.core.partners.TogetherRow.ROW_KEYS }

    val along = LocalDisplayPrefs.current.tvUpDownAlong
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
                    // "Up/Down along the row": ▼▲ step titles first, and only past the last (first) one go on
                    // to the next row's first title (the previous row's last).
                    Key.DirectionDown -> {
                        if (down) when {
                            along && current < row.items.lastIndex -> itemIndex[row.key] = current + 1
                            rowIndex < shown.lastIndex -> { val next = shown[rowIndex + 1]; if (along) itemIndex[next.key] = 0; rowKey = next.key }
                            else -> reachMenu(event.key)
                        }
                        true
                    }
                    Key.DirectionUp -> {
                        // Held: straight to the first row (on long pages that's many presses).
                        if (down && event.nativeKeyEvent.repeatCount > 0) {
                            if (event.nativeKeyEvent.repeatCount == 1 && rowIndex > 0) rowKey = shown[0].key
                            return@onPreviewKeyEvent true
                        }
                        if (down) when {
                            along && current > 0 -> itemIndex[row.key] = current - 1
                            rowIndex > 0 -> { val previous = shown[rowIndex - 1]; if (along) itemIndex[previous.key] = previous.items.lastIndex.coerceAtLeast(0); rowKey = previous.key }
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
                            val customizeRow = { customize?.takeIf { pageRows != null }?.openRow(row, pageRows!!) ?: run { editing = row } }
                            if (together != null && together.shares(item)) together.hold(item) { customizeRow() } else customizeRow()
                        } else if (!down) {
                            if (!longPressHandled && together?.select(item) != true) open(item)
                            longPressHandled = false
                        }
                        true
                    }
                    Key.MediaPlay, Key.MediaPlayPause -> { if (!down && together?.select(item) != true) onPlay(item); true }
                    else -> false
                }
            }
            .focusRequester(pageFocus)
            .focusable(),
    ) {
        // The picture (or the page's chosen background) and the theme over it, drawn by the menu.
        dev.nitrate.tv.ui.effects.PageBackdropSlot(repository.backdropUrl(item), clean = true)
      androidx.compose.runtime.CompositionLocalProvider(dev.nitrate.tv.ui.effects.LocalCardFx provides fx) {
        // Sparks over the whole picture, under the caption.
        Box(Modifier.fillMaxSize().then(Modifier.cardFx(item.id, click = false)))

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
                        .cardFx(it.id, spark = false)
                        .then(pad).padding(top = if (capTop) 84.dp + insets.top else 0.dp, bottom = if (capTop) 0.dp else 56.dp + insets.bottom),
                )
            }
        }

      }

        // Which row this is, on the side away from the menu.
        if (dev.nitrate.tv.ui.components.LocalDisplayPrefs.current.tvRowMarker) {
            val menuRight = dev.nitrate.tv.ui.home.LocalMenuSide.current(Key.DirectionRight)
            dev.nitrate.tv.ui.components.RowMarker(
                rowIndex, shown.size,
                Modifier.align(if (menuRight) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(start = 18.dp + insets.start, end = 18.dp + insets.end),
            )
        }

        header?.let { tabs ->
            Box(
                Modifier.align(Alignment.TopEnd).padding(end = insets.end + dev.nitrate.tv.ui.components.LocalClockRoom.current, top = 20.dp + insets.top)
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
