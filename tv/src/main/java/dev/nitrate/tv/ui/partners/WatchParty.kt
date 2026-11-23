package dev.nitrate.tv.ui.partners

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.nitrate.android.AppContainer
import dev.nitrate.android.partners.PartyOutcome
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.partners.GroupSchedule
import dev.nitrate.core.partners.PartyChoice
import dev.nitrate.core.partners.PartyPrompt
import dev.nitrate.core.partners.PartyTime
import dev.nitrate.tv.ui.lists.TvDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The schedule picker's watch party: none, or one or more days at a time ("Thursdays 8pm"). It
 * holds nothing back; changing it later passes by majority like the rest of the schedule.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PartyTimePicker(schedule: GroupSchedule, onChange: (GroupSchedule) -> Unit, first: Modifier = Modifier) {
    val party = schedule.party
    Text("Group Watch Session", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(party == null, { onChange(schedule.copy(party = null)) }, first) { Text("None") }
        GroupSchedule.DAYS.forEachIndexed { i, d ->
            val on = party?.days?.contains(i) == true
            FilterChip(on, {
                val days = (party?.days.orEmpty() - i).let { if (on) it else it + i }.sorted()
                onChange(schedule.copy(party = if (days.isEmpty()) null else (party ?: PartyTime(emptyList())).copy(days = days)))
            }) { Text(d.take(3)) }
        }
    }
    if (party != null) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GroupSchedule.HOURS.forEach { h -> FilterChip(party.hour == h && party.minute == 0, { onChange(schedule.copy(party = party.copy(hour = h, minute = 0))) }) { Text(GroupSchedule.clockOf(h, 0)) } }
        }
        Text(
            "${party.label}: you're all reminded 15 minutes before, and the first to join starts watching the next part together.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * What the Companion says about the user's groups' watch parties, as it happens (reminders,
 * postponements, requests to agree to, the start-without question), or tonight's party opened by
 * hand. Off the player: a dialog (Join first). In the player ([inPlayer]): a quiet note at the top
 * that never takes focus from the controls, More, Info or a dialog; the question waits for the
 * player to close. Easy mode keeps Join and Not tonight.
 */
@Composable
fun WatchPartyPrompt(container: AppContainer, inPlayer: Boolean, easy: Boolean, onPlay: (MediaItem) -> Unit) {
    val news by container.parties.asking.collectAsState()
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    note?.let { text ->
        if (!inPlayer) {
            val ok = remember { FocusRequester() }
            TvDialog({ note = null }, width = 560) {
                Text(text)
                Button(onClick = { note = null }, modifier = Modifier.focusRequester(ok)) { Text("OK") }
            }
            LaunchedEffect(text) { delay(50); runCatching { ok.requestFocus() }; delay(6_000); note = null }
        } else LaunchedEffect(text) { delay(6_000); note = null }
    }
    val n = news ?: return
    val prompt = PartyPrompt.of(n, easy) ?: return
    if (inPlayer) {
        PartyNote(prompt) { container.parties.dismiss() }
        return
    }
    WatchPartyDialog(prompt, onDismiss = { container.parties.dismiss() }) { choice ->
        scope.launch {
            when (val out = container.parties.choose(n, choice)) {
                is PartyOutcome.Start -> {
                    val error = container.startParty(out)
                    if (error != null) note = error else onPlay(out.choice.episode)
                }
                is PartyOutcome.Done -> note = out.message
                is PartyOutcome.Failed -> note = out.message
                PartyOutcome.Joined -> Unit
            }
        }
    }
}

/** The prompt's dialog: who and when, then its buttons (Join first, focused). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WatchPartyDialog(prompt: PartyPrompt, onDismiss: () -> Unit, onChoose: (PartyChoice) -> Unit) {
    val first = remember(prompt) { FocusRequester() }
    TvDialog(onDismiss, width = 760) {
        Text(prompt.title, style = MaterialTheme.typography.headlineSmall)
        prompt.text.lines().forEachIndexed { i, line ->
            Text(line, color = if (i == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            prompt.choices.forEachIndexed { i, c ->
                val m = if (i == 0) Modifier.focusRequester(first) else Modifier
                if (i == 0) Button(onClick = { onChoose(c) }, modifier = m) { Text(c.label) }
                else OutlinedButton(onClick = { onChoose(c) }, modifier = m) { Text(c.label) }
            }
            if (prompt.choices.none { it == PartyChoice.Ok }) OutlinedButton(onClick = onDismiss) { Text("Close") }
        }
    }
    LaunchedEffect(prompt) { delay(60); runCatching { first.requestFocus() } }
}

/** In the player: the news at the top, never focusable; the question itself waits until the player closes. */
@Composable
private fun PartyNote(prompt: PartyPrompt, onGone: () -> Unit) {
    // News with nothing to answer goes by itself.
    val newsOnly = prompt.choices == listOf(PartyChoice.Ok)
    if (newsOnly) LaunchedEffect(prompt) { delay(8_000); onGone() }
    Box(Modifier.fillMaxSize().padding(top = 24.dp), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 640.dp).background(Color(0xCC101418), RoundedCornerShape(12.dp)).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(prompt.text.lines().first(), color = Color.White, style = MaterialTheme.typography.titleSmall)
            if (!newsOnly) Text("It'll ask when you stop playing.", color = Color(0xFFB8C0CC), style = MaterialTheme.typography.bodySmall)
        }
    }
}
