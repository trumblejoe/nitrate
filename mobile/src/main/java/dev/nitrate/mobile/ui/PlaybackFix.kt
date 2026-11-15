package dev.nitrate.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.MediaKind
import dev.nitrate.core.playback.PlaybackDiagnosis
import dev.nitrate.core.playback.PlaybackFix
import dev.nitrate.core.playback.PlaybackQuality
import kotlinx.coroutines.launch

/**
 * "Why is this converting?": what the server does with the item, why, in plain words, and fixes.
 * [canApply] leaves out fixes that only work in the player (choosing a track) on a details page.
 */
@Composable
fun PlaybackFixDialog(
    diagnosis: PlaybackDiagnosis?,
    error: String?,
    onFix: (PlaybackFix) -> Unit,
    onDismiss: () -> Unit,
    canApply: (PlaybackFix) -> Boolean = { true },
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(diagnosis?.quality?.label ?: "How this plays") },
        text = {
            when {
                error != null -> Text("Couldn't ask the server: $error", color = MaterialTheme.colorScheme.error)
                diagnosis == null -> Text("Asking the server…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { Text(diagnosis.summary, style = MaterialTheme.typography.bodyLarge) }
                    if (diagnosis.findings.isEmpty() && diagnosis.quality != PlaybackQuality.ORIGINAL) {
                        item { Text("Nothing on this device can change that.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    diagnosis.findings.forEach { f ->
                        item {
                            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(f.title, style = MaterialTheme.typography.titleSmall, color = accentColor())
                                Text(f.text, style = MaterialTheme.typography.bodyMedium)
                                f.adminTip?.let {
                                    Text("For your server's admin: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        f.fixes.forEach { fix ->
                            item {
                                val applies = canApply(fix)
                                Column(Modifier.padding(top = 4.dp)) {
                                    OutlinedButton(onClick = { onFix(fix) }, enabled = applies) { Text(fix.label) }
                                    (if (applies) fix.detail else "Choose this in the player.")?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** The fixer for an item on its details page: asks the server, and again after each fix. */
@Composable
fun CheckPlaybackDialog(actions: AppActions, item: MediaItem, onDismiss: () -> Unit) {
    var round by remember { mutableIntStateOf(0) }
    val result by produceState<Result<PlaybackDiagnosis>?>(null, item.id, round) {
        value = null
        value = runCatching { actions.container.checkPlayback(item.id, item.mediaKind == MediaKind.AUDIO) }
    }
    val scope = rememberCoroutineScope()
    PlaybackFixDialog(
        diagnosis = result?.getOrNull(),
        error = result?.exceptionOrNull()?.let { it.message ?: it::class.simpleName },
        onFix = { fix -> scope.launch { actions.container.applyPlaybackFix(item.id, fix); round++ } },
        onDismiss = onDismiss,
        canApply = { it is PlaybackFix.Change || it is PlaybackFix.UseVersion },
    )
}
