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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
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
import dev.nitrate.core.pages.MenuStops
import dev.nitrate.core.pages.CustomPage
import dev.nitrate.core.pages.DisplayPrefs
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import dev.nitrate.core.pages.MenuGroups
import dev.nitrate.core.pages.MenuLayout
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
    /** A group (`group:<id>`): OK opens it in place onto these stops instead of going anywhere. */
    val children: List<NavEntry> = emptyList(),
    val onClick: () -> Unit,
)

/** Standard icons for the menu; kept here so screens don't each pick their own. */
object NavIcons {
    val search = Icons.Filled.Search
    val home = Icons.Filled.Home
    val pages = Icons.AutoMirrored.Filled.List
    val customize = Icons.Filled.Edit
    val together = Icons.Filled.Face
    val settings = Icons.Filled.Settings
    val profile = Icons.Filled.AccountCircle
    val channels = Icons.Filled.DateRange
    val comics = Icons.Filled.Star
    val music = Icons.Filled.PlayArrow
    val more = Icons.Filled.MoreVert
    val playlist = Icons.AutoMirrored.Filled.List

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

internal enum class Edge { TOP, BOTTOM }

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

/**
 * The main menu around a top-level page ([current] is its [MenuStops] id), in the user's style and
 * arrangement. [overlay] for pages that fill the screen with art. Without a catalog (tests,
 * previews) the page shows alone.
 */
@Composable
fun MainMenu(current: String, overlay: Boolean, content: @Composable (MenuInsets) -> Unit) {
    val catalog = LocalMenuCatalog.current ?: return content(MenuInsets())
    val prefs = dev.nitrate.tv.ui.components.LocalDisplayPrefs.current
    val marked = remember(catalog, current) { catalog.current(current) }
    // "Fade in" used to be its own menu: it's the top/bottom bar, hidden.
    val style = if (prefs.tvMenu == TvMenuStyle.FADE) TvMenuStyle.TOP else prefs.tvMenu
    val pageKey = dev.nitrate.core.effects.PageThemes.pageKeyFor(current)
    // While music plays, a "Now playing" stop first: the way back to it from anywhere.
    val listening = dev.nitrate.tv.ui.pages.LocalTvContainer.current?.listening
    val music = listening?.state?.collectAsState()?.value?.active == true
    // The page's Customize panel, held here so the menu's Customize stop can open it.
    val customize = remember { dev.nitrate.tv.ui.pages.CustomizeHost() }
    // "Customize" (a pencil) just before Settings on every page with a layout of its own: the way to
    // change the page without knowing to hold OK on a row. Not in easy mode.
    val customizeStop = if (pageKey != null && !prefs.easyMode) NavEntry("Customize this page", NavIcons.customize, id = CUSTOMIZE_STOP, onClick = { customize.openPage() }) else null
    val stops = marked.stops(prefs)
        .let { s -> if (customizeStop == null) s else s.indexOfFirst { it.id == dev.nitrate.core.pages.MenuStops.SETTINGS }.let { at -> if (at < 0) s + customizeStop else s.take(at) + customizeStop + s.drop(at) } }
        .let { s -> if (music && marked.nowPlaying != null) listOf(marked.nowPlaying) + s else s }
    TvMenu(style, stops, marked.dial(prefs), overlay, autoHide = prefs.tvMenuAutoHide || prefs.tvMenu == TvMenuStyle.FADE) { insets ->
        ThemedPage(pageKey, insets, customize) { content(insets) }
    }
}
