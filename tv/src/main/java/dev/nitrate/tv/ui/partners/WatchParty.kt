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
 * hand: a dialog (Join first, focused), in the player too ([inPlayer]), which plays on meanwhile;
 * starting the party there closes it (its place kept) for the party's part. Easy mode keeps Join
 * and Not tonight.
 */
@Composable
fun WatchPartyPrompt(container: AppContainer, inPlayer: Boolean, easy: Boolean, onPlay: (MediaItem) -> Unit) {
    val news by container.parties.asking.collectAsState()
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    note?.let { text ->
        val ok = remember { FocusRequester() }
        TvDialog({ note = null }, width = 560) {
            Text(text)
            Button(onClick = { note = null }, modifier = Modifier.focusRequester(ok)) { Text("OK") }
        }
        LaunchedEffect(text) { delay(50); runCatching { ok.requestFocus() }; delay(6_000); note = null }
    }
    val n = news ?: return
    // Over the player too ([inPlayer]), taking focus: it plays on meanwhile, and closing the prompt gives focus back to the player.
    val prompt = PartyPrompt.of(n, easy) ?: return
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

/**
 * A schedule change someone in one of the user's groups asked for ("Sam asks to change the
 * schedule of The Bear to Thursdays 7pm. Agree?": Yes focused, No, Later), or a note when one
 * passes, fails or lapses (OK). Back (or Later) puts the question away: it stays first in the
 * card's choices and in Settings → Watch groups. In the player too ([inPlayer]), which plays on.
 * Waits while a watch party prompt shows.
 */
@Composable
fun ScheduleChangePrompt(container: AppContainer, inPlayer: Boolean) {
    val news by container.scheduleAsks.showing.collectAsState()
    val party by container.parties.asking.collectAsState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val n = news ?: return
    val prompt = dev.nitrate.core.partners.SchedulePrompt.of(n) ?: return
    // Over the player too (it plays on); after a watch party's prompt.
    if (party != null) return
    ScheduleAskDialog(prompt, error, onLater = { error = null; container.scheduleAsks.dismiss(n) }) { yes ->
        scope.launch { error = container.scheduleAsks.answer(n, yes) }
    }
}

/** The schedule prompt's dialog: the question with Yes (focused), No and Later, or the outcome with OK. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleAskDialog(prompt: dev.nitrate.core.partners.SchedulePrompt, error: String? = null, onLater: () -> Unit, onAnswer: (Boolean) -> Unit) {
    val first = remember(prompt) { FocusRequester() }
    TvDialog(onLater, width = 640) {
        Text(prompt.title, style = MaterialTheme.typography.headlineSmall)
        Text(prompt.text)
        if (prompt.asks) Text(
            "It changes once most of the group agrees, and lapses after 7 days. You can also answer from the title's group choices.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (prompt.asks) {
                Button(onClick = { onAnswer(true) }, modifier = Modifier.focusRequester(first)) { Text("Yes") }
                OutlinedButton(onClick = { onAnswer(false) }) { Text("No") }
                OutlinedButton(onClick = onLater) { Text("Later") }
            } else Button(onClick = onLater, modifier = Modifier.focusRequester(first)) { Text("OK") }
        }
    }
    LaunchedEffect(prompt) { delay(60); runCatching { first.requestFocus() } }
}


/**
 * "Tommy started a Group Watch Session · S1:E4 of The Bear": someone in a group started a Group Watch Session.
 * Off the player a dialog with Join focused (late: "Join at 12:30", "Start from the beginning");
 * Join opens the player, which waits for the countdown. In a player too, taking focus while it plays
 * on; Join there closes it (its place kept) for the session's part.
 */
@Composable
fun SessionJoinPrompt(container: AppContainer, inPlayer: Boolean, easy: Boolean) {
    val asking by container.groupSessions.asking.collectAsState()
    val scope = rememberCoroutineScope()
    var problem by remember { mutableStateOf<String?>(null) }
    val s = asking ?: return
    // Over the player too, taking focus: it plays on meanwhile; Join closes it (its place kept) for the session's part.
    val prompt = dev.nitrate.core.partners.SessionPrompt.of(s, container.groupSessions.serverNow(), easy) ?: return
    SessionJoinDialog(prompt, problem, onDismiss = { container.groupSessions.dismiss() }) { c ->
        scope.launch { problem = container.groupSessions.choose(s, c) }
    }
}

/** The join prompt's dialog: who started it and what, then Join (focused), the beginning for a late joiner, Not now. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionJoinDialog(prompt: dev.nitrate.core.partners.SessionPrompt, problem: String? = null, onDismiss: () -> Unit, onChoose: (dev.nitrate.core.partners.SessionChoice) -> Unit) {
    val first = remember(prompt) { FocusRequester() }
    TvDialog(onDismiss, width = 760) {
        Text(prompt.title, style = MaterialTheme.typography.headlineSmall)
        Text(prompt.text)
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            prompt.choices.forEachIndexed { i, c ->
                val m = if (i == 0) Modifier.focusRequester(first) else Modifier
                if (i == 0) Button(onClick = { onChoose(c) }, modifier = m) { Text(c.label) }
                else OutlinedButton(onClick = { onChoose(c) }, modifier = m) { Text(c.label) }
            }
        }
    }
    LaunchedEffect(prompt.sessionId) { delay(60); runCatching { first.requestFocus() } }
}

/**
 * Asks to watch together on the TV: someone's ask ("Grandma would like to watch together · S1:E4 of
 * The Bear · now"; "Start it" focused, then Not now), over the player too, taking focus while it plays
 * on; the reminder when a later one this user took is due; what the user's own asks came to (a short
 * message); and starting one here as host ([onPlay] plays its part, in place of a player open).
 */
@Composable
fun WatchAskPrompt(container: AppContainer, onPlay: (dev.nitrate.core.model.MediaItem) -> Unit) {
    val asks = container.groupAsks
    val asking by asks.asking.collectAsState()
    val due by asks.due.collectAsState()
    val scope = rememberCoroutineScope()
    var problem by remember { mutableStateOf<String?>(null) }
    var said by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(asks) { asks.toAsker.collect { said = it } }
    LaunchedEffect(asks) {
        asks.starts.collect { a ->
            val (item, error) = container.startAsked(a)
            if (item != null) onPlay(item) else problem = error
        }
    }
    said?.let { text ->
        TvDialog({ said = null }, width = 640) {
            Text("Watch together", style = MaterialTheme.typography.headlineSmall)
            Text(text)
            val ok = remember { FocusRequester() }
            Button(onClick = { said = null }, modifier = Modifier.focusRequester(ok)) { Text("OK") }
            LaunchedEffect(text) { delay(60); runCatching { ok.requestFocus() } }
        }
        LaunchedEffect(text) { delay(8_000); said = null }
        return
    }
    due?.let { a ->
        val (title, text) = dev.nitrate.core.partners.AskPrompt.due(a)
        AskDialog(a.id + "due", title, text, problem, "Start it now", onYes = { asks.startDue(true) }, onNo = { asks.startDue(false) })
        return
    }
    val a = asking ?: run {
        problem?.let { p -> AskDialog("problem", "Couldn't start the Group Watch Session", p, null, "OK", onYes = { problem = null }, onNo = null) }
        return
    }
    val clock = { ms: Long -> java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(ms)) }
    val (title, text) = dev.nitrate.core.partners.AskPrompt.question(a, clock)
    AskDialog(a.id, title, text, problem, dev.nitrate.core.partners.AskPrompt.startLabel(a, clock), onYes = { scope.launch { problem = asks.take(a) } }, onNo = { asks.decline(a) })
}

/** An ask's dialog: the question, its yes (focused at once), and Not now (Back too) when there's one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskDialog(key: String, title: String, text: String, problem: String?, yes: String, onYes: () -> Unit, onNo: (() -> Unit)?) {
    val first = remember(key) { FocusRequester() }
    TvDialog(onNo ?: onYes, width = 760) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(text)
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onYes, modifier = Modifier.focusRequester(first)) { Text(yes) }
            if (onNo != null) OutlinedButton(onClick = onNo) { Text("Not now") }
        }
    }
    LaunchedEffect(key) { delay(60); runCatching { first.requestFocus() } }
}
