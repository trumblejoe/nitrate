package dev.nitrate.tv.ui.player

import androidx.compose.foundation.background
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
) {
    Row(
        modifier.width(1120.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), RoundedCornerShape(14.dp)).padding(20.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Playback info", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 6.dp))
            InfoRows(plan.infoRows(videoDecoder, audioDecoder) + video?.rows().orEmpty())
        }
        val sound = audio?.rows().orEmpty()
        if (sound.isNotEmpty()) Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Sound", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 6.dp))
            InfoRows(sound)
        }
    }
}

@Composable
private fun InfoRows(rows: List<Pair<String, String>>) {
    for ((label, value) in rows) {
        Row {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(120.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium, color = if (label == "Note") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
    }
}

