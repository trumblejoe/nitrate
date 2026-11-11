package dev.nitrate.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.nitrate.android.update.AppUpdater
import dev.nitrate.android.update.UpdateState
import dev.nitrate.core.update.AppRelease

/** Offers a newer Nitrate from the releases, then shows the download until Android's installer takes over. */
@Composable
fun UpdatePrompt(updater: AppUpdater) {
    val state by updater.state.collectAsState()
    UpdateDialog(state, onUpdate = updater::install, onLater = updater::skip, onDismiss = updater::dismiss, onReoffer = updater::offer)
}

@Composable
internal fun UpdateDialog(state: UpdateState, onUpdate: (AppRelease) -> Unit, onLater: (AppRelease) -> Unit, onDismiss: () -> Unit, onReoffer: (AppRelease) -> Unit = {}) {
    val context = LocalContext.current
    when (state) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = { onLater(state.release) },
            title = { Text("Update available") },
            text = { ReleaseNotes(state.release) },
            confirmButton = { TextButton(onClick = { onUpdate(state.release) }) { Text("Update") } },
            dismissButton = { TextButton(onClick = { onLater(state.release) }) { Text("Later") } },
        )
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Downloading ${state.release.title}") },
            text = { LinearProgressIndicator(progress = { state.progress }, color = accentColor(), modifier = Modifier.fillMaxWidth()) },
            confirmButton = {},
        )
        is UpdateState.NeedsPermission -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Allow Nitrate to install updates") },
            text = { Text("Android asks once: turn on \"Allow from this source\" for Nitrate, then come back and tap Update.") },
            confirmButton = { TextButton(onClick = { context.startActivity(state.settings); onReoffer(state.release) }) { Text("Open settings") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
        )
        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Update") },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        )
        else -> Unit
    }
}

@Composable
internal fun ReleaseNotes(release: AppRelease) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(release.title, style = MaterialTheme.typography.titleSmall)
        if (release.notes.isNotEmpty()) {
            Text("What's new", style = MaterialTheme.typography.labelLarge, color = accentColor())
            release.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
