package dev.nitrate.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nitrate.core.companion.TimelineChat
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.partners.WatchCompany
import dev.nitrate.core.partners.WatchingWith
import dev.nitrate.core.partners.WithChip
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Who this playback is watched with (the player's "With…" chip): made once per player, told as each
 * item starts and where it's got to (what's played to the end counts for whoever's in the room), and
 * it steers [groupChat] to the group it counts for. Null when [enabled] is false (downloads offline,
 * recap clips, test plays).
 */
@Composable
fun rememberWatchCompany(
    actions: AppActions,
    item: MediaItem?,
    enabled: Boolean,
    groupChat: TimelineChat?,
    positionMs: () -> Long,
    durationMs: () -> Long,
): WatchCompany? {
    if (!enabled) return null
    val scope = rememberCoroutineScope()
    val company = remember { actions.container.watchCompany(scope) }
    val position by rememberUpdatedState(positionMs)
    val duration by rememberUpdatedState(durationMs)
    // Where the item playing has got to, so that leaving it (the next one, or the player closing) can tell.
    val last = remember { mutableStateOf<Triple<String, Long, Long>?>(null) }
    LaunchedEffect(company, item?.id) {
        val i = item ?: return@LaunchedEffect
        last.value?.takeIf { it.first != i.id }?.let { (id, at, of) -> company.left(id, at, of) }
        company.start(i, actions.container.groupStandings.value)
        while (true) {
            last.value = Triple(i.id, position(), duration())
            delay(1_000)
        }
    }
    DisposableEffect(company) { onDispose { last.value?.let { (id, at, of) -> company.left(id, at, of) } } }
    LaunchedEffect(company, groupChat) { company.chip.collect { groupChat?.focus(chatFocus(it)) } }
    return company
}

/** What the group chat follows for this chip: the group(s) it counts for, none for just the user; everything in a Group Watch Session. */
fun chatFocus(chip: WithChip?): List<String>? = when {
    chip == null || chip.inSession -> null
    else -> chip.context.groups.map { it.id }
}

/**
 * The chip itself: which situation this playback is in. Tapping it opens [onOpen] (the panel); in
 * easy mode it only says it.
 */
@Composable
fun WithChipButton(chip: WithChip, easy: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    val base = modifier.widthIn(max = 260.dp).background(Color.White.copy(alpha = 0.16f), shape)
    val label = if (easy) chip.plain else chip.label
    Text(
        label,
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = (if (easy) base else base.clickable(onClickLabel = "Who you're watching with", role = Role.Button, onClick = onOpen))
            .semantics { contentDescription = label }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/**
 * When more than one group could count this playback and none was chosen, which one it counts
 * for, shown plainly for about five seconds as it starts ("Counting for: Tommy"), so it can be
 * switched (tap). Once per title.
 */
@Composable
fun CountingBanner(chip: WithChip?, easy: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
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
    AnimatedVisibility(visible && chip != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        val c = chip ?: return@AnimatedVisibility
        val words = c.counting + if (easy) "" else " · Change"
        Text(
            words,
            color = Color.Black,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.background(accentColor(), RoundedCornerShape(50))
                .then(if (easy) Modifier else Modifier.clickable(role = Role.Button) { visible = false; onOpen() })
                .semantics { contentDescription = words }
                .padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

/** How long "Counting for: …" stays up as playback starts. */
const val COUNTING_MS = 5_000L

/** The panel behind the chip, wired to the app: who's here, Counts for, Watch now together (a Group Watch Session), the same room, group chat, bringing notes. */
@Composable
fun PlayerWithSheet(
    actions: AppActions,
    company: WatchCompany,
    chip: WithChip,
    item: MediaItem?,
    groupChat: TimelineChat?,
    chatAvailable: Boolean,
    positionMs: () -> Long,
    onDismiss: () -> Unit,
) {
    val container = actions.container
    val scope = rememberCoroutineScope()
    var problem by remember { mutableStateOf<String?>(null) }
    val chatOn = groupChat?.on?.collectAsState()?.value
    val broughtBy = groupChat?.broughtBy?.collectAsState()?.value
    var broughtMine by remember { mutableStateOf<String?>(null) }
    val me = container.server.userId
    WithSheet(
        chip = chip,
        problem = problem,
        chatOn = chatOn?.takeIf { chatAvailable },
        brought = broughtText(chip, broughtBy, me, broughtMine),
        onCountFor = { company.countFor(it) },
        // A Group Watch Session of the group it counts for, from where this player is now.
        onStartSession = if (container.sessionsAvailable() && chip.group != null) ({
            scope.launch { problem = item?.let { container.startWatchingNow(it, chip.group, positionMs()) } }
        }) else null,
        onLeaveSession = { container.groupSessions.leave(); onDismiss() },
        onEndSession = { container.groupSessions.end(); onDismiss() },
        onSameRoom = { group, ids -> company.sameRoom(group, ids) },
        onGroupChat = if (groupChat != null && chatAvailable) { on -> turnGroupChat(actions, groupChat, on, positionMs()) } else null,
        onBring = if (groupChat != null && chip.inSession) { groupId ->
            scope.launch {
                groupChat.bring(groupId)
                    .onSuccess { broughtMine = groupId; problem = null }
                    .onFailure { problem = it.message ?: "Couldn't bring the notes" }
            }
        } else null,
        onDismiss = onDismiss,
    )
}

/** "You brought your notes with Tommy & Suzy", "Tommy brought earlier notes" (null: none brought). */
fun broughtText(chip: WithChip, by: String?, me: String?, mine: String?): String? {
    if (by == null && mine == null) return null
    if (by == null || by == me) return chip.groups.firstOrNull { it.id == mine }?.let { "You brought your notes with " + WithChip.nameOf(it) } ?: "You brought earlier notes"
    val name = chip.groups.flatMap { it.others }.firstOrNull { it.id == by }?.name
    return (name ?: "Someone watching") + " brought earlier notes"
}

private enum class WithPage { MAIN, ROOM, BRING }

/**
 * The "With…" panel as a sheet: who's here, then Counts for (each group on the title, or just
 * you), Watch now together (a Group Watch Session of the group it counts for; in one, leave it,
 * or end it for everyone when the user started it), "We're in the same room" (pick who's here),
 * Group chat on or off, and in a session "Bring our earlier notes".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WithSheet(
    chip: WithChip,
    problem: String? = null,
    /** Group chat on or off (null: no chat for this playback). */
    chatOn: Boolean? = null,
    brought: String? = null,
    onCountFor: (WatchingWith) -> Unit = {},
    /** Starts a Group Watch Session (null: the server has none, or no group to start it for). */
    onStartSession: (() -> Unit)? = {},
    onLeaveSession: () -> Unit = {},
    onEndSession: () -> Unit = {},
    onSameRoom: (groupId: String, present: List<String>) -> Unit = { _, _ -> },
    onGroupChat: ((Boolean) -> Unit)? = null,
    onBring: ((String?) -> Unit)? = null,
    onDismiss: () -> Unit,
    initialPage: String? = null,
) {
    var page by remember { mutableStateOf(initialPage?.let { WithPage.valueOf(it) } ?: WithPage.MAIN) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (page) {
                WithPage.MAIN -> WithMain(chip, problem, chatOn, brought, onCountFor, onStartSession, onLeaveSession, onEndSession, onGroupChat, onBring, go = { page = it })
                WithPage.ROOM -> chip.group?.let { g -> RoomPicker(chip, g, onDone = { ids -> onSameRoom(g.id, ids); page = WithPage.MAIN }, onBack = { page = WithPage.MAIN }) }
                WithPage.BRING -> BringPicker(chip, onBring = { onBring?.invoke(it); page = WithPage.MAIN }, onBack = { page = WithPage.MAIN })
            }
        }
    }
}

@Composable
private fun WithMain(
    chip: WithChip,
    problem: String?,
    chatOn: Boolean?,
    brought: String?,
    onCountFor: (WatchingWith) -> Unit,
    onStartSession: (() -> Unit)?,
    onLeaveSession: () -> Unit,
    onEndSession: () -> Unit,
    onGroupChat: ((Boolean) -> Unit)?,
    onBring: ((String?) -> Unit)?,
    go: (WithPage) -> Unit,
) {
    Text(chip.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text(chip.who, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (chip.groups.isNotEmpty() && !chip.inSession) {
        Heading("Counting for")
        val chosen = chip.group?.id
        chip.groups.forEach { g ->
            Choice("With " + WithChip.nameOf(g), selected = chosen == g.id) { onCountFor(WatchingWith.Group(g.id)) }
        }
        Choice("No group", selected = chosen == null) { onCountFor(WatchingWith.JustMe) }
    }
    if (chip.inSession) {
        Heading("Group Watch Session")
        Action("Leave the Group Watch Session", "Carry on by yourself from here", onLeaveSession)
        if (chip.startedByMe) Action("End it for everyone", "Everyone carries on by themselves", onEndSession)
    } else if (onStartSession != null && chip.group != null) {
        Heading("Watch now together")
        Action(
            "Watch now together with " + WithChip.nameOf(chip.group!!),
            "Starts a Group Watch Session: the group is asked to join, and everyone starts together on their own screen",
            onStartSession,
        )
    }
    if (chip.group != null && !chip.inSession) {
        Action(
            "We're in the same room",
            if (chip.roomNames.isEmpty()) "Pick who's watching here with you: it counts for them too" else "Here: " + WithChip.and(chip.roomNames) + " · change",
        ) { go(WithPage.ROOM) }
    }
    if (chatOn != null && onGroupChat != null) GroupChatSwitch(chatOn, onGroupChat)
    if (onBring != null && chip.groups.isNotEmpty()) {
        Action("Bring our earlier notes", brought ?: "Your group's notes on this pop up for everyone watching, this time only") { go(WithPage.BRING) }
    } else brought?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
    problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun RoomPicker(chip: WithChip, group: dev.nitrate.core.partners.WatchGroup, onDone: (List<String>) -> Unit, onBack: () -> Unit) {
    var here by remember { mutableStateOf(chip.room?.takeIf { it.groupId == group.id }?.present?.toSet() ?: group.others.map { it.id }.toSet()) }
    Text("We're in the same room", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text("Who's watching here with you? What you play to the end counts for them too.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    group.others.forEach { p ->
        Row(
            Modifier.fillMaxWidth().toggleable(value = p.id in here, role = Role.Checkbox) { on -> here = if (on) here + p.id else here - p.id }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = p.id in here, onCheckedChange = null)
            Text(p.name, Modifier.padding(start = 8.dp))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { onDone(group.others.map { it.id }.filter { it in here }) }) { Text(if (here.isEmpty()) "Nobody else here" else "Done") }
        TextButton(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun BringPicker(chip: WithChip, onBring: (String?) -> Unit, onBack: () -> Unit) {
    Text("Bring our earlier notes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text("Notes from one of your groups on this pop up for everyone watching as playback passes them, this time only. Nobody keeps them.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    chip.groups.forEach { g -> Action("Our notes with " + WithChip.nameOf(g), g.seriesName) { onBring(g.id) } }
    Action("Stop bringing them", "Only what's said while watching now pops up") { onBring(null) }
    TextButton(onClick = onBack) { Text("Back") }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text, Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun Action(title: String, detail: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
