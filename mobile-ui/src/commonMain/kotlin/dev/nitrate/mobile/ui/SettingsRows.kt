package dev.nitrate.mobile.ui

import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import dev.nitrate.core.pages.DisplayPrefs
import dev.nitrate.core.pages.CardTitles
import dev.nitrate.core.pages.AccentMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nitrate.core.episodes.EpisodeOrder.Scope
import dev.nitrate.core.episodes.PreviousEpisodePlugin
import dev.nitrate.core.episodes.PreviousEpisodeSettings
import dev.nitrate.core.playback.CapabilityRules
import dev.nitrate.core.playback.EffectiveCapability
import dev.nitrate.core.playback.MaxResolution
import dev.nitrate.core.playback.Override
import dev.nitrate.core.playback.PlaybackSettings
import dev.nitrate.core.playback.capabilityLabel
import dev.nitrate.core.playback.formatBitrate
import dev.nitrate.core.playback.SoundMode
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import dev.nitrate.core.playback.soundMode
import dev.nitrate.core.playback.withSoundMode
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import dev.nitrate.core.plugins.settings
import dev.nitrate.core.plugins.update
import dev.nitrate.core.writing.WritingPlugin
import dev.nitrate.core.writing.WritingSettings
import kotlinx.coroutines.launch

internal fun LazyListScope.group(title: String) {
    item(key = "g-$title") {
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
    }
}

@Composable
internal fun Link(title: String, detail: String?, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = detail?.let { { Text(it) } },
        trailingContent = { Text("›", color = MaterialTheme.colorScheme.primary) },
    )
}

/** Where a page's settings apply: this phone only, its display profile, or every device. */
@Composable
internal fun ScopeTag(scope: dev.nitrate.core.settings.SettingScope, profile: String? = null) {
    Text(
        scope.label("phone", profile),
        style = MaterialTheme.typography.labelMedium,
        color = accentColor(),
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(accentColor().copy(alpha = 0.12f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

internal fun LazyListScope.heading(title: String, scope: dev.nitrate.core.settings.SettingScope? = null, profile: String? = null) {
    item(key = "h-$title") {
        Column(Modifier.padding(top = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            scope?.let { ScopeTag(it, profile) }
        }
    }
}

/** Signs in a TV (or any device showing a Quick Connect code) as this account. */
@Composable
internal fun QuickConnectRow(actions: AppActions) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable { open = true },
        headlineContent = { Text("Sign in another device") },
        supportingContent = { Text("Enter the Quick Connect code your TV shows") },
        trailingContent = { Text("›", color = MaterialTheme.colorScheme.primary) },
    )
    if (open) QuickConnectDialog(actions) { open = false }
}

@Composable
fun QuickConnectDialog(actions: AppActions, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sign in another device") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("On the TV, choose Quick Connect. Type the code it shows to sign it in as you.")
                androidx.compose.material3.OutlinedTextField(
                    code,
                    { code = it.filter(Char::isLetterOrDigit).take(8); status = null },
                    label = { Text("Code") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                )
                status?.let { (ok, text) -> Text(text, color = if (ok) accentColor() else MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (status?.first == true) {
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Done") }
            } else {
                androidx.compose.material3.TextButton(
                    enabled = code.length >= 6 && !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            status = runCatching { actions.server.authorizeQuickConnect(code) }
                                .fold({ true to "Approved. The other device is signing in." }, { false to (it.message ?: "Couldn't approve that code") })
                            busy = false
                        }
                    },
                ) { Text("Approve") }
            }
        },
        dismissButton = { if (status?.first != true) androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Sends what this device can play (and what Nitrate allows) to the server's logs, for diagnosing playback. */
@Composable
internal fun DeviceReportRow(actions: AppActions) {
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    ListItem(
        modifier = Modifier.clickable {
            message = "Sending…"
            scope.launch {
                message = actions.container.sendPlaybackReport().fold(
                    { name -> "Saved on your server as $name (Jellyfin Dashboard → Logs)" },
                    { e -> "Couldn't send: ${e.message ?: e::class.simpleName}" },
                )
            }
        },
        headlineContent = { Text("Send device report") },
        supportingContent = { Text(message ?: "What this device can play, saved to your Jellyfin server's logs. During playback, Info → Send log adds the file and what happened.") },
        trailingContent = { Text("›", color = MaterialTheme.colorScheme.primary) },
    )
}
