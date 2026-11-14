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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateListOf
import dev.nitrate.core.lists.AddToLists
import dev.nitrate.core.lists.ListChoices
import dev.nitrate.core.lists.ListKind
import dev.nitrate.core.lists.ListResult
import dev.nitrate.core.lists.ListTarget

/**
 * Adds [item] to any number of playlists and collections (collections only for accounts allowed
 * to manage them), new or existing: tick them on two tabs (counts on each, so collections are never
 * hidden behind a long playlist list), review where it will go, then add.
 */
@Composable
fun AddToListDialog(actions: AppActions, item: MediaItem, onDismiss: () -> Unit) {
    val server = actions.server
    val scope = rememberCoroutineScope()
    val account by actions.container.account.collectAsState()
    val canCollect = account?.canManageCollections == true
    val choices by produceState<ListChoices?>(null, canCollect) { value = AddToLists.load(server, canCollect) }
    val selected = remember { mutableStateListOf<ListTarget>() }
    val created = remember { mutableStateListOf<ListTarget>() }
    var tab by remember { mutableStateOf(ListKind.PLAYLIST) }
    var naming by remember { mutableStateOf<ListKind?>(null) }
    var reviewing by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<ListResult>?>(null) }
    fun toggle(t: ListTarget) { if (selected.any { it.key == t.key }) selected.removeAll { it.key == t.key } else selected += t }

    results?.let { done ->
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Added “${item.displayTitle}”") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    done.forEach { r ->
                        Text(
                            (if (r.error == null) "✓  " else "✗  ") + "${r.target.kind.label.replaceFirstChar(Char::uppercase)} “${r.target.name}”" + (r.error?.let { " — couldn't: $it" } ?: ""),
                            color = if (r.error == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        )
        return
    }
    if (reviewing) {
        AlertDialog(
            onDismissRequest = { if (!working) reviewing = false },
            title = { Text("Add to ${AddToLists.summary(selected)}?") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("“${item.displayTitle}” will be added to:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ListKind.entries.forEach { kind ->
                        val these = selected.filter { it.kind == kind }
                        if (these.isNotEmpty()) {
                            Text(kind.plural, style = MaterialTheme.typography.labelLarge, color = accentColor(), modifier = Modifier.padding(top = 8.dp))
                            these.forEach { t -> Text("•  ${t.name}" + if (t.isNew) "  (new)" else "") }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !working, onClick = {
                    working = true
                    scope.launch { results = AddToLists.apply(server, item, selected.toList(), item.mediaKind == MediaKind.AUDIO); working = false }
                }) { Text(if (working) "Adding…" else "Add") }
            },
            dismissButton = { TextButton(enabled = !working, onClick = { reviewing = false }) { Text("Back") } },
        )
        return
    }
    naming?.let { kind ->
        var name by remember(kind) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = null },
            title = { Text("New ${kind.label}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                    Text("It's made when you add, after the review.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    val t = ListTarget(kind, null, name.trim())
                    if (created.none { it.key == t.key }) created += t
                    if (selected.none { it.key == t.key }) selected += t
                    naming = null
                }) { Text("Add to selection") }
            },
            dismissButton = { TextButton(onClick = { naming = null }) { Text("Cancel") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add “${item.displayTitle}” to…") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (canCollect) {
                    androidx.compose.material3.SecondaryTabRow(selectedTabIndex = tab.ordinal, containerColor = Color.Transparent) {
                        ListKind.entries.forEach { kind ->
                            val count = choices?.let { if (kind == ListKind.PLAYLIST) it.playlists.size else it.collections.size }
                            val picked = selected.count { it.kind == kind }
                            androidx.compose.material3.Tab(
                                selected = tab == kind,
                                onClick = { tab = kind },
                                text = { Text(kind.plural + (count?.let { " ($it)" } ?: "") + if (picked > 0) " · $picked ✓" else "") },
                            )
                        }
                    }
                }
                val existing = choices?.let { if (tab == ListKind.PLAYLIST) it.playlists else it.collections }
                androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    item(key = "new") { Row({ naming = tab }, "＋ New ${tab.label}") }
                    val all = created.filter { it.kind == tab } + existing.orEmpty()
                    items(all.size, key = { all[it].key }) { i ->
                        val t = all[i]
                        val on = selected.any { it.key == t.key }
                        ListItem(
                            modifier = Modifier.fillMaxWidth().clickable { toggle(t) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            leadingContent = { androidx.compose.material3.Checkbox(checked = on, onCheckedChange = { toggle(t) }) },
                            headlineContent = { Text(t.name + if (t.isNew) "  (new)" else "") },
                        )
                    }
                    if (existing == null) item(key = "loading") {
                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else if (all.isEmpty()) item(key = "none") {
                        Text("None yet", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = selected.isNotEmpty(), onClick = { reviewing = true }) { Text(if (selected.isEmpty()) "Review" else "Review (${selected.size})") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
