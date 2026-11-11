package dev.nitrate.tv.ui.settings

import dev.nitrate.core.playback.capabilityLabel
import dev.nitrate.core.playback.formatBitrate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import dev.nitrate.core.playback.DeviceCapabilities
import dev.nitrate.core.playback.CapabilityRules
import dev.nitrate.core.playback.EffectiveCapability
import dev.nitrate.core.playback.MaxResolution
import dev.nitrate.core.playback.Override
import dev.nitrate.core.playback.PlaybackSettings
import dev.nitrate.android.storage.PlaybackSettingsStore
import kotlinx.coroutines.launch

private val PagePadding = 58.dp

@Composable
fun PlaybackSettingsScreen(
    store: PlaybackSettingsStore,
    capabilities: DeviceCapabilities,
    onRunTest: () -> Unit,
    onChannelTest: () -> Unit,
) {
    val settings by store.settings.collectAsState(PlaybackSettings())
    val scope = rememberCoroutineScope()
    val update: ((PlaybackSettings) -> PlaybackSettings) -> Unit = { f -> scope.launch { store.update(f) } }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = PagePadding, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Playback settings", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "${capabilities.model} · display ${capabilities.displayWidth}×${capabilities.displayHeight}. " +
                        "These settings belong to this TV and aren't synced.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onRunTest, modifier = Modifier.focusRequester(first)) { Text("Run playback test") }
                    OutlinedButton(onClick = onChannelTest) { Text("Audio channel test") }
                    if (settings.learnedFailures.isNotEmpty()) {
                        OutlinedButton(onClick = { scope.launch { store.clearFailures() } }) {
                            Text("Forget ${settings.learnedFailures.size} learned failure(s)")
                        }
                    }
                }
            }
        }

        section("Streaming")
        item {
            ChoiceRow("Maximum bitrate", formatBitrate(settings.maxBitrate), "Higher bitrates are transcoded down") {
                val list = PlaybackSettings.BITRATES
                update { it.copy(maxBitrate = list[(list.indexOf(it.maxBitrate) + 1).mod(list.size)]) }
            }
        }
        item {
            ChoiceRow("Maximum resolution", settings.maxResolution.label, "Larger video is transcoded down") {
                update { it.copy(maxResolution = MaxResolution.entries[(it.maxResolution.ordinal + 1) % MaxResolution.entries.size]) }
            }
        }
        item { ToggleRow("Allow direct play", settings.allowDirectPlay, "Play files as they are when the TV supports them") { v -> update { it.copy(allowDirectPlay = v) } } }
        item { ToggleRow("Allow direct stream", settings.allowDirectStream, "Repackage without re-encoding when only the container is unsupported") { v -> update { it.copy(allowDirectStream = v) } } }
        item {
            ToggleRow("Learn from failures", settings.useLearnedFailures, "Transcode formats that failed to direct play on this TV") { v ->
                update { it.copy(useLearnedFailures = v) }
            }
        }

        section("Video codecs", "Select to cycle Auto → Always → Never. Long-press to clear limits and failures.")
        items(CapabilityRules.videoCodecs(capabilities, settings), key = { it.id }) { cap ->
            val c = capabilities.video[cap.id]
            val reported = c?.let {
                "${it.maxWidth}×${it.maxHeight}" + (if (it.tenBit) ", 10-bit" else ", 8-bit") + (if (it.hardware) ", hardware" else ", software") + " (${it.decoderName})"
            }
            val limits = listOfNotNull(
                settings.codecMaxHeight[cap.id]?.let { "limited to ${it}p" },
                "8-bit only".takeIf { cap.id in settings.eightBitOnly },
            )
            CapabilityRow(cap, reported, limits, settings, update)
        }

        section("HDR formats", "Formats that are off are tone-mapped by the server.")
        items(CapabilityRules.hdrFormats(capabilities, settings), key = { it.id }) { cap ->
            CapabilityRow(cap, if (cap.claimed) "display supports it" else null, emptyList(), settings, update)
        }

        section("Audio codecs")
        items(CapabilityRules.audioCodecs(capabilities, settings), key = { it.id }) { cap ->
            val a = capabilities.audio[cap.id]
            val reported = listOfNotNull(a?.decoder?.let { "decoder $it" }, "HDMI passthrough".takeIf { a?.passthrough == true })
                .joinToString(", ").ifEmpty { null }
            CapabilityRow(cap, reported, emptyList(), settings, update)
        }
    }
}

private fun LazyListScope.section(title: String, subtitle: String? = null) {
    item(key = "section-$title") {
        Column(Modifier.padding(top = 24.dp, bottom = 6.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun CapabilityRow(
    cap: EffectiveCapability,
    reported: String?,
    limits: List<String>,
    settings: PlaybackSettings,
    update: ((PlaybackSettings) -> PlaybackSettings) -> Unit,
) {
    val override = settings.overrides[cap.id]
    val failure = settings.learnedFailures[cap.id]
    ListItem(
        selected = false,
        onClick = {
            // Auto -> Always -> Never -> Auto
            val next = when (override) {
                null -> Override.ALWAYS
                Override.ALWAYS -> Override.NEVER
                Override.NEVER -> null
            }
            update { s -> s.copy(overrides = if (next == null) s.overrides - cap.id else s.overrides + (cap.id to next)) }
        },
        onLongClick = {
            update { s ->
                s.copy(
                    learnedFailures = s.learnedFailures - cap.id,
                    codecMaxHeight = s.codecMaxHeight - cap.id,
                    eightBitOnly = s.eightBitOnly - cap.id,
                )
            }
        },
        headlineContent = { Text(capabilityLabel(cap.id)) },
        supportingContent = {
            Column {
                Text("TV reports: " + (reported ?: "not supported"), style = MaterialTheme.typography.bodySmall)
                if (limits.isNotEmpty()) Text(limits.joinToString(" · ").replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                failure?.let {
                    Text("Failed to direct play here ${it.count}× (${it.lastError})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        trailingContent = {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                Text(override?.label ?: "Auto", color = MaterialTheme.colorScheme.primary)
                Text(
                    if (cap.allowed) "direct play" else "transcode",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (cap.allowed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ChoiceRow(label: String, value: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        selected = false,
        onClick = onClick,
        headlineContent = { Text(label) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Text("$value  ›", color = MaterialTheme.colorScheme.primary) },
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, subtitle: String, onToggle: (Boolean) -> Unit) {
    ListItem(
        selected = false,
        onClick = { onToggle(!checked) },
        headlineContent = { Text(label) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    )
}
