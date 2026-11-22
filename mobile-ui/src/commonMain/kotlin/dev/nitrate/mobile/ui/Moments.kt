package dev.nitrate.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nitrate.core.companion.ChatRecord
import dev.nitrate.core.companion.Moment
import dev.nitrate.core.companion.Moments
import kotlinx.coroutines.launch

/** "Mark this moment" in the player's More: saves where it is now (Companion `moments`). */
@Composable
fun MarkMomentRow(actions: AppActions, itemId: String?, positionMs: () -> Long) {
    if (itemId == null || !actions.container.companionOffers("moments")) return
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FilterChip(selected = false, onClick = {
            val at = positionMs()
            scope.launch {
                note = runCatching { actions.server.addMoment(itemId, at, null) }.getOrNull()?.let { "Marked at " + Moments.clock(it.positionMs) } ?: "Couldn't mark it"
            }
        }, label = { Text("Mark this moment") })
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/**
 * A title's moments (the user's own and those shared with their watch groups) and, only when asked
 * for, the chat kept from viewings they were part of: two buttons on the details page, each opening
 * its list. Nothing is fetched until a button is pressed; nothing without the Companion's features.
 */
@Composable
fun MomentsAndChatButtons(actions: AppActions, itemId: String, onPlayFrom: (Long) -> Unit) {
    val container = actions.container
    val moments = container.companionOffers("moments")
    val chat = container.companionOffers("chathistory")
    if (!moments && !chat) return
    var open by remember { mutableStateOf<String?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (moments) TextButton(onClick = { open = "moments" }) { Text("Moments") }
        if (chat) TextButton(onClick = { open = "chat" }) { Text("Earlier chat") }
    }
    when (open) {
        "moments" -> MomentsDialog(actions, itemId, onPlayFrom) { open = null }
        "chat" -> EarlierChatDialog(actions, itemId) { open = null }
    }
}

@Composable
private fun MomentsDialog(actions: AppActions, itemId: String, onPlayFrom: (Long) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Moment>?>(null) }
    LaunchedEffect(itemId) { list = runCatching { actions.server.moments(itemId) }.getOrDefault(emptyList()) }
    val me = actions.server.userId
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Moments") },
        text = {
            val l = list
            when {
                l == null -> Text("Loading…")
                l.isEmpty() -> Text("None yet. In the player, More → Mark this moment saves where you are.")
                else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(l, key = { it.id }) { m ->
                        Row(Modifier.fillMaxWidth().clickable { onDismiss(); onPlayFrom(m.positionMs) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(Moments.line(m, me), modifier = Modifier.weight(1f))
                            if (m.userId == me) TextButton(onClick = {
                                scope.launch { runCatching { actions.server.deleteMoment(m.id) }; list = list?.filterNot { it.id == m.id } }
                            }) { Text("Remove") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun EarlierChatDialog(actions: AppActions, itemId: String, onDismiss: () -> Unit) {
    var list by remember { mutableStateOf<List<ChatRecord>?>(null) }
    LaunchedEffect(itemId) { list = runCatching { actions.server.chatHistory(itemId) }.getOrDefault(emptyList()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Earlier chat") },
        text = {
            val l = list
            when {
                l == null -> Text("Loading…")
                l.isEmpty() -> Text("No chat kept from watching this together (it's kept 30 days, for those who were there).")
                else -> LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(l) { m ->
                        Column {
                            Text(m.name + (m.positionMs?.let { " · at " + Moments.clock(it) } ?: ""), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(m.text, style = if (m.reaction) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
