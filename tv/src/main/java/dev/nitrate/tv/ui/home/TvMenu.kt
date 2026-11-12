package dev.nitrate.tv.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.foundation.focusable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import dev.nitrate.core.pages.TvMenuStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.Icon
import androidx.tv.material3.NavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.Text
import androidx.tv.material3.rememberDrawerState
import dev.nitrate.core.model.CollectionKind
import dev.nitrate.core.model.MediaItem

/** One stop in the side menu. */
data class NavEntry(val label: String, val icon: ImageVector, val selected: Boolean = false, val onClick: () -> Unit)

/** Standard icons for the menu; kept here so screens don't each pick their own. */
object NavIcons {
    val search = Icons.Filled.Search
    val home = Icons.Filled.Home
    val pages = Icons.AutoMirrored.Filled.List
    val together = Icons.Filled.Face
    val settings = Icons.Filled.Settings
    val profile = Icons.Filled.AccountCircle

    fun library(item: MediaItem): ImageVector = when (item.collection) {
        CollectionKind.TV_SHOWS -> Icons.Filled.DateRange
        CollectionKind.MUSIC, CollectionKind.MUSIC_VIDEOS -> Icons.Filled.Favorite
        CollectionKind.LIVE_TV -> Icons.Filled.Notifications
        CollectionKind.BOX_SETS, CollectionKind.PLAYLISTS -> Icons.AutoMirrored.Filled.List
        CollectionKind.PHOTOS, CollectionKind.HOME_VIDEOS -> Icons.Filled.Star
        else -> Icons.Filled.PlayArrow
    }
}

/**
 * Pages that draw over the whole screen call this when the D-pad runs off their edge; it moves to
 * the menu if the menu is that way and says whether it did.
 */
val LocalMenuEdge = androidx.compose.runtime.staticCompositionLocalOf<(Key) -> Boolean> { { false } }

/** Which way the content is from a bar, so "Next row" knows its arrow and its key. */
private enum class Edge { TOP, BOTTOM }

/**
 * The TV's main menu in the user's chosen [style], around [content]. [top] entries come first,
 * [bottom] ones after a gap. [overlay] draws bars over the content (full-screen art) instead of
 * beside it; [content] gets the space the menu keeps clear at each edge.
 *
 * You reach the menu by moving to where it is (past the first item or row); the fade-in bar and
 * the dial wait at the top edge. The remote's Menu key jumps to it from anywhere and back again,
 * and Back from the menu returns to the page. Back on the page is never taken over.
 */
@Composable
fun TvMenu(
    style: TvMenuStyle,
    top: List<NavEntry>,
    bottom: List<NavEntry>,
    overlay: Boolean,
    content: @Composable (MenuInsets) -> Unit,
) {
    val contentFocus = remember { FocusRequester() }
    val menuFocus = remember { FocusRequester() }
    var inMenu by remember { mutableStateOf(false) }
    var dialOpen by remember { mutableStateOf(false) }
    val entries = top + bottom
    val toContent: () -> Unit = { dialOpen = false; runCatching { contentFocus.requestFocus() } }
    val toMenu: () -> Unit = { if (style == TvMenuStyle.RADIAL) dialOpen = true else runCatching { menuFocus.requestFocus() } }
    BackHandler(enabled = inMenu || dialOpen, onBack = toContent)

    val edgeKey = when (style) {
        TvMenuStyle.LEFT -> Key.DirectionLeft
        TvMenuStyle.RIGHT -> Key.DirectionRight
        TvMenuStyle.BOTTOM -> Key.DirectionDown
        else -> Key.DirectionUp
    }
    val reach: (Key) -> Boolean = { key -> if (key == edgeKey) { toMenu(); true } else false }

    @Composable
    fun page(insets: MenuInsets) = Box(Modifier.fillMaxSize().focusRequester(contentFocus).focusGroup()) {
        CompositionLocalProvider(LocalMenuEdge provides reach) { content(insets) }
    }
    val trackMenu = Modifier.onFocusChanged { inMenu = it.hasFocus }

    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            if (e.key != Key.Menu) return@onPreviewKeyEvent false
            if (e.type == KeyEventType.KeyUp) { if (inMenu || dialOpen) toContent() else toMenu() }
            true
        },
    ) {
        when (style) {
            TvMenuStyle.LEFT -> Rail(top, bottom, overlay, menuFocus, trackMenu) { page(if (overlay) MenuInsets(start = RailWidth) else MenuInsets()) }
            TvMenuStyle.RIGHT -> CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Rail(top, bottom, overlay, menuFocus, trackMenu) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { page(if (overlay) MenuInsets(end = RailWidth) else MenuInsets()) }
                }
            }
            TvMenuStyle.TOP, TvMenuStyle.BOTTOM -> {
                val edge = if (style == TvMenuStyle.TOP) Edge.TOP else Edge.BOTTOM
                val bar = @Composable { m: Modifier -> MenuBar(entries, edge, overlay, menuFocus, toContent, m.then(trackMenu)) }
                if (overlay) {
                    Box(Modifier.fillMaxSize()) {
                        page(if (edge == Edge.TOP) MenuInsets(top = BarHeight) else MenuInsets(bottom = BarHeight))
                        bar(Modifier.align(if (edge == Edge.TOP) Alignment.TopStart else Alignment.BottomStart))
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        if (edge == Edge.TOP) bar(Modifier)
                        Box(Modifier.weight(1f)) { page(MenuInsets()) }
                        if (edge == Edge.BOTTOM) bar(Modifier)
                    }
                }
            }
            TvMenuStyle.FADE -> {
                // The bar is there but invisible until focus reaches it.
                val alpha by animateFloatAsState(if (inMenu) 1f else 0f, label = "fade-menu")
                Box(Modifier.fillMaxSize()) {
                    page(MenuInsets(top = BarHeight))
                    MenuBar(entries, Edge.TOP, overlay = true, menuFocus, toContent, Modifier.align(Alignment.TopStart).alpha(alpha).then(trackMenu))
                }
            }
            TvMenuStyle.RADIAL -> Box(Modifier.fillMaxSize()) {
                page(MenuInsets())
                // Moving up past the top lands here and opens the dial.
                Box(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().height(1.dp)
                        .onFocusChanged { if (it.isFocused) dialOpen = true }
                        .focusable(enabled = !dialOpen),
                )
                if (dialOpen) Dial(entries, onClose = toContent)
            }
        }
    }
}

private val BarHeight = 76.dp
private val RailWidth = 40.dp

/**
 * Icons down the side that open with labels when focused. Over full-screen art the icons float on
 * the picture and the opened menu darkens it; otherwise the rail has its own column.
 */
@Composable
private fun Rail(
    top: List<NavEntry>,
    bottom: List<NavEntry>,
    overlay: Boolean,
    menuFocus: FocusRequester,
    trackMenu: Modifier,
    content: @Composable () -> Unit,
) {
    val state = rememberDrawerState(DrawerValue.Closed)
    val target = (top + bottom).let { all -> all.firstOrNull { it.selected } ?: all.firstOrNull() }
    val drawer: @Composable androidx.tv.material3.NavigationDrawerScope.(DrawerValue) -> Unit = {
        Column(
            Modifier.fillMaxHeight().then(trackMenu).padding(vertical = 24.dp, horizontal = 8.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            (top + listOf(null) + bottom).forEach { entry ->
                if (entry == null) Spacer(Modifier.height(20.dp))
                else NavigationDrawerItem(
                    selected = entry.selected,
                    onClick = entry.onClick,
                    leadingContent = { Icon(entry.icon, contentDescription = null) },
                    modifier = if (entry == target) Modifier.focusRequester(menuFocus) else Modifier,
                ) { Text(entry.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
    if (overlay) {
        androidx.tv.material3.ModalNavigationDrawer(
            drawerContent = drawer,
            drawerState = state,
            // Dark on the menu's side, fading across the picture.
            scrimBrush = Brush.horizontalGradient(
                listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent).let { if (LocalLayoutDirection.current == LayoutDirection.Rtl) it.reversed() else it },
            ),
            content = content,
        )
    } else {
        NavigationDrawer(drawerContent = drawer, drawerState = state, content = content)
    }
}

/**
 * A row of icon-and-word stops, the current page underlined, and "Next row" at the start. The
 * D-pad doesn't drop off the bar onto the page (stops may grow sub-menus there): "Next row" is the
 * way back, by pressing towards the page on it or by selecting it.
 */
@Composable
private fun MenuBar(entries: List<NavEntry>, edge: Edge, overlay: Boolean, menuFocus: FocusRequester, toContent: () -> Unit, modifier: Modifier) {
    val shade = Color.Black.copy(alpha = 0.6f)
    val background = when {
        !overlay -> Modifier
        edge == Edge.TOP -> Modifier.background(Brush.verticalGradient(listOf(shade, Color.Transparent)))
        else -> Modifier.background(Brush.verticalGradient(listOf(Color.Transparent, shade)))
    }
    val towardsPage = if (edge == Edge.TOP) Key.DirectionDown else Key.DirectionUp
    val nextRow = NavEntry("Next row", if (edge == Edge.TOP) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp, onClick = toContent)
    val target = entries.firstOrNull { it.selected } ?: entries.firstOrNull()
    LazyRow(
        modifier.fillMaxWidth().height(BarHeight).then(background),
        contentPadding = PaddingValues(horizontal = 50.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(listOf(nextRow) + entries) { i, entry ->
            val isNextRow = i == 0
            BarItem(
                entry,
                emphasis = isNextRow,
                modifier = Modifier
                    .then(if (entry == target) Modifier.focusRequester(menuFocus) else Modifier)
                    .onPreviewKeyEvent { e ->
                        if (e.key != towardsPage) return@onPreviewKeyEvent false
                        if (isNextRow && e.type == KeyEventType.KeyDown) toContent()
                        true
                    },
            )
        }
    }
}

@Composable
private fun BarItem(entry: NavEntry, emphasis: Boolean = false, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        onClick = entry.onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (emphasis) Color.White.copy(alpha = 0.12f) else Color.Transparent,
            contentColor = Color.White.copy(alpha = 0.85f),
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(entry.icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(entry.label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            }
            Box(Modifier.padding(top = 4.dp).width(24.dp).height(2.dp).background(if (entry.selected) accent else Color.Transparent, RoundedCornerShape(1.dp)))
        }
    }
}

/** Up, Right, Down, Left: the dial's spokes, in the order entries fill each ring. */
private val Spokes = listOf(Key.DirectionUp, Key.DirectionRight, Key.DirectionDown, Key.DirectionLeft)

/**
 * Rings of up to four stops each, only straight up, right, down and left of the centre, so every
 * move is a single press. Pressing outwards along a spoke steps to the next ring, inwards steps
 * back (to the centre from the first ring), and sideways swings to that side on the same ring.
 */
@Composable
private fun Dial(entries: List<NavEntry>, onClose: () -> Unit) {
    val rings = entries.chunked(4)
    // (spoke, ring), or null at the centre.
    var at by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val focus = remember { FocusRequester() }
    fun entry(spoke: Int, ring: Int) = rings.getOrNull(ring)?.getOrNull(spoke)
    fun move(dir: Int) {
        val pos = at
        at = when {
            pos == null -> if (entry(dir, 0) != null) dir to 0 else null
            dir == pos.first -> if (entry(dir, pos.second + 1) != null) dir to pos.second + 1 else pos
            dir == (pos.first + 2) % 4 -> if (pos.second == 0) null else pos.first to pos.second - 1
            else -> (pos.second downTo 0).firstOrNull { entry(dir, it) != null }?.let { dir to it } ?: pos
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val selected = at?.let { (s, r) -> entry(s, r) }

    // Rings sit close together so many fit; they tighten further as more are added.
    val first = 92.dp
    val gap = if (rings.size <= 1) 64.dp else minOf(64.dp, (230.dp - first) / (rings.size - 1))
    val item = minOf(54.dp, gap - 8.dp).coerceAtLeast(36.dp)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
            .focusRequester(focus)
            .onPreviewKeyEvent { e ->
                val dir = Spokes.indexOf(e.key)
                when {
                    dir >= 0 -> { if (e.type == KeyEventType.KeyDown) move(dir); true }
                    e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter -> {
                        if (e.type == KeyEventType.KeyUp) selected?.let { onClose(); it.onClick() }
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
        Alignment.Center,
    ) {
        rings.indices.forEach { r ->
            val radius = first + gap * r
            Box(Modifier.size(radius * 2).border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape))
        }
        Text(
            selected?.label ?: "▲ ▶ ▼ ◀",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 2,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.width(first + 20.dp),
        )
        rings.forEachIndexed { r, ring ->
            val radius = first + gap * r
            ring.forEachIndexed { s, e ->
                val (dx, dy) = when (s) { 0 -> 0f to -1f; 1 -> 1f to 0f; 2 -> 0f to 1f; else -> -1f to 0f }
                val on = at == (s to r)
                Box(
                    Modifier.offset(x = radius * dx, y = radius * dy).size(item)
                        .graphicsLayer { scaleX = if (on) 1.2f else 1f; scaleY = if (on) 1.2f else 1f }
                        .background(if (on) MaterialTheme.colorScheme.primary else Color(0xFF26262B), CircleShape),
                    Alignment.Center,
                ) {
                    Icon(e.icon, contentDescription = e.label, tint = if (on) Color.Black else Color.White, modifier = Modifier.size(item * 0.5f))
                }
            }
        }
    }
}
