package dev.nitrate.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.nitrate.core.playback.PlaybackTrail

/** Amber for steps that turned out other than predicted. */
private val Differs = Color(0xFFFFB74D)

/**
 * A playback trail: each part (picture, sound, subtitles, refresh rate) as numbered steps with who
 * made each one, why, and in amber what was predicted when it turned out otherwise.
 */
@Composable
fun TrailView(trail: PlaybackTrail, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val differences = trail.differences
        Text(
            if (differences.isEmpty()) "Everything so far went as predicted." else "${differences.size} step${if (differences.size == 1) "" else "s"} not as predicted (in amber).",
            style = MaterialTheme.typography.bodySmall,
            color = if (differences.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else Differs,
        )
        trail.trails.forEach { t ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(t.title, style = MaterialTheme.typography.titleSmall)
                t.steps.forEachIndexed { i, s ->
                    Column(Modifier.padding(start = 4.dp)) {
                        Text("${i + 1}. ${s.step}: ${s.what}", style = MaterialTheme.typography.bodyMedium, color = if (s.differs != null) Differs else MaterialTheme.colorScheme.onSurface)
                        Text(listOfNotNull(s.who.label, s.why).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        s.differs?.let { Text(it.replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.labelSmall, color = Differs) }
                    }
                }
            }
        }
    }
}
