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

/** What the player is actually doing, and why: method, server reasons, streams and decoders. */
@Composable
fun PlaybackInfoPanel(plan: PlaybackPlan, videoDecoder: String?, audioDecoder: String?, modifier: Modifier = Modifier) {
    val rows = plan.infoRows(videoDecoder, audioDecoder)
    Column(
        modifier.width(560.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), RoundedCornerShape(14.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Playback info", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 6.dp))
        for ((label, value) in rows) {
            Row {
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(130.dp))
                Text(value, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

