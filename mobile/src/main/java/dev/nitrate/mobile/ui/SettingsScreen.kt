package dev.nitrate.mobile.ui

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
    val prefs = LocalDisplayPrefs.current
    val account by c.account.collectAsState()

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

        if (account?.isAdmin == true) {
            item {
                ListItem(
                    modifier = Modifier.clickable { actions.nav.navigate(ServerAdminRoute) },
                    headlineContent = { Text("Manage server") },
                    supportingContent = { Text("Libraries, tasks, activity, devices and users") },
                    trailingContent = { Text("›", color = MaterialTheme.colorScheme.primary) },
                )
            }
        }

        header("Display", "For your account; follows you to your other devices.")
        displaySettings(prefs) { change -> actions.server.userId?.let { u -> scope.launch { c.displaySettings.savePrefs(u, change(prefs)) } } }

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

/** Toggles for what's shown about items, the look, and Home. */
private fun LazyListScope.displaySettings(prefs: DisplayPrefs, update: ((DisplayPrefs) -> DisplayPrefs) -> Unit) {
    item(key = "accent") {
        ListItem(
            headlineContent = { Text("Accent colour") },
            supportingContent = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    AccentMode.entries.forEach { m ->
                        FilterChip(
                            selected = prefs.accent == m,
                            onClick = { update { it.copy(accent = m) } },
                            label = { Text(m.label) },
                            leadingIcon = m.argb?.let { c -> { Box(Modifier.size(12.dp).clip(CircleShape).background(Color(c))) } },
                        )
                    }
                }
            },
        )
    }
    item(key = "black") { Toggle("True black", "Pure black backgrounds, for OLED screens", prefs.trueBlack) { v -> update { it.copy(trueBlack = v) } } }
    item(key = "hero") { Toggle("Home hero", "The big rotating picture at the top of Home", prefs.homeHero) { v -> update { it.copy(homeHero = v) } } }
    item(key = "titles") {
        Choice("Titles under cards", prefs.cardTitles.label) { update { it.copy(cardTitles = CardTitles.entries[(it.cardTitles.ordinal + 1) % CardTitles.entries.size]) } }
    }
    val toggles: List<Triple<String, String, Pair<(DisplayPrefs) -> Boolean, (DisplayPrefs, Boolean) -> DisplayPrefs>>> = listOf(
        Triple("Community rating", "★ 7.8", Pair({ p -> p.communityRating }, { p, v -> p.copy(communityRating = v) })),
        Triple("Critic rating", "92% critics", Pair({ p -> p.criticRating }, { p, v -> p.copy(criticRating = v) })),
        Triple("Age rating", "PG-13, TV-14", Pair({ p -> p.officialRating }, { p, v -> p.copy(officialRating = v) })),
        Triple("Year", "Release year", Pair({ p -> p.year }, { p, v -> p.copy(year = v) })),
        Triple("Runtime", "1h 56m", Pair({ p -> p.runtime }, { p, v -> p.copy(runtime = v) })),
        Triple("Genres", "Drama • Mystery", Pair({ p -> p.genres }, { p, v -> p.copy(genres = v) })),
        Triple("Plot overviews", "Descriptions on Home and details pages", Pair({ p -> p.overview }, { p, v -> p.copy(overview = v) })),
        Triple("Taglines", "The one-line pitch on details pages", Pair({ p -> p.tagline }, { p, v -> p.copy(tagline = v) })),
        Triple("Quality labels", "4K, HDR, Dolby Vision, Atmos, 5.1", Pair({ p -> p.qualityBadges }, { p, v -> p.copy(qualityBadges = v) })),
        Triple("Progress bars", "How far into something you are", Pair({ p -> p.progressBars }, { p, v -> p.copy(progressBars = v) })),
        Triple("Watched ticks", "On cards and episodes", Pair({ p -> p.watchedMarks }, { p, v -> p.copy(watchedMarks = v) })),
        Triple("Unwatched counts", "On shows", Pair({ p -> p.unwatchedCounts }, { p, v -> p.copy(unwatchedCounts = v) })),
        Triple("Logos", "Title art instead of plain titles", Pair({ p -> p.logos }, { p, v -> p.copy(logos = v) })),
        Triple("Cast & crew", "On details pages", Pair({ p -> p.cast }, { p, v -> p.copy(cast = v) })),
        Triple("More like this", "On details pages", Pair({ p -> p.similar }, { p, v -> p.copy(similar = v) })),
        Triple("Hide spoilers", "Blur stills and hide descriptions of episodes you haven't seen", Pair({ p -> p.hideSpoilers }, { p, v -> p.copy(hideSpoilers = v) })),
    )
    toggles.forEach { (label, detail, get) ->
        item(key = "pref-$label") { Toggle(label, detail, get.first(prefs)) { v -> update { get.second(it, v) } } }
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
