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
    val vm = viewModel(key = "home") { RowsViewModel(PageKeys.HOME, container.server::homeRows, container.displaySettings, container.server, container.serverChanges, ArtworkFirst::display, cache = container.rowCache) }
    val state by vm.state.collectAsStateWithLifecycle()
    val overrides by vm.overrides.collectAsStateWithLifecycle()
    var refreshing by remember { mutableStateOf(false) }
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

    when (val s = state) {
        RowsState.Loading -> Loading(modifier)
        is RowsState.Error -> ErrorMessage(s.message, vm::refresh, modifier)
        is RowsState.Loaded -> {
            // The page takes its colour from the first piece of artwork on it.
            val lead = s.rows.firstOrNull { it.items.isNotEmpty() && !it.key.endsWith("my_media") }?.items?.firstOrNull()
            val tint by rememberArtworkColor(lead?.let { actions.server.backdropUrl(it, 320) ?: actions.server.imageUrl(it) })
            val list = rememberLazyListState()

            TintedBackdrop(tint, modifier) {
                PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refreshing = true; vm.refresh(); refreshing = false }) {
                    LazyColumn(state = list, contentPadding = PaddingValues(bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(28.dp), modifier = Modifier.fillMaxSize()) {
                        // Room for the wordmark bar.
                        item(key = "top") { Spacer(Modifier.windowInsetsPadding(WindowInsets.statusBars).height(56.dp)) }
                        item(key = "pages") { PinnedPages(actions) }
                        items(s.rows, key = { it.key }) { row -> CinematicRow(row, overrides[row.key] ?: row.defaultDisplay, actions, onDisplay = { vm.save(row, it) }) }
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
 * A row as posters, artwork (wide pictures) or a showcase (big swipeable artwork cards), per the
 * row's [display]. With [onDisplay], the header offers the choice.
 */
@Composable
fun CinematicRow(row: MediaRow, display: RowDisplay, actions: AppActions, onDisplay: ((RowDisplay) -> Unit)? = null) {
    Column {
        SectionHeader(row.title) { if (onDisplay != null) RowStylePicker(display, onDisplay) }
        when (display.style) {
            RowStyle.SHOWCASE -> ShowcaseRow(row, actions)
            RowStyle.FILM_STRIP -> FilmStripRow(row, actions)
            RowStyle.LIGHTNING -> LightningRow(row, actions)
            RowStyle.CRESSIDA -> CressidaRow(row, actions, display)
            RowStyle.WIDE -> LazyRow(contentPadding = PaddingValues(horizontal = ScreenPadding), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                itemsIndexed(row.items, key = { i, it -> "$i:${it.id}" }) { _, item ->
                    val key = "${row.key}-${item.id}"
                    MediaCard(
                        item = item,
                        imageUrl = actions.server.cardImage(item, CardShape.WIDE),
                        shape = CardShape.WIDE,
                        width = 272.dp,
                        title = item.displayTitle.takeIf { display.showTitles },
                        subtitle = listOfNotNull(item.episodeLabel ?: item.productionYear?.toString(), timeLeft(item)).joinToString(" · ").takeIf { display.showTitles },
                        sharedKey = key,
                        onClick = { actions.open(item, key) },
                    )
                }
            }
            RowStyle.POSTER -> LazyRow(contentPadding = PaddingValues(horizontal = ScreenPadding), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                itemsIndexed(row.items, key = { i, it -> "$i:${it.id}" }) { _, item ->
                    val key = "${row.key}-${item.id}"
                    MediaCard(
                        item = item,
                        imageUrl = actions.server.cardImage(item, CardShape.POSTER),
                        shape = CardShape.POSTER,
                        width = 128.dp,
                        title = item.name.takeIf { display.showTitles },
                        subtitle = item.productionYear?.toString().takeIf { display.showTitles },
                        sharedKey = key,
                        onClick = { actions.open(item, key) },
                    )
                }
            }
        }
    }
}

/** The row's look, changed in place: Posters, Artwork or Showcase, and titles. */
@Composable
private fun RowStylePicker(display: RowDisplay, onDisplay: (RowDisplay) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var colouring by remember { mutableStateOf(false) }
    if (colouring) InkColourDialog(display, onSave = { onDisplay(it); colouring = false }, onDismiss = { colouring = false })
    Box {
        Text(
            display.style.label,
            style = MaterialTheme.typography.labelLarge,
            color = accentColor(),
            modifier = Modifier.clip(RoundedCornerShape(14.dp)).pressable({ open = true }).padding(horizontal = 10.dp, vertical = 6.dp),
        )
        DropdownMenu(open, onDismissRequest = { open = false }) {
            RowStyle.entries.forEach { style ->
                DropdownMenuItem(
                    text = { Text(style.label) },
                    trailingIcon = { if (style == display.style) Icon(Icons.Default.Check, null) },
                    onClick = { onDisplay(display.copy(style = style)); open = false },
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
            if (display.style == RowStyle.POSTER || display.style == RowStyle.WIDE) {
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

/** Big artwork cards, one screen-width at a time: logo or title, facts and Play over the picture. */
@Composable
private fun ShowcaseRow(row: MediaRow, actions: AppActions) {
    val state = rememberLazyListState()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardWidth = (maxWidth - ScreenPadding * 2).coerceAtMost(560.dp)
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            flingBehavior = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(state),
        ) {
            itemsIndexed(row.items, key = { i, it -> "$i:${it.id}" }) { _, item ->
                val key = "${row.key}-${item.id}"
                ShowcaseCard(item, actions, key, Modifier.width(cardWidth))
            }
        }
    }
}

@Composable
private fun ShowcaseCard(item: MediaItem, actions: AppActions, sharedKey: String, modifier: Modifier) {
    val server = actions.server
    val prefs = LocalDisplayPrefs.current
    Box(modifier.aspectRatio(4f / 3f).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.06f)).pressable({ actions.open(item, sharedKey) })) {
        AsyncImage(
            server.backdropUrl(item, 1280) ?: server.imageUrl(item, ImageKind.THUMB, 720) ?: server.imageUrl(item, maxHeight = 720),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().sharedArtwork(sharedKey),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xE6000000))))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val logo = server.logoUrl(item)?.takeIf { prefs.logos }
            if (logo != null) {
                AsyncImage(logo, item.displayTitle, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart, modifier = Modifier.widthIn(max = 220.dp).heightIn(max = 72.dp))
            } else {
                Text(item.displayTitle, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            item.episodeLabel?.let { Text(it, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelLarge) }
            val facts = prefs.facts(item)
            if (facts.isNotEmpty()) Text(facts.take(4).joinToString("  ·  "), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 2.dp)) {
                PillButton(if (item.kind == ItemKind.SERIES) "Watch" else if (item.user.positionMs > 0) "Resume" else "Play", Icons.Default.PlayArrow, primary = true, onClick = { actions.playOrOpen(item) })
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
