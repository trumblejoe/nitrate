package dev.nitrate.mobile.ui

import androidx.compose.foundation.clickable
import dev.nitrate.core.companion.Emojis
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nitrate.core.companion.ChatKind
import dev.nitrate.core.companion.Notes
import dev.nitrate.core.companion.QuickPicks
import dev.nitrate.core.companion.QuickReplies
import dev.nitrate.core.companion.TimelineChat
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.formatClock
import dev.nitrate.core.partners.WatchCompany
import dev.nitrate.core.partners.WatchingWith
import dev.nitrate.core.partners.WithChip
import dev.nitrate.mobile.platform.timeZoneOffsetMsAt
import kotlinx.coroutines.launch

/** The group panel wired to the app (see [GroupSheet]). */
@Composable
fun PlayerGroupSheet(
    actions: AppActions,
    company: WatchCompany?,
    chip: WithChip,
    item: MediaItem?,
    positionMs: () -> Long,
    onDismiss: () -> Unit,
) {
    val container = actions.container
    val scope = rememberCoroutineScope()
    var problem by remember { mutableStateOf<String?>(null) }
    GroupSheet(
        chip = chip,
        problem = problem,
        onCountFor = { company?.countFor(it) },
        // A Group Watch Session of the group it counts for, from where this player is now.
        onStartSession = if (container.sessionsAvailable() && chip.group != null) ({
            scope.launch { problem = item?.let { container.startWatchingNow(it, chip.group, positionMs()) } }
        }) else null,
        onLeaveSession = { container.groupSessions.leave(); onDismiss() },
        onEndSession = { container.groupSessions.end(); onDismiss() },
        onSameRoom = { group, ids -> company?.sameRoom(group, ids) },
        onDismiss = onDismiss,
    )
}

private enum class GroupPage { MAIN, COUNT, ROOM }

/**
 * The group panel as a sheet (the player's group button opens it): the top line says who it counts
 * for ("Counting for you, Tommy & Suzy · Change", which opens the Change choices and "We're in the same
 * room") or "In a Group Watch Session with Tommy"; then "Start a Group Watch Session" (in one: leave
 * it, or end it for everyone when the user started it). The chat is the Chat button's ([ChatPanelSheet]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupSheet(
    chip: WithChip,
    problem: String? = null,
    onCountFor: (WatchingWith) -> Unit = {},
    /** Starts a Group Watch Session (null: the server has none, or no group to start it for). */
    onStartSession: (() -> Unit)? = {},
    onLeaveSession: () -> Unit = {},
    onEndSession: () -> Unit = {},
    onSameRoom: (groupId: String, present: List<String>) -> Unit = { _, _ -> },
    onDismiss: () -> Unit,
    initialPage: String? = null,
) {
    var page by remember { mutableStateOf(initialPage?.let { GroupPage.valueOf(it) } ?: GroupPage.MAIN) }
    val back = { page = if (page == GroupPage.ROOM) GroupPage.COUNT else GroupPage.MAIN }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        // A page's system Back goes back one step; on the first page it closes the sheet.
        dev.nitrate.mobile.platform.PlatformBackHandler(enabled = page != GroupPage.MAIN) { back() }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (page) {
                GroupPage.MAIN -> GroupMain(chip, problem, onStartSession, onLeaveSession, onEndSession) { page = it }
                GroupPage.COUNT -> {
                    Text("Counting for", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Who what you play to the end counts for.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val chosen = chip.group?.id
                    chip.groups.forEach { g -> Choice("With " + WithChip.nameOf(g), selected = chosen == g.id) { onCountFor(WatchingWith.Group(g.id)); back() } }
                    Choice("No group", selected = chosen == null) { onCountFor(WatchingWith.JustMe); back() }
                    if (chip.group != null) {
                        Action(
                            "We're in the same room",
                            if (chip.roomNames.isEmpty()) "Pick who's watching here with you: it counts for them too" else "Here: " + WithChip.and(chip.roomNames) + " · change",
                        ) { page = GroupPage.ROOM }
                    }
                    TextButton(onClick = back) { Text("Back") }
                }
                GroupPage.ROOM -> chip.group?.let { g -> RoomPicker(chip, g, onDone = { ids -> onSameRoom(g.id, ids); page = GroupPage.MAIN }, onBack = back) }
            }
        }
    }
}

@Composable
private fun GroupMain(
    chip: WithChip,
    problem: String?,
    onStartSession: (() -> Unit)?,
    onLeaveSession: () -> Unit,
    onEndSession: () -> Unit,
    go: (GroupPage) -> Unit,
) {
    if (!chip.inSession) {
        Action(chip.label, chip.who, bold = true) { go(GroupPage.COUNT) }
    } else {
        Text(chip.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(chip.who, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (chip.inSession) {
        Heading("Group Watch Session")
        Action("Leave the Group Watch Session", "Carry on by yourself from here", onClick = onLeaveSession)
        if (chip.startedByMe) Action("End it for everyone", "Everyone carries on by themselves", onClick = onEndSession)
    } else if (onStartSession != null && chip.group != null) {
        Action(
            "Start a Group Watch Session",
            "With " + WithChip.nameOf(chip.group!!) + ": they're asked to join, and everyone starts together on their own screen",
            onClick = onStartSession,
        )
    }
    problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

/** The chat sheet wired to the app (see [ChatPanelSheet]). */
@Composable
fun PlayerChatSheet(
    actions: AppActions,
    chip: WithChip?,
    item: MediaItem?,
    chat: TimelineChat,
    chatTitle: String?,
    positionMs: () -> Long,
    onDismiss: () -> Unit,
) {
    val container = actions.container
    // The title's quick replies (the usual ones until its own are worked out: an episode's show is asked for once).
    val picks by produceState(QuickReplies.forTitle(item), item?.id) {
        if (item != null) value = container.quickPicks(item)
    }
    ChatPanelSheet(
        chat = chat,
        who = chip?.group?.let(WithChip::nameOf) ?: chatTitle,
        positionMs = positionMs,
        picks = picks,
        onTurn = { on -> turnGroupChat(actions, chat, on, positionMs()) },
        onDismiss = onDismiss,
    )
}

/**
 * The player's chat as a sheet (the Chat button opens it, fully up): which conversation this is
 * ("Group chat · Will", "Replaying 2026/10/02 · Will"; chosen before playing, on the details page)
 * with Turn off for this playback; then what was said up to here, the title's quick lines (one tap
 * each) and the message box with its emoji button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatPanelSheet(
    chat: TimelineChat,
    /** Who it's with ("Will", "Tommy & Suzy"). */
    who: String?,
    positionMs: () -> Long = { 0L },
    picks: QuickPicks = QuickPicks.PLAIN,
    onTurn: (Boolean) -> Unit = {},
    onDismiss: () -> Unit,
    /** Opens with the emoji list showing (screenshots). */
    showEmojis: Boolean = false,
    /** The device's offset from UTC (minutes) at a moment, for a replay's date. */
    offsetAt: (Long) -> Int = { (timeZoneOffsetMsAt(it) / 60_000).toInt() },
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Conversation(chat, who, positionMs, picks, offsetAt, onTurn, showEmojis)
        }
    }
}

/** Which conversation (with Turn off), what was said up to here, quick lines and a message box. */
@Composable
private fun Conversation(
    chat: TimelineChat,
    who: String?,
    positionMs: () -> Long,
    picks: QuickPicks,
    offsetAt: (Long) -> Int,
    onTurn: (Boolean) -> Unit,
    showEmojis: Boolean,
) {
    val on by chat.on.collectAsState()
    val unread by chat.unread.collectAsState()
    val history by chat.history.collectAsState()
    val missed by chat.missed.collectAsState()
    val replaying by chat.replaying.collectAsState()
    val threads by chat.threads.collectAsState()
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var sendProblem by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(showEmojis) }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { chat.opened() }
    // Open: what pops up meanwhile is seen.
    LaunchedEffect(unread) { if (unread > 0) chat.read() }
    LaunchedEffect(history.size) { if (history.isNotEmpty()) list.animateScrollToItem(history.size - 1) }
    fun send(text: String, kind: ChatKind) {
        scope.launch {
            chat.send(text, kind, positionMs())
                .onSuccess { if (kind == ChatKind.TEXT) draft = ""; sendProblem = null }
                .onFailure { sendProblem = it.message ?: "Couldn't send" }
        }
    }
    // Which conversation this is (chosen before playing), and Turn off for this playback.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (on) dev.nitrate.core.companion.Threads.line(replaying, threads, who, offsetAt) else "Group chat is off",
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
        )
        if (on) TextButton(onClick = { onTurn(false) }) { Text("Turn off") }
    }
    if (!on) {
        Text("Nothing pops up, and nothing is sent.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.material3.Button(onClick = { onTurn(true) }) { Text("Turn on") }
        return
    }
    if (missed.isNotEmpty()) TextButton(onClick = { chat.showMissed() }) { Text("Show what I missed (${missed.size})") }
    LazyColumn(Modifier.fillMaxWidth().heightIn(min = 60.dp, max = 300.dp), state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (history.isEmpty()) item {
            Text("Nothing yet up to here. A reaction or a message stays at this point for your group.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(history, key = { it.id }) { n ->
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(n.userName, style = MaterialTheme.typography.labelMedium, color = accentColor(), fontWeight = FontWeight.Bold)
                    Text(formatClock(n.positionMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (n.kind == ChatKind.REACTION) Text(n.text, fontSize = 28.sp) else Text(Notes.text(n), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    // Its characters' names and short lines: one tap sends.
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(picks.lines) { line -> SuggestionChip(onClick = { send(line, ChatKind.TEXT) }, label = { Text(line) }) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        // One emoji button opens the whole list (the title's own and the usual ones first), as chat apps do.
        IconButton(onClick = { picking = !picking }) { Text(if (picking) "⌨️" else "🙂", fontSize = 24.sp, modifier = Modifier.semantics { contentDescription = if (picking) "Close the emoji list" else "Emoji" }) }
        OutlinedTextField(
            draft, { draft = it.take(500) },
            placeholder = { Text("Message") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (draft.isNotBlank()) send(draft, ChatKind.TEXT) }),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { send(draft, ChatKind.TEXT) }, enabled = draft.isNotBlank()) {
            Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = accentColor())
        }
    }
    if (picking) EmojiList(Emojis.groupsFor(picks)) { emoji -> send(emoji, ChatKind.REACTION); picking = false }
    sendProblem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

/** The full emoji list under the message box, by heading ("For this" first); a tap sends one. */
@Composable
internal fun EmojiList(groups: List<dev.nitrate.core.companion.EmojiGroup>, onPick: (String) -> Unit) {
    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
        androidx.compose.foundation.lazy.grid.GridCells.Adaptive(44.dp),
        Modifier.fillMaxWidth().heightIn(max = 280.dp),
    ) {
        groups.forEach { group ->
            item(key = "h-${group.name}", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Text(group.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
            }
            items(group.emojis.size, key = { "e-${group.emojis[it]}" }) { i ->
                val emoji = group.emojis[i]
                Box(
                    Modifier.size(44.dp).clickable(role = Role.Button, onClickLabel = "Send") { onPick(emoji) },
                    contentAlignment = Alignment.Center,
                ) { Text(emoji, fontSize = 24.sp) }
            }
        }
    }
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
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun Choice(text: String, selected: Boolean, detail: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 8.dp)) {
            Text(text)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun Action(title: String, detail: String, bold: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp)) {
        Text(title, style = if (bold) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall, fontWeight = if (bold) FontWeight.Bold else null)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
