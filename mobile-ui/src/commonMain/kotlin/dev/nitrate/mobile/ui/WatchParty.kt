package dev.nitrate.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.nitrate.android.partners.PartyOutcome
import dev.nitrate.core.partners.GroupSchedule
import dev.nitrate.core.partners.PartyChoice
import dev.nitrate.core.partners.PartyPrompt
import dev.nitrate.core.partners.PartyTime
import kotlinx.coroutines.launch

/**
 * The schedule picker's watch party: none, or one or more days at a time ("Thursdays 8pm"). It
 * holds nothing back; changing it later passes by majority like the rest of the schedule.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PartyTimePicker(schedule: GroupSchedule, onChange: (GroupSchedule) -> Unit) {
    val party = schedule.party
    Text("Group Watch Session", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(party == null, { onChange(schedule.copy(party = null)) }, label = { Text("None") })
        GroupSchedule.DAYS.forEachIndexed { i, d ->
            val on = party?.days?.contains(i) == true
            FilterChip(on, {
                val days = (party?.days.orEmpty() - i).let { if (on) it else it + i }.sorted()
                onChange(schedule.copy(party = if (days.isEmpty()) null else (party ?: PartyTime(emptyList())).copy(days = days)))
            }, label = { Text(d.take(3)) })
        }
    }
    if (party != null) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GroupSchedule.HOURS.forEach { h ->
                FilterChip(party.hour == h && party.minute == 0, { onChange(schedule.copy(party = party.copy(hour = h, minute = 0))) }, label = { Text(GroupSchedule.clockOf(h, 0)) })
            }
        }
        Text(
            "${party.label}: you're all reminded 15 minutes before, and the first to join starts watching the next part together.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * What the Companion says about the user's groups' watch parties as it happens (the reminder,
 * postponements, requests to agree to, the start-without question), or tonight's party opened by
 * hand: an in-app sheet. Join starts the watch party when the user is first (through the guards);
 * easy mode keeps Join and Not tonight. Not over the player (it waits until the player closes).
 */
@Composable
fun WatchPartyPrompt(actions: AppActions, inPlayer: Boolean) {
    val container = actions.container
    val news by container.parties.asking.collectAsState()
    val easy = LocalDisplayPrefs.current.easyMode
    val platform = dev.nitrate.mobile.platform.rememberPlatformActions()
    val scope = rememberCoroutineScope()
    val n = news ?: return
    if (inPlayer) return
    val prompt = PartyPrompt.of(n, easy) ?: return
    WatchPartySheet(prompt, onDismiss = { container.parties.dismiss() }) { choice ->
        scope.launch {
            when (val out = container.parties.choose(n, choice)) {
                is PartyOutcome.Start -> {
                    val error = container.startParty(out)
                    if (error != null) platform.toast(error) else actions.play(out.choice.episode, false)
                }
                is PartyOutcome.Done -> out.message?.let(platform.toast)
                is PartyOutcome.Failed -> platform.toast(out.message)
                PartyOutcome.Joined -> Unit
            }
        }
    }
}

/** The prompt as a sheet: who and when, then its buttons (Join first). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WatchPartySheet(prompt: PartyPrompt, onDismiss: () -> Unit, onChoose: (PartyChoice) -> Unit) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        WatchPartyChoices(prompt, onDismiss, onChoose)
    }
}

/** The sheet's contents (also shot on its own in the screenshot tests). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WatchPartyChoices(prompt: PartyPrompt, onDismiss: () -> Unit, onChoose: (PartyChoice) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(prompt.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        prompt.text.lines().forEachIndexed { i, line ->
            Text(line, style = if (i == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium, color = if (i == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            prompt.choices.forEachIndexed { i, c ->
                if (i == 0) Button(onClick = { onChoose(c) }) { Text(c.label) } else OutlinedButton(onClick = { onChoose(c) }) { Text(c.label) }
            }
        }
        if (prompt.choices.none { it == PartyChoice.Ok }) TextButton(onClick = onDismiss) { Text("Close") }
    }
}
