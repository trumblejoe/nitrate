package dev.nitrate.mobile.ui

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.aspectRatio
import dev.nitrate.core.model.ImageKind
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.lazy.LazyRow
import dev.nitrate.core.rows.RowStyle
import dev.nitrate.core.rows.RowDisplay
import dev.nitrate.core.rows.ArtworkFirst
import dev.nitrate.core.pages.facts
import dev.nitrate.core.pages.isSpoiler
import dev.nitrate.core.pages.PageKeys
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import dev.nitrate.android.viewmodel.RowsState
import dev.nitrate.android.viewmodel.RowsViewModel
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.formatRuntime
import dev.nitrate.core.rows.MediaRow
import dev.nitrate.mobile.platform.rememberArtworkColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

/** Home: the user's rows, artwork first, each styled from its own header. */
@Composable
fun HomeTab(actions: AppActions, modifier: Modifier = Modifier, onSearch: () -> Unit = {}) {
    val container = actions.container
    // Artwork first: rows the user hasn't styled show as showcases or artwork, not posters.
    val vm = viewModel(key = "home") { RowsViewModel(PageKeys.HOME, container.server::homeRows, container.displaySettings, container.server, container.serverChanges, ArtworkFirst::display, cache = container.dataCache, loadProgressSkipping = dev.nitrate.android.viewmodel.homeRowsLoader(container.server, container.displaySettings, container.playlists)) }
    val state by vm.state.collectAsStateWithLifecycle()
    // Continue Watching and Next Up as one row or two: the page is loaded again when that changes.
    val combineRows = dev.nitrate.mobile.ui.LocalDisplayPrefs.current.combineContinueNextUp
    var combineSeen by remember { mutableStateOf(combineRows) }
    LaunchedEffect(combineRows) { if (combineRows != combineSeen) { combineSeen = combineRows; vm.refresh() } }
    val overrides by vm.overrides.collectAsStateWithLifecycle()
    // The spinner shows for a pull, until that reload has finished (not for quiet background reloads).
    val reloading by vm.refreshing.collectAsStateWithLifecycle()
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(reloading) { if (!reloading) pulled = false }
    val refreshing = pulled && reloading
    var editing by remember { mutableStateOf(false) }
    if (editing) PageEditor("Home", vm, actions, libraryId = null, onDone = { editing = false })

    // Refresh "Continue Watching" when coming back from the player or details.
    // Saved with the screen, so coming back (from the player) counts as coming back.
    var first by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        if (!first) vm.refresh()
        first = false
        onPauseOrDispose { }
    }

    TogetherHost(actions, partnersAvailable(actions)) {
    when (val s = state) {
        RowsState.Loading -> RowsPlaceholder(modifier.padding(top = 56.dp))
        is RowsState.Error -> ErrorMessage(s.message, vm::refresh, modifier)
        is RowsState.Loaded -> {
            // The page takes its colour from the first piece of artwork on it.
            val lead = s.rows.firstOrNull { it.items.isNotEmpty() && !it.key.endsWith("my_media") }?.items?.firstOrNull()
            val tint by rememberArtworkColor(lead?.let { actions.server.backdropUrl(it, 320) ?: actions.server.imageUrl(it) })
            val list = rememberLazyListState()
            PrefetchRows(list, s.rows, overrides, actions.server)

            TintedBackdrop(tint, modifier) {
                PullToRefreshBox(isRefreshing = refreshing, onRefresh = { pulled = true; vm.refresh() }) {
                    LazyColumn(state = list, contentPadding = PaddingValues(bottom = NavBarClearance + 24.dp), verticalArrangement = Arrangement.spacedBy(28.dp), modifier = Modifier.fillMaxSize()) {
                        // Room for the wordmark bar.
                        item(key = "top") { Spacer(Modifier.windowInsetsPadding(WindowInsets.statusBars).height(56.dp)) }
                        item(key = "pages") { PinnedPages(actions) }
                        items(s.rows, key = { it.key }) { row ->
                            // Rows added or removed by a reload slide and fade rather than jump.
                            Box(Modifier.animateItem()) {
                                androidx.compose.runtime.CompositionLocalProvider(LocalPagePanel provides remember(vm) { PagePanel("Home", vm, null) }) {
                                    CinematicRow(row, overrides[row.key] ?: row.defaultDisplay, actions, onDisplay = { vm.save(row, it) })
                                }
                            }
                        }
                        item(key = "edit") {
                            Box(Modifier.fillMaxWidth(), Alignment.Center) { PillButton("Edit Home", Icons.Default.Edit, primary = false, onClick = { editing = true }) }
                        }
                    }
                }
                // Wordmark and search; a scrim fades in once the page scrolls under them.
                val scrolled = list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 40
                val scrim by androidx.compose.animation.core.animateFloatAsState(if (scrolled) 0.92f else 0f, label = "scrim")
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background.copy(alpha = scrim)).windowInsetsPadding(WindowInsets.statusBars)
                        .padding(start = ScreenPadding, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Wordmark(Modifier.weight(1f))
                    IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, "Edit Home", tint = Color.White) }
                    IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "Search", tint = Color.White) }
                }
            }
        }
    }
    }
}

/** NITRATE, letter-spaced, with an accent dot. */
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("NITRATE", color = Color.White, fontWeight = FontWeight.Black, letterSpacing = 4.sp, style = MaterialTheme.typography.titleMedium)
        Box(Modifier.padding(start = 4.dp).size(6.dp).clip(CircleShape).background(accentColor()))
    }
}

/** A big rounded button: accent-filled when [primary], glass otherwise. */
@Composable
fun PillButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, primary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = accentColor()
    val bg = if (primary) accent else Color.White.copy(alpha = 0.14f)
    val fg = if (primary) contentOn(accent) else Color.White
    Row(
        modifier.clip(RoundedCornerShape(28.dp)).background(bg).pressable(onClick).padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = fg, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * A row as posters, artwork (wide pictures; with its cards hidden, one title at a time filling the
 * screen) or one of the art styles, per the row's [display]. With [onDisplay], the header offers the choice.
 */
@Composable
fun CinematicRow(row: MediaRow, shownAs: RowDisplay, rowActions: AppActions, onDisplay: ((RowDisplay) -> Unit)? = null) {
    // Every row style shows titles spoiler-safe (named "Episode 5", blurred, no description).
    @Suppress("NAME_SHADOWING") val row = spoilerSafeRows(listOf(row)).first()
    // What's watched together sits in Continue Watching: invites answer a tap themselves, a long press
    // on a shared title offers its choices, and those cards say whose turn it is. Others are as anywhere.
    val cards = if (row.key in dev.nitrate.core.partners.TogetherRow.ROW_KEYS) LocalTogetherCards.current else null
    // Watch groups switched off on this device: its row isn't shown (its cards would open nothing that works).
    if (row.key in dev.nitrate.core.partners.TogetherRow.ROW_KEYS && cards == null) return
    val actions = if (cards != null) rowActions.withCards(cards) else rowActions
    val partnersInfo by rowActions.container.partners.collectAsState()
    val standings by rowActions.container.groupStandings.collectAsState()
    // A group's card in another row (half-watched with the group, Watching together hidden) says so too.
    fun line(item: dev.nitrate.core.model.MediaItem) = if ((cards == null || !cards.shares(item)) && item.watchGroupId == null) null else dev.nitrate.core.partners.TogetherRow.line(item, partnersInfo, standings)?.short
    // Retired styles show as what replaced them (Showcase as Artwork, Full Showcase with the cards hidden).
    val display = shownAs.normalized()
    // Poster and Artwork rows: the next cards' pictures load before they scroll in (the other styles do their own).
    val cardState = rememberLazyListState()
    if (display.style == RowStyle.POSTER || display.style == RowStyle.WIDE) {
        PrefetchAlongRow(cardState, row.items) { listOf(actions.server.rowCardImage(it, display)) }
    }
    // Cards hidden: the whole screen, with its own header over the artwork.
    if (display.cardsHidden) {
        FullShowcaseRow(row, display, actions, onDisplay)
        return
    }
    Column {
        SectionHeader(row.title) { if (onDisplay != null) RowStylePicker(row, display, onDisplay, actions) }
        when (display.style) {
            RowStyle.FILM_STRIP -> FilmStripRow(row, actions)
            RowStyle.LIGHTNING -> LightningRow(row, actions)
            RowStyle.CRESSIDA -> CressidaRow(row, actions, display)
            RowStyle.WIDE -> LazyRow(state = cardState, contentPadding = PaddingValues(horizontal = ScreenPadding), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                itemsIndexed(row.items, key = { i, it -> "$i:${it.id}" }) { _, item ->
                    val key = "${row.key}-${item.id}"
                    MediaCard(
                        item = item,
                        imageUrl = actions.server.cardImage(item, CardShape.WIDE),
                        shape = CardShape.WIDE,
                        width = 272.dp,
                        title = item.displayTitle.takeIf { display.showTitles },
                        subtitle = line(item) ?: listOfNotNull(dev.nitrate.core.model.AirDates.status(item, dev.nitrate.android.nowMs()) ?: item.episodeLabel ?: item.productionYear?.toString(), timeLeft(item)).joinToString(" · ").takeIf { display.showTitles },
                        sharedKey = key,
                        onClick = { actions.open(item, key) },
                        onLongClick = cards?.takeIf { it.shares(item) }?.let { c -> { c.hold(item) } },
                    )
                }
            }
            else -> LazyRow(state = cardState, contentPadding = PaddingValues(horizontal = ScreenPadding), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                itemsIndexed(row.items, key = { i, it -> "$i:${it.id}" }) { _, item ->
                    val key = "${row.key}-${item.id}"
                    MediaCard(
                        item = item,
                        imageUrl = actions.server.cardImage(item, CardShape.POSTER),
                        shape = CardShape.POSTER,
                        width = 128.dp,
                        title = item.name.takeIf { display.showTitles },
                        subtitle = line(item) ?: item.productionYear?.toString().takeIf { display.showTitles },
                        sharedKey = key,
                        onClick = { actions.open(item, key) },
                        onLongClick = cards?.takeIf { it.shares(item) }?.let { c -> { c.hold(item) } },
                    )
                }
            }
        }
    }
}

/** The row's look, changed in place: its style, cards shown or hidden (Artwork), and titles. */
@Composable
private fun RowStylePicker(row: MediaRow, display: RowDisplay, onDisplay: (RowDisplay) -> Unit, actions: AppActions) {
    // On a page of rows: Customize (this row and this page) in a sheet; elsewhere the quick menu.
    val panel = LocalPagePanel.current
    if (panel != null) {
        var customizing by remember { mutableStateOf(false) }
        Text(
            display.styleLabel,
            style = MaterialTheme.typography.labelLarge,
            color = accentColor(),
            modifier = Modifier.clip(RoundedCornerShape(14.dp)).pressable({ customizing = true }).padding(horizontal = 10.dp, vertical = 6.dp),
        )
        if (customizing) CustomizeSheet(row, panel, actions, onDismiss = { customizing = false })
        return
    }
    var open by remember { mutableStateOf(false) }
    var colouring by remember { mutableStateOf(false) }
    if (colouring) InkColourDialog(display, onSave = { onDisplay(it); colouring = false }, onDismiss = { colouring = false })
    Box {
        Text(
            display.styleLabel,
            style = MaterialTheme.typography.labelLarge,
            color = accentColor(),
            modifier = Modifier.clip(RoundedCornerShape(14.dp)).pressable({ open = true }).padding(horizontal = 10.dp, vertical = 6.dp),
        )
        DropdownMenu(open, onDismissRequest = { open = false }) {
            RowStyle.choices.forEach { style ->
                DropdownMenuItem(
                    text = { Text(style.label) },
                    trailingIcon = { if (style == display.style) Icon(Icons.Default.Check, null) },
                    onClick = { onDisplay(display.copy(style = style, cards = true)); open = false },
                )
            }
            if (display.style == RowStyle.WIDE) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Show cards") },
                    trailingIcon = { if (display.cards) Icon(Icons.Default.Check, null) },
                    onClick = { onDisplay(display.copy(cards = !display.cards)); open = false },
                )
            }
            if (display.style == RowStyle.CRESSIDA) {
                HorizontalDivider()
                Text("Ink", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                dev.nitrate.core.rows.CressidaInk.entries.forEach { ink ->
                    DropdownMenuItem(
                        text = { Text(ink.label) },
                        trailingIcon = { if (ink == display.ink) Icon(Icons.Default.Check, null) },
                        onClick = { onDisplay(display.copy(ink = ink)); open = false },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Ink colour: ${display.inkColourLabel()}") },
                    onClick = { colouring = true; open = false },
                )
            }
            if (display.style == RowStyle.POSTER || (display.style == RowStyle.WIDE && display.cards)) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Titles under pictures") },
                    trailingIcon = { if (display.showTitles) Icon(Icons.Default.Check, null) },
                    onClick = { onDisplay(display.copy(showTitles = !display.showTitles)); open = false },
                )
            }
        }
    }
}

/**
 * Artwork with its cards hidden: the row fills the screen, one title at a time with its artwork as the whole
 * background (the poster upright, the backdrop sideways); swipe sideways through the titles and
 * scroll on to the next row. The row's name and style choice sit over the artwork.
 */
@Composable
private fun FullShowcaseRow(row: MediaRow, display: RowDisplay, actions: AppActions, onDisplay: ((RowDisplay) -> Unit)?) {
    if (row.items.isEmpty()) return
    val server = actions.server
    val window = dev.nitrate.mobile.platform.windowSizeDp()
    val portrait = window.height >= window.width
    val pager = androidx.compose.foundation.pager.rememberPagerState { row.items.size }
    // The next titles' backgrounds (and logos) load before they're swiped in.
    val context = coil3.compose.LocalPlatformContext.current
    LaunchedEffect(pager.currentPage, row.items, portrait) {
        ImagePrefetch.enqueue(context, row.items.drop(pager.currentPage + 1).take(2).flatMap { listOf(server.fullShowcaseImage(it, portrait), server.logoUrl(it)) })
    }
    Box(Modifier.fillMaxWidth().height(window.height)) {
        androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.fillMaxSize(), key = { "$it:${row.items[it].id}" }) { page ->
            FullShowcasePage(row.items[page], "${row.key}-${row.items[page].id}", display, actions, portrait)
        }
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.statusBars).padding(horizontal = ScreenPadding, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(row.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
            if (onDisplay != null) RowStylePicker(row, display, onDisplay, actions)
        }
        Text(
            "${pager.currentPage + 1} / ${row.items.size}",
            style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.75f),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = ScreenPadding, bottom = 104.dp),
        )
    }
}

@Composable
private fun FullShowcasePage(item: MediaItem, sharedKey: String, display: RowDisplay, actions: AppActions, portrait: Boolean) {
    val server = actions.server
    val prefs = LocalDisplayPrefs.current
    val options = display.showcase
    Box(Modifier.fillMaxSize().background(Color.Black).cardPress(actions, item) { actions.open(item, sharedKey) }) {
        AsyncImage(
            server.fullShowcaseImage(item, portrait),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().sharedArtwork(sharedKey).spoilerBlur(item),
        )
        // Dark at the top for the row's name, and deep at the bottom for the title and buttons.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x99000000), 0.18f to Color.Transparent, 0.45f to Color.Transparent, 1f to Color(0xF2000000))))
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = ScreenPadding, end = ScreenPadding, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val logo = server.logoUrl(item)?.takeIf { prefs.logos && options.showLogo }
            if (logo != null) {
                AsyncImage(logo, item.displayTitle, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart, modifier = Modifier.widthIn(max = 280.dp).heightIn(max = 96.dp))
            } else {
                Text(item.displayTitle, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            item.episodeLabel?.let { Text(it, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.titleSmall) }
            if (options.showDetails) {
                val facts = prefs.facts(item).let { f -> if (options.showRatings) f else f.filterNot { it.startsWith("★") } }
                if (facts.isNotEmpty()) Text(facts.take(5).joinToString("  ·  "), color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (options.showOverview && prefs.overview && !descriptionHidden(item)) {
                item.overview?.let { Text(it, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                PillButton(if (item.kind == ItemKind.SERIES) "Watch" else if (item.user.positionMs > 0) "Resume" else "Play", Icons.Default.PlayArrow, primary = true, onClick = { actions.playOrOpen(item) })
                PillButton("Details", null, primary = false, onClick = { actions.open(item, sharedKey) })
            }
        }
    }
}

/** "18m left" for something part-watched. */
fun timeLeft(item: MediaItem): String? {
    val runtime = item.runtimeMs ?: return null
    val pct = item.user.playedPercentage ?: return null
    if (pct <= 0 || item.user.played) return null
    val left = (runtime * (1 - pct / 100)).toLong()
    return if (left > 60_000) "${formatRuntime(left)} left" else null
}
