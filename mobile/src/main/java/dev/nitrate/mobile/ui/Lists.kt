package dev.nitrate.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.MediaKind
import kotlinx.coroutines.launch

/**
 * Adds [item] to a playlist or collection, or to a new one. Collections only for accounts
 * allowed to manage them.
 */
@Composable
fun AddToListDialog(actions: AppActions, item: MediaItem, onDismiss: () -> Unit) {
    val server = actions.server
    val scope = rememberCoroutineScope()
    val account by actions.container.account.collectAsState()
    val canCollect = account?.canManageCollections == true
    val playlists by produceState<List<MediaItem>?>(null) { value = runCatching { server.playlists() }.getOrDefault(emptyList()) }
    val collections by produceState<List<MediaItem>?>(null, canCollect) { value = if (canCollect) runCatching { server.collections() }.getOrDefault(emptyList()) else emptyList() }
    var creating by remember { mutableStateOf<String?>(null) } // "playlist" or "collection"
    var name by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val audio = item.mediaKind == MediaKind.AUDIO

    fun run(done: String, block: suspend () -> Unit) {
        scope.launch {
            status = runCatching { block() }.fold({ done }, { "Couldn't do that: ${it.message}" })
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add “${item.displayTitle}” to…") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                status?.let { Text(it, color = accentColor()) }
                if (creating != null) {
                    OutlinedTextField(name, { name = it }, label = { Text(if (creating == "playlist") "New playlist" else "New collection") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { creating = null }) { Text("Cancel") }
                        TextButton(enabled = name.isNotBlank(), onClick = {
                            val kind = creating
                            creating = null
                            run("Added to new ${kind} “$name”") {
                                if (kind == "playlist") server.createPlaylist(name.trim(), listOf(item.id), audio) else server.createCollection(name.trim(), listOf(item.id))
                            }
                        }) { Text("Create") }
                    }
                    return@Column
                }
                Section("Playlists")
                Row({ creating = "playlist"; name = "" }, "＋ New playlist")
                playlists?.forEach { p -> Row({ run("Added to “${p.name}”") { server.addToPlaylist(p.id, listOf(item.id)) } }, p.name) }
                if (canCollect) {
                    Section("Collections")
                    Row({ creating = "collection"; name = "" }, "＋ New collection")
                    collections?.forEach { c -> Row({ run("Added to “${c.name}”") { server.addToCollection(c.id, listOf(item.id)) } }, c.name) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Row(onClick: () -> Unit, label: String) {
    ListItem(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(label) },
    )
}
