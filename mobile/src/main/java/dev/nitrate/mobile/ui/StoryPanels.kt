package dev.nitrate.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nitrate.core.writing.ScriptEdit
import dev.nitrate.android.writing.StoryContext
import dev.nitrate.android.writing.StoryHistory
import dev.nitrate.core.writing.TextOperation
import dev.nitrate.core.writing.UserRef
import dev.nitrate.core.writing.story.Activity
import dev.nitrate.core.writing.story.EdgeKind
import dev.nitrate.core.writing.story.History
import dev.nitrate.core.writing.story.ListKind
import dev.nitrate.core.writing.story.MapFilter
import dev.nitrate.core.writing.story.Outline
import dev.nitrate.core.writing.story.RecordChange
import dev.nitrate.core.writing.story.RecordTypes
import dev.nitrate.core.writing.story.Ref
import dev.nitrate.core.writing.story.Scene
import dev.nitrate.core.writing.story.SceneStatus
import dev.nitrate.core.writing.story.Story
import dev.nitrate.core.writing.story.StoryColors
import dev.nitrate.core.writing.story.StoryEntry
import dev.nitrate.core.writing.story.StoryGraph
import dev.nitrate.core.writing.story.StoryList
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private val timeFormat: DateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
private fun whenText(ms: Long) = if (ms > 0) timeFormat.format(Date(ms)) else ""

/** "ann · 9:42" for a record. */
@Composable
private fun ChangedBy(record: dev.nitrate.core.writing.story.StoryRecord?) {
    if (record == null) return
    val who = record.updatedByName ?: "someone"
    Text("Changed by $who · ${whenText(record.updatedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ---- Scenes ----

/** Scene cards in script order: move, add, colour, assign; each opens its details. */
@Composable
fun ScenesPanel(ctx: StoryContext, modifier: Modifier = Modifier) {
    var details by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf<String?>(null) }
    var addingAt by remember { mutableStateOf(false) }
    val unmarked = ctx.scenes.count { it.id == null }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${ctx.scenes.size} scenes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { adding = null; addingAt = true }) { Text("Add scene") }
            }
            if (unmarked > 0) Text("Getting $unmarked new scene(s) ready…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
        }
        items(ctx.scenes, key = { it.id ?: "i${it.index}" }) { scene ->
            SceneCard(ctx, scene, onDetails = { scene.id?.let { details = it } }, onAddAfter = { adding = scene.id; addingAt = true })
        }
    }
    if (addingAt) AddSceneDialog(onAdd = { heading -> ctx.applyText(Outline.add(ctx.text, heading, adding)); addingAt = false }, onDismiss = { addingAt = false })
    details?.let { id -> SceneSheet(ctx, id) { details = null } }
}

@Composable
private fun SceneCard(ctx: StoryContext, scene: Scene, onDetails: () -> Unit, onAddAfter: () -> Unit) {
    val meta = scene.id?.let { ctx.story.sceneMeta(it) }
    val comments = scene.id?.let { id -> ctx.story.comments("scene:$id").count { !it.resolved } } ?: 0
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
            .clickable(onClick = onDetails),
    ) {
        Box(Modifier.width(6.dp).height(84.dp).background(Color(StoryColors.SCENE[meta?.color ?: 0]).takeIf { (meta?.color ?: 0) > 0 } ?: Color.Transparent, RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)))
        Column(Modifier.weight(1f).padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("${scene.index + 1}. ${scene.heading}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            meta?.synopsis?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            val facts = listOfNotNull(
                meta?.status?.label,
                meta?.assigneeName?.let { "→ $it" },
                scene.characters.takeIf { it.isNotEmpty() }?.joinToString(),
                "$comments comment${if (comments == 1) "" else "s"}".takeIf { comments > 0 },
            )
            if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Scene actions") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                val id = scene.id
                fun move(to: Int) { if (id != null) Outline.move(ctx.text, id, to)?.let(ctx.applyText); menu = false }
                DropdownMenuItem(text = { Text("Show in script") }, onClick = { menu = false; ctx.openInScript(scene) })
                DropdownMenuItem(text = { Text("Details, comments, history") }, onClick = { menu = false; onDetails() })
                if (id != null) {
                    DropdownMenuItem(text = { Text("Move up") }, enabled = scene.index > 0, onClick = { move(scene.index - 1) })
                    DropdownMenuItem(text = { Text("Move down") }, enabled = scene.index < ctx.scenes.size - 1, onClick = { move(scene.index + 2) })
                    DropdownMenuItem(text = { Text("Move to the start") }, enabled = scene.index > 0, onClick = { move(0) })
                    DropdownMenuItem(text = { Text("Move to the end") }, enabled = scene.index < ctx.scenes.size - 1, onClick = { move(ctx.scenes.size) })
                    DropdownMenuItem(text = { Text("Add a scene after") }, onClick = { menu = false; onAddAfter() })
                }
            }
        }
    }
}

@Composable
private fun AddSceneDialog(onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var heading by remember { mutableStateOf("INT. ") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New scene") },
        text = { OutlinedTextField(heading, { heading = it }, label = { Text("Scene heading") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onAdd(heading) }, enabled = heading.trim().length > 4) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A scene's details: summary, colour, who's writing it and how far along, comments and history. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SceneSheet(ctx: StoryContext, sceneId: String, onDismiss: () -> Unit) {
    val scene = ctx.scenes.firstOrNull { it.id == sceneId }
    val meta = ctx.story.sceneMeta(sceneId)
    var synopsis by remember(sceneId) { mutableStateOf(meta.synopsis) }
    var assigning by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(scene?.heading ?: "Scene", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            ChangedBy(meta.record)
            OutlinedTextField(synopsis, { synopsis = it }, label = { Text("What happens") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            if (synopsis != meta.synopsis) TextButton(onClick = { ctx.changeStory(listOf(Story.sceneMeta(sceneId, synopsis = synopsis))) }) { Text("Save summary") }
            Text("Colour", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StoryColors.SCENE.forEachIndexed { i, c ->
                    Box(
                        Modifier.size(30.dp).background(if (i == 0) MaterialTheme.colorScheme.surfaceVariant else Color(c), CircleShape)
                            .border(if (meta.color == i) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            .clickable { ctx.changeStory(listOf(Story.sceneMeta(sceneId, color = i))) },
                    )
                }
            }
            Text("Progress", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(meta.status == null, { ctx.changeStory(listOf(Story.sceneMeta(sceneId, clearStatus = true))) }, label = { Text("Not set") })
                SceneStatus.entries.forEach { st ->
                    FilterChip(meta.status == st, { ctx.changeStory(listOf(Story.sceneMeta(sceneId, status = st))) }, label = { Text(st.label) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Writing it: ${meta.assigneeName ?: "no one"}", modifier = Modifier.weight(1f))
                Box {
                    TextButton(onClick = { assigning = true }) { Text("Assign") }
                    DropdownMenu(expanded = assigning, onDismissRequest = { assigning = false }) {
                        DropdownMenuItem(text = { Text("No one") }, onClick = { assigning = false; ctx.changeStory(listOf(Story.sceneMeta(sceneId, assignee = "" to ""))) })
                        ctx.people.forEach { p ->
                            DropdownMenuItem(text = { Text(p.name) }, onClick = { assigning = false; ctx.changeStory(listOf(Story.sceneMeta(sceneId, assignee = p.id to p.name))) })
                        }
                    }
                }
            }
            scene?.let { s ->
                if (s.characters.isNotEmpty()) Text("Speaking: " + s.characters.joinToString(), style = MaterialTheme.typography.bodySmall)
                s.location?.let { Text("Where: $it", style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { onDismiss(); ctx.openInScript(s) }) { Text("Show in the script") }
            }
            HorizontalDivider()
            CommentThread(ctx, "scene:$sceneId")
            ctx.history?.let { h ->
                HorizontalDivider()
                RecordHistory(ctx, h, Story.sceneRecordId(sceneId), RecordTypes.SCENE, textSceneId = sceneId)
            }
        }
    }
}

// ---- Lists ----

/** Characters, locations and the writers' own lists; detected names show until someone edits them. */
@Composable
fun ListsPanel(ctx: StoryContext, modifier: Modifier = Modifier) {
    val lists = ctx.story.lists()
    var listId by remember { mutableStateOf(lists.firstOrNull()?.id) }
    var open by remember { mutableStateOf<StoryEntry?>(null) }
    var newList by remember { mutableStateOf(false) }
    var newEntry by remember { mutableStateOf(false) }
    val list = lists.firstOrNull { it.id == listId } ?: lists.firstOrNull()
    Column(modifier) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            lists.forEach { l -> FilterChip(l.id == list?.id, { listId = l.id }, label = { Text(l.name) }) }
            AssistChip(onClick = { newList = true }, label = { Text("+ List") })
        }
        if (list != null) {
            val items = ctx.story.listItems(list, ctx.scenes)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${items.size} in ${list.name}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = { newEntry = true }) { Text("Add") }
                    }
                }
                items(items, key = { it.id }) { e ->
                    val scenes = ctx.story.scenesOf(e, list, ctx.scenes).size
                    Column(Modifier.fillMaxWidth().clickable { open = e }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(e.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        val note = when {
                            e.notes.isNotBlank() -> e.notes
                            e.record == null -> "From the script"
                            else -> ""
                        }
                        val tail = if (scenes > 0) "$scenes scene${if (scenes == 1) "" else "s"}" else ""
                        Text(listOf(note, tail).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    if (newList) NameDialog("New list", "Name (Props, Themes…)", onDone = { name ->
        ctx.changeStory(listOf(Story.setList("l-" + newId(), name, ListKind.CUSTOM, 10 + lists.size)))
        newList = false
    }) { newList = false }
    if (newEntry && list != null) NameDialog("Add to ${list.name}", "Name", onDone = { name ->
        val id = if (list.kind == ListKind.CUSTOM) "e-" + newId() else Story.detectedId(list.id, name)
        ctx.changeStory(listOf(Story.setEntry(id, list.id, name.trim(), "")))
        newEntry = false
    }) { newEntry = false }
    open?.let { e -> EntrySheet(ctx, e, list) { open = null } }
}

@Composable
private fun NameDialog(title: String, label: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onDone(name.trim()) }, enabled = name.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** An entry: its name and notes (a bio, a description), where it appears, its links, comments and history. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntrySheet(ctx: StoryContext, entry: StoryEntry, list: StoryList?, onDismiss: () -> Unit) {
    val live = ctx.story.entry(entry.id) ?: entry
    var name by remember(entry.id) { mutableStateOf(live.name) }
    var notes by remember(entry.id) { mutableStateOf(live.notes) }
    var linking by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val ref = Ref.entry(entry.id)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(live.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (live.record == null) Text("Found in the script. Write about them and they're kept.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChangedBy(live.record)
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(notes, { notes = it }, label = { Text(if (list?.kind == ListKind.CHARACTERS) "Bio" else "Notes") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
            if (name != live.name || notes != live.notes) {
                TextButton(onClick = { ctx.changeStory(listOf(Story.setEntry(entry.id, entry.listId, name.trim(), notes))) }, enabled = name.isNotBlank()) { Text("Save") }
            }
            val scenes = ctx.story.scenesOf(live, list, ctx.scenes)
            if (scenes.isNotEmpty()) {
                Text("In the script", style = MaterialTheme.typography.labelLarge)
                scenes.forEach { s -> Text("${s.index + 1}. ${s.heading}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.clickable { onDismiss(); ctx.openInScript(s) }.padding(vertical = 2.dp)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Links", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { linking = true }) { Text("Add link") }
            }
            ctx.story.links().filter { it.from == ref || it.to == ref }.forEach { l ->
                val other = if (l.from == ref) l.to else l.from
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${l.label.ifBlank { "linked to" }} → ${nameOf(ctx, other)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { ctx.changeStory(listOf(Story.deleteRecord(l.record))) }) { Text("Remove") }
                }
            }
            HorizontalDivider()
            CommentThread(ctx, "entry:${entry.id}")
            ctx.history?.let { h ->
                HorizontalDivider()
                RecordHistory(ctx, h, entry.id, RecordTypes.ENTRY)
            }
            if (live.record != null) {
                TextButton(onClick = {
                    val record = live.record ?: return@TextButton
                    if (confirmDelete) { ctx.changeStory(listOf(Story.deleteRecord(record))); onDismiss() } else confirmDelete = true
                }) { Text(if (confirmDelete) "Delete ${live.name}? Tap again" else "Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (linking) LinkDialog(ctx, ref, onDismiss = { linking = false })
}

private fun nameOf(ctx: StoryContext, ref: Ref): String = when (ref.kind) {
    Ref.Kind.SCENE -> ctx.scenes.firstOrNull { it.id == ref.id }?.heading ?: "a scene"
    Ref.Kind.ENTRY -> ctx.story.entry(ref.id)?.name ?: ctx.story.lists().flatMap { ctx.story.listItems(it, ctx.scenes) }.firstOrNull { it.id == ref.id }?.name ?: "an entry"
}

/** Links [from] to another entry or a scene, with a label ("sister of", "owns"). */
@Composable
private fun LinkDialog(ctx: StoryContext, from: Ref, to: Ref? = null, onDismiss: () -> Unit) {
    var label by remember { mutableStateOf("") }
    var target by remember { mutableStateOf(to) }
    var query by remember { mutableStateOf("") }
    val options = remember(ctx.story, ctx.scenes) {
        ctx.story.lists().flatMap { l -> ctx.story.listItems(l, ctx.scenes).map { Ref.entry(it.id) to "${it.name} (${l.name})" } } +
            ctx.scenes.filter { it.id != null }.map { Ref.scene(it.id!!) to "${it.index + 1}. ${it.heading}" }
    }.filter { it.first != from }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link ${nameOf(ctx, from)} to…") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it }, label = { Text("How (sister of, owns, meets…)") }, singleLine = true)
                if (to == null) {
                    OutlinedTextField(query, { query = it }, label = { Text("Find") }, singleLine = true)
                    LazyColumn(Modifier.heightIn(max = 260.dp)) {
                        items(options.filter { query.isBlank() || it.second.contains(query, ignoreCase = true) }, key = { it.first.toString() }) { (r, name) ->
                            Text(
                                name,
                                color = if (r == target) accentColor() else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (r == target) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.fillMaxWidth().clickable { target = r }.padding(vertical = 8.dp),
                            )
                        }
                    }
                } else Text("→ ${nameOf(ctx, to)}")
            }
        },
        confirmButton = {
            TextButton(onClick = { target?.let { ctx.changeStory(listOf(Story.link("k-" + newId(), from, it, label.trim()))) }; onDismiss() }, enabled = target != null) { Text("Link") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---- Comments ----

/** Comments on a scene or entry: replies, resolving, and adding one. */
@Composable
fun CommentThread(ctx: StoryContext, on: String, quote: String? = null) {
    val all = ctx.story.comments(on)
    var draft by remember(on) { mutableStateOf("") }
    var replyTo by remember(on) { mutableStateOf<String?>(null) }
    var showResolved by remember(on) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val top = all.filter { it.parentId == null }
        val resolved = top.count { it.resolved }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Comments", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            if (resolved > 0) TextButton(onClick = { showResolved = !showResolved }) { Text(if (showResolved) "Hide resolved" else "$resolved resolved") }
        }
        top.filter { showResolved || !it.resolved }.forEach { c ->
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(10.dp)).padding(10.dp)) {
                Text("${c.authorName ?: "someone"} · ${whenText(c.createdAt)}", style = MaterialTheme.typography.labelSmall, color = accentColor())
                c.quote?.let { Text("“$it”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                Text(c.text, style = MaterialTheme.typography.bodyMedium)
                all.filter { it.parentId == c.id }.forEach { r ->
                    Column(Modifier.padding(start = 14.dp, top = 6.dp)) {
                        Text("${r.authorName ?: "someone"} · ${whenText(r.createdAt)}", style = MaterialTheme.typography.labelSmall, color = accentColor())
                        Text(r.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Row {
                    TextButton(onClick = { replyTo = c.id }) { Text("Reply") }
                    TextButton(onClick = { ctx.changeStory(listOf(Story.resolve(c.id, !c.resolved))) }) { Text(if (c.resolved) "Reopen" else "Resolve") }
                }
            }
        }
        val replying = replyTo?.let { id -> top.firstOrNull { it.id == id } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(draft, { draft = it }, label = { Text(if (replying != null) "Reply to ${replying.authorName}" else "Add a comment") }, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                ctx.changeStory(listOf(Story.comment("c-" + newId(), on, draft.trim(), ctx.selfName, quote = quote.takeIf { replying == null }, parentId = replying?.id)))
                draft = ""
                replyTo = null
            }, enabled = draft.isNotBlank()) { Text("Send") }
        }
    }
}

// ---- History ----

/** Every change to one scene's details or one entry: who, when, what, and putting it back. */
@Composable
private fun RecordHistory(ctx: StoryContext, history: StoryHistory, recordId: String, type: String, textSceneId: String? = null) {
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide history" else "History") }
    if (!expanded) return
    val log by produceState<List<ScriptEdit>?>(null, recordId) { value = runCatching { history.log() }.getOrNull() ?: emptyList() }
    val entries = log?.let { l ->
        History.of(recordId, l).map { (e, c) -> Triple(e, (c.set.keys.joinToString().ifEmpty { if (c.delete) "deleted" else "changed" }), true) } +
            (textSceneId?.let { sid -> l.filter { it.op != null && it.sceneId == sid }.reversed().take(30).map { Triple(it, "wrote in the scene", false) } } ?: emptyList())
    }?.sortedByDescending { it.first.version }
    when {
        log == null -> Text("Loading…", style = MaterialTheme.typography.bodySmall)
        entries.isNullOrEmpty() -> Text("No changes yet.", style = MaterialTheme.typography.bodySmall)
        else -> entries.take(40).forEach { (e, what, restorable) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${e.userName} $what", style = MaterialTheme.typography.bodySmall)
                    Text(whenText(e.atMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (restorable) TextButton(onClick = {
                    scope.launch {
                        // The record as it was right after this change.
                        val then = runCatching { history.recordAt(e.version, recordId) }.getOrNull()
                        ctx.changeStory(listOf(History.restore(ctx.story.records[recordId], then, recordId, type)))
                        message = "Put back as it was at ${whenText(e.atMs)}"
                    }
                }) { Text("Restore") }
            }
        }
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = accentColor()) }
}

/** Who did what and when, across the script and its story; versions can be looked at and restored. */
@Composable
fun ActivityPanel(ctx: StoryContext, modifier: Modifier = Modifier) {
    val history = ctx.history ?: run {
        Text("Share this script to keep a history of who changed what.", modifier = modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    var refresh by remember { mutableStateOf(0) }
    val log by produceState<List<ScriptEdit>?>(null, refresh) { value = runCatching { history.log() }.getOrNull() ?: emptyList() }
    var viewing by remember { mutableStateOf<Activity?>(null) }
    val activities = remember(log, ctx.story, ctx.scenes) { log?.let { History.activities(it, ctx.story, ctx.scenes) } }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Activity", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { refresh++ }) { Text("Refresh") }
            }
        }
        if (activities == null) item { Text("Loading…", modifier = Modifier.padding(16.dp)) }
        else items(activities, key = { "${it.fromVersion}-${it.userId}-${it.kind}" }) { a ->
            Row(Modifier.fillMaxWidth().clickable { viewing = a }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(Color(StoryColors.writer(a.userId)), CircleShape))
                Column(Modifier.padding(start = 10.dp)) {
                    Text("${a.userName} ${a.kind.verb} ${a.targetName}" + if (a.count > 1) " (${a.count} changes)" else "", style = MaterialTheme.typography.bodyMedium)
                    Text(whenText(a.untilMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    viewing?.let { a -> VersionDialog(ctx, history, a) { viewing = null } }
}

/** The script as it was after an activity, with the option to put the whole script back that way. */
@Composable
private fun VersionDialog(ctx: StoryContext, history: StoryHistory, activity: Activity, onDismiss: () -> Unit) {
    val text by produceState<String?>(null, activity.toVersion) { value = runCatching { history.textAt(activity.toVersion) }.getOrNull() }
    var confirm by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("As it was at ${whenText(activity.untilMs)}") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("After ${activity.userName} ${activity.kind.verb} ${activity.targetName}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(text?.let(Outline::stripMarkers) ?: "Loading…", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val old = text ?: return@TextButton
                if (!confirm) { confirm = true; return@TextButton }
                // Restoring is a new change on top (nothing in the history is lost).
                TextOperation.diff(ctx.text, old).takeIf { !it.isNoop }?.let(ctx.applyText)
                onDismiss()
            }, enabled = text != null) { Text(if (confirm) "Tap again to restore the script" else "Restore this version") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

// ---- Map ----

/**
 * Scenes and list entries joined by coloured lines: who speaks in which scene, where scenes are
 * set, and the writers' own links. Filter what shows; tap to focus on one; "Link" joins two.
 */
@Composable
fun MapPanel(ctx: StoryContext, modifier: Modifier = Modifier) {
    val lists = ctx.story.lists()
    var filter by remember { mutableStateOf(MapFilter()) }
    var selected by remember { mutableStateOf<Ref?>(null) }
    var linkFrom by remember { mutableStateOf<Ref?>(null) }
    var linkTo by remember { mutableStateOf<Pair<Ref, Ref>?>(null) }
    var scale by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val full = remember(ctx.story, ctx.scenes) { StoryGraph.build(ctx.story, ctx.scenes) }
    val shown = remember(full, filter) { StoryGraph.filter(full, filter) }
    val positions = remember(shown) { StoryGraph.layout(shown) }
    val listColor = lists.mapIndexed { i, l -> l.id to Color(StoryColors.list(i)) }.toMap()
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface)
    Column(modifier) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EdgeKind.entries.forEach { k ->
                FilterChip(
                    k in filter.edgeKinds,
                    { filter = filter.copy(edgeKinds = if (k in filter.edgeKinds) filter.edgeKinds - k else filter.edgeKinds + k) },
                    label = { Text(k.label) },
                    leadingIcon = { Box(Modifier.size(10.dp).background(Color(StoryColors.edge(k)), CircleShape)) },
                )
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(filter.showScenes, { filter = filter.copy(showScenes = !filter.showScenes) }, label = { Text("Scenes") })
            lists.forEach { l ->
                val on = filter.lists?.contains(l.id) ?: true
                FilterChip(on, {
                    val now = filter.lists ?: lists.map { it.id }.toSet()
                    filter = filter.copy(lists = if (on) now - l.id else now + l.id)
                }, label = { Text(l.name) }, leadingIcon = { Box(Modifier.size(10.dp).background(listColor[l.id] ?: Color.Gray, CircleShape)) })
            }
            FilterChip(filter.hideLoose, { filter = filter.copy(hideLoose = !filter.hideLoose) }, label = { Text("Only joined") })
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(8.dp).background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp))
                .pointerInput(Unit) {
                    detectTransformGestures { _, p, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.5f, 4f)
                        pan += p
                    }
                },
        ) {
            Canvas(
                Modifier.fillMaxSize().pointerInput(shown, positions, scale, pan, linkFrom) {
                    detectTapGestures { tap ->
                        val hit = positions.minByOrNull { (_, p) ->
                            val x = p.first * size.width * scale + pan.x
                            val y = p.second * size.height * scale + pan.y
                            (x - tap.x) * (x - tap.x) + (y - tap.y) * (y - tap.y)
                        }?.takeIf { (_, p) ->
                            val x = p.first * size.width * scale + pan.x
                            val y = p.second * size.height * scale + pan.y
                            (x - tap.x) * (x - tap.x) + (y - tap.y) * (y - tap.y) < 40f * 40f
                        }?.key
                        val from = linkFrom
                        when {
                            from != null && hit != null && hit != from -> { linkTo = from to hit; linkFrom = null }
                            else -> selected = hit
                        }
                    }
                },
            ) {
                fun at(r: Ref) = positions[r]?.let { Offset(it.first * size.width * scale + pan.x, it.second * size.height * scale + pan.y) }
                shown.edges.forEach { e ->
                    val a = at(e.from) ?: return@forEach
                    val b = at(e.to) ?: return@forEach
                    val strong = selected == null || e.from == selected || e.to == selected
                    drawLine(Color(StoryColors.edge(e.kind)).copy(alpha = if (strong) 0.85f else 0.15f), a, b, strokeWidth = if (e.kind == EdgeKind.RELATES || e.kind == EdgeKind.LINKED) 3f else 1.6f)
                }
                shown.nodes.forEach { n ->
                    val p = at(n.ref) ?: return@forEach
                    val color = if (n.listId == null) Color(StoryColors.SCENE_NODE) else listColor[n.listId] ?: Color.Gray
                    val radius = if (n.listId == null) 7f else 10f
                    drawCircle(color, radius, p)
                    if (n.ref == selected || n.ref == linkFrom) drawCircle(Color.White, radius + 5f, p, style = Stroke(3f))
                    val label = if (n.listId == null) "${(n.sceneIndex ?: 0) + 1}" else n.label.take(18)
                    // One line, on whichever side of the dot has room.
                    val measured = measurer.measure(label, labelStyle, softWrap = false, maxLines = 1)
                    val x = if (p.x + radius + 3f + measured.size.width > size.width) p.x - radius - 3f - measured.size.width else p.x + radius + 3f
                    drawText(measured, topLeft = Offset(x, p.y - measured.size.height / 2f))
                }
            }
            if (shown.nodes.isEmpty()) Text("Nothing to show with these filters.", modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val sel = selected
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    linkFrom != null -> "Tap what to link ${nameOf(ctx, linkFrom!!)} to"
                    sel != null -> nameOf(ctx, sel)
                    else -> "Tap a dot to see what it's joined to; pinch to zoom"
                },
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (sel != null && linkFrom == null) {
                TextButton(onClick = { filter = filter.copy(focus = if (filter.focus == sel) null else sel) }) { Text(if (filter.focus == sel) "Show all" else "Focus") }
                TextButton(onClick = { linkFrom = sel }) { Text("Link") }
            }
            if (linkFrom != null) TextButton(onClick = { linkFrom = null }) { Text("Cancel") }
        }
    }
    linkTo?.let { (a, b) -> LinkDialog(ctx, a, b) { linkTo = null } }
}
