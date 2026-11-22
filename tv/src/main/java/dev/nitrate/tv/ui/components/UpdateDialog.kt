package dev.nitrate.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.nitrate.android.update.AppUpdater
import dev.nitrate.android.update.UpdateState
import dev.nitrate.core.update.AppRelease

/** Offers a newer Nitrate from the releases and shows the download until Android's installer takes over. */
@Composable
fun UpdatePrompt(updater: AppUpdater) {
    val state by updater.state.collectAsState()
    UpdateDialog(state, onUpdate = updater::install, onLater = updater::skip, onDismiss = updater::dismiss, onReoffer = updater::offer)
}

private data class Choice(val label: String, val action: () -> Unit)

@Composable
internal fun UpdateDialog(state: UpdateState, onUpdate: (AppRelease) -> Unit, onLater: (AppRelease) -> Unit, onDismiss: () -> Unit, onReoffer: (AppRelease) -> Unit = {}) {
    val context = LocalContext.current
    when (state) {
        is UpdateState.Available -> {
            val notesFocus = remember { FocusRequester() }
            val hasNotes = state.release.notes.isNotEmpty()
            Panel(
                "Update available", { onLater(state.release) },
                listOf(Choice("Update now") { onUpdate(state.release) }, Choice("Later") { onLater(state.release) }),
                upFromChoices = notesFocus.takeIf { hasNotes },
            ) {
                Text(state.release.title, style = MaterialTheme.typography.titleMedium)
                if (hasNotes) {
                    Text("What's new", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                    ReleaseNotes(state.release.notes, notesFocus)
                }
            }
        }
        is UpdateState.Downloading -> Panel("Downloading ${state.release.title}", {}, emptyList()) {
            Column(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth(state.progress.coerceIn(0.02f, 1f)).height(6.dp).background(MaterialTheme.colorScheme.primary)) {}
            }
        }
        is UpdateState.NeedsPermission -> Panel(
            "Allow Nitrate to install updates",
            onDismiss,
            listOf(Choice("Open settings") { runCatching { context.startActivity(state.settings as android.content.Intent) }; onReoffer(state.release) }, Choice("Not now", onDismiss)),
        ) {
            Text("Android asks once: turn on \"Allow from this source\" for Nitrate, then come back and choose Update now.", style = MaterialTheme.typography.bodyMedium)
        }
        UpdateState.UpToDate -> Panel("You're up to date", onDismiss, listOf(Choice("OK", onDismiss))) {
            Text("This is the newest Nitrate for this TV.", style = MaterialTheme.typography.bodyMedium)
        }
        is UpdateState.Failed -> Panel("Update", onDismiss, listOf(Choice("OK", onDismiss))) {
            Text(state.message, style = MaterialTheme.typography.bodyMedium)
        }
        else -> Unit
    }
}

/**
 * Every change, in a box of its own height so a long list never pushes the choices off the screen.
 * It rolls slowly by itself (a pause at the top, down to the end, a pause, back to the top) until
 * someone scrolls it with ▲▼, which works once it has focus (Up from the choices goes straight to it).
 */
@Composable
internal fun ReleaseNotes(notes: List<String>, focus: FocusRequester = remember { FocusRequester() }, maxHeight: Int = 260) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    var byHand by remember { mutableStateOf(false) }
    LaunchedEffect(notes, byHand) {
        if (byHand) return@LaunchedEffect
        while (true) {
            delay(3_000)
            if (scroll.maxValue <= 0) continue
            // About 40 px a second, so every line can be read as it passes.
            scroll.animateScrollTo(scroll.maxValue, tween(durationMillis = ((scroll.maxValue - scroll.value) * 25).coerceAtLeast(1), easing = LinearEasing))
            delay(3_000)
            scroll.animateScrollTo(0, tween(600))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight.dp)
                .border(2.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(10.dp))
                .focusRequester(focus)
                .onFocusChanged { focused = it.isFocused }
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (e.key) {
                        Key.DirectionDown -> if (scroll.value < scroll.maxValue) { byHand = true; scope.launch { scroll.animateScrollBy(160f) }; true } else false
                        Key.DirectionUp -> if (scroll.value > 0) { byHand = true; scope.launch { scroll.animateScrollBy(-160f) }; true } else false
                        else -> false
                    }
                }
                .semantics { contentDescription = "What's new: " + notes.joinToString(". ") }
                .focusable()
                .verticalScroll(scroll)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            notes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        }
        if (scroll.maxValue > 0) Text(
            if (focused) "▲▼ to scroll · ▼ at the end for the choices" else "Press ▲ to scroll through the changes yourself",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Panel(title: String, onDismiss: () -> Unit, choices: List<Choice>, upFromChoices: FocusRequester? = null, body: @Composable () -> Unit) {
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(600.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 4.dp))
            body()
            if (choices.isNotEmpty()) Column(Modifier.padding(top = 10.dp)) {
                choices.forEachIndexed { i, c ->
                    ListItem(
                        selected = false, onClick = c.action, headlineContent = { Text(c.label) },
                        modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier)
                            .then(if (i == 0 && upFromChoices != null) Modifier.focusProperties { up = upFromChoices } else Modifier),
                    )
                }
            }
        }
    }
    LaunchedEffect(title, choices.size) { if (choices.isNotEmpty()) runCatching { first.requestFocus() } }
}
