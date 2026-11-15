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
import dev.nitrate.core.companion.ChatMessage
import dev.nitrate.core.companion.GroupChat
import dev.nitrate.core.companion.QUICK_REACTIONS
import dev.nitrate.core.model.formatClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** New messages from the others in the group, popping up over the video for a few seconds each. */
@Composable
fun ChatToasts(chat: GroupChat, modifier: Modifier = Modifier) {
    val shown = remember { mutableStateListOf<ChatMessage>() }
    LaunchedEffect(chat) {
        chat.incoming.collect { m ->
            shown.add(m)
            while (shown.size > 3) shown.removeAt(0)
            launch {
                delay(8_000)
                shown.remove(m)
            }
        }
    }
    Column(modifier.widthIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (m in shown) {
            androidx.compose.runtime.key(m.seq) {
                Bubble(m, Modifier.background(Color(0xCC101014), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
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
