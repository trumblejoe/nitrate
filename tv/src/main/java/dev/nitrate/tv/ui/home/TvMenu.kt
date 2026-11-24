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
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
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
 * Top/Bottom bar over the content (full-screen art) instead of above it; [content] gets the space
 * the menu keeps clear at each edge.
 *
 * The overlay (the default) and the side rail show nothing while you browse: moving off the page's
 * edge (the rail's side; for the overlay the arrow chosen in [opensWith], Left unless changed) or the
 * Menu key opens them over the page, focus on the current page's stop, and Back or Menu close them
 * (the rail also towards the page), focus going back where it was on the page. The overlay is a
 * pop-up card in the middle ([MenuPopup]) with Search and the avatar in its top row and More's
 * stops ([more]) below the menu's own. The fade-in bar and the dial wait at the top edge, and the Top/Bottom bar is at
 * the top on the first row and at the bottom on the last. The remote's Menu key opens and closes the
 * menu from anywhere, and Back from the menu returns to the page. Back on the page is never taken over.
 *
 * With [corner], the profile corner sits top right (Search and the avatar, [ProfileCorner]): Up from
 * the page's top reaches it (on the Top bar and the dial, Up from the bar or past the dial's top).
 * The overlay shows no corner: its pop-up holds Search and the avatar itself.
 */
@Composable
fun TvMenu(
    style: TvMenuStyle,
    stops: List<NavEntry>,
    dial: List<NavEntry?>,
    overlay: Boolean,
    /** Keep the menu invisible until focus reaches it, fading in over the page (as on Home). */
    autoHide: Boolean = false,
    corner: CornerActions? = null,
    /** The overlay pop-up's "More" section: the stops kept in More (other styles reach them through the More page). */
    more: List<NavEntry> = emptyList(),
    /** The overlay: which arrow at the page's edge opens it (Left, Right or Up). */
    opensWith: Key = Key.DirectionLeft,
    content: @Composable (MenuInsets) -> Unit,
) {
    // A hidden menu takes no room of its own: it fades in over the page on every page, not only
    // over Home's full-screen art (a column of its own left a blank strip while hidden).
    @Suppress("NAME_SHADOWING") val overlay = overlay || autoHide
    // The overlay and the rail: nothing on screen until called, then a column over the page.
    val panel = style == TvMenuStyle.OVERLAY || style.rail
    // The overlay is a pop-up in the middle, which holds Search and the account itself: no corner.
    val popup = style == TvMenuStyle.OVERLAY
    val showCorner = corner != null && !popup
    val contentFocus = remember { FocusRequester() }
    val menuFocus = remember { FocusRequester() }
    var inMenu by remember { mutableStateOf(false) }
    var dialOpen by remember { mutableStateOf(false) }
    var panelOpen by remember { mutableStateOf(false) }
    var focusPanel by remember { mutableStateOf(0) }
    // Top/Bottom: which rows the page is on, and where the bar is (kept while you're in it).
    var onFirst by remember { mutableStateOf(true) }
    var onLast by remember { mutableStateOf(true) }
    var forcedTop by remember { mutableStateOf(false) }
    var focusBar by remember { mutableStateOf(0) }
    // The profile corner and its account panel.
    val searchFocus = remember { FocusRequester() }
    val avatarFocus = remember { FocusRequester() }
    var inCorner by remember { mutableStateOf(false) }
    var accountOpen by remember { mutableStateOf(false) }
    var focusAvatar by remember { mutableStateOf(0) }
    // Back to the page where it was left (the card in the row, not the first thing on the page) when
    // the page can say where that was (dev.nitrate.tv.ui.rows.FocusReturn), else to its start.
    val focusReturn = remember { dev.nitrate.tv.ui.rows.FocusReturn() }
    val toContent: () -> Unit = {
        dialOpen = false; forcedTop = false
        if (focusReturn.restore?.invoke() != true) runCatching { contentFocus.requestFocus() }
        panelOpen = false
    }
    val toMenu: () -> Unit = {
        when (style) {
            TvMenuStyle.RADIAL -> dialOpen = true
            // On the first or last row the bar is already there; elsewhere Menu brings it to the top first.
            TvMenuStyle.TOP, TvMenuStyle.FADE -> if (onFirst || onLast) runCatching { menuFocus.requestFocus() } else { forcedTop = true; focusBar++ }
            // Shown at once, focus on the current page's stop as soon as it's there.
            else -> { panelOpen = true; focusPanel++ }
        }
    }
    val toCorner: () -> Unit = {
        dialOpen = false; forcedTop = false; panelOpen = false
        if (!runCatching { avatarFocus.requestFocus() }.getOrDefault(false)) focusAvatar++
    }
    // The bar may only just have been placed: focus it once it's there.
    LaunchedEffect(focusBar) { if (focusBar > 0) runCatching { kotlinx.coroutines.delay(16); menuFocus.requestFocus() } }
    // The panel's stops are composed when it opens: focus the current one at once (asked again for a
    // few frames in case the column isn't placed yet; nothing waits on an animation).
    LaunchedEffect(focusPanel) {
        if (focusPanel > 0) repeat(10) {
            if (runCatching { menuFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            kotlinx.coroutines.delay(16)
        }
    }
    LaunchedEffect(focusAvatar) {
        if (focusAvatar > 0) repeat(10) {
            if (runCatching { avatarFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            kotlinx.coroutines.delay(16)
        }
    }
    // Focus gone from the open panel for good (not just handed between stops): it closes. Not while
    // the pop-up's account panel is open over it (a window of its own has focus then).
    LaunchedEffect(panelOpen, inMenu, accountOpen) {
        if (panelOpen && !inMenu && !accountOpen) { kotlinx.coroutines.delay(250); if (!inMenu && !accountOpen) panelOpen = false }
    }
    BackHandler(enabled = inMenu || dialOpen || panelOpen || inCorner, onBack = toContent)

    val side: (Key) -> Boolean = { key ->
        when (style) {
            TvMenuStyle.OVERLAY -> key == opensWith
            TvMenuStyle.LEFT -> key == Key.DirectionLeft
            TvMenuStyle.RIGHT -> key == Key.DirectionRight
            // With one row the bar is only at the top.
            TvMenuStyle.TOP, TvMenuStyle.FADE -> (key == Key.DirectionUp && onFirst) || (key == Key.DirectionDown && onLast && !onFirst)
            else -> key == Key.DirectionUp
        }
    }
    // Past the page's top (where the menu isn't): the profile corner.
    val reach: (Key) -> Boolean = { key ->
        when {
            side(key) -> { toMenu(); true }
            key == Key.DirectionUp && showCorner -> { toCorner(); true }
            else -> false
        }
    }
    val rows: (Boolean, Boolean) -> Unit = { first, last -> onFirst = first; onLast = last }

    // Leaving the page towards the menu always lands on the menu (its current stop, or the dial),
    // and leaving it upwards elsewhere on the profile corner, whatever the page is (rows, a grid, a
    // list), instead of whichever piece of it happens to be nearest. Moves within the page are left
    // alone; a page that handles its own edges (full art) never lets focus leave this way.
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
                    when {
                        key != null && side(key) -> { mainHandler.post { toMenu() }; FocusRequester.Cancel }
                        key == Key.DirectionUp && showCorner -> { mainHandler.post { toCorner() }; FocusRequester.Cancel }
                        else -> FocusRequester.Default
                    }
                }
            }
            .focusRequester(contentFocus).focusGroup(),
    ) {
        CompositionLocalProvider(
            LocalMenuEdge provides reach, LocalMenuSide provides side, LocalMenuRows provides rows,
            dev.nitrate.tv.ui.rows.LocalFocusReturn provides focusReturn,
            LocalCornerRoom provides if (showCorner) CornerRoom else 0.dp,
        ) { content(insets) }
    }
    val shown by animateFloatAsState(if (!autoHide || inMenu) 1f else 0f, label = "menu-fade")
    val trackMenu = Modifier.alpha(shown).onFocusChanged { inMenu = it.hasFocus; if (!it.hasFocus) forcedTop = false }
    val account = if (corner != null) rememberCornerAccount() else null

    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            if (e.key != Key.Menu) return@onPreviewKeyEvent false
            if (e.type == KeyEventType.KeyUp) { if (inMenu || dialOpen || panelOpen) toContent() else toMenu() }
            true
        }
            // Back from the menu or the corner, taken as a key too (as well as BackHandler) once what's
            // inside (an open group) has passed on it: back to the page.
            .onKeyEvent { e ->
                if (e.key != Key.Back || !(inMenu || dialOpen || panelOpen || inCorner)) return@onKeyEvent false
                if (e.type == KeyEventType.KeyUp) toContent()
                true
            },
    ) {
        when (style) {
            TvMenuStyle.OVERLAY, TvMenuStyle.LEFT, TvMenuStyle.RIGHT -> Box(Modifier.fillMaxSize()) {
                page(MenuInsets())
                // Hidden, the menu has nothing focus could move to: these slivers at the edges are
                // where moving off the page goes (the page's exit sends it on to the menu or corner).
                // (The overlay's: on the edge its arrow leads to; along the top for Up.)
                val catcher = when {
                    style == TvMenuStyle.RIGHT || (popup && opensWith == Key.DirectionRight) -> Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(1.dp)
                    popup && opensWith == Key.DirectionUp -> Modifier.align(Alignment.TopCenter).fillMaxWidth().height(1.dp)
                    else -> Modifier.align(Alignment.CenterStart).fillMaxHeight().width(1.dp)
                }
                EdgeCatcher("Menu", catcher, enabled = !panelOpen, onFocus = toMenu)
                if (showCorner) EdgeCatcher("Account and search", Modifier.align(Alignment.TopCenter).fillMaxWidth().height(1.dp), enabled = !panelOpen, onFocus = toCorner)
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
                        MenuBar(
                            stops, e, overlay = true, menuFocus, toContent, Modifier.align(if (e == Edge.TOP) Alignment.TopStart else Alignment.BottomStart).then(trackMenu),
                            endRoom = if (e == Edge.TOP && corner != null) CornerRoom else 0.dp, toCorner = toCorner.takeIf { corner != null },
                        )
                    }
                }
            } else {
                // The classic rows scroll on their own, so the bar stays at the top.
                Column(Modifier.fillMaxSize()) {
                    MenuBar(stops, Edge.TOP, overlay = false, menuFocus, toContent, trackMenu, endRoom = if (corner != null) CornerRoom else 0.dp, toCorner = toCorner.takeIf { corner != null })
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
            }
        }
        // The profile corner, top right on every page; under the open menu (its dimming covers it).
        if (showCorner && account != null) {
            ProfileCorner(
                account,
                onSearch = corner.onSearch,
                onAvatar = { accountOpen = true },
                searchFocus = searchFocus,
                avatarFocus = avatarFocus,
                onKey = { key ->
                    when {
                        key == Key.DirectionDown -> toContent()
                        // Left from Search (or Right from the avatar) when the menu is that way.
                        panel && side(key) -> toMenu()
                    }
                },
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 26.dp)
                    .onFocusChanged { inCorner = it.hasFocus },
            )
        }
        if (accountOpen && corner != null && account != null) AccountPanel(
            account, corner,
            // From the pop-up, choosing something closes the pop-up too (back to the page, then on).
            onChosen = if (popup) ({ accountOpen = false; toContent() }) else null,
        ) {
            accountOpen = false
            // Back on the avatar (the corner's or the pop-up's) once the panel's window has gone.
            focusAvatar++
        }
        if (popup && panelOpen) {
            MenuPopup(
                stops, more,
                account = account ?: CornerAccount("", null),
                onSearch = corner?.onSearch,
                onAvatar = if (corner != null) ({ accountOpen = true }) else null,
                menuFocus = menuFocus,
                avatarFocus = avatarFocus,
                onClose = toContent,
                modifier = Modifier.onFocusChanged { inMenu = it.hasFocus },
            )
        } else if (panel && panelOpen) {
            SideMenu(
                stops,
                large = false,
                end = style == TvMenuStyle.RIGHT,
                menuFocus = menuFocus,
                exitKey = if (style == TvMenuStyle.RIGHT) Key.DirectionLeft else Key.DirectionRight,
                onClose = toContent,
                modifier = Modifier.onFocusChanged { inMenu = it.hasFocus },
            )
        }
        if (style == TvMenuStyle.RADIAL && dialOpen) Dial(dial, onClose = toContent, onCorner = toCorner.takeIf { corner != null })
    }
}

/**
 * The main menu around a top-level page ([current] is its [MenuStops] id), in the user's style and
 * arrangement, with the profile corner top right. [overlay] for pages that fill the screen with art.
 * Without a catalog (tests, previews) the page shows alone.
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
    // "Customize" (a pencil) just before Settings (at the end where Settings is in the corner) on
    // every page with a layout of its own: the way to change the page without knowing to hold OK
    // on a row. Not in easy mode.
    val customizeStop = if (pageKey != null && !prefs.easyMode) NavEntry("Customize this page", NavIcons.customize, id = CUSTOMIZE_STOP, onClick = { customize.openPage() }) else null
    // The overlay's pop-up shows More's stops in a section of their own, so it has no More stop.
    val popup = style == TvMenuStyle.OVERLAY
    val more = if (popup) marked.more(prefs) else emptyList()
    val stops = marked.stops(prefs)
        .let { s -> if (popup) s.filter { it.id != MenuStops.MORE } else s }
        .let { s -> if (customizeStop == null) s else s.indexOfFirst { it.id == dev.nitrate.core.pages.MenuStops.SETTINGS }.let { at -> if (at < 0) s + customizeStop else s.take(at) + customizeStop + s.drop(at) } }
        .let { s -> if (music && marked.nowPlaying != null) listOf(marked.nowPlaying) + s else s }
    // The profile corner: Search, and the account panel's Switch user, Settings and Sign out.
    val corner = remember(marked) {
        CornerActions(
            onSearch = marked.entries[MenuStops.SEARCH]?.onClick,
            onSettings = marked.entries[MenuStops.SETTINGS]?.onClick,
            onProfiles = marked.entries[MenuStops.PROFILES]?.onClick,
            onQuickSwitch = marked.entries[MenuStops.PROFILES]?.onLongClick,
            onSignOut = marked.signOut,
        )
    }
    val opensWith = when (prefs.tvPopupKey) {
        dev.nitrate.core.pages.PopupKey.LEFT -> Key.DirectionLeft
        dev.nitrate.core.pages.PopupKey.RIGHT -> Key.DirectionRight
        dev.nitrate.core.pages.PopupKey.UP -> Key.DirectionUp
    }
    TvMenu(
        style, stops, marked.dial(prefs), overlay, autoHide = prefs.tvMenuAutoHide || prefs.tvMenu == TvMenuStyle.FADE, corner = corner,
        more = more, opensWith = opensWith,
    ) { insets ->
        ThemedPage(pageKey, insets, customize) { content(insets) }
    }
}

/** An invisible sliver at the screen's edge that moving off the page can land on, named for where it leads. */
@Composable
private fun EdgeCatcher(name: String, modifier: Modifier, enabled: Boolean, onFocus: () -> Unit) {
    Box(
        modifier
            .onFocusChanged { if (it.isFocused) onFocus() }
            .semantics { contentDescription = name }
            .focusable(enabled = enabled),
    )
}
