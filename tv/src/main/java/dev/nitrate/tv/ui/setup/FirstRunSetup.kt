package dev.nitrate.tv.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import dev.nitrate.android.AppContainer
import dev.nitrate.android.backup.RestorePreview
import dev.nitrate.core.backup.BackupSummary
import dev.nitrate.core.pages.DisplayPrefs
import dev.nitrate.core.pages.GroupSuggestion
import dev.nitrate.core.pages.MenuGroups
import dev.nitrate.core.pages.MenuLayout
import dev.nitrate.core.pages.MenuStops
import dev.nitrate.core.playback.SoundOutput
import dev.nitrate.core.playback.SpeakerLayout
import dev.nitrate.core.playback.Speakers
import dev.nitrate.core.setup.FirstRunSetup
import dev.nitrate.core.setup.SetupFacts
import dev.nitrate.core.setup.SetupStep
import dev.nitrate.tv.ui.home.MenuCatalog
import dev.nitrate.tv.ui.pages.changePrefs
import dev.nitrate.tv.ui.pages.rememberStoredPrefs
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** What the setup needs, read once as it starts. */
internal data class SetupData(
    val steps: List<SetupStep>,
    val accounts: List<dev.nitrate.core.model.SavedAccount>,
    val backups: List<BackupSummary>,
    val output: SoundOutput?,
    val suggestions: List<GroupSuggestion>,
)

/**
 * The first-run setup (core `setup/FirstRunSetup`), full screen over everything once signed in, when
 * `AppContainer.setup` says it's due (the first sign-in on this TV, or Settings → Set up this device
 * again). One step at a time, each skippable, Back to the one before; the backup offer, the speakers
 * question and update offers wait while it shows.
 */
@Composable
fun FirstRunSetupHost(container: AppContainer, catalog: MenuCatalog?, onSignInAgain: (dev.nitrate.core.model.SavedAccount) -> Unit) {
    val active by container.setup.active.collectAsState()
    if (!active) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val output by dev.nitrate.android.playback.SoundOutputs.current(context).collectAsState()
    val prefs = rememberStoredPrefs(container)
    val data by produceState<SetupData?>(null) {
        value = gather(container, catalog, prefs) { output }
    }
    val d = data
    SetupFrame {
        if (d == null) {
            Text("Getting ready…", style = MaterialTheme.typography.headlineMedium)
            return@SetupFrame
        }
        SetupSteps(container, d, catalog, onSignInAgain)
    }
}

/** Reads which steps apply: profiles, the backup offer (waiting a few seconds for it), the speakers, the menu. */
internal suspend fun gather(container: AppContainer, catalog: MenuCatalog?, prefs: DisplayPrefs, output: () -> SoundOutput?): SetupData {
    val accounts = runCatching { container.server.savedAccounts() }.getOrDefault(emptyList())
    val locked = runCatching { container.profiles.locked.first() }.getOrDefault(emptySet())
    // The backup offer comes after a look at the server: a few seconds at most.
    kotlinx.coroutines.withTimeoutOrNull(5_000) {
        combine(container.backups.offer, container.backups.settled) { o, s -> o != null || s }.first { it }
    }
    val backups = container.setupBackups()
    // The sound output is known a moment after start.
    kotlinx.coroutines.delay(300)
    val settings = container.playbackSettings.settings.first()
    val o = output()
    val askSound = settings.upmixStereo && Speakers.shouldAsk(o, settings.speakerLayouts, emptyMap(), System.currentTimeMillis())
    val libraries = catalog?.entries?.filterKeys { it.startsWith("library:") }?.mapValues { it.value.label }.orEmpty()
    val suggestions = MenuGroups.suggest(libraries, prefs.tvMenuGroups, prefs.tvGroupSuggestionsDismissed)
    val facts = SetupFacts(
        tv = true,
        profilesToAsk = accounts.size > 1 || locked.isNotEmpty(),
        backupsOffered = backups.isNotEmpty(),
        askSound = askSound,
        menuSuggestions = suggestions.isNotEmpty(),
    )
    return SetupData(FirstRunSetup.steps(facts), accounts, backups, o, suggestions)
}

/** Full screen, dark, the step in a readable column. */
@Composable
internal fun SetupFrame(content: @Composable () -> Unit) {
    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0B0D12)), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 1100.dp).fillMaxWidth().padding(horizontal = 72.dp, vertical = 48.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) { content() }
        }
    }
}

@Composable
internal fun SetupSteps(container: AppContainer, data: SetupData, catalog: MenuCatalog?, onSignInAgain: (dev.nitrate.core.model.SavedAccount) -> Unit, startAt: SetupStep? = null) {
    val scope = rememberCoroutineScope()
    var index by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(startAt?.let { data.steps.indexOf(it).coerceAtLeast(0) } ?: 0) }
    val step = data.steps[index.coerceIn(data.steps.indices)]
    fun next() { if (index < data.steps.lastIndex) index++ }
    fun finish() = scope.launch { container.setup.finish() }
    BackHandler(enabled = index > 0) { index-- }
    val primary = remember(step) { FocusRequester() }

    FirstRunSetup.progress(data.steps, step)?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    Text(step.title, style = MaterialTheme.typography.displaySmall)
    when (step) {
        SetupStep.PROFILE -> ProfileStep(container, data, primary, onSignInAgain) { next() }
        SetupStep.COPY_SETTINGS -> CopyStep(container, data, primary) { next() }
        SetupStep.SOUND -> SoundStep(container, data.output, primary) { next() }
        SetupStep.MENU -> MenuStep(container, data, catalog, primary) { next() }
        SetupStep.DONE -> DoneStep(primary) { finish() }
    }
    // The way out of every step: on to the next (or, at the start, out of the setup altogether).
    if (step != SetupStep.DONE) Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 8.dp)) {
        if (index > 0) OutlinedButton(onClick = { index-- }) { Text("Back") }
        OutlinedButton(onClick = {
            // Skipping the backup offer answers it as "Start fresh": nothing is pushed until it's answered.
            if (step == SetupStep.COPY_SETTINGS) scope.launch { container.backups.notNow() }
            if (step == SetupStep.SOUND) data.output?.let { o -> scope.launch { container.playbackSettings.update { it.copy(speakerAskLater = Speakers.askLater(it.speakerAskLater, o, System.currentTimeMillis())) } } }
            next()
        }) { Text("Skip") }
        OutlinedButton(onClick = { if (step == SetupStep.COPY_SETTINGS) scope.launch { container.backups.notNow() }; finish() }) { Text("Skip the rest") }
    }
    LaunchedEffect(step) { kotlinx.coroutines.delay(80); runCatching { primary.requestFocus() } }
}

@Composable
private fun Intro(text: String) = Text(text, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun ProfileStep(container: AppContainer, data: SetupData, primary: FocusRequester, onSignInAgain: (dev.nitrate.core.model.SavedAccount) -> Unit, onNext: () -> Unit) {
    val current = container.server.currentAccountKey
    val me = data.accounts.firstOrNull { it.key == current }
    var switching by remember { mutableStateOf(false) }
    Intro("This TV is signed in as ${me?.userName ?: "you"}. Others in the house can have their own profile here.")
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Button(onClick = onNext, modifier = Modifier.focusRequester(primary)) { Text("Continue as ${me?.userName ?: "me"}") }
        if (data.accounts.size > 1) OutlinedButton(onClick = { switching = true }) { Text("Someone else is watching") }
    }
    if (switching) dev.nitrate.tv.ui.profiles.QuickSwitchDialog(container, onOpened = onNext, onSignInAgain = onSignInAgain, onDismiss = { switching = false })
}

@Composable
private fun CopyStep(container: AppContainer, data: SetupData, primary: FocusRequester, onNext: () -> Unit) {
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<RestorePreview?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    Intro("Your other devices' playback and plugin settings are saved on your server. Copy one here (you'll see what changes first), or start fresh.")
    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    data.backups.forEachIndexed { i, b ->
        ListItem(
            selected = false,
            onClick = { message = null; scope.launch { preview = runCatching { container.backups.preview(b) }.getOrNull() ?: run { message = "Couldn't read that backup"; null } } },
            headlineContent = { Text(dev.nitrate.tv.ui.settings.title(b, container.backups.deviceId), style = MaterialTheme.typography.titleLarge) },
            supportingContent = { Text("${b.appLabel} · ${b.model} · saved ${dev.nitrate.tv.ui.settings.age(b.savedAtMs)}") },
            modifier = Modifier.widthIn(max = 900.dp).then(if (i == 0) Modifier.focusRequester(primary) else Modifier),
        )
    }
    Button(onClick = { scope.launch { container.backups.notNow(); onNext() } }) { Text("Start fresh") }
    preview?.let { p ->
        dev.nitrate.tv.ui.settings.DifferencesDialog(p, onApply = { scope.launch { container.backups.apply(p); preview = null; onNext() } }, onBack = { preview = null })
    }
}

@Composable
private fun SoundStep(container: AppContainer, output: SoundOutput?, primary: FocusRequester, onNext: () -> Unit) {
    val scope = rememberCoroutineScope()
    val o = output ?: return run { Intro("No sound output to set up."); Button(onClick = onNext, modifier = Modifier.focusRequester(primary)) { Text("Next") } }
    Intro("What's connected to ${o.label}? Stereo films and episodes can play on all your speakers. Change it any time in Settings → Picture & sound.")
    SpeakerLayout.entries.chunked(3).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            pair.forEach { l ->
                ListItem(
                    selected = false,
                    onClick = { scope.launch { container.playbackSettings.update { it.copy(speakerLayouts = it.speakerLayouts + (o.key to l)) }; onNext() } },
                    headlineContent = { Text(l.label, style = MaterialTheme.typography.titleLarge) },
                    supportingContent = { Text(l.detail, maxLines = 3) },
                    modifier = Modifier.weight(1f).then(if (l == SpeakerLayout.FIVE_ONE) Modifier.focusRequester(primary) else Modifier),
                )
            }
        }
    }
}

@Composable
private fun MenuStep(container: AppContainer, data: SetupData, catalog: MenuCatalog?, primary: FocusRequester, onNext: () -> Unit) {
    val prefs = rememberStoredPrefs(container)
    var chosen by remember { mutableStateOf(data.suggestions.map { it.key }.toSet()) }
    val c = catalog
    Intro("Libraries that belong together can share one menu stop that opens onto them, so the menu stays short. Things you use less wait in More.")
    data.suggestions.forEachIndexed { i, s ->
        val on = s.key in chosen
        ListItem(
            selected = false,
            onClick = { chosen = if (on) chosen - s.key else chosen + s.key },
            headlineContent = { Text("Make “${s.name}”", style = MaterialTheme.typography.titleLarge) },
            supportingContent = { Text(s.stops.mapNotNull { c?.entries?.get(it)?.label }.joinToString(", ")) },
            trailingContent = { Switch(checked = on, onCheckedChange = null) },
            modifier = Modifier.widthIn(max = 900.dp).then(if (i == 0) Modifier.focusRequester(primary) else Modifier),
        )
    }
    if (c != null) {
        val after = tidied(prefs, c, data.suggestions.filter { it.key in chosen })
        val available = c.available(after)
        val arranged = MenuStops.linear(MenuStops.arrange(after.tvMenuStops, available, c.defaults))
        val more = MenuLayout.more(after.tvMoreStops, c.moreDefaults, available, after.tvMenuGroups)
        val main = MenuLayout.main(arranged, after.tvMenuGroups, more)
        Text("Your menu: " + main.mapNotNull { c.entry(it, after)?.label }.joinToString("  ·  "), style = MaterialTheme.typography.titleMedium)
        if (more.isNotEmpty()) Text("In More: " + more.mapNotNull { c.entry(it, after)?.label }.joinToString(", "), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Button(onClick = {
            if (c != null) container.changePrefs { p -> tidied(p, c, data.suggestions.filter { it.key in chosen }) }
            onNext()
        }, enabled = chosen.isNotEmpty()) { Text("Tidy it") }
        OutlinedButton(onClick = onNext) { Text("Keep it as it is") }
    }
}

/** [prefs] with the [chosen] suggestions made into groups, where their first library was in the menu. */
private fun tidied(prefs: DisplayPrefs, catalog: MenuCatalog, chosen: List<GroupSuggestion>): DisplayPrefs = chosen.fold(prefs) { p, s ->
    val arranged = MenuStops.linear(MenuStops.arrange(p.tvMenuStops, catalog.available(p), catalog.defaults))
    MenuGroups.make(p, s.name, s.stops, arranged)
}

@Composable
private fun DoneStep(primary: FocusRequester, onFinish: () -> Unit) {
    Intro("You can change any of this later:")
    listOf(
        "Copying another device's settings" to "Settings → Device backup",
        "Your speakers" to "Settings → Picture & sound",
        "The menu" to "Settings → Display → Menu stops (or the pencil in the menu)",
        "This setup" to "Settings → Set up this device again",
    ).forEach { (what, where) ->
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(what, style = MaterialTheme.typography.titleLarge, modifier = Modifier.width(420.dp))
            Text(where, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
    Button(onClick = onFinish, modifier = Modifier.padding(top = 12.dp).focusRequester(primary)) { Text("Start watching") }
}
