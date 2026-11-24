package dev.nitrate.tv.ui.together

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.nitrate.core.companion.ChatKind
import dev.nitrate.core.companion.ChatMessage
import dev.nitrate.core.companion.GroupChat
import dev.nitrate.core.companion.Notes
import dev.nitrate.core.companion.QuickPicks
import dev.nitrate.core.companion.TimelineNote
import dev.nitrate.core.model.formatClock
import dev.nitrate.tv.ui.components.TvTextField
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** How long a chat pop-up stays over the video. */
private const val TOAST_MS = 8_000L

/**
 * The group's notes popping up over the video as playback reaches them (or live from the
 * watch-together group): a small notification each, name then what they said, for a few seconds
 * (the newest three at most). Never takes focus.
 */
@Composable
fun NoteToasts(popups: Flow<TimelineNote>, modifier: Modifier = Modifier) {
    val shown = remember { mutableStateListOf<TimelineNote>() }
    LaunchedEffect(popups) {
        popups.collect { n ->
            shown.add(n)
            while (shown.size > 3) shown.removeAt(0)
            launch {
                delay(TOAST_MS)
                shown.remove(n)
            }
        }
    }
    Column(modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (n in shown) {
            androidx.compose.runtime.key(n.id) {
                Row(
                    Modifier.background(Color(0xD9101014), RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(n.userName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    if (n.kind == ChatKind.REACTION) Text(n.text, fontSize = 26.sp)
                    else Text(Notes.text(n), style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
internal fun NoteLine(n: TimelineNote) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(n.userName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text(formatClock(n.positionMs), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
        }
        if (n.kind == ChatKind.REACTION) Text(n.text, fontSize = 30.sp)
        else Text(Notes.text(n), style = MaterialTheme.typography.bodyLarge, color = Color.White)
    }
}

/**
 * Quick reactions (rows of five, one press each: the title's own emojis first, [QuickPicks.emojis]),
 * a row of quick lines under them (its characters' names and short phrases, sent as messages with
 * one press; ◀▶ along the row), and the text field. ▼ goes from the reactions to the lines and on to
 * the text field, ▲ back up the same way (nothing else lies between, so focus never falls out to
 * whatever is further down the screen).
 */
@Composable
internal fun ChatComposer(
    firstReaction: FocusRequester,
    send: (String, ChatKind) -> Unit,
    draft: String,
    onDraft: (String) -> Unit,
    picks: QuickPicks = QuickPicks.PLAIN,
) {
    val field = remember { FocusRequester() }
    val lines = remember { FocusRequester() }
    val reactions = remember { FocusRequester() }
    val rows = picks.emojis.chunked(5)
    val hasLines = picks.lines.isNotEmpty()
    Column(Modifier.focusRequester(reactions).focusGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEachIndexed { row, emojis ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                emojis.forEachIndexed { i, emoji ->
                    var m: Modifier = Modifier
                    if (row == 0 && i == 0) m = m.focusRequester(firstReaction)
                    if (row == rows.lastIndex) m = m.focusProperties { down = if (hasLines) lines else field }
                    OutlinedButton(onClick = { send(emoji, ChatKind.REACTION) }, modifier = m) { Text(emoji, fontSize = 22.sp) }
                }
            }
        }
    }
    if (hasLines) {
        // One press sends the line as a message.
        // A plain scrolling row (a dozen chips at most): every chip stays composed, so ▼ always finds the first.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            picks.lines.forEachIndexed { i, line ->
                OutlinedButton(
                    onClick = { send(line, ChatKind.TEXT) },
                    modifier = (if (i == 0) Modifier.focusRequester(lines) else Modifier).focusProperties { up = firstReaction; down = field },
                ) { Text(line, maxLines = 1) }
            }
        }
    }
    // The text field with Send beside it (one line, so the panel keeps room for what was said).
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TvTextField(
            draft, onDraft, "Type a message",
            modifier = Modifier.weight(1f).focusRequester(field).focusProperties { up = if (hasLines) lines else firstReaction },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (draft.isNotBlank()) send(draft, ChatKind.TEXT) }),
        )
        Button(onClick = { send(draft, ChatKind.TEXT) }, enabled = draft.isNotBlank(), modifier = Modifier.focusProperties { up = if (hasLines) lines else firstReaction }) { Text("Send") }
    }
}

/**
 * The watch-together group's live chat beside the Watch together page (choosing what to watch):
 * the conversation so far, quick reactions and a text field.
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
            items(messages, key = { it.seq }) { Bubble(it) }
        }
        ChatComposer(firstReaction, ::send, draft, { draft = it.take(500) })
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun Bubble(m: ChatMessage) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(m.userName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            m.positionMs?.let { Text(formatClock(it), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f)) }
        }
        if (m.kind == ChatKind.REACTION) Text(m.text, fontSize = 34.sp)
        else Text(m.text, style = MaterialTheme.typography.bodyLarge, color = Color.White)
    }
}
