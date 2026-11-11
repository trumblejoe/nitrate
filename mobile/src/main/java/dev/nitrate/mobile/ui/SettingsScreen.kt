package dev.nitrate.mobile.ui

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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
import dev.nitrate.core.plugins.settings
import dev.nitrate.core.plugins.update
import dev.nitrate.core.writing.WritingPlugin
import dev.nitrate.core.writing.WritingSettings
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(actions: AppActions, onSignedOut: () -> Unit, modifier: Modifier = Modifier) {
    val c = actions.container
    val scope = rememberCoroutineScope()
    val playback by c.playbackSettings.settings.collectAsState(PlaybackSettings())
    val enabled by c.plugins.enabledIds.collectAsState(emptySet())
    val store = c.plugins.host.store
    val previous by store.settings(PreviousEpisodePlugin.id, PreviousEpisodeSettings()).collectAsState(PreviousEpisodeSettings())
    val writing by store.settings(WritingPlugin.id, WritingSettings()).collectAsState(WritingSettings())
    val updatePlayback: ((PlaybackSettings) -> PlaybackSettings) -> Unit = { f -> scope.launch { c.playbackSettings.update(f) } }
    val caps = c.capabilities

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars)) }
        header("Account")
        item {
            ListItem(
                headlineContent = { Text(actions.server.serverUrl ?: "Not signed in") },
                supportingContent = { Text("Server") },
                trailingContent = {
                    OutlinedButton(onClick = { scope.launch { actions.server.logout(); onSignedOut() } }) { Text("Sign out") }
                },
            )
        }

        header("Playback", "Settings for this device; they aren't synced.")
        item {
            Choice("Maximum bitrate", formatBitrate(playback.maxBitrate)) {
                val list = PlaybackSettings.BITRATES
                updatePlayback { it.copy(maxBitrate = list[(list.indexOf(it.maxBitrate) + 1).mod(list.size)]) }
            }
        }
        item {
            Choice("Maximum resolution", playback.maxResolution.label) {
                updatePlayback { it.copy(maxResolution = MaxResolution.entries[(it.maxResolution.ordinal + 1) % MaxResolution.entries.size]) }
            }
        }
        item { Toggle("Allow direct play", "Play files as they are when this device supports them", playback.allowDirectPlay) { v -> updatePlayback { it.copy(allowDirectPlay = v) } } }
        item { Toggle("Allow direct stream", "Repackage without re-encoding when only the container is unsupported", playback.allowDirectStream) { v -> updatePlayback { it.copy(allowDirectStream = v) } } }
        item { Toggle("Learn from failures", "Transcode formats that failed to direct play here", playback.useLearnedFailures) { v -> updatePlayback { it.copy(useLearnedFailures = v) } } }
        if (playback.learnedFailures.isNotEmpty()) {
            item {
                ListItem(
                    modifier = Modifier.clickable { scope.launch { c.playbackSettings.clearFailures() } },
                    headlineContent = { Text("Forget ${playback.learnedFailures.size} learned failure(s)") },
                    supportingContent = { Text(playback.learnedFailures.keys.joinToString { capabilityLabel(it) }) },
                )
            }
        }

        header("Formats", "Tap to cycle Auto → Always → Never.")
        items(CapabilityRules.videoCodecs(caps, playback) + CapabilityRules.hdrFormats(caps, playback) + CapabilityRules.audioCodecs(caps, playback), key = { it.id }) { cap ->
            Capability(cap, playback, updatePlayback)
        }

        header("Plugins", "Built in; switch them on or off for this device.")
        items(c.plugins.all, key = { it.id }) { plugin ->
            Toggle(plugin.name, plugin.description, plugin.id in enabled) { on -> scope.launch { c.plugins.setEnabled(plugin.id, on) } }
        }
        if (PreviousEpisodePlugin.id in enabled) {
            item {
                Choice(
                    "Unwatched episode check",
                    when (previous.scope) {
                        Scope.PREVIOUS -> "The one before"
                        Scope.SEASON -> "Whole season"
                        Scope.SERIES -> "Whole series"
                    },
                ) { scope.launch { store.update(PreviousEpisodePlugin.id, PreviousEpisodeSettings()) { it.copy(scope = Scope.entries[(it.scope.ordinal + 1) % Scope.entries.size]) } } }
            }
            item {
                Toggle("Include specials", "Specials placed before, during or after seasons count", previous.includeSpecials) { v ->
                    scope.launch { store.update(PreviousEpisodePlugin.id, PreviousEpisodeSettings()) { it.copy(includeSpecials = v) } }
                }
            }
            item {
                Toggle("Spot missing episodes", "Gaps in episode or season numbering", previous.detectGaps) { v ->
                    scope.launch { store.update(PreviousEpisodePlugin.id, PreviousEpisodeSettings()) { it.copy(detectGaps = v) } }
                }
            }
        }
        if (WritingPlugin.id in enabled) {
            item {
                Toggle("Back up scripts to the server", "Keeps a copy in your Jellyfin account for other devices", writing.backupToServer) { v ->
                    scope.launch { store.update(WritingPlugin.id, WritingSettings()) { it.copy(backupToServer = v) } }
                }
            }
        }
    }
}

private fun LazyListScope.header(title: String, subtitle: String? = null) {
    item(key = "h-$title") {
        HorizontalDivider(Modifier.padding(top = 8.dp))
        ListItem(
            headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) },
            supportingContent = subtitle?.let { { Text(it) } },
        )
    }
}

@Composable
private fun Choice(label: String, value: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(label) },
        trailingContent = { Text(value, color = MaterialTheme.colorScheme.primary) },
    )
}

@Composable
private fun Toggle(label: String, detail: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onToggle(!checked) },
        headlineContent = { Text(label) },
        supportingContent = { Text(detail) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onToggle) },
    )
}

@Composable
private fun Capability(cap: EffectiveCapability, settings: PlaybackSettings, update: ((PlaybackSettings) -> PlaybackSettings) -> Unit) {
    val override = settings.overrides[cap.id]
    ListItem(
        modifier = Modifier.clickable {
            val next = when (override) {
                null -> Override.ALWAYS
                Override.ALWAYS -> Override.NEVER
                Override.NEVER -> null
            }
            update { s -> s.copy(overrides = if (next == null) s.overrides - cap.id else s.overrides + (cap.id to next)) }
        },
        headlineContent = { Text(capabilityLabel(cap.id)) },
        supportingContent = {
            Text(
                (if (cap.claimed) "Device supports it" else "Device doesn't report it") +
                    (settings.learnedFailures[cap.id]?.let { " · failed here ${it.count}×" } ?: ""),
            )
        },
        trailingContent = {
            Text(
                (override?.label ?: "Auto") + " · " + if (cap.allowed) "direct" else "transcode",
                color = if (cap.allowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        },
    )
}
