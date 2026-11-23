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


internal val BarHeight = 76.dp

/**
 * The overlay ([large], the default menu) and the side rail: nothing on screen until called, then
 * a full-height column of stops at the edge ([end]: the right), each its icon and its words, laid
 * over the page (never pushing it). The overlay dims the page behind a wide panel of big stops; the
 * rail is a narrower column. Focus starts on the current page's stop ([menuFocus]); ▲▼ move, a group
 * opens in place (its stops indented under it, focus on the first; moving off it or Back folds it,
 * and the page's own group shows open with its stop marked), and towards the page ([exitKey]) closes
 * it, as Back and Menu do (`TvMenu`). Choosing a stop closes it first ([onClose]).
 */
@Composable
internal fun SideMenu(
    stops: List<NavEntry>,
    large: Boolean,
    end: Boolean,
    menuFocus: FocusRequester,
    exitKey: Key,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val black = dev.nitrate.tv.ui.components.LocalDisplayPrefs.current.tvMenuBlack
    val target = stops.firstOrNull { it.selected } ?: stops.firstOrNull()
    // The group opened with OK (the page's own group shows open anyway).
    var openGroup by remember { mutableStateOf<String?>(null) }
    val choose: (NavEntry) -> () -> Unit = { e -> { onClose(); e.onClick() } }
    Box(
        Modifier.fillMaxSize()
            // The overlay dims the whole page; the rail only shades its own column.
            .then(if (large) Modifier.background(Color.Black.copy(alpha = if (black) 0.8f else 0.55f)) else Modifier),
        contentAlignment = if (end) Alignment.TopEnd else Alignment.TopStart,
    ) {
        Column(
            modifier.fillMaxHeight().width(if (large) 440.dp else 280.dp)
                .background(
                    if (black) Brush.horizontalGradient(listOf(Color.Black, Color.Black))
                    else Brush.horizontalGradient(
                        listOf(Color(0xF2101014), Color(0xE6101014)).let { if (end) it.reversed() else it },
                    ),
                )
                .onPreviewKeyEvent { e ->
                    if (e.key != exitKey) return@onPreviewKeyEvent false
                    if (e.type == KeyEventType.KeyDown) onClose()
                    true
                }
                // Nothing past its ends: ▲▼ stop at the first and last stop, the far side goes nowhere
                // (only moves are held; closing hands focus back to the page by request).
                .focusProperties {
                    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
                    exit = { d ->
                        val move = d == androidx.compose.ui.focus.FocusDirection.Up || d == androidx.compose.ui.focus.FocusDirection.Down ||
                            d == androidx.compose.ui.focus.FocusDirection.Left || d == androidx.compose.ui.focus.FocusDirection.Right
                        if (move) FocusRequester.Cancel else FocusRequester.Default
                    }
                }
                .focusGroup()
                .verticalScroll(rememberScrollState())
                .padding(vertical = if (large) 48.dp else 28.dp, horizontal = if (large) 28.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(if (large) 10.dp else 4.dp),
        ) {
            stops.forEach { entry ->
                if (entry.children.isEmpty()) {
                    MenuPanelItem(
                        entry, large, indent = false, onClick = choose(entry),
                        onLongClick = entry.onLongClick?.let { l -> { onClose(); l() } },
                        modifier = if (entry == target) Modifier.focusRequester(menuFocus) else Modifier,
                    )
                } else {
                    val here = entry.children.any { it.selected }
                    val open = openGroup == entry.id || here
                    val groupFocus = remember(entry.id) { FocusRequester() }
                    val firstChild = remember(entry.id) { FocusRequester() }
                    var focusFirst by remember(entry.id) { mutableStateOf(false) }
                    // Folds once focus has really left: handing focus from the group to its first stop
                    // passes through "nothing focused here" for a moment, which mustn't close it.
                    var inGroup by remember(entry.id) { mutableStateOf(false) }
                    LaunchedEffect(inGroup) { if (!inGroup) { kotlinx.coroutines.delay(48); if (openGroup == entry.id) openGroup = null } }
                    Column(
                        Modifier
                            .onFocusChanged { inGroup = it.hasFocus }
                            .onPreviewKeyEvent { e ->
                                if (e.key != Key.Back || openGroup != entry.id || here) return@onPreviewKeyEvent false
                                if (e.type == KeyEventType.KeyUp) { openGroup = null; runCatching { groupFocus.requestFocus() } }
                                true
                            },
                        verticalArrangement = Arrangement.spacedBy(if (large) 10.dp else 4.dp),
                    ) {
                        MenuPanelItem(
                            entry.copy(selected = entry.selected && !open), large, indent = false,
                            label = entry.label + if (open) "  ▾" else "  ▸",
                            onClick = { if (openGroup == entry.id) openGroup = null else { openGroup = entry.id; focusFirst = true } },
                            modifier = Modifier.focusRequester(groupFocus).then(if (entry == target && !here) Modifier.focusRequester(menuFocus) else Modifier),
                        )
                        if (open) entry.children.forEachIndexed { i, child ->
                            MenuPanelItem(
                                child, large, indent = true, onClick = choose(child),
                                onLongClick = child.onLongClick?.let { l -> { onClose(); l() } },
                                modifier = Modifier
                                    .then(if (i == 0) Modifier.focusRequester(firstChild) else Modifier)
                                    .then(if (child.selected) Modifier.focusRequester(menuFocus) else Modifier),
                            )
                        }
                    }
                    LaunchedEffect(focusFirst, open) {
                        if (focusFirst && open) { kotlinx.coroutines.delay(16); runCatching { firstChild.requestFocus() }; focusFirst = false }
                    }
                }
            }
        }
    }
}

/** One stop in the overlay or rail: its icon and words (never the icon alone), the current page marked. */
@Composable
private fun MenuPanelItem(
    entry: NavEntry,
    large: Boolean,
    indent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = entry.label,
    onLongClick: (() -> Unit)? = null,
) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(if (large) 16.dp else 12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (entry.selected) Color.White.copy(alpha = 0.12f) else Color.Transparent,
            contentColor = Color.White.copy(alpha = 0.9f),
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        modifier = Modifier.padding(start = if (indent) (if (large) 36.dp else 20.dp) else 0.dp).fillMaxWidth().then(modifier),
    ) {
        Row(
            Modifier.padding(horizontal = if (large) 22.dp else 14.dp, vertical = if (large) 14.dp else 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (large) 20.dp else 12.dp),
        ) {
            Icon(entry.icon, contentDescription = null, modifier = Modifier.size(if (indent) (if (large) 26.dp else 18.dp) else if (large) 32.dp else 22.dp))
            Text(
                label,
                style = if (large) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // Where you are.
            if (entry.selected) Box(Modifier.size(if (large) 10.dp else 8.dp).background(accent, CircleShape))
        }
    }
}

/**
 * A row of icon-and-word stops with the current page underlined. Pressing towards the page from
 * any stop goes back to it; Up from the top bar goes to the profile corner ([toCorner]), which sits
 * at the bar's end ([endRoom] kept clear for it).
 */
@Composable
internal fun MenuBar(entries: List<NavEntry>, edge: Edge, overlay: Boolean, menuFocus: FocusRequester, toContent: () -> Unit, modifier: Modifier, endRoom: androidx.compose.ui.unit.Dp = 0.dp, toCorner: (() -> Unit)? = null) {
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
    // Folds once focus has really left the bar (not while it's handed from one stop to the next).
    var inBar by remember { mutableStateOf(false) }
    LaunchedEffect(inBar) { if (!inBar) { kotlinx.coroutines.delay(48); openGroup = null } }
    LaunchedEffect(focusFirst, openGroup) { if (focusFirst && openGroup != null) { kotlinx.coroutines.delay(16); runCatching { firstChild.requestFocus() }; focusFirst = false } }
    LazyRow(
        // The bar's shade runs the whole width; its stops stop short of the corner.
        modifier.fillMaxWidth().height(BarHeight).then(background).padding(end = endRoom)
            .onFocusChanged { inBar = it.hasFocus }
            .onPreviewKeyEvent { e ->
                if (e.key == Key.Back && openGroup != null) {
                    if (e.type == KeyEventType.KeyUp) { openGroup = null }
                    return@onPreviewKeyEvent true
                }
                if (edge == Edge.TOP && e.key == Key.DirectionUp && toCorner != null) {
                    if (e.type == KeyEventType.KeyDown) toCorner()
                    return@onPreviewKeyEvent true
                }
                if (e.key != towardsPage) return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyDown) toContent()
                true
            },
        contentPadding = PaddingValues(start = 50.dp, end = if (endRoom > 0.dp) 12.dp else 50.dp),
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
