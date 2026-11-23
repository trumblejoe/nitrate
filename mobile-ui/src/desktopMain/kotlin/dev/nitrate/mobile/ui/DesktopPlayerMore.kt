package dev.nitrate.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nitrate.core.playback.DesktopPlayback

/**
 * The desktop player's More: speed, sleep timer, a second subtitle at the top, sound and subtitle
 * timing for this playback, and this PC's player choices (bitstreaming to a receiver, how the
 * picture is shown, hardware decoding; they apply from the next video), plus Media info.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DesktopPlayerMore(
    session: DesktopPlayerSession,
    settings: DesktopPlayerSettings,
    plan: dev.nitrate.core.model.PlaybackPlan,
    sleepAt: Long?,
    sleepAtEnd: Boolean,
    onSleep: (at: Long?, atEnd: Boolean) -> Unit,
    groupChat: (@Composable () -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val speed by session.speed.collectAsState()
    val second by session.secondSubtitle.collectAsState()
    val audioDelay by session.audioDelayMs.collectAsState()
    val subDelay by session.subtitleDelayMs.collectAsState()
    val item by session.item.collectAsState()
    val values by settings.values.collectAsState()
    var info by remember { mutableStateOf(false) }
    if (info) {
        val text = remember(plan, item) { dev.nitrate.core.playback.MediaInfo.render(item, plan.source, plan, local = plan.url.let { !it.startsWith("http") }) }
        MediaInfoDialog(text) { info = false }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("More") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                groupChat?.invoke()
                Text("Speed", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
                        FilterChip(selected = speed == s, onClick = { session.setSpeed(s) }, label = { Text(if (s == 1f) "Normal" else "${s}×") })
                    }
                }
                Text(
                    "Sleep timer" + (sleepAt?.let { " · stops at ${dev.nitrate.mobile.platform.formatTime(it)}" } ?: if (sleepAtEnd) " · end of this" else ""),
                    style = MaterialTheme.typography.titleSmall,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = sleepAt == null && !sleepAtEnd, onClick = { onSleep(null, false) }, label = { Text("Off") })
                    listOf(15, 30, 45, 60, 90).forEach { m ->
                        FilterChip(selected = false, onClick = { onSleep(dev.nitrate.android.nowMs() + m * 60_000L, false) }, label = { Text("$m min") })
                    }
                    FilterChip(selected = sleepAtEnd, onClick = { onSleep(null, true) }, label = { Text("End of this") })
                }
                val choices = dev.nitrate.core.playback.SecondSubtitles.choices(plan.source, plan.subtitleIndex)
                if (choices.isNotEmpty()) {
                    Text("Second subtitles", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = second == null, onClick = { session.setSecondSubtitle(null) }, label = { Text("Off") })
                        choices.forEach { c ->
                            FilterChip(selected = second?.index == c.index, onClick = { session.setSecondSubtitle(c.index) }, label = { Text(c.title + (c.detail?.let { " · $it" } ?: "")) })
                        }
                    }
                }
                Timing("Audio sync", audioDelay, "Sound later if it comes before the picture (a TV or receiver that adds lag).", session::setAudioDelay)
                Timing("Subtitle timing", subDelay, "Subtitles later if they show before the line is said.", session::setSubtitleDelay)

                Text("This PC", style = MaterialTheme.typography.titleSmall)
                Text("For the next video. Kept on this PC only.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Send to a receiver untouched (bitstream)", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Off unless your PC's sound goes to an AV receiver over HDMI or S/PDIF that decodes these. A soundbar or TV speakers may stay silent.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DesktopPlayback.BITSTREAM_CODECS.forEach { codec ->
                        val on = codec in values.bitstream
                        FilterChip(selected = on, onClick = { settings.update { v -> v.copy(bitstream = if (on) v.bitstream - codec else v.bitstream + codec) } }, label = { Text(BITSTREAM_NAMES.getValue(codec)) })
                    }
                }
                Text("Picture", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VideoOutput.entries.forEach { o ->
                        FilterChip(selected = values.output == o, onClick = { settings.update { it.copy(output = o) } }, label = { Text(o.label) })
                    }
                }
                Text(values.output.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = values.hardwareDecoding, onClick = { settings.update { it.copy(hardwareDecoding = !it.hardwareDecoding) } }, label = { Text("Hardware decoding") })
                }
                TextButton(onClick = { info = true }) { Text("Media info") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** A timing in 25/100 ms steps, with where it is now and back to zero. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Timing(title: String, ms: Int, hint: String, onChange: (Int) -> Unit) {
    Text("$title: " + if (ms == 0) "in sync" else "${if (ms > 0) "+" else "−"}${kotlin.math.abs(ms)} ms", style = MaterialTheme.typography.titleSmall)
    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(-100, -25, 25, 100).forEach { step ->
            FilterChip(selected = false, onClick = { onChange(ms + step) }, label = { Text("${if (step > 0) "+" else "−"}${kotlin.math.abs(step)} ms") })
        }
        if (ms != 0) FilterChip(selected = false, onClick = { onChange(0) }, label = { Text("Reset") })
    }
}

private val BITSTREAM_NAMES = mapOf("ac3" to "Dolby Digital", "eac3" to "Dolby Digital Plus", "dts" to "DTS / DTS-HD", "truehd" to "Dolby TrueHD")
