package dev.nitrate.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.FilterChip
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import dev.nitrate.core.playback.PlaybackSettings
import dev.nitrate.core.playback.SoundOutput
import dev.nitrate.core.playback.SpeakerLayout
import dev.nitrate.core.playback.Speakers

/** One chip per speaker layout for [output], the current one selected. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeakerChips(current: SpeakerLayout?, modifier: Modifier = Modifier, firstFocus: FocusRequester? = null, onPick: (SpeakerLayout) -> Unit) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SpeakerLayout.entries.forEachIndexed { i, l ->
            FilterChip(selected = current == l, onClick = { onPick(l) }, modifier = if (i == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier) { Text(l.label) }
        }
    }
}

/**
 * "Upmix stereo" and the speakers on the output in use (and others seen before), for TV settings
 * and the player's More.
 */
@Composable
fun SpeakersSettings(settings: PlaybackSettings, output: SoundOutput?, update: ((PlaybackSettings) -> PlaybackSettings) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ListItem(
            selected = false,
            onClick = { update { it.copy(upmixStereo = !it.upmixStereo) } },
            headlineContent = { Text("Upmix stereo: ${if (settings.upmixStereo) "Automatic" else "Off"}") },
            supportingContent = { Text("Automatic: stereo films and episodes play on all the speakers you've said an output has (voices in the centre, ambience around you); asked once per output. Off: always stereo. Music stays stereo") },
            trailingContent = { Switch(checked = settings.upmixStereo, onCheckedChange = null) },
        )
        if (output != null) {
            Text("Sound is going to ${output.label}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
            if (output.stereoOnly) {
                Text("Stereo only: nothing to upmix to here.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp))
            } else {
                val layout = Speakers.layout(output, settings.speakerLayouts)
                Text(layout?.detail ?: "What speakers does it have?", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp))
                SpeakerChips(layout, Modifier.padding(start = 16.dp)) { l -> update { it.copy(speakerLayouts = it.speakerLayouts + (output.key to l)) } }
            }
        }
        settings.speakerLayouts.filterKeys { it != output?.key }.forEach { (key, layout) ->
            ListItem(
                selected = false,
                onClick = { update { it.copy(speakerLayouts = it.speakerLayouts - key) } },
                headlineContent = { Text("${key.substringAfter(':').ifBlank { key.substringBefore(':') }}: ${layout.label}") },
                supportingContent = { Text("Another output seen before. OK forgets it (asked again next time)") },
            )
        }
    }
}

/** Asked once per new output that takes more than stereo, while upmixing is on. */
@Composable
fun SpeakersQuestionDialog(output: SoundOutput, onAnswer: (SpeakerLayout) -> Unit, onLater: () -> Unit) {
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = onLater) {
        Column(
            Modifier.width(1040.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Stereo here can play on all your speakers", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onLater) { Text("Later") }
            }
            Text("What's connected to ${output.label}? Asked once for this output; change it any time in Settings → Playback.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Two columns, so every answer fits on screen.
            SpeakerLayout.entries.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { l ->
                        ListItem(
                            selected = false,
                            onClick = { onAnswer(l) },
                            headlineContent = { Text(l.label) },
                            supportingContent = { Text(l.detail, maxLines = 2) },
                            modifier = Modifier.weight(1f).then(if (l == SpeakerLayout.FIVE_ONE) Modifier.focusRequester(focus) else Modifier),
                        )
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
