package dev.nitrate.showviews

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.ImageKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.pages.BrowseView
import dev.nitrate.core.pages.CollageLines

/**
 * What a shared show view gets from its app: the titles loaded so far, the server (pictures, and the
 * extra facts Volumes and Collage ask for), how to name, open and play a title, and more on demand.
 * [overviewOf] is a title's story when the user's settings (and spoiler hiding) allow it, else null.
 */
class ShowSource(
    val items: List<MediaItem>,
    val server: MediaServer,
    val titleOf: (MediaItem) -> String,
    val overviewOf: @Composable (MediaItem) -> String?,
    val onOpen: (MediaItem) -> Unit,
    val onPlay: (MediaItem) -> Unit,
    val onNeedMore: () -> Unit,
    /** Whether every title is loaded (Collage only wraps top to bottom then). */
    val complete: Boolean = false,
    /** What Collage's hairlines join. */
    val lines: CollageLines = CollageLines.GENRE,
    /** On a TV (bigger type, D-pad hints); else touch hints. */
    val tv: Boolean = false,
    /** The chosen title, held by the page (its jump bar and view changes move it and keep it); null: the view keeps its own. */
    val chosen: MutableIntState? = null,
)

/** The app's type and accent, so the views read like the rest of it. */
class ShowStyle(
    val display: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
    val accent: Color,
)

/**
 * The app's parts a view may use: Back (the phone's gesture; keys are taken by the view itself),
 * an A–Z beside Vortex and Volumes (null for none; gets where to draw and what to do with a title's
 * place), and where focus goes in (TV).
 */
class ShowHost(
    val backHandler: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit,
    val letters: (@Composable (Modifier, (Int) -> Unit) -> Unit)? = null,
    val focus: FocusRequester? = null,
    /** How many times a held key has repeated (Android's count), so holding a direction speeds up; 0 where unknown. */
    val repeats: (androidx.compose.ui.input.key.KeyEvent) -> Int = { 0 },
)

/** The library All views drawn here: everything [BrowseView.shared]. */
@Composable
fun SharedShowView(view: BrowseView, source: ShowSource, style: ShowStyle, host: ShowHost, modifier: Modifier = Modifier) {
    when (view) {
        BrowseView.VORTEX -> Vortex(source, style, host, modifier)
        BrowseView.VOLUMES -> Volumes(source, style, host, modifier)
        BrowseView.COLLAGE -> CollageView(source, style, host, modifier)
        BrowseView.CORRIDOR -> Corridor(source, style, host, modifier)
        BrowseView.PANES -> Panes(source, style, host, modifier)
        BrowseView.PAVEMENT -> Pavement(source, style, host, modifier)
        else -> Unit
    }
}

/** What a view is told: an arrow, OK, Back (only while it has something open) or Play. */
internal enum class ShowKey { LEFT, RIGHT, UP, DOWN, OK, BACK, PLAY }

/**
 * A view's one focusable: arrows go to [onKey] as they're pressed (false lets one leave the view,
 * Up to the bar), OK and Play as they're let go, Back (and Escape) only while [catchBack] (something
 * is open). Read out as [label]: the chosen title.
 */
internal fun Modifier.showInput(label: String?, catchBack: Boolean, focus: FocusRequester?, repeats: (androidx.compose.ui.input.key.KeyEvent) -> Int = { 0 }, onKey: (ShowKey) -> Boolean): Modifier =
    this.onPreviewKeyEvent { e ->
        val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
        val arrow = when (e.key) {
            Key.DirectionLeft -> ShowKey.LEFT
            Key.DirectionRight -> ShowKey.RIGHT
            Key.DirectionUp -> ShowKey.UP
            Key.DirectionDown -> ShowKey.DOWN
            else -> null
        }
        when {
            // Held: a few titles at a time after a moment.
            arrow != null -> if (e.type == KeyEventType.KeyDown) {
                val moved = onKey(arrow)
                if (moved && repeats(e) > 6) repeat(2) { onKey(arrow) }
                moved
            } else false
            ok -> { if (e.type == KeyEventType.KeyUp) onKey(ShowKey.OK); true }
            (e.key == Key.Back || e.key == Key.Escape) && catchBack -> { if (e.type == KeyEventType.KeyUp) onKey(ShowKey.BACK); true }
            e.key == Key.MediaPlay || e.key == Key.MediaPlayPause -> { if (e.type == KeyEventType.KeyUp) onKey(ShowKey.PLAY); true }
            else -> false
        }
    }
        .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
        .focusable()
        .semantics { label?.let { text = AnnotatedString(it) } }

/** The view's chosen title, kept across visits, and more titles asked for near the end. */
@Composable
internal fun rememberChoice(source: ShowSource): MutableIntState {
    val count = source.items.size
    val own = rememberSaveable { mutableIntStateOf(0) }
    val index = source.chosen ?: own
    if (index.intValue > count - 1) index.intValue = (count - 1).coerceAtLeast(0)
    LaunchedEffect(index.intValue, count) { if (index.intValue >= count - 10) source.onNeedMore() }
    return index
}

/** "2021 · PG-13 · 1h 56m · ★ 7.8". */
internal fun facts(item: MediaItem): String = listOfNotNull(
    item.productionYear?.toString(),
    item.officialRating,
    item.runtimeMs?.let { ms -> val m = (ms / 60_000).toInt(); if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m" },
    item.communityRating?.let { r -> "★ " + ((r * 10).toInt() / 10f).toString() },
).joinToString(" · ")

/** A title's poster (or a picture of [kind]), its name on a plain card until (or unless) it loads. */
@Composable
internal fun Picture(item: MediaItem, server: MediaServer, style: ShowStyle, modifier: Modifier, kind: ImageKind = ImageKind.PRIMARY, heightPx: Int = 600, corner: Int = 8) {
    Box(modifier.clip(RoundedCornerShape(corner.dp)).background(Color(0xFF2A2E3A)), contentAlignment = Alignment.Center) {
        BasicText(item.name, style = style.label.copy(color = Color(0xCCFFFFFF)), maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp))
        val url = if (kind == ImageKind.BACKDROP) server.backdropUrl(item, heightPx * 16 / 9) else server.imageUrl(item, kind, heightPx)
        AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** The buttons of a peeled-out card or the lightbox: Details (first) and Play; [chosen] is lit. */
@Composable
internal fun PeelButtons(chosen: Int, style: ShowStyle, dark: Boolean, onDetails: () -> Unit, onPlay: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("Details" to onDetails, "Play" to onPlay).forEachIndexed { i, (label, act) ->
            val on = i == chosen
            val ink = if (dark) Color.White else Color(0xFF1C1A16)
            Box(
                Modifier.clip(RoundedCornerShape(50))
                    .background(if (on) style.accent else Color.Transparent)
                    .border(1.5.dp, if (on) style.accent else ink.copy(alpha = 0.5f), RoundedCornerShape(50))
                    .clickable(onClick = act)
                    .padding(horizontal = 22.dp, vertical = 10.dp),
            ) { BasicText(label, style = style.label.copy(color = if (on) Color.Black else ink)) }
        }
    }
}

/** A peeled-out title's words: name, facts, story, the buttons, and how to get back. */
@Composable
internal fun PeelDetails(item: MediaItem, source: ShowSource, style: ShowStyle, chosen: Int, dark: Boolean, modifier: Modifier = Modifier, hint: String? = null) {
    val ink = if (dark) Color.White else Color(0xFF1C1A16)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BasicText(source.titleOf(item), style = style.display.copy(color = ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
        BasicText(facts(item), style = style.label.copy(color = ink.copy(alpha = 0.7f)))
        source.overviewOf(item)?.let { BasicText(it, style = style.body.copy(color = ink.copy(alpha = 0.85f)), maxLines = 6, overflow = TextOverflow.Ellipsis) }
        Box(Modifier.padding(top = 6.dp)) {
            PeelButtons(chosen, style, dark, onDetails = { source.onOpen(item) }, onPlay = { source.onPlay(item) })
        }
        BasicText(
            hint ?: if (source.tv) "◀ ▶ choose · OK · Back puts it back" else "Tap outside to put it back",
            style = style.label.copy(color = ink.copy(alpha = 0.5f)),
        )
    }
}

internal fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
