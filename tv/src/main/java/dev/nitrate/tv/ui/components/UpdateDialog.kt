package dev.nitrate.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.nitrate.android.update.AppUpdater
import dev.nitrate.android.update.UpdateState
import dev.nitrate.core.update.AppRelease

/** Offers a newer Nitrate from the releases and shows the download until Android's installer takes over. */
@Composable
fun UpdatePrompt(updater: AppUpdater) {
    val state by updater.state.collectAsState()
    UpdateDialog(state, onUpdate = updater::install, onLater = updater::skip, onDismiss = updater::dismiss, onReoffer = updater::offer)
}

private data class Choice(val label: String, val action: () -> Unit)

@Composable
internal fun UpdateDialog(state: UpdateState, onUpdate: (AppRelease) -> Unit, onLater: (AppRelease) -> Unit, onDismiss: () -> Unit, onReoffer: (AppRelease) -> Unit = {}) {
    val context = LocalContext.current
    when (state) {
        is UpdateState.Available -> Panel("Update available", { onLater(state.release) }, listOf(Choice("Update now") { onUpdate(state.release) }, Choice("Later") { onLater(state.release) })) {
            Text(state.release.title, style = MaterialTheme.typography.titleMedium)
            if (state.release.notes.isNotEmpty()) {
                Text("What's new", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                state.release.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            }
        }
        is UpdateState.Downloading -> Panel("Downloading ${state.release.title}", {}, emptyList()) {
            Column(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth(state.progress.coerceIn(0.02f, 1f)).height(6.dp).background(MaterialTheme.colorScheme.primary)) {}
            }
        }
        is UpdateState.NeedsPermission -> Panel(
            "Allow Nitrate to install updates",
            onDismiss,
            listOf(Choice("Open settings") { runCatching { context.startActivity(state.settings) }; onReoffer(state.release) }, Choice("Not now", onDismiss)),
        ) {
            Text("Android asks once: turn on \"Allow from this source\" for Nitrate, then come back and choose Update now.", style = MaterialTheme.typography.bodyMedium)
        }
        is UpdateState.Failed -> Panel("Update", onDismiss, listOf(Choice("OK", onDismiss))) {
            Text(state.message, style = MaterialTheme.typography.bodyMedium)
        }
        else -> Unit
    }
}

@Composable
private fun Panel(title: String, onDismiss: () -> Unit, choices: List<Choice>, body: @Composable () -> Unit) {
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(600.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 4.dp))
            body()
            if (choices.isNotEmpty()) Column(Modifier.padding(top = 10.dp)) {
                choices.forEachIndexed { i, c ->
                    ListItem(selected = false, onClick = c.action, headlineContent = { Text(c.label) }, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier)
                }
            }
        }
    }
    LaunchedEffect(title, choices.size) { if (choices.isNotEmpty()) runCatching { first.requestFocus() } }
}
