package dev.nitrate.tv.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
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
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
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
    }
}

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
