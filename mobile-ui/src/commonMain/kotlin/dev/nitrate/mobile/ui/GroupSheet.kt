package dev.nitrate.mobile.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nitrate.core.companion.ChatKind
import dev.nitrate.core.companion.NoteChats
import dev.nitrate.core.companion.Notes
import dev.nitrate.core.companion.QUICK_REACTIONS
import dev.nitrate.core.companion.TimelineChat
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.formatClock
import dev.nitrate.core.partners.WatchCompany
import dev.nitrate.core.partners.WatchingWith
import dev.nitrate.core.partners.WithChip
import dev.nitrate.mobile.platform.timeZoneOffsetMs
import kotlinx.coroutines.launch

/** The group panel wired to the app (see [GroupSheet]). */
@Composable
fun PlayerGroupSheet(
    actions: AppActions,
    company: WatchCompany?,
    chip: WithChip?,
    item: MediaItem?,
    groupChat: TimelineChat?,
    chatAvailable: Boolean,
    chatTitle: String?,
    positionMs: () -> Long,
    onDismiss: () -> Unit,
) {
    val container = actions.container
    val scope = rememberCoroutineScope()
    var problem by remember { mutableStateOf<String?>(null) }
    val me = container.server.userId
    val pickedBy = groupChat?.broughtBy?.collectAsState()?.value
    GroupSheet(
        chip = chip,
        chat = groupChat?.takeIf { chatAvailable },
        chatTitle = chatTitle,
        positionMs = positionMs,
        problem = problem,
        pickedBy = pickedBy?.takeIf { it != me }?.let { id -> chip?.groups?.flatMap { it.others }?.firstOrNull { it.id == id }?.name ?: "Someone watching" },
        onCountFor = { company?.countFor(it) },
        // A Group Watch Session of the group it counts for, from where this player is now.
        onStartSession = if (container.sessionsAvailable() && chip?.group != null) ({
            scope.launch { problem = item?.let { container.startWatchingNow(it, chip.group, positionMs()) } }
        }) else null,
        onLeaveSession = { container.groupSessions.leave(); onDismiss() },
        onEndSession = { container.groupSessions.end(); onDismiss() },
        onSameRoom = { group, ids -> company?.sameRoom(group, ids) },
        onTurnChat = { on -> groupChat?.let { turnGroupChat(actions, it, on, positionMs()) } },
        onPlayChat = { id ->
            scope.launch {
                groupChat?.play(id)?.onSuccess { problem = null }?.onFailure { problem = it.message ?: "Couldn't open that chat" }
            }
        },
        onDismiss = onDismiss,
    )
}

private enum class GroupPage { MAIN, COUNT, ROOM, CHATS }

/**
 * The group panel as a sheet (the player's group button opens it): the top line says who it counts
 * for ("Counting for Tommy & Suzy · Change", which opens the Change choices and "We're in the same
 * room") or "In a Group Watch Session with Tommy"; then the chat: which chat this is ("New chat", or
 * the earlier one playing along; tapping it opens **Chats**: Group chat on or off, New chat and the
 * earlier chats, newest first), what was said up to here, reactions and a message box; then "Start a
 * Group Watch Session" (in one: leave it, or end it for everyone when the user started it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupSheet(
    chip: WithChip?,
    chat: TimelineChat?,
    chatTitle: String? = null,
    positionMs: () -> Long = { 0L },
    problem: String? = null,
    /** Who else picked the chat playing along for everyone in the session (null: the user, or nobody). */
    pickedBy: String? = null,
    onCountFor: (WatchingWith) -> Unit = {},
    /** Starts a Group Watch Session (null: the server has none, or no group to start it for). */
    onStartSession: (() -> Unit)? = {},
    onLeaveSession: () -> Unit = {},
    onEndSession: () -> Unit = {},
    onSameRoom: (groupId: String, present: List<String>) -> Unit = { _, _ -> },
    onTurnChat: (Boolean) -> Unit = {},
    onPlayChat: (String?) -> Unit = {},
    onDismiss: () -> Unit,
    initialPage: String? = null,
    nowMs: Long = dev.nitrate.android.nowMs(),
    offsetMinutes: Int = (timeZoneOffsetMs() / 60_000).toInt(),
) {
    var page by remember { mutableStateOf(initialPage?.let { GroupPage.valueOf(it) } ?: GroupPage.MAIN) }
    val back = { page = if (page == GroupPage.ROOM) GroupPage.COUNT else GroupPage.MAIN }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        // A page's system Back goes back one step; on the first page it closes the sheet.
        dev.nitrate.mobile.platform.PlatformBackHandler(enabled = page != GroupPage.MAIN) { back() }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (page) {
                GroupPage.MAIN -> GroupMain(chip, chat, chatTitle, positionMs, problem, pickedBy, nowMs, offsetMinutes, onStartSession, onLeaveSession, onEndSession) { page = it }
                GroupPage.COUNT -> chip?.let { c ->
                    Text("Counting for", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Who what you play to the end counts for.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val chosen = c.group?.id
                    c.groups.forEach { g -> Choice("With " + WithChip.nameOf(g), selected = chosen == g.id) { onCountFor(WatchingWith.Group(g.id)); back() } }
                    Choice("No group", selected = chosen == null) { onCountFor(WatchingWith.JustMe); back() }
                    if (c.group != null) {
                        Action(
                            "We're in the same room",
                            if (c.roomNames.isEmpty()) "Pick who's watching here with you: it counts for them too" else "Here: " + WithChip.and(c.roomNames) + " · change",
                        ) { page = GroupPage.ROOM }
                    }
                    TextButton(onClick = back) { Text("Back") }
                }
                GroupPage.ROOM -> chip?.group?.let { g -> RoomPicker(chip, g, onDone = { ids -> onSameRoom(g.id, ids); page = GroupPage.MAIN }, onBack = back) }
                GroupPage.CHATS -> chat?.let { ChatsList(it, chip?.inSession == true, pickedBy, nowMs, offsetMinutes, onTurnChat, onPick = { id -> onPlayChat(id); back() }, onBack = back) }
            }
        }
    }
}

@Composable
private fun GroupMain(
    chip: WithChip?,
    chat: TimelineChat?,
    chatTitle: String?,
    positionMs: () -> Long,
    problem: String?,
    pickedBy: String?,
    nowMs: Long,
    offsetMinutes: Int,
    onStartSession: (() -> Unit)?,
    onLeaveSession: () -> Unit,
    onEndSession: () -> Unit,
    go: (GroupPage) -> Unit,
) {
    when {
        chip != null && !chip.inSession -> Action(chip.label, chip.who, bold = true) { go(GroupPage.COUNT) }
        chip != null -> {
            Text(chip.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(chip.who, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> Text("Group chat" + (chatTitle?.let { " · $it" } ?: ""), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
    if (chat != null) ChatPart(chat, positionMs, pickedBy, nowMs, offsetMinutes) { go(GroupPage.CHATS) }
    if (chip?.inSession == true) {
        Heading("Group Watch Session")
        Action("Leave the Group Watch Session", "Carry on by yourself from here", onClick = onLeaveSession)
        if (chip.startedByMe) Action("End it for everyone", "Everyone carries on by themselves", onClick = onEndSession)
    } else if (onStartSession != null && chip?.group != null) {
        Action(
            "Start a Group Watch Session",
            "With " + WithChip.nameOf(chip.group!!) + ": they're asked to join, and everyone starts together on their own screen",
            onClick = onStartSession,
        )
    }
    problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

/** The chat: which one (tap for Chats), what was said up to here, reactions and a message box. */
@Composable
private fun ChatPart(chat: TimelineChat, positionMs: () -> Long, pickedBy: String?, nowMs: Long, offsetMinutes: Int, onChats: () -> Unit) {
    val on by chat.on.collectAsState()
    val history by chat.history.collectAsState()
    val missed by chat.missed.collectAsState()
    val hasChats by chat.hasChats.collectAsState()
    val playing by chat.playing.collectAsState()
    val all by chat.allChats.collectAsState()
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { chat.opened() }
    LaunchedEffect(history.size) { if (history.isNotEmpty()) list.animateScrollToItem(history.size - 1) }
    fun send(text: String, kind: ChatKind) {
        scope.launch {
            chat.send(text, kind, positionMs())
                .onSuccess { if (kind == ChatKind.TEXT) draft = ""; problem = null }
                .onFailure { problem = it.message ?: "Couldn't send" }
        }
    }
    Heading("Chat")
    Action(
        when {
            !on -> "Group chat: Off"
            hasChats -> NoteChats.playingLabel(playing, all, nowMs, offsetMinutes)
            else -> "Group chat: On"
        },
        when {
            pickedBy != null && playing != null -> "$pickedBy picked it for everyone · Chats ›"
            hasChats -> "Chats: play an earlier chat along, or turn it off ›"
            else -> "Turn it on or off ›"
        },
        onClick = onChats,
    )
    if (!on) return
    if (missed.isNotEmpty()) TextButton(onClick = { chat.showMissed() }) { Text("Show what I missed (${missed.size})") }
    LazyColumn(Modifier.fillMaxWidth().heightIn(min = 60.dp, max = 300.dp), state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (history.isEmpty()) item {
            Text(
                if (playing == null) "A new chat: nothing earlier pops up. A reaction or a note stays at this point for your group."
                else "Nothing yet up to here.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    LazyRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        items(QUICK_REACTIONS) { emoji -> TextButton(onClick = { send(emoji, ChatKind.REACTION) }) { Text(emoji, fontSize = 22.sp) } }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
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
    problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

/** Chats: Group chat on or off, then New chat and the earlier chats for this group and part, newest first. */
@Composable
private fun ChatsList(
    chat: TimelineChat,
    inSession: Boolean,
    pickedBy: String?,
    nowMs: Long,
    offsetMinutes: Int,
    onTurn: (Boolean) -> Unit,
    onPick: (String?) -> Unit,
    onBack: () -> Unit,
) {
    val on by chat.on.collectAsState()
    val hasChats by chat.hasChats.collectAsState()
    val offered by chat.chats.collectAsState()
    val all by chat.allChats.collectAsState()
    val playing by chat.playing.collectAsState()
    Text("Chats", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    GroupChatSwitch(on, onTurn)
    if (hasChats && on) {
        Text(
            if (inSession) "What you pick plays along for everyone in the Group Watch Session." + (pickedBy?.let { " $it picked the one now." } ?: "")
            else "Pick an earlier chat to play along: its messages pop up as you reach them. Anything you write starts a new chat that builds on it.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            item { Choice(NoteChats.NEW_CHAT, selected = playing == null, detail = "Nothing earlier pops up") { onPick(null) } }
            items(offered, key = { it.id }) { c -> Choice(NoteChats.label(c, all, nowMs, offsetMinutes), selected = playing == c.id) { onPick(c.id) } }
            if (offered.isEmpty()) item { Text("No earlier chats on this yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        }
    }
    TextButton(onClick = onBack) { Text("Back") }
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
