package dev.nitrate.tv.ui.together

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.nitrate.core.companion.ChatKind
import dev.nitrate.core.companion.ChatMessage
import dev.nitrate.core.companion.GroupChat
import dev.nitrate.core.companion.QUICK_REACTIONS
import dev.nitrate.core.model.formatClock
import dev.nitrate.tv.ui.components.TvTextField
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long a chat pop-up stays over the video. */
private const val TOAST_MS = 8_000L

/**
 * New chat messages from the others in the group, popping up over the video for a few seconds
 * each (the newest three at most). Reactions show big.
 */
@Composable
fun ChatToasts(chat: GroupChat, modifier: Modifier = Modifier) {
    val shown = remember { mutableStateListOf<ChatMessage>() }
    LaunchedEffect(chat) {
        chat.incoming.collect { m ->
            shown.add(m)
            while (shown.size > 3) shown.removeAt(0)
            launch {
                delay(TOAST_MS)
                shown.remove(m)
            }
        }
    }
    Column(modifier.widthIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (m in shown) {
            androidx.compose.runtime.key(m.seq) {
                Bubble(m, Modifier.background(Color(0xCC101014), RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 10.dp))
            }
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage, modifier: Modifier = Modifier, showTime: Boolean = false) {
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(m.userName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            if (showTime) m.positionMs?.let { Text(formatClock(it), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f)) }
        }
        if (m.kind == ChatKind.REACTION) Text(m.text, fontSize = 34.sp)
        else Text(m.text, style = MaterialTheme.typography.bodyLarge, color = Color.White)
    }
}

/**
 * The chat beside the video: the conversation so far, quick reactions (one press each, made for
 * a remote) and a text field for anyone with a keyboard. Messages carry where the sender is in
 * the movie ([positionMs]).
 */
@Composable
fun ChatPanel(chat: GroupChat, groupName: String?, positionMs: () -> Long?, modifier: Modifier = Modifier, autoFocus: Boolean = true) {
    val messages by chat.messages.collectAsState()
    val status by chat.status.collectAsState()
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    val firstReaction = remember { FocusRequester() }
    val list = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) list.scrollToItem(messages.size - 1) }
    LaunchedEffect(Unit) { if (autoFocus) runCatching { firstReaction.requestFocus() } }
    fun send(text: String, kind: ChatKind) {
        scope.launch {
            chat.send(text, kind, positionMs()).onSuccess { if (kind == ChatKind.TEXT) draft = ""; problem = null }
                .onFailure { problem = it.message ?: "Couldn't send" }
        }
    }
    Column(
        modifier.width(440.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Chat" + (groupName?.let { " · $it" } ?: ""), style = MaterialTheme.typography.titleLarge)
        when (status) {
            GroupChat.Status.CONNECTING -> "Connecting…"
            GroupChat.Status.RECONNECTING -> "Reconnecting…"
            GroupChat.Status.NOT_ALLOWED -> "This group's chat isn't available."
            else -> null
        }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (messages.isEmpty()) item {
                Text("No messages yet. Send a reaction, or type something.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(messages, key = { it.seq }) { Bubble(it, showTime = true) }
        }
        // Two rows of four, so each reaction is one press away with the D-pad.
        QUICK_REACTIONS.chunked(4).forEachIndexed { row, emojis ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                emojis.forEachIndexed { i, emoji ->
                    OutlinedButton(
                        onClick = { send(emoji, ChatKind.REACTION) },
                        modifier = if (row == 0 && i == 0) Modifier.focusRequester(firstReaction) else Modifier,
                    ) { Text(emoji, fontSize = 22.sp) }
                }
            }
        }
        TvTextField(
            draft, { draft = it.take(500) }, "Type a message",
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (draft.isNotBlank()) send(draft, ChatKind.TEXT) }),
        )
        Button(onClick = { send(draft, ChatKind.TEXT) }, enabled = draft.isNotBlank()) { Text("Send") }
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
