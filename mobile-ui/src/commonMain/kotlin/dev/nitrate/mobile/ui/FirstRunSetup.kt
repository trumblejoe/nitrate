package dev.nitrate.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.nitrate.android.AppContainer
import dev.nitrate.android.backup.RestorePreview
import dev.nitrate.core.backup.BackupSummary
import dev.nitrate.core.model.SavedAccount
import dev.nitrate.core.playback.SoundOutput
import dev.nitrate.core.playback.SpeakerLayout
import dev.nitrate.core.playback.Speakers
import dev.nitrate.core.setup.FirstRunSetup
import dev.nitrate.core.setup.SetupFacts
import dev.nitrate.core.setup.SetupStep
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** What the setup needs, read once as it starts. */
data class PhoneSetupData(
    val steps: List<SetupStep>,
    val accounts: List<SavedAccount>,
    val backups: List<BackupSummary>,
    val output: SoundOutput?,
)

/**
 * The first-run setup (core `setup/FirstRunSetup`) on a phone or tablet: full-screen pages, one step
 * at a time, each skippable, Back to the one before; shown when `AppContainer.setup` says it's due
 * (the first sign-in here, or Settings → Set up this device again). The backup offer, speakers
 * question and update offers wait while it shows.
 */
@Composable
fun FirstRunSetupHost(container: AppContainer) {
    val active by container.setup.active.collectAsState()
    if (!active) return
    val output by dev.nitrate.mobile.platform.rememberSoundOutput()
    val data by produceState<PhoneSetupData?>(null) {
        val accounts = runCatching { container.server.savedAccounts() }.getOrDefault(emptyList())
        val locked = runCatching { container.profiles.locked.first() }.getOrDefault(emptySet())
        // The backup offer comes after a look at the server: a few seconds at most.
        kotlinx.coroutines.withTimeoutOrNull(5_000) {
            combine(container.backups.offer, container.backups.settled) { o, s -> o != null || s }.first { it }
        }
        val backups = container.setupBackups()
        kotlinx.coroutines.delay(300)
        val settings = container.playbackSettings.settings.first()
        val o = output
        val facts = SetupFacts(
            tv = false,
            profilesToAsk = accounts.size > 1 || locked.isNotEmpty(),
            backupsOffered = backups.isNotEmpty(),
            askSound = settings.upmixStereo && Speakers.shouldAsk(o, settings.speakerLayouts, emptyMap(), dev.nitrate.android.nowMs()),
            menuSuggestions = false,
        )
        value = PhoneSetupData(FirstRunSetup.steps(facts), accounts, backups, o)
    }
    PhoneSetupFrame {
        val d = data
        if (d == null) Text("Getting ready…", style = MaterialTheme.typography.headlineSmall)
        else PhoneSetupSteps(container, d)
    }
}

/** Full screen over everything, the step in a readable column. */
@Composable
fun PhoneSetupFrame(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) { content() }
        }
    }
}

@Composable
fun PhoneSetupSteps(container: AppContainer, data: PhoneSetupData, startAt: SetupStep? = null) {
    val scope = rememberCoroutineScope()
    var index by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(startAt?.let { data.steps.indexOf(it).coerceAtLeast(0) } ?: 0) }
    val step = data.steps[index.coerceIn(data.steps.indices)]
    fun next() { if (index < data.steps.lastIndex) index++ }
    fun finish() = scope.launch { container.setup.finish() }
    dev.nitrate.mobile.platform.PlatformBackHandler(enabled = index > 0) { index-- }

    Spacer(Modifier.height(24.dp))
    FirstRunSetup.progress(data.steps, step)?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = accentColor()) }
    Text(step.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    when (step) {
        SetupStep.PROFILE -> PhoneProfileStep(container, data) { next() }
        SetupStep.COPY_SETTINGS -> PhoneCopyStep(container, data) { next() }
        SetupStep.SOUND -> PhoneSoundStep(container, data.output) { next() }
        SetupStep.MENU -> next()
        SetupStep.DONE -> PhoneDoneStep { finish() }
    }
    if (step != SetupStep.DONE) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        if (index > 0) TextButton(onClick = { index-- }) { Text("Back") }
        TextButton(onClick = {
            // Skipping the backup offer answers it as "Start fresh": nothing is pushed until it's answered.
            if (step == SetupStep.COPY_SETTINGS) scope.launch { container.backups.notNow() }
            if (step == SetupStep.SOUND) data.output?.let { o -> scope.launch { container.playbackSettings.update { it.copy(speakerAskLater = Speakers.askLater(it.speakerAskLater, o, dev.nitrate.android.nowMs())) } } }
            next()
        }) { Text("Skip") }
        TextButton(onClick = { if (step == SetupStep.COPY_SETTINGS) scope.launch { container.backups.notNow() }; finish() }) { Text("Skip the rest") }
    }
}

@Composable
private fun SetupIntro(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun PhoneProfileStep(container: AppContainer, data: PhoneSetupData, onNext: () -> Unit) {
    val scope = rememberCoroutineScope()
    val me = data.accounts.firstOrNull { it.key == container.server.currentAccountKey }
    var unlocking by remember { mutableStateOf<SavedAccount?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    SetupIntro("This device is signed in as ${me?.userName ?: "you"}. Others in the house can have their own profile here.")
    PillButton("Continue as ${me?.userName ?: "me"}", null, primary = true, onClick = onNext, modifier = Modifier.fillMaxWidth())
    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    data.accounts.filter { it.key != me?.key }.forEach { a ->
        ListItem(
            headlineContent = { Text(a.userName) },
            supportingContent = { Text(a.serverName) },
            modifier = Modifier.pressable({
                scope.launch {
                    if (container.profiles.hasPin(a.key)) unlocking = a
                    else if (container.switchAccount(a.key) == dev.nitrate.core.model.SessionState.VALID) onNext() else message = "Couldn't open ${a.userName} right now."
                }
            }),
        )
    }
    unlocking?.let { a ->
        var pin by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { unlocking = null },
            title = { Text("${a.userName}'s PIN") },
            text = { OutlinedTextField(pin, { pin = it.filter(Char::isDigit) }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        if (!container.profiles.check(a.key, pin)) { message = "That PIN isn't right."; unlocking = null; return@launch }
                        unlocking = null
                        if (container.switchAccount(a.key) == dev.nitrate.core.model.SessionState.VALID) onNext() else message = "Couldn't open ${a.userName} right now."
                    }
                }) { Text("Open") }
            },
            dismissButton = { TextButton(onClick = { unlocking = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PhoneCopyStep(container: AppContainer, data: PhoneSetupData, onNext: () -> Unit) {
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<RestorePreview?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    SetupIntro("Your other devices' playback and plugin settings are saved on your server. Copy one here (you'll see what changes first), or start fresh.")
    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    data.backups.forEach { b ->
        ListItem(
            headlineContent = { Text(backupTitle(b, container.backups.deviceId), style = MaterialTheme.typography.titleMedium) },
            supportingContent = { Text("${b.appLabel} · ${b.model} · saved ${backupAge(b.savedAtMs)}") },
            modifier = Modifier.pressable({ message = null; scope.launch { preview = runCatching { container.backups.preview(b) }.getOrNull() ?: run { message = "Couldn't read that backup"; null } } }),
        )
    }
    PillButton("Start fresh", null, primary = false, onClick = { scope.launch { container.backups.notNow(); onNext() } }, modifier = Modifier.fillMaxWidth())
    preview?.let { p -> DifferencesDialog(p, onApply = { scope.launch { container.backups.apply(p); preview = null; onNext() } }, onBack = { preview = null }) }
}

@Composable
private fun PhoneSoundStep(container: AppContainer, output: SoundOutput?, onNext: () -> Unit) {
    val scope = rememberCoroutineScope()
    if (output == null) { SetupIntro("No sound output to set up."); PillButton("Next", null, primary = true, onClick = onNext); return }
    SetupIntro("What's connected to ${output.label}? Stereo films and episodes can play on all your speakers. Change it any time in Settings → Picture & sound.")
    SpeakerLayout.entries.forEach { l ->
        ListItem(
            headlineContent = { Text(l.label, style = MaterialTheme.typography.titleMedium) },
            supportingContent = { Text(l.detail) },
            modifier = Modifier.pressable({ scope.launch { container.playbackSettings.update { it.copy(speakerLayouts = it.speakerLayouts + (output.key to l)) }; onNext() } }),
        )
    }
}

@Composable
private fun PhoneDoneStep(onFinish: () -> Unit) {
    SetupIntro("You can change any of this later:")
    listOf(
        "Copying another device's settings" to "Settings → Device backup",
        "Your speakers" to "Settings → Picture & sound",
        "This setup" to "Settings → Set up this device again",
    ).forEach { (what, where) ->
        Column {
            Text(what, style = MaterialTheme.typography.titleMedium)
            Text(where, style = MaterialTheme.typography.bodyLarge, color = accentColor())
        }
    }
    PillButton("Start watching", null, primary = true, onClick = onFinish, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
}
