package dev.nitrate.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.pages.hidesDescription
import dev.nitrate.core.pages.isSpoiler
import dev.nitrate.core.partners.PartnerProgress
import dev.nitrate.core.partners.WatchPartners
import kotlinx.coroutines.launch

/** Where the user's watch partners are in the show on screen (empty: none); spoiler hiding keeps only Together groups' ([WatchPartners.spoilerProgress]). */
val LocalPartnerProgress = staticCompositionLocalOf<List<PartnerProgress>> { emptyList() }

/** The user's watch groups and invites (null: none known, or no watch partners). */
val LocalPartnersInfo = compositionLocalOf<dev.nitrate.core.partners.PartnersInfo?> { null }

/** Where the user stands in each title they watch with a group, by title id (empty without any). */
val LocalGroupStandings = compositionLocalOf { emptyMap<String, dev.nitrate.core.partners.KeepUp>() }

/**
 * Whether an episode still is blurred: unwatched by the user, or (on a shared show) by a partner;
 * and, whatever the user's spoiler settings, anything a group's schedule locks for them now.
 */
@Composable
fun spoilerStill(item: MediaItem): Boolean {
    val prefs = LocalDisplayPrefs.current
    // Only Together groups' partners hide spoilers (a group with it off only tracks progress and pace).
    val progress = WatchPartners.spoilerProgress(LocalPartnerProgress.current, LocalPartnersInfo.current)
    return prefs.isSpoiler(item) || (progress.isNotEmpty() && WatchPartners.isSpoiler(prefs, item, progress)) || lockedNow(item)
}

/** Whether a description waits behind "Show": by the same rules as [spoilerStill]. */
@Composable
fun descriptionHidden(item: MediaItem): Boolean {
    val prefs = LocalDisplayPrefs.current
    // Only Together groups' partners hide spoilers (a group with it off only tracks progress and pace).
    val progress = WatchPartners.spoilerProgress(LocalPartnerProgress.current, LocalPartnersInfo.current)
    return prefs.hidesDescription(item) || (progress.isNotEmpty() && WatchPartners.hidesDescription(prefs, item, progress)) || lockedNow(item)
}

/** Whether a group's schedule locks [item] for the user now (not out yet, or for watching together). */
@Composable
private fun lockedNow(item: MediaItem): Boolean =
    dev.nitrate.core.partners.PartLock.of(item, LocalPartnersInfo.current, LocalGroupStandings.current) != null

/** Whether this server has watch partners (the Companion plugin). */
@Composable
fun partnersAvailable(actions: AppActions): Boolean {
    val companion by actions.container.companion.collectAsState()
    val enabled by actions.container.plugins.enabledIds.collectAsState(emptySet())
    return companion?.features?.contains(WatchPartners.FEATURE) == true && dev.nitrate.core.partners.WatchPartnersPlugin.ID in enabled
}

/** The partners' progress on a show, for its details pages. */
@Composable
fun rememberPartnerProgress(actions: AppActions, seriesId: String?): List<PartnerProgress> {
    val available = partnersAvailable(actions)
    val partners by actions.container.partners.collectAsState()
    return produceState(emptyList<PartnerProgress>(), seriesId, available, partners) {
        value = if (seriesId == null || !available) emptyList() else runCatching { actions.server.partnerProgress(seriesId) }.getOrDefault(emptyList())
    }.value
}

/**
 * Invites someone to watch a show together: into one of the user's groups for it, or as a new
 * group. Someone listed (people who aren't private), or anyone by exact user name.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WatchWithDialog(actions: AppActions, series: MediaItem, groupId: String? = null, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val info by actions.container.partners.collectAsState()
    val people by produceState(emptyList<dev.nitrate.core.writing.UserRef>()) { value = runCatching { actions.server.partnerPeople() }.getOrDefault(emptyList()) }
    val groups = info?.on(series.id).orEmpty()
    var target by remember { mutableStateOf(groupId) }
    var chosen by remember { mutableStateOf<String?>(null) }
    var typed by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var schedule by remember { mutableStateOf(dev.nitrate.core.partners.GroupSchedule()) }
    val inGroup = groups.firstOrNull { it.id == target }?.others?.map { it.id }.orEmpty().toSet()
    // People known from other groups (private ones too: they already shared a group), then everyone listed.
    val known = info?.groups.orEmpty().flatMap { it.others }
    val everyone = (known + people).distinctBy { it.id }.filter { it.id !in inGroup }
    fun send() {
        error = null
        scope.launch {
            val titles = groups.firstOrNull { it.id == target }?.titles ?: listOf(dev.nitrate.core.partners.GroupTitle(series.id, series.name))
            runCatching { actions.server.askPartner(chosen, typed.takeIf { chosen == null }, series.id, series.name, target, schedule.takeIf { target == null }, titles) }
                .onSuccess {
                    result = "Sent. If that's someone on this server, they'll see it next time they open Nitrate."
                    actions.container.refreshPartners()
                }
                .onFailure { error = it.message }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Watch ${groups.firstOrNull { it.id == target }?.titleNames ?: series.name} with…") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (result != null) Text(result!!) else {
                    if (groups.isNotEmpty()) {
                        Text("Invite into", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(target == null, { target = null }, label = { Text("A new group") })
                            groups.forEach { g -> FilterChip(target == g.id, { target = g.id }, label = { Text("With ${g.label}") }) }
                        }
                    }
                    Text(
                        "Pick everyone to invite. They'll each be asked to accept, and see who's in the group first. Everyone in a group sees each other's progress in what it watches.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (everyone.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        everyone.forEach { u -> FilterChip(chosen == u.id, { chosen = if (chosen == u.id) null else u.id; typed = "" }, label = { Text(u.name) }) }
                    }
                    OutlinedTextField(
                        typed, { typed = it; if (it.isNotBlank()) chosen = null },
                        label = { Text("Or their exact user name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("For people not listed. Nothing here tells you whether an account exists.") },
                    )
                    // A new group's schedule is the inviter's choice; accepting agrees to it.
                    if (target == null) SchedulePicker(schedule, lockable(actions)) { schedule = it }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            if (result != null) TextButton(onClick = onDismiss) { Text("Done") }
            else TextButton(onClick = ::send, enabled = chosen != null || typed.isNotBlank()) { Text("Send") }
        },
        dismissButton = { if (result == null) TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * A group's schedule: the release schedule (Binge, every 2 days, every 3 days or weekly with its
 * day, and the time new parts open), the Group Watch Session (optional days and time), the
 * Together switch and, where the server keeps it ([lockable]), "If someone plays ahead", with what
 * they mean.
 */
@Composable
fun SchedulePicker(schedule: dev.nitrate.core.partners.GroupSchedule, lockable: Boolean = true, onChange: (dev.nitrate.core.partners.GroupSchedule) -> Unit) {
    PacePicker(schedule, onChange)
    PartyTimePicker(schedule, onChange)
    TogetherSwitch(schedule, onChange)
    if (lockable) PlayAheadPicker(schedule, onChange)
    Text(schedule.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Whether the server keeps a schedule's "If someone plays ahead" (Companion feature `schedulelock`; older plugins drop it). */
@Composable
fun lockable(actions: AppActions): Boolean =
    actions.container.companion.collectAsState().value?.features?.contains(dev.nitrate.core.partners.GroupSchedule.LOCK_FEATURE) == true

/**
 * "If someone plays ahead": Ask first (the default) or Don't allow, with what the choice means.
 * Only while the schedule holds something back (paced, or Together on): binge with Together off has nothing to lock.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayAheadPicker(schedule: dev.nitrate.core.partners.GroupSchedule, onChange: (dev.nitrate.core.partners.GroupSchedule) -> Unit) {
    if (!schedule.active) return
    val g = dev.nitrate.core.partners.GroupSchedule
    Text(g.PLAY_AHEAD, style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        g.LOCK_CHOICES.forEach { (locked, label) -> FilterChip(schedule.locked == locked, { onChange(schedule.copy(locked = locked)) }, label = { Text(label) }) }
    }
    Text(schedule.lockNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The release schedule: Binge, every 2 days, every 3 days or weekly (with its day), and the time new parts open. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PacePicker(schedule: dev.nitrate.core.partners.GroupSchedule, onChange: (dev.nitrate.core.partners.GroupSchedule) -> Unit) {
    val g = dev.nitrate.core.partners.GroupSchedule
    Text("Release schedule", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        g.CADENCES.forEach { (id, label) -> FilterChip(schedule.cadence == id, { onChange(schedule.copy(cadence = id)) }, label = { Text(label) }) }
    }
    if (schedule.limits) {
        if (schedule.cadence == g.WEEKLY) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            g.DAYS.forEachIndexed { i, d -> FilterChip(schedule.day == i, { onChange(schedule.copy(day = i)) }, label = { Text(d.take(3)) }) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            g.HOURS.forEach { h -> FilterChip(schedule.hour == h && schedule.minute == 0, { onChange(schedule.copy(hour = h, minute = 0)) }, label = { Text(g.clockOf(h, 0)) }) }
        }
    }
}

/** The Together switch: the whole line toggles it, and says what on or off means. */
@Composable
fun TogetherSwitch(schedule: dev.nitrate.core.partners.GroupSchedule, onChange: (dev.nitrate.core.partners.GroupSchedule) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(schedule.together, role = androidx.compose.ui.semantics.Role.Switch) { onChange(schedule.copy(together = it)) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Together", style = MaterialTheme.typography.titleMedium)
            Text(schedule.togetherNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(schedule.together, onCheckedChange = null)
    }
}

/** Asks the group to change its schedule: it takes effect once most of the group agrees (both, in a pair). */
@Composable
fun ScheduleChangeDialog(actions: AppActions, group: dev.nitrate.core.partners.WatchGroup, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var schedule by remember { mutableStateOf(group.schedule ?: dev.nitrate.core.partners.GroupSchedule()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change the schedule") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${group.titleNames} now: ${group.schedule?.label ?: "Binge"}. ${if (group.others.size == 1) "${group.label} has to agree" else "Most of the group has to agree"} before it changes.", style = MaterialTheme.typography.bodySmall)
                SchedulePicker(schedule, lockable(actions)) { schedule = it }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { scope.launch { val e = actions.container.proposeSchedule(group.id, schedule); if (e != null) error = e else onDismiss() } }) { Text("Ask the group") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** What an invite means, including exactly who learns the user's name and progress by accepting. */
fun inviteText(r: dev.nitrate.core.partners.IncomingRequest): String {
    val others = r.willSee.filter { it != r.fromName }
    val into = if (others.isEmpty()) "${r.fromName} would like to watch ${r.titleNames} with you."
    else "${r.fromName} invites you to watch ${r.titleNames} with ${WatchPartners.names(others)}."
    return "$into If you accept, ${WatchPartners.names(r.willSee)} will see your name and your progress in it.${r.schedule?.terms.orEmpty()}"
}

/** Settings → Watch partners: privacy, invites, and the user's groups. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WatchPartnersScreen(actions: AppActions) {
    val container = actions.container
    val scope = rememberCoroutineScope()
    val info by container.partners.collectAsState()
    var privacy by remember { mutableStateOf<dev.nitrate.core.partners.Privacy?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var leaving by remember { mutableStateOf<dev.nitrate.core.partners.WatchGroup?>(null) }
    var inviting by remember { mutableStateOf<dev.nitrate.core.partners.WatchGroup?>(null) }
    var starting by remember { mutableStateOf(false) }
    var changingSchedule by remember { mutableStateOf<dev.nitrate.core.partners.WatchGroup?>(null) }
    LaunchedEffect(Unit) {
        container.refreshPartners()
        privacy = runCatching { container.server.privacy() }.getOrNull()
    }
    fun act(block: suspend () -> Unit) = scope.launch {
        error = null
        runCatching { block() }.onFailure { error = it.message }
        container.refreshPartners()
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Watch groups", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Watch a show, film, playlist or collection with a group, and everyone in it sees each other's progress. Home's Watching together row shows whose turn it is; press and hold a card there for more. One title can have several groups.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        privacy?.let { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Keep me private", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (p.byAdmin) "Your server's admin keeps your account private."
                        else "You won't appear in anyone's list of people or be found by name. Someone who knows your exact user name can still send you a request; only if you accept do they learn it's you.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(p.isPrivate, { on -> act { privacy = container.server.setPrivacy(on) } }, Modifier.labelled("Keep me private"), enabled = !p.byAdmin)
            }
        }

        val incoming = info?.incoming.orEmpty()
        if (incoming.isNotEmpty()) {
            SectionTitleSmall("Asking you")
            incoming.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${r.fromName}: ${r.titleNames}")
                        Text("Would see you: ${WatchPartners.names(r.willSee)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val note by androidx.compose.runtime.produceState<String?>(null, r.id) { value = WatchPartners.unopenableNote(WatchPartners.unopenable(container.server, r.titles)) }
                        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
                    }
                    TextButton(onClick = { act { container.server.acceptPartner(r.id) } }) { Text("Accept") }
                    TextButton(onClick = { act { container.server.declinePartner(r.id) } }) { Text("Decline") }
                }
            }
        }

        SectionTitleSmall("Your groups")
        TextButton(onClick = { starting = true }) { Text("Watch something with someone") }
        val groups = info?.groups.orEmpty()
        if (groups.isEmpty()) Text(if (info == null) "Loading…" else "None yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        groups.forEach { g ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // Each title opens its page.
                g.titles.forEach { t ->
                    // Opens in the group: its marks and plays are the group's there.
                    Text(t.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.pressable({ openInGroup(actions, g.id, t.id) }))
                    // Someone in the group can't open it here (library access, parental rating).
                    t.reachNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
                }
                Text(groupLine(g), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                g.pending?.let { p -> Text("${p.fromName} asks: ${p.proposed.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                g.party?.let { p -> Text(p.line(dev.nitrate.android.nowMs()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Row {
                    if (g.party != null) TextButton(onClick = { actions.container.parties.open(g) }) { Text("Group Watch Session") }
                    TextButton(onClick = { changingSchedule = g }) { Text("Schedule") }
                    TextButton(onClick = { inviting = g }) { Text("Invite someone") }
                    TextButton(onClick = { leaving = g }) { Text("Leave") }
                }
            }
        }

        val outgoing = info?.outgoing.orEmpty()
        if (outgoing.isNotEmpty()) {
            SectionTitleSmall("Waiting for an answer")
            outgoing.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${r.titleNames} with ${r.to}", modifier = Modifier.weight(1f))
                    TextButton(onClick = { act { container.server.cancelPartnerRequest(r.id) } }) { Text("Cancel") }
                }
            }
            Text("Requests wait up to 14 days.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.padding(bottom = 24.dp))
    }
    leaving?.let { g ->
        AlertDialog(
            onDismissRequest = { leaving = null },
            title = { Text("Leave ${g.titleNames} with ${g.label}?") },
            text = { Text(if (g.others.size == 1) "The group ends. Nothing you've watched changes." else "The others carry on without you. Nothing you've watched changes.") },
            confirmButton = { TextButton(onClick = { leaving = null; act { container.server.leaveGroup(g.id) } }) { Text("Leave") } },
            dismissButton = { TextButton(onClick = { leaving = null }) { Text("Stay") } },
        )
    }
    inviting?.let { g ->
        WatchWithDialog(actions, MediaItem(g.seriesId, g.seriesName, dev.nitrate.core.model.ItemKind.SERIES), g.id) { inviting = null }
    }
    if (starting) StartTogetherDialog(actions) { starting = false }
    changingSchedule?.let { g -> ScheduleChangeDialog(actions, g) { changingSchedule = null } }
}

/**
 * Home's "Watching together" row's own handling of its cards. [select]: a tap; true when it took
 * it (an invite, the start card), else the card opens as usual. [hold]: a long press.
 */
class TogetherCards(
    val select: (MediaItem) -> Boolean,
    val hold: (MediaItem) -> Unit,
    /** Whether a card is about watching together (an invite, a shared title): only those answer here. */
    val shares: (MediaItem) -> Boolean = { true },
)

val LocalTogetherCards = staticCompositionLocalOf<TogetherCards?> { null }

/** Provides [LocalTogetherCards] for Home and shows what the row's cards open. */
@Composable
fun TogetherHost(actions: AppActions, enabled: Boolean, content: @Composable () -> Unit) {
    val container = actions.container
    var answering by remember { mutableStateOf<dev.nitrate.core.partners.IncomingRequest?>(null) }
    var holding by remember { mutableStateOf<MediaItem?>(null) }
    var starting by remember { mutableStateOf(false) }
    val cards = remember(enabled) {
        if (!enabled) null else TogetherCards(
            select = { item ->
                when {
                    dev.nitrate.core.partners.TogetherRow.isStart(item) -> { starting = true; true }
                    else -> dev.nitrate.core.partners.TogetherRow.inviteFor(item, container.partners.value)?.let { answering = it; true } ?: false
                }
            },
            hold = { holding = it },
            shares = { item -> dev.nitrate.core.partners.TogetherRow.isShared(item, container.partners.value, container.groupStandings.value) },
        )
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalTogetherCards provides cards) { content() }
    answering?.let { r -> InviteAnswerDialog(actions, r) { answering = null } }
    holding?.let { item -> TogetherChoicesSheet(actions, item, onStart = { starting = true }) { holding = null } }
    if (starting) StartTogetherDialog(actions) { starting = false }
}

/** A tap on an invite's card: who's asking, who'd see the user, and the answer. */
@Composable
fun InviteAnswerDialog(actions: AppActions, r: dev.nitrate.core.partners.IncomingRequest, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    fun answer(choice: dev.nitrate.core.partners.TogetherChoice) = scope.launch { actions.container.runTogether(choice); onDismiss() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Watch ${r.titleNames} together?") },
        text = {
            val note by androidx.compose.runtime.produceState<String?>(null, r.id) { value = WatchPartners.unopenableNote(WatchPartners.unopenable(actions.container.server, r.titles)) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(inviteText(r))
                note?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
            }
        },
        confirmButton = { TextButton(onClick = { answer(dev.nitrate.core.partners.TogetherChoice.Accept(r)) }) { Text("Accept") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Not now") }
                TextButton(onClick = { answer(dev.nitrate.core.partners.TogetherChoice.Decline(r)) }) { Text("Decline") }
            }
        },
    )
}

/** A long press on a "Watching together" card: [dev.nitrate.core.partners.TogetherRow.choices] in a sheet. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TogetherChoicesSheet(actions: AppActions, item: MediaItem, onStart: () -> Unit, onDismiss: () -> Unit) {
    val container = actions.container
    val scope = rememberCoroutineScope()
    val info by container.partners.collectAsState()
    val standings by container.groupStandings.collectAsState()
    val prefs = LocalDisplayPrefs.current
    val series = dev.nitrate.core.partners.TogetherRow.titleOf(item, standings)
    val episodes by produceState(emptyList<MediaItem>(), series) {
        value = if (series == null || dev.nitrate.core.partners.TogetherRow.isStart(item)) emptyList()
        else runCatching { container.server.titleItems(series) }.getOrDefault(emptyList())
    }
    val choices = dev.nitrate.core.partners.TogetherRow.choices(item, info, standings, episodes, container.sessionsAvailable(), prefs.ownSpoilerShows, dev.nitrate.android.nowMs())
    var leaving by remember { mutableStateOf<dev.nitrate.core.partners.WatchGroup?>(null) }
    var inviting by remember { mutableStateOf<String?>(null) }
    var changing by remember { mutableStateOf<dev.nitrate.core.partners.WatchGroup?>(null) }
    var adding by remember { mutableStateOf<dev.nitrate.core.partners.WatchGroup?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun run(c: dev.nitrate.core.partners.TogetherChoice) {
        when (c) {
            is dev.nitrate.core.partners.TogetherChoice.AddTitle -> adding = c.group
            // Everyone in the group here, then it plays from the group's card (it counts for them too).
            is dev.nitrate.core.partners.TogetherChoice.InTheRoom -> scope.launch {
                val e = container.runTogether(c)
                if (e != null) error = e else { onDismiss(); actions.play(c.item.copy(watchGroupId = c.groupId), false) }
            }
            is dev.nitrate.core.partners.TogetherChoice.ChangeSchedule -> changing = c.group
            // Tonight's watch party: the same sheet as its reminder.
            is dev.nitrate.core.partners.TogetherChoice.Party -> { onDismiss(); container.parties.open(c.group) }
            is dev.nitrate.core.partners.TogetherChoice.Start -> { onDismiss(); onStart() }
            is dev.nitrate.core.partners.TogetherChoice.OpenShow -> { onDismiss(); actions.nav.navigate(DetailsRoute(c.seriesId, group = c.groupId)) }
            is dev.nitrate.core.partners.TogetherChoice.Invite -> inviting = c.groupId
            is dev.nitrate.core.partners.TogetherChoice.Leave -> leaving = c.group
            is dev.nitrate.core.partners.TogetherChoice.WatchNow -> scope.launch {
                val e = container.runTogether(c)
                // Played for the group the live session was started for (no question about watching together).
                if (e != null) error = e else { onDismiss(); actions.play(c.episode.copy(watchGroupId = c.groupId), false) }
            }
            else -> scope.launch { val e = container.runTogether(c); if (e != null) error = e else onDismiss() }
        }
    }
    changing?.let { g -> ScheduleChangeDialog(actions, g) { changing = null; onDismiss() }; return }
    adding?.let { g -> AddTitlesDialog(actions, g) { adding = null; onDismiss() }; return }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        TogetherChoicesList(item, info, standings, choices, error, ::run)
    }
    leaving?.let { g ->
        AlertDialog(
            onDismissRequest = { leaving = null },
            title = { Text("Leave ${g.titleNames} with ${g.label}?") },
            text = { Text(if (g.others.size == 1) "The group ends. Nothing you've watched changes." else "The others carry on without you. Nothing you've watched changes.") },
            confirmButton = { TextButton(onClick = { scope.launch { container.runTogether(dev.nitrate.core.partners.TogetherChoice.Leave(g)); leaving = null; onDismiss() } }) { Text("Leave") } },
            dismissButton = { TextButton(onClick = { leaving = null }) { Text("Stay") } },
        )
    }
    inviting?.let { groupId ->
        val name = info?.groups?.firstOrNull { it.id == groupId }?.seriesName ?: item.seriesName ?: item.name
        WatchWithDialog(actions, MediaItem(info?.groups?.firstOrNull { it.id == groupId }?.seriesId ?: series ?: item.id, name, dev.nitrate.core.model.ItemKind.SERIES), groupId) { inviting = null; onDismiss() }
    }
}

/** [TogetherChoicesSheet]'s contents: the title, each group's line, where the user stands, the choices (also shot on their own). */
@Composable
fun TogetherChoicesList(
    item: MediaItem,
    info: dev.nitrate.core.partners.PartnersInfo?,
    standings: Map<String, dev.nitrate.core.partners.KeepUp>,
    choices: List<dev.nitrate.core.partners.TogetherChoice>,
    error: String? = null,
    onChoose: (dev.nitrate.core.partners.TogetherChoice) -> Unit,
) {
    val series = dev.nitrate.core.partners.TogetherRow.titleOf(item, standings)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(item.seriesName ?: item.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        info?.groups?.filter { it.holds(series ?: "") && (item.watchGroupId == null || it.id == item.watchGroupId) }?.forEach { g ->
            Text("With ${g.label} · ${groupLine(g, withNames = false)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        dev.nitrate.core.partners.TogetherRow.line(item, info, standings)?.let { Text(it.long, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        choices.forEach { c ->
            TextButton(onClick = { onChoose(c) }) {
                Text(c.label, color = if (c is dev.nitrate.core.partners.TogetherChoice.Leave) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** "Watch something with someone": the guided setup ([GroupSetupSheet]). */
@Composable
fun StartTogetherDialog(actions: AppActions, onDismiss: () -> Unit) = GroupSetupSheet(actions, onDismiss = onDismiss)

/** A group's line in lists: "With Tommy · Thursdays 7pm · Group Watch Session Fridays 9pm · Together on". */
fun groupLine(g: dev.nitrate.core.partners.WatchGroup, withNames: Boolean = true): String {
    val s = g.schedule ?: dev.nitrate.core.partners.GroupSchedule()
    val pace = s.copy(together = false, party = null).label
    return listOfNotNull(if (withNames) "With ${g.label}" else null, pace, s.partyLabel, "Together " + if (s.together) "on" else "off").joinToString(" · ")
}

/**
 * Picks one or more titles (shows, films, playlists, collections): watched lately, or found by
 * name. The whole line toggles each; what's picked shows above the list.
 */
@Composable
fun TitlePicker(actions: AppActions, isPicked: (String) -> Boolean, picked: List<String>, onToggle: (MediaItem) -> Unit) {
    var query by remember { mutableStateOf("") }
    val shows by produceState(emptyList<MediaItem>(), query) {
        kotlinx.coroutines.delay(if (query.isBlank()) 0 else 350)
        value = actions.container.togetherShows(query)
    }
    OutlinedTextField(query, { query = it }, label = { Text("Find a show, film, playlist or collection") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    if (picked.isNotEmpty()) Text("Picked: " + WatchPartners.names(picked), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
    Text(if (query.isBlank()) "Watched lately" else "Found", style = MaterialTheme.typography.labelLarge)
    if (shows.isEmpty()) Text(if (query.isBlank()) "Type a name." else "Nothing found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    shows.forEach { s ->
        val on = isPicked(s.id)
        Row(
            Modifier.fillMaxWidth().toggleable(on, role = androidx.compose.ui.semantics.Role.Checkbox) { onToggle(s) }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(listOfNotNull(s.name, s.productionYear?.toString(), dev.nitrate.core.partners.TogetherRow.kindLabel(s)).joinToString(" · "), modifier = Modifier.weight(1f))
            androidx.compose.material3.Checkbox(on, onCheckedChange = null)
        }
    }
}

/**
 * "Watch something with someone", step by step ([dev.nitrate.core.partners.GroupSetup]), as a
 * full-screen sheet: what (one or more titles), who, the release schedule, the Group Watch Session
 * (optional), Together on or off; then the invite is sent. Back steps back a page (from the first,
 * closes). Easy mode: the same steps, without the explanations.
 */
@Composable
fun GroupSetupSheet(actions: AppActions, start: dev.nitrate.core.partners.GroupSetup = dev.nitrate.core.partners.GroupSetup(), onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = dev.nitrate.mobile.platform.fullScreenDialog()) {
        GroupSetupPages(actions, start, onDismiss)
    }
}

/** [GroupSetupSheet]'s pages (also shot on their own in the screenshot tests). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GroupSetupPages(actions: AppActions, start: dev.nitrate.core.partners.GroupSetup = dev.nitrate.core.partners.GroupSetup(), onDismiss: () -> Unit) {
    val container = actions.container
    val scope = rememberCoroutineScope()
    val easy = LocalDisplayPrefs.current.easyMode
    val info by container.partners.collectAsState()
    val people by produceState(emptyList<dev.nitrate.core.writing.UserRef>()) { value = runCatching { container.server.partnerPeople() }.getOrDefault(emptyList()) }
    // Several people into one group when the server can (feature `invitebatch`), else one as before.
    val several = container.companion.collectAsState().value?.features?.contains("invitebatch") == true
    var setup by remember { mutableStateOf(start) }
    if (setup.several != several) setup = setup.copy(several = several)
    var sent by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val back = { if (sent) onDismiss() else setup.back()?.let { setup = it; error = null } ?: onDismiss() }
    dev.nitrate.mobile.platform.PlatformBackHandler(enabled = true) { back() }
    fun go() {
        if (!setup.last) { setup = setup.next(); return }
        sending = true
        error = null
        scope.launch {
            runCatching { setup.send(container.server) }
                .onSuccess { sent = true; container.refreshPartners() }
                .onFailure { error = it.message ?: "Couldn't send the invite" }
            sending = false
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
            Text("Watch something with someone", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (sent) {
                Text(dev.nitrate.core.partners.GroupSetup.SENT)
                return@Column
            }
            Text(setup.heading, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            when (setup.step) {
                dev.nitrate.core.partners.GroupSetup.Step.WHAT -> {
                    if (!easy) Text("Pick one or more: shows, films, playlists or collections. The group keeps pace on each.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TitlePicker(actions, setup::picked, setup.titles.map { it.name }) { setup = setup.toggle(it) }
                }
                dev.nitrate.core.partners.GroupSetup.Step.WHO -> {
                    if (!easy) Text("Pick everyone to invite. They'll each be asked to accept, and see who's in the group first. Everyone in a group sees each other's progress in what it watches.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // People known from other groups, then everyone listed (never private accounts).
                    val everyone = (info?.groups.orEmpty().flatMap { it.others } + people).distinctBy { it.id }
                    if (everyone.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        everyone.forEach { u -> FilterChip(setup.picked(u.id), { setup = setup.pick(u.id) }, label = { Text(u.name) }) }
                    }
                    OutlinedTextField(
                        setup.typed, { setup = setup.type(it) },
                        label = { Text(if (setup.several) "And/or exact user names, separated by commas" else "Or their exact user name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        supportingText = if (easy) null else ({ Text("For people not listed. Nothing here tells you whether an account exists.") }),
                    )
                }
                dev.nitrate.core.partners.GroupSetup.Step.PACE -> {
                    if (!easy) Text("How new parts are let out to the group. Anything someone has watched with the group stays open; whether anyone may play ahead is the last step's choice.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PacePicker(setup.schedule) { setup = setup.copy(schedule = it) }
                }
                dev.nitrate.core.partners.GroupSetup.Step.SESSION -> {
                    if (!easy) Text("Optional: a regular time to meet and watch the next part together, with a reminder 15 minutes before.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PartyTimePicker(setup.schedule) { setup = setup.copy(schedule = it) }
                }
                dev.nitrate.core.partners.GroupSetup.Step.TOGETHER -> {
                    TogetherSwitch(setup.schedule) { setup = setup.copy(schedule = it) }
                    if (!easy) Text(if (setup.schedule.together) dev.nitrate.core.partners.GroupSchedule.TOGETHER_OFF else dev.nitrate.core.partners.GroupSchedule.TOGETHER_ON, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // Whether anyone may play ahead of what the schedule and Together hold back (shown once something is held back).
                    if (lockable(actions)) PlayAheadPicker(setup.schedule) { setup = setup.copy(schedule = it) }
                    Text(setup.summary, style = MaterialTheme.typography.titleSmall)
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            if (sent) Button(onClick = onDismiss) { Text("Done") }
            else {
                TextButton(onClick = back) { Text(if (setup.back() == null) "Cancel" else "Back") }
                Button(onClick = ::go, enabled = setup.canGoOn && !sending) { Text(if (setup.last) "Send the invite" else "Next") }
            }
        }
    }
}

/** "Add a title to the group": pick one or more, then they're added (any member may). */
@Composable
fun AddTitlesDialog(actions: AppActions, group: dev.nitrate.core.partners.WatchGroup, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf(emptyList<MediaItem>()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to ${group.titleNames}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TitlePicker(actions, { id -> picked.any { it.id == id } || group.holds(id) }, picked.map { it.name }) { s ->
                    if (!group.holds(s.id)) picked = if (picked.any { it.id == s.id }) picked.filter { it.id != s.id } else picked + s
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { scope.launch { val e = actions.container.addGroupTitles(group.id, picked); if (e != null) error = e else onDismiss() } }, enabled = picked.isNotEmpty()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * "Also count it for Suzy, where it's next too?" after a playback from a group's card that's
 * next in other groups too, as a sheet once the player has closed ([inPlayer]: it waits).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CountingPrompt(actions: AppActions, inPlayer: Boolean) {
    val asks by actions.container.countAsks.collectAsState()
    val ask = asks.firstOrNull() ?: return
    if (inPlayer) return
    fun answer(all: Boolean) { actions.container.saveScope.launch { actions.container.answerCounting(ask, all) } }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = { answer(false) }) {
        CountingChoices(ask, ::answer)
    }
}

/** [CountingPrompt]'s sheet contents: the question, Just this group, All groups where it's next. */
@Composable
fun CountingChoices(ask: dev.nitrate.core.partners.CountAsk, onAnswer: (all: Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(ask.question, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("${ask.what} counted for your group with ${ask.counting.groups.joinToString { it.label }}.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onAnswer(false) }) { Text(dev.nitrate.core.partners.CountAsk.JUST_THIS) }
            androidx.compose.material3.OutlinedButton(onClick = { onAnswer(true) }) { Text(dev.nitrate.core.partners.CountAsk.ALL) }
        }
    }
}

@Composable
private fun SectionTitleSmall(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))

/**
 * Opens one of group [groupId]'s titles as from its card (Watch groups' list): its page is in the
 * group (its own marks, plays counting for it), whatever kind of title it is (a show, a film, a
 * playlist, a collection).
 */
fun openInGroup(actions: AppActions, groupId: String, titleId: String) {
    actions.scope.launch {
        val title = runCatching { actions.server.getItem(titleId) }.getOrNull()
        if (title == null) actions.nav.navigate(DetailsRoute(titleId, group = groupId))
        else actions.fromGroup(groupId).open(title.copy(watchGroupId = groupId))
    }
}
