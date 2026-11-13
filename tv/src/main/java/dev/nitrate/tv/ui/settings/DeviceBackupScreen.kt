package dev.nitrate.tv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.nitrate.android.backup.DeviceBackupManager
import dev.nitrate.android.backup.RestorePreview
import dev.nitrate.core.backup.BackupSummary
import dev.nitrate.tv.ui.lists.TvDialog
import kotlinx.coroutines.launch

private fun age(savedAtMs: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = (now - savedAtMs) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 48 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} days ago"
    }
}

private fun title(b: BackupSummary, thisDevice: String) = if (b.deviceId == thisDevice) "This TV (saved before)" else b.deviceName

/**
 * Asked once after signing in on a TV that hasn't backed up for this account: set it up like one
 * of the user's other devices (after seeing the differences), or not now.
 */
@Composable
fun BackupPrompt(manager: DeviceBackupManager) {
    val offer by manager.offer.collectAsState()
    val scope = rememberCoroutineScope()
    val o = offer ?: return
    RestoreChooser(
        title = "Set this TV up like…",
        intro = "Your other devices' settings are saved on your server. Choose one to copy its playback and plugin settings here; you'll see what changes first.",
        backups = o.backups,
        manager = manager,
        dismissLabel = "Not now",
        onDismiss = { scope.launch { manager.notNow() } },
        onApplied = {},
    )
}

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
    val first = remember { FocusRequester() }
    preview?.let { p ->
        DifferencesDialog(p, onApply = { scope.launch { manager.apply(p); preview = null; onApplied() } }, onBack = { preview = null })
        return
    }
    TvDialog(onDismiss) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        intro?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        backups.forEachIndexed { i, b ->
            ListItem(
                selected = false,
                onClick = { message = null; scope.launch { preview = runCatching { manager.preview(b) }.getOrNull() ?: run { message = "Couldn't read that backup"; null } } },
                headlineContent = { Text(title(b, manager.deviceId)) },
                supportingContent = { Text("${b.appLabel} · ${b.model} · saved ${age(b.savedAtMs)}") },
                modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
            )
        }
        ListItem(selected = false, onClick = onDismiss, headlineContent = { Text(dismissLabel) })
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

/** What restoring would change, setting by setting. */
@Composable
internal fun DifferencesDialog(preview: RestorePreview, onApply: () -> Unit, onBack: () -> Unit) = DifferencesPanel(
    intro = "From ${preview.source.deviceName}, saved ${age(preview.source.savedAtMs)}. " +
        if (preview.sameModel) "Same model, so its codec test results come too." else "A different model, so this TV keeps its own codec test results.",
    differences = preview.differences,
    onApply = onApply,
    onBack = onBack,
)

/**
 * A Differences list before anything is applied: Apply and Back first, then every change grouped
 * by section; each line is focusable so the D-pad scrolls through them.
 */
@Composable
internal fun DifferencesPanel(intro: String, differences: List<dev.nitrate.core.backup.Difference>, onApply: () -> Unit, onBack: () -> Unit) {
    val apply = remember { FocusRequester() }
    TvDialog(onBack, width = 720) {
        Text("Differences", style = MaterialTheme.typography.headlineSmall)
        Text(intro, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val n = differences.size
        ListItem(
            selected = false,
            onClick = onApply,
            headlineContent = { Text(if (n == 0) "OK" else "Apply $n change${if (n == 1) "" else "s"}") },
            modifier = Modifier.focusRequester(apply),
        )
        ListItem(selected = false, onClick = onBack, headlineContent = { Text("Back") })
        if (n == 0) Text("Nothing would change: this TV already has the same settings.")
        differences.groupBy { it.section }.forEach { (section, list) ->
            Text(section, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
            list.forEach { d ->
                ListItem(
                    selected = false,
                    onClick = {},
                    headlineContent = { Text(d.setting) },
                    supportingContent = { Text("${d.from}  →  ${d.to}") },
                )
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { apply.requestFocus() } }
}

/** Settings → Device backup: when this TV last backed up, back up now, restore from another device. */
@Composable
fun DeviceBackupScreen(manager: DeviceBackupManager) {
    val scope = rememberCoroutineScope()
    val saved by manager.lastSaved.collectAsState(null)
    var status by remember { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf<List<BackupSummary>?>(null) }
    val first = remember { FocusRequester() }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 58.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Column(Modifier.padding(bottom = 16.dp).widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Device backup", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "This TV's playback and plugin settings are saved to your account on your server a few seconds after any change, " +
                        "so a new or reset device can start the same way. No PINs; scripts have their own backup.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            ListItem(
                selected = false,
                onClick = {
                    status = "Saving…"
                    scope.launch { status = manager.backUpNow().fold({ "Saved" }, { "Couldn't save: ${it.message ?: "server unreachable"}" }) }
                },
                headlineContent = { Text("Back up now") },
                supportingContent = { Text(status ?: saved?.let { "Last saved ${age(it)}" } ?: "Not saved yet from this TV") },
                modifier = Modifier.widthIn(max = 720.dp).focusRequester(first),
            )
        }
        item {
            ListItem(
                selected = false,
                onClick = {
                    status = null
                    scope.launch {
                        runCatching { manager.backups().filter { it.deviceId != manager.deviceId } }
                            .onSuccess { if (it.isEmpty()) status = "No other devices have backed up yet" else choosing = it }
                            .onFailure { status = "Couldn't reach your server" }
                    }
                },
                headlineContent = { Text("Restore from another device") },
                supportingContent = { Text("Copy another device's settings here; shows the differences first") },
                modifier = Modifier.widthIn(max = 720.dp),
            )
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    choosing?.let { list ->
        RestoreChooser(
            title = "Restore from…", intro = null, backups = list, manager = manager, dismissLabel = "Cancel",
            onDismiss = { choosing = null }, onApplied = { choosing = null; status = "Restored" },
        )
    }
}
