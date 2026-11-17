package dev.nitrate.mobile.ui

import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
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
                Text("Upmix stereo: ${if (settings.upmixStereo) "Automatic" else "Off"}", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Automatic: stereo films and episodes play on all the speakers you've said an output has (voices in the centre, ambience around you); asked once per output. Off: always stereo. Music stays stereo",
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
fun SpeakersQuestion(
    output: SoundOutput,
    onAnswer: (SpeakerLayout) -> Unit,
    onLater: () -> Unit,
    title: String = "Stereo here can play on all your speakers",
    laterLabel: String = "Later",
) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("What's connected to ${output.label}? Asked once for this output; change it any time in Settings → Picture & sound.", style = MaterialTheme.typography.bodySmall)
                SpeakerLayout.entries.forEach { l ->
                    Row(Modifier.fillMaxWidth().clickable { onAnswer(l) }.padding(vertical = 8.dp)) {
                        Text(l.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 14.dp))
                        Text(l.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onLater) { Text(laterLabel) } },
    )
}

/**
 * A new output with more than two speakers while stereo plays: a quiet note in a corner (never a
 * dialog over the video) that fades by itself; "Set up" opens the question.
 */
@Composable
fun androidx.compose.foundation.layout.BoxScope.SpeakersBanner(output: SoundOutput, onSetUp: () -> Unit, onDone: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(output.key) {
        kotlinx.coroutines.delay(10_000)
        onDone()
    }
    Row(
        Modifier.align(Alignment.TopStart).padding(16.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .background(androidx.compose.ui.graphics.Color(0xE6101216)).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("New sound output: ${output.label}", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelLarge)
        TextButton(onClick = { onSetUp(); onDone() }) { Text("Set up speakers") }
    }
}

/**
 * At app start (once the first-run backup question is out of the way): if sound goes to an
 * output with more than two speakers that hasn't been set up, ask what's connected. "Skip" puts it
 * off for a day; it's asked at most once per app run.
 */
@Composable
fun SpeakersSetupPrompt(container: dev.nitrate.android.AppContainer) {
    val settled by container.backups.settled.collectAsState(false)
    val settings by container.playbackSettings.settings.collectAsState(null)
    val output by dev.nitrate.mobile.platform.rememberSoundOutput()
    var asking by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var decided by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(settled, settings != null) {
        if (decided || !settled) return@LaunchedEffect
        val s = settings ?: return@LaunchedEffect
        // Give the sound output a moment to be known after start.
        kotlinx.coroutines.delay(1_500)
        val o = output
        decided = true
        if (s.upmixStereo && Speakers.shouldAsk(o, s.speakerLayouts, s.speakerAskLater, dev.nitrate.android.nowMs())) asking = o?.key
    }
    val o = output?.takeIf { it.key == asking } ?: return
    SpeakersQuestion(
        o,
        title = "Your sound",
        laterLabel = "Skip",
        onAnswer = { l ->
            asking = null
            scope.launch { container.playbackSettings.update { it.copy(speakerLayouts = it.speakerLayouts + (o.key to l)) } }
        },
        onLater = {
            asking = null
            scope.launch { container.playbackSettings.update { it.copy(speakerAskLater = Speakers.askLater(it.speakerAskLater, o, dev.nitrate.android.nowMs())) } }
        },
    )
}
