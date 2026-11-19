package dev.nitrate.tv.ui.settings

import dev.nitrate.core.playback.SubtitleEdge
import dev.nitrate.core.playback.SubtitleBackground
import dev.nitrate.core.playback.SubtitleStyle
import dev.nitrate.core.playback.capabilityLabel
import dev.nitrate.core.playback.formatBitrate
import dev.nitrate.core.playback.SoundMode
import dev.nitrate.core.playback.soundMode
import dev.nitrate.core.playback.withSoundMode
import androidx.compose.foundation.background

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.focusable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
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

/** The TV's playback settings pages: Settings → Picture & sound, its Advanced page, and Subtitles. */
enum class PlaybackPage { MAIN, ADVANCED, SUBTITLES }

@Composable
fun PlaybackSettingsScreen(
    store: PlaybackSettingsStore,
    capabilities: DeviceCapabilities,
    page: PlaybackPage = PlaybackPage.MAIN,
    onAdvanced: () -> Unit = {},
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
            Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when (page) {
                        PlaybackPage.MAIN -> "Picture & sound"
                        PlaybackPage.ADVANCED -> "Picture & sound: Advanced"
                        PlaybackPage.SUBTITLES -> "Subtitles"
                    },
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    when (page) {
                        PlaybackPage.MAIN -> "${capabilities.model} · display ${capabilities.displayWidth}×${capabilities.displayHeight}."
                        PlaybackPage.ADVANCED -> "For troubleshooting and unusual setups. Leave these as they are unless something plays wrong; \"Why converting?\" in the player and \"How it plays\" on a title suggest the right change."
                        PlaybackPage.SUBTITLES -> "How subtitles look. Which language plays is set per show, and by your server account (Settings → Server-side settings)."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ScopeTag(dev.nitrate.core.settings.SettingScope.DEVICE)
            }
        }
        when (page) {
            PlaybackPage.MAIN -> mainPage(settings, update, onAdvanced, first)
            PlaybackPage.ADVANCED -> advancedPage(settings, capabilities, update, store, scope, first)
            PlaybackPage.SUBTITLES -> subtitlesPage(settings, update, first)
        }
    }
}

private fun LazyListScope.mainPage(
    settings: PlaybackSettings,
    update: ((PlaybackSettings) -> PlaybackSettings) -> Unit,
    onAdvanced: () -> Unit,
    first: FocusRequester,
) {
    section("Audio")
    item {
        val mode = settings.soundMode
        ListItem(
            selected = false,
            onClick = { update { it.withSoundMode(SoundMode.entries[(mode.ordinal + 1) % SoundMode.entries.size]) } },
            headlineContent = { Text("Sound") },
            supportingContent = { Text(mode.detail + ". " + SoundMode.RECEIVER_NOTE) },
            trailingContent = { Text("${mode.label}  ›", color = MaterialTheme.colorScheme.primary) },
            modifier = Modifier.focusRequester(first),
        )
    }
    item {
        val output by dev.nitrate.android.playback.SoundOutputs.current(androidx.compose.ui.platform.LocalContext.current).collectAsState()
        dev.nitrate.tv.ui.player.SpeakersSettings(settings, output, update)
    }
    item {
        // Coarse steps here; the player's menu fine-tunes while you listen.
        val steps = listOf(-300, -200, -100, -50, 0, 50, 100, 200, 300)
        ChoiceRow("Audio sync", dev.nitrate.core.playback.AudioSync.label(settings.audioDelayMs), "Change it if lips and voices don't line up, usually because a soundbar or receiver adds lag. Fine-tune in the player's More menu.") {
            update { it.copy(audioDelayMs = steps.firstOrNull { s -> s > it.audioDelayMs } ?: steps.first()) }
        }
    }
    section("Picture")
    item {
        val m = settings.frameRateMatch
        ChoiceRow("Match frame rate", m.label, m.detail + ". Keep it on for smoother motion in films.") {
            val all = dev.nitrate.core.playback.FrameRateMatch.entries
            update { it.copy(frameRateMatch = all[(m.ordinal + 1) % all.size]) }
        }
    }
    item {
        var open by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
        ListItem(
            selected = false,
            onClick = { open = true },
            headlineContent = { Text("How refresh rate works") },
            supportingContent = { Text("Why films can stutter on a TV, and what each choice does") },
            trailingContent = { Text("ⓘ", color = MaterialTheme.colorScheme.primary) },
        )
        if (open) RefreshRateInfo { open = false }
    }
    item {
        ChoiceRow("Maximum bitrate", formatBitrate(settings.maxBitrate), "No limit plays every file as it is (a fast file on a slow network buffers rather than being converted). Set a cap only if you'd rather the server convert big files down, e.g. away from home.") {
            val list = PlaybackSettings.BITRATES
            update { it.copy(maxBitrate = list[(list.indexOf(it.maxBitrate) + 1).mod(list.size)]) }
        }
    }
    item(key = "advanced") {
        ListItem(
            selected = false,
            onClick = onAdvanced,
            headlineContent = { Text("Advanced") },
            supportingContent = { Text("Tunneling, Dolby Vision profile 7, 3D, resolution limit, direct play, and the formats this TV plays") },
            trailingContent = { Text("›", color = MaterialTheme.colorScheme.primary) },
        )
    }
}

private fun LazyListScope.advancedPage(
    settings: PlaybackSettings,
    capabilities: DeviceCapabilities,
    update: ((PlaybackSettings) -> PlaybackSettings) -> Unit,
    store: PlaybackSettingsStore,
    scope: kotlinx.coroutines.CoroutineScope,
    first: FocusRequester,
) {
    section("Picture")
    item {
        ToggleRow(
            "Tunneled playback", settings.tunneling,
            "Video and sound go to the TV's hardware together. Turn it on only if video stutters or sound drifts out of sync, and keep it only if that helps; some TVs stutter with it. Takes effect the next time something plays.",
            Modifier.focusRequester(first),
        ) { v -> update { it.copy(tunneling = v) } }
    }
    item {
        val how = CapabilityRules.profile7(capabilities, settings)
        ToggleRow(
            "Dolby Vision profile 7 as 8.1", settings.convertProfile7,
            "UHD Blu-ray Dolby Vision (profile 7) is converted to profile 8.1 while it plays, so this TV shows it as Dolby Vision rather than HDR10. Turn off only if those films look wrong. Now plays ${how.label}.",
        ) { v -> update { it.copy(convertProfile7 = v) } }
    }
    item {
        val t = settings.threeD
        ChoiceRow("3D films", t.label, t.detail + ". Takes effect the next time something plays.") {
            val all = dev.nitrate.core.playback.ThreeDMode.entries
            update { it.copy(threeD = all[(t.ordinal + 1) % all.size]) }
        }
    }
    item {
        ChoiceRow("Maximum resolution", settings.maxResolution.label, "Larger video is converted down by the server. Only for a TV that struggles with 4K") {
            update { it.copy(maxResolution = MaxResolution.entries[(it.maxResolution.ordinal + 1) % MaxResolution.entries.size]) }
        }
    }
    section("Troubleshooting")
    item {
        ToggleRow("Learn from failures", settings.useLearnedFailures, "A title that failed to play here as it is gets converted next time; a whole format only after 3 different titles fail. Forgotten after 60 days. Keep on") { v ->
            update { it.copy(useLearnedFailures = v) }
        }
    }
    if (settings.learnedFailures.isNotEmpty() || settings.failedTitles.isNotEmpty()) item(key = "forget") {
        val formats = settings.learnedFailures.filterValues { it.blocksFormat }.keys
        ListItem(
            selected = false,
            onClick = { scope.launch { store.clearFailures() } },
            headlineContent = { Text("Forget learned failures") },
            supportingContent = {
                Text(listOfNotNull(
                    "${settings.failedTitles.size} title(s) converted".takeIf { settings.failedTitles.isNotEmpty() },
                    "whole formats converted: ${formats.joinToString()}".takeIf { formats.isNotEmpty() },
                ).joinToString(" · ").ifEmpty { "Failures noted, nothing converted yet" } + ". Try them as they are again")
            },
        )
    }
    item { ToggleRow("Allow direct play", settings.allowDirectPlay, "Files play as they are when the TV supports them. Turning this off makes the server convert everything") { v -> update { it.copy(allowDirectPlay = v) } } }
    item { ToggleRow("Allow direct stream", settings.allowDirectStream, "Repackaged without re-encoding when only the container is unsupported. Turning this off converts those too") { v -> update { it.copy(allowDirectStream = v) } } }

        section("Video codecs", "Select to cycle Auto → Always → Never. Long-press to clear limits and failures.")
        items(CapabilityRules.videoCodecs(capabilities, settings), key = { it.id }) { cap ->
            val c = capabilities.video[cap.id]
            val reported = c?.let {
                "${it.maxWidth}×${it.maxHeight}" + (if (it.tenBit) ", 10-bit" else ", 8-bit") + (if (it.hardware) ", hardware" else ", software") + " (${it.decoderName})" +
                    (if (it.softwareTenBit) "; 10-bit in software up to 1080p" else "")
            }
            val limits = listOfNotNull(
                settings.codecMaxHeight[cap.id]?.let { "limited to ${it}p" },
                "8-bit only".takeIf { cap.id in settings.eightBitOnly },
                "10-bit allowed (passed the test)".takeIf { cap.id in settings.tenBitAllowed },
                "software decoder: up to 1080p unless set to Always".takeIf { c != null && !c.hardware && settings.overrides[cap.id] != dev.nitrate.core.playback.Override.ALWAYS },
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
        item(key = "passthrough-extras") {
            // What the receiver takes as it is beyond the basic formats (sent automatically when it can).
            val extras = dev.nitrate.core.playback.DeviceCapabilities.PASSTHROUGH_EXTRAS.joinToString("  ·  ") { e ->
                "$e: " + if (e in capabilities.passthroughExtras) "passed through" else "no"
            }
            ListItem(selected = false, onClick = {}, headlineContent = { Text("Lossless and object audio") },
                supportingContent = { Text("$extras. When not, DTS-HD goes out as its DTS core and Atmos in E-AC-3 as plain E-AC-3.") })
        }

}

private fun LazyListScope.subtitlesPage(
    settings: PlaybackSettings,
    update: ((PlaybackSettings) -> PlaybackSettings) -> Unit,
    first: FocusRequester,
) {
        item {
            val st = settings.subtitleStyle
            ChoiceRow("Size", "${(st.scale * 100).toInt()}%", null, Modifier.focusRequester(first)) {
                val list = SubtitleStyle.SCALES
                update { it.copy(subtitleStyle = st.copy(scale = list[(list.indexOf(st.scale) + 1).mod(list.size)])) }
            }
        }
        item {
            val st = settings.subtitleStyle
            ChoiceRow("Colour", SubtitleStyle.COLORS.firstOrNull { it.first == st.textColor }?.second ?: "Custom", null) {
                val list = SubtitleStyle.COLORS.map { it.first }
                update { it.copy(subtitleStyle = st.copy(textColor = list[(list.indexOf(st.textColor) + 1).mod(list.size)])) }
            }
        }
        item {
            val st = settings.subtitleStyle
            ChoiceRow("Background", st.background.label, null) {
                update { it.copy(subtitleStyle = st.copy(background = SubtitleBackground.entries[(st.background.ordinal + 1) % SubtitleBackground.entries.size])) }
            }
        }
        item {
            val st = settings.subtitleStyle
            ChoiceRow("Edges", st.edge.label, null) {
                update { it.copy(subtitleStyle = st.copy(edge = SubtitleEdge.entries[(st.edge.ordinal + 1) % SubtitleEdge.entries.size])) }
            }
        }
        item {
            val st = settings.subtitleStyle
            val heights = listOf(0.02f, 0.05f, 0.08f, 0.12f, 0.18f)
            ChoiceRow("Height from bottom", "${(st.bottomMargin * 100).toInt()}%", null) {
                update { it.copy(subtitleStyle = st.copy(bottomMargin = heights.firstOrNull { h -> h > st.bottomMargin + 0.001f } ?: heights.first())) }
            }
        }
        item {
            val styled = settings.subtitleStyle.styled
            ChoiceRow("Styled subtitles (ASS/SSA)", styled.label, styled.detail) {
                val all = dev.nitrate.core.playback.StyledSubtitles.entries
                update { it.copy(subtitleStyle = it.subtitleStyle.copy(styled = all[(styled.ordinal + 1) % all.size])) }
            }
        }

}

/** Where the settings on a page apply: this TV only, its display profile, or every device. */
@Composable
fun ScopeTag(scope: dev.nitrate.core.settings.SettingScope, profile: String? = null) {
    Text(
        scope.label("TV", profile),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** Settings → Updates: check now, and whether to check when Nitrate opens. */
@Composable
fun UpdatesScreen(updater: dev.nitrate.android.update.AppUpdater) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    LazyColumn(contentPadding = PaddingValues(horizontal = PagePadding, vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Updates", style = MaterialTheme.typography.headlineMedium)
                Text("New versions from Nitrate's releases. Android asks before installing.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { UpdateRows(updater, Modifier.focusRequester(first)) }
    }
}

@Composable
private fun UpdateRows(updater: dev.nitrate.android.update.AppUpdater, modifier: Modifier = Modifier) {
    val state by updater.state.collectAsState()
    val auto by updater.autoCheck.collectAsState()
    Column {
        ChoiceRow(
            "Check for updates",
            "Nitrate ${updater.versionName}",
            when (val s = state) {
                dev.nitrate.android.update.UpdateState.Checking -> "Checking…"
                dev.nitrate.android.update.UpdateState.UpToDate -> "You're up to date"
                is dev.nitrate.android.update.UpdateState.Available -> "${s.release.title} is available"
                is dev.nitrate.android.update.UpdateState.Downloading -> "Downloading ${(s.progress * 100).toInt()}%"
                else -> null
            },
            modifier,
        ) { updater.check() }
        ToggleRow("Check automatically", auto, "Look for a new version when Nitrate opens", onToggle = updater::setAutoCheck)
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
                    failedTitles = s.failedTitles.filterValues { f -> f.capability != cap.id },
                    codecMaxHeight = s.codecMaxHeight - cap.id,
                    eightBitOnly = s.eightBitOnly - cap.id,
                    tenBitAllowed = s.tenBitAllowed - cap.id,
                )
            }
        },
        headlineContent = { Text(capabilityLabel(cap.id)) },
        supportingContent = {
            Column {
                Text("TV reports: " + (reported ?: "not supported"), style = MaterialTheme.typography.bodySmall)
                if (limits.isNotEmpty()) Text(limits.joinToString(" · ").replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                failure?.let {
                    val titles = it.items.size.takeIf { n -> n > 0 } ?: it.count
                    Text("Failed to play as it is with $titles title(s) (${it.lastError})" + if (it.blocksFormat) ": all converted" else ": only those converted",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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
private fun ChoiceRow(label: String, value: String, subtitle: String?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    ListItem(
        selected = false,
        onClick = onClick,
        modifier = modifier,
        headlineContent = { Text(label) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Text("$value  ›", color = MaterialTheme.colorScheme.primary) },
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, subtitle: String, modifier: Modifier = Modifier, onToggle: (Boolean) -> Unit) {
    ListItem(
        selected = false,
        onClick = { onToggle(!checked) },
        modifier = modifier,
        headlineContent = { Text(label) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    )
}

/**
 * What refresh rate does to films, in plain words: why 23.976 fps stutters at 59.94 Hz, what
 * each "Match frame rate" choice does, the near match Nitrate falls back to, and where else to look.
 */
@Composable
internal fun RefreshRateInfo(onDismiss: () -> Unit) {
    val close = remember { androidx.compose.ui.focus.FocusRequester() }
    dev.nitrate.tv.ui.lists.TvDialog(onDismiss, width = 760) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("How refresh rate works", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            androidx.tv.material3.Button(onClick = onDismiss, modifier = Modifier.focusRequester(close)) { Text("Close") }
        }
        // Each part takes focus in turn, so ▲▼ read through it (the dialog scrolls to the focused part).
        @Composable fun Part(title: String, body: String) {
            var focused by remember { mutableStateOf(false) }
            Column(
                Modifier.fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused }
                    .focusable()
                    .background(if (focused) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f) else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Part(
            "Why films can stutter",
            "Most films and many shows are made at 23.976 frames a second; TVs usually run at 59.94 or 60 times a second. " +
                "59.94 ÷ 23.976 = 2.5, so frames can't be shown for equal times: one stays for 3 refreshes, the next for 2 (\"3:2\"). " +
                "Each frame lasts 50 or 33 ms in turn, which shows as a slight shudder on slow camera pans, called judder.",
        )
        Part(
            "What matching does",
            "With Match frame rate on Always, the player switches the TV to a rate the video divides into evenly: 23.976 Hz for a film " +
                "(else 47.952 or 119.88 Hz), 50 Hz for 25 fps, 59.94 Hz for 29.97 fps. Every frame is then shown for the same time and motion is smooth. " +
                "The screen goes black for a second or two while the TV changes mode, with playback held; leaving the player gives the TV its own rate back.",
        )
        Part(
            "When the TV has no exact rate",
            "Some TVs offer 24 Hz but not 23.976 Hz. Nitrate then uses 24 Hz: it's 0.1% fast, so one frame repeats about every 42 seconds, " +
                "far less noticeable than 3:2 judder all the time. A TV already at a near rate isn't switched.",
        )
        Part(
            "The three choices",
            "Off: the TV keeps its own rate. Only when seamless: the player asks for a match, but only where the TV can change without a blank screen " +
                "(many TVs can't, so nothing changes). Always: switches for each video, with the short blank screen.",
        )
        Part(
            "For one film, and checking",
            "More → Settings in the player changes this for what's playing (DEFAULT marks this setting) and says how the current video looks at the TV's rate. " +
                "The player's Info shows the TV's mode and warns about judder.",
        )
        Part(
            "Fire TV and other boxes",
            "Fire TV has its own Match Original Frame Rate switch (Settings → Display & Sounds → Display). If Always doesn't change anything, turn that on too. " +
                "Some other boxes have a similar \"auto frame rate\" setting.",
        )
    }
    LaunchedEffect(Unit) { runCatching { close.requestFocus() } }
}
