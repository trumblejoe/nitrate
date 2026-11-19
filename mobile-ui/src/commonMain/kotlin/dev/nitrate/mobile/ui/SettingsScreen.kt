package dev.nitrate.mobile.ui

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

/** Settings subpages, opened from the Settings tab ([SettingsPageRoute.page]). */
enum class SettingsPage(val title: String) {
    SOUND("Picture & sound"),
    ADVANCED("Picture & sound: Advanced"),
    SUBTITLES("Subtitles"),
    DISPLAY("Display"),
    SHOWN("What's shown"),
    WATCHING("While watching"),
    NOTIFICATIONS("Notifications"),
    BACKUP("Device backup"),
    REMOTE("Control a device"),
    UPDATES("Updates"),
    PLUGINS("Plugins"),
}

/**
 * The Settings tab: the settings in groups (the settings themselves, account and servers, your
 * watching, tools, about), each opening its own page.
 */
@Composable
fun SettingsScreen(actions: AppActions, onSignedOut: () -> Unit, modifier: Modifier = Modifier) {
    val c = actions.container
    val scope = rememberCoroutineScope()
    val account by c.account.collectAsState()
    val companion by c.companion.collectAsState()
    val easy = LocalDisplayPrefs.current.easyMode
    fun open(page: SettingsPage) = actions.nav.navigate(SettingsPageRoute(page.name))

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars)) }
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        item { Link("Picture & sound", "Sound, speakers, audio sync; Advanced for formats and fixes") { open(SettingsPage.SOUND) } }
        item { Link("Subtitles", "Size, colour, background and styled subtitles") { open(SettingsPage.SUBTITLES) } }
        item { Link("Display", "Look, colours, display profile, what's shown about titles") { open(SettingsPage.DISPLAY) } }
        item { Link("While watching", "Scenes in the credits, recaps, spoilers, audio description, theme music, \"Still watching?\"") { open(SettingsPage.WATCHING) } }
        item { Link("Notifications", "New episodes and new seasons") { open(SettingsPage.NOTIFICATIONS) } }

        group("Account & servers")
        item {
            ListItem(
                headlineContent = { Text(actions.server.serverUrl ?: "Not signed in") },
                supportingContent = { Text("Server") },
                trailingContent = {
                    // Easy mode asks first.
                    var asking by remember { mutableStateOf(false) }
                    OutlinedButton(onClick = { if (easy) asking = true else scope.launch { c.signOut(); onSignedOut() } }) { Text("Sign out") }
                    if (asking) androidx.compose.material3.AlertDialog(
                        onDismissRequest = { asking = false },
                        title = { Text("Sign out?") },
                        text = { Text("You'll need your password to sign in again.") },
                        confirmButton = { androidx.compose.material3.TextButton(onClick = { asking = false; scope.launch { c.signOut(); onSignedOut() } }) { Text("Sign out") } },
                        dismissButton = { androidx.compose.material3.TextButton(onClick = { asking = false }) { Text("Cancel") } },
                    )
                },
            )
        }
        item { Link("Switch account", "Other users and servers, PINs for profiles") { actions.nav.navigate(ProfilesRoute) } }
        item { Link("Servers", "Local, VPN and remote addresses; add, switch and choose the primary") { actions.nav.navigate(ServerAddressesRoute) } }
        item { ServerSettingsRow(actions) }
        item { QuickConnectRow(actions) }
        item { Link("Device backup", "This phone's settings on your server; restore another device's") { open(SettingsPage.BACKUP) } }

        group("Your watching")
        item { Link("Your year", "Films, episodes and songs, top shows, genres and artists") { actions.nav.navigate(StatsRoute) } }
        if (companion?.features?.contains(dev.nitrate.core.diary.Diary.FEATURE) == true) item {
            Link("Watch diary", "Every film and episode you finish, with your ratings and notes; export for Letterboxd") { actions.nav.navigate(DiaryRoute) }
        }
        item { WatchTogetherRow(actions) }
        item { if (partnersAvailable(actions)) Link("Watch partners", "Shows you watch with someone, requests, and keeping your account private") { actions.nav.navigate(WatchPartnersRoute) } }
        item { Link("Control a device", "Play, pause, seek and change volume on your TV or another app") { open(SettingsPage.REMOTE) } }

        group("Tools")
        item { Link("Checking how things play", "A guide to these tools: which to use, how, and what to do about washed-out HDR, missing surround or stutter") { actions.nav.navigate(GuideRoute) } }
        item { Link("Advanced playback settings", "The formats this phone plays (Always, Never), learned failures, direct play, resolution limit: what the tools' fixes change") { open(SettingsPage.ADVANCED) } }
        item { Link("Library playback overview", "Every film and episode you can see, and how each plays on this phone: as it is, converted, and why") { actions.nav.navigate(PlaybackOverviewRoute) } }
        item { SpeedCheckRow(actions) }
        item { DeviceReportRow(actions) }
        if (account?.isAdmin == true) {
            item { Link("Household devices", "How every device in the house plays the library, its tests and what its users saw at their TVs") { actions.nav.navigate(HouseholdDevicesRoute) } }
            item { Link("Library health", "Missing artwork and details, duplicates, missing episodes, what this phone always converts") { actions.nav.navigate(LibraryHealthRoute) } }
            item { Link("Manage server", "Libraries, tasks, activity, devices and users") { actions.nav.navigate(ServerAdminRoute) } }
        }

        group("About")
        item { Link("Updates", "Nitrate ${c.updater.versionName}; check now or when Nitrate opens") { open(SettingsPage.UPDATES) } }
        item { Link("Plugins", "Built-in extras, on or off for this phone") { open(SettingsPage.PLUGINS) } }
        item { Link("Open-source licences", "FFmpeg, Media3, Kotlin and the rest Nitrate is built with") { actions.nav.navigate(LicensesRoute) } }
    }
}

private fun LazyListScope.group(title: String) {
    item(key = "g-$title") {
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
    }
}

@Composable
private fun Link(title: String, detail: String?, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = detail?.let { { Text(it) } },
        trailingContent = { Text("›", color = MaterialTheme.colorScheme.primary) },
    )
}

/** Where a page's settings apply: this phone only, its display profile, or every device. */
@Composable
private fun ScopeTag(scope: dev.nitrate.core.settings.SettingScope, profile: String? = null) {
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

private fun LazyListScope.heading(title: String, scope: dev.nitrate.core.settings.SettingScope? = null, profile: String? = null) {
    item(key = "h-$title") {
        Column(Modifier.padding(top = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            scope?.let { ScopeTag(it, profile) }
        }
    }
}

/** One settings page, with a back arrow. */
@Composable
fun SettingsPageScreen(actions: AppActions, page: SettingsPage) {
    val c = actions.container
    val scope = rememberCoroutineScope()
    val playback by c.playbackSettings.settings.collectAsState(PlaybackSettings())
    val updatePlayback: ((PlaybackSettings) -> PlaybackSettings) -> Unit = { f -> scope.launch { c.playbackSettings.update(f) } }
    val prefs = LocalDisplayPrefs.current
    val updatePrefs: ((DisplayPrefs) -> DisplayPrefs) -> Unit = { change -> actions.server.userId?.let { u -> scope.launch { c.displaySettings.savePrefs(u, change(prefs)) } } }
    val user = actions.server.userId
    val profile by remember(user) { user?.let { c.displaySettings.activeProfileName(it) } ?: kotlinx.coroutines.flow.flowOf(null) }.collectAsState(null)
    val caps = c.capabilities

    LazyColumn(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                androidx.compose.material3.IconButton(onClick = { actions.nav.popBackStack() }) {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
                Text(page.title, style = MaterialTheme.typography.headlineSmall)
            }
        }
        when (page) {
            SettingsPage.SOUND -> {
                item { ScopeTag(dev.nitrate.core.settings.SettingScope.DEVICE) }
                heading("Audio")
                item {
                    ListItem(
                        headlineContent = { Text("Sound") },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SoundMode.entries.forEach { m -> FilterChip(selected = playback.soundMode == m, onClick = { updatePlayback { it.withSoundMode(m) } }, label = { Text(m.label) }) }
                                }
                                Text(playback.soundMode.detail + ". " + SoundMode.RECEIVER_NOTE)
                            }
                        },
                    )
                }
                item {
                    val output by dev.nitrate.mobile.platform.rememberSoundOutput()
                    SpeakersSettings(playback, output, updatePlayback)
                }
                item {
                    ListItem(
                        headlineContent = { Text("Audio sync") },
                        supportingContent = {
                            Column {
                                Text(dev.nitrate.core.playback.AudioSync.label(playback.audioDelayMs) + ". Change it if lips and voices don't line up, e.g. with Bluetooth headphones or a receiver that adds lag; also in the player's menu.")
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    dev.nitrate.core.playback.AudioSync.STEPS.forEach { d ->
                                        OutlinedButton(onClick = { updatePlayback { it.copy(audioDelayMs = dev.nitrate.core.playback.AudioSync.clamp(it.audioDelayMs + d)) } }) {
                                            Text((if (d > 0) "+" else "−") + "${kotlin.math.abs(d)} ms")
                                        }
                                    }
                                    if (playback.audioDelayMs != 0) androidx.compose.material3.TextButton(onClick = { updatePlayback { it.copy(audioDelayMs = 0) } }) { Text("Reset") }
                                }
                            }
                        },
                    )
                }
                heading("Player")
                item { Toggle("Keep playing in the background", "Audio continues with the screen off or in another app", playback.backgroundAudio) { v -> updatePlayback { it.copy(backgroundAudio = v) } } }
                item { Toggle("Picture-in-picture", "Leaving the app mid-video keeps it in a small window", playback.pictureInPicture) { v -> updatePlayback { it.copy(pictureInPicture = v) } } }
                item { Toggle("Swipe for brightness and volume", "Up and down on the left of the video for brightness, on the right for volume", playback.swipeControls) { v -> updatePlayback { it.copy(swipeControls = v) } } }
                heading("Streaming")
                item {
                    Choice("Maximum bitrate", formatBitrate(playback.maxBitrate), "No limit plays every file as it is. Set a cap to have the server convert big files down, e.g. on mobile data") {
                        val list = PlaybackSettings.BITRATES
                        updatePlayback { it.copy(maxBitrate = list[(list.indexOf(it.maxBitrate) + 1).mod(list.size)]) }
                    }
                }
                item { Link("Advanced", "Dolby Vision profile 7, 3D, resolution limit, direct play, and the formats this phone plays") { actions.nav.navigate(SettingsPageRoute(SettingsPage.ADVANCED.name)) } }
            }
            SettingsPage.ADVANCED -> {
                item {
                    Text(
                        "For troubleshooting and unusual setups. Leave these as they are unless something plays wrong; \"Why converting?\" in the player and \"How it plays\" on a title suggest the right change.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                item { ScopeTag(dev.nitrate.core.settings.SettingScope.DEVICE) }
                heading("Picture")
                item {
                    Toggle("Dolby Vision profile 7 as 8.1", "UHD Blu-ray Dolby Vision is converted while it plays, for Dolby Vision instead of HDR10 on devices that take profile 8. Turn off only if those films look wrong", playback.convertProfile7) { v ->
                        updatePlayback { it.copy(convertProfile7 = v) }
                    }
                }
                item {
                    val t = playback.threeD
                    Choice("3D films", t.label, t.detail) {
                        val all = dev.nitrate.core.playback.ThreeDMode.entries
                        updatePlayback { it.copy(threeD = all[(t.ordinal + 1) % all.size]) }
                    }
                }
                item {
                    Choice("Maximum resolution", playback.maxResolution.label, "Larger video is converted down by the server") {
                        updatePlayback { it.copy(maxResolution = MaxResolution.entries[(it.maxResolution.ordinal + 1) % MaxResolution.entries.size]) }
                    }
                }
                heading("Troubleshooting")
                item { Toggle("Learn from failures", "A title that failed to play here as it is gets converted next time; a whole format only after 3 different titles fail. Forgotten after 60 days. Keep on", playback.useLearnedFailures) { v -> updatePlayback { it.copy(useLearnedFailures = v) } } }
                if (playback.learnedFailures.isNotEmpty() || playback.failedTitles.isNotEmpty()) item {
                    val formats = playback.learnedFailures.filterValues { it.blocksFormat }.keys
                    ListItem(
                        modifier = Modifier.clickable { scope.launch { c.playbackSettings.clearFailures() } },
                        headlineContent = { Text("Forget learned failures") },
                        supportingContent = {
                            Text(listOfNotNull(
                                "${playback.failedTitles.size} title(s) converted".takeIf { playback.failedTitles.isNotEmpty() },
                                "whole formats converted: ${formats.joinToString { capabilityLabel(it) }}".takeIf { formats.isNotEmpty() },
                            ).joinToString(" · ").ifEmpty { "Failures noted, nothing converted yet" })
                        },
                    )
                }
                item { Toggle("Allow direct play", "Files play as they are when this phone supports them. Turning this off makes the server convert everything", playback.allowDirectPlay) { v -> updatePlayback { it.copy(allowDirectPlay = v) } } }
                item { Toggle("Allow direct stream", "Repackaged without re-encoding when only the container is unsupported. Turning this off converts those too", playback.allowDirectStream) { v -> updatePlayback { it.copy(allowDirectStream = v) } } }
                heading("Formats")
                item { Text("Tap to cycle Auto → Always → Never.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp)) }
                items(CapabilityRules.videoCodecs(caps, playback) + CapabilityRules.hdrFormats(caps, playback) + CapabilityRules.audioCodecs(caps, playback), key = { it.id }) { cap ->
                    Capability(cap, playback, updatePlayback)
                }
            }
            SettingsPage.SUBTITLES -> {
                item {
                    Text("How subtitles look. Which language plays is set per show, and by your server account (Account → Server-side settings).", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                item { ScopeTag(dev.nitrate.core.settings.SettingScope.DEVICE) }
                item { SubtitleStyleEditor(playback.subtitleStyle) { st -> updatePlayback { it.copy(subtitleStyle = st) } } }
            }
            SettingsPage.DISPLAY -> {
                item { ScopeTag(dev.nitrate.core.settings.SettingScope.PROFILE, profile) }
                item { DisplayProfileRow(actions) }
                displayLook(prefs, updatePrefs)
                item { Link("What's shown", "Ratings, year, genres, descriptions, logos, progress and the rest on cards and pages") { actions.nav.navigate(SettingsPageRoute(SettingsPage.SHOWN.name)) } }
                heading("Your server account", dev.nitrate.core.settings.SettingScope.EVERYWHERE)
                item { MissingEpisodesToggle(actions) }
            }
            SettingsPage.SHOWN -> {
                item { ScopeTag(dev.nitrate.core.settings.SettingScope.PROFILE, profile) }
                shownSettings(prefs, updatePrefs)
            }
            SettingsPage.WATCHING -> {
                heading("Your display profile", dev.nitrate.core.settings.SettingScope.PROFILE, profile)
                watchingSettings(prefs, updatePrefs)
                heading("All your devices", dev.nitrate.core.settings.SettingScope.EVERYWHERE)
                item { Toggle("Hide spoilers", "Blur stills and hold back descriptions of episodes and films you haven't seen", prefs.hideSpoilers) { v -> updatePrefs { it.copy(hideSpoilers = v) } } }
                heading("This phone", dev.nitrate.core.settings.SettingScope.DEVICE)
                item { Toggle("Ask \"Still watching?\"", "After two episodes play on by themselves with nothing pressed, stop and ask. No answer: go back to where you last pressed something", playback.askStillWatching) { v -> updatePlayback { it.copy(askStillWatching = v) } } }
            }
            SettingsPage.NOTIFICATIONS -> {
                item { ScopeTag(dev.nitrate.core.settings.SettingScope.DEVICE) }
                item { NewEpisodesToggle(actions) }
                item { NewSeasonsToggle(actions) }
            }
            SettingsPage.BACKUP -> {
                item {
                    Text("This phone's playback and plugin settings, kept on your server so a new device can start the same way. No PINs; screenplays have their own backup.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                item { DeviceBackupSection(c.backups) }
            }
            SettingsPage.REMOTE -> item { RemoteTargets(actions) }
            SettingsPage.UPDATES -> {
                item { Text("New versions from Nitrate's releases. Android asks before installing.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                item { UpdatesSection(c.updater) }
            }
            SettingsPage.PLUGINS -> {
                item { Text("Built in; switch them on or off for this phone.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                pluginSettings(actions)
            }
        }
    }
}

/** Each plugin with its own settings beneath it (shown while it's on). */
private fun LazyListScope.pluginSettings(actions: AppActions) {
    val c = actions.container
    items(c.plugins.all, key = { it.id }) { plugin ->
        val scope = rememberCoroutineScope()
        val enabled by c.plugins.enabledIds.collectAsState(emptySet())
        val store = c.plugins.host.store
        val previous by store.settings(PreviousEpisodePlugin.id, PreviousEpisodeSettings()).collectAsState(PreviousEpisodeSettings())
        val writing by store.settings(WritingPlugin.id, WritingSettings()).collectAsState(WritingSettings())
                val on = plugin.id in enabled
                PluginBlock(plugin.name, plugin.description, on, { v -> scope.launch { c.plugins.setEnabled(plugin.id, v) } }) {
                    when (plugin.id) {
                        PreviousEpisodePlugin.id -> {
                            Choice(
                                "Unwatched episode check",
                                when (previous.scope) {
                                    Scope.PREVIOUS -> "The one before"
                                    Scope.SEASON -> "Whole season"
                                    Scope.SERIES -> "Whole series"
                                },
                            ) { scope.launch { store.update(PreviousEpisodePlugin.id, PreviousEpisodeSettings()) { it.copy(scope = Scope.entries[(it.scope.ordinal + 1) % Scope.entries.size]) } } }
                            Toggle("Include specials", "Specials placed before, during or after seasons count", previous.includeSpecials) { v ->
                                scope.launch { store.update(PreviousEpisodePlugin.id, PreviousEpisodeSettings()) { it.copy(includeSpecials = v) } }
                            }
                            Toggle("Spot missing episodes", "Gaps in episode or season numbering", previous.detectGaps) { v ->
                                scope.launch { store.update(PreviousEpisodePlugin.id, PreviousEpisodeSettings()) { it.copy(detectGaps = v) } }
                            }
                        }
                        WritingPlugin.id -> {
                            Toggle("Back up screenplays to the server", "Keeps a copy in your Jellyfin account for other devices", writing.backupToServer) { v ->
                                scope.launch { store.update(WritingPlugin.id, WritingSettings()) { it.copy(backupToServer = v) } }
                            }
                            ScriptSyncSettings(actions)
                        }
                        dev.nitrate.core.partners.WatchPartnersPlugin.ID -> if (partnersAvailable(actions)) PluginLink("Watch partners", "Your groups, invites, and keeping your account private") {
                            actions.nav.navigate(WatchPartnersRoute)
                        } else PluginNote("Needs the Nitrate Companion plugin on your server.")
                        dev.nitrate.core.channels.ChannelsPlugin.id -> {
                            val companion by c.companion.collectAsState()
                            if (companion?.features?.contains(dev.nitrate.core.channels.ChannelsPlugin.FEATURE) == true) PluginLink("Open Channels", "The guide; admins make and change channels there") {
                                actions.nav.navigate(ChannelsRoute)
                            } else PluginNote("Needs the Nitrate Companion plugin on your server.")
                        }
                        else -> Unit
                    }
                }
            }
}

/** A plugin: its switch, and its own settings indented beneath it while it's on. */
@Composable
private fun PluginBlock(name: String, description: String, on: Boolean, onToggle: (Boolean) -> Unit, settings: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Toggle(name, description, on, onToggle)
        if (on) Column(Modifier.padding(start = 24.dp)) { settings() }
    }
}

@Composable
private fun PluginLink(title: String, detail: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        trailingContent = { Text("›", color = MaterialTheme.colorScheme.primary) },
    )
}

@Composable
private fun PluginNote(text: String) = ListItem(headlineContent = { Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) })

/** How things look: easy mode, accent, black level, card titles. */
private fun LazyListScope.displayLook(prefs: DisplayPrefs, update: ((DisplayPrefs) -> DisplayPrefs) -> Unit) {
    item(key = "easy") {
        Toggle(
            "Easy mode", "Bigger text and buttons, only the essential tabs and player controls, and a question before anything is deleted or signed out",
            prefs.easyMode,
        ) { v -> update { it.copy(easyMode = v) } }
    }
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
    item(key = "titles") {
        Choice("Titles under cards", prefs.cardTitles.label) { update { it.copy(cardTitles = CardTitles.entries[(it.cardTitles.ordinal + 1) % CardTitles.entries.size]) } }
    }
}

/** What Nitrate does around what you play (display profile settings). */
private fun LazyListScope.watchingSettings(prefs: DisplayPrefs, update: ((DisplayPrefs) -> DisplayPrefs) -> Unit) {
    item(key = "credit_scenes") {
        Toggle(
            "Skip to scenes in the credits", "When a film has a scene during or after its credits and Nitrate knows where, jump to it when the credits start (\"Watch credits\" goes back). Off: just offer it",
            prefs.creditScenes,
        ) { v -> update { it.copy(creditScenes = v) } }
    }
    item(key = "recap") {
        // Cycles Never → 3 days → 1 week → 2 weeks → 1 month.
        val choices = dev.nitrate.core.recap.RECAP_CHOICES
        val i = choices.indexOfFirst { it.first == prefs.recapAfterDays }.coerceAtLeast(0)
        Choice("\"Last time\" recap for shows", choices[i].second, "After a break of at least this long") { update { it.copy(recapAfterDays = choices[(i + 1) % choices.size].first) } }
    }
    item(key = "recap-playlists") {
        val choices = dev.nitrate.core.recap.RECAP_CHOICES
        val i = choices.indexOfFirst { it.first == prefs.playlistRecapAfterDays }.coerceAtLeast(0)
        Choice("Recap for playlists in story order", choices[i].second, "After a break of at least this long") { update { it.copy(playlistRecapAfterDays = choices[(i + 1) % choices.size].first) } }
    }
    item(key = "described") {
        Toggle(
            "Audio description", "Start with the described track (a narrator for what's on screen) when a title has one in its language. Off: never start with one",
            prefs.describedAudio,
        ) { v -> update { it.copy(describedAudio = v) } }
    }
    item(key = "theme") { Toggle("Theme music", "Plays a title's theme quietly on its details page", prefs.themeMusic ?: false) { v -> update { it.copy(themeMusic = v) } } }
}

/** What's shown about titles on cards and pages. */
private fun LazyListScope.shownSettings(prefs: DisplayPrefs, update: ((DisplayPrefs) -> DisplayPrefs) -> Unit) {
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
    )
    toggles.forEach { (label, detail, get) ->
        item(key = "pref-$label") { Toggle(label, detail, get.first(prefs)) { v -> update { get.second(it, v) } } }
    }
}

@Composable
private fun Choice(label: String, value: String, detail: String? = null, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(label) },
        supportingContent = detail?.let { { Text(it) } },
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

/** Other devices that can be remote-controlled. */
/** New episodes of followed shows; asks for notification permission (Android 13+) when switched on. */
@Composable
private fun NewEpisodesToggle(actions: AppActions) {
    val notifier = actions.container.newEpisodes
    val scope = rememberCoroutineScope()
    val on by notifier.enabled.collectAsState(false)
    val askPermission = dev.nitrate.mobile.platform.rememberNotificationPermission { scope.launch { notifier.setEnabled(true) } }
    Toggle("New episodes", "When shows you follow (favourites, or ones you're watching) get new episodes", on) { want ->
        if (want) askPermission { scope.launch { notifier.setEnabled(true) } } else scope.launch { notifier.setEnabled(false) }
    }
}

/** New seasons of shows followed or watched before; asks for notification permission like [NewEpisodesToggle]. */
@Composable
private fun NewSeasonsToggle(actions: AppActions) {
    val notifier = actions.container.newEpisodes
    val scope = rememberCoroutineScope()
    val on by notifier.seasonsEnabled.collectAsState(false)
    val askPermission = dev.nitrate.mobile.platform.rememberNotificationPermission { scope.launch { notifier.setSeasonsEnabled(true) } }
    Toggle("New seasons", "When a show you follow or have watched before starts a new season", on) { want ->
        if (want) askPermission { scope.launch { notifier.setSeasonsEnabled(true) } } else scope.launch { notifier.setSeasonsEnabled(false) }
    }
}

/** Signs in a TV (or any device showing a Quick Connect code) as this account. */
@Composable
private fun QuickConnectRow(actions: AppActions) {
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

@Composable
private fun WatchTogetherRow(actions: AppActions) {
    var open by remember { mutableStateOf(false) }
    val group by actions.container.syncPlay.group.collectAsState()
    ListItem(
        modifier = Modifier.clickable { open = true },
        headlineContent = { Text(group?.name ?: "Start or join a group") },
        supportingContent = { Text(group?.let { "${it.participants.size} watching · ${it.state.label()}" } ?: "Then play anything and it plays for everyone") },
        trailingContent = { Text("›", color = MaterialTheme.colorScheme.primary) },
    )
    if (open) WatchTogetherDialog(actions) { open = false }
}

@Composable
private fun RemoteTargets(actions: AppActions) {
    val list = rememberRemoteDevices(actions)?.let { it.getOrNull() ?: emptyList() }
    Column {
        when {
            list == null -> ListItem(headlineContent = { Text("Looking for devices…") })
            list.isEmpty() -> ListItem(headlineContent = { Text("No devices to control") }, supportingContent = { Text("Open Nitrate or another Jellyfin app on your TV.") })
            else -> list.forEach { t ->
                ListItem(
                    modifier = Modifier.clickable { actions.nav.navigate(RemoteRoute(t.id)) },
                    headlineContent = { Text(t.deviceName ?: t.client ?: "Device") },
                    supportingContent = { Text(listOfNotNull(t.userName, t.nowPlaying?.let { "Playing ${it.episodeLabel ?: it.name}" } ?: t.client).joinToString(" · ")) },
                    trailingContent = { Text("›", color = MaterialTheme.colorScheme.primary) },
                )
            }
        }
    }
}

/** Size, colour, background, edges and position of subtitles, with a preview. */
@Composable
private fun SubtitleStyleEditor(style: dev.nitrate.core.playback.SubtitleStyle, onChange: (dev.nitrate.core.playback.SubtitleStyle) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().height(90.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(Color(0xFF2B3240)), Alignment.BottomCenter) {
            val bg = when (style.background) {
                dev.nitrate.core.playback.SubtitleBackground.NONE -> Color.Transparent
                dev.nitrate.core.playback.SubtitleBackground.SHADED -> Color(0x99000000)
                dev.nitrate.core.playback.SubtitleBackground.SOLID -> Color.Black
            }
            Text(
                "This is how subtitles will look.",
                color = Color(style.textColor),
                fontSize = (16 * style.scale).sp,
                style = MaterialTheme.typography.bodyLarge.copy(
                    shadow = if (style.edge != dev.nitrate.core.playback.SubtitleEdge.NONE) androidx.compose.ui.graphics.Shadow(Color.Black, androidx.compose.ui.geometry.Offset(2f, 2f), if (style.edge == dev.nitrate.core.playback.SubtitleEdge.OUTLINE) 2f else 6f) else null,
                ),
                modifier = Modifier.padding(bottom = (style.bottomMargin * 90).dp).background(bg).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Text("Size", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            dev.nitrate.core.playback.SubtitleStyle.SCALES.forEach { sc -> FilterChip(selected = style.scale == sc, onClick = { onChange(style.copy(scale = sc)) }, label = { Text("${(sc * 100).toInt()}%") }) }
        }
        Text("Colour", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            dev.nitrate.core.playback.SubtitleStyle.COLORS.forEach { (c, name) ->
                FilterChip(selected = style.textColor == c, onClick = { onChange(style.copy(textColor = c)) }, label = { Text(name) },
                    leadingIcon = { Box(Modifier.size(12.dp).clip(CircleShape).background(Color(c))) })
            }
        }
        Text("Background", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            dev.nitrate.core.playback.SubtitleBackground.entries.forEach { b -> FilterChip(selected = style.background == b, onClick = { onChange(style.copy(background = b)) }, label = { Text(b.label) }) }
        }
        Text("Edges", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            dev.nitrate.core.playback.SubtitleEdge.entries.forEach { e -> FilterChip(selected = style.edge == e, onClick = { onChange(style.copy(edge = e)) }, label = { Text(e.label) }) }
        }
        Text("Height from bottom", style = MaterialTheme.typography.titleSmall)
        androidx.compose.material3.Slider(value = style.bottomMargin, onValueChange = { onChange(style.copy(bottomMargin = it)) }, valueRange = 0f..0.3f)
        Text("Styled subtitles (ASS/SSA)", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            dev.nitrate.core.playback.StyledSubtitles.entries.forEach { s -> FilterChip(selected = style.styled == s, onClick = { onChange(style.copy(styled = s)) }, label = { Text(s.label) }) }
        }
        Text(style.styled.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UpdatesSection(updater: dev.nitrate.android.update.AppUpdater) {
    val state by updater.state.collectAsState()
    val auto by updater.autoCheck.collectAsState()
    Column {
        ListItem(
            headlineContent = { Text("Nitrate ${updater.versionName}") },
            supportingContent = {
                Text(
                    when (val s = state) {
                        dev.nitrate.android.update.UpdateState.Checking -> "Checking…"
                        dev.nitrate.android.update.UpdateState.UpToDate -> "You're up to date"
                        is dev.nitrate.android.update.UpdateState.Available -> "${s.release.title} is available"
                        is dev.nitrate.android.update.UpdateState.Downloading -> "Downloading ${(s.progress * 100).toInt()}%"
                        is dev.nitrate.android.update.UpdateState.Installing -> "Installing…"
                        else -> when (updater.channel) {
                            dev.nitrate.core.update.UpdateChannel.DEV -> "Dev builds"
                            dev.nitrate.core.update.UpdateChannel.STABLE -> "Releases"
                            dev.nitrate.core.update.UpdateChannel.NONE -> "Local build; doesn't update"
                        }
                    },
                )
            },
            trailingContent = {
                OutlinedButton(onClick = { updater.check() }, enabled = state !is dev.nitrate.android.update.UpdateState.Checking) { Text("Check now") }
            },
        )
        Toggle("Check automatically", "Look for a new version when Nitrate opens", auto, updater::setAutoCheck)
    }
}

/** Sends what this device can play (and what Nitrate allows) to the server's logs, for diagnosing playback. */
@Composable
private fun DeviceReportRow(actions: AppActions) {
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

/** Season lists include episodes you don't have (marked Missing, or with their air date): your server account's setting. */
@Composable
private fun MissingEpisodesToggle(actions: AppActions) {
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { on = runCatching { actions.server.showsMissingEpisodes() }.getOrNull() }
    Toggle("Show missing episodes", "Seasons list episodes you don't have, marked Missing or with their air date. Saved to your server account", on == true) { next ->
        if (on == null) return@Toggle
        on = next
        scope.launch { if (runCatching { actions.server.setShowMissingEpisodes(next) }.isFailure) on = !next }
    }
}

/**
 * Which accounts' scripts sync with their server (Nitrate Companion plugin). On this device only;
 * by default just the Primary server's. A new server never gets scripts until it's switched on.
 */
@Composable
private fun ScriptSyncSettings(actions: AppActions) {
    val shared = actions.container.sharedWriting
    var refresh by remember { mutableStateOf(0) }
    val options by androidx.compose.runtime.produceState(emptyList<dev.nitrate.android.writing.SharedWriting.SyncOption>(), refresh) {
        value = runCatching { shared.syncOptions() }.getOrDefault(emptyList())
    }
    val available by shared.available.collectAsState(false)
    val syncScope = rememberCoroutineScope()
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Sync scripts with", style = MaterialTheme.typography.titleSmall)
        Text(
            if (available) "Your scripts stay in step on all your devices, with the full history of changes. Off: they stay on this device (history kept here)."
            else "Needs the Nitrate Companion plugin on the server. Until then, scripts and their history stay on this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    options.forEach { o ->
        Toggle("${o.account.serverName} · ${o.account.userName}", if (o.primary) "Primary server" else "Another server", o.on) { v ->
            shared.setSync(o.account.key, v)
            refresh++
            syncScope.launch { runCatching { shared.autoSync() } }
        }
    }
}

