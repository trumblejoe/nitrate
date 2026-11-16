package dev.nitrate.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.StreamKind
import dev.nitrate.core.playback.TrackSetup
import dev.nitrate.core.playback.audioChoices
import dev.nitrate.core.playback.subtitleChoices
import kotlinx.coroutines.launch

/**
 * "Audio: Japanese · 5.1" and "Subtitles: English": what [item] will start with (the pick for its
 * show or itself, else the server's picks), each opening the player's track list. A pick is
 * remembered like one made while playing, so Play and the next episode follow it.
 */
@Composable
fun TrackLine(actions: AppActions, item: MediaItem, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var setup by remember(item.id) { mutableStateOf<TrackSetup?>(null) }
    LaunchedEffect(item.id) { setup = runCatching { actions.container.trackSetup(item) }.getOrNull() }
    val s = setup ?: return
    if (!s.hasChoices) return
    var picking by remember { mutableStateOf<StreamKind?>(null) }
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TrackPill("Audio", s.audioName) { picking = StreamKind.AUDIO }
        TrackPill("Subtitles", s.subtitleName) { picking = StreamKind.SUBTITLE }
    }
    picking?.let { kind ->
        val audio = kind == StreamKind.AUDIO
        TrackPicker(
            if (audio) "Audio" else "Subtitles",
            if (audio) s.source.audioChoices() else s.source.subtitleChoices(),
            if (audio) s.audioIndex else s.subtitleIndex,
            onSelect = { index ->
                picking = null
                scope.launch { setup = actions.container.pickTrack(item, s, kind, index) }
            },
        ) { picking = null }
    }
}

@Composable
internal fun TrackPill(label: String, value: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.10f)).pressable(onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = accentColor())
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Color.White)
    }
}
