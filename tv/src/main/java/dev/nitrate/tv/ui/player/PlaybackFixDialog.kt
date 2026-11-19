package dev.nitrate.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.nitrate.core.playback.PlaybackDiagnosis
import dev.nitrate.core.playback.PlaybackFix
import dev.nitrate.core.playback.PlaybackQuality

/**
 * "Why is this converting?": what the server does with the item, why, in plain words, and the
 * fixes that can be applied from here. [canApply] leaves out fixes that only work in the player
 * (choosing a track) when shown from a details page; they're still listed as advice.
 */
@Composable
fun PlaybackFixDialog(
    diagnosis: PlaybackDiagnosis?,
    error: String?,
    onFix: (PlaybackFix) -> Unit,
    onDismiss: () -> Unit,
    canApply: (PlaybackFix) -> Boolean = { true },
) {
    val focus = remember { FocusRequester() }
    // Focus starts on the first fix that can be applied here, else on Close.
    val firstFix = diagnosis?.findings?.flatMap { it.fixes }?.firstOrNull(canApply)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(680.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(diagnosis?.quality?.label ?: "How this plays", style = MaterialTheme.typography.headlineSmall)
            when {
                error != null -> Text("Couldn't ask the server: $error", color = MaterialTheme.colorScheme.error)
                diagnosis == null -> Text("Asking the server…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    Text(diagnosis.summary, style = MaterialTheme.typography.bodyLarge)
                    if (diagnosis.findings.isEmpty() && diagnosis.quality != PlaybackQuality.ORIGINAL) {
                        Text("Nothing on this device can change that.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // On this device: decoder, HDR, sound, refresh rate, subtitles (the server's part is above).
                        diagnosis.prediction?.rows()?.filter { it.first != "Server" }?.takeIf { it.isNotEmpty() }?.let { rows ->
                            item {
                                Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("On this device", style = MaterialTheme.typography.titleMedium)
                                    rows.forEach { (label, text) ->
                                        androidx.compose.foundation.layout.Row {
                                            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(130.dp))
                                            Text(text, style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }
                            }
                        }
                        diagnosis.findings.forEach { f ->
                            item {
                                Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(f.title, style = MaterialTheme.typography.titleMedium)
                                    Text(f.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    f.adminTip?.let {
                                        Text("For your server's admin: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            f.fixes.forEach { fix ->
                                item {
                                    val applies = canApply(fix)
                                    val first = fix === firstFix
                                    ListItem(
                                        selected = false,
                                        enabled = applies,
                                        onClick = { onFix(fix) },
                                        headlineContent = { Text(fix.label) },
                                        supportingContent = (if (applies) fix.detail else "Choose this in the player.")?.let { { Text(it) } },
                                        modifier = if (first) Modifier.focusRequester(focus) else Modifier,
                                    )
                                }
                            }
                        }
                        item {
                            ListItem(
                                selected = false,
                                onClick = onDismiss,
                                headlineContent = { Text("Close") },
                                modifier = if (firstFix == null) Modifier.focusRequester(focus) else Modifier,
                            )
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(diagnosis, error) {
        if (diagnosis != null || error != null) {
            kotlinx.coroutines.delay(50)
            runCatching { focus.requestFocus() }
        }
    }
}
