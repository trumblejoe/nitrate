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

fun GroupState.label() = when (this) {
    GroupState.IDLE -> "Nothing playing"
    GroupState.WAITING -> "Waiting for everyone"
    GroupState.PAUSED -> "Paused"
    GroupState.PLAYING -> "Playing"
}

/**
 * Watch party (SyncPlay): start a group or join one, see who's in it, leave. Whatever anyone
 * in the group plays, pauses or seeks happens for everyone.
 */
@Composable
fun WatchTogetherPanel(actions: AppActions, defaultName: String) {
    val syncPlay = actions.container.syncPlay
    val group by syncPlay.group.collectAsState()
    val companion by actions.container.companion.collectAsState()
    val account by actions.container.account.collectAsState()
    val current = group
    // Chat needs the Nitrate Companion plugin on the server.
    var chatting by remember { mutableStateOf<SyncPlayGroup?>(null) }
    val canChat = companion?.chat == true
    var inviting by remember { mutableStateOf(false) }
    if (current != null) InGroup(syncPlay, current, onChat = { chatting = current }.takeIf { canChat },
        onInvite = { inviting = true }.takeIf { actions.container.invitesAvailable() })
    else {
        // A phone beside the TV: chats for this account's devices in the group, without playing.
        val alongside: (SyncPlayGroup) -> Unit = { g ->
            actions.container.chatAlongside(g.id)
            chatting = g
        }
        FindGroup(syncPlay, defaultName, myName = account?.name, onChatAlongside = alongside.takeIf { canChat })
    }
    if (inviting && current != null) InvitePeopleDialog(actions, current) { inviting = false }
    chatting?.let { g ->
        ChatSheet(actions.container.chat, "Chat · ${g.name}", positionMs = { null }) {
            if (current == null) actions.container.chatAlongside(null)
            chatting = null
        }
    }
}

@Composable
private fun InGroup(syncPlay: SyncPlayController, group: SyncPlayGroup, onChat: (() -> Unit)?, onInvite: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    val queue by syncPlay.queue.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(group.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            group.state.label() + if (queue?.itemIds?.size?.let { it > 1 } == true) " · ${queue!!.index + 1} of ${queue!!.itemIds.size}" else "",
            color = accentColor(),
        )
        Text("In the party", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        group.participants.groupingBy { it }.eachCount().forEach { (name, count) ->
            Text(if (count > 1) "$name ($count devices)" else name, style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            "Play anything and it plays for everyone. Pausing or seeking on any device does it for all; if someone's buffering, the others wait.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            onChat?.let { Button(onClick = it) { Text("Chat") } }
            onInvite?.let { OutlinedButton(onClick = it) { Text("Invite people") } }
            OutlinedButton(onClick = { scope.launch { syncPlay.leave() } }) { Text("Leave the party") }
        }
    }
}

@Composable
private fun FindGroup(syncPlay: SyncPlayController, defaultName: String, myName: String?, onChatAlongside: ((SyncPlayGroup) -> Unit)?) {
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
            OutlinedTextField(name, { name = it }, label = { Text("New session") }, singleLine = true, modifier = Modifier.weight(1f))
            Button(onClick = { act { syncPlay.create(name.ifBlank { defaultName }) } }, enabled = !busy) { Text("Start") }
        }
        Text("Join someone watching now", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val list = groups?.getOrNull()
        when {
            groups == null -> Text("Looking for watch parties…")
            groups!!.isFailure -> Text(
                "Couldn't list watch parties. Your account may not be allowed to join them (ask your server admin).",
                color = MaterialTheme.colorScheme.error,
            )
            list.isNullOrEmpty() -> Text("No watch parties right now. Start one, then join it from the other devices.")
            else -> list.forEach { g ->
                // Already watching in it on another device (the TV): chat from here without playing.
                val mine = myName != null && g.participants.any { it.equals(myName, ignoreCase = true) }
                ListItem(
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { act { syncPlay.join(g.id) } },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(g.name) },
                    supportingContent = { Text("${g.participants.distinct().joinToString()} · ${g.state.label()}") },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (mine && onChatAlongside != null) TextButton(onClick = { onChatAlongside(g) }) { Text("Chat") }
                            Text("Join", color = accentColor())
                        }
                    },
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
        title = { Text("Watch now together") },
        text = { Column(Modifier.fillMaxWidth()) { WatchTogetherPanel(actions, defaultName = defaultGroupName(actions)) } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
fun defaultGroupName(actions: AppActions): String {
    val account by actions.container.account.collectAsState()
    return account?.name?.let { "$it's group" } ?: "Movie night"
}

/**
 * Invites people (the server's people list: private accounts are never in it) to this group. They
 * get a notification, or a prompt in the app, and joining puts them in the group.
 */
@Composable
fun InvitePeopleDialog(actions: AppActions, group: SyncPlayGroup, onDismiss: () -> Unit) {
    val container = actions.container
    val scope = rememberCoroutineScope()
    val me = container.server.userId
    val people by produceState<List<dev.nitrate.core.writing.UserRef>?>(null) {
        value = runCatching { container.server.partnerPeople() }.getOrDefault(emptyList()).filter { it.id != me }
    }
    var chosen by remember { mutableStateOf(emptySet<String>()) }
    var sent by remember { mutableStateOf(false) }
    val queue by container.syncPlay.queue.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (sent) "Invites sent" else "Invite to ${group.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    sent -> Text("They'll see it on their phone or when they open Nitrate.")
                    people == null -> Text("Loading…")
                    people!!.isEmpty() -> Text("Nobody else to invite on this server.")
                    else -> people!!.forEach { p ->
                        Row(Modifier.fillMaxWidth().clickable { chosen = if (p.id in chosen) chosen - p.id else chosen + p.id }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(checked = p.id in chosen, onCheckedChange = null)
                            Text(p.name, Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (sent) TextButton(onClick = onDismiss) { Text("Done") }
            else TextButton(enabled = chosen.isNotEmpty(), onClick = {
                scope.launch {
                    val itemId = queue?.let { q -> q.itemIds.getOrNull(q.index) }
                    val itemName = itemId?.let { runCatching { container.server.getItem(it).displayTitle }.getOrNull() }
                    runCatching { container.server.inviteToWatch(group.id, chosen.toList(), itemId, itemName) }
                    sent = true
                }
            }) { Text("Invite") }
        },
        dismissButton = { if (!sent) TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * A watch-together invite waiting (checked every 30 s while the app is open): Join puts this
 * device in the group, Not now turns it down.
 */
@Composable
fun WatchInvitePrompt(actions: AppActions) {
    val container = actions.container
    val companion by container.companion.collectAsState()
    androidx.compose.runtime.LaunchedEffect(companion) {
        while (true) {
            container.refreshInvites()
            kotlinx.coroutines.delay(30_000)
        }
    }
    val invites by container.watchInvites.collectAsState()
    val scope = rememberCoroutineScope()
    val invite = invites.firstOrNull() ?: return
    AlertDialog(
        onDismissRequest = { scope.launch { container.dismissInvite(invite) } },
        title = { Text("Watch now together?") },
        text = { Text(invite.text + ". Joining puts you in their session: what they play, plays for you.") },
        confirmButton = { TextButton(onClick = { scope.launch { runCatching { container.joinInvite(invite) } } }) { Text("Join") } },
        dismissButton = { TextButton(onClick = { scope.launch { container.dismissInvite(invite) } }) { Text("Not now") } },
    )
}
