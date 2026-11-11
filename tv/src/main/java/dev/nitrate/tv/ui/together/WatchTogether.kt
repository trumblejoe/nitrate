package dev.nitrate.tv.ui.together

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.nitrate.core.syncplay.GroupState
import dev.nitrate.core.syncplay.SyncPlayController
import dev.nitrate.core.syncplay.SyncPlayGroup
import dev.nitrate.tv.ui.components.TvTextField
import kotlinx.coroutines.launch

private fun GroupState.label() = when (this) {
    GroupState.IDLE -> "Nothing playing"
    GroupState.WAITING -> "Waiting for everyone"
    GroupState.PAUSED -> "Paused"
    GroupState.PLAYING -> "Playing"
}

/** Watch together as a page, from Home. */
@Composable
fun WatchTogetherScreen(syncPlay: SyncPlayController, defaultName: String) {
    Column(Modifier.fillMaxSize().padding(horizontal = 58.dp, vertical = 32.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Watch together", style = MaterialTheme.typography.headlineMedium)
        Column(Modifier.width(720.dp)) { WatchTogetherPanel(syncPlay, defaultName) }
    }
}

/**
 * Watch together (SyncPlay): start or join a group, see who's in it, leave. Whatever anyone in
 * the group plays, pauses or seeks happens for everyone.
 */
@Composable
fun WatchTogetherPanel(syncPlay: SyncPlayController, defaultName: String) {
    val group by syncPlay.group.collectAsState()
    val current = group
    val first = remember { FocusRequester() }
    if (current != null) InGroup(syncPlay, current, first) else FindGroup(syncPlay, defaultName, first)
    LaunchedEffect(current == null) { runCatching { first.requestFocus() } }
}

@Composable
private fun InGroup(syncPlay: SyncPlayController, group: SyncPlayGroup, first: FocusRequester) {
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(group.name, style = MaterialTheme.typography.titleLarge)
        Text(group.state.label(), color = MaterialTheme.colorScheme.primary)
        Text("In the group: " + group.participants.groupingBy { it }.eachCount().map { (n, c) -> if (c > 1) "$n ($c devices)" else n }.joinToString(), style = MaterialTheme.typography.bodyLarge)
        Text(
            "Play anything and it plays for everyone. Pausing or seeking on any device does it for all; if someone's buffering, the others wait.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { scope.launch { syncPlay.leave() } }, modifier = Modifier.focusRequester(first)) { Text("Leave group") }
    }
}

@Composable
private fun FindGroup(syncPlay: SyncPlayController, defaultName: String, first: FocusRequester) {
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
        Text("Watch in step with friends and family on other devices, wherever they are.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        val list = groups?.getOrNull()
        Text("Join a group", style = MaterialTheme.typography.titleSmall)
        when {
            groups == null -> Text("Looking for groups…")
            groups!!.isFailure -> Text("Couldn't list groups. Your account may not be allowed to use SyncPlay (ask your server admin).", color = MaterialTheme.colorScheme.error)
            list.isNullOrEmpty() -> Text("No groups right now. Start one, then join it from the other devices.")
            else -> list.forEachIndexed { i, g ->
                ListItem(
                    selected = false,
                    enabled = !busy,
                    onClick = { act { syncPlay.join(g.id) } },
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    headlineContent = { Text(g.name) },
                    supportingContent = { Text("${g.participants.distinct().joinToString()} · ${g.state.label()}") },
                    trailingContent = { Text("Join") },
                )
            }
        }
        Text("Start a group", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvTextField(name, { name = it }, placeholder = "Group name", modifier = Modifier.weight(1f).then(if (list.isNullOrEmpty()) Modifier.focusRequester(first) else Modifier))
            Button(onClick = { act { syncPlay.create(name.ifBlank { defaultName }) } }, enabled = !busy) { Text("Start") }
            OutlinedButton(onClick = { refresh++ }) { Text("Refresh") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
