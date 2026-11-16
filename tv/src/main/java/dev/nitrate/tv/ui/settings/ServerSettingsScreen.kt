package dev.nitrate.tv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.nitrate.android.AppContainer
import dev.nitrate.core.account.ServerSettings
import dev.nitrate.core.account.SettingReach
import kotlinx.coroutines.launch

/**
 * Settings → Server-side settings: this account's settings as the Jellyfin server keeps them
 * (languages, subtitles, permissions, Home), each marked with whether it applies in Nitrate, and
 * where to change it. Can be sent to the server's logs or copied.
 */
@Composable
fun ServerSettingsScreen(container: AppContainer) {
    val result by produceState<Result<ServerSettings>?>(null) { value = runCatching { container.server.serverSettings() } }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val first = remember { FocusRequester() }
    var message by remember { mutableStateOf<String?>(null) }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 58.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Column(Modifier.padding(bottom = 12.dp).widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Server-side settings", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Set for your account on your Jellyfin server, so the same on every device. Your picks in Nitrate (per show or film) come first; this TV's own settings are under Playback and Display.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val settings = result?.getOrNull()
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                    OutlinedButton(onClick = {
                        settings ?: return@OutlinedButton
                        message = "Saving…"
                        scope.launch {
                            message = runCatching { container.server.sendClientLog("Nitrate server-side settings\n\n" + settings.render()) }
                                .fold({ "Saved on your server as $it (Dashboard → Logs)" }, { "Couldn't save: ${it.message}" })
                        }
                    }, enabled = settings != null, modifier = Modifier.focusRequester(first)) { Text("Save to server logs") }
                    OutlinedButton(onClick = {
                        settings ?: return@OutlinedButton
                        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Nitrate server-side settings", settings.render()))
                        message = if (clipboard != null) "Copied to this TV's clipboard" else "This TV has no clipboard"
                    }, enabled = settings != null) { Text("Copy") }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                result?.exceptionOrNull()?.let { Text("Couldn't ask your server: ${it.message}", color = MaterialTheme.colorScheme.error) }
                if (result == null) Text("Asking your server…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        result?.getOrNull()?.groups?.forEach { g ->
            item(key = g.title) {
                Column(Modifier.padding(top = 16.dp, bottom = 4.dp)) {
                    Text(g.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    g.where?.let { Text("Change in: $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            g.settings.forEach { s ->
                item(key = g.title + s.label) {
                    // Focusable, so the list scrolls with the D-pad.
                    ListItem(
                        selected = false,
                        onClick = {},
                        headlineContent = { Text(s.label) },
                        supportingContent = {
                            Text(
                                s.reach.label + (s.note?.let { " · $it" } ?: ""),
                                color = if (s.reach == SettingReach.JELLYFIN_APPS) Color(0xFF9AA0A6) else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = { Text(s.value, style = MaterialTheme.typography.titleMedium) },
                        modifier = Modifier.widthIn(max = 900.dp),
                    )
                }
            }
        }
    }
    LaunchedEffect(result != null) { runCatching { first.requestFocus() } }
}
