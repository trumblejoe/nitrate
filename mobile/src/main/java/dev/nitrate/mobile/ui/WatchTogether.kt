package dev.nitrate.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.nitrate.core.syncplay.GroupState
import dev.nitrate.core.syncplay.SyncPlayController
import dev.nitrate.core.syncplay.SyncPlayGroup
import kotlinx.coroutines.launch

internal fun GroupState.label() = when (this) {
    GroupState.IDLE -> "Nothing playing"
    GroupState.WAITING -> "Waiting for everyone"
    GroupState.PAUSED -> "Paused"
    GroupState.PLAYING -> "Playing"
}

/**
 * Watch together (SyncPlay): start a group or join one, see who's in it, leave. Whatever anyone
 * in the group plays, pauses or seeks happens for everyone.
 */
@Composable
fun WatchTogetherPanel(syncPlay: SyncPlayController, defaultName: String) {
    val group by syncPlay.group.collectAsState()
    val current = group
    if (current != null) InGroup(syncPlay, current) else FindGroup(syncPlay, defaultName)
}

@Composable
private fun InGroup(syncPlay: SyncPlayController, group: SyncPlayGroup) {
    val scope = rememberCoroutineScope()
    val queue by syncPlay.queue.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(group.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            group.state.label() + if (queue?.itemIds?.size?.let { it > 1 } == true) " · ${queue!!.index + 1} of ${queue!!.itemIds.size}" else "",
            color = accentColor(),
        )
        Text("In the group", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        group.participants.groupingBy { it }.eachCount().forEach { (name, count) ->
            Text(if (count > 1) "$name ($count devices)" else name, style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            "Play anything and it plays for everyone. Pausing or seeking on any device does it for all; if someone's buffering, the others wait.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { scope.launch { syncPlay.leave() } }) { Text("Leave group") }
    }
}

@Composable
private fun FindGroup(syncPlay: SyncPlayController, defaultName: String) {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf(defaultName) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val groups by produceState<Result<List<SyncPlayGroup>>?>(null, refresh) { value = runCatching { syncPlay.groups() } }
    fun act(block: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            runCatching { block() }.onFailure { error = it.message ?: "Couldn't reach the server" }
            busy = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Watch in step with friends and family on other devices, wherever they are.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("New group") }, singleLine = true, modifier = Modifier.weight(1f))
            Button(onClick = { act { syncPlay.create(name.ifBlank { defaultName }) } }, enabled = !busy) { Text("Start") }
        }
        Text("Join a group", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val list = groups?.getOrNull()
        when {
            groups == null -> Text("Looking for groups…")
            groups!!.isFailure -> Text(
                "Couldn't list groups. Your account may not be allowed to use SyncPlay (ask your server admin).",
                color = MaterialTheme.colorScheme.error,
            )
            list.isNullOrEmpty() -> Text("No groups right now. Start one, then join it from the other devices.")
            else -> list.forEach { g ->
                ListItem(
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { act { syncPlay.join(g.id) } },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(g.name) },
                    supportingContent = { Text("${g.participants.distinct().joinToString()} · ${g.state.label()}") },
                    trailingContent = { Text("Join", color = accentColor()) },
                )
            }
        }
        TextButton(onClick = { refresh++ }) { Text("Refresh") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
fun WatchTogetherDialog(actions: AppActions, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Watch together") },
        text = { Column(Modifier.fillMaxWidth()) { WatchTogetherPanel(actions.container.syncPlay, defaultName = defaultGroupName(actions)) } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
internal fun defaultGroupName(actions: AppActions): String {
    val account by actions.container.account.collectAsState()
    return account?.name?.let { "$it's group" } ?: "Movie night"
}
