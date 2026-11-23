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
import dev.nitrate.core.companion.Moments
import dev.nitrate.core.companion.Notes
import dev.nitrate.core.companion.TimelineNote
import dev.nitrate.core.model.MediaItem
import kotlinx.coroutines.launch

/**
 * "Group notes": what the user's watch groups said along this title (group chat pinned to the
 * timeline, older moments among them), only up to where the user has got (all of it once watched).
 * Tapping one plays from there; the user's own can be removed. Nothing is fetched until it opens.
 */
@Composable
internal fun GroupNotesDialog(actions: AppActions, item: MediaItem, onPlayFrom: (Long) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var all by remember { mutableStateOf<List<TimelineNote>?>(null) }
    LaunchedEffect(item.id) { all = runCatching { actions.server.titleNotes(item.id)?.notes }.getOrNull().orEmpty() }
    val me = actions.server.userId
    val upTo = Notes.listableUpTo(item)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Group notes") },
        text = {
            val l = all
            val shown = l?.let { Notes.reached(it, upTo) }
            when {
                l == null || shown == null -> Text("Loading…")
                shown.isEmpty() -> Text(
                    if (l.isEmpty()) "Nothing yet. While watching, a reaction or a message in Group chat stays at that point for your group."
                    else "Your group has left notes further on. They show here as you get there.",
                )
                else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(shown, key = { it.id }) { n ->
                        Row(Modifier.fillMaxWidth().clickable { onDismiss(); onPlayFrom(n.positionMs) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(Notes.line(n, me), modifier = Modifier.weight(1f))
                            if (n.userId == me) TextButton(onClick = {
                                scope.launch { runCatching { actions.server.deleteNote(item.id, n.id) }; all = all?.filterNot { it.id == n.id } }
                            }) { Text("Remove") }
                        }
                    }
                    if (shown.size < l.size) item {
                        Text("More further on, shown as you get there.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
internal fun EarlierChatDialog(actions: AppActions, itemId: String, onDismiss: () -> Unit) {
    var list by remember { mutableStateOf<List<ChatRecord>?>(null) }
    LaunchedEffect(itemId) { list = runCatching { actions.server.chatHistory(itemId) }.getOrDefault(emptyList()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Earlier chat") },
        text = {
            val l = list
            when {
                l == null -> Text("Loading…")
                l.isEmpty() -> Text("No chat kept from watching this together (it's kept 3 years, for those who were there).")
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
