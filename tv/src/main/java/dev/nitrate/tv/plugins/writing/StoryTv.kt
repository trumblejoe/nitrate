package dev.nitrate.tv.plugins.writing

import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.FilterChip
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.nitrate.android.writing.StoryContext
import dev.nitrate.core.writing.ScriptEdit
import dev.nitrate.core.writing.TextOperation
import dev.nitrate.core.writing.story.Activity
import dev.nitrate.core.writing.story.EdgeKind
import dev.nitrate.core.writing.story.History
import dev.nitrate.core.writing.story.ListKind
import dev.nitrate.core.writing.story.MapFilter
import dev.nitrate.core.writing.story.Outline
import dev.nitrate.core.writing.story.RecordTypes
import dev.nitrate.core.writing.story.Ref
import dev.nitrate.core.writing.story.SceneStatus
import dev.nitrate.core.writing.story.Story
import dev.nitrate.core.writing.story.StoryColors
import dev.nitrate.core.writing.story.StoryEntry
import dev.nitrate.core.writing.story.StoryGraph
import dev.nitrate.tv.ui.components.TvTextField
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.UUID

/** Which story screen the TV editor shows beside (or instead of) the script. */
internal enum class StoryTvTab { SCENES, LISTS, MAP, ACTIVITY }

private val timeFormat: DateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
private fun whenText(ms: Long) = if (ms > 0) timeFormat.format(Date(ms)) else ""
private fun newId() = UUID.randomUUID().toString().take(12)

/** What's open inside a story screen: a list, or one scene, entry or version. */
private sealed interface Open {
    data object Nothing : Open
    data class Scene(val id: String) : Open
    data class Entry(val id: String, val listId: String) : Open
    data class Version(val activity: Activity) : Open
}

/**
 * The script's story on the TV: scene cards, lists and entries, the map (to look at and filter;
 * links are made from an entry) and the activity. Everything is reachable with the D-pad; Back
 * from a scene, entry or version returns to its list.
 */
@Composable
internal fun StoryTvPanel(ctx: StoryContext, tab: StoryTvTab, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var open by remember(tab) { mutableStateOf<Open>(Open.Nothing) }
    androidx.activity.compose.BackHandler(enabled = open != Open.Nothing) { open = Open.Nothing }
    Box(modifier) {
        when (val o = open) {
            is Open.Scene -> SceneDetail(ctx, o.id) { open = Open.Nothing }
            is Open.Entry -> EntryDetail(ctx, o.id, o.listId) { open = Open.Nothing }
            is Open.Version -> VersionView(ctx, o.activity) { open = Open.Nothing }
            Open.Nothing -> when (tab) {
                StoryTvTab.SCENES -> ScenesTv(ctx) { open = Open.Scene(it) }
                StoryTvTab.LISTS -> ListsTv(ctx) { e -> open = Open.Entry(e.id, e.listId) }
                StoryTvTab.MAP -> MapTv(ctx) { ref ->
                    open = if (ref.kind == Ref.Kind.SCENE) Open.Scene(ref.id)
                    else Open.Entry(ref.id, ctx.story.entry(ref.id)?.listId ?: ctx.story.lists().firstOrNull { l -> ctx.story.listItems(l, ctx.scenes).any { it.id == ref.id } }?.id.orEmpty())
                }
                StoryTvTab.ACTIVITY -> ActivityTv(ctx) { open = Open.Version(it) }
            }
        }
    }
}

// ---- Scenes ----

@Composable
private fun ScenesTv(ctx: StoryContext, onOpen: (String) -> Unit) {
    val first = remember { FocusRequester() }
    var adding by remember { mutableStateOf(false) }
    var heading by remember { mutableStateOf("INT. ") }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${ctx.scenes.size} scenes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { adding = !adding }) { Text(if (adding) "Cancel" else "Add scene") }
            }
            if (adding) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvTextField(heading, { heading = it }, "Scene heading", modifier = Modifier.weight(1f))
                Button(onClick = { ctx.applyText(Outline.add(ctx.text, heading, null)); adding = false; heading = "INT. " }, enabled = heading.trim().length > 4) { Text("Add") }
            }
        }
        items(ctx.scenes, key = { it.id ?: "i${it.index}" }) { scene ->
            val meta = scene.id?.let { ctx.story.sceneMeta(it) }
            val comments = scene.id?.let { id -> ctx.story.comments("scene:$id").count { !it.resolved } } ?: 0
            ListItem(
                selected = false,
                onClick = { scene.id?.let(onOpen) },
                leadingContent = {
                    Box(Modifier.size(width = 6.dp, height = 36.dp).background(if ((meta?.color ?: 0) > 0) Color(StoryColors.SCENE[meta!!.color]) else Color.Transparent, RoundedCornerShape(3.dp)))
                },
                headlineContent = { Text("${scene.index + 1}. ${scene.heading}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    val facts = listOfNotNull(
                        meta?.synopsis?.takeIf { it.isNotBlank() }, meta?.status?.label, meta?.assigneeName?.let { "→ $it" },
                        scene.characters.takeIf { it.isNotEmpty() }?.joinToString(), "$comments comment${if (comments == 1) "" else "s"}".takeIf { comments > 0 },
                    )
                    if (facts.isNotEmpty()) Text(facts.joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis)
                },
                modifier = if (scene.index == 0) Modifier.focusRequester(first) else Modifier,
            )
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun SceneDetail(ctx: StoryContext, sceneId: String, onDone: () -> Unit) {
    val scene = ctx.scenes.firstOrNull { it.id == sceneId }
    val meta = ctx.story.sceneMeta(sceneId)
    var synopsis by remember(sceneId) { mutableStateOf(meta.synopsis) }
    val first = remember { FocusRequester() }
    fun move(to: Int) { Outline.move(ctx.text, sceneId, to)?.let(ctx.applyText) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(scene?.heading ?: "Scene", style = MaterialTheme.typography.titleLarge)
        meta.record?.let { Text("Changed by ${it.updatedByName ?: "someone"} · ${whenText(it.updatedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDone, modifier = Modifier.focusRequester(first)) { Text("Back") }
            scene?.let { s -> OutlinedButton(onClick = { ctx.openInScript(s) }) { Text("Show in script") } }
            if (scene != null) {
                OutlinedButton(onClick = { move(scene.index - 1) }, enabled = scene.index > 0) { Text("Move up") }
                OutlinedButton(onClick = { move(scene.index + 2) }, enabled = scene.index < ctx.scenes.size - 1) { Text("Move down") }
                OutlinedButton(onClick = { move(0) }, enabled = scene.index > 0) { Text("To start") }
                OutlinedButton(onClick = { move(ctx.scenes.size) }, enabled = scene.index < ctx.scenes.size - 1) { Text("To end") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TvTextField(synopsis, { synopsis = it }, "What happens", modifier = Modifier.weight(1f))
            Button(onClick = { ctx.changeStory(listOf(Story.sceneMeta(sceneId, synopsis = synopsis))) }, enabled = synopsis != meta.synopsis) { Text("Save") }
        }
        Text("Colour", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StoryColors.SCENE.forEachIndexed { i, c ->
                FilterChip(meta.color == i, { ctx.changeStory(listOf(Story.sceneMeta(sceneId, color = i))) }, leadingIcon = {
                    Box(Modifier.size(12.dp).background(if (i == 0) Color.Transparent else Color(c), CircleShape).border(1.dp, MaterialTheme.colorScheme.onSurface, CircleShape))
                }) { Text(StoryColors.SCENE_NAMES[i]) }
            }
        }
        Text("Progress", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(meta.status == null, { ctx.changeStory(listOf(Story.sceneMeta(sceneId, clearStatus = true))) }) { Text("Not set") }
            SceneStatus.entries.forEach { st -> FilterChip(meta.status == st, { ctx.changeStory(listOf(Story.sceneMeta(sceneId, status = st))) }) { Text(st.label) } }
        }
        Text("Writing it", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(meta.assigneeId == null, { ctx.changeStory(listOf(Story.sceneMeta(sceneId, assignee = "" to ""))) }) { Text("No one") }
            ctx.people.forEach { p -> FilterChip(meta.assigneeId == p.id, { ctx.changeStory(listOf(Story.sceneMeta(sceneId, assignee = p.id to p.name))) }) { Text(p.name) } }
        }
        scene?.let { s ->
            if (s.characters.isNotEmpty()) Text("Speaking: " + s.characters.joinToString(), style = MaterialTheme.typography.bodyMedium)
            s.location?.let { Text("Where: $it", style = MaterialTheme.typography.bodyMedium) }
        }
        CommentsTv(ctx, "scene:$sceneId")
        ctx.history?.let { HistoryTv(ctx, it, Story.sceneRecordId(sceneId), RecordTypes.SCENE, sceneId) }
    }
    LaunchedEffect(sceneId) { runCatching { first.requestFocus() } }
}

// ---- Lists ----

@Composable
private fun ListsTv(ctx: StoryContext, onOpen: (StoryEntry) -> Unit) {
    val lists = ctx.story.lists()
    var listId by remember { mutableStateOf(lists.firstOrNull()?.id) }
    var adding by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    val list = lists.firstOrNull { it.id == listId } ?: lists.firstOrNull()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(lists, key = { it.id }) { l ->
                FilterChip(l.id == list?.id, { listId = l.id }, modifier = if (l.id == list?.id) Modifier.focusRequester(first) else Modifier) { Text(l.name) }
            }
            item { OutlinedButton(onClick = { adding = "list"; name = "" }) { Text("+ List") } }
            item { OutlinedButton(onClick = { adding = "entry"; name = "" }, enabled = list != null) { Text("+ ${list?.name ?: "Entry"}") } }
        }
        adding?.let { what ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvTextField(name, { name = it }, if (what == "list") "New list's name (Props, Themes…)" else "Name", modifier = Modifier.weight(1f))
                Button(onClick = {
                    if (what == "list") ctx.changeStory(listOf(Story.setList("l-" + newId(), name.trim(), ListKind.CUSTOM, 10 + lists.size)))
                    else if (list != null) {
                        val id = if (list.kind == ListKind.CUSTOM) "e-" + newId() else Story.detectedId(list.id, name)
                        ctx.changeStory(listOf(Story.setEntry(id, list.id, name.trim(), "")))
                    }
                    adding = null
                }, enabled = name.isNotBlank()) { Text("Add") }
                OutlinedButton(onClick = { adding = null }) { Text("Cancel") }
            }
        }
        if (list != null) LazyColumn(Modifier.weight(1f)) {
            items(ctx.story.listItems(list, ctx.scenes), key = { it.id }) { e ->
                val scenes = ctx.story.scenesOf(e, list, ctx.scenes).size
                ListItem(
                    selected = false,
                    onClick = { onOpen(e) },
                    headlineContent = { Text(e.name) },
                    supportingContent = {
                        Text(listOf(e.notes.ifBlank { if (e.record == null) "From the script" else "" }, if (scenes > 0) "$scenes scenes" else "")
                            .filter { it.isNotEmpty() }.joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                )
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun EntryDetail(ctx: StoryContext, entryId: String, listId: String, onDone: () -> Unit) {
    val list = ctx.story.lists().firstOrNull { it.id == listId }
    val entry = ctx.story.entry(entryId) ?: list?.let { l -> ctx.story.listItems(l, ctx.scenes).firstOrNull { it.id == entryId } } ?: return onDone()
    var name by remember(entryId) { mutableStateOf(entry.name) }
    var notes by remember(entryId) { mutableStateOf(entry.notes) }
    var linking by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val ref = Ref.entry(entryId)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(entry.name, style = MaterialTheme.typography.titleLarge)
        entry.record?.let { Text("Changed by ${it.updatedByName ?: "someone"} · ${whenText(it.updatedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            ?: Text("Found in the script. Write about them and they're kept.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDone, modifier = Modifier.focusRequester(first)) { Text("Back") }
            Button(onClick = { ctx.changeStory(listOf(Story.setEntry(entryId, listId, name.trim(), notes))) }, enabled = name.isNotBlank() && (name != entry.name || notes != entry.notes)) { Text("Save") }
            OutlinedButton(onClick = { linking = !linking }) { Text(if (linking) "Cancel link" else "Add link") }
            entry.record?.let { r ->
                OutlinedButton(onClick = { if (confirmDelete) { ctx.changeStory(listOf(Story.deleteRecord(r))); onDone() } else confirmDelete = true }) {
                    Text(if (confirmDelete) "Select again to delete" else "Delete")
                }
            }
        }
        TvTextField(name, { name = it }, "Name")
        TvTextField(notes, { notes = it }, if (list?.kind == ListKind.CHARACTERS) "Bio" else "Notes")
        if (linking) {
            TvTextField(label, { label = it }, "How (sister of, owns, meets…)")
            Text("Link to:", style = MaterialTheme.typography.labelLarge)
            val targets = ctx.story.lists().flatMap { l -> ctx.story.listItems(l, ctx.scenes).map { Ref.entry(it.id) to "${it.name} (${l.name})" } } +
                ctx.scenes.filter { it.id != null }.map { Ref.scene(it.id!!) to "${it.index + 1}. ${it.heading}" }
            targets.filter { it.first != ref }.forEach { (r, n) ->
                ListItem(selected = false, onClick = { ctx.changeStory(listOf(Story.link("k-" + newId(), ref, r, label.trim()))); linking = false; label = "" }, headlineContent = { Text(n) })
            }
        }
        val scenes = ctx.story.scenesOf(entry, list, ctx.scenes)
        if (scenes.isNotEmpty()) {
            Text("In the script", style = MaterialTheme.typography.labelLarge)
            scenes.forEach { s -> ListItem(selected = false, onClick = { ctx.openInScript(s) }, headlineContent = { Text("${s.index + 1}. ${s.heading}") }) }
        }
        val links = ctx.story.links().filter { it.from == ref || it.to == ref }
        if (links.isNotEmpty()) {
            Text("Links (select to remove)", style = MaterialTheme.typography.labelLarge)
            links.forEach { l ->
                val other = if (l.from == ref) l.to else l.from
                ListItem(selected = false, onClick = { ctx.changeStory(listOf(Story.deleteRecord(l.record))) }, headlineContent = { Text("${l.label.ifBlank { "linked to" }} → ${nameOf(ctx, other)}") })
            }
        }
        CommentsTv(ctx, "entry:$entryId")
        ctx.history?.let { HistoryTv(ctx, it, entryId, RecordTypes.ENTRY, null) }
    }
    LaunchedEffect(entryId) { runCatching { first.requestFocus() } }
}

private fun nameOf(ctx: StoryContext, ref: Ref): String = when (ref.kind) {
    Ref.Kind.SCENE -> ctx.scenes.firstOrNull { it.id == ref.id }?.heading ?: "a scene"
    Ref.Kind.ENTRY -> ctx.story.entry(ref.id)?.name ?: ctx.story.lists().flatMap { ctx.story.listItems(it, ctx.scenes) }.firstOrNull { it.id == ref.id }?.name ?: "an entry"
}

// ---- Comments and history ----

@Composable
private fun CommentsTv(ctx: StoryContext, on: String) {
    val all = ctx.story.comments(on)
    var draft by remember(on) { mutableStateOf("") }
    var replyTo by remember(on) { mutableStateOf<String?>(null) }
    Text("Comments", style = MaterialTheme.typography.labelLarge)
    all.filter { it.parentId == null }.forEach { c ->
        ListItem(
            selected = replyTo == c.id,
            onClick = { replyTo = if (replyTo == c.id) null else c.id },
            onLongClick = { ctx.changeStory(listOf(Story.resolve(c.id, !c.resolved))) },
            overlineContent = { Text("${c.authorName ?: "someone"} · ${whenText(c.createdAt)}" + if (c.resolved) " · resolved" else "") },
            headlineContent = { Text(c.text) },
            supportingContent = {
                val replies = all.filter { it.parentId == c.id }
                Text((listOfNotNull(c.quote?.let { "“$it”" }) + replies.map { "${it.authorName}: ${it.text}" }).joinToString("\n").ifEmpty { "Select to reply · hold to resolve" })
            },
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TvTextField(draft, { draft = it }, if (replyTo != null) "Reply" else "Add a comment", modifier = Modifier.weight(1f))
        Button(onClick = {
            ctx.changeStory(listOf(Story.comment("c-" + newId(), on, draft.trim(), ctx.selfName, parentId = replyTo)))
            draft = ""
            replyTo = null
        }, enabled = draft.isNotBlank()) { Text("Send") }
    }
}

@Composable
private fun HistoryTv(ctx: StoryContext, history: dev.nitrate.android.writing.StoryHistory, recordId: String, type: String, sceneId: String?) {
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { shown = !shown }) { Text(if (shown) "Hide history" else "History") }
    if (!shown) return
    val log by produceState<List<ScriptEdit>?>(null, recordId) { value = runCatching { history.log() }.getOrNull() ?: emptyList() }
    val items = log?.let { l ->
        History.of(recordId, l).map { (e, c) -> Triple(e, c.set.keys.joinToString().ifEmpty { "changed" }, true) } +
            (sceneId?.let { sid -> l.filter { it.op != null && it.sceneId == sid }.takeLast(20).map { Triple(it, "wrote in the scene", false) } } ?: emptyList())
    }?.sortedByDescending { it.first.version }
    if (items == null) Text("Loading…") else if (items.isEmpty()) Text("No changes yet.")
    else items.take(30).forEach { (e, what, restorable) ->
        ListItem(
            selected = false,
            enabled = restorable,
            onClick = {
                scope.launch {
                    val then = runCatching { history.recordAt(e.version, recordId) }.getOrNull()
                    ctx.changeStory(listOf(History.restore(ctx.story.records[recordId], then, recordId, type)))
                }
            },
            headlineContent = { Text("${e.userName} $what") },
            supportingContent = { Text(whenText(e.atMs) + if (restorable) " · select to put it back like this" else "") },
        )
    }
}

// ---- Activity ----

@Composable
private fun ActivityTv(ctx: StoryContext, onOpen: (Activity) -> Unit) {
    val history = ctx.history
    if (history == null) {
        Text("Share this script to keep a history of who changed what.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val log by produceState<List<ScriptEdit>?>(null) { value = runCatching { history.log() }.getOrNull() ?: emptyList() }
    val activities = remember(log, ctx.story, ctx.scenes) { log?.let { History.activities(it, ctx.story, ctx.scenes) } }
    val first = remember { FocusRequester() }
    if (activities == null) Text("Loading…")
    else LazyColumn(Modifier.fillMaxSize()) {
        items(activities.size) { i ->
            val a = activities[i]
            ListItem(
                selected = false,
                onClick = { onOpen(a) },
                leadingContent = { Box(Modifier.size(12.dp).background(Color(StoryColors.writer(a.userId)), CircleShape)) },
                headlineContent = { Text("${a.userName} ${a.kind.verb} ${a.targetName}" + if (a.count > 1) " (${a.count} changes)" else "") },
                supportingContent = { Text(whenText(a.untilMs)) },
                modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
            )
        }
    }
    LaunchedEffect(activities != null) { runCatching { first.requestFocus() } }
}

@Composable
private fun VersionView(ctx: StoryContext, activity: Activity, onDone: () -> Unit) {
    val history = ctx.history ?: return onDone()
    val text by produceState<String?>(null, activity.toVersion) { value = runCatching { history.textAt(activity.toVersion) }.getOrNull() }
    var confirm by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("As it was at ${whenText(activity.untilMs)}", style = MaterialTheme.typography.titleLarge)
        Text("After ${activity.userName} ${activity.kind.verb} ${activity.targetName}.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDone, modifier = Modifier.focusRequester(first)) { Text("Back") }
            OutlinedButton(onClick = {
                val old = text ?: return@OutlinedButton
                if (!confirm) { confirm = true; return@OutlinedButton }
                TextOperation.diff(ctx.text, old).takeIf { !it.isNoop }?.let(ctx.applyText)
                onDone()
            }, enabled = text != null) { Text(if (confirm) "Select again to restore the script" else "Restore this version") }
        }
        // Scrolls with the D-pad once focused.
        var focused by remember { mutableStateOf(false) }
        Text(
            text?.let(Outline::stripMarkers) ?: "Loading…",
            fontFamily = FontFamily.Monospace, fontSize = 15.sp,
            modifier = Modifier.weight(1f).fillMaxWidth().onFocusChanged { focused = it.isFocused }.focusable()
                .border(if (focused) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)).padding(8.dp)
                .verticalScroll(rememberScrollState()),
        )
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

// ---- Map ----

/**
 * The map to look at: move between dots with the D-pad (to the nearest one in that direction),
 * OK opens it; the filters above choose what shows. Links are made from an entry's page.
 */
@Composable
private fun MapTv(ctx: StoryContext, onOpen: (Ref) -> Unit) {
    val lists = ctx.story.lists()
    var filter by remember { mutableStateOf(MapFilter()) }
    val full = remember(ctx.story, ctx.scenes) { StoryGraph.build(ctx.story, ctx.scenes) }
    val shown = remember(full, filter) { StoryGraph.filter(full, filter) }
    val positions = remember(shown) { StoryGraph.layout(shown) }
    var selected by remember(shown) { mutableStateOf(shown.nodes.firstOrNull { it.sceneIndex == 0 }?.ref ?: shown.nodes.firstOrNull()?.ref) }
    var mapFocused by remember { mutableStateOf(false) }
    val listColor = lists.mapIndexed { i, l -> l.id to Color(StoryColors.list(i)) }.toMap()
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
    val mapFocus = remember { FocusRequester() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(EdgeKind.entries.size) { i ->
                val k = EdgeKind.entries[i]
                FilterChip(k in filter.edgeKinds, { filter = filter.copy(edgeKinds = if (k in filter.edgeKinds) filter.edgeKinds - k else filter.edgeKinds + k) }, leadingIcon = {
                    Box(Modifier.size(10.dp).background(Color(StoryColors.edge(k)), CircleShape))
                }) { Text(k.label) }
            }
            item { FilterChip(filter.showScenes, { filter = filter.copy(showScenes = !filter.showScenes) }) { Text("Scenes") } }
            items(lists.size) { i ->
                val l = lists[i]
                val on = filter.lists?.contains(l.id) ?: true
                FilterChip(on, {
                    val now = filter.lists ?: lists.map { it.id }.toSet()
                    filter = filter.copy(lists = if (on) now - l.id else now + l.id)
                }, leadingIcon = { Box(Modifier.size(10.dp).background(listColor[l.id] ?: Color.Gray, CircleShape)) }) { Text(l.name) }
            }
            item { FilterChip(filter.hideLoose, { filter = filter.copy(hideLoose = !filter.hideLoose) }) { Text("Only joined") } }
            item { FilterChip(filter.focus != null, { filter = filter.copy(focus = if (filter.focus != null) null else selected) }) { Text("Focus on selected") } }
        }
        Text(
            selected?.let { r -> nameOf(ctx, r) + "  ·  OK opens it" } ?: "Nothing to show with these filters",
            style = MaterialTheme.typography.bodyMedium,
        )
        Canvas(
            Modifier.weight(1f).fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                .border(if (mapFocused) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                .focusRequester(mapFocus)
                .onFocusChanged { mapFocused = it.isFocused }
                // Said as the map and the entry chosen on it, which the arrows move between.
                .semantics {
                    contentDescription = "Story map"
                    stateDescription = selected?.let { r -> nameOf(ctx, r) + ", OK opens it" } ?: "nothing to show with these filters"
                }
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val from = selected ?: return@onKeyEvent false
                    val (dx, dy) = when (e.key) {
                        Key.DirectionLeft -> -1 to 0
                        Key.DirectionRight -> 1 to 0
                        Key.DirectionUp -> 0 to -1
                        Key.DirectionDown -> 0 to 1
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { onOpen(from); return@onKeyEvent true }
                        else -> return@onKeyEvent false
                    }
                    // Nothing further that way: let focus leave the map (up to the filters).
                    val next = StoryGraph.nearest(from, dx, dy, positions) ?: return@onKeyEvent false
                    selected = next
                    true
                }
                .focusable(),
        ) {
            fun at(r: Ref) = positions[r]?.let { Offset(it.first * size.width, it.second * size.height) }
            shown.edges.forEach { e ->
                val a = at(e.from) ?: return@forEach
                val b = at(e.to) ?: return@forEach
                val strong = e.from == selected || e.to == selected
                drawLine(Color(StoryColors.edge(e.kind)).copy(alpha = if (strong) 0.95f else 0.35f), a, b, strokeWidth = if (strong) 4f else 2f)
            }
            shown.nodes.forEach { n ->
                val p = at(n.ref) ?: return@forEach
                val color = if (n.listId == null) Color(StoryColors.SCENE_NODE) else listColor[n.listId] ?: Color.Gray
                val radius = if (n.listId == null) 8f else 11f
                drawCircle(color, radius, p)
                if (n.ref == selected) drawCircle(Color.White, radius + 7f, p, style = Stroke(4f))
                val label = if (n.listId == null) "${(n.sceneIndex ?: 0) + 1}" else n.label.take(20)
                val measured = measurer.measure(label, labelStyle, softWrap = false, maxLines = 1)
                val x = if (p.x + radius + 4f + measured.size.width > size.width) p.x - radius - 4f - measured.size.width else p.x + radius + 4f
                drawText(measured, topLeft = Offset(x, p.y - measured.size.height / 2f))
            }
        }
    }
}
