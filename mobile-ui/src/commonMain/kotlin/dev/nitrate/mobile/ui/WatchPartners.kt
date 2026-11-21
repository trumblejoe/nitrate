package dev.nitrate.mobile.ui

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

/** Where the user's watch partners are in the show on screen (empty: none). */
val LocalPartnerProgress = staticCompositionLocalOf<List<PartnerProgress>> { emptyList() }

/** Whether an episode still is blurred: unwatched by the user, or (on a shared show) by a partner. */
@Composable
fun spoilerStill(item: MediaItem): Boolean {
    val prefs = LocalDisplayPrefs.current
    val progress = LocalPartnerProgress.current
    return prefs.isSpoiler(item) || (progress.isNotEmpty() && WatchPartners.isSpoiler(prefs, item, progress))
}

/** Whether a description waits behind "Show": unwatched by the user, or (on a shared show) by a partner. */
@Composable
fun descriptionHidden(item: MediaItem): Boolean {
    val prefs = LocalDisplayPrefs.current
    val progress = LocalPartnerProgress.current
    return prefs.hidesDescription(item) || (progress.isNotEmpty() && WatchPartners.hidesDescription(prefs, item, progress))
}

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
            runCatching { actions.server.askPartner(chosen, typed.takeIf { chosen == null }, series.id, series.name, target, schedule.takeIf { target == null }) }
                .onSuccess {
                    result = "Sent. If that's someone on this server, they'll see it next time they open Nitrate."
                    actions.container.refreshPartners()
                }
                .onFailure { error = it.message }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Watch ${series.name} with…") },
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
                        "They'll be asked to accept, and see who's in the group first. Everyone in a group sees each other's progress in the show.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (everyone.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        everyone.forEach { u -> FilterChip(chosen == u.id, { chosen = if (chosen == u.id) null else u.id; typed = "" }, label = { Text(u.name) }) }
                    }
                    OutlinedTextField(
                        typed, { typed = it; if (it.isNotBlank()) chosen = null },
                        label = { Text("Or their exact user name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("For someone not listed. Nothing here tells you whether the account exists.") },
                    )
                    // A new group's schedule is the inviter's choice; accepting agrees to it.
                    if (target == null) SchedulePicker(schedule) { schedule = it }
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
 * A group's schedule: Binge, every 2 days, every 3 days or weekly (with its day), the time new
 * parts open, and whether a locked part can still be played after a warning.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SchedulePicker(schedule: dev.nitrate.core.partners.GroupSchedule, onChange: (dev.nitrate.core.partners.GroupSchedule) -> Unit) {
    val g = dev.nitrate.core.partners.GroupSchedule
    Text("How it's let out", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        g.CADENCES.forEach { (id, label) -> FilterChip(schedule.cadence == id, { onChange(schedule.copy(cadence = id)) }, label = { Text(label) }) }
    }
    if (!schedule.limits) return
    if (schedule.cadence == g.WEEKLY) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        g.DAYS.forEachIndexed { i, d -> FilterChip(schedule.day == i, { onChange(schedule.copy(day = i)) }, label = { Text(d.take(3)) }) }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        g.HOURS.forEach { h -> FilterChip(schedule.hour == h && schedule.minute == 0, { onChange(schedule.copy(hour = h, minute = 0)) }, label = { Text(g.clockOf(h, 0)) }) }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(!schedule.softLock, { onChange(schedule.copy(softLock = false)) }, label = { Text("Locked until then") })
        FilterChip(schedule.softLock, { onChange(schedule.copy(softLock = true)) }, label = { Text("Ask first") })
    }
    Text(
        "Anything someone in the group has seen stays open, so whoever's behind can catch up. Only Nitrate keeps to it: Jellyfin's own apps don't know about it.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
                Text("${group.seriesName} now: ${group.schedule?.label ?: "Binge"}. ${if (group.others.size == 1) "${group.label} has to agree" else "Most of the group has to agree"} before it changes.", style = MaterialTheme.typography.bodySmall)
                SchedulePicker(schedule) { schedule = it }
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
    val into = if (others.isEmpty()) "${r.fromName} would like to watch ${r.seriesName} with you."
    else "${r.fromName} invites you to watch ${r.seriesName} with ${WatchPartners.names(others)}."
    val pace = r.schedule?.takeIf { it.limits }?.let { " The group goes at its own pace: ${it.label}, ${if (it.softLock) "with a warning before watching ahead" else "locked until then"}; accepting agrees to it." }.orEmpty()
    return "$into If you accept, ${WatchPartners.names(r.willSee)} will see your name and your progress in it.$pace"
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
        Text("Watch partners", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
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
                Switch(p.isPrivate, { on -> act { privacy = container.server.setPrivacy(on) } }, enabled = !p.byAdmin)
            }
        }

        val incoming = info?.incoming.orEmpty()
        if (incoming.isNotEmpty()) {
            SectionTitleSmall("Asking you")
            incoming.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${r.fromName}: ${r.seriesName}")
                        Text("Would see you: ${WatchPartners.names(r.willSee)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Text(g.seriesName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.pressable({ actions.nav.navigate(DetailsRoute(g.seriesId)) }))
                Text("With ${g.label} · ${g.schedule?.label ?: "Binge"}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                g.pending?.let { p -> Text("${p.fromName} asks: ${p.proposed.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Row {
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
                    Text("${r.seriesName} with ${r.to}", modifier = Modifier.weight(1f))
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
            title = { Text("Leave ${g.seriesName} with ${g.label}?") },
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
class TogetherCards(val select: (MediaItem) -> Boolean, val hold: (MediaItem) -> Unit)

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
        title = { Text("Watch ${r.seriesName} together?") },
        text = { Text(inviteText(r)) },
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
    val choices = dev.nitrate.core.partners.TogetherRow.choices(item, info, standings, episodes, container.invitesAvailable(), prefs.ownSpoilerShows)
    var leaving by remember { mutableStateOf<dev.nitrate.core.partners.WatchGroup?>(null) }
    var inviting by remember { mutableStateOf<String?>(null) }
    var changing by remember { mutableStateOf<dev.nitrate.core.partners.WatchGroup?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun run(c: dev.nitrate.core.partners.TogetherChoice) {
        when (c) {
            is dev.nitrate.core.partners.TogetherChoice.ChangeSchedule -> changing = c.group
            is dev.nitrate.core.partners.TogetherChoice.Start -> { onDismiss(); onStart() }
            is dev.nitrate.core.partners.TogetherChoice.OpenShow -> { onDismiss(); actions.nav.navigate(DetailsRoute(c.seriesId)) }
            is dev.nitrate.core.partners.TogetherChoice.Invite -> inviting = c.groupId
            is dev.nitrate.core.partners.TogetherChoice.Leave -> leaving = c.group
            is dev.nitrate.core.partners.TogetherChoice.WatchNow -> scope.launch {
                val e = container.runTogether(c)
                if (e != null) error = e else { onDismiss(); actions.play(c.episode, false) }
            }
            else -> scope.launch { val e = container.runTogether(c); if (e != null) error = e else onDismiss() }
        }
    }
    changing?.let { g -> ScheduleChangeDialog(actions, g) { changing = null; onDismiss() }; return }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.seriesName ?: item.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            dev.nitrate.core.partners.TogetherRow.line(item, info, standings)?.let { Text(it.long, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            choices.forEach { c ->
                TextButton(onClick = { run(c) }) {
                    Text(c.label, color = if (c is dev.nitrate.core.partners.TogetherChoice.Leave) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
    leaving?.let { g ->
        AlertDialog(
            onDismissRequest = { leaving = null },
            title = { Text("Leave ${g.seriesName} with ${g.label}?") },
            text = { Text(if (g.others.size == 1) "The group ends. Nothing you've watched changes." else "The others carry on without you. Nothing you've watched changes.") },
            confirmButton = { TextButton(onClick = { scope.launch { container.runTogether(dev.nitrate.core.partners.TogetherChoice.Leave(g)); leaving = null; onDismiss() } }) { Text("Leave") } },
            dismissButton = { TextButton(onClick = { leaving = null }) { Text("Stay") } },
        )
    }
    inviting?.let { groupId ->
        val name = info?.groups?.firstOrNull { it.id == groupId }?.seriesName ?: item.seriesName ?: item.name
        WatchWithDialog(actions, MediaItem(series ?: item.id, name, dev.nitrate.core.model.ItemKind.SERIES), groupId) { inviting = null; onDismiss() }
    }
}

/** "Watch with someone": pick a show (watched last, or searched), then who to ask ([WatchWithDialog]). */
@Composable
fun StartTogetherDialog(actions: AppActions, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<MediaItem?>(null) }
    val shows by produceState(emptyList<MediaItem>(), query) {
        kotlinx.coroutines.delay(if (query.isBlank()) 0 else 350)
        value = actions.container.togetherShows(query)
    }
    picked?.let { s -> WatchWithDialog(actions, s) { onDismiss() }; return }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Watch something with someone") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Pick what to watch, then who to ask. They'll see who's in the group before they accept.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(query, { query = it }, label = { Text("Find a show, film, playlist or collection") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(if (query.isBlank()) "Watched lately" else "Found", style = MaterialTheme.typography.labelLarge)
                if (shows.isEmpty()) Text(if (query.isBlank()) "Type a name." else "Nothing found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                shows.forEach { s ->
                    TextButton(onClick = { picked = s }) { Text(listOfNotNull(s.name, s.productionYear?.toString(), dev.nitrate.core.partners.TogetherRow.kindLabel(s)).joinToString(" · ")) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SectionTitleSmall(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
