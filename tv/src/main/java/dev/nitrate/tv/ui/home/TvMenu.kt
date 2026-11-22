package dev.nitrate.tv.ui.home

import androidx.compose.runtime.collectAsState
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
import androidx.compose.material.icons.filled.ThumbUp
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
import dev.nitrate.core.pages.MenuStops
import dev.nitrate.core.pages.CustomPage
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.onKeyEvent
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
data class NavEntry(
    val label: String,
    val icon: ImageVector,
    val selected: Boolean = false,
    val id: String = label,
    /** A long press (held OK): a quicker version of what [onClick] opens, when there is one. */
    val onLongClick: (() -> Unit)? = null,
    val onClick: () -> Unit,
)

/** Standard icons for the menu; kept here so screens don't each pick their own. */
object NavIcons {
    val search = Icons.Filled.Search
    val home = Icons.Filled.Home
    val pages = Icons.AutoMirrored.Filled.List
    val together = Icons.Filled.Face
    val settings = Icons.Filled.Settings
    val profile = Icons.Filled.AccountCircle
    val channels = Icons.Filled.DateRange
    val comics = Icons.Filled.Star
    val franchises = Icons.Filled.ThumbUp
    val music = Icons.Filled.PlayArrow

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

/** Whether the menu is that way from the page's edge (without moving there), e.g. so a row wraps round only when it isn't. */
val LocalMenuSide = androidx.compose.runtime.staticCompositionLocalOf<(Key) -> Boolean> { { false } }

/**
 * Pages that step through rows themselves (the full-screen Home) report whether they're on the
 * first and last row, so the Top/Bottom bar can sit where you can reach it.
 */
val LocalMenuRows = androidx.compose.runtime.staticCompositionLocalOf<(first: Boolean, last: Boolean) -> Unit> { { _, _ -> } }

private enum class Edge { TOP, BOTTOM }

/**
 * The TV's main menu in the user's chosen [style], around [content]. [stops] are the menu in the
 * user's order; [dial] the dial's spots (`ring * 4 + spoke`, null = empty). [overlay] draws the
 * menu over the content (full-screen art) instead of beside it; [content] gets the space the menu
 * keeps clear at each edge.
 *
 * You reach the menu by moving to where it is (past the first item or row); the fade-in bar and
 * the dial wait at the top edge, and the Top/Bottom bar is at the top on the first row and at the
 * bottom on the last. The remote's Menu key jumps to it from anywhere and back again, and Back
 * from the menu returns to the page. Back on the page is never taken over.
 */
@Composable
fun TvMenu(
    style: TvMenuStyle,
    stops: List<NavEntry>,
    dial: List<NavEntry?>,
    overlay: Boolean,
    /** Keep the menu invisible until focus reaches it, fading in over the page (as on Home). */
    autoHide: Boolean = false,
    content: @Composable (MenuInsets) -> Unit,
) {
    // A hidden menu takes no room of its own: it fades in over the page on every page, not only
    // over Home's full-screen art (a column of its own left a blank strip while hidden).
    @Suppress("NAME_SHADOWING") val overlay = overlay || autoHide
    val contentFocus = remember { FocusRequester() }
    val menuFocus = remember { FocusRequester() }
    var inMenu by remember { mutableStateOf(false) }
    var dialOpen by remember { mutableStateOf(false) }
    // Top/Bottom: which rows the page is on, and where the bar is (kept while you're in it).
    var onFirst by remember { mutableStateOf(true) }
    var onLast by remember { mutableStateOf(true) }
    var forcedTop by remember { mutableStateOf(false) }
    var focusBar by remember { mutableStateOf(0) }
    // Back to the page where it was left (the card in the row, not the first thing on the page) when
    // the page can say where that was (dev.nitrate.tv.ui.rows.FocusReturn), else to its start.
    val focusReturn = remember { dev.nitrate.tv.ui.rows.FocusReturn() }
    val toContent: () -> Unit = {
        dialOpen = false; forcedTop = false
        if (focusReturn.restore?.invoke() != true) runCatching { contentFocus.requestFocus() }
    }
    val toMenu: () -> Unit = {
        when (style) {
            TvMenuStyle.RADIAL -> dialOpen = true
            // On the first or last row the bar is already there; elsewhere Menu brings it to the top first.
            TvMenuStyle.TOP, TvMenuStyle.FADE -> if (onFirst || onLast) runCatching { menuFocus.requestFocus() } else { forcedTop = true; focusBar++ }
            else -> runCatching { menuFocus.requestFocus() }
        }
    }
    // The bar may only just have been placed: focus it once it's there.
    LaunchedEffect(focusBar) { if (focusBar > 0) runCatching { kotlinx.coroutines.delay(16); menuFocus.requestFocus() } }
    BackHandler(enabled = inMenu || dialOpen, onBack = toContent)

    val side: (Key) -> Boolean = { key ->
        when (style) {
            TvMenuStyle.LEFT -> key == Key.DirectionLeft
            TvMenuStyle.RIGHT -> key == Key.DirectionRight
            // With one row the bar is only at the top.
            TvMenuStyle.TOP, TvMenuStyle.FADE -> (key == Key.DirectionUp && onFirst) || (key == Key.DirectionDown && onLast && !onFirst)
            else -> key == Key.DirectionUp
        }
    }
    val reach: (Key) -> Boolean = { key -> side(key).also { if (it) toMenu() } }
    val rows: (Boolean, Boolean) -> Unit = { first, last -> onFirst = first; onLast = last }

    // Leaving the page towards the menu always lands on the menu (its current stop, or the dial),
    // whatever the page is (rows, a grid, a list), instead of whichever piece of it happens to be
    // nearest. Moves within the page are left alone; a page that handles its own edges (full art)
    // never lets focus leave this way.
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    @Composable
    fun page(insets: MenuInsets) = Box(
        Modifier.fillMaxSize()
            .focusProperties {
                @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
                @Suppress("DEPRECATION")
                exit = { direction ->
                    val key = when (direction) {
                        androidx.compose.ui.focus.FocusDirection.Left -> Key.DirectionLeft
                        androidx.compose.ui.focus.FocusDirection.Right -> Key.DirectionRight
                        androidx.compose.ui.focus.FocusDirection.Up -> Key.DirectionUp
                        androidx.compose.ui.focus.FocusDirection.Down -> Key.DirectionDown
                        else -> null
                    }
                    if (key != null && side(key)) {
                        mainHandler.post { toMenu() }
                        FocusRequester.Cancel
                    } else FocusRequester.Default
                }
            }
            .focusRequester(contentFocus).focusGroup(),
    ) {
        CompositionLocalProvider(LocalMenuEdge provides reach, LocalMenuSide provides side, LocalMenuRows provides rows, dev.nitrate.tv.ui.rows.LocalFocusReturn provides focusReturn) { content(insets) }
    }
    val shown by animateFloatAsState(if (!autoHide || inMenu) 1f else 0f, label = "menu-fade")
    val trackMenu = Modifier.alpha(shown).onFocusChanged { inMenu = it.hasFocus; if (!it.hasFocus) forcedTop = false }

    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            if (e.key != Key.Menu) return@onPreviewKeyEvent false
            if (e.type == KeyEventType.KeyUp) { if (inMenu || dialOpen) toContent() else toMenu() }
            true
        },
    ) {
        when (style) {
            TvMenuStyle.LEFT -> Rail(stops, overlay, menuFocus, trackMenu, Key.DirectionRight, toContent) { page(if (overlay && !autoHide) MenuInsets(start = RailWidth) else MenuInsets()) }
            TvMenuStyle.RIGHT -> CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Rail(stops, overlay, menuFocus, trackMenu, Key.DirectionLeft, toContent) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { page(if (overlay && !autoHide) MenuInsets(end = RailWidth) else MenuInsets()) }
                }
            }
            TvMenuStyle.TOP, TvMenuStyle.FADE -> if (overlay) {
                // At the top on the first row (and when there's only one), at the bottom on the last.
                var edge by remember { mutableStateOf<Edge?>(Edge.TOP) }
                if (!inMenu) edge = when {
                    forcedTop || onFirst -> Edge.TOP
                    onLast -> Edge.BOTTOM
                    else -> null
                }
                Box(Modifier.fillMaxSize()) {
                    page(when {
                        autoHide || edge == null -> MenuInsets()
                        edge == Edge.TOP -> MenuInsets(top = BarHeight)
                        else -> MenuInsets(bottom = BarHeight)
                    })
                    edge?.let { e ->
                        MenuBar(stops, e, overlay = true, menuFocus, toContent, Modifier.align(if (e == Edge.TOP) Alignment.TopStart else Alignment.BottomStart).then(trackMenu))
                    }
                }
            } else {
                // The classic rows scroll on their own, so the bar stays at the top.
                Column(Modifier.fillMaxSize()) {
                    MenuBar(stops, Edge.TOP, overlay = false, menuFocus, toContent, trackMenu)
                    Box(Modifier.weight(1f)) { page(MenuInsets()) }
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
                if (dialOpen) Dial(dial, onClose = toContent)
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
    stops: List<NavEntry>,
    overlay: Boolean,
    menuFocus: FocusRequester,
    trackMenu: Modifier,
    /** Towards the page: leaves the rail (the open drawer covers the page, so focus can't find it). */
    exitKey: Key,
    toContent: () -> Unit,
    content: @Composable () -> Unit,
) {
    val state = rememberDrawerState(DrawerValue.Closed)
    val target = stops.firstOrNull { it.selected } ?: stops.firstOrNull()
    val drawer: @Composable androidx.tv.material3.NavigationDrawerScope.(DrawerValue) -> Unit = { drawerValue ->
        Column(
            Modifier.fillMaxHeight()
                .then(if (dev.nitrate.tv.ui.components.LocalDisplayPrefs.current.tvMenuBlack) Modifier.background(Color.Black) else Modifier)
                .then(trackMenu)
                .onPreviewKeyEvent { e ->
                    if (e.key != exitKey) return@onPreviewKeyEvent false
                    if (e.type == KeyEventType.KeyDown) toContent()
                    true
                }
                .padding(vertical = 24.dp, horizontal = 8.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            stops.forEach { entry ->
                NavigationDrawerItem(
                    selected = entry.selected,
                    onClick = entry.onClick,
                    onLongClick = entry.onLongClick,
                    // Closed, the rail shows icons alone: each says its stop's name.
                    leadingContent = { Icon(entry.icon, contentDescription = if (drawerValue == DrawerValue.Closed) entry.label else null) },
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
 * A row of icon-and-word stops with the current page underlined. Pressing towards the page from
 * any stop goes back to it.
 */
@Composable
private fun MenuBar(entries: List<NavEntry>, edge: Edge, overlay: Boolean, menuFocus: FocusRequester, toContent: () -> Unit, modifier: Modifier) {
    val shade = Color.Black.copy(alpha = 0.6f)
    val background = when {
        dev.nitrate.tv.ui.components.LocalDisplayPrefs.current.tvMenuBlack -> Modifier.background(Color.Black)
        !overlay -> Modifier
        edge == Edge.TOP -> Modifier.background(Brush.verticalGradient(listOf(shade, Color.Transparent)))
        else -> Modifier.background(Brush.verticalGradient(listOf(Color.Transparent, shade)))
    }
    val towardsPage = if (edge == Edge.TOP) Key.DirectionDown else Key.DirectionUp
    val target = entries.firstOrNull { it.selected } ?: entries.firstOrNull()
    LazyRow(
        modifier.fillMaxWidth().height(BarHeight).then(background).onPreviewKeyEvent { e ->
            if (e.key != towardsPage) return@onPreviewKeyEvent false
            if (e.type == KeyEventType.KeyDown) toContent()
            true
        },
        contentPadding = PaddingValues(horizontal = 50.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(entries, key = { _, e -> e.id }) { _, entry ->
            BarItem(entry, modifier = if (entry == target) Modifier.focusRequester(menuFocus) else Modifier)
        }
    }
}

@Composable
private fun BarItem(entry: NavEntry, emphasis: Boolean = false, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        onClick = entry.onClick,
        onLongClick = entry.onLongClick,
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
private fun Dial(slots: List<NavEntry?>, onClose: () -> Unit) {
    val rings = slots.chunked(4)
    // (spoke, ring), or null at the centre.
    var at by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var held by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    fun entry(spoke: Int, ring: Int) = rings.getOrNull(ring)?.getOrNull(spoke)
    fun move(dir: Int) {
        val pos = at
        at = when {
            pos == null -> rings.indices.firstOrNull { entry(dir, it) != null }?.let { dir to it }
            // Outwards: the next filled spot along this spoke (empty spots are skipped).
            dir == pos.first -> (pos.second + 1 until rings.size).firstOrNull { entry(dir, it) != null }?.let { dir to it } ?: pos
            // Inwards: the previous filled spot, or the centre.
            dir == (pos.first + 2) % 4 -> (pos.second - 1 downTo 0).firstOrNull { entry(pos.first, it) != null }?.let { pos.first to it }
            // Sideways: that spoke on this ring, else the nearest ring in, else the nearest out.
            else -> ((pos.second downTo 0) + (pos.second + 1 until rings.size)).firstOrNull { entry(dir, it) != null }?.let { dir to it } ?: pos
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val selected = at?.let { (s, r) -> entry(s, r) }

    DialFace(
        rings = rings.size,
        stop = ::entry,
        at = at,
        centre = selected?.label ?: "▲ ▶ ▼ ◀",
        showEmpty = false,
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (dev.nitrate.tv.ui.components.LocalDisplayPrefs.current.tvMenuBlack) 0.94f else 0.6f))
            .focusRequester(focus)
            .onPreviewKeyEvent { e ->
                val dir = Spokes.indexOf(e.key)
                when {
                    dir >= 0 -> { if (e.type == KeyEventType.KeyDown) move(dir); true }
                    e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter -> {
                        val long = selected?.onLongClick
                        when {
                            // Held: the stop's quick version (once), and the release doesn't click it too.
                            e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 1 && long != null -> { held = true; onClose(); long() }
                            e.type == KeyEventType.KeyUp && !held -> selected?.let { onClose(); it.onClick() }
                            e.type == KeyEventType.KeyUp -> held = false
                        }
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
    )
}

/** Where spoke [s] points: up, right, down, left. */
private fun spokeOffset(s: Int) = when (s) { 0 -> 0f to -1f; 1 -> 1f to 0f; 2 -> 0f to 1f; else -> -1f to 0f }

/**
 * The dial's rings and stops, [at] (spoke, ring) highlighted and [centre] in the middle. Rings sit
 * close together so many fit, and tighten further as more are added. [showEmpty] marks empty spots
 * (for arranging them).
 */
@Composable
internal fun DialFace(
    rings: Int,
    stop: (spoke: Int, ring: Int) -> NavEntry?,
    at: Pair<Int, Int>?,
    centre: String,
    showEmpty: Boolean,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier, Alignment.Center) {
    // Fit the rings to the space: the first ring leaves room for the name in the middle, the rest
    // share what's left, up to 64dp apart.
    val avail = minOf(maxWidth, maxHeight) / 2 - 12.dp
    val first = maxOf(avail * 0.38f, 70.dp)
    val gap = if (rings <= 1) 64.dp else minOf(64.dp, (avail - 27.dp - first) / (rings - 1))
    val item = minOf(54.dp, gap - 8.dp).coerceAtLeast(30.dp)
        repeat(rings) { r ->
            val radius = first + gap * r
            Box(Modifier.size(radius * 2).border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape))
        }
        Text(
            centre,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 2,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.width(first + 20.dp),
        )
        repeat(rings) { r ->
            val radius = first + gap * r
            repeat(4) { s ->
                val e = stop(s, r)
                if (e == null && !showEmpty) return@repeat
                val (dx, dy) = spokeOffset(s)
                val on = at == (s to r)
                Box(
                    Modifier.offset(x = radius * dx, y = radius * dy).size(item)
                        .graphicsLayer { scaleX = if (on) 1.2f else 1f; scaleY = if (on) 1.2f else 1f }
                        .background(
                            when {
                                on -> MaterialTheme.colorScheme.primary
                                e == null -> Color.White.copy(alpha = 0.05f)
                                else -> Color(0xFF26262B)
                            },
                            CircleShape,
                        )
                        .then(if (e == null) Modifier.border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape) else Modifier),
                    Alignment.Center,
                ) {
                    if (e != null) Icon(e.icon, contentDescription = e.label, tint = if (on) Color.Black else Color.White, modifier = Modifier.size(item * 0.5f))
                    else Text("+", color = if (on) Color.Black else Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/** What each kind of stop does. */
class MenuActions(
    val onSearch: () -> Unit = {},
    val onHome: () -> Unit = {},
    val onLibrary: (MediaItem) -> Unit = {},
    val onPage: (String) -> Unit = {},
    val onPages: () -> Unit = {},
    val onTogether: () -> Unit = {},
    /** Set when the household's channels are available (Companion plugin and the Channels plugin on). */
    val onChannels: (() -> Unit)? = null,
    val onComics: () -> Unit = {},
    val onFranchises: () -> Unit = {},
    val onSettings: () -> Unit = {},
    val onProfiles: () -> Unit = {},
    /** Long press on "Switch user": switch straight from the menu. */
    val onQuickSwitch: (() -> Unit)? = null,
    /** Back to the listening player; its stop shows only while music plays. */
    val onNowPlaying: () -> Unit = {},
)

/** Every stop the menu can have, by [MenuStops] id, and the order it starts in. */
class MenuCatalog(val entries: Map<String, NavEntry>, val defaults: List<String>, val nowPlaying: NavEntry? = null) {
    /** The same stops with [id] underlined as the page you're on. */
    fun current(id: String) = MenuCatalog(entries.mapValues { (k, e) -> e.copy(selected = k == id) }, defaults, nowPlaying)

    /** The rail's or bar's stops as the user arranged them. */
    fun stops(prefs: dev.nitrate.core.pages.DisplayPrefs): List<NavEntry> =
        MenuStops.linear(MenuStops.arrange(prefs.tvMenuStops, entries.keys, defaults)).filter { !prefs.easyMode || MenuStops.essential(it) }.mapNotNull(entries::get)

    /** The dial's spots as the user arranged them (null = empty); Easy mode keeps only the essential ones. */
    fun dial(prefs: dev.nitrate.core.pages.DisplayPrefs): List<NavEntry?> =
        MenuStops.arrange(prefs.tvDialStops, entries.keys, defaults).map { id -> id?.takeIf { !prefs.easyMode || MenuStops.essential(it) }?.let(entries::get) }
}

/** The menu's stops for these libraries and custom pages; [current] is underlined. */
fun menuCatalog(libraries: List<MediaItem>, pages: List<CustomPage>, actions: MenuActions, current: String = MenuStops.HOME): MenuCatalog {
    val list = buildList {
        add(NavEntry("Search", NavIcons.search, id = MenuStops.SEARCH, onClick = actions.onSearch))
        add(NavEntry("Home", NavIcons.home, id = MenuStops.HOME, onClick = actions.onHome))
        libraries.forEach { lib -> add(NavEntry(lib.name, NavIcons.library(lib), id = MenuStops.library(lib.id)) { actions.onLibrary(lib) }) }
        pages.forEach { p -> add(NavEntry(p.title, NavIcons.pages, id = MenuStops.page(p.id)) { actions.onPage(p.id) }) }
        add(NavEntry("Franchises", NavIcons.franchises, id = MenuStops.FRANCHISES, onClick = actions.onFranchises))
        actions.onChannels?.let { add(NavEntry("Channels", NavIcons.channels, id = MenuStops.CHANNELS, onClick = it)) }
        add(NavEntry("Your pages", NavIcons.pages, id = MenuStops.PAGES, onClick = actions.onPages))
        add(NavEntry("Comics", NavIcons.comics, id = MenuStops.COMICS, onClick = actions.onComics))
        add(NavEntry("Watch together", NavIcons.together, id = MenuStops.TOGETHER, onClick = actions.onTogether))
        add(NavEntry("Settings", NavIcons.settings, id = MenuStops.SETTINGS, onClick = actions.onSettings))
        add(NavEntry("Switch user", NavIcons.profile, id = MenuStops.PROFILES, onLongClick = actions.onQuickSwitch, onClick = actions.onProfiles))
    }.map { if (it.id == current) it.copy(selected = true) else it }
    return MenuCatalog(
        list.associateBy { it.id },
        // Pinned pages are Home's tabs, so they aren't stops by default (they can still be added in Menu stops).
        MenuStops.defaults(libraries.map { it.id }, emptyList()),
        nowPlaying = NavEntry("Now playing", NavIcons.music, id = NOW_PLAYING_STOP, onClick = actions.onNowPlaying),
    )
}

/** The menu stop that leads back to the listening player (not one the user arranges). */
const val NOW_PLAYING_STOP = "now-playing"

/** The app's menu, provided once by the nav host so every top-level page shows the same one. */
val LocalMenuCatalog = androidx.compose.runtime.staticCompositionLocalOf<MenuCatalog?> { null }

/**
 * The main menu around a top-level page ([current] is its [MenuStops] id), in the user's style and
 * arrangement. [overlay] for pages that fill the screen with art. Without a catalog (tests,
 * previews) the page shows alone.
 */
@Composable
fun MainMenu(current: String, overlay: Boolean, defaultTheme: dev.nitrate.core.effects.PageTheme? = null, content: @Composable (MenuInsets) -> Unit) {
    val catalog = LocalMenuCatalog.current ?: return content(MenuInsets())
    val prefs = dev.nitrate.tv.ui.components.LocalDisplayPrefs.current
    val marked = remember(catalog, current) { catalog.current(current) }
    // "Fade in" used to be its own menu: it's the top/bottom bar, hidden.
    val style = if (prefs.tvMenu == TvMenuStyle.FADE) TvMenuStyle.TOP else prefs.tvMenu
    val pageKey = dev.nitrate.core.effects.PageThemes.pageKeyFor(current)
    // While music plays, a "Now playing" stop first: the way back to it from anywhere.
    val listening = dev.nitrate.tv.ui.pages.LocalTvContainer.current?.listening
    val music = listening?.state?.collectAsState()?.value?.active == true
    val stops = marked.stops(prefs).let { s -> if (music && marked.nowPlaying != null) listOf(marked.nowPlaying) + s else s }
    TvMenu(style, stops, marked.dial(prefs), overlay, autoHide = prefs.tvMenuAutoHide || prefs.tvMenu == TvMenuStyle.FADE) { insets ->
        ThemedPage(pageKey, insets, defaultTheme) { content(insets) }
    }
}

/**
 * What's behind and in front of a page, kept for as long as the page frame shows (so switching tabs
 * never restarts it): the page's backdrop (the focused title's picture, reported by the page with
 * `PageBackdropSlot`, or the colour or gradient chosen for [pageKey]), then the back and middle of
 * its theme and the day's occasion (clear of [insets], where a menu sits; never in the player), the
 * page, and the theme's front over it. Menu pages get it from `MainMenu`; others (More from…) use it
 * directly.
 */
@Composable
fun ThemedPage(pageKey: String?, insets: MenuInsets = MenuInsets(), defaultTheme: dev.nitrate.core.effects.PageTheme? = null, content: @Composable () -> Unit) {
    // The page's own layout (core PageLooks): everything inside reads it as the display prefs.
    val prefs = dev.nitrate.core.pages.PageLooks.forPage(dev.nitrate.tv.ui.components.LocalDisplayPrefs.current, pageKey)
    val theme = pageKey?.let { prefs.pageThemes[it] } ?: defaultTheme
    val occasion = dev.nitrate.tv.ui.effects.rememberOccasion(prefs.effects)
    val themed = !prefs.easyMode
    val clear = Modifier.padding(start = insets.start, top = insets.top, end = insets.end, bottom = insets.bottom)
    val behind = remember { dev.nitrate.tv.ui.effects.ThemeBehind() }
    behind.active = themed && prefs.effects.on && ((theme != null && theme != dev.nitrate.core.effects.PageTheme.NONE) || occasion != null)
    val backdrop = pageKey?.let { prefs.pageBackdrops[it] } ?: dev.nitrate.core.effects.PageBackdrop.ARTWORK
    androidx.compose.runtime.CompositionLocalProvider(dev.nitrate.tv.ui.effects.LocalThemeBehind provides behind) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
            if (behind.titles > 0 || !backdrop.followsTitle) dev.nitrate.tv.ui.effects.PageBackground(backdrop, behind.artwork, clean = behind.clean)
            if (themed) dev.nitrate.tv.ui.effects.EffectsLayer(theme, occasion, prefs.effects, clear, depths = dev.nitrate.tv.ui.effects.BEHIND)
            if (behind.active && behind.titles > 0 && !prefs.tvCaptionBox) dev.nitrate.tv.ui.effects.ThemeShade()
            // The page's own layout, and the Customize panel held here so it outlives the page's
            // redraws (a row's new style, rows ↔ full-screen artwork).
            val customize = remember { dev.nitrate.tv.ui.pages.CustomizeHost() }
            androidx.compose.runtime.CompositionLocalProvider(
                dev.nitrate.tv.ui.components.LocalDisplayPrefs provides prefs,
                dev.nitrate.tv.ui.pages.LocalLayoutPage provides pageKey,
                dev.nitrate.tv.ui.pages.LocalCustomizeHost provides customize.takeIf { pageKey != null },
                dev.nitrate.tv.ui.components.LocalClockRoom provides if (prefs.tvClock) dev.nitrate.tv.ui.components.ClockRoom else 0.dp,
            ) { content() }
            // The clock, top end, clear of the menu; the page keeps that corner free (LocalClockRoom).
            if (prefs.tvClock) dev.nitrate.tv.ui.components.PageClock(Modifier.align(androidx.compose.ui.Alignment.TopEnd).then(clear).padding(top = 20.dp, end = 28.dp))
            if (pageKey != null) dev.nitrate.tv.ui.pages.CustomizeLayer(customize, pageKey)
            if (themed) dev.nitrate.tv.ui.effects.EffectsLayer(theme, occasion, prefs.effects, clear, depths = dev.nitrate.tv.ui.effects.INFRONT)
        }
    }
}
