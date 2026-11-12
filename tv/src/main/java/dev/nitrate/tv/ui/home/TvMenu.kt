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
 * The TV's main menu in the user's chosen [style], around [content]. [top] entries come first,
 * [bottom] ones after a gap. [overlay] draws bars over the content (full-screen art) instead of
 * beside it; [content] gets the space the menu keeps clear at each edge.
 */
@Composable
fun TvMenu(
    style: TvMenuStyle,
    top: List<NavEntry>,
    bottom: List<NavEntry>,
    overlay: Boolean,
    content: @Composable (MenuInsets) -> Unit,
) {
    when (style) {
        TvMenuStyle.LEFT -> Rail(top, bottom, overlay) { content(if (overlay) MenuInsets(start = RailWidth) else MenuInsets()) }
        TvMenuStyle.RIGHT -> CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Rail(top, bottom, overlay) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { content(if (overlay) MenuInsets(end = RailWidth) else MenuInsets()) }
            }
        }
        TvMenuStyle.TOP, TvMenuStyle.BOTTOM -> {
            val atTop = style == TvMenuStyle.TOP
            if (overlay) {
                Box(Modifier.fillMaxSize()) {
                    content(if (atTop) MenuInsets(top = BarHeight) else MenuInsets(bottom = BarHeight))
                    MenuBar(top + bottom, Modifier.align(if (atTop) Alignment.TopStart else Alignment.BottomStart), scrim = if (atTop) Scrim.TOP else Scrim.BOTTOM)
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    if (atTop) MenuBar(top + bottom, Modifier, Scrim.NONE)
                    Box(Modifier.weight(1f)) { content(MenuInsets()) }
                    if (!atTop) MenuBar(top + bottom, Modifier, Scrim.NONE)
                }
            }
        }
        TvMenuStyle.FADE -> FadeMenu(top + bottom, content)
        TvMenuStyle.RADIAL -> DialMenu(top + bottom, content)
    }
}

private val BarHeight = 76.dp
private val RailWidth = 40.dp

/**
 * Icons down the side that open with labels when focused. Over full-screen art the icons float on
 * the picture and the opened menu darkens it; otherwise the rail has its own column.
 */
@Composable
private fun Rail(top: List<NavEntry>, bottom: List<NavEntry>, overlay: Boolean, content: @Composable () -> Unit) {
    val state = rememberDrawerState(DrawerValue.Closed)
    val drawer: @Composable androidx.tv.material3.NavigationDrawerScope.(DrawerValue) -> Unit = {
            Column(
                Modifier.fillMaxHeight().padding(vertical = 24.dp, horizontal = 8.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                (top + listOf(null) + bottom).forEach { entry ->
                    if (entry == null) Spacer(Modifier.height(20.dp))
                    else NavigationDrawerItem(
                        selected = entry.selected,
                        onClick = entry.onClick,
                        leadingContent = { Icon(entry.icon, contentDescription = null) },
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

private enum class Scrim { NONE, TOP, BOTTOM }

/** A row of icon-and-word stops; the current page is underlined in the accent colour. */
@Composable
private fun MenuBar(entries: List<NavEntry>, modifier: Modifier, scrim: Scrim, firstFocus: FocusRequester? = null) {
    val shade = Color.Black.copy(alpha = 0.6f)
    val background = when (scrim) {
        Scrim.NONE -> Modifier
        Scrim.TOP -> Modifier.background(Brush.verticalGradient(listOf(shade, Color.Transparent)))
        Scrim.BOTTOM -> Modifier.background(Brush.verticalGradient(listOf(Color.Transparent, shade)))
    }
    LazyRow(
        modifier.fillMaxWidth().height(BarHeight).then(background),
        contentPadding = PaddingValues(horizontal = 50.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(entries) { i, entry -> BarItem(entry, if (i == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier) }
    }
}

@Composable
private fun BarItem(entry: NavEntry, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        onClick = entry.onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
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

/**
 * Nothing on screen until wanted: Back (or moving up past the top) brings a bar in over the top;
 * moving back down or pressing Back again puts it away.
 */
@Composable
private fun FadeMenu(entries: List<NavEntry>, content: @Composable (MenuInsets) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var hasFocus by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val back = remember { FocusRequester() }
    BackHandler(enabled = !open) { open = true }
    BackHandler(enabled = open) { open = false; runCatching { back.requestFocus() } }
    LaunchedEffect(open) { if (open && !hasFocus) runCatching { kotlinx.coroutines.delay(30); first.requestFocus() } }
    LaunchedEffect(hasFocus) { if (hasFocus) open = true else open = false }
    val alpha by animateFloatAsState(if (open) 1f else 0f, label = "fade-menu")
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().focusRequester(back).focusGroup()) { content(MenuInsets(top = BarHeight)) }
        MenuBar(
            entries,
            Modifier.align(Alignment.TopStart).alpha(alpha).onFocusChanged { hasFocus = it.hasFocus },
            Scrim.TOP,
            firstFocus = first,
        )
    }
}

/** Back opens a ring of destinations around the centre; the focused one is named in the middle. */
@Composable
private fun DialMenu(entries: List<NavEntry>, content: @Composable (MenuInsets) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var named by remember { mutableStateOf<NavEntry?>(null) }
    val first = remember { FocusRequester() }
    val back = remember { FocusRequester() }
    BackHandler(enabled = !open) { open = true }
    BackHandler(enabled = open) { open = false; runCatching { back.requestFocus() } }
    LaunchedEffect(open) { if (open) runCatching { kotlinx.coroutines.delay(30); first.requestFocus() } }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().focusRequester(back).focusGroup()) { content(MenuInsets()) }
        AnimatedVisibility(open, enter = fadeIn() + scaleIn(initialScale = 0.85f), exit = fadeOut() + scaleOut(targetScale = 0.85f)) {
            // Focus stays in the ring while it's open.
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).focusProperties { onExit = { cancelFocusChange() } }.focusGroup(), Alignment.Center) {
                val radius = 190.dp
                Box(Modifier.size(radius * 2 + 120.dp).border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape))
                Text(named?.label.orEmpty(), style = MaterialTheme.typography.headlineSmall, color = Color.White)
                entries.forEachIndexed { i, entry ->
                    // Clockwise from the top.
                    val angle = -PI / 2 + 2 * PI * i / entries.size
                    Surface(
                        onClick = { open = false; entry.onClick() },
                        shape = ClickableSurfaceDefaults.shape(CircleShape),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.White.copy(alpha = 0.12f),
                            contentColor = Color.White,
                            focusedContainerColor = MaterialTheme.colorScheme.primary,
                            focusedContentColor = Color.Black,
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.2f),
                        modifier = Modifier
                            .offset(x = radius * cos(angle).toFloat(), y = radius * sin(angle).toFloat())
                            .size(72.dp)
                            .onFocusChanged { if (it.isFocused) named = entry }
                            .then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                    ) {
                        Box(Modifier.fillMaxSize(), Alignment.Center) { Icon(entry.icon, contentDescription = entry.label, modifier = Modifier.size(30.dp)) }
                    }
                }
            }
        }
    }
}
