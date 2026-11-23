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

/** Up, Right, Down, Left: the dial's spokes, in the order entries fill each ring. */
internal val Spokes = listOf(Key.DirectionUp, Key.DirectionRight, Key.DirectionDown, Key.DirectionLeft)

/**
 * Rings of up to four stops each, only straight up, right, down and left of the centre, so every
 * move is a single press. Pressing outwards along a spoke steps to the next ring, inwards steps
 * back (to the centre from the first ring), and sideways swings to that side on the same ring.
 */
@Composable
internal fun Dial(slots: List<NavEntry?>, onClose: () -> Unit, onCorner: (() -> Unit)? = null) {
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
        // Up past the top spoke's last stop (or with nothing up there): on to the profile corner.
        if (dir == 0 && at == pos && (pos == null || pos.first == 0)) onCorner?.invoke()
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val selected = at?.let { (s, r) -> entry(s, r) }
    // A group's spoke: OK opens a short list of its stops in the middle (▲▼ and OK; Back or ◀ folds it).
    var group by remember { mutableStateOf<NavEntry?>(null) }
    var groupAt by remember { mutableStateOf(0) }
    group?.let { g ->
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f))
                .focusRequester(focus)
                .onPreviewKeyEvent { e ->
                    val down = e.type == KeyEventType.KeyDown
                    when (e.key) {
                        Key.DirectionUp -> { if (down) groupAt = (groupAt - 1).coerceAtLeast(0); true }
                        Key.DirectionDown -> { if (down) groupAt = (groupAt + 1).coerceAtMost(g.children.lastIndex); true }
                        Key.Back, Key.DirectionLeft -> { if (!down) { group = null }; true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { if (!down) g.children.getOrNull(groupAt)?.let { onClose(); it.onClick() }; true }
                        else -> true
                    }
                }
                .focusable()
                .semantics { contentDescription = "${g.label}: ${g.children.getOrNull(groupAt)?.label.orEmpty()}" },
            Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(g.label, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                g.children.forEachIndexed { i, c ->
                    Row(
                        Modifier.background(if (i == groupAt) MaterialTheme.colorScheme.primary else Color(0xFF26262B), RoundedCornerShape(50)).padding(horizontal = 22.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(c.icon, contentDescription = null, tint = if (i == groupAt) Color.Black else Color.White, modifier = Modifier.size(22.dp))
                        Text(c.label, style = MaterialTheme.typography.titleMedium, color = if (i == groupAt) Color.Black else Color.White)
                    }
                }
            }
        }
        LaunchedEffect(g.id) { runCatching { focus.requestFocus() } }
        return
    }

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
                            e.type == KeyEventType.KeyUp && !held && selected?.children?.isNotEmpty() == true -> { groupAt = 0; group = selected }
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
internal fun spokeOffset(s: Int) = when (s) { 0 -> 0f to -1f; 1 -> 1f to 0f; 2 -> 0f to 1f; else -> -1f to 0f }

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
