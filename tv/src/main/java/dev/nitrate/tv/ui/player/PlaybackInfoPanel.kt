package dev.nitrate.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.nitrate.core.model.PlaybackPlan
import dev.nitrate.core.playback.infoRows
import dev.nitrate.core.model.StreamKind
import dev.nitrate.core.model.PlayMethod

/**
 * What the player is actually doing, and why: on the left the file, the server's decision and the
 * picture's path (HDR, display, smoothness, subtitles); on the right the sound's path.
 */
@Composable
fun PlaybackInfoPanel(
    plan: PlaybackPlan,
    videoDecoder: String?,
    audioDecoder: String?,
    modifier: Modifier = Modifier,
    audio: dev.nitrate.core.playback.AudioPath? = null,
    video: dev.nitrate.core.playback.VideoPath? = null,
    quality: dev.nitrate.core.playback.QualityDecision? = null,
    trail: dev.nitrate.core.playback.PlaybackTrail? = null,
) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    // Fits the screen whatever the notes: the panel scrolls (▲▼, as it has focus) rather than run off it.
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(max = 492.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), RoundedCornerShape(14.dp))
            .focusRequester(focus)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionDown -> { scope.launch { scroll.animateScrollBy(140f) }; true }
                    Key.DirectionUp -> { scope.launch { scroll.animateScrollBy(-140f) }; true }
                    // Nothing to move to sideways: keep focus here.
                    Key.DirectionLeft, Key.DirectionRight -> true
                    else -> false
                }
            }
            .focusable()
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            Text("Playback info", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text(if (scroll.maxValue > 0) "▲▼ scroll  ·  Back closes" else "Back closes", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Picture", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
                InfoRows(plan.infoRows(videoDecoder, audioDecoder, soundPassedThrough = audio?.delivery == dev.nitrate.core.playback.AudioDelivery.BITSTREAM, quality = quality) + video?.rows().orEmpty())
            }
            val sound = audio?.rows().orEmpty()
            if (sound.isNotEmpty()) Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Sound", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
                InfoRows(sound)
            }
        }
        // Every decision from the file to what left the device, and who made it.
        trail?.let {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Path: from the file to the screen", style = MaterialTheme.typography.titleLarge)
                TrailView(it)
            }
        }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun InfoRows(rows: List<Pair<String, String>>) {
    for ((label, value) in rows) {
        Row {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(120.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), color = if (label == "Note") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
    }
}

