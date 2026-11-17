package dev.nitrate.tv.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.PlayMethod
import dev.nitrate.core.model.PlaybackPlan

internal const val SEEK_BACK_MS = 10_000L
internal const val SEEK_FORWARD_MS = 30_000L

/**
 * Where focus is in the player's controls: each button has a requester by its label, and the last
 * one focused is remembered, so closing a pop-up puts focus back on the button that opened it.
 */
@Stable
internal class ControlFocus {
    private val requesters = mutableMapOf<String, FocusRequester>()
    var last: String? = null

    fun of(key: String): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    fun playPause() {
        runCatching { of(PLAY_PAUSE).requestFocus() }
    }

    /** Back to the button focused last (it may have gone, e.g. "Why converting?"): else play/pause. */
    fun restore() {
        val key = last ?: return playPause()
        runCatching { of(key).requestFocus() }.onFailure { playPause() }
    }

    companion object {
        const val PLAY_PAUSE = "play-pause"
    }
}

/**
 * The bar at the bottom of the player: title, seek bar and one row of buttons. Every button looks
 * the same until focused, and the focused one is unmistakable (white, larger, outlined), so it's
 * always clear which one OK will press, play/pause included.
 */
@Composable
internal fun PlayerControls(
    item: MediaItem?,
    plan: PlaybackPlan?,
    position: Long,
    duration: Long,
    playing: Boolean,
    focus: ControlFocus,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    buttons: List<PlayerAction>,
    /** The title's chapters as a strip of pictures under the buttons (none: no strip). */
    chapters: List<dev.nitrate.core.model.Chapter> = emptyList(),
    chapterImage: (Int) -> String? = { null },
    onChapter: (Long) -> Unit = {},
    /** The cast as a strip of photos under the chapters; OK on someone calls [onPerson]. */
    cast: List<dev.nitrate.core.model.Person> = emptyList(),
    personImage: (dev.nitrate.core.model.Person) -> String? = { null },
    onPerson: (dev.nitrate.core.model.Person) -> Unit = {},
    /** More strips of titles after the cast (extras, the season's episodes...), in this order. */
    titleStrips: List<TitleStrip> = emptyList(),
    /** Told when a strip under the buttons opens (Down onto it) and when it folds again. */
    onStripOpen: (Boolean) -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
            // Taller than the screen once a strip opens with more below it: focus scrolls the
            // strip into view (the title and seek bar go up and come back on the way up).
            .verticalScroll(rememberScrollState())
            .padding(start = 48.dp, end = 48.dp, top = 80.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item?.let {
            Text(it.displayTitle, style = MaterialTheme.typography.headlineSmall, color = Color.White)
            it.episodeLabel?.let { label -> Text(label, style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.8f)) }
        }
        SeekBar(position, duration, onSeek)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            PlayPauseButton(playing, onPlayPause, Modifier.remembered(focus, ControlFocus.PLAY_PAUSE))
            for (b in buttons) {
                PlayerButton(b.label, b.onClick, Modifier.remembered(focus, b.label), enabled = b.enabled)
            }
            plan?.takeIf { it.method != PlayMethod.DIRECT_PLAY }?.let {
                Text(
                    if (it.method == PlayMethod.TRANSCODE) "Transcoding" else "Direct stream",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
        // Only one strip is open at a time: opening one folds whichever was open.
        var openStrip by remember { mutableStateOf<String?>(null) }
        androidx.compose.runtime.LaunchedEffect(openStrip) { onStripOpen(openStrip != null) }
        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { onStripOpen(false) } }
        val headings = remember { mutableMapOf<String, FocusRequester>() }
        fun heading(label: String) = headings.getOrPut(label) { FocusRequester() }
        val hasChapters = chapters.size > 1
        val labels = buildList {
            if (hasChapters) add("Chapters")
            if (cast.isNotEmpty()) add("Cast")
            titleStrips.filter { it.cards.isNotEmpty() }.forEach { add(it.label) }
        }
        @Composable
        fun Strip(label: String, count: Int, start: Int, item: @Composable (Int, Modifier) -> Unit) {
            val above = labels.getOrNull(labels.indexOf(label) - 1)
            FoldingStrip(
                label, count, start, heading = heading(label), open = openStrip == label,
                onOpen = { open -> if (open) openStrip = label else if (openStrip == label) openStrip = null },
                // Up goes to the strip above it, or back to the button Down was pressed on.
                onUp = { if (above == null) focus.restore() else runCatching { heading(above).requestFocus() } },
                item = item,
            )
        }
        if (hasChapters) {
            val now = dev.nitrate.core.playback.Chapters.current(chapters, position).coerceAtLeast(0)
            // Only the word while folded: chapter names and counts can give things away until asked for.
            Strip("Chapters", chapters.size, now) { i, modifier ->
                ChapterCard(
                    index = i,
                    chapter = chapters[i],
                    title = dev.nitrate.core.playback.Chapters.title(chapters, i),
                    imageUrl = chapterImage(i),
                    playing = i == now,
                    onClick = { onChapter(chapters[i].startMs) },
                    modifier = modifier,
                )
            }
        }
        if (cast.isNotEmpty()) {
            Strip("Cast", cast.size, 0) { i, modifier -> CastCard(cast[i], personImage(cast[i]), onClick = { onPerson(cast[i]) }, modifier = modifier) }
        }
        for (strip in titleStrips) {
            if (strip.cards.isEmpty()) continue
            Strip(strip.label, strip.cards.size, strip.start) { i, modifier ->
                val card = strip.cards[i]
                TitleCardView(card, onClick = { strip.onClick(card.item) }, modifier = modifier)
            }
        }
    }
}

/** A strip of titles under the buttons: extras, the season's episodes, the rest of a collection. */
internal class TitleStrip(val label: String, val cards: List<TitleCard>, val start: Int = 0, val onClick: (MediaItem) -> Unit)

/** One title in a [TitleStrip]: [now] marks the one playing. */
internal class TitleCard(val item: MediaItem, val title: String, val subtitle: String?, val imageUrl: String?, val now: Boolean = false)

/**
 * A title in a strip: its picture (blurred when it would spoil, as on every page; a placeholder with
 * its name when there's none), progress and watched mark as the user shows them, name and a line.
 */
@Composable
private fun TitleCardView(card: TitleCard, onClick: () -> Unit, modifier: Modifier) {
    val prefs = dev.nitrate.tv.ui.components.LocalDisplayPrefs.current
    val user = card.item.user
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(CHAPTER_WIDTH), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        androidx.tv.material3.Surface(
            onClick = onClick,
            modifier = modifier.width(CHAPTER_WIDTH).height(CHAPTER_WIDTH * 9 / 16).onFocusChanged { focused = it.isFocused },
            shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
            border = androidx.tv.material3.ClickableSurfaceDefaults.border(
                border = if (card.now) Border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary)) else Border.None,
                focusedBorder = Border(BorderStroke(3.dp, Color.White), inset = (-3).dp),
            ),
            colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(containerColor = Color(0xFF1E232A), focusedContainerColor = Color(0xFF1E232A)),
        ) {
            Box(Modifier.matchParentSize()) {
                Box(
                    Modifier.matchParentSize().background(Brush.linearGradient(listOf(Color(0xFF2C343E), Color(0xFF161A1F)))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(card.title, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.45f), maxLines = 2, modifier = Modifier.padding(8.dp))
                }
                if (card.imageUrl != null) {
                    val blur = dev.nitrate.tv.ui.partners.spoilerStill(card.item)
                    coil3.compose.AsyncImage(
                        card.imageUrl, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.matchParentSize().then(if (blur) Modifier.blur(20.dp) else Modifier),
                    )
                }
                if (card.now) {
                    Text(
                        "Now", style = MaterialTheme.typography.labelSmall, color = Color.Black,
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                } else if (user.played && prefs.watchedMarks) {
                    Text(
                        "✓", style = MaterialTheme.typography.labelMedium, color = Color.Black,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color.White.copy(alpha = 0.9f), CircleShape).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
                val runtime = card.item.runtimeMs ?: 0
                if (!card.now && prefs.progressBars && !user.played && user.positionMs > 0 && runtime > 0) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.5f))) {
                        Box(Modifier.fillMaxWidth((user.positionMs.toFloat() / runtime).coerceIn(0f, 1f)).height(4.dp).background(MaterialTheme.colorScheme.primary))
                    }
                }
            }
        }
        Text(card.title, style = MaterialTheme.typography.labelLarge, color = if (focused) Color.White else Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        card.subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
    }
}

/**
 * A row under the buttons, folded to its name ("▾ Chapters") so the picture keeps the screen.
 * Focus reaching the name (Down) opens it on item [start]; Up leaves through [onUp] (the button
 * Down was pressed on, or the strip above), and focus leaving any other way folds it again.
 */
@Composable
private fun FoldingStrip(
    label: String,
    count: Int,
    start: Int,
    heading: FocusRequester,
    open: Boolean,
    onOpen: (Boolean) -> Unit,
    onUp: () -> Unit,
    item: @Composable (index: Int, modifier: Modifier) -> Unit,
) {
    // Set once focus is inside the open strip, so it folds only when focus really leaves.
    var entered by remember(open) { mutableStateOf(false) }
    fun setOpen(value: Boolean) { if (open != value) onOpen(value) }
    if (!open) {
        var focused by remember { mutableStateOf(false) }
        Text(
            "▾  $label", style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = if (focused) 1f else 0.75f),
            modifier = Modifier
                .focusRequester(heading)
                .onFocusChanged { focused = it.isFocused; if (it.isFocused) setOpen(true) }
                .focusable()
                .padding(vertical = 2.dp),
        )
        return
    }
    val startFocus = remember { FocusRequester() }
    val list = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = start)
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { startFocus.requestFocus() } }
    Column(
        Modifier
            .onFocusChanged {
                if (it.hasFocus) entered = true
                else if (entered) setOpen(false)
            }
            .onPreviewKeyEvent { e ->
                if (e.key == Key.DirectionUp && e.type == KeyEventType.KeyDown) { onUp(); true } else false
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("▴  $label", style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.8f))
        androidx.compose.foundation.lazy.LazyRow(
            state = list,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp),
            modifier = Modifier.focusRestorer(startFocus),
        ) {
            items(count) { i -> item(i, if (i == start) Modifier.focusRequester(startFocus) else Modifier) }
        }
    }
}

/** Someone in the cast: photo (or initials), name and the part they play. */
@Composable
private fun CastCard(person: dev.nitrate.core.model.Person, imageUrl: String?, onClick: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(CAST_WIDTH), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        androidx.tv.material3.Surface(
            onClick = onClick,
            modifier = modifier.width(CAST_WIDTH).height(CAST_WIDTH * 4 / 3).onFocusChanged { focused = it.isFocused },
            shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
            border = androidx.tv.material3.ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White), inset = (-3).dp)),
            colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(containerColor = Color(0xFF1E232A), focusedContainerColor = Color(0xFF1E232A)),
        ) {
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                Text(initials(person.name), style = MaterialTheme.typography.headlineSmall, color = Color.White.copy(alpha = 0.45f))
                if (imageUrl != null) {
                    coil3.compose.AsyncImage(imageUrl, person.name, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.matchParentSize())
                }
            }
        }
        Text(person.name, style = MaterialTheme.typography.labelLarge, color = if (focused) Color.White else Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        person.role?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

internal fun initials(name: String) = name.split(" ").mapNotNull { it.firstOrNull()?.uppercaseChar() }.take(2).joinToString("")

private val CAST_WIDTH = 104.dp

@Composable
private fun ChapterCard(index: Int, chapter: dev.nitrate.core.model.Chapter, title: String, imageUrl: String?, playing: Boolean, onClick: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(CHAPTER_WIDTH), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        androidx.tv.material3.Surface(
            onClick = onClick,
            modifier = modifier.width(CHAPTER_WIDTH).height(CHAPTER_WIDTH * 9 / 16).onFocusChanged { focused = it.isFocused },
            shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
            border = androidx.tv.material3.ClickableSurfaceDefaults.border(
                border = if (playing) Border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary)) else Border.None,
                focusedBorder = Border(BorderStroke(3.dp, Color.White), inset = (-3).dp),
            ),
            colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(containerColor = Color(0xFF1E232A), focusedContainerColor = Color(0xFF1E232A)),
        ) {
            Box(Modifier.matchParentSize()) {
                // Under the picture, so it shows when there's none or it doesn't load.
                ChapterPlaceholder(index)
                if (imageUrl != null) {
                    coil3.compose.AsyncImage(imageUrl, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.matchParentSize())
                }
                if (playing) {
                    Text(
                        "Now", style = MaterialTheme.typography.labelSmall, color = Color.Black,
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Text(title, style = MaterialTheme.typography.labelLarge, color = if (focused) Color.White else Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Text(dev.nitrate.core.model.formatClock(chapter.startMs), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
    }
}

/** A chapter with no picture: its number on a quiet gradient. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.ChapterPlaceholder(index: Int) {
    Box(
        Modifier.matchParentSize().background(Brush.linearGradient(listOf(Color(0xFF2C343E), Color(0xFF161A1F)))),
        contentAlignment = Alignment.Center,
    ) {
        Text("${index + 1}", style = MaterialTheme.typography.headlineMedium, color = Color.White.copy(alpha = 0.45f))
    }
}

private val CHAPTER_WIDTH = 176.dp

/** How far up overlays sit above the controls: higher when the chapter strip is shown. */
internal fun controlsHeight(foldedStrips: Int, stripOpen: Boolean) = 220.dp + 34.dp * foldedStrips + if (stripOpen) 190.dp else 0.dp

/** One button in the player's row. */
internal class PlayerAction(val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

private fun Modifier.remembered(focus: ControlFocus, key: String) =
    focusRequester(focus.of(key)).onFocusChanged { if (it.isFocused) focus.last = key }

private val focusBorder = Border(BorderStroke(3.dp, Color.White.copy(alpha = 0.9f)), inset = (-4).dp)

@Composable
private fun playerButtonColors() = ButtonDefaults.colors(
    containerColor = Color.White.copy(alpha = 0.14f),
    contentColor = Color.White,
    focusedContainerColor = Color.White,
    focusedContentColor = Color.Black,
    pressedContainerColor = Color.White.copy(alpha = 0.8f),
    pressedContentColor = Color.Black,
    disabledContainerColor = Color.White.copy(alpha = 0.06f),
    disabledContentColor = Color.White.copy(alpha = 0.35f),
)

/** A player button: quiet until focused, then white with an outline and a little larger. */
@Composable
internal fun PlayerButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = playerButtonColors(),
        scale = ButtonDefaults.scale(focusedScale = 1.12f),
        border = ButtonDefaults.border(focusedBorder = focusBorder),
    ) { Text(label) }
}

/** Play/pause as a round button with its symbol, so it reads as the main control. */
@Composable
private fun PlayPauseButton(playing: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = if (playing) "Pause" else "Play" },
        shape = ButtonDefaults.shape(CircleShape),
        contentPadding = PaddingValues(12.dp),
        colors = playerButtonColors(),
        scale = ButtonDefaults.scale(focusedScale = 1.15f),
        border = ButtonDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White.copy(alpha = 0.9f)), inset = (-4).dp, shape = CircleShape)),
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            if (playing) PauseSymbol() else Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(28.dp))
        }
    }
}

/** Two bars, in the button's content colour (the core icon set has no pause). */
@Composable
private fun PauseSymbol() {
    val color = androidx.tv.material3.LocalContentColor.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(2) { Box(Modifier.width(7.dp).height(22.dp).background(color, RoundedCornerShape(2.dp))) }
    }
}

/**
 * Focusable progress bar: Left/Right seek while it has focus. Focused, it thickens, gets a knob at
 * the current spot, an outline and a hint of what Left and Right do.
 */
@Composable
private fun SeekBar(position: Long, duration: Long, onSeek: (Long) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var widthPx by remember { mutableStateOf(0) }
    val accent = MaterialTheme.colorScheme.primary
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val barHeight = if (focused) 12.dp else 6.dp
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .onSizeChanged { widthPx = it.width }
                .onFocusChanged { focused = it.isFocused }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> { onSeek(-SEEK_BACK_MS); true }
                        Key.DirectionRight -> { onSeek(SEEK_FORWARD_MS); true }
                        else -> false
                    }
                }
                .focusable(),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(barHeight)
                    .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(50))
                    .then(if (focused) Modifier.border(2.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(50)) else Modifier),
            ) {
                Box(Modifier.fillMaxWidth(fraction).height(barHeight).background(if (focused) Color.White else accent, RoundedCornerShape(50)))
            }
            if (focused) {
                val knob = 24.dp
                val x = with(LocalDensity.current) { (widthPx * fraction).toDp() } - knob / 2
                Box(
                    Modifier.offset(x = x.coerceAtLeast(0.dp)).size(knob)
                        .background(Color.White, CircleShape)
                        .border(3.dp, accent, CircleShape),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(position), style = MaterialTheme.typography.labelMedium, color = Color.White)
            if (focused) {
                Text("◀  ${SEEK_BACK_MS / 1000} s back   ·   ${SEEK_FORWARD_MS / 1000} s on  ▶", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f))
            }
            Text("-" + formatTime((duration - position).coerceAtLeast(0)), style = MaterialTheme.typography.labelMedium, color = Color.White)
        }
    }
}

internal fun formatTime(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
