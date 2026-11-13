package dev.nitrate.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.nitrate.android.backup.DeviceBackupManager
import dev.nitrate.android.backup.RestorePreview
import dev.nitrate.core.backup.BackupSummary
import dev.nitrate.core.backup.Difference
import dev.nitrate.mobile.platform.rememberFilePickers
import kotlinx.coroutines.launch

/** "When" in words, for backups. */
internal fun backupAge(savedAtMs: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = (now - savedAtMs) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 48 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} days ago"
    }
}

internal fun backupTitle(b: BackupSummary, thisDevice: String) =
    if (b.deviceId == thisDevice) "This device (saved before)" else b.deviceName

/**
 * Asked once after signing in on a device that hasn't backed up for this account: set it up like
 * one of the user's other devices (after seeing the differences), or not now.
 */
@Composable
fun BackupPrompt(manager: DeviceBackupManager) {
    val offer by manager.offer.collectAsState()
    val scope = rememberCoroutineScope()
    val o = offer ?: return
    RestoreChooser(
        title = "Set this device up like…",
        intro = "Your other devices' settings are saved on your server. Choose one to copy its playback and plugin settings here; you'll see what changes first.",
        backups = o.backups,
        manager = manager,
        dismissLabel = "Not now",
        onDismiss = { scope.launch { manager.notNow() } },
        onApplied = {},
    )
}

/** The list of backups, then the differences for the chosen one, then apply. */
@Composable
internal fun RestoreChooser(
    title: String,
    intro: String?,
    backups: List<BackupSummary>,
    manager: DeviceBackupManager,
    dismissLabel: String,
    onDismiss: () -> Unit,
    onApplied: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<RestorePreview?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    preview?.let { p ->
        DifferencesDialog(p, onApply = { scope.launch { manager.apply(p); preview = null; onApplied() } }, onBack = { preview = null })
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                intro?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(backups, key = { it.deviceId }) { b ->
                        ListItem(
                            modifier = Modifier.clickable {
                                message = null
                                scope.launch { preview = runCatching { manager.preview(b) }.getOrNull() ?: run { message = "Couldn't read that backup"; null } }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(backupTitle(b, manager.deviceId)) },
                            supportingContent = { Text("${b.appLabel} · ${b.model} · saved ${backupAge(b.savedAtMs)}") },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}

/** What restoring would change, setting by setting, before anything is applied. */
@Composable
internal fun DifferencesDialog(preview: RestorePreview, onApply: () -> Unit, onBack: () -> Unit) {
    AlertDialog(
        onDismissRequest = onBack,
        title = { Text("Differences") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "From ${preview.source.deviceName}, saved ${backupAge(preview.source.savedAtMs)}. " +
                        if (preview.sameModel) "Same model, so its codec test results come too." else "A different model, so this device keeps its own codec test results.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (preview.differences.isEmpty()) {
                    Text("Nothing would change: this device already has the same settings.")
                } else {
                    DifferenceList(preview.differences, Modifier.heightIn(max = 380.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onApply) {
                Text(if (preview.differences.isEmpty()) "OK" else "Apply ${preview.differences.size} change${if (preview.differences.size == 1) "" else "s"}")
            }
        },
        dismissButton = { TextButton(onClick = onBack) { Text("Back") } },
    )
}

@Composable
internal fun DifferenceList(differences: List<Difference>, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        differences.groupBy { it.section }.forEach { (section, list) ->
            item(key = "h:$section") {
                Text(section, style = MaterialTheme.typography.labelLarge, color = accentColor(), modifier = Modifier.padding(top = 6.dp))
            }
            items(list, key = { "${it.section}:${it.setting}" }) { d ->
                Column {
                    Text(d.setting, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(d.from, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("→", style = MaterialTheme.typography.bodySmall)
                        Text(d.to, style = MaterialTheme.typography.bodySmall, color = accentColor())
                    }
                }
            }
        }
    }
}

/** Settings: when this device last backed up, back up now, restore from another device, and files. */
@Composable
fun DeviceBackupSection(manager: DeviceBackupManager) {
    val scope = rememberCoroutineScope()
    val saved by manager.lastSaved.collectAsState(null)
    var status by remember { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf<List<BackupSummary>?>(null) }
    var filePreview by remember { mutableStateOf<RestorePreview?>(null) }
    val pickers = rememberFilePickers(
        onOpened = { bytes -> scope.launch { filePreview = manager.previewFile(bytes) ?: run { status = "That file isn't a Nitrate settings backup"; null } } },
        onResult = { status = it },
    )
    Column {
        ListItem(
            headlineContent = { Text("Backed up to your server") },
            supportingContent = { Text(status ?: saved?.let { "Last saved ${backupAge(it)}. Saves by itself a few seconds after a change." } ?: "Not saved yet from this device") },
            trailingContent = {
                OutlinedButton(onClick = {
                    status = "Saving…"
                    scope.launch { status = manager.backUpNow().fold({ "Saved" }, { "Couldn't save: ${it.message ?: "server unreachable"}" }) }
                }) { Text("Back up now") }
            },
        )
        ListItem(
            modifier = Modifier.clickable {
                status = null
                scope.launch {
                    runCatching { manager.backups().filter { it.deviceId != manager.deviceId } }
                        .onSuccess { if (it.isEmpty()) status = "No other devices have backed up yet" else choosing = it }
                        .onFailure { status = "Couldn't reach your server" }
                }
            },
            headlineContent = { Text("Restore from another device") },
            supportingContent = { Text("Copy another device's settings here; shows the differences first") },
            trailingContent = { Text("›", color = MaterialTheme.colorScheme.primary) },
        )
        ListItem(
            modifier = Modifier.clickable { scope.launch { pickers.save("nitrate-settings.json", "text/plain", manager.exportFile()) } },
            headlineContent = { Text("Export to a file") },
            supportingContent = { Text("This device's playback and plugin settings (no PINs, no scripts)") },
        )
        ListItem(
            modifier = Modifier.clickable { status = null; pickers.open() },
            headlineContent = { Text("Import from a file") },
            supportingContent = { Text("Shows the differences before applying") },
        )
    }
    choosing?.let { list ->
        RestoreChooser(
            title = "Restore from…", intro = null, backups = list, manager = manager, dismissLabel = "Cancel",
            onDismiss = { choosing = null }, onApplied = { choosing = null; status = "Restored" },
        )
    }
    filePreview?.let { p ->
        DifferencesDialog(p, onApply = { scope.launch { manager.apply(p); filePreview = null; status = "Restored" } }, onBack = { filePreview = null })
    }
}
