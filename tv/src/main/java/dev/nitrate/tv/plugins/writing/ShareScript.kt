package dev.nitrate.tv.plugins.writing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Checkbox
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.nitrate.android.writing.SharedWriting
import dev.nitrate.core.writing.SharedScriptInfo
import dev.nitrate.core.writing.UserRef
import dev.nitrate.tv.ui.components.TvTextField
import kotlinx.coroutines.launch

/**
 * Who a script is shared with, D-pad first: tick people (OK on each), then Share or Save. The
 * owner can stop sharing for everyone; an invited writer can leave. Everyone keeps their copy.
 */
@Composable
internal fun SharePanel(shared: SharedWriting, localId: String, onMessage: (String) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<SharedScriptInfo?>(null) }
    var people by remember { mutableStateOf<List<UserRef>>(emptyList()) }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var byName by remember { mutableStateOf("") }
    var confirmStop by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(localId) {
        runCatching {
            info = shared.info(localId)
            people = shared.people()
            chosen = info?.members?.map { it.id }?.toSet().orEmpty()
        }.onFailure { error = it.message ?: "Couldn't reach the server" }
        loading = false
        runCatching { first.requestFocus() }
    }
    fun act(block: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            runCatching { block() }.onFailure { error = it.message ?: "Couldn't reach the server" }
            busy = false
        }
    }
    // Synced but with nobody invited yet: still "not shared" here.
    val current = info?.takeIf { it.members.isNotEmpty() || !it.isOwner(shared.userId) }
    val isOwner = current == null || current.isOwner(shared.userId)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (current == null) "Share this script" else "Shared script", style = MaterialTheme.typography.titleLarge)
        when {
            loading -> Text("Asking the server…")
            current != null && !isOwner -> {
                Text("${current.owner.name} shared this with you. Writing with: " +
                    (listOf(current.owner) + current.members).filter { it.id != shared.userId }.joinToString { it.name })
                Text("Leaving keeps your own copy on this TV.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { act { shared.leave(localId); onMessage("Left: the script stays here as your own"); onDone() } }, enabled = !busy, modifier = Modifier.focusRequester(first)) { Text("Leave") }
                    OutlinedButton(onClick = onDone) { Text("Back") }
                }
            }
            else -> {
                Text(
                    if (current == null) "Tick who can write in it with you. Only they can open it." else "Only you and the people ticked can open it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (current == null) Button(onClick = {
                        act { val made = shared.share(localId, chosen.toList()); onMessage("Shared with " + made.members.joinToString { it.name }); onDone() }
                    }, enabled = !busy && chosen.isNotEmpty(), modifier = Modifier.focusRequester(first)) { Text("Share") }
                    else Button(onClick = { act { shared.setMembers(localId, chosen.toList()); onMessage("Sharing updated"); onDone() } }, enabled = !busy, modifier = Modifier.focusRequester(first)) { Text("Save") }
                    OutlinedButton(onClick = onDone) { Text("Back") }
                    if (current != null) OutlinedButton(onClick = {
                        if (confirmStop) act { shared.stopSharing(localId); onMessage("Stopped sharing: everyone keeps their own copy"); onDone() } else confirmStop = true
                    }, enabled = !busy) { Text(if (confirmStop) "Select again to stop sharing" else "Stop sharing") }
                }
                // Servers can hide the list of people: invite by exact user name instead.
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvTextField(byName, { byName = it }, "Add by user name", modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = {
                        act {
                            val found = shared.findPerson(byName.trim())
                            if (found == null) error = "No one called \"${byName.trim()}\" on this server."
                            else { people = (people + found).distinctBy { it.id }; chosen = chosen + found.id; byName = "" }
                        }
                    }, enabled = byName.isNotBlank() && !busy) { Text("Add") }
                }
                val list = (people + current?.members.orEmpty()).distinctBy { it.id }.sortedBy { it.name.lowercase() }
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    items(list, key = { it.id }) { p ->
                        ListItem(
                            selected = false,
                            onClick = { chosen = if (p.id in chosen) chosen - p.id else chosen + p.id },
                            headlineContent = { Text(p.name) },
                            trailingContent = { Checkbox(checked = p.id in chosen, onCheckedChange = null) },
                        )
                    }
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Scripts others shared with this user that aren't on this TV yet. */
@Composable
internal fun SharedWithYouItems(shared: SharedWriting, linked: Set<String>, onOpen: (SharedScriptInfo) -> Unit) {
    val list by produceState<List<SharedScriptInfo>>(emptyList(), linked) {
        value = runCatching { shared.list() }.getOrDefault(emptyList()).filter { it.id !in linked }
    }
    if (list.isEmpty()) return
    Text("Shared with you", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    list.forEach { s ->
        ListItem(
            selected = false,
            onClick = { onOpen(s) },
            headlineContent = { Text(s.title) },
            supportingContent = { Text("From ${s.owner.name}") },
        )
    }
}
