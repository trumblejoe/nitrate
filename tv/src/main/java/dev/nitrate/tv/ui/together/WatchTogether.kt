package dev.nitrate.tv.ui.together

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
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

/** Watch party as a page, from Home. */
@Composable
fun WatchTogetherScreen(syncPlay: SyncPlayController, defaultName: String, chat: dev.nitrate.core.companion.GroupChat? = null) {
    val group by syncPlay.group.collectAsState()
    val chatGroup = chat?.groupId?.collectAsState()?.value
    androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxHeight().padding(horizontal = 58.dp, vertical = 32.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Watch now together", style = MaterialTheme.typography.headlineMedium)
            Column(Modifier.width(720.dp)) { WatchTogetherPanel(syncPlay, defaultName) }
        }
        // While choosing what to watch: the group's chat (with the Companion plugin on the server).
        val current = group
        if (chat != null && current != null && chatGroup == current.id) {
            ChatPanel(chat, current.name, positionMs = { null }, autoFocus = false)
        }
    }
}

/**
 * Watch party (SyncPlay): start or join a group, see who's in it, leave. Whatever anyone in
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
        Text("In the party: " + group.participants.groupingBy { it }.eachCount().map { (n, c) -> if (c > 1) "$n ($c devices)" else n }.joinToString(), style = MaterialTheme.typography.bodyLarge)
        Text(
            "Play anything and it plays for everyone. Pausing or seeking on any device does it for all; if someone's buffering, the others wait.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val container = dev.nitrate.tv.ui.pages.LocalTvContainer.current
        var inviting by remember { mutableStateOf(false) }
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { scope.launch { syncPlay.leave() } }, modifier = Modifier.focusRequester(first)) { Text("Leave the party") }
            if (container?.invitesAvailable() == true) OutlinedButton(onClick = { inviting = true }) { Text("Invite people") }
        }
        if (inviting && container != null) InvitePeopleDialog(container, group) { inviting = false }
    }
}

/** Invites people (the server's people list; private accounts are never in it) to this group. */
@Composable
fun InvitePeopleDialog(container: dev.nitrate.android.AppContainer, group: SyncPlayGroup, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val me = container.server.userId
    val people by androidx.compose.runtime.produceState<List<dev.nitrate.core.writing.UserRef>?>(null) {
        value = runCatching { container.server.partnerPeople() }.getOrDefault(emptyList()).filter { it.id != me }
    }
    var chosen by remember { mutableStateOf(emptySet<String>()) }
    var sent by remember { mutableStateOf(false) }
    val queue by container.syncPlay.queue.collectAsState()
    val focus = remember { FocusRequester() }
    dev.nitrate.tv.ui.lists.TvDialog(onDismiss, width = 620) {
        Text(if (sent) "Invites sent" else "Invite to ${group.name}", style = MaterialTheme.typography.headlineSmall)
        when {
            sent -> Text("They'll see it on their phone or when they open Nitrate.")
            people == null -> Text("Loading…")
            people!!.isEmpty() -> Text("Nobody else to invite on this server.")
            else -> people!!.forEachIndexed { i, p ->
                androidx.tv.material3.ListItem(
                    selected = p.id in chosen,
                    onClick = { chosen = if (p.id in chosen) chosen - p.id else chosen + p.id },
                    headlineContent = { Text(p.name) },
                    trailingContent = { androidx.tv.material3.Switch(checked = p.id in chosen, onCheckedChange = null) },
                    modifier = if (i == 0) Modifier.focusRequester(focus) else Modifier,
                )
            }
        }
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (sent) androidx.tv.material3.Button(onClick = onDismiss) { Text("Done") }
            else {
                androidx.tv.material3.Button(enabled = chosen.isNotEmpty(), onClick = {
                    scope.launch {
                        val itemId = queue?.let { q -> q.itemIds.getOrNull(q.index) }
                        val itemName = itemId?.let { runCatching { container.server.getItem(it).displayTitle }.getOrNull() }
                        runCatching { container.server.inviteToWatch(group.id, chosen.toList(), itemId, itemName) }
                        sent = true
                    }
                }) { Text("Invite") }
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    }
    LaunchedEffect(people) { if (!people.isNullOrEmpty()) runCatching { focus.requestFocus() } }
}

/**
 * A watch-together invite waiting (checked every 30 s while the app is open; TVs show no
 * notifications): Join puts this TV in the group, Not now turns it down.
 */
@Composable
fun WatchInvitePrompt(container: dev.nitrate.android.AppContainer) {
    val companion by container.companion.collectAsState()
    LaunchedEffect(companion) {
        while (true) {
            container.refreshInvites()
            kotlinx.coroutines.delay(30_000)
        }
    }
    val invites by container.watchInvites.collectAsState()
    val scope = rememberCoroutineScope()
    val invite = invites.firstOrNull() ?: return
    val focus = remember(invite.id) { FocusRequester() }
    dev.nitrate.tv.ui.lists.TvDialog({ scope.launch { container.dismissInvite(invite) } }, width = 640) {
        Text("Watch now together?", style = MaterialTheme.typography.headlineSmall)
        Text(invite.text + ". Joining puts this TV in their session: what they play, plays here.")
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.tv.material3.Button(onClick = { scope.launch { runCatching { container.joinInvite(invite) } } }, modifier = Modifier.focusRequester(focus)) { Text("Join") }
            OutlinedButton(onClick = { scope.launch { container.dismissInvite(invite) } }) { Text("Not now") }
        }
    }
    LaunchedEffect(invite.id) { kotlinx.coroutines.delay(50); runCatching { focus.requestFocus() } }
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
        Text("Join someone watching now", style = MaterialTheme.typography.titleSmall)
        when {
            groups == null -> Text("Looking for watch parties…")
            groups!!.isFailure -> Text("Couldn't list watch parties. Your account may not be allowed to join them (ask your server admin).", color = MaterialTheme.colorScheme.error)
            list.isNullOrEmpty() -> Text("No watch parties right now. Start one, then join it from the other devices.")
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
        Text("Start watching now together", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvTextField(name, { name = it }, placeholder = "Party name", modifier = Modifier.weight(1f).then(if (list.isNullOrEmpty()) Modifier.focusRequester(first) else Modifier))
            Button(onClick = { act { syncPlay.create(name.ifBlank { defaultName }) } }, enabled = !busy) { Text("Start") }
            OutlinedButton(onClick = { refresh++ }) { Text("Refresh") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
