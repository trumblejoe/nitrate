package dev.nitrate.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nitrate.core.companion.Emojis
import dev.nitrate.core.companion.QuickPicks
import dev.nitrate.core.remote.RemoteChatDo
import dev.nitrate.core.remote.RemoteChatState

/**
 * The remote's Chat card, while the TV ([deviceName]) plays something with group chat: what it
 * shows (which conversation, the last few messages), the quick lines, the emoji button with the full
 * list and a message box with this phone's keyboard, and Turn off (or on) for that playback. Which
 * conversation is chosen before playing; here it's talked in. Everything goes through the TV
 * ([send]): it posts at its own position, in the group's conversation, and the card shows it once
 * the TV says so (nothing here changes before then).
 */
@Composable
fun RemoteChatCard(chat: RemoteChatState, deviceName: String, send: (RemoteChatDo) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    fun sendText() {
        val text = draft.trim()
        if (text.isEmpty()) return
        send(RemoteChatDo(RemoteChatDo.Kind.TEXT, text = text.take(500)))
        draft = ""
    }
    Column(
        Modifier.fillMaxWidth().padding(top = 16.dp).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.08f)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Chat on $deviceName", style = MaterialTheme.typography.labelLarge, color = accentColor())
        // Which conversation (chosen before playing), and Turn off for this playback.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (chat.on) chat.line else "Group chat is off", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (chat.on) androidx.compose.material3.TextButton(onClick = { send(RemoteChatDo(RemoteChatDo.Kind.TURN, on = false)) }) { Text("Turn off") }
        }
        if (!chat.on) {
            Text("Group chat is off on $deviceName.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { send(RemoteChatDo(RemoteChatDo.Kind.TURN, on = true)) }) { Text("Turn on") }
            return@Column
        }
        if (chat.recent.isEmpty()) Text("Nothing yet up to here.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        chat.recent.forEach { m ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(m.name, style = MaterialTheme.typography.labelMedium, color = accentColor(), fontWeight = FontWeight.Bold)
                Text(m.at, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(m.text, fontSize = if (m.reaction) 22.sp else 14.sp)
            }
        }
        if (chat.lines.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(chat.lines) { line -> SuggestionChip(onClick = { send(RemoteChatDo(RemoteChatDo.Kind.TEXT, text = line)) }, label = { Text(line) }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { picking = !picking }) {
                Text(if (picking) "⌨️" else "🙂", fontSize = 24.sp, modifier = Modifier.semantics { contentDescription = if (picking) "Close the emoji list" else "Emoji" })
            }
            OutlinedTextField(
                draft, { draft = it.take(500) },
                placeholder = { Text("Message") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { sendText() }),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { sendText() }, enabled = draft.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = accentColor()) }
        }
        if (picking) EmojiList(Emojis.groupsFor(QuickPicks(chat.emojis, chat.lines))) { emoji -> send(RemoteChatDo(RemoteChatDo.Kind.REACTION, text = emoji)); picking = false }
    }
}
