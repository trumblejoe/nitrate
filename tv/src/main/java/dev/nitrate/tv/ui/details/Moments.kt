package dev.nitrate.tv.ui.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.nitrate.core.MediaServer
import dev.nitrate.core.companion.ChatRecord
import dev.nitrate.core.companion.Moments
import dev.nitrate.core.companion.Notes
import dev.nitrate.core.companion.TimelineNote
import dev.nitrate.core.model.MediaItem
import dev.nitrate.tv.ui.lists.TvDialog
import kotlinx.coroutines.launch

/**
 * "Group notes": what the user's watch groups said along this title (group chat pinned to the
 * timeline, older moments among them), only up to where the user has got (all of it once watched);
 * OK plays from one, and the user's own can be removed. Opened from the details page's More.
 */
@Composable
internal fun GroupNotesDialog(repository: MediaServer, item: MediaItem, onPlayFrom: (Long) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var all by remember { mutableStateOf<List<TimelineNote>?>(null) }
    LaunchedEffect(item.id) { all = runCatching { repository.titleNotes(item.id)?.notes }.getOrNull().orEmpty() }
    val me = repository.userId
    val upTo = Notes.listableUpTo(item)
    TvDialog(onDismiss = onDismiss, width = 760) {
        Text("Group notes", style = MaterialTheme.typography.headlineSmall)
        val l = all
        val shown = l?.let { Notes.reached(it, upTo) }
        when {
            l == null || shown == null -> Text("Loading…")
            shown.isEmpty() -> Text(
                if (l.isEmpty()) "Nothing yet. While watching, a reaction or a message in Group chat stays at that point for your group."
                else "Your group has left notes further on. They show here as you get there.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                shown.forEach { n ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ListItem(
                            selected = false,
                            onClick = { onDismiss(); onPlayFrom(n.positionMs) },
                            headlineContent = { Text(Notes.line(n, me)) },
                            supportingContent = { Text("Play from here") },
                            modifier = androidx.compose.ui.Modifier.weight(1f),
                        )
                        if (n.userId == me) OutlinedButton(onClick = {
                            scope.launch { runCatching { repository.deleteNote(item.id, n.id) }; all = all?.filterNot { it.id == n.id } }
                        }) { Text("Remove") }
                    }
                }
                if (shown.size < l.size) Text("More further on, shown as you get there.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        OutlinedButton(onClick = onDismiss) { Text("Close") }
    }
}

@Composable
internal fun EarlierChatDialog(repository: MediaServer, itemId: String, onDismiss: () -> Unit) {
    var list by remember { mutableStateOf<List<ChatRecord>?>(null) }
    LaunchedEffect(itemId) { list = runCatching { repository.chatHistory(itemId) }.getOrDefault(emptyList()) }
    TvDialog(onDismiss = onDismiss, width = 760) {
        Text("Earlier chat", style = MaterialTheme.typography.headlineSmall)
        val l = list
        when {
            l == null -> Text("Loading…")
            l.isEmpty() -> Text("No chat kept from watching this together (it's kept 3 years, for those who were there).", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> dev.nitrate.tv.ui.lists.ScrollBox(maxHeight = 420, takeFocus = true) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    l.forEach { m ->
                        Text(m.name + (m.positionMs?.let { " · at " + Moments.clock(it) } ?: ""), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(m.text, style = if (m.reaction) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        OutlinedButton(onClick = onDismiss) { Text("Close") }
    }
}

/** The Companion plugin's features on this server (empty without it), asked once per page. */
@Composable
fun rememberCompanionFeatures(repository: MediaServer): Set<String> {
    val features by produceState(emptySet<String>(), repository) {
        value = runCatching { repository.companion()?.features?.toSet() }.getOrNull().orEmpty()
    }
    return features
}
