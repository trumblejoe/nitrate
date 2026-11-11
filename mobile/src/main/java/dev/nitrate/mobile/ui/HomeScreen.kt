package dev.nitrate.mobile.ui

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

/** Picks what the hero shows: the newest movies and shows, then anything else, up to six. */
fun featuredItems(rows: List<MediaRow>): List<MediaItem> {
    val latest = rows.filter { it.key.startsWith("home/latest/") }.map { r -> r.items.filter { it.kind == ItemKind.MOVIE || it.kind == ItemKind.SERIES } }
    // Interleave libraries so one doesn't take every slot.
    val interleaved = (0 until (latest.maxOfOrNull { it.size } ?: 0)).flatMap { i -> latest.mapNotNull { it.getOrNull(i) } }
    val fallback = rows.flatMap { it.items }.filter { it.kind == ItemKind.MOVIE || it.kind == ItemKind.SERIES }
    return (interleaved + fallback).distinctBy { it.id }.take(6)
}

@Composable
fun HomeTab(actions: AppActions, modifier: Modifier = Modifier, onSearch: () -> Unit = {}) {
    val container = actions.container
    val vm = viewModel(key = "home") { RowsViewModel(container.server::homeRows, container.displaySettings, container.server.userId) }
    val state by vm.state.collectAsStateWithLifecycle()
    var refreshing by remember { mutableStateOf(false) }

    // Refresh "Continue Watching" when coming back from the player or details.
    var first by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        if (!first) vm.refresh()
        first = false
        onPauseOrDispose { }
    }

    when (val s = state) {
        RowsState.Loading -> Loading(modifier)
        is RowsState.Error -> ErrorMessage(s.message, vm::refresh, modifier)
        is RowsState.Loaded -> {
            val featured = remember(s.rows) { featuredItems(s.rows) }
            val pager = rememberPagerState { featured.size }
            val current = featured.getOrNull(pager.currentPage)
            val tint by rememberArtworkColor(current?.let { actions.server.backdropUrl(it) ?: actions.server.imageUrl(it) })
            val list = rememberLazyListState()

            TintedBackdrop(tint, modifier) {
                PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refreshing = true; vm.refresh(); refreshing = false }) {
                    LazyColumn(state = list, contentPadding = PaddingValues(bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(28.dp), modifier = Modifier.fillMaxSize()) {
                        if (featured.isNotEmpty()) {
                            item(key = "hero") { HeroCarousel(featured, pager, actions) }
                        }
                        items(s.rows.filterNot { it.key == "home/my_media" }, key = { it.key }) { row -> CinematicRow(row, actions) }
                    }
                }
                // Wordmark and search over the hero; a scrim fades in once the hero scrolls away.
                val scrolled = list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 200
                val scrim by androidx.compose.animation.core.animateFloatAsState(if (scrolled) 0.92f else 0f, label = "scrim")
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background.copy(alpha = scrim)).windowInsetsPadding(WindowInsets.statusBars)
                        .padding(start = ScreenPadding, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Wordmark(Modifier.weight(1f))
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

@Composable
private fun HeroCarousel(items: List<MediaItem>, pager: PagerState, actions: AppActions) {
    val scope = rememberCoroutineScope()
    // Moves on every 7 seconds, unless the user is swiping.
    LaunchedEffect(pager.settledPage, items.size) {
        if (items.size < 2) return@LaunchedEffect
        delay(7_000)
        if (!pager.isScrollInProgress) pager.animateScrollToPage((pager.settledPage + 1) % items.size)
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val height = (maxWidth * 1.32f).coerceAtMost(640.dp).coerceAtLeast(420.dp)
        Box(Modifier.fillMaxWidth().height(height)) {
            HorizontalPager(pager, Modifier.fillMaxSize(), key = { items[it].id }) { page ->
                val item = items[page]
                val offset = (pager.currentPage - page) + pager.currentPageOffsetFraction
                HeroPage(item, actions.server, offset, onPlay = { actions.playOrOpen(item) }, onOpen = { actions.open(item, "hero-${item.id}") })
            }
            // Page dots: the current one stretches into a bar.
            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items.indices.forEach { i ->
                    val w by animateDpAsState(if (i == pager.currentPage) 22.dp else 6.dp, label = "dot")
                    Box(
                        Modifier.width(w).height(6.dp).clip(CircleShape)
                            .background(if (i == pager.currentPage) accentColor() else Color.White.copy(alpha = 0.35f))
                            .pressable({ scope.launch { pager.animateScrollToPage(i) } }),
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroPage(item: MediaItem, server: MediaServer, offset: Float, onPlay: () -> Unit, onOpen: () -> Unit) {
    val bg = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize()) {
        // Parallax: the picture moves at half the page's speed.
        AsyncImage(
            server.backdropUrl(item) ?: server.imageUrl(item, maxHeight = 1080),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().sharedArtwork("hero-${item.id}").fadeBottom(0.6f).graphicsLayer {
                translationX = offset * size.width * 0.5f
                val s = 1.08f - 0.06f * (1f - offset.absoluteValue.coerceIn(0f, 1f))
                scaleX = s; scaleY = s
            },
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x99000000), 0.22f to Color.Transparent, 1f to Color.Transparent)))
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = ScreenPadding, end = ScreenPadding, bottom = 26.dp)
                .graphicsLayer { alpha = 1f - offset.absoluteValue.coerceIn(0f, 1f) * 1.4f; translationX = offset * 60f },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val logo = server.logoUrl(item)
            if (logo != null) {
                AsyncImage(logo, item.name, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart, modifier = Modifier.widthIn(max = 280.dp).heightIn(max = 110.dp))
            } else {
                Text(item.name, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                heroFacts(item).forEach { GlassChip(it) }
            }
            if (item.genres.isNotEmpty()) {
                Text(item.genres.take(3).joinToString("  •  "), color = accentColor(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
            item.overview?.let { Text(it, color = Color.White.copy(alpha = 0.82f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                PillButton(if (item.kind == ItemKind.SERIES) "Watch" else if (item.user.positionMs > 0) "Resume" else "Play", Icons.Default.PlayArrow, primary = true, onClick = onPlay)
                PillButton("Details", Icons.Default.Info, primary = false, onClick = onOpen)
            }
        }
    }
}

private fun heroFacts(item: MediaItem) = listOfNotNull(
    if (item.kind == ItemKind.SERIES) item.productionYear?.let { start -> if (item.endYear != null && item.endYear != start) "$start–${item.endYear}" else "$start" } else item.productionYear?.toString(),
    item.officialRating,
    item.runtimeMs?.takeIf { item.kind != ItemKind.SERIES }?.let(::formatRuntime),
    item.communityRating?.let { "★ ${dev.nitrate.core.model.formatOneDecimal(it.toDouble())}" },
)

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

/** A row in the cinematic style: wide cards for Continue Watching and Next Up, posters otherwise. */
@Composable
fun CinematicRow(row: MediaRow, actions: AppActions) {
    val wide = row.key.endsWith("continue_watching") || row.key.endsWith("next_up") || row.defaultDisplay.style == dev.nitrate.core.rows.RowStyle.WIDE
    Column {
        SectionHeader(row.title)
        androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = ScreenPadding), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(row.items, key = { it.id }) { item ->
                val key = "${row.key}-${item.id}"
                if (wide) {
                    MediaCard(
                        item = item,
                        imageUrl = actions.server.cardImage(item, CardShape.WIDE),
                        shape = CardShape.WIDE,
                        width = 272.dp,
                        title = item.displayTitle,
                        subtitle = listOfNotNull(item.episodeLabel ?: item.productionYear?.toString(), timeLeft(item)).joinToString(" · "),
                        sharedKey = key,
                        onClick = { actions.open(item, key) },
                    )
                } else {
                    MediaCard(
                        item = item,
                        imageUrl = actions.server.cardImage(item, CardShape.POSTER),
                        shape = CardShape.POSTER,
                        width = 128.dp,
                        sharedKey = key,
                        onClick = { actions.open(item, key) },
                    )
                }
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
