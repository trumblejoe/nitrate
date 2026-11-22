package dev.nitrate.tv.ui.details

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
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
import dev.nitrate.core.companion.Moment
import dev.nitrate.core.companion.Moments
import dev.nitrate.tv.ui.lists.TvDialog
import kotlinx.coroutines.launch

/**
 * "Moments" (the user's own marks and those shared with their watch groups; OK plays from one) and
 * "Earlier chat" (what was said while watching it together, for those who were there): buttons on
 * the details page, each opening its list only when pressed. Nothing shows without the Companion's
 * features; chat is never shown by itself.
 */
@Composable
fun MomentsAndChatButtons(repository: MediaServer, itemId: String, onPlayFrom: (Long) -> Unit) {
    val features by produceState(emptySet<String>(), repository) {
        value = runCatching { repository.companion()?.features?.toSet() }.getOrNull().orEmpty()
    }
    var open by remember { mutableStateOf<String?>(null) }
    if ("moments" in features) OutlinedButton(onClick = { open = "moments" }) { Text("Moments") }
    if ("chathistory" in features) OutlinedButton(onClick = { open = "chat" }) { Text("Earlier chat") }
    when (open) {
        "moments" -> MomentsDialog(repository, itemId, onPlayFrom) { open = null }
        "chat" -> EarlierChatDialog(repository, itemId) { open = null }
    }
}

@Composable
private fun MomentsDialog(repository: MediaServer, itemId: String, onPlayFrom: (Long) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Moment>?>(null) }
    LaunchedEffect(itemId) { list = runCatching { repository.moments(itemId) }.getOrDefault(emptyList()) }
    val me = repository.userId
    TvDialog(onDismiss = onDismiss, width = 760) {
        Text("Moments", style = MaterialTheme.typography.headlineSmall)
        val l = list
        when {
            l == null -> Text("Loading…")
            l.isEmpty() -> Text("None yet. In the player, More → Settings → Mark this moment saves where you are.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> l.forEach { m ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ListItem(
                        selected = false,
                        onClick = { onDismiss(); onPlayFrom(m.positionMs) },
                        headlineContent = { Text(Moments.line(m, me)) },
                        supportingContent = { Text("Play from here") },
                        modifier = androidx.compose.ui.Modifier.weight(1f),
                    )
                    if (m.userId == me) OutlinedButton(onClick = {
                        scope.launch { runCatching { repository.deleteMoment(m.id) }; list = list?.filterNot { it.id == m.id } }
                    }) { Text("Remove") }
                }
            }
        }
        OutlinedButton(onClick = onDismiss) { Text("Close") }
    }
}

@Composable
private fun EarlierChatDialog(repository: MediaServer, itemId: String, onDismiss: () -> Unit) {
    var list by remember { mutableStateOf<List<ChatRecord>?>(null) }
    LaunchedEffect(itemId) { list = runCatching { repository.chatHistory(itemId) }.getOrDefault(emptyList()) }
    TvDialog(onDismiss = onDismiss, width = 760) {
        Text("Earlier chat", style = MaterialTheme.typography.headlineSmall)
        val l = list
        when {
            l == null -> Text("Loading…")
            l.isEmpty() -> Text("No chat kept from watching this together (it's kept 30 days, for those who were there).", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
