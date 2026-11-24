package dev.nitrate.tv.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

private val TileGap = 14.dp
private val TileMinWidth = 140.dp
private val TileHeight = 104.dp
private val HeaderHeight = 64.dp
private val CardPadding = 28.dp

/**
 * The overlay menu (`TvMenuStyle.OVERLAY`): a card in the middle of the dimmed page that scales and
 * fades up as it opens (focus is asked for at once, never after the animation). Its top row holds
 * Search and the avatar ([onAvatar]: the account panel with Switch user, Settings, the display
 * profile and Sign out), then the menu's [stops] as a grid of large tiles (icon and words) in the
 * user's order, then More's stops ([more]) under a "More" heading. Focus starts on the current
 * page's tile ([menuFocus]); all four arrows move around the grid and nothing past its edges leaves
 * the card (only moves are held, so closing can hand focus back to the page). A group's tile opens
 * its stops as a small grid of their own under the group's name; Back steps out to the main grid
 * with focus on the group's tile. Choosing a tile closes the card first ([onClose]), then goes.
 * Too many tiles for the screen scroll inside the card, the focused one brought into view.
 */
@Composable
internal fun MenuPopup(
    stops: List<NavEntry>,
    more: List<NavEntry>,
    account: CornerAccount,
    onSearch: (() -> Unit)?,
    onAvatar: (() -> Unit)?,
    menuFocus: FocusRequester,
    avatarFocus: FocusRequester,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val black = dev.nitrate.tv.ui.components.LocalDisplayPrefs.current.tvMenuBlack
    val target = (stops + more).firstOrNull { it.selected } ?: stops.firstOrNull() ?: more.firstOrNull()
    var openGroupId by remember { mutableStateOf<String?>(null) }
    val openGroup = openGroupId?.let { id -> (stops + more).firstOrNull { it.id == id } }
    // Back out of a group: focus goes to its tile once the main grid is back.
    var backTo by remember { mutableStateOf<String?>(null) }
    val groupTiles = remember { mutableMapOf<String, FocusRequester>() }
    val firstMember = remember { FocusRequester() }
    val choose: (NavEntry) -> () -> Unit = { e ->
        if (e.children.isNotEmpty()) ({ openGroupId = e.id })
        else ({ onClose(); e.onClick() })
    }
    val held: (NavEntry) -> (() -> Unit)? = { e -> e.onLongClick?.let { l -> { onClose(); l() } } }

    // Opening: the page dims and the card scales and fades up, quickly.
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(160)) }

    LaunchedEffect(openGroupId) {
        if (openGroupId != null) repeat(10) {
            if (runCatching { firstMember.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            kotlinx.coroutines.delay(16)
        }
    }
    LaunchedEffect(backTo) {
        val id = backTo ?: return@LaunchedEffect
        repeat(10) {
            if (runCatching { groupTiles[id]?.requestFocus() }.getOrDefault(false) == true) { backTo = null; return@LaunchedEffect }
            kotlinx.coroutines.delay(16)
        }
        backTo = null
    }

    BoxWithConstraints(
        Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        // Only the dimming fades; the card itself is solid from the first frame, so the page never shows through it.
        Box(Modifier.matchParentSize().graphicsLayer { alpha = appear.value }.background(Color.Black.copy(alpha = if (black) 0.8f else 0.6f)))
        val cardWidth = min(maxWidth - 96.dp, 900.dp)
        val inner = cardWidth - CardPadding * 2
        val columns = ((inner + TileGap) / (TileMinWidth + TileGap)).toInt().coerceIn(2, 6)
        val tileWidth = (inner - TileGap * (columns - 1)) / columns
        val shape = RoundedCornerShape(28.dp)
        Column(
            modifier
                .width(cardWidth)
                .heightIn(max = maxHeight - 48.dp)
                .graphicsLayer {
                    val s = 0.9f + 0.1f * appear.value
                    scaleX = s; scaleY = s
                }
                .background(if (black) Color.Black else Color(0xFF121218), shape)
                .border(1.dp, Color.White.copy(alpha = if (black) 0.18f else 0.1f), shape)
                // Back inside a group steps out to the main grid (the page's Back handling never sees it).
                .onPreviewKeyEvent { e ->
                    if (e.key != Key.Back || openGroupId == null) return@onPreviewKeyEvent false
                    if (e.type == KeyEventType.KeyUp) { backTo = openGroupId; openGroupId = null }
                    true
                }
                // Nothing past the card's edges: only moves are held, so closing can hand focus back.
                .focusProperties {
                    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
                    exit = { d ->
                        val move = d == FocusDirection.Up || d == FocusDirection.Down || d == FocusDirection.Left || d == FocusDirection.Right
                        if (move) FocusRequester.Cancel else FocusRequester.Default
                    }
                }
                .focusGroup()
                .verticalScroll(rememberScrollState())
                .padding(CardPadding),
            verticalArrangement = Arrangement.spacedBy(TileGap),
        ) {
            if (openGroup != null) {
                Text(openGroup.label, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Text("Back returns to the menu", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.65f))
                TileGrid(openGroup.children, columns, tileWidth) { i, entry ->
                    PopupTile(
                        entry, tileWidth, onClick = choose(entry), onLongClick = held(entry),
                        modifier = if (i == 0) Modifier.focusRequester(firstMember) else Modifier,
                    )
                }
                return@Column
            }
            // Search and the account, the card's top row (Search at the start, the avatar at the end).
            if (onSearch != null || onAvatar != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (onSearch != null) HeaderTile(
                    "Search", tileWidth, onClick = { onClose(); onSearch() },
                    modifier = if (target == null) Modifier.focusRequester(menuFocus) else Modifier,
                ) { Icon(NavIcons.search, contentDescription = null, modifier = Modifier.size(28.dp)) }
                Spacer(Modifier.weight(1f))
                if (onAvatar != null) HeaderTile(
                    account.name.ifBlank { "Your account" }, tileWidth * 2 + TileGap, onClick = onAvatar,
                    detail = "Settings, switch user",
                    spoken = account.spoken,
                    modifier = Modifier.focusRequester(avatarFocus).then(if (target == null && onSearch == null) Modifier.focusRequester(menuFocus) else Modifier),
                ) { Avatar(account, 40.dp) }
            }
            TileGrid(stops, columns, tileWidth) { _, entry ->
                PopupTile(entry, tileWidth, onClick = choose(entry), onLongClick = held(entry), modifier = tileModifier(entry, target, menuFocus, groupTiles))
            }
            if (more.isNotEmpty()) {
                Text("More", style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(top = 8.dp))
                TileGrid(more, columns, tileWidth) { _, entry ->
                    PopupTile(entry, tileWidth, onClick = choose(entry), onLongClick = held(entry), modifier = tileModifier(entry, target, menuFocus, groupTiles))
                }
            }
        }
    }
}

private fun tileModifier(entry: NavEntry, target: NavEntry?, menuFocus: FocusRequester, groupTiles: MutableMap<String, FocusRequester>): Modifier =
    Modifier
        .then(if (entry.children.isNotEmpty()) Modifier.focusRequester(groupTiles.getOrPut(entry.id) { FocusRequester() }) else Modifier)
        .then(if (entry == target) Modifier.focusRequester(menuFocus) else Modifier)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TileGrid(entries: List<NavEntry>, columns: Int, tileWidth: Dp, tile: @Composable (Int, NavEntry) -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val margin = with(androidx.compose.ui.platform.LocalDensity.current) { CardPadding.toPx() }
    entries.chunked(columns).forEachIndexed { r, row ->
        // The focused row comes into view with room round it, so its grown tile isn't cut off at the card's edge.
        val inView = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
        var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
        Row(
            Modifier
                .fillMaxWidth()
                // Left/Right stay in the row: centred rows sit inset, so the search could jump diagonally to the top row.
                .focusProperties {
                    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
                    exit = { d -> if (d == FocusDirection.Left || d == FocusDirection.Right) FocusRequester.Cancel else FocusRequester.Default }
                }
                .focusGroup()
                .bringIntoViewRequester(inView)
                .onSizeChanged { size = it }
                .onFocusChanged { f ->
                    if (f.hasFocus) scope.launch {
                        kotlinx.coroutines.delay(16)
                        inView.bringIntoView(androidx.compose.ui.geometry.Rect(0f, -margin, size.width.toFloat(), size.height + margin))
                    }
                },
            // Rows are centred, so a short last row sits in the middle rather than leaving a gap on the right.
            horizontalArrangement = Arrangement.spacedBy(TileGap, Alignment.CenterHorizontally),
        ) {
            row.forEachIndexed { c, entry -> Box(Modifier.width(tileWidth)) { tile(r * columns + c, entry) } }
        }
    }
}

/** One stop: a large tile, its icon over its words (never the icon alone), the current page marked with a dot; a group says how many it holds. */
@Composable
private fun PopupTile(entry: NavEntry, width: Dp, onClick: () -> Unit, onLongClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(18.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = if (entry.selected) 0.16f else 0.07f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        modifier = modifier.width(width).height(TileHeight),
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(entry.icon, contentDescription = null, modifier = Modifier.size(32.dp))
                Text(
                    entry.label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                    maxLines = if (entry.children.isEmpty()) 2 else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp),
                )
                if (entry.children.isNotEmpty()) Text(
                    "${entry.children.size} inside  ›", style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    modifier = Modifier.graphicsLayer { alpha = 0.75f },
                )
            }
            // Where you are.
            if (entry.selected) Box(Modifier.align(Alignment.TopEnd).padding(10.dp).size(10.dp).background(accent, CircleShape))
        }
    }
}

/** Search and the account in the card's top row: a wide, low tile, the picture or icon beside the words. */
@Composable
private fun HeaderTile(
    label: String,
    width: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    spoken: String? = null,
    leading: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.07f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        modifier = modifier.width(width).height(HeaderHeight).then(if (spoken != null) Modifier.semantics { contentDescription = spoken } else Modifier),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            leading()
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.graphicsLayer { alpha = 0.75f })
            }
        }
    }
}
