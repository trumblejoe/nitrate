package dev.nitrate.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nitrate.core.companion.ChatKind
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import dev.nitrate.core.companion.ChatMessage
import dev.nitrate.core.companion.Notes
import dev.nitrate.core.companion.TimelineChat
import dev.nitrate.core.companion.TimelineNote
import kotlinx.coroutines.flow.Flow
import dev.nitrate.core.companion.GroupChat
import dev.nitrate.core.companion.QUICK_REACTIONS
import dev.nitrate.core.model.formatClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One playback's group chat (Companion `notes`, live in a Group Watch Session too): started once the
 * title is known (its notes loaded once), told where playback is about once a second, closed with
 * the player. Null for downloads played offline, recap clips and test plays.
 */
@Composable
fun rememberGroupChat(actions: AppActions, itemId: String?, enabled: Boolean, positionMs: () -> Long): TimelineChat? {
    if (!enabled) return null
    val scope = rememberCoroutineScope()
    val chat = remember { actions.container.timelineChat(scope, live = true) }
    val prefs by androidx.compose.runtime.rememberUpdatedState(LocalDisplayPrefs.current)
    LaunchedEffect(chat, itemId) {
        val id = itemId ?: return@LaunchedEffect
        // As picked on the details page it was played from, else as remembered for its groups.
        chat.start(id, positionMs(), dev.nitrate.android.playback.ChatChoices.take(id)) { groups -> prefs.groupChatOn(groups) }
        // Notes pop up as playback reaches them (never ahead).
        while (true) {
            delay(1_000)
            chat.at(positionMs())
        }
    }
    androidx.compose.runtime.DisposableEffect(chat) { onDispose { chat.close() } }
    return chat
}

/** Whether there's anyone to chat with in this playback: a watch group on the title, or a live group. */
@Composable
fun groupChatAvailable(chat: TimelineChat?, liveGroup: Boolean): Boolean =
    chat != null && (chat.groups.collectAsState().value.isNotEmpty() || liveGroup)

/** Turns group chat on or off for this playback and remembers it for the title's groups (per user). */
fun turnGroupChat(actions: AppActions, chat: TimelineChat, on: Boolean, positionMs: Long) {
    chat.turn(on, positionMs)
    val groups = chat.keys
    actions.scope.launch { actions.container.setGroupChat(groups, on) }
}

/**
 * The group's notes popping up over the video as playback reaches them (or live from the group): a
 * small notification each, name then what they said, for a few seconds (the newest three at most).
 */
@Composable
fun NoteToasts(popups: Flow<TimelineNote>, modifier: Modifier = Modifier) {
    val shown = remember { mutableStateListOf<TimelineNote>() }
    LaunchedEffect(popups) {
        popups.collect { n ->
            shown.add(n)
            while (shown.size > 3) shown.removeAt(0)
            launch {
                delay(8_000)
                shown.remove(n)
            }
        }
    }
    Column(modifier.widthIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (n in shown) {
            androidx.compose.runtime.key(n.id) {
                Row(
                    Modifier.background(Color(0xD9101014), RoundedCornerShape(18.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(n.userName, style = MaterialTheme.typography.labelMedium, color = accentColor(), fontWeight = FontWeight.Bold)
                    if (n.kind == ChatKind.REACTION) Text(n.text, fontSize = 22.sp)
                    else Text(Notes.text(n), style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 3)
                }
            }
        }
    }
}

/** "Group chat" with its switch (the whole line toggles), for the group panel's Chats and the player's More. */
@Composable
fun GroupChatSwitch(on: Boolean, onTurn: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().toggleable(value = on, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onTurn).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Group chat: " + if (on) "On" else "Off", style = MaterialTheme.typography.titleSmall)
            Text(if (on) "What's said pops up as you reach it" else "No pop-ups, nothing sent", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = null)
    }
}

/** The player's More: the Group chat switch, and "Show what I missed" after turning it on mid-play. */
@Composable
fun GroupChatRow(chat: TimelineChat, onTurn: (Boolean) -> Unit, onShowMissed: () -> Unit) {
    val on by chat.on.collectAsState()
    val missed by chat.missed.collectAsState()
    Column {
        GroupChatSwitch(on, onTurn)
        if (on && missed.isNotEmpty()) TextButton(onClick = onShowMissed) { Text("Show what I missed (${missed.size})") }
    }
}

@Composable
private fun Bubble(m: ChatMessage, modifier: Modifier = Modifier, showTime: Boolean = false) {
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(m.userName, style = MaterialTheme.typography.labelMedium, color = accentColor(), fontWeight = FontWeight.Bold)
            if (showTime) m.positionMs?.let { Text(formatClock(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (m.kind == ChatKind.REACTION) Text(m.text, fontSize = 28.sp)
        else Text(m.text, style = MaterialTheme.typography.bodyMedium, color = if (showTime) MaterialTheme.colorScheme.onSurface else Color.White)
    }
}

/**
 * A watch-together group's chat: the conversation, quick reactions and a text box. Messages carry
 * where the sender is in the movie ([positionMs], null when not playing here).
 */
@Composable
fun ChatContent(chat: GroupChat, positionMs: () -> Long?, modifier: Modifier = Modifier) {
    val messages by chat.messages.collectAsState()
    val status by chat.status.collectAsState()
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) list.animateScrollToItem(messages.size - 1) }
    fun send(text: String, kind: ChatKind) {
        scope.launch {
            chat.send(text, kind, positionMs())
                .onSuccess { if (kind == ChatKind.TEXT) draft = ""; problem = null }
                .onFailure { problem = it.message ?: "Couldn't send" }
        }
    }
    Column(modifier.imePadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (status) {
            GroupChat.Status.CONNECTING -> "Connecting…"
            GroupChat.Status.RECONNECTING -> "Reconnecting…"
            GroupChat.Status.NOT_ALLOWED -> "This group's chat isn't available: nobody on your account is watching in it now."
            else -> null
        }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        LazyColumn(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 420.dp), state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (messages.isEmpty()) item { Text("No messages yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(messages, key = { it.seq }) { Bubble(it, showTime = true) }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            items(QUICK_REACTIONS) { emoji ->
                TextButton(onClick = { send(emoji, ChatKind.REACTION) }) { Text(emoji, fontSize = 22.sp) }
            }
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
}

/** The chat as a sheet over the player or the Watch together screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSheet(chat: GroupChat, title: String, positionMs: () -> Long?, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            ChatContent(chat, positionMs)
        }
    }
}
