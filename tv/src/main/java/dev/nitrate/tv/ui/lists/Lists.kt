package dev.nitrate.tv.ui.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
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

/**
 * Adds [item] to any number of playlists and collections (collections when [canCollect]), new or
 * existing: tick them in two side-by-side columns (both always in view; "Loading…" until each list
 * arrives, so nothing moves under the cursor), review where it will go, then add.
 */
@Composable
fun AddToListDialog(server: MediaServer, item: MediaItem, canCollect: Boolean, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val choices by rememberListChoices(server, canCollect)
    val selected = remember { androidx.compose.runtime.mutableStateListOf<dev.nitrate.core.lists.ListTarget>() }
    val created = remember { androidx.compose.runtime.mutableStateListOf<dev.nitrate.core.lists.ListTarget>() }
    var naming by remember { mutableStateOf<dev.nitrate.core.lists.ListKind?>(null) }
    var reviewing by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<dev.nitrate.core.lists.ListResult>?>(null) }
    var working by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }

    fun toggle(t: dev.nitrate.core.lists.ListTarget) { if (selected.any { it.key == t.key }) selected.removeAll { it.key == t.key } else selected += t }

    Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.ignoreHeldSelect().width(if (canCollect) 880.dp else 560.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val done = results
            when {
                done != null -> {
                    Text("Added “${item.displayTitle}”", style = MaterialTheme.typography.headlineSmall)
                    Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        done.forEach { r ->
                            Text(
                                (if (r.error == null) "✓  " else "✗  ") + "${r.target.kind.label.replaceFirstChar(Char::uppercase)} “${r.target.name}”" + (r.error?.let { " — couldn't: $it" } ?: ""),
                                color = if (r.error == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Button(onClick = onDismiss, modifier = Modifier.focusRequester(first)) { Text("Done") }
                    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
                }
                reviewing -> {
                    Text("Add “${item.displayTitle}” to ${dev.nitrate.core.lists.AddToLists.summary(selected)}?", style = MaterialTheme.typography.headlineSmall)
                    Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        dev.nitrate.core.lists.ListKind.entries.forEach { kind ->
                            val these = selected.filter { it.kind == kind }
                            if (these.isNotEmpty()) {
                                Text(kind.plural, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                                these.forEach { t -> Text("•  ${t.name}" + if (t.isNew) "  (new)" else "") }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(enabled = !working, onClick = {
                            working = true
                            scope.launch { results = dev.nitrate.core.lists.AddToLists.apply(server, item, selected.toList(), item.mediaKind == MediaKind.AUDIO); working = false }
                        }, modifier = Modifier.focusRequester(first)) { Text(if (working) "Adding…" else "Add") }
                        OutlinedButton(enabled = !working, onClick = { reviewing = false }) { Text("Back") }
                    }
                    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
                }
                naming != null -> {
                    val kind = naming!!
                    var name by remember(kind) { mutableStateOf("") }
                    Text("New ${kind.label}", style = MaterialTheme.typography.headlineSmall)
                    TvTextField(name, { name = it }, placeholder = "${kind.label.replaceFirstChar(Char::uppercase)} name", modifier = Modifier.focusRequester(first))
                    Text("It's made when you add, after the review.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(enabled = name.isNotBlank(), onClick = {
                            val t = dev.nitrate.core.lists.ListTarget(kind, null, name.trim())
                            if (created.none { it.key == t.key }) created += t
                            if (selected.none { it.key == t.key }) selected += t
                            naming = null
                        }) { Text("Add to selection") }
                        OutlinedButton(onClick = { naming = null }) { Text("Cancel") }
                    }
                    LaunchedEffect(kind) { runCatching { first.requestFocus() } }
                }
                else -> {
                    Text("Add “${item.displayTitle}” to…", style = MaterialTheme.typography.headlineSmall)
                    Text("Tick as many as you like, then Review.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.height(360.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        val kinds = if (canCollect) dev.nitrate.core.lists.ListKind.entries else listOf(dev.nitrate.core.lists.ListKind.PLAYLIST)
                        kinds.forEachIndexed { i, kind ->
                            val existing = choices?.let { if (kind == dev.nitrate.core.lists.ListKind.PLAYLIST) it.playlists else it.collections }
                            Column(Modifier.weight(1f)) {
                                Text(kind.plural + (existing?.let { " (${it.size})" } ?: ""), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                androidx.compose.foundation.lazy.LazyColumn(Modifier.padding(top = 6.dp)) {
                                    item(key = "new") {
                                        ListItem(
                                            selected = false,
                                            onClick = { naming = kind },
                                            headlineContent = { Text("＋ New ${kind.label}") },
                                            modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                                        )
                                    }
                                    val all = created.filter { it.kind == kind } + existing.orEmpty()
                                    items(all.size, key = { all[it].key }) { idx ->
                                        val t = all[idx]
                                        val on = selected.any { it.key == t.key }
                                        ListItem(
                                            selected = on,
                                            onClick = { toggle(t) },
                                            leadingContent = { Text(if (on) "☑" else "☐", style = MaterialTheme.typography.titleMedium) },
                                            headlineContent = { Text(t.name + if (t.isNew) "  (new)" else "") },
                                        )
                                    }
                                    if (existing == null) item(key = "loading") {
                                        Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                                    } else if (existing.isEmpty() && created.none { it.kind == kind }) item(key = "none") {
                                        Text("None yet", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                                    }
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(enabled = selected.isNotEmpty(), onClick = { reviewing = true }) {
                            Text(if (selected.isEmpty()) "Review" else "Review (${selected.size})")
                        }
                        OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                    }
                    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
                }
            }
        }
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
