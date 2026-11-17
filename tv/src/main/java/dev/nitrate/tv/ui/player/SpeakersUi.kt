package dev.nitrate.tv.ui.player

import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
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
fun SpeakersQuestionDialog(
    output: SoundOutput,
    onAnswer: (SpeakerLayout) -> Unit,
    onLater: () -> Unit,
    title: String = "Stereo here can play on all your speakers",
    laterLabel: String = "Later",
) {
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = onLater) {
        Column(
            Modifier.width(1040.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onLater) { Text(laterLabel) }
            }
            Text("What's connected to ${output.label}? Asked once for this output; change it any time in Settings → Picture & sound.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/**
 * A new output with more than two speakers while stereo plays: a quiet note in a corner that never
 * takes focus from the player and fades by itself; it says where to set the speakers up.
 */
@Composable
fun androidx.compose.foundation.layout.BoxScope.SpeakersBanner(output: SoundOutput, onDone: () -> Unit) {
    LaunchedEffect(output.key) {
        kotlinx.coroutines.delay(12_000)
        onDone()
    }
    Text(
        "New sound output: ${output.label}. Set up its speakers in More (or Settings → Picture & sound) to hear stereo on all of them.",
        style = MaterialTheme.typography.bodyMedium,
        color = androidx.compose.ui.graphics.Color.White,
        modifier = Modifier.align(androidx.compose.ui.Alignment.TopStart).padding(32.dp).width(620.dp)
            .background(androidx.compose.ui.graphics.Color(0xE6101216), RoundedCornerShape(14.dp)).padding(horizontal = 18.dp, vertical = 12.dp),
    )
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
    val output by dev.nitrate.android.playback.SoundOutputs.current(androidx.compose.ui.platform.LocalContext.current).collectAsState()
    var asking by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var decided by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(settled, settings != null) {
        if (decided || !settled) return@LaunchedEffect
        val s = settings ?: return@LaunchedEffect
        kotlinx.coroutines.delay(1_500)
        val o = output
        decided = true
        if (s.upmixStereo && Speakers.shouldAsk(o, s.speakerLayouts, s.speakerAskLater, System.currentTimeMillis())) asking = o?.key
    }
    val o = output?.takeIf { it.key == asking } ?: return
    SpeakersQuestionDialog(
        o,
        title = "Your sound",
        laterLabel = "Skip",
        onAnswer = { l ->
            asking = null
            scope.launch { container.playbackSettings.update { it.copy(speakerLayouts = it.speakerLayouts + (o.key to l)) } }
        },
        onLater = {
            asking = null
            scope.launch { container.playbackSettings.update { it.copy(speakerAskLater = Speakers.askLater(it.speakerAskLater, o, System.currentTimeMillis())) } }
        },
    )
}

/** For screenshots: the banner as it shows over the video. */
internal object SpeakersUiPreview {
    @Composable
    fun androidx.compose.foundation.layout.BoxScope.banner() =
        SpeakersBanner(SoundOutput(dev.nitrate.core.playback.OutputKind.HDMI, "Living room soundbar", 6)) {}
}
