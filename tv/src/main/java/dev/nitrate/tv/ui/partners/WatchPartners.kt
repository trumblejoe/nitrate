package dev.nitrate.tv.ui.partners

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.nitrate.android.AppContainer
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.pages.hidesDescription
import dev.nitrate.core.pages.isSpoiler
import dev.nitrate.core.partners.PartnerProgress
import dev.nitrate.core.partners.WatchPartners
import dev.nitrate.tv.ui.components.LocalDisplayPrefs
import dev.nitrate.tv.ui.components.TvTextField
import dev.nitrate.tv.ui.lists.TvDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where the user's watch partners are in the show on screen (empty: none); spoiler hiding keeps only Together groups' ([WatchPartners.spoilerProgress]). */
val LocalPartnerProgress = staticCompositionLocalOf<List<PartnerProgress>> { emptyList() }

/**
 * Home's "Watching together" row's own handling of its cards (null: no watch partners here).
 * [select]: OK on a card; true when it took it (an invite, the start card), else the card opens
 * or plays as usual. [hold]: holding OK on a card, with the row's usual Customize to offer too.
 */
class TogetherCards(
    val select: (MediaItem) -> Boolean,
    val hold: (MediaItem, customize: () -> Unit) -> Unit,
    /** Whether a card is about watching together (an invite, a shared title): only those answer here. */
    val shares: (MediaItem) -> Boolean = { true },
)

val LocalTogetherCards = staticCompositionLocalOf<TogetherCards?> { null }

/**
 * Whether an episode still is blurred: unwatched by the user, or (on a shared show) by a partner;
 * and, whatever the user's spoiler settings, anything a group's schedule locks for them now.
 */
@Composable
fun spoilerStill(item: MediaItem): Boolean {
    val info = dev.nitrate.tv.ui.components.LocalPartnersInfo.current
    val progress = WatchPartners.spoilerProgress(LocalPartnerProgress.current, info)
    return dev.nitrate.core.partners.Spoilers.picture(LocalDisplayPrefs.current, item, progress, info, lockedNow(item))
}

/** Whether a description waits behind "Show": by the same rules as [spoilerStill] (films too). */
@Composable
fun descriptionHidden(item: MediaItem): Boolean {
    val info = dev.nitrate.tv.ui.components.LocalPartnersInfo.current
    val progress = WatchPartners.spoilerProgress(LocalPartnerProgress.current, info)
    return dev.nitrate.core.partners.Spoilers.description(LocalDisplayPrefs.current, item, progress, info, lockedNow(item))
}

/**
 * [items] as every screen shows them (core `Spoilers.safe`): an episode that would give something
 * away named "Episode 5", without its description and blurred; a film's description held back. Rows,
 * details pages, the player and playlists all show these, so none can skip the rule.
 */
@Composable
fun spoilerSafe(items: List<MediaItem>): List<MediaItem> {
    val prefs = LocalDisplayPrefs.current
    val info = dev.nitrate.tv.ui.components.LocalPartnersInfo.current
    val standings = dev.nitrate.tv.ui.components.LocalGroupStandings.current
    val progress = WatchPartners.spoilerProgress(LocalPartnerProgress.current, info)
    return androidx.compose.runtime.remember(items, prefs, info, standings, progress) {
        items.map { i -> dev.nitrate.core.partners.Spoilers.safe(i, prefs, progress, info) { dev.nitrate.core.partners.PartLock.of(it, info, standings) != null } }
    }
}

/** Blurs a picture of [item] when it's shown spoiler-safe ([spoilerSafe]); any picture of a title goes through this. */
fun Modifier.spoilerBlur(item: MediaItem): Modifier = if (item.spoiler) this.then(Modifier.blur(24.dp)) else this

/** One item as screens show it (see the list form). */
@Composable
fun spoilerSafe(item: MediaItem): MediaItem = spoilerSafe(listOf(item)).first()

/** Rows as screens show them: each row's titles spoiler-safe. */
@Composable
fun spoilerSafeRows(rows: List<dev.nitrate.core.rows.MediaRow>): List<dev.nitrate.core.rows.MediaRow> {
    val all = spoilerSafe(rows.flatMap { it.items })
    return androidx.compose.runtime.remember(rows, all) {
        var at = 0
        rows.map { r -> r.copy(items = all.subList(at, at + r.items.size).also { at += r.items.size }) }
    }
}

/** Whether a group's schedule locks [item] for the user now (not out yet, or for watching together). */
@Composable
private fun lockedNow(item: MediaItem): Boolean =
    dev.nitrate.core.partners.PartLock.of(item, dev.nitrate.tv.ui.components.LocalPartnersInfo.current, dev.nitrate.tv.ui.components.LocalGroupStandings.current) != null

/** Invites someone to watch a show together: into one of the user's groups for it, or a new group. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WatchWithDialog(container: AppContainer, series: MediaItem, groupId: String? = null, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val info by container.partners.collectAsState()
    val people by produceState(emptyList<dev.nitrate.core.writing.UserRef>()) { value = runCatching { container.server.partnerPeople() }.getOrDefault(emptyList()) }
    val groups = info?.on(series.id).orEmpty()
    var target by remember { mutableStateOf(groupId) }
    var chosen by remember { mutableStateOf<String?>(null) }
    var typed by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var schedule by remember { mutableStateOf(dev.nitrate.core.partners.GroupSchedule()) }
    val focus = remember { FocusRequester() }
    var resultFocused by remember { mutableStateOf(false) }
    val inGroup = groups.firstOrNull { it.id == target }?.others?.map { it.id }.orEmpty().toSet()
    val everyone = (info?.groups.orEmpty().flatMap { it.others } + people).distinctBy { it.id }.filter { it.id !in inGroup }
    fun send() {
        error = null
        scope.launch {
            val titles = groups.firstOrNull { it.id == target }?.titles ?: listOf(dev.nitrate.core.partners.GroupTitle(series.id, series.name))
            runCatching { container.server.askPartner(chosen, typed.takeIf { chosen == null }, series.id, series.name, target, schedule.takeIf { target == null }, titles) }
                .onSuccess { result = "Sent. If that's someone on this server, they'll see it next time they open Nitrate."; container.refreshPartners() }
                .onFailure { error = it.message }
        }
    }
    TvDialog(onDismiss, width = 900) {
        Text("Watch ${groups.firstOrNull { it.id == target }?.titleNames ?: series.name} with…", style = MaterialTheme.typography.headlineSmall)
        if (result != null) {
            Text(result!!)
            Box(Modifier.padding(vertical = 8.dp, horizontal = 4.dp)) {
                Button(onClick = onDismiss, modifier = Modifier.focusRequester(focus).onFocusChanged { resultFocused = it.isFocused }) { Text("Done") }
            }
        } else {
            if (groups.isNotEmpty()) {
                Text("Invite into", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(target == null, { target = null }) { Text("A new group") }
                    groups.forEach { g -> FilterChip(target == g.id, { target = g.id }) { Text("With ${g.label}") } }
                }
            }
            Text("Pick everyone to invite. They'll each be asked to accept, and see who's in the group first. Everyone in a group sees each other's progress in what it watches.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (everyone.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                everyone.forEach { u -> FilterChip(chosen == u.id, { chosen = if (chosen == u.id) null else u.id; typed = "" }) { Text(u.name) } }
            }
            TvTextField(typed, { typed = it; if (it.isNotBlank()) chosen = null }, "Or their exact user name (for someone not listed)", modifier = Modifier.width(620.dp))
            // A new group's schedule is the inviter's choice; accepting agrees to it.
            if (target == null) SchedulePicker(schedule, lockable(container)) { schedule = it }
            Text("Nothing here tells you whether an account exists.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = ::send, enabled = chosen != null || typed.isNotBlank()) { Text("Send") }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.focusRequester(focus)) { Text("Cancel") }
            }
        }
    }
    // After sending, Done takes focus, asked until it has it (the Send button it replaces had it).
    LaunchedEffect(result) {
        repeat(20) {
            delay(50)
            runCatching { focus.requestFocus() }
            if (result == null || resultFocused) return@LaunchedEffect
        }
    }
}

/**
 * A group's schedule: the release schedule (Binge, every 2 days, every 3 days or weekly with its
 * day, and the time new parts open), the Group Watch Session (optional days and time), the
 * Together switch and, where the server keeps it ([lockable]), "If someone plays ahead", with what
 * they mean.
 */
@Composable
fun SchedulePicker(schedule: dev.nitrate.core.partners.GroupSchedule, lockable: Boolean = true, titles: List<dev.nitrate.core.partners.GroupTitle> = emptyList(), onChange: (dev.nitrate.core.partners.GroupSchedule) -> Unit) {
    PacePicker(schedule, onChange, titles = titles)
    PartyTimePicker(schedule, onChange)
    TogetherSwitch(schedule, onChange)
    if (lockable) PlayAheadPicker(schedule, onChange)
    Text(schedule.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Whether the server keeps a schedule's "If someone plays ahead" (Companion feature `schedulelock`; older plugins drop it). */
@Composable
fun lockable(container: AppContainer): Boolean =
    container.companion.collectAsState().value?.features?.contains(dev.nitrate.core.partners.GroupSchedule.LOCK_FEATURE) == true

/**
 * "If someone plays ahead": Ask first (the default) or Don't allow, with what the choice means.
 * Only while the schedule holds something back (paced, or Together on): binge with Together off has nothing to lock.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayAheadPicker(schedule: dev.nitrate.core.partners.GroupSchedule, onChange: (dev.nitrate.core.partners.GroupSchedule) -> Unit, first: Modifier = Modifier) {
    if (!schedule.active) return
    val g = dev.nitrate.core.partners.GroupSchedule
    Text(g.PLAY_AHEAD, style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        g.LOCK_CHOICES.forEachIndexed { i, (locked, label) ->
            FilterChip(schedule.locked == locked, { onChange(schedule.copy(locked = locked)) }, if (i == 0) first else Modifier) { Text(label) }
        }
    }
    Text(schedule.lockNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * The release schedule: Binge, Daily, Every few days (2–7), Weekly (any days, every week or every
 * other week) or Monthly (a day of the month); the time new parts open (an hour back or on, and the
 * minutes in 15-minute steps), how many each release opens, whether the first part waits for the
 * first release, and with several [titles] Separate or Sequential (in an order set here with ▲ ▼).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PacePicker(
    schedule: dev.nitrate.core.partners.GroupSchedule,
    onChange: (dev.nitrate.core.partners.GroupSchedule) -> Unit,
    first: Modifier = Modifier,
    titles: List<dev.nitrate.core.partners.GroupTitle> = emptyList(),
) {
    val g = dev.nitrate.core.partners.GroupSchedule
    val row = Arrangement.spacedBy(8.dp)
    Text("Release schedule", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = row, verticalArrangement = row) {
        g.CADENCES.forEachIndexed { i, (id, name) -> FilterChip(g.pickerCadence(schedule.cadence) == id, { onChange(schedule.picked(id)) }, if (i == 0) first else Modifier) { Text(name) } }
    }
    if (!schedule.limits) return
    when (g.pickerCadence(schedule.cadence)) {
        g.EVERY_FEW_DAYS -> {
            val every = when (schedule.cadence) { g.DAYS2 -> 2; g.DAYS3 -> 3; else -> schedule.everyDays }
            FlowRow(horizontalArrangement = row, verticalArrangement = row) {
                (2..7).forEach { n -> FilterChip(every == n, { onChange(schedule.copy(cadence = g.EVERY_FEW_DAYS, everyDays = n)) }) { Text("Every $n days") } }
            }
        }
        g.WEEKLY -> {
            FlowRow(horizontalArrangement = row, verticalArrangement = row) {
                g.DAYS.forEachIndexed { i, d -> FilterChip(i in schedule.days, { onChange(schedule.toggleDay(i)) }) { Text(d.take(3)) } }
            }
            FlowRow(horizontalArrangement = row, verticalArrangement = row) {
                listOf(1 to "Every week", 2 to "Every other week").forEach { (n, name) -> FilterChip(schedule.everyWeeks == n, { onChange(schedule.copy(everyWeeks = n)) }) { Text(name) } }
            }
        }
        g.MONTHLY -> Row(horizontalArrangement = row, verticalAlignment = Alignment.CenterVertically) {
            Text("On the")
            OutlinedButton(onClick = { onChange(schedule.copy(monthDay = (schedule.monthDay - 2).mod(28) + 1)) }, modifier = Modifier.semantics { contentDescription = "A day earlier in the month" }) { Text("−") }
            Text(g.ordinal(schedule.monthDay.coerceIn(1, 28)), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { onChange(schedule.copy(monthDay = schedule.monthDay % 28 + 1)) }, modifier = Modifier.semantics { contentDescription = "A day later in the month" }) { Text("+") }
            Text("of each month")
        }
    }
    // The time: an hour back or on, then the minutes.
    Row(horizontalArrangement = row, verticalAlignment = Alignment.CenterVertically) {
        Text("At")
        OutlinedButton(onClick = { onChange(schedule.hourStep(-1)) }, modifier = Modifier.semantics { contentDescription = "An hour earlier, now ${schedule.clock}" }) { Text("−") }
        Text(schedule.clock, style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { onChange(schedule.hourStep(1)) }, modifier = Modifier.semantics { contentDescription = "An hour later, now ${schedule.clock}" }) { Text("+") }
        g.MINUTES.forEach { m -> FilterChip(schedule.minute == m, { onChange(schedule.copy(minute = m)) }) { Text(":" + m.toString().padStart(2, '0')) } }
    }
    Text("Each release opens", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = row, verticalArrangement = row) {
        g.PER_RELEASE.forEach { (n, name) -> FilterChip(schedule.perRelease == n, { onChange(schedule.copy(perRelease = n)) }) { Text(name) } }
    }
    Text("The first part", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = row, verticalArrangement = row) {
        g.FIRST_CHOICES.forEach { (on, name) -> FilterChip(schedule.firstLocked == on, { onChange(schedule.copy(firstLocked = on)) }) { Text(name) } }
    }
    if (titles.size > 1) {
        Text("Titles", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = row, verticalArrangement = row) {
            g.ORDER_CHOICES.forEach { (on, name) -> FilterChip(schedule.sequential == on, { onChange(schedule.copy(sequential = on)) }) { Text(name) } }
        }
        if (schedule.sequential) {
            val ordered = schedule.ordered(titles)
            ordered.forEachIndexed { i, t ->
                Row(horizontalArrangement = row, verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}. ${t.name}", Modifier.width(420.dp))
                    // Buttons that can't move it further still take focus (so ◀▶ along the line never jumps away), and do nothing.
                    OutlinedButton(onClick = { if (i > 0) onChange(schedule.moved(titles, t.id, -1)) }, modifier = Modifier.semantics { contentDescription = "Move ${t.name} up" }) { Text("▲") }
                    OutlinedButton(onClick = { if (i < ordered.lastIndex) onChange(schedule.moved(titles, t.id, 1)) }, modifier = Modifier.semantics { contentDescription = "Move ${t.name} down" }) { Text("▼") }
                }
            }
        }
        Text(g.ORDER_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The Together switch: the whole line toggles it, and says what on or off means. */
@Composable
fun TogetherSwitch(schedule: dev.nitrate.core.partners.GroupSchedule, onChange: (dev.nitrate.core.partners.GroupSchedule) -> Unit, modifier: Modifier = Modifier) {
    androidx.tv.material3.ListItem(
        selected = false,
        onClick = { onChange(schedule.copy(together = !schedule.together)) },
        headlineContent = { Text("Together: " + if (schedule.together) "On" else "Off") },
        supportingContent = { Text(schedule.togetherNote) },
        trailingContent = { androidx.tv.material3.Switch(checked = schedule.together, onCheckedChange = null) },
        modifier = modifier.width(760.dp),
    )
}

/** Asks the group to change its schedule: it takes effect once most of the group agrees (both, in a pair). */
@Composable
fun ScheduleChangeDialog(container: AppContainer, group: dev.nitrate.core.partners.WatchGroup, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var schedule by remember { mutableStateOf(group.schedule ?: dev.nitrate.core.partners.GroupSchedule()) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    TvDialog(onDismiss, width = 900) {
        Text("Change the schedule for ${group.titleNames}", style = MaterialTheme.typography.headlineSmall)
        Text("Now: ${group.schedule?.label ?: "Binge"}. ${if (group.others.size == 1) "${group.label} has to agree" else "Most of the group has to agree"} before it changes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        SchedulePicker(schedule, lockable(container), group.titles) { schedule = it }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { scope.launch { val e = container.proposeSchedule(group.id, schedule); if (e != null) error = e else onDismiss() } }, modifier = Modifier.focusRequester(focus)) { Text("Ask the group") }
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        }
    }
    LaunchedEffect(Unit) { delay(60); runCatching { focus.requestFocus() } }
}

/**
 * What an invite means ([dev.nitrate.core.partners.InviteSummary]): what, with whom and exactly who
 * learns the user's name and progress, as labelled lines; then the group's schedule as short points.
 */
@Composable
fun InviteDetails(summary: dev.nitrate.core.partners.InviteSummary, note: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        summary.lines.forEach { (label, value) ->
            Column {
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(value, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (summary.rules.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("The group's schedule", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                summary.rules.forEach { rule ->
                    Row {
                        Text("•", Modifier.width(20.dp), style = MaterialTheme.typography.bodyLarge)
                        Text(rule, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        summary.agrees?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary) }
    }
}

/** Settings → Watch partners: privacy, requests, partners and their shows. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WatchPartnersScreen(container: AppContainer, onOpenShow: (id: String, groupId: String?) -> Unit) {
    val scope = rememberCoroutineScope()
    val info by container.partners.collectAsState()
    var privacy by remember { mutableStateOf<dev.nitrate.core.partners.Privacy?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var leaving by remember { mutableStateOf<dev.nitrate.core.partners.WatchGroup?>(null) }
    var inviting by remember { mutableStateOf<dev.nitrate.core.partners.WatchGroup?>(null) }
    var starting by remember { mutableStateOf(false) }
    var changingSchedule by remember { mutableStateOf<dev.nitrate.core.partners.WatchGroup?>(null) }
    var answeringInvite by remember { mutableStateOf<dev.nitrate.core.partners.IncomingRequest?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        container.refreshPartners()
        privacy = runCatching { container.server.privacy() }.getOrNull()
    }
    fun act(block: suspend () -> Unit) = scope.launch {
        error = null
        runCatching { block() }.onFailure { error = it.message }
        container.refreshPartners()
    }
    answeringInvite?.let { r -> InviteAnswerDialog(container, r) { answeringInvite = null; act { } } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 58.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Watch groups", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Watch a show, film, playlist or collection with a group, and everyone in it sees each other's progress. Home's Watching together row shows whose turn it is; hold OK on a card there for more. One title can have several groups.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        item {
            val p = privacy
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(p?.isPrivate == true, { p?.let { act { privacy = container.server.setPrivacy(!it.isPrivate) } } }, enabled = p != null && !p.byAdmin, modifier = Modifier.focusRequester(first)) {
                    Text("Keep me private")
                }
                Text(
                    if (p?.byAdmin == true) "Your server's admin keeps your account private."
                    else "You won't appear in anyone's list of people or be found by name. Someone who knows your exact user name can still send you a request; only if you accept do they learn it's you.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val incoming = info?.incoming.orEmpty()
        if (incoming.isNotEmpty()) {
            item { Text("Asking you", style = MaterialTheme.typography.titleLarge) }
            incoming.forEach { r ->
                item(key = "in-${r.id}") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.width(560.dp)) {
                            Text("${r.fromName}: ${r.titleNames}")
                            Text("Would see you: ${WatchPartners.names(r.willSee)}" + (r.schedule?.takeIf { it.kept }?.let { " · ${it.label}" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val note by androidx.compose.runtime.produceState<String?>(null, r.id) { value = WatchPartners.unopenableNote(WatchPartners.unopenable(container.server, r.titles)) }
                            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
                        }
                        // Answered in the invite's own prompt, which shows what accepting agrees to (the schedule too).
                        Button(onClick = { answeringInvite = r }) { Text("Answer") }
                    }
                }
            }
        }
        item { Text("Your groups", style = MaterialTheme.typography.titleLarge) }
        item { OutlinedButton(onClick = { starting = true }) { Text("Watch something with someone") } }
        val groups = info?.groups.orEmpty()
        if (groups.isEmpty()) item { Text(if (info == null) "Loading…" else "None yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        groups.forEach { g ->
            item(key = "g-${g.id}") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.width(480.dp)) {
                        Text(g.titleNames, style = MaterialTheme.typography.titleMedium)
                        Text(groupLine(g), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        // Someone in the group can't open one of its titles here (library access, parental rating).
                        g.titles.mapNotNull { it.reachNote }.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
                        g.pending?.let { p -> Text("${p.fromName} asks: ${p.proposed.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        g.party?.let { p -> Text(p.line(System.currentTimeMillis()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (g.party != null) OutlinedButton(onClick = { container.parties.open(g) }) { Text("Group Watch Session") }
                    OutlinedButton(onClick = { changingSchedule = g }) { Text("Schedule") }
                    OutlinedButton(onClick = { onOpenShow(g.seriesId, g.id) }) { Text("Open") }
                    OutlinedButton(onClick = { inviting = g }) { Text("Invite someone") }
                    OutlinedButton(onClick = { leaving = g }) { Text("Leave") }
                }
            }
        }
        val outgoing = info?.outgoing.orEmpty()
        if (outgoing.isNotEmpty()) {
            item { Text("Waiting for an answer (up to 14 days)", style = MaterialTheme.typography.titleLarge) }
            outgoing.forEach { r ->
                item(key = "out-${r.id}") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${r.titleNames} with ${r.to}", modifier = Modifier.width(560.dp))
                        OutlinedButton(onClick = { act { container.server.cancelPartnerRequest(r.id) } }) { Text("Cancel") }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    leaving?.let { g ->
        val focus = remember { FocusRequester() }
        TvDialog({ leaving = null }) {
            Text("Leave ${g.titleNames} with ${g.label}?", style = MaterialTheme.typography.headlineSmall)
            Text(if (g.others.size == 1) "The group ends. Nothing you've watched changes." else "The others carry on without you. Nothing you've watched changes.")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { leaving = null }, modifier = Modifier.focusRequester(focus)) { Text("Stay") }
                Button(onClick = { leaving = null; act { container.server.leaveGroup(g.id) } }) { Text("Leave") }
            }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        }
    }
    inviting?.let { g ->
        WatchWithDialog(container, MediaItem(g.seriesId, g.seriesName, dev.nitrate.core.model.ItemKind.SERIES), g.id) { inviting = null }
    }
    if (starting) StartTogetherDialog(container) { starting = false }
    changingSchedule?.let { g -> ScheduleChangeDialog(container, g) { changingSchedule = null } }
}

/**
 * Provides [LocalTogetherCards] for Home and shows what its cards open: an invite's answer, the
 * choices for a shared show, picking a show to start with, inviting, leaving.
 */
@Composable
fun TogetherHost(container: AppContainer, enabled: Boolean, onPlay: (MediaItem) -> Unit, onOpenShow: (String, String?) -> Unit, content: @Composable () -> Unit) {
    var answering by remember { mutableStateOf<dev.nitrate.core.partners.IncomingRequest?>(null) }
    var holding by remember { mutableStateOf<Pair<MediaItem, () -> Unit>?>(null) }
    var starting by remember { mutableStateOf(false) }
    val info by container.partners.collectAsState()
    val cards = remember(enabled) {
        if (!enabled) null else TogetherCards(
            select = { item ->
                when {
                    dev.nitrate.core.partners.TogetherRow.isStart(item) -> { starting = true; true }
                    else -> dev.nitrate.core.partners.TogetherRow.inviteFor(item, container.partners.value)?.let { answering = it; true } ?: false
                }
            },
            hold = { item, customize -> holding = item to customize },
            shares = { item -> dev.nitrate.core.partners.TogetherRow.isShared(item, container.partners.value, container.groupStandings.value) },
        )
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalTogetherCards provides cards) { content() }
    answering?.let { r -> InviteAnswerDialog(container, r) { answering = null } }
    holding?.let { (item, customize) ->
        TogetherChoicesDialog(container, item, info, onPlay = onPlay, onOpenShow = onOpenShow, onStart = { starting = true }, onCustomize = customize) { holding = null }
    }
    if (starting) StartTogetherDialog(container) { starting = false }
}

/** OK on an invite's card: who's asking, who'd see the user, and the answer. */
@Composable
fun InviteAnswerDialog(container: AppContainer, r: dev.nitrate.core.partners.IncomingRequest, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    fun answer(choice: dev.nitrate.core.partners.TogetherChoice) = scope.launch { container.runTogether(choice); onDismiss() }
    TvDialog(onDismiss, width = 720) {
        val summary = remember(r) { dev.nitrate.core.partners.InviteSummary.of(r) }
        Text(summary.heading, style = MaterialTheme.typography.headlineSmall)
        val note by androidx.compose.runtime.produceState<String?>(null, r.id) { value = WatchPartners.unopenableNote(WatchPartners.unopenable(container.server, r.titles)) }
        InviteDetails(summary, note)
        // Accept first (focused), Not now, and Decline last in the error colour (it can't be undone).
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { answer(dev.nitrate.core.partners.TogetherChoice.Accept(r)) }, modifier = Modifier.focusRequester(focus)) { Text("Accept") }
            OutlinedButton(onClick = onDismiss) { Text("Not now") }
            OutlinedButton(onClick = { answer(dev.nitrate.core.partners.TogetherChoice.Decline(r)) }) { Text("Decline", color = MaterialTheme.colorScheme.error) }
        }
    }
    LaunchedEffect(Unit) { delay(50); runCatching { focus.requestFocus() } }
}

/** Holding OK on a "Watching together" card: [dev.nitrate.core.partners.TogetherRow.choices], then Customize this row. */
@Composable
fun TogetherChoicesDialog(
    container: AppContainer,
    item: MediaItem,
    info: dev.nitrate.core.partners.PartnersInfo?,
    onPlay: (MediaItem) -> Unit,
    /** "Open the show": its id, and the group whose card it was held on (its details page then plays for that group). */
    onOpenShow: (String, String?) -> Unit,
    onStart: () -> Unit,
    /** Holding OK on a row's card: "Customize this row" last. Null (from a details page): left out. */
    onCustomize: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val standings by container.groupStandings.collectAsState()
    val prefs = LocalDisplayPrefs.current
    val series = dev.nitrate.core.partners.TogetherRow.titleOf(item, standings)
    val episodes by produceState(emptyList<MediaItem>(), series) {
        value = if (series == null || dev.nitrate.core.partners.TogetherRow.isStart(item)) emptyList()
        else runCatching { container.server.titleItems(series) }.getOrDefault(emptyList())
    }
    val choices = dev.nitrate.core.partners.TogetherRow.choices(item, info, standings, episodes, container.sessionsAvailable(), prefs.ownSpoilerShows, System.currentTimeMillis(), canAsk = container.asksAvailable())
    var askingWhen by remember { mutableStateOf<dev.nitrate.core.partners.TogetherChoice.AskToWatch?>(null) }
    var asked by remember { mutableStateOf<String?>(null) }
    var leaving by remember { mutableStateOf<dev.nitrate.core.partners.WatchGroup?>(null) }
    var inviting by remember { mutableStateOf<String?>(null) }
    var changing by remember { mutableStateOf<dev.nitrate.core.partners.WatchGroup?>(null) }
    var adding by remember { mutableStateOf<dev.nitrate.core.partners.WatchGroup?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    fun run(c: dev.nitrate.core.partners.TogetherChoice) {
        when (c) {
            is dev.nitrate.core.partners.TogetherChoice.AddTitle -> adding = c.group
            // Everyone in the group here, then it plays from the group's card (it counts for them too).
            is dev.nitrate.core.partners.TogetherChoice.InTheRoom -> scope.launch {
                val e = container.runTogether(c)
                if (e != null) error = e else { onDismiss(); onPlay(c.item.copy(watchGroupId = c.groupId)) }
            }
            is dev.nitrate.core.partners.TogetherChoice.ChangeSchedule -> changing = c.group
            // Tonight's watch party: the same prompt as its reminder.
            is dev.nitrate.core.partners.TogetherChoice.Party -> { onDismiss(); container.parties.open(c.group) }
            is dev.nitrate.core.partners.TogetherChoice.Start -> { onDismiss(); onStart() }
            is dev.nitrate.core.partners.TogetherChoice.OpenShow -> { onDismiss(); onOpenShow(c.seriesId, c.groupId) }
            is dev.nitrate.core.partners.TogetherChoice.Invite -> inviting = c.groupId
            is dev.nitrate.core.partners.TogetherChoice.Leave -> leaving = c.group
            is dev.nitrate.core.partners.TogetherChoice.AskToWatch -> askingWhen = c
            is dev.nitrate.core.partners.TogetherChoice.WatchNow -> scope.launch {
                val e = container.runTogether(c)
                // Played for the group the live session was started for (no question about watching together).
                if (e != null) error = e else { onDismiss(); onPlay(c.episode.copy(watchGroupId = c.groupId)) }
            }
            else -> scope.launch { val e = container.runTogether(c); if (e != null) error = e else onDismiss() }
        }
    }
    changing?.let { g -> ScheduleChangeDialog(container, g) { changing = null; onDismiss() }; return }
    asked?.let { text ->
        TvDialog(onDismiss, width = 640) {
            Text(text)
            val ok = remember { FocusRequester() }
            Button(onClick = onDismiss, modifier = Modifier.focusRequester(ok)) { Text("OK") }
            LaunchedEffect(text) { delay(60); runCatching { ok.requestFocus() } }
        }
        return
    }
    askingWhen?.let { ask ->
        AskWhenDialog(ask, error, onBack = { askingWhen = null }) { minutes ->
            scope.launch {
                val e = container.groupAsks.ask(ask.groupId, ask.episode.id, minutes)
                if (e != null) error = e else asked = "Asked ${ask.names}. You'll hear when someone starts it."
            }
        }
        return
    }
    adding?.let { g -> AddTitlesDialog(container, g) { adding = null; onDismiss() }; return }
    if (leaving == null && inviting == null) TvDialog(onDismiss, width = 720) {
        Text(item.seriesName ?: item.name, style = MaterialTheme.typography.headlineSmall)
        info?.groups?.filter { it.holds(series ?: "") && (item.watchGroupId == null || it.id == item.watchGroupId) }?.forEach { g ->
            Text("With ${g.label} · ${groupLine(g, withNames = false)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        dev.nitrate.core.partners.TogetherRow.line(item, info, standings)?.let { Text(it.long, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        choices.forEachIndexed { i, c ->
            val m = if (i == 0) Modifier.focusRequester(focus) else Modifier
            if (c is dev.nitrate.core.partners.TogetherChoice.Leave) OutlinedButton(onClick = { run(c) }, modifier = m) { Text(c.label) }
            else Button(onClick = { run(c) }, modifier = m) { Text(c.label) }
        }
        if (onCustomize != null) OutlinedButton(onClick = { onDismiss(); onCustomize() }, modifier = if (choices.isEmpty()) Modifier.focusRequester(focus) else Modifier) { Text("Customize this row") }
    }
    LaunchedEffect(choices.size, leaving, inviting) { delay(60); runCatching { focus.requestFocus() } }
    leaving?.let { g ->
        val stay = remember { FocusRequester() }
        TvDialog({ leaving = null }) {
            Text("Leave ${g.titleNames} with ${g.label}?", style = MaterialTheme.typography.headlineSmall)
            Text(if (g.others.size == 1) "The group ends. Nothing you've watched changes." else "The others carry on without you. Nothing you've watched changes.")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { leaving = null }, modifier = Modifier.focusRequester(stay)) { Text("Stay") }
                Button(onClick = { scope.launch { container.runTogether(dev.nitrate.core.partners.TogetherChoice.Leave(g)); leaving = null; onDismiss() } }) { Text("Leave") }
            }
            LaunchedEffect(Unit) { delay(50); runCatching { stay.requestFocus() } }
        }
    }
    inviting?.let { groupId ->
        val name = info?.groups?.firstOrNull { it.id == groupId }?.seriesName ?: item.seriesName ?: item.name
        WatchWithDialog(container, MediaItem(info?.groups?.firstOrNull { it.id == groupId }?.seriesId ?: series ?: item.id, name, dev.nitrate.core.model.ItemKind.SERIES), groupId) { inviting = null; onDismiss() }
    }
}

/** "Watch something with someone": the guided setup ([GroupSetupDialog]). */
@Composable
fun StartTogetherDialog(container: AppContainer, onDismiss: () -> Unit) = GroupSetupDialog(container, onDismiss = onDismiss)

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
fun TitlePicker(container: AppContainer, isPicked: (String) -> Boolean, picked: List<String>, onToggle: (MediaItem) -> Unit, field: Modifier = Modifier) {
    var query by remember { mutableStateOf("") }
    val shows by produceState(emptyList<MediaItem>(), query) {
        delay(if (query.isBlank()) 0 else 350)
        value = container.togetherShows(query)
    }
    TvTextField(query, { query = it }, "Find a show, film, playlist or collection", modifier = field.width(620.dp))
    if (picked.isNotEmpty()) Text("Picked: " + WatchPartners.names(picked), color = MaterialTheme.colorScheme.primary)
    Text(if (query.isBlank()) "Watched lately" else "Found", style = MaterialTheme.typography.titleSmall)
    if (shows.isEmpty()) Text(if (query.isBlank()) "Type a name." else "Nothing found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    shows.forEach { s ->
        androidx.tv.material3.ListItem(
            selected = isPicked(s.id),
            onClick = { onToggle(s) },
            headlineContent = { Text(listOfNotNull(s.name, s.productionYear?.toString(), dev.nitrate.core.partners.TogetherRow.kindLabel(s)).joinToString(" · ")) },
            trailingContent = { androidx.tv.material3.Switch(checked = isPicked(s.id), onCheckedChange = null) },
            modifier = Modifier.width(760.dp),
        )
    }
}

/**
 * "Watch something with someone", step by step ([dev.nitrate.core.partners.GroupSetup]): what
 * (one or more titles), who, the release schedule, the Group Watch Session (optional), Together on
 * or off; then the invite is sent. A side panel: focus on each page's first choice, Back steps
 * back a page (from the first, closes). Easy mode: the same steps, without the explanations.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GroupSetupDialog(container: AppContainer, start: dev.nitrate.core.partners.GroupSetup = dev.nitrate.core.partners.GroupSetup(), onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val easy = LocalDisplayPrefs.current.easyMode
    val info by container.partners.collectAsState()
    val people by produceState(emptyList<dev.nitrate.core.writing.UserRef>()) { value = runCatching { container.server.partnerPeople() }.getOrDefault(emptyList()) }
    // Several people into one group when the server can (feature `invitebatch`), else one as before.
    val several = container.companion.collectAsState().value?.features?.contains("invitebatch") == true
    var setup by remember { mutableStateOf(start) }
    if (setup.several != several) setup = setup.copy(several = several)
    var sent by remember { mutableStateOf(false) }
    var doneFocused by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val first = remember(setup.step, sent) { FocusRequester() }
    val back = { if (sent) onDismiss() else setup.back()?.let { setup = it; error = null } ?: onDismiss() }
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
    TvDialog(back, width = 900) {
        // Back as a key too (a page steps back one), so one press always does it.
        Column(
            Modifier.onKeyEvent { e ->
                if (e.key != androidx.compose.ui.input.key.Key.Back && e.key != androidx.compose.ui.input.key.Key.Escape) return@onKeyEvent false
                if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyUp) back()
                true
            },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Watch something with someone", style = MaterialTheme.typography.headlineSmall)
            if (sent) {
                Text(dev.nitrate.core.partners.GroupSetup.SENT)
                // Room around it so its focused (larger) look isn't cut off at the dialog's edge.
                Box(Modifier.padding(vertical = 8.dp, horizontal = 4.dp)) {
                    Button(onClick = onDismiss, modifier = Modifier.focusRequester(first).onFocusChanged { doneFocused = it.isFocused }) { Text("Done") }
                }
                return@Column
            }
            Text(setup.heading, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            // The first page says how a group counts: a track of its own for each of you, apart from your own.
            setup.note?.let { Text(it, color = MaterialTheme.colorScheme.onSurface) }
            when (setup.step) {
                dev.nitrate.core.partners.GroupSetup.Step.WHAT -> {
                    if (!easy) Text("Pick one or more: shows, films, playlists or collections. The group keeps pace on each.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TitlePicker(container, setup::picked, setup.titles.map { it.name }, { setup = setup.toggle(it) }, Modifier.focusRequester(first))
                }
                dev.nitrate.core.partners.GroupSetup.Step.WHO -> {
                    if (!easy) Text("Pick everyone to invite. They'll each be asked to accept, and see who's in the group first. Everyone in a group sees each other's progress in what it watches.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // People known from other groups, then everyone listed (never private accounts).
                    val everyone = (info?.groups.orEmpty().flatMap { it.others } + people).distinctBy { it.id }
                    if (everyone.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        everyone.forEachIndexed { i, u -> FilterChip(setup.picked(u.id), { setup = setup.pick(u.id) }, if (i == 0) Modifier.focusRequester(first) else Modifier) { Text(u.name) } }
                    }
                    TvTextField(setup.typed, { setup = setup.type(it) }, if (setup.several) "And/or exact user names, separated by commas (for people not listed)" else "Or their exact user name (for someone not listed)", modifier = (if (everyone.isEmpty()) Modifier.focusRequester(first) else Modifier).width(620.dp))
                    if (!easy) Text("Nothing here tells you whether an account exists.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                dev.nitrate.core.partners.GroupSetup.Step.PACE -> {
                    if (!easy) Text("How new parts are let out to the group. Anything someone has watched with the group stays open; whether anyone may play ahead is the last step's choice.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PacePicker(setup.schedule, { setup = setup.copy(schedule = it) }, Modifier.focusRequester(first), setup.titles)
                }
                dev.nitrate.core.partners.GroupSetup.Step.SESSION -> {
                    if (!easy) Text("Optional: a regular time to meet and watch the next part together, with a reminder 15 minutes before.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PartyTimePicker(setup.schedule, { setup = setup.copy(schedule = it) }, Modifier.focusRequester(first))
                }
                dev.nitrate.core.partners.GroupSetup.Step.TOGETHER -> {
                    TogetherSwitch(setup.schedule, { setup = setup.copy(schedule = it) }, Modifier.focusRequester(first))
                    if (!easy) Text(if (setup.schedule.together) dev.nitrate.core.partners.GroupSchedule.TOGETHER_OFF else dev.nitrate.core.partners.GroupSchedule.TOGETHER_ON, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // Whether anyone may play ahead of what the schedule and Together hold back (shown once something is held back).
                    if (lockable(container)) PlayAheadPicker(setup.schedule, { setup = setup.copy(schedule = it) })
                    Text(setup.summary, style = MaterialTheme.typography.titleSmall)
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Kept focusable while sending (a disabled button lets focus fall out of the dialog); a second press does nothing.
                Button(onClick = { if (!sending) go() }, enabled = setup.canGoOn) { Text(if (setup.last) (if (sending) "Sending…" else "Send the invite") else "Next") }
                OutlinedButton(onClick = back) { Text(if (setup.back() == null) "Cancel" else "Back") }
            }
        }
    }
    // Each page's first thing takes focus, asked until it has it (after sending, Done: the Send button it
    // replaces had focus, and one early ask could land before Done was placed, leaving focus nowhere clear).
    LaunchedEffect(setup.step, sent) {
        repeat(20) {
            delay(60)
            runCatching { first.requestFocus() }
            if (!sent || doneFocused) return@LaunchedEffect
        }
    }
}

/** "Add a title to the group": pick one or more, then they're added (any member may). */
@Composable
fun AddTitlesDialog(container: AppContainer, group: dev.nitrate.core.partners.WatchGroup, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf(emptyList<MediaItem>()) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    TvDialog(onDismiss, width = 900) {
        Text("Add to ${group.titleNames} with ${group.label}", style = MaterialTheme.typography.headlineSmall)
        TitlePicker(
            container, { id -> picked.any { it.id == id } || group.holds(id) }, picked.map { it.name },
            { s -> if (!group.holds(s.id)) picked = if (picked.any { it.id == s.id }) picked.filter { it.id != s.id } else picked + s },
            Modifier.focusRequester(focus),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { scope.launch { val e = container.addGroupTitles(group.id, picked); if (e != null) error = e else onDismiss() } }, enabled = picked.isNotEmpty()) { Text("Add") }
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        }
    }
    LaunchedEffect(Unit) { delay(60); runCatching { focus.requestFocus() } }
}

/**
 * "Also count it for Suzy, where it's next too?" after a playback from a group's card that's
 * next in other groups too: Just this group (focused) / All groups where it's next. Shown once
 * the player has closed; Back keeps it to this group.
 */
@Composable
fun CountingPrompt(container: AppContainer) {
    val asks by container.countAsks.collectAsState()
    val ask = asks.firstOrNull() ?: return
    CountingDialog(ask) { all -> container.saveScope.launch { container.answerCounting(ask, all) } }
}

/** [CountingPrompt]'s panel: the question, Just this group (focused), All groups where it's next. */
@Composable
fun CountingDialog(ask: dev.nitrate.core.partners.CountAsk, onAnswer: (all: Boolean) -> Unit) {
    val focus = remember(ask) { FocusRequester() }
    TvDialog({ onAnswer(false) }, width = 640) {
        Text(ask.question, style = MaterialTheme.typography.headlineSmall)
        Text("${ask.what} counted for your group with ${ask.counting.groups.joinToString { it.label }}.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { onAnswer(false) }, modifier = Modifier.focusRequester(focus)) { Text(dev.nitrate.core.partners.CountAsk.JUST_THIS) }
        OutlinedButton(onClick = { onAnswer(true) }) { Text(dev.nitrate.core.partners.CountAsk.ALL) }
    }
    LaunchedEffect(ask) { delay(60); runCatching { focus.requestFocus() } }
}


/** "Ask … to watch together": when (Now focused, then the postpone steps); Back steps back to the choices. */
@Composable
fun AskWhenDialog(ask: dev.nitrate.core.partners.TogetherChoice.AskToWatch, error: String?, onBack: () -> Unit, onWhen: (Int) -> Unit) {
    val first = remember { FocusRequester() }
    TvDialog(onBack, width = 720) {
        Text("Ask ${ask.names} to watch together", style = MaterialTheme.typography.headlineSmall)
        Text(
            "${ask.what}. Whoever says yes first starts the Group Watch Session on their device, and you join it.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("When?", style = MaterialTheme.typography.titleMedium)
        dev.nitrate.core.partners.WatchAsk.WHEN.forEachIndexed { i, m ->
            Button(onClick = { onWhen(m) }, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) { Text(dev.nitrate.core.partners.WatchAsk.whenLabel(m)) }
        }
    }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
}
