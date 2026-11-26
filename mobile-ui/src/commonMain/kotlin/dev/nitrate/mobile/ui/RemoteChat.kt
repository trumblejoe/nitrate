package dev.nitrate.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nitrate.core.companion.Emojis
import dev.nitrate.core.companion.NoteChats
import dev.nitrate.core.companion.QuickPicks
import dev.nitrate.core.remote.RemoteChatDo
import dev.nitrate.core.remote.RemoteChatState

/**
 * The remote's Chat card, while the TV ([deviceName]) plays something with group chat: what it
 * shows (which chat, the last few messages), the emoji button with the full list, the quick lines
 * and a message box with this phone's keyboard; the chat line opens Group chat on or off, New chat
 * and the earlier chats to play along. Everything goes through the TV ([send]): it posts at its own
 * position, in its chat, for its group, and the card shows it once the TV says so (nothing here
 * changes before then).
 */
@Composable
fun RemoteChatCard(chat: RemoteChatState, deviceName: String, send: (RemoteChatDo) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
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
        Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Change the chat") { choosing = !choosing }.padding(vertical = 4.dp)) {
            Text(chat.line + " ›", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(if (chat.hasChats) "Change chat, or turn it off" else "Turn it on or off", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (choosing) {
            Row(
                Modifier.fillMaxWidth().toggleable(value = chat.on, role = Role.Switch) { on -> send(RemoteChatDo(RemoteChatDo.Kind.TURN, on = on)) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Group chat: " + if (chat.on) "On" else "Off", Modifier.weight(1f))
                Switch(checked = chat.on, onCheckedChange = null)
            }
            if (chat.on && chat.hasChats) {
                ChatOption(NoteChats.NEW_CHAT, "Nothing earlier pops up", chat.playing == null) { send(RemoteChatDo(RemoteChatDo.Kind.PLAY)); choosing = false }
                chat.chats.forEach { c -> ChatOption(c.title, c.detail, chat.playing == c.id) { send(RemoteChatDo(RemoteChatDo.Kind.PLAY, chatId = c.id)); choosing = false } }
            }
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

@Composable
private fun ChatOption(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
