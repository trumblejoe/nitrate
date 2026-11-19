package dev.nitrate.tv.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.nitrate.core.playback.PlaybackTrail
import dev.nitrate.core.playback.Trail

/** Amber for steps that turned out other than predicted. */
private val Differs = Color(0xFFFFB74D)

/**
 * A playback trail: picture and sound side by side, then subtitles and the refresh rate; each step
 * numbered with who made it, why, and in amber what was predicted when it turned out otherwise.
 */
@Composable
fun TrailView(trail: PlaybackTrail, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val differences = trail.differences
        Text(
            if (differences.isEmpty()) "Everything so far went as predicted." else "${differences.size} step${if (differences.size == 1) "" else "s"} not as predicted (in amber).",
            style = MaterialTheme.typography.bodyMedium,
            color = if (differences.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else Differs,
        )
        trail.trails.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                pair.forEach { t -> TrailColumn(t, Modifier.weight(1f)) }
                if (pair.size == 1) Column(Modifier.weight(1f)) {}
            }
        }
    }
}

@Composable
private fun TrailColumn(t: Trail, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(t.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 2.dp))
        t.steps.forEachIndexed { i, s ->
            Row {
                Text("${i + 1}. ${s.step}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(150.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.what, style = MaterialTheme.typography.bodyMedium, color = if (s.differs != null) Differs else MaterialTheme.colorScheme.onSurface)
                    Text(listOfNotNull(s.who.label, s.why).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    s.differs?.let { Text(it.replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.labelMedium, color = Differs) }
                }
            }
        }
    }
}
