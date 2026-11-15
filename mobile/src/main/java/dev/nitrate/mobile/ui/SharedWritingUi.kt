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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import dev.nitrate.android.writing.SharedWriting
import dev.nitrate.core.writing.SharedScriptInfo
import dev.nitrate.core.writing.UserRef
import kotlinx.coroutines.launch

/**
 * Sharing a script: who it's shared with. Not shared yet: pick people and share. The owner
 * changes who's invited or stops sharing for everyone; an invited writer can leave. Everyone
 * always keeps their own copy of the script.
 */
@Composable
fun ShareScriptDialog(shared: SharedWriting, localId: String, onMessage: (String) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<SharedScriptInfo?>(null) }
    var people by remember { mutableStateOf<List<UserRef>?>(null) }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var byName by remember { mutableStateOf("") }
    var confirmStop by remember { mutableStateOf(false) }
    LaunchedEffect(localId) {
        runCatching {
            info = shared.info(localId)
            people = shared.people()
            chosen = info?.members?.map { it.id }?.toSet().orEmpty()
        }.onFailure { error = it.message ?: "Couldn't reach the server" }
        loading = false
    }
    fun act(block: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            runCatching { block() }.onFailure { error = it.message ?: "Couldn't reach the server" }
            busy = false
        }
    }
    val current = info
    val isOwner = current == null || current.isOwner(shared.userId)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (current == null) "Share this script" else "Shared script") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    loading -> Text("Asking the server…")
                    current != null && !isOwner -> {
                        Text("${current.owner.name} shared this with you.", style = MaterialTheme.typography.bodyMedium)
                        Text("Writing with: " + (listOf(current.owner) + current.members).filter { it.id != shared.userId }.joinToString { it.name })
                        Text(
                            "Leaving keeps your own copy here; you stop seeing the others' changes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> {
                        Text(
                            if (current == null) "Pick who can write in it with you. Only the people you pick can open it."
                            else "Only you and the people ticked can open it.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        val list = (people.orEmpty() + current?.members.orEmpty()).distinctBy { it.id }.sortedBy { it.name.lowercase() }
                        LazyColumn(Modifier.heightIn(max = 300.dp)) {
                            items(list, key = { it.id }) { p ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { chosen = if (p.id in chosen) chosen - p.id else chosen + p.id }.padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(checked = p.id in chosen, onCheckedChange = null)
                                    Text(p.name, modifier = Modifier.padding(start = 8.dp))
                                }
                            }
                        }
                        // Servers can hide the list: invite by exact user name instead.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(byName, { byName = it }, label = { Text("Add by user name") }, singleLine = true, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                act {
                                    val found = shared.findPerson(byName.trim())
                                    if (found == null) error = "No one called \"${byName.trim()}\" on this server."
                                    else { people = (people.orEmpty() + found).distinctBy { it.id }; chosen = chosen + found.id; byName = "" }
                                }
                            }, enabled = byName.isNotBlank() && !busy) { Text("Add") }
                        }
                        if (current != null) {
                            if (confirmStop) {
                                TextButton(onClick = {
                                    act { shared.stopSharing(localId); onMessage("Stopped sharing. Everyone keeps their own copy."); onDismiss() }
                                }) { Text("Stop sharing for everyone? Tap to confirm", color = MaterialTheme.colorScheme.error) }
                            } else {
                                TextButton(onClick = { confirmStop = true }) { Text("Stop sharing…", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            when {
                loading -> Unit
                current != null && !isOwner -> TextButton(onClick = {
                    act { shared.leave(localId); onMessage("Left. The script stays here as your own."); onDismiss() }
                }, enabled = !busy) { Text("Leave") }
                current == null -> TextButton(onClick = {
                    act {
                        val made = shared.share(localId, chosen.toList())
                        onMessage("Shared with ${made.members.joinToString { it.name }.ifEmpty { "no one yet" }}")
                        onDismiss()
                    }
                }, enabled = !busy && chosen.isNotEmpty()) { Text("Share") }
                else -> TextButton(onClick = {
                    act { shared.setMembers(localId, chosen.toList()); onMessage("Sharing updated"); onDismiss() }
                }, enabled = !busy) { Text("Save") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Scripts others shared with this user that aren't on this device yet. */
@Composable
fun SharedWithYou(shared: SharedWriting, linked: Set<String>, onOpen: (SharedScriptInfo) -> Unit) {
    val list by androidx.compose.runtime.produceState<List<SharedScriptInfo>>(emptyList(), linked) {
        value = runCatching { shared.list() }.getOrDefault(emptyList()).filter { it.id !in linked }
    }
    if (list.isEmpty()) return
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Shared with you", style = MaterialTheme.typography.labelLarge, color = accentColor())
        list.forEach { s ->
            Column(Modifier.fillMaxWidth().clickable { onOpen(s) }.padding(vertical = 8.dp)) {
                Text(s.title, style = MaterialTheme.typography.bodyLarge)
                Text("From ${s.owner.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
