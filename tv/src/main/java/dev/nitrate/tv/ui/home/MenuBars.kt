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

internal val BarHeight = 76.dp

internal val RailWidth = 40.dp

/**
 * Icons down the side that open with labels when focused. Over full-screen art the icons float on
 * the picture and the opened menu darkens it; otherwise the rail has its own column.
 */
@Composable
internal fun Rail(
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
    // The group opened with OK (the page's own group shows open anyway).
    var openGroup by remember { mutableStateOf<String?>(null) }
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
                if (entry.children.isEmpty()) NavigationDrawerItem(
                    selected = entry.selected,
                    onClick = entry.onClick,
                    onLongClick = entry.onLongClick,
                    // Closed, the rail shows icons alone: each says its stop's name.
                    leadingContent = { Icon(entry.icon, contentDescription = if (drawerValue == DrawerValue.Closed) entry.label else null) },
                    modifier = if (entry == target) Modifier.focusRequester(menuFocus) else Modifier,
                ) { Text(entry.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                else {
                    // A group opens in place: OK shows its stops indented under it, focus on the first;
                    // moving off it (or Back) folds it again. The page's own group stays open, its stop marked.
                    val here = entry.children.any { it.selected }
                    val open = openGroup == entry.id || here
                    val groupFocus = remember(entry.id) { FocusRequester() }
                    val firstChild = remember(entry.id) { FocusRequester() }
                    var focusFirst by remember(entry.id) { mutableStateOf(false) }
                    Column(
                        Modifier
                            .onFocusChanged { if (!it.hasFocus && openGroup == entry.id) openGroup = null }
                            .onPreviewKeyEvent { e ->
                                if (e.key != Key.Back || openGroup != entry.id || here) return@onPreviewKeyEvent false
                                if (e.type == KeyEventType.KeyUp) { openGroup = null; runCatching { groupFocus.requestFocus() } }
                                true
                            },
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        NavigationDrawerItem(
                            selected = entry.selected && !open,
                            onClick = { if (openGroup == entry.id) openGroup = null else { openGroup = entry.id; focusFirst = true } },
                            leadingContent = { Icon(entry.icon, contentDescription = if (drawerValue == DrawerValue.Closed) entry.label else null) },
                            modifier = Modifier.focusRequester(groupFocus).then(if (entry == target && !here) Modifier.focusRequester(menuFocus) else Modifier),
                        ) { Text(entry.label + if (open) "  ▾" else "  ▸", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        if (open) entry.children.forEachIndexed { i, child ->
                            NavigationDrawerItem(
                                selected = child.selected,
                                onClick = child.onClick,
                                onLongClick = child.onLongClick,
                                leadingContent = { Icon(child.icon, contentDescription = if (drawerValue == DrawerValue.Closed) "${entry.label}: ${child.label}" else null, modifier = Modifier.size(20.dp)) },
                                modifier = Modifier
                                    .padding(start = if (drawerValue == DrawerValue.Open) 20.dp else 0.dp)
                                    .then(if (i == 0) Modifier.focusRequester(firstChild) else Modifier)
                                    .then(if (child.selected) Modifier.focusRequester(menuFocus) else Modifier),
                            ) { Text(child.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                    LaunchedEffect(focusFirst, open) {
                        if (focusFirst && open) { kotlinx.coroutines.delay(16); runCatching { firstChild.requestFocus() }; focusFirst = false }
                    }
                }
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
internal fun MenuBar(entries: List<NavEntry>, edge: Edge, overlay: Boolean, menuFocus: FocusRequester, toContent: () -> Unit, modifier: Modifier) {
    val shade = Color.Black.copy(alpha = 0.6f)
    val background = when {
        dev.nitrate.tv.ui.components.LocalDisplayPrefs.current.tvMenuBlack -> Modifier.background(Color.Black)
        !overlay -> Modifier
        edge == Edge.TOP -> Modifier.background(Brush.verticalGradient(listOf(shade, Color.Transparent)))
        else -> Modifier.background(Brush.verticalGradient(listOf(Color.Transparent, shade)))
    }
    val towardsPage = if (edge == Edge.TOP) Key.DirectionDown else Key.DirectionUp
    val target = entries.firstOrNull { it.selected } ?: entries.firstOrNull()
    // A group opens in place: its stops follow it along the bar (tinted), focus on the first; moving
    // off the group and its stops (or Back) folds it. The page's own group stays open, its stop marked.
    var openGroup by remember { mutableStateOf<String?>(null) }
    var focusedId by remember { mutableStateOf<String?>(null) }
    val firstChild = remember { FocusRequester() }
    var focusFirst by remember { mutableStateOf(false) }
    fun isOpen(e: NavEntry) = openGroup == e.id || e.children.any { it.selected }
    val shown = entries.flatMap { e -> if (e.children.isNotEmpty() && isOpen(e)) listOf(e) + e.children.map { it.copy(id = "${e.id}/${it.id}") } else listOf(e) }
    LaunchedEffect(focusedId) {
        val g = openGroup ?: return@LaunchedEffect
        if (focusedId != null && focusedId != g && focusedId?.startsWith("$g/") != true) openGroup = null
    }
    LaunchedEffect(focusFirst, openGroup) { if (focusFirst && openGroup != null) { kotlinx.coroutines.delay(16); runCatching { firstChild.requestFocus() }; focusFirst = false } }
    LazyRow(
        modifier.fillMaxWidth().height(BarHeight).then(background)
            .onFocusChanged { if (!it.hasFocus) openGroup = null }
            .onPreviewKeyEvent { e ->
                if (e.key == Key.Back && openGroup != null) {
                    if (e.type == KeyEventType.KeyUp) { openGroup = null }
                    return@onPreviewKeyEvent true
                }
                if (e.key != towardsPage) return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyDown) toContent()
                true
            },
        contentPadding = PaddingValues(horizontal = 50.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(shown, key = { _, e -> e.id }) { _, entry ->
            val member = entry.id.contains('/')
            val group = entry.children.isNotEmpty()
            val firstOfGroup = member && shown.getOrNull(shown.indexOf(entry) - 1)?.children?.isNotEmpty() == true
            val focusTarget = if (member) entry.selected else entry == target && !(group && entry.children.any { it.selected })
            BarItem(
                if (group) entry.copy(label = entry.label + if (isOpen(entry)) "  ▾" else "  ▸", onClick = {
                    if (openGroup == entry.id) openGroup = null else { openGroup = entry.id; focusFirst = true }
                }) else entry,
                emphasis = member,
                modifier = Modifier.onFocusChanged { if (it.isFocused) focusedId = entry.id }
                    .then(if (firstOfGroup) Modifier.focusRequester(firstChild) else Modifier)
                    .then(if (focusTarget) Modifier.focusRequester(menuFocus) else Modifier),
            )
        }
    }
}

@Composable
internal fun BarItem(entry: NavEntry, emphasis: Boolean = false, modifier: Modifier = Modifier) {
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
