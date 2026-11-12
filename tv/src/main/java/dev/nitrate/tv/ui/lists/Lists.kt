package dev.nitrate.tv.ui.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.MediaKind
import dev.nitrate.tv.ui.components.TvTextField
import dev.nitrate.tv.ui.rows.ignoreHeldSelect
import kotlinx.coroutines.launch

/** A dialog panel with the TV look. */
@Composable
fun TvDialog(onDismiss: () -> Unit, width: Int = 620, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            // Some open from a long press: the held OK mustn't click the first choice.
            Modifier.ignoreHeldSelect().width(width.dp).heightIn(max = 620.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { content() }
    }
}

/** Adds [item] to a playlist or collection, or a new one (collections when [canCollect]). */
@Composable
fun AddToListDialog(server: MediaServer, item: MediaItem, canCollect: Boolean, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val playlists by produceState<List<MediaItem>?>(null) { value = runCatching { server.playlists() }.getOrDefault(emptyList()) }
    val collections by produceState<List<MediaItem>?>(null, canCollect) { value = if (canCollect) runCatching { server.collections() }.getOrDefault(emptyList()) else emptyList() }
    var creating by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    fun run(done: String, block: suspend () -> Unit) {
        scope.launch { status = runCatching { block() }.fold({ done }, { "Couldn't do that: ${it.message}" }) }
    }
    TvDialog(onDismiss) {
        Text("Add “${item.displayTitle}” to…", style = MaterialTheme.typography.headlineSmall)
        status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        val kind = creating
        if (kind != null) {
            TvTextField(name, { name = it }, placeholder = if (kind == "playlist") "Playlist name" else "Collection name", modifier = Modifier.focusRequester(first))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = name.isNotBlank(), onClick = {
                    creating = null
                    run("Added to new $kind “$name”") {
                        if (kind == "playlist") server.createPlaylist(name.trim(), listOf(item.id), item.mediaKind == MediaKind.AUDIO) else server.createCollection(name.trim(), listOf(item.id))
                    }
                }) { Text("Create") }
                OutlinedButton(onClick = { creating = null }) { Text("Cancel") }
            }
        } else {
            Text("Playlists", style = MaterialTheme.typography.titleSmall)
            ListItem(selected = false, onClick = { creating = "playlist"; name = "" }, headlineContent = { Text("＋ New playlist") }, modifier = Modifier.focusRequester(first))
            playlists?.forEach { p -> ListItem(selected = false, onClick = { run("Added to “${p.name}”") { server.addToPlaylist(p.id, listOf(item.id)) } }, headlineContent = { Text(p.name) }) }
            if (canCollect) {
                Text("Collections", style = MaterialTheme.typography.titleSmall)
                ListItem(selected = false, onClick = { creating = "collection"; name = "" }, headlineContent = { Text("＋ New collection") })
                collections?.forEach { c -> ListItem(selected = false, onClick = { run("Added to “${c.name}”") { server.addToCollection(c.id, listOf(item.id)) } }, headlineContent = { Text(c.name) }) }
            }
            OutlinedButton(onClick = onDismiss) { Text("Done") }
        }
        LaunchedEffect(kind) { runCatching { first.requestFocus() } }
    }
}

/** What can be done with one playlist entry while editing. */
@Composable
fun PlaylistEntryDialog(
    item: MediaItem,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    TvDialog(onDismiss, width = 520) {
        Text(item.displayTitle, style = MaterialTheme.typography.headlineSmall)
        item.episodeLabel?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        ListItem(selected = false, enabled = canMoveUp, onClick = { onMove(-1) }, headlineContent = { Text("Move up") }, modifier = Modifier.focusRequester(first))
        ListItem(selected = false, enabled = canMoveDown, onClick = { onMove(1) }, headlineContent = { Text("Move down") })
        ListItem(selected = false, onClick = onRemove, headlineContent = { Text("Remove from playlist") })
        ListItem(selected = false, onClick = onOpen, headlineContent = { Text("Open details") })
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    }
}
