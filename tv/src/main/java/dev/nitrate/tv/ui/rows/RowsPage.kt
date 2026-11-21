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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import dev.nitrate.core.pages.hidesDescription
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
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
    /** An "Edit page" button after the rows: off everywhere now (Settings → Display → Pages does it; holding OK on a row offers it too). */
    editButton: Boolean = false,
    /**
     * Focus coming into the page goes to the card left, else the first row, never the tabs above them
     * (Home: opening it or coming back to it). Library pages keep it off: they open on their tab.
     */
    startOnRows: Boolean = false,
) {
    var focused by remember { mutableStateOf<MediaItem?>(null) }
    var activeShowcase by remember { mutableStateOf<String?>(null) }
    // The row holding focus: a Full Showcase above it draws as its banner, not a screen-tall picture
    // cut off by the scroll (that left a strip of it over the rows below).
    var focusedRowIndex by remember { mutableStateOf(-1) }
    // The card last focused (its row and item), to come back to from the menu.
    // Saved with the page (it outlives a visit to a details page and back, which recomposes the page):
    // coming back puts focus on that card again rather than the page's start.
    var lastCard by androidx.compose.runtime.saveable.rememberSaveable(stateSaver = LastCardSaver) { mutableStateOf<Pair<Int, String>?>(null) }
    // The card last focused in each row: moving Up/Down back into a row lands on it again, whatever
    // happened in other rows meanwhile (nearest-on-screen depended on the other row's card sizes).
    val rowLast = androidx.compose.runtime.saveable.rememberSaveable(saver = RowLastSaver) { mutableStateMapOf<String, String>() }
    // Each row's own requester (it enters the row at its remembered card): the page is entered at a row.
    val rowEntry = remember { mutableMapOf<String, androidx.compose.ui.focus.FocusRequester>() }
    val returnRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val focusReturn = LocalFocusReturn.current
    DisposableEffect(focusReturn) {
        focusReturn?.restore = { lastCard != null && runCatching { returnRequester.requestFocus() }.isSuccess }
        onDispose { focusReturn?.restore = null }
    }
    // One row at a time, along the bottom (the picture gets the rest), unless the user stacks them.
    val oneAtATime = !LocalDisplayPrefs.current.tvArrangement.stacked
    // The room above the first row (one at a time), so any row can sit at the bottom; px.
    var roomAbove by remember { mutableStateOf(0) }
    val showcaseIndex = remember { mutableStateMapOf<String, Int>() }
    // The picture each cards-hidden row is showing (its title's, or the one its rotation reached).
    val cardlessArt = remember { mutableStateMapOf<String, String?>() }
    var editing by remember { mutableStateOf<MediaRow?>(null) }
    var listingRows by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // Back on the page (from a details page): focus to the card left, once its row is composed.
    val cameBackTo = remember { lastCard }
    LaunchedEffect(cameBackTo, rows.isNotEmpty()) {
        if (cameBackTo == null || rows.isEmpty()) return@LaunchedEffect
        repeat(40) {
            kotlinx.coroutines.delay(50)
            runCatching { returnRequester.requestFocus() }
            kotlinx.coroutines.delay(30)
            if (focused?.id == cameBackTo.second) return@LaunchedEffect
        }
    }
    PrefetchRows(listState, rows, overrides, repository)

    // Holding OK on a row opens the Customize panel (pages/CustomizePanel) when the page's rows are
    // known; else the row's own dialog.
    val customize = dev.nitrate.tv.ui.pages.LocalCustomizeHost.current
    val pageRows = dev.nitrate.tv.ui.pages.LocalPageRows.current
    // Home's "Watching together" row: its invite and start cards answer OK themselves, and holding OK
    // on any of its cards offers the show's choices (with Customize this row among them).
    val together = dev.nitrate.tv.ui.partners.LocalTogetherCards.current
    val currentRows by androidx.compose.runtime.rememberUpdatedState(rows)
    fun inTogetherRow() = lastCard?.first?.let { currentRows.getOrNull(it)?.key } == dev.nitrate.core.partners.TogetherRow.KEY
    val actions = remember(onOpen, onPlay, customize, pageRows, together) {
        fun customizeRow(row: MediaRow) { customize?.takeIf { pageRows != null }?.openRow(row, pageRows!!) ?: run { editing = row } }
        RowActions(
            onFocus = { focused = it },
            onOpen = { item -> if (together == null || !inTogetherRow() || !together.select(item)) onOpen(item) },
            onPlay = { item ->
                if (together != null && inTogetherRow() && together.select(item)) Unit
                else if (item.isPlayable) onPlay(item) else onOpen(item)
            },
            onEditRow = { row ->
                val card = focused
                if (together != null && row.key == dev.nitrate.core.partners.TogetherRow.KEY && card != null && inTogetherRow()) together.hold(card) { customizeRow(row) }
                else customizeRow(row)
            },
        )
    }

    LaunchedEffect(activeShowcase) {
        val key = activeShowcase ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it.key == key }
        if (index < 0) return@LaunchedEffect
        // One at a time the list has a screen's room above its rows: past it, so the showcase's top meets
        // the list's (once it has grown to the screen's height).
        if (oneAtATime) { kotlinx.coroutines.delay(32); listState.animateScrollToItem(index, roomAbove) }
        else listState.animateScrollToItem(index)
    }

    // Up to the tabs: the first row back exactly in its place under them (the search for something
    // above it could leave the list part-scrolled, the row's foot off the screen).
    LaunchedEffect(focusedRowIndex) {
        if (oneAtATime && focusedRowIndex == HEADER_FOCUSED && rows.isNotEmpty()) {
            kotlinx.coroutines.delay(32)
            listState.animateScrollToItem(0, roomAbove)
        }
    }

    // "Row: style" for a moment after Fast-forward/Rewind changes one.
    var styleNote by remember { mutableStateOf<Pair<String, Long>?>(null) }
    LaunchedEffect(styleNote) { if (styleNote != null) { kotlinx.coroutines.delay(2_500); styleNote = null } }
    // Focus coming into the page (opening it, coming back to it) goes to the card left, else the first
    // row: never the tabs above them, which are the first thing on the page (see startOnRows).
    Box(modifier.fillMaxSize()
        .focusProperties {
            if (startOnRows) enter = {
                when {
                    lastCard != null -> returnRequester
                    else -> rows.firstOrNull { it.items.isNotEmpty() }?.let { rowEntry[it.key] } ?: androidx.compose.ui.focus.FocusRequester.Default
                }
            }
        }
        .focusGroup()) {
        // The title's picture (or the page's chosen background) and the theme over it: drawn by the menu
        // around the page, so they carry on through tab switches.
        // A row with its cards hidden, in use, puts its own title's picture there instead.
        val usedRow = if (focusedRowIndex in rows.indices) focusedRowIndex else 0
        val artKey = activeShowcase ?: rows.getOrNull(usedRow)?.takeIf { oneAtATime && (overrides[it.key] ?: it.defaultDisplay).normalized().cardsHidden }?.key
        // Until that row has said which picture it shows, its own title's (never the last row's), so the
        // background goes straight to the right picture rather than through another one.
        val artFor = { key: String ->
            cardlessArt[key] ?: rows.firstOrNull { it.key == key }?.let { r ->
                r.items.getOrNull((showcaseIndex[key] ?: 0).coerceIn(0, (r.items.size - 1).coerceAtLeast(0)))
            }?.let { repository.backdropUrls(it).firstOrNull() ?: repository.backdropUrl(it) }
        }
        // One at a time the caption sits in a corner with its own shade: the picture whole, as the
        // screensaver shows it. Stacked keeps the wash for the summary over the top left.
        dev.nitrate.tv.ui.effects.PageBackdropSlot(artKey?.let(artFor) ?: focused?.let(repository::backdropUrl), clean = oneAtATime)
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
            // The page's tabs, at the very top (above the focused title, as they're top right on full
            // art): there while on them or the first row, out of the rows' way further down. They
            // count as above every row, so a Full Showcase below shows as its banner meanwhile.
            val tabsShown = focusedRowIndex <= 0 || focusedRowIndex == HEADER_FOCUSED
            if (header != null && oneAtATime) {
                // One at a time the tabs keep their place and only fade: collapsing them moved the
                // whole page each time the first row was left or reached.
                val tabsAlpha by androidx.compose.animation.core.animateFloatAsState(if (tabsShown) 1f else 0f, label = "tabs")
                androidx.compose.foundation.layout.Box(Modifier.graphicsLayer { alpha = tabsAlpha }.onFocusChanged { if (it.hasFocus) focusedRowIndex = HEADER_FOCUSED }) { header() }
            } else if (header != null) AnimatedVisibility(visible = tabsShown) {
                androidx.compose.foundation.layout.Box(Modifier.onFocusChanged { if (it.hasFocus) focusedRowIndex = HEADER_FOCUSED }) { header() }
            }
            // One at a time, the title's caption sits beside its row along the bottom instead.
            if (!oneAtATime) AnimatedVisibility(visible = activeShowcase == null) {
                // A clear gap below, so the first row's title never runs into the description.
                // Just tall enough for its title, facts and two lines (it took nearly half the screen).
                FocusedSummary(focused, Modifier.padding(start = RowPadding, top = 20.dp, end = RowPadding, bottom = 14.dp).height(148.dp))
            }
            // TVs scroll focused content to a fixed "pivot", which pushes a tall row's title off the
            // top: up and down, scroll just enough to show the whole row; rows keep the TV's own way.
            val rowsScroll = androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val viewport = with(density) { maxHeight.roundToPx() }
            val bottomGap = with(density) { 36.dp.toPx() }
            if (oneAtATime) roomAbove = (viewport - 1).coerceAtLeast(0)
            val spec = remember(oneAtATime, bottomGap) { if (oneAtATime) RowAtBottom(bottomGap) else WholeRowInView }
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides spec) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = with(density) { (if (oneAtATime) roomAbove else 0).toDp() }, bottom = if (oneAtATime) 0.dp else 48.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(rows, key = { _, row -> row.key }) { rowIndex, row ->
                    // Rows added or removed by a reload slide and fade rather than jump.
                    // Moving into a row scrolls the whole row into view, its title included (a tall
                    // row otherwise brings only the focused card in, leaving its title out of sight).
                    val wholeRow = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
                    val rowScope = androidx.compose.runtime.rememberCoroutineScope()
                    // Changing a row's style swaps its cards for others: focus waits on a holder in the
                    // row meanwhile (else it falls to the menu), then goes into the new style.
                    val holder = remember { androidx.compose.ui.focus.FocusRequester() }
                    val content = remember { androidx.compose.ui.focus.FocusRequester() }
                    val rowReturn = remember { androidx.compose.ui.focus.FocusRequester() }
                    DisposableEffect(row.key) { rowEntry[row.key] = content; onDispose { if (rowEntry[row.key] === content) rowEntry.remove(row.key) } }
                    var holding by remember { mutableStateOf(false) }
                    // The holder has focus (a style change waiting for its cards).
                    var holderFocused by remember { mutableStateOf(false) }
                    // A card in the row (not the holder) has focus.
                    var contentFocused by remember { mutableStateOf(false) }
                    // The look a Fast-forward/Rewind asked for and not yet saved: quick presses step on from
                    // it, not from the saved look (two presses used to pick the same style).
                    var pendingLook by remember { mutableStateOf<RowDisplay?>(null) }
                    // One at a time: only the row in use shows (the first while the tabs have focus).
                    val current = if (focusedRowIndex in rows.indices) focusedRowIndex else 0
                    val shown by androidx.compose.animation.core.animateFloatAsState(if (!oneAtATime || rowIndex == current) 1f else 0f, label = "row")
                    androidx.compose.foundation.layout.Box(
                        Modifier.animateItem()
                            .graphicsLayer { alpha = shown }
                            .bringIntoViewRequester(wholeRow)
                            // Opening the page (and while on the tabs): the first row up into its place
                            // along the bottom, again whenever it changes size (its art and titles arriving).
                            // The row in use back into place whenever it changes size: a showcase growing to
                            // the full screen, art and titles arriving (the first row also while on the tabs).
                            .then(if (oneAtATime) Modifier.onSizeChanged {
                                if (rowIndex == current) rowScope.launch { wholeRow.placeRow(spec) }
                            } else Modifier)
                            .onFocusChanged { if (it.hasFocus) { focusedRowIndex = rowIndex; rowScope.launch { wholeRow.placeRow(spec) } } }
                            // Fast-forward / Rewind on a row: its next / previous artwork style, there and then.
                            .onPreviewKeyEvent { e ->
                                val by = when (e.key) { Key.MediaFastForward -> 1; Key.MediaRewind -> -1; else -> 0 }
                                if (by == 0) return@onPreviewKeyEvent false
                                if (e.type == KeyEventType.KeyDown) {
                                    val now = pendingLook ?: (overrides[row.key] ?: row.defaultDisplay).normalized()
                                    val next = now.stepped(by)
                                    pendingLook = next
                                    if (activeShowcase == row.key) activeShowcase = null
                                    holding = true
                                    runCatching { holder.requestFocus() }
                                    onSaveDisplay(row, next)
                                    styleNote = "${row.title}: ${next.styleLabel}" to System.nanoTime()
                                }
                                true
                            },
                    ) {
                        androidx.compose.foundation.layout.Box(
                            Modifier.size(1.dp).focusRequester(holder).onFocusChanged { holderFocused = it.isFocused }
                                .focusProperties { canFocus = holding }.focusable(),
                        )
                        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides rowsScroll) {
                        val display = (overrides[row.key] ?: row.defaultDisplay).normalized()
                        // The new style is on screen: into it, and the whole row back in view at its new height.
                        LaunchedEffect(display.style, display.cards) {
                            // Only the look the last press asked for takes focus: an earlier one arriving
                            // late (quick presses) leaves it on the holder for the next.
                            val waited = pendingLook
                            if (waited != null && waited != display) return@LaunchedEffect
                            if (!holding && waited == null) return@LaunchedEffect
                            // Some styles lay their cards out a beat later (the film strip): keep asking
                            // until one of them has focus.
                            var tries = 0
                            do {
                                kotlinx.coroutines.delay(80)
                                if (pendingLook != null && pendingLook != display) return@LaunchedEffect
                                runCatching { content.requestFocus() }
                            } while (!contentFocused && ++tries < 50)
                            pendingLook = null
                            holding = false
                            kotlinx.coroutines.delay(60)
                            wholeRow.placeRow(spec)
                        }
                        // Never left on the holder: if the look asked for never arrives (its save failed), give
                        // focus back to the row. A slow save is waited for: the look takes focus when it comes.
                        LaunchedEffect(holding) {
                            if (!holding) return@LaunchedEffect
                            kotlinx.coroutines.delay(10_000)
                            if (holding) { runCatching { content.requestFocus() }; holding = false; pendingLook = null }
                        }
                        val prefs = LocalDisplayPrefs.current
                        val fx = dev.nitrate.tv.ui.effects.rememberCardFx(display, prefs.effects, prefs.easyMode)
                        val rowActions = remember(actions, rowIndex) {
                            RowActions(onFocus = { item -> lastCard = rowIndex to item.id; rowLast[row.key] = item.id; actions.onFocus(item) }, onOpen = actions.onOpen, onPlay = actions.onPlay, onEditRow = actions.onEditRow)
                        }
                        val actions = fx?.wrap(rowActions) ?: rowActions
                        androidx.compose.runtime.CompositionLocalProvider(
                            dev.nitrate.tv.ui.effects.LocalCardFx provides fx,
                            // The card left in this row takes a requester: the page's own (for the menu) when it
                            // was the last card anywhere, else the row's (for coming back into the row).
                            LocalReturnTarget provides (lastCard?.takeIf { it.first == rowIndex }?.let { ReturnTarget(it.second, returnRequester) }
                                ?: rowLast[row.key]?.let { ReturnTarget(it, rowReturn) }),
                        ) {
                        // Left/Right stay in the row: past its end nothing happens (it used to jump to
                        // whatever was nearest that way, the tabs or a hidden row), except towards the
                        // menu, which they still reach. Up/Down go between rows as before.
                        val menuSide = dev.nitrate.tv.ui.home.LocalMenuSide.current
                        val reachMenu = dev.nitrate.tv.ui.home.LocalMenuEdge.current
                        val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
                        androidx.compose.foundation.layout.Box(Modifier.onFocusChanged { contentFocused = it.hasFocus }.focusRequester(content)
                            .focusProperties {
                                // Into the row: the card left in it, if it's still there; else the nearest as usual.
                                // (Redirected, not requested here: cancelling the move sent an Up on to the tabs.)
                                enter = {
                                    val requester = if (lastCard?.first == rowIndex) returnRequester else rowReturn
                                    val left = rowLast[row.key]
                                    if (left != null && row.items.any { it.id == left }) requester
                                    else androidx.compose.ui.focus.FocusRequester.Default
                                }
                                exit = { d ->
                                    val key = when (d) { androidx.compose.ui.focus.FocusDirection.Left -> Key.DirectionLeft; androidx.compose.ui.focus.FocusDirection.Right -> Key.DirectionRight; else -> null }
                                    when {
                                        key == null -> androidx.compose.ui.focus.FocusRequester.Default
                                        // Towards the menu: straight to it (else the tabs, up and to the side, could
                                        // be "nearest" with the caption beside the row).
                                        menuSide(key) -> { mainHandler.post { reachMenu(key) }; androidx.compose.ui.focus.FocusRequester.Cancel }
                                        else -> androidx.compose.ui.focus.FocusRequester.Cancel
                                    }
                                }
                            }
                            .focusGroup()) {
                        val hidden = activeShowcase != null && activeShowcase != row.key
                        if (display.cardsHidden) {
                            val active = activeShowcase == row.key
                            // One at a time every such row is always a screen of its own, never a banner: rows next to the
                            // one in use slide in and out as the page moves, and a banner of the row's artwork there
                            // flashed past on every move. Stacked, it's the screen while it has focus (the others
                            // step aside), else a banner.
                            val expanded = active || oneAtATime
                            CardlessRow(
                                row = row,
                                display = display,
                                repository = repository,
                                actions = actions,
                                index = showcaseIndex[row.key] ?: 0,
                                onIndexChange = { showcaseIndex[row.key] = it },
                                onActiveChange = { isActive ->
                                    // One at a time every row has the screen to itself already: nothing to step aside.
                                    if (oneAtATime) Unit
                                    else if (isActive) activeShowcase = row.key
                                    else if (activeShowcase == row.key) activeShowcase = null
                                },
                                expanded = expanded,
                                onArtwork = { cardlessArt[row.key] = it },
                                modifier = Modifier.alpha(if (hidden) 0f else 1f)
                                    // The screen's height (not the list's: one at a time it has a screen's room above its rows).
                                    .then(if (expanded || oneAtATime) Modifier.height(with(density) { viewport.toDp() }) else Modifier),
                            )
                        } else {
                            val cards: @Composable () -> Unit = {
                                when (display.style) {
                                    // The page's own summary names the focused title: no second caption.
                                    RowStyle.FILM_STRIP -> FilmStripRow(row, repository, actions, display.size, caption = false)
                                    RowStyle.LIGHTNING -> LightningRow(row, repository, actions, display.size)
                                    RowStyle.CRESSIDA -> CressidaRow(row, repository, actions, display)
                                    else -> CardRow(row, display, repository, actions)
                                }
                            }
                            if (oneAtATime) {
                                // The screensaver's way: the title's caption (the row and place as its label) in
                                // its corner, the row along the top or bottom or down a side; the row fills the
                                // screen as one layer, so each can go exactly where it's set.
                                val p = LocalDisplayPrefs.current
                                val place = p.tvRowPlace
                                val (capTop, capEnd) = p.tvCaptionCorner.resolve(rowIndex, place)
                                // The row's own caption size, else the page's.
                                val capSize = display.captionSize ?: p.tvCaptionSize
                                val compact = capSize != dev.nitrate.core.pages.CaptionSize.NORMAL
                                val shownItem = focused?.takeIf { f -> row.items.any { it.id == f.id } } ?: row.items.firstOrNull()
                                val number = shownItem?.let { f -> row.items.indexOfFirst { it.id == f.id } + 1 } ?: 0
                                val captionPad = Modifier.padding(start = if (capEnd) 0.dp else RowPadding, end = if (capEnd) RowPadding else 0.dp, top = if (capTop) 12.dp else 0.dp, bottom = if (capTop) 0.dp else 10.dp)
                                val caption: @Composable (Modifier) -> Unit = { m ->
                                    if (shownItem != null) dev.nitrate.tv.ui.components.ArtCaption(
                                        shownItem, label = "${row.title}  ·  $number / ${row.items.size}", end = capEnd,
                                        logoUrl = dev.nitrate.tv.ui.components.captionLogo(repository, shownItem, size = capSize),
                                        size = capSize, compact = compact, modifier = m.then(captionPad).width(dev.nitrate.tv.ui.components.captionWidth(compact)),
                                    )
                                }
                                val partial = p.tvRowExtent == dev.nitrate.core.pages.RowExtent.PARTIAL
                                Box(Modifier.alpha(if (hidden) 0f else 1f).fillMaxWidth().height(with(density) { viewport.toDp() })) {
                                    dev.nitrate.tv.ui.components.ArtCaptionShade(top = capTop, side = if (place.horizontal) null else place == dev.nitrate.core.pages.RowPlace.RIGHT)
                                    if (place.horizontal && capTop != (place == dev.nitrate.core.pages.RowPlace.TOP)) dev.nitrate.tv.ui.components.ArtCaptionShade(top = !capTop)
                                    CompositionLocalProvider(LocalRowTitle provides false) {
                                        if (place.horizontal) {
                                            val rowTop = place == dev.nitrate.core.pages.RowPlace.TOP
                                            val edge = if (rowTop) Alignment.TopStart else Alignment.BottomStart
                                            if (capTop == rowTop) {
                                                // On the same line: the row starts beside the caption (part way, always).
                                                androidx.compose.foundation.layout.Row(Modifier.align(edge).padding(top = if (rowTop) 8.dp else 0.dp, bottom = if (rowTop) 0.dp else RowFoot), verticalAlignment = if (rowTop) Alignment.Top else Alignment.Bottom) {
                                                    if (!capEnd) caption(Modifier)
                                                    Box(Modifier.weight(1f)) { cards() }
                                                    if (capEnd) caption(Modifier)
                                                }
                                            } else {
                                                caption(Modifier.align(corner(capTop, capEnd)))
                                                Box(Modifier.align(if (rowTop) (if (partial && capEnd) Alignment.TopEnd else Alignment.TopStart) else (if (partial && capEnd) Alignment.BottomEnd else Alignment.BottomStart))
                                                    .padding(top = if (rowTop) 8.dp else 0.dp, bottom = if (rowTop) 0.dp else RowFoot)
                                                    .fillMaxWidth(if (partial) PartialShare else 1f)) { cards() }
                                            }
                                        } else {
                                            val rowEnd = place == dev.nitrate.core.pages.RowPlace.RIGHT
                                            val sidePad = Modifier.padding(start = if (rowEnd) 0.dp else RowPadding - 16.dp, end = if (rowEnd) RowPadding - 16.dp else 0.dp)
                                            val column: @Composable (Modifier) -> Unit = { m -> ColumnCards(row, display, repository, actions, m.width(ColumnWidth)) }
                                            if (capEnd == rowEnd) {
                                                // On the same side: the caption heads (or ends) the column, which takes the rest.
                                                Column(Modifier.align(if (rowEnd) Alignment.TopEnd else Alignment.TopStart).fillMaxHeight(),
                                                    horizontalAlignment = if (rowEnd) Alignment.End else Alignment.Start) {
                                                    if (capTop) caption(Modifier)
                                                    column(Modifier.weight(1f).then(sidePad))
                                                    if (!capTop) caption(Modifier)
                                                }
                                            } else {
                                                caption(Modifier.align(corner(capTop, capEnd)))
                                                column(Modifier.align(corner(!partial || capTop, rowEnd)).then(sidePad).fillMaxHeight(if (partial) PartialShare else 1f))
                                            }
                                        }
                                    }
                                }
                            } else Box(Modifier.alpha(if (hidden) 0f else 1f)) { cards() }
                        }
                        }
                        }
                        }
                    }
                }
                if (editButton) item(key = "customize-rows") {
                    OutlinedButton(onClick = { onEditPage?.invoke() ?: run { listingRows = true } }, modifier = Modifier.padding(start = RowPadding)) {
                        Text(if (onEditPage != null) "Edit page" else "Customize rows")
                    }
                }
                footer()
            }
            }
            }
        }
    }

    editing?.let { row ->
        RowSettingsDialog(
            row = row,
            display = (overrides[row.key] ?: row.defaultDisplay).normalized(),
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
            // The user's word in bold, then each partner's ("Behind · Kevin 2 ahead · Kim 1 behind").
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    append(androidx.compose.ui.text.AnnotatedString(st.word, androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)))
                    if (st.partners.isNotEmpty()) append("  ·  " + st.partners)
                },
                style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (st.catchUp) Color(0xFFFFC94D) else Color(0xDDFFFFFF),
            )
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
/** One at a time: the row in use sits along the bottom, [gap] px up from the edge (a taller one from the top). */
private class RowAtBottom(private val gap: Float) : androidx.compose.foundation.gestures.BringIntoViewSpec {
    /** Set while a row asks to come into place; a card's own request (focus landing on it) only scrolls when it's out of view. */
    var rowRequest = false
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
        // A card's own request never moves the page: its row is already in place (focus arriving in a
        // row places it), and art cards report their design size, larger than drawn, which pulled the
        // row up the screen.
        !rowRequest -> 0f
        size + gap <= containerSize -> offset + size - (containerSize - gap)
        else -> offset
    }
}

/** Brings a row into its place along the bottom (one at a time) or whole into view (stacked). */
private suspend fun androidx.compose.foundation.relocation.BringIntoViewRequester.placeRow(spec: androidx.compose.foundation.gestures.BringIntoViewSpec) {
    val atBottom = spec as? RowAtBottom
    atBottom?.rowRequest = true
    try { bringIntoView() } finally { atBottom?.rowRequest = false }
}

private object WholeRowInView : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
        offset < 0 -> offset
        size <= containerSize -> if (offset + size > containerSize) offset + size - containerSize else 0f
        offset <= containerSize / 3 -> 0f
        else -> offset
    }
}

/** A little room under a row along the bottom, so a focused (enlarged) card stays on screen. */
private val RowFoot = 14.dp

/** How much of the page a row part way takes (one at a time). */
private const val PartialShare = 0.62f

private fun corner(top: Boolean, end: Boolean) = when {
    top && end -> Alignment.TopEnd
    top -> Alignment.TopStart
    end -> Alignment.BottomEnd
    else -> Alignment.BottomStart
}

/** [RowsPage]'s focused row while the page's header (its tabs) has focus. */
private const val HEADER_FOCUSED = Int.MAX_VALUE

/** The card last focused (row index, item id), kept with the page's saved state. */
private val LastCardSaver = androidx.compose.runtime.saveable.Saver<Pair<Int, String>?, String>(
    save = { c -> c?.let { "${it.first}|${it.second}" } ?: "" },
    restore = { v -> v.takeIf { it.isNotEmpty() }?.let { it.substringBefore('|').toInt() to it.substringAfter('|') } },
)

/** Each row's last card (row key to item id), kept with the page's saved state. */
private val RowLastSaver = androidx.compose.runtime.saveable.Saver<androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>, ArrayList<String>>(
    save = { m -> ArrayList(m.flatMap { listOf(it.key, it.value) }) },
    restore = { l -> mutableStateMapOf<String, String>().apply { l.chunked(2).forEach { (k, v) -> put(k, v) } } },
)
