package dev.nitrate.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.nitrate.core.account.ServerSettings
import dev.nitrate.core.account.SettingReach

/** Settings row (Account): what's set for this account on the Jellyfin server. */
@Composable
fun ServerSettingsRow(actions: AppActions) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable { open = true },
        headlineContent = { Text("Server-side settings") },
        supportingContent = { Text("Languages, subtitles and permissions set on your Jellyfin server, not on this device") },
        trailingContent = { Text("›", color = MaterialTheme.colorScheme.primary) },
    )
    if (open) ServerSettingsDialog(actions) { open = false }
}

/**
 * The account's settings as the Jellyfin server keeps them, in groups, each marked with whether
 * it applies in Nitrate or only in Jellyfin's own apps, and where to change it. Copyable.
 */
@Composable
fun ServerSettingsDialog(actions: AppActions, onDismiss: () -> Unit) {
    val platformActions = dev.nitrate.mobile.platform.rememberPlatformActions()
    val result by produceState<Result<ServerSettings>?>(null) { value = runCatching { actions.server.serverSettings() } }
    var copied by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Server-side settings", modifier = Modifier.weight(1f))
                val settings = result?.getOrNull()
                IconButton(onClick = { settings?.let { platformActions.copy(it.render(), "Nitrate server-side settings"); copied = true } }, enabled = settings != null) {
                    Icon(CopyIcon, "Copy")
                }
            }
        },
        text = {
            val r = result
            when {
                r == null -> Text("Asking your server…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                r.isFailure -> Text("Couldn't ask your server: ${r.exceptionOrNull()?.message}", color = MaterialTheme.colorScheme.error)
                else -> LazyColumn(Modifier.heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item {
                        Text(
                            (if (copied) "Copied. " else "") + "Set for your account on Jellyfin, so the same on every device. Your picks in Nitrate (per show or film) come first; device and display settings are in this app's other sections.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    r.getOrThrow().groups.forEach { g ->
                        item(key = g.title) {
                            Column(Modifier.padding(top = 14.dp)) {
                                Text(g.title, style = MaterialTheme.typography.titleSmall, color = accentColor())
                                g.where?.let { Text("Change in: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                        g.settings.forEach { s ->
                            item(key = g.title + s.label) {
                                Column(Modifier.padding(top = 6.dp)) {
                                    Row(verticalAlignment = Alignment.Top) {
                                        Text(s.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                        Text(s.value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
                                    }
                                    Text(
                                        s.reach.label + (s.note?.let { " · $it" } ?: ""),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (s.reach == SettingReach.JELLYFIN_APPS) Color(0xFF9AA0A6) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
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
