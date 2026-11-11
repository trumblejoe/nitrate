package dev.nitrate.tv.plugins.core

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.plugins.GuardAction
import dev.nitrate.core.plugins.GuardDecision
import dev.nitrate.core.plugins.PluginRegistry

/**
 * Runs enabled playback guards (see [PluginRegistry.checkBeforePlay]) before playback and asks
 * the user when one objects. A "play instead" choice is not checked again.
 */
@Stable
class PlaybackGate(
    private val registry: PluginRegistry,
    private val scope: CoroutineScope,
    private val start: (item: MediaItem, fromStart: Boolean) -> Unit,
) {
    internal var pending by mutableStateOf<Pair<GuardDecision.Ask, CompletableDeferred<GuardAction>>?>(null)
        private set
    var checking by mutableStateOf(false)
        private set

    fun play(item: MediaItem, fromStart: Boolean = false) {
        if (checking) return
        scope.launch {
            checking = true
            val action = try {
                registry.checkBeforePlay(item) { ask ->
                    val answer = CompletableDeferred<GuardAction>()
                    pending = ask to answer
                    answer.await().also { pending = null }
                }
            } finally {
                checking = false
            }
            when (action) {
                GuardAction.PlayAnyway -> start(item, fromStart)
                GuardAction.Cancel -> Unit
                is GuardAction.PlayInstead -> start(action.item, false)
                is GuardAction.MarkWatchedThenPlay -> {
                    action.itemIds.forEach { id -> runCatching { registry.host.server.setPlayed(id, true) } }
                    start(item, fromStart)
                }
            }
        }
    }

    internal fun answer(action: GuardAction) {
        pending?.second?.complete(action)
    }
}

@Composable
fun rememberPlaybackGate(registry: PluginRegistry, start: (MediaItem, Boolean) -> Unit): PlaybackGate {
    val scope = rememberCoroutineScope()
    return remember(registry) { PlaybackGate(registry, scope, start) }
}

/** Shows a guard's question when there is one. Place once, near the root. */
@Composable
fun PlaybackGateDialog(gate: PlaybackGate) {
    val (ask, _) = gate.pending ?: return
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = { gate.answer(GuardAction.Cancel) }) {
        Column(
            Modifier.width(560.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(ask.title, style = MaterialTheme.typography.headlineSmall)
            Text(ask.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 10.dp))
            ask.choices.forEachIndexed { i, choice ->
                ListItem(
                    selected = false,
                    onClick = { gate.answer(choice.action) },
                    headlineContent = { Text(choice.label) },
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                )
            }
        }
    }
    LaunchedEffect(ask) { runCatching { first.requestFocus() } }
}
