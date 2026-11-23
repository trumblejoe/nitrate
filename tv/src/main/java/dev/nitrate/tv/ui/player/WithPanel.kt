package dev.nitrate.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.nitrate.android.AppContainer
import dev.nitrate.core.companion.TimelineChat
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.partners.WatchCompany
import dev.nitrate.core.partners.WatchGroup
import dev.nitrate.core.partners.WatchingWith
import dev.nitrate.core.partners.WithChip
import dev.nitrate.core.writing.UserRef
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Who this playback is watched with (the "With…" chip): one per player, told as each item starts and
 * where it's got to (what's played to the end counts for whoever's in the room), steering
 * [groupChat] to the group it counts for. Null without [container] or when not [enabled].
 */
@Composable
internal fun rememberWatchCompany(
    container: AppContainer?,
    item: MediaItem?,
    enabled: Boolean,
    groupChat: TimelineChat?,
    positionMs: () -> Long,
    durationMs: () -> Long,
): WatchCompany? {
    if (container == null || !enabled) return null
    val scope = rememberCoroutineScope()
    val company = remember { container.watchCompany(scope) }
    val position by rememberUpdatedState(positionMs)
    val duration by rememberUpdatedState(durationMs)
    val last = remember { mutableStateOf<Triple<String, Long, Long>?>(null) }
    LaunchedEffect(company, item?.id) {
        val i = item ?: return@LaunchedEffect
        last.value?.takeIf { it.first != i.id }?.let { (id, at, of) -> company.left(id, at, of) }
        company.start(i, container.groupStandings.value)
        while (true) {
            last.value = Triple(i.id, position(), duration())
            delay(1_000)
        }
    }
    DisposableEffect(company) { onDispose { last.value?.let { (id, at, of) -> company.left(id, at, of) } } }
    LaunchedEffect(company, groupChat) {
        company.chip.collect { c -> groupChat?.focus(if (c == null || c.partyOn) null else c.context.groups.map { it.id }) }
    }
    return company
}

/**
 * More than one group would count this playback and none was chosen: which one, plainly, for about
 * five seconds as it starts ("Counting for: Will"). It never takes focus: the With… button in the
 * controls changes it.
 */
@Composable
internal fun CountingBanner(chip: WithChip?, modifier: Modifier = Modifier) {
    val title = chip?.context?.titleId
    var shownFor by remember { mutableStateOf<String?>(null) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(title, chip?.ambiguous) {
        if (chip == null || !chip.ambiguous || title == null || shownFor == title) return@LaunchedEffect
        shownFor = title
        visible = true
        delay(COUNTING_MS)
        visible = false
    }
    if (visible && chip != null) {
        Text(
            chip.counting,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.Black,
            modifier = modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)).padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}

internal const val COUNTING_MS = 5_000L

/** The With… panel wired to the app: who's here, Counting for (Change), Watch now together, the same room, group chat, bringing notes. */
@Composable
internal fun PlayerWithPanel(
    container: AppContainer,
    company: WatchCompany,
    chip: WithChip,
    item: MediaItem?,
    groupChat: TimelineChat?,
    chatAvailable: Boolean,
    onGroupChat: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var problem by remember { mutableStateOf<String?>(null) }
    val chatOn = groupChat?.on?.collectAsState()?.value
    val broughtBy = groupChat?.broughtBy?.collectAsState()?.value
    var broughtMine by remember { mutableStateOf<String?>(null) }
    val me = container.server.userId
    WithPanel(
        chip = chip,
        problem = problem,
        chatOn = chatOn?.takeIf { chatAvailable },
        brought = broughtText(chip, broughtBy, me, broughtMine),
        people = { runCatching { container.server.partnerPeople() }.getOrDefault(emptyList()).filter { it.id != me } },
        onCountFor = { company.countFor(it) },
        onStartParty = { scope.launch { problem = item?.let { container.startWatchParty(it, chip.group) } } },
        onInvite = { ids -> scope.launch { problem = container.inviteToParty(ids, item) } },
        onAddToGroup = { group, ids -> scope.launch { problem = container.inviteToGroup(group, ids) } },
        onSameRoom = { group, ids -> company.sameRoom(group, ids) },
        onGroupChat = if (groupChat != null && chatAvailable) onGroupChat else null,
        onBring = if (groupChat != null && chip.partyOn) { groupId ->
            scope.launch {
                groupChat.bring(groupId)
                    .onSuccess { broughtMine = groupId; problem = null }
                    .onFailure { problem = it.message ?: "Couldn't bring the notes" }
            }
        } else null,
        onDismiss = onDismiss,
    )
}

/** "You brought your notes with Will", "Will brought earlier notes" (null: none brought). */
internal fun broughtText(chip: WithChip, by: String?, me: String?, mine: String?): String? {
    if (by == null && mine == null) return null
    if (by == null || by == me) return chip.groups.firstOrNull { it.id == mine }?.let { "You brought your notes with " + WithChip.nameOf(it) } ?: "You brought earlier notes"
    val name = chip.groups.flatMap { it.others }.firstOrNull { it.id == by }?.name
    return (name ?: "Someone watching") + " brought earlier notes"
}

private enum class WithPage { MAIN, ROOM, INVITE, ADD, BRING }

/**
 * The With… panel, beside the video: who's here, Counts for (each group on the title, or just
 * you), the watch party (start one, or invite someone, then offer to add them to the group too),
 * "We're in the same room" (pick who's here), Group chat on or off, and in a party "Bring our
 * earlier notes". Focus starts on the first choice of each page; Back goes back a page, then closes.
 */
@Composable
fun WithPanel(
    chip: WithChip,
    problem: String? = null,
    chatOn: Boolean? = null,
    brought: String? = null,
    people: suspend () -> List<UserRef> = { emptyList() },
    onCountFor: (WatchingWith) -> Unit = {},
    onStartParty: () -> Unit = {},
    onInvite: (List<String>) -> Unit = {},
    onAddToGroup: (WatchGroup, List<String>) -> Unit = { _, _ -> },
    onSameRoom: (groupId: String, present: List<String>) -> Unit = { _, _ -> },
    onGroupChat: ((Boolean) -> Unit)? = null,
    onBring: ((String?) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    var page by remember { mutableStateOf(WithPage.MAIN) }
    var outsiders by remember { mutableStateOf<List<UserRef>>(emptyList()) }
    val first = remember(page) { FocusRequester() }
    val back = { if (page == WithPage.MAIN) onDismiss() else page = WithPage.MAIN }
    dev.nitrate.tv.ui.lists.TvDialog(onDismiss = back, width = 600) {
        // Back as a key too (a page goes back one step), so one press always does it.
        androidx.compose.foundation.layout.Column(
            Modifier.onKeyEvent { e ->
                if (e.key != Key.Back && e.key != Key.Escape) return@onKeyEvent false
                if (e.type == KeyEventType.KeyUp) back()
                true
            },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
        when (page) {
            WithPage.MAIN -> {
                Text(chip.label, style = MaterialTheme.typography.headlineSmall)
                Text(chip.who, color = MaterialTheme.colorScheme.onSurfaceVariant)
                var firstUsed = false
                fun firstOne(): Modifier = if (firstUsed) Modifier else Modifier.focusRequester(first).also { firstUsed = true }
                if (chip.groups.isNotEmpty() && !chip.partyOn) {
                    Heading("Counting for")
                    val chosen = chip.group?.id
                    chip.groups.forEach { g ->
                        Choice("With " + WithChip.nameOf(g), selected = chosen == g.id, firstOne()) { onCountFor(WatchingWith.Group(g.id)) }
                    }
                    Choice("No group", selected = chosen == null, Modifier) { onCountFor(WatchingWith.JustMe) }
                }
                Heading("Watch now together")
                if (chip.partyOn) {
                    Action("Invite someone to watch now", "They get an invite to join and watch in step", firstOne()) { page = WithPage.INVITE }
                } else {
                    Action(
                        "Watch now together" + (chip.group?.let { " with " + WithChip.nameOf(it) } ?: ""),
                        "Play in step on each other's screens; pausing pauses for everyone", firstOne(), onStartParty,
                    )
                }
                if (chip.group != null && !chip.partyOn) {
                    Action(
                        "We're in the same room",
                        if (chip.roomNames.isEmpty()) "Pick who's watching here with you: it counts for them too" else "Here: " + WithChip.and(chip.roomNames) + " · change",
                        Modifier,
                    ) { page = WithPage.ROOM }
                }
                if (chatOn != null && onGroupChat != null) {
                    ListItem(
                        selected = false,
                        onClick = { onGroupChat(!chatOn) },
                        headlineContent = { Text("Group chat: " + if (chatOn) "On" else "Off") },
                        supportingContent = { Text(if (chatOn) "Your group's notes pop up as you reach them" else "No pop-ups, nothing sent") },
                        trailingContent = { androidx.tv.material3.Switch(checked = chatOn, onCheckedChange = null) },
                    )
                }
                if (onBring != null && chip.groups.isNotEmpty()) {
                    Action("Bring our earlier notes", brought ?: "Your group's notes on this pop up for everyone watching, this time only", Modifier) { page = WithPage.BRING }
                } else brought?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            WithPage.ROOM -> chip.group?.let { g ->
                var here by remember { mutableStateOf(chip.room?.takeIf { it.groupId == g.id }?.present?.toSet() ?: g.others.map { it.id }.toSet()) }
                Text("We're in the same room", style = MaterialTheme.typography.headlineSmall)
                Text("Who's watching here with you? What you play to the end counts for them too.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                g.others.forEachIndexed { i, p ->
                    Toggle(p.name, p.id in here, if (i == 0) Modifier.focusRequester(first) else Modifier) { here = if (p.id in here) here - p.id else here + p.id }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.tv.material3.Button(onClick = { onSameRoom(g.id, g.others.map { it.id }.filter { it in here }); page = WithPage.MAIN }) {
                        Text(if (here.isEmpty()) "Nobody else here" else "Done")
                    }
                    androidx.tv.material3.OutlinedButton(onClick = { page = WithPage.MAIN }) { Text("Back") }
                }
            }
            WithPage.INVITE -> {
                val list by produceState<List<UserRef>?>(null) { value = people() }
                var chosen by remember { mutableStateOf(emptySet<String>()) }
                val group = chip.group ?: chip.groups.firstOrNull()
                Text("Invite someone to watch now", style = MaterialTheme.typography.headlineSmall)
                when {
                    list == null -> Text("Loading…")
                    list!!.isEmpty() -> Text("Nobody else to invite on this server.")
                    else -> list!!.forEachIndexed { i, p ->
                        Toggle(p.name, p.id in chosen, if (i == 0) Modifier.focusRequester(first) else Modifier) { chosen = if (p.id in chosen) chosen - p.id else chosen + p.id }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.tv.material3.Button(enabled = chosen.isNotEmpty(), onClick = {
                        onInvite(chosen.toList())
                        // Someone outside the title's group: ask about adding them to it, the usual group invite.
                        val members = group?.others.orEmpty().map { it.id }.toSet()
                        outsiders = list.orEmpty().filter { it.id in chosen && it.id !in members }
                        page = if (group != null && outsiders.isNotEmpty()) WithPage.ADD else WithPage.MAIN
                    }) { Text("Invite") }
                    androidx.tv.material3.OutlinedButton(onClick = { page = WithPage.MAIN }) { Text("Back") }
                }
            }
            WithPage.ADD -> (chip.group ?: chip.groups.firstOrNull())?.let { group ->
                Text("Add them to the group too?", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Invites sent. ${dev.nitrate.core.partners.WatchPartners.names(outsiders.map { it.name })} ${if (outsiders.size == 1) "isn't" else "aren't"} in your group with " +
                        "${WithChip.nameOf(group)}. Ask them to join it, to keep pace on ${group.seriesName} after tonight too? They'll see an invite to the group.",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.tv.material3.Button(onClick = { onAddToGroup(group, outsiders.map { it.id }); page = WithPage.MAIN }, modifier = Modifier.focusRequester(first)) { Text("Ask them") }
                    androidx.tv.material3.OutlinedButton(onClick = { page = WithPage.MAIN }) { Text("Not now") }
                }
            }
            WithPage.BRING -> {
                Text("Bring our earlier notes", style = MaterialTheme.typography.headlineSmall)
                Text("Notes from one of your groups on this pop up for everyone watching as playback passes them, this time only. Nobody keeps them.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                chip.groups.forEachIndexed { i, g ->
                    Action("Our notes with " + WithChip.nameOf(g), g.seriesName, if (i == 0) Modifier.focusRequester(first) else Modifier) { onBring?.invoke(g.id); page = WithPage.MAIN }
                }
                Action("Stop bringing them", "Only what's said while watching now pops up", Modifier) { onBring?.invoke(null); page = WithPage.MAIN }
            }
        }
        }
    }
    // Each page opens with focus on its first choice.
    // Once more a moment later: as the panel first opens its window may not take focus yet.
    LaunchedEffect(page) { delay(50); runCatching { first.requestFocus() }; delay(300); if (page == WithPage.MAIN) runCatching { first.requestFocus() } }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Choice(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    ListItem(
        selected = selected,
        onClick = onClick,
        headlineContent = { Text(text) },
        trailingContent = if (selected) ({ Text("✓", color = MaterialTheme.colorScheme.primary) }) else null,
        modifier = modifier,
    )
}

@Composable
private fun Toggle(text: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    ListItem(
        selected = on,
        onClick = onClick,
        headlineContent = { Text(text) },
        trailingContent = { androidx.tv.material3.Switch(checked = on, onCheckedChange = null) },
        modifier = modifier,
    )
}

@Composable
private fun Action(title: String, detail: String, modifier: Modifier, onClick: () -> Unit) {
    ListItem(
        selected = false,
        onClick = onClick,
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        modifier = modifier,
    )
}
