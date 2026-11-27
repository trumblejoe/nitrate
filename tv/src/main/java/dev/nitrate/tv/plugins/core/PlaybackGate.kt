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
    private val start: (item: MediaItem, fromStart: Boolean, startMs: Long?) -> Unit,
    /** Where a play from a group's card starts: that group's own resume point, or 0 for the start; null otherwise (`AppContainer.groupStartMs`). */
    private val groupStart: (dev.nitrate.core.partners.PlayOrigin?, MediaItem) -> Long? = { _, _ -> null },
    /** A play turned down or that couldn't start (`AppContainer.playTurnedDown`). */
    private val turnedDown: (MediaItem) -> Unit = {},
) {
    internal var pending by mutableStateOf<Pair<GuardDecision.Ask, CompletableDeferred<GuardAction>>?>(null)
        private set
    var checking by mutableStateOf(false)
        private set

    /**
     * Plays [item] (from the start, resuming, or from [startMs]) once the guards agree. [origin]:
     * where it's played from (null: a watch group's card by [MediaItem.watchGroupId], else
     * elsewhere), told to the guards, which hand it on to the player.
     */
    fun play(item: MediaItem, fromStart: Boolean = false, startMs: Long? = null, origin: dev.nitrate.core.partners.PlayOrigin? = null) {
        if (checking) return
        scope.launch {
            checking = true
            val from = origin ?: dev.nitrate.core.partners.PlayOrigin.of(item).takeIf { it is dev.nitrate.core.partners.PlayOrigin.GroupCard }
            // A show or season on a card: its next episode (else its first) is what plays.
            val item = if (dev.nitrate.core.playback.PlayTargets.needsEpisode(item)) {
                dev.nitrate.core.playback.PlayTargets.episodeFor(registry.host.server, item) ?: run { checking = false; return@launch }
            } else item
            val action = try {
                registry.checkBeforePlay(item, from) { ask ->
                    val answer = CompletableDeferred<GuardAction>()
                    pending = ask to answer
                    answer.await().also { pending = null }
                }
            } finally {
                checking = false
            }
            // From a group's card: the group's own resume point (or the start), never the solo one.
            // Said for every play (a playlist's card starts a sitting even from the start); used when no place was given.
            val placed = groupStart(from, item)
            val fromGroup = if (fromStart || startMs != null) null else placed
            @Suppress("NAME_SHADOWING") val fromStart = fromStart || fromGroup == 0L
            @Suppress("NAME_SHADOWING") val startMs = startMs ?: fromGroup?.takeIf { it > 0 }
            when (action) {
                GuardAction.PlayAnyway -> start(item, fromStart, startMs)
                GuardAction.Cancel -> turnedDown(item)
                is GuardAction.PlayInstead -> start(action.item, false, null)
                is GuardAction.MarkWatchedThenPlay -> {
                    action.itemIds.forEach { id -> runCatching { registry.host.server.setPlayed(id, true) } }
                    start(item, fromStart, startMs)
                }
                is GuardAction.RunThenPlay -> {
                    val failed = runCatching { action.run() }.exceptionOrNull()
                    if (failed == null || action.playIfFailed) start(item, fromStart, startMs)
                    else {
                        // Said, not just closed: why it couldn't start.
                        turnedDown(item)
                        val ok = CompletableDeferred<GuardAction>()
                        pending = GuardDecision.Ask("Couldn't start", failed.message ?: "Something went wrong. Try again in a moment.",
                            listOf(dev.nitrate.core.plugins.GuardChoice("OK", GuardAction.Cancel))) to ok
                        ok.await().also { pending = null }
                    }
                }
            }
        }
    }

    internal fun answer(action: GuardAction) {
        pending?.second?.complete(action)
    }
}

@Composable
fun rememberPlaybackGate(
    registry: PluginRegistry,
    groupStart: (dev.nitrate.core.partners.PlayOrigin?, MediaItem) -> Long? = { _, _ -> null },
    turnedDown: (MediaItem) -> Unit = {},
    start: (MediaItem, Boolean, Long?) -> Unit,
): PlaybackGate {
    val scope = rememberCoroutineScope()
    return remember(registry) { PlaybackGate(registry, scope, start, groupStart, turnedDown) }
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
    // The dialog's window comes up a frame later: focus its first choice once it's there (a refusal's only choice, OK).
    LaunchedEffect(ask) { kotlinx.coroutines.delay(60); runCatching { first.requestFocus() } }
}
