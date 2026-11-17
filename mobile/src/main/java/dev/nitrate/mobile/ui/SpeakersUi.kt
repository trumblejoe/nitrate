package dev.nitrate.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nitrate.core.playback.PlaybackSettings
import dev.nitrate.core.playback.SoundOutput
import dev.nitrate.core.playback.SpeakerLayout
import dev.nitrate.core.playback.Speakers

/** The speakers on [output]: one chip per layout, the current one selected. */
@Composable
fun SpeakerChips(output: SoundOutput, current: SpeakerLayout?, onPick: (SpeakerLayout) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SpeakerLayout.entries.forEach { l -> FilterChip(selected = current == l, onClick = { onPick(l) }, label = { Text(l.label) }) }
    }
}

/**
 * "Upmix stereo" with the speakers of the output in use (and any others seen before): what the
 * upmix spreads stereo over (3.1, 5.1 or 7.1), remembered per output on this device.
 */
@Composable
fun SpeakersSettings(settings: PlaybackSettings, output: SoundOutput?, update: ((PlaybackSettings) -> PlaybackSettings) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().clickable { update { it.copy(upmixStereo = !it.upmixStereo) } }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Upmix stereo to surround", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Films and episodes in stereo play on all your speakers: voices in the centre, ambience around you. Only on outputs with more than two speakers; music stays stereo",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = settings.upmixStereo, onCheckedChange = { v -> update { it.copy(upmixStereo = v) } })
        }
        if (output != null) {
            Text("Sound is going to ${output.label}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            if (output.stereoOnly) {
                Text("Stereo only: nothing to upmix to here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val layout = Speakers.layout(output, settings.speakerLayouts)
                Text(layout?.detail ?: "What speakers does it have?", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SpeakerChips(output, layout) { l -> update { it.copy(speakerLayouts = it.speakerLayouts + (output.key to l)) } }
            }
        }
        val others = settings.speakerLayouts.filterKeys { it != output?.key }
        if (others.isNotEmpty()) {
            Text("Other outputs", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            others.forEach { (key, layout) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${key.substringAfter(':').ifBlank { key.substringBefore(':') }}: ${layout.label}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { update { it.copy(speakerLayouts = it.speakerLayouts - key) } }) { Text("Forget") }
                }
            }
        }
    }
}

/** Asked once per new output that takes more than stereo, when upmixing is on. */
@Composable
fun SpeakersQuestion(output: SoundOutput, onAnswer: (SpeakerLayout) -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("What speakers are on ${output.label}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("So stereo can be spread over them. Asked once for this output; change it any time in Settings → Playback.", style = MaterialTheme.typography.bodySmall)
                SpeakerLayout.entries.forEach { l ->
                    Row(Modifier.fillMaxWidth().clickable { onAnswer(l) }.padding(vertical = 8.dp)) {
                        Text(l.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 14.dp))
                        Text(l.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onLater) { Text("Later") } },
    )
}
