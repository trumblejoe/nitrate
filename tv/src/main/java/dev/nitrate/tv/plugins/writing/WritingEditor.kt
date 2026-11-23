package dev.nitrate.tv.plugins.writing

import dev.nitrate.android.writing.asTransferDocs
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import dev.nitrate.android.writing.WritingStore
import dev.nitrate.core.plugins.PlaybackControls
import dev.nitrate.core.writing.Element
import dev.nitrate.core.writing.FadeIn
import dev.nitrate.core.writing.ExportFormat
import dev.nitrate.core.writing.Fountain
import dev.nitrate.core.writing.Scripts
import dev.nitrate.core.writing.TransferServer
import dev.nitrate.android.writing.lanAddress
import dev.nitrate.core.writing.WritingDoc
import kotlinx.coroutines.delay
import androidx.compose.runtime.produceState
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private enum class Mode { EDIT, DOCUMENTS, HELP, SHARE, SCENES, LISTS, MAP, ACTIVITY }

private val ELEMENT_KEYS = mapOf(
    Key.One to Element.SCENE_HEADING,
    Key.Two to Element.ACTION,
    Key.Three to Element.CHARACTER,
    Key.Four to Element.PARENTHETICAL,
    Key.Five to Element.DIALOGUE,
    Key.Six to Element.TRANSITION,
)

private const val AUTOSAVE_MS = 800L
private const val BACKUP_MS = 5_000L
private const val RETRY_MS = 60_000L

/**
 * A keyboard-first Fountain editor. Used beside the video in the player ([playback] set) and
 * as a full-screen page. Saves as you type; [backup] also keeps a copy on the Jellyfin server.
 */
@Composable
fun WritingEditor(
    store: WritingStore,
    backup: Boolean,
    playback: PlaybackControls?,
    modifier: Modifier = Modifier,
    /** Shared screenplays (Companion plugin), when the app has them; also each script's story. */
    shared: dev.nitrate.android.writing.SharedWriting? = null,
    /** This user's name, on their comments and changes. */
    selfName: String? = null,
) {
    val docs by store.docs.collectAsState()
    var docId by remember { mutableStateOf<String?>(null) }
    var value by remember { mutableStateOf(TextFieldValue("")) }
    var loaded by remember { mutableStateOf(false) }
    // Last text written to disk, so opening a document doesn't count as an edit.
    var savedText by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(Mode.EDIT) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val editorFocus = remember { FocusRequester() }
    val context = LocalContext.current

    // Open the most recent document, after sending anything written offline and pulling in
    // anything newer from the server.
    LaunchedEffect(Unit) {
        if (backup) {
            store.flushPending()
            store.restore().takeIf { it > 0 }?.let { status = "Restored $it from server" }
        }
        val id = store.docs.value.firstOrNull()?.id ?: store.create()
        docId = id
        savedText = store.read(id)
        value = TextFieldValue(savedText)
        loaded = true
        runCatching { editorFocus.requestFocus() }
    }

    fun open(id: String) {
        scope.launch {
            docId = id
            savedText = store.read(id)
            value = TextFieldValue(savedText)
            mode = Mode.EDIT
            runCatching { editorFocus.requestFocus() }
        }
    }

    // A shared script (Companion plugin): everyone's changes merge as they type.
    val sharedOn by (shared?.available ?: kotlinx.coroutines.flow.flowOf(false)).collectAsState(false)
    val linkedId = docs.firstOrNull { it.id == docId }?.sharedId
    val session = remember(docId, linkedId, sharedOn) { docId?.takeIf { linkedId != null && sharedOn }?.let { shared?.session(it, scope) } }
    DisposableEffect(session) {
        session?.start()
        onDispose { session?.close() }
    }
    val others by (session?.others ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList())).collectAsState()
    val sessionStatus by (session?.status ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsState()
    val sessionPending by (session?.pending ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsState()
    LaunchedEffect(session) {
        val s = session ?: return@LaunchedEffect
        if (value.text != s.text.value) value = TextFieldValue(s.text.value, TextRange(s.text.value.length))
        s.remote.collect { change ->
            val t = s.text.value
            val sel = value.selection
            value = TextFieldValue(t, TextRange(change.op.transformIndex(sel.start).coerceIn(0, t.length), change.op.transformIndex(sel.end).coerceIn(0, t.length)))
        }
    }
    LaunchedEffect(sessionStatus) {
        if (sessionStatus == dev.nitrate.core.writing.SharedScriptSession.Status.UNAVAILABLE) {
            docId?.let { shared?.lost(it) }
            status = "No longer shared with you: kept here as your own"
        }
    }
    /** Every change to the text goes through here, so a shared script hears of it. */
    fun change(v: TextFieldValue) {
        value = v
        session?.edit(v.text, v.selection.start)
    }

    fun applyOp(op: dev.nitrate.core.writing.TextOperation) {
        val sel = value.selection
        val t = op.apply(value.text)
        change(TextFieldValue(t, TextRange(op.transformIndex(sel.start).coerceIn(0, t.length), op.transformIndex(sel.end).coerceIn(0, t.length))))
    }

    // ---- Story: scenes, lists, map, activity ----
    val scriptStory = remember(docId, session) { docId?.let { id -> shared?.storyFor(id, session, scope) } }
    val story by (scriptStory?.story ?: kotlinx.coroutines.flow.MutableStateFlow(dev.nitrate.core.writing.story.Story())).collectAsState()
    val scenes = remember(value.text) { dev.nitrate.core.writing.story.Outline.scenes(value.text) }
    LaunchedEffect(value.text) {
        delay(1_500)
        dev.nitrate.core.writing.story.Outline.addIds(value.text, skipAt = value.selection.start)?.let(::applyOp)
    }
    val people by produceState(emptyList<dev.nitrate.core.writing.UserRef>(), docId, linkedId) {
        this.value = docId?.let { id -> shared?.let { s -> runCatching { s.writers(id, selfName) }.getOrNull() } }.orEmpty()
    }
    val history = remember(docId, linkedId) { docId?.let { id -> shared?.history(id) } }
    val storyCtx = dev.nitrate.android.writing.StoryContext(
        text = value.text, scenes = scenes, story = story, selfId = shared?.userId, selfName = selfName ?: "Me", people = people,
        changeStory = { scriptStory?.change(it) }, applyText = ::applyOp,
        openInScript = { scene -> mode = Mode.EDIT; value = value.copy(selection = TextRange(scene.start)); runCatching { editorFocus.requestFocus() } },
        history = history,
    )
    var whoWrote by remember(docId) { mutableStateOf(false) }
    var authorship by remember(docId) { mutableStateOf<Pair<String, dev.nitrate.core.writing.story.Attribution>?>(null) }
    LaunchedEffect(whoWrote, docId) {
        while (whoWrote && docId != null) {
            authorship = runCatching { shared?.authorship(docId!!) }.getOrNull()
            delay(15_000)
        }
        if (!whoWrote) authorship = null
    }
    val writers = remember(authorship, value.text) {
        authorship?.let { (base, a) ->
            if (base == value.text) a else runCatching { a.apply(dev.nitrate.core.writing.TextOperation.diff(base, value.text), shared?.userId.orEmpty(), base) }.getOrNull()
        }
    }

    // Autosave shortly after typing stops; back up less often.
    LaunchedEffect(value.text, docId) {
        val id = docId ?: return@LaunchedEffect
        if (!loaded || value.text == savedText) return@LaunchedEffect
        delay(AUTOSAVE_MS)
        store.save(id, value.text)
        savedText = value.text
        status = "Saved"
        if (backup) {
            delay(BACKUP_MS)
            status = if (store.backUp(id)) "Saved · backed up" else "Saved on this TV · backup waiting for server"
        }
    }

    // Keep retrying queued backups while the editor is open, e.g. after working offline.
    LaunchedEffect(backup) {
        while (backup) {
            delay(RETRY_MS)
            if (store.pendingCount > 0 && store.flushPending()) status = "Saved · backed up"
        }
    }

    fun writeTo(uri: Uri?, bytes: () -> ByteArray) {
        if (uri == null) return
        runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes()) } }
            .onSuccess { status = "Exported" }.onFailure { status = "Export failed: ${it.message}" }
    }
    val exportFountain = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        writeTo(uri) { value.text.toByteArray() }
    }
    val exportFadeIn = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        writeTo(uri) { FadeIn.export(value.text) }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val text = runCatching { context.contentResolver.openInputStream(uri)?.use { Scripts.importText(it.readBytes()) } }.getOrNull()
                if (text != null) open(store.create(text)) else status = "Couldn't read that file"
            }
        }
    }
    fun exportNow(format: ExportFormat) {
        val name = Scripts.fileName(value.text, format)
        try {
            when (format) {
                ExportFormat.FOUNTAIN -> exportFountain.launch(name)
                ExportFormat.FADE_IN -> exportFadeIn.launch(name)
            }
        } catch (_: ActivityNotFoundException) {
            // Many TVs have no file picker: fall back to the shared Downloads folder.
            status = exportToDownloads(context, name, format.mime, format.bytes(value.text))
        }
    }
    fun importNow() {
        try {
            import.launch(arrayOf("text/*", "application/octet-stream"))
        } catch (_: ActivityNotFoundException) {
            status = "This TV has no file picker. Use server backup to move scripts between devices."
        }
    }

    fun convertCurrentLine(element: Element) {
        val text = value.text
        val caret = value.selection.start
        val start = text.lastIndexOf('\n', (caret - 1).coerceAtLeast(0)).let { if (it < 0 || caret == 0) 0 else it + 1 }
        val end = text.indexOf('\n', caret).let { if (it < 0) text.length else it }
        val converted = Fountain.convertLine(text.substring(start, end), element)
        change(TextFieldValue(text.replaceRange(start, end, converted), TextRange(start + converted.length)))
    }

    // Computer transfer: off unless switched on here, and always off once the editor closes.
    val transfer = remember(store) { TransferServer(store.asTransferDocs()) }
    DisposableEffect(transfer) { onDispose { transfer.stop() } }
    val transferState by transfer.state.collectAsState()

    val colors = MaterialTheme.colorScheme
    val highlight = remember(colors, others, writers) {
        FountainHighlight(colors.primary, colors.secondary, colors.onSurfaceVariant, others.map { it.cursor }, colors.primary.copy(alpha = 0.55f), writers)
    }

    Column(modifier.background(colors.surface).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(Scripts.titleOf(value.text), style = MaterialTheme.typography.titleLarge, maxLines = 1)
                val sharedNote = when {
                    session == null -> ""
                    sessionStatus == dev.nitrate.core.writing.SharedScriptSession.Status.OFFLINE -> " · Shared: offline, changes kept"
                    others.isNotEmpty() -> " · With " + others.joinToString { it.userName }
                    sessionPending -> " · Sending…"
                    else -> docs.firstOrNull { it.id == docId }?.let { " · " + it.placeLabel }.orEmpty()
                }
                Text(
                    "%d words · ~%.1f pages%s%s".format(Fountain.wordCount(value.text), Fountain.estimatePages(value.text), sharedNote, if (status.isNotEmpty()) " · $status" else ""),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = { mode = if (mode == Mode.DOCUMENTS) Mode.EDIT else Mode.DOCUMENTS }) { Text("Documents") }
            // The script's story: one press each, and again (or Back) for the script.
            listOf(Mode.SCENES to "Scenes", Mode.LISTS to "Lists", Mode.MAP to "Map").forEach { (m, label) ->
                OutlinedButton(onClick = { mode = if (mode == m) Mode.EDIT else m }) { Text(if (mode == m) "Script" else label) }
            }
            if (history != null) {
                OutlinedButton(onClick = { mode = if (mode == Mode.ACTIVITY) Mode.EDIT else Mode.ACTIVITY }) { Text(if (mode == Mode.ACTIVITY) "Script" else "Activity") }
                OutlinedButton(onClick = { whoWrote = !whoWrote }) { Text(if (whoWrote) "Who wrote: on" else "Who wrote") }
            }
            if (sharedOn) OutlinedButton(onClick = { mode = if (mode == Mode.SHARE) Mode.EDIT else Mode.SHARE }) { Text(if (linkedId != null) "Sharing" else "Share") }
            OutlinedButton(onClick = { mode = if (mode == Mode.HELP) Mode.EDIT else Mode.HELP }) { Text("Keys") }
        }

        when (mode) {
            Mode.EDIT -> BasicTextField(
                value = value,
                onValueChange = { change(it) },
                visualTransformation = highlight,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 17.sp, lineHeight = 24.sp, color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(colors.background.copy(alpha = 0.6f), MaterialTheme.shapes.medium)
                    .padding(16.dp)
                    .focusRequester(editorFocus)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return@onPreviewKeyEvent false
                        ELEMENT_KEYS[event.key]?.let { convertCurrentLine(it); return@onPreviewKeyEvent true }
                        when (event.key) {
                            Key.S -> { docId?.let { id -> scope.launch { store.save(id, value.text); status = "Saved" } }; true }
                            Key.N -> { scope.launch { open(store.create()) }; true }
                            Key.O -> { mode = Mode.DOCUMENTS; true }
                            Key.Slash -> { mode = Mode.HELP; true }
                            Key.K -> { playback?.togglePlay(); playback != null }
                            Key.J -> { playback?.seekBy(-10_000); playback != null }
                            Key.L -> { playback?.seekBy(30_000); playback != null }
                            else -> false
                        }
                    },
            )
            Mode.DOCUMENTS -> Documents(
                docs = docs,
                current = docId,
                onOpen = ::open,
                onNew = { scope.launch { open(store.create()) } },
                onDelete = { id ->
                    scope.launch {
                        store.delete(id, backup)
                        if (id == docId) open(store.docs.value.firstOrNull()?.id ?: store.create())
                    }
                },
                onMove = { id, to ->
                    scope.launch {
                        runCatching { shared?.move(id, to) }
                            .onSuccess { status = if (to == null) "Kept on this TV only" else "Moved"; if (to != null && id == docId) open(store.docs.value.firstOrNull()?.id ?: store.create()) }
                            .onFailure { status = "Couldn't: ${it.message}" }
                    }
                },
                moveTargets = { runCatching { shared?.syncOptions() }.getOrNull().orEmpty().filter { it.account.key != shared?.accountKey } },
                onExport = ::exportNow,
                onImport = ::importNow,
                transfer = transferState,
                onTransfer = { on -> if (on) transfer.start(lanAddress(context)) else transfer.stop() },
                modifier = Modifier.weight(1f),
                sharedWithYou = if (shared != null && sharedOn) ({
                    SharedWithYouItems(shared, docs.mapNotNull { it.sharedId }.toSet()) { s ->
                        scope.launch { runCatching { open(shared.open(s.id)) }.onFailure { status = "Couldn't open: ${it.message}" } }
                    }
                }) else null,
            )
            Mode.HELP -> Help(playback != null, Modifier.weight(1f))
            Mode.SCENES, Mode.LISTS, Mode.MAP, Mode.ACTIVITY -> StoryTvPanel(
                storyCtx,
                when (mode) { Mode.SCENES -> StoryTvTab.SCENES; Mode.LISTS -> StoryTvTab.LISTS; Mode.MAP -> StoryTvTab.MAP; else -> StoryTvTab.ACTIVITY },
                onBack = { mode = Mode.EDIT },
                modifier = Modifier.weight(1f),
            )
            Mode.SHARE -> if (shared != null && docId != null) SharePanel(
                shared, docId!!, onMessage = { status = it }, onDone = { mode = Mode.EDIT; runCatching { editorFocus.requestFocus() } },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Documents(
    docs: List<WritingDoc>,
    current: String?,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onDelete: (String) -> Unit,
    /** Moves a script to another account's server, or (null) keeps it on this TV only. */
    onMove: (String, String?) -> Unit = { _, _ -> },
    moveTargets: suspend () -> List<dev.nitrate.android.writing.SharedWriting.SyncOption> = { emptyList() },
    onExport: (ExportFormat) -> Unit,
    onImport: () -> Unit,
    transfer: TransferServer.State,
    onTransfer: (Boolean) -> Unit,
    modifier: Modifier,
    sharedWithYou: (@Composable () -> Unit)? = null,
) {
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var reallyDelete by remember { mutableStateOf(false) }
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onNew) { Text("New") }
            OutlinedButton(onClick = { onExport(ExportFormat.FADE_IN) }) { Text("Export .fadein") }
            OutlinedButton(onClick = { onExport(ExportFormat.FOUNTAIN) }) { Text("Export .fountain") }
            OutlinedButton(onClick = onImport) { Text("Import") }
        }
        ListItem(
            selected = false,
            onClick = { onTransfer(!transfer.running) },
            headlineContent = { Text("Transfer to and from a computer") },
            supportingContent = {
                Text(
                    when {
                        transfer.running && transfer.address != null ->
                            "Open ${transfer.address} in a browser on the same network and enter PIN ${transfer.pin}. Turns off after 15 minutes unused."
                        transfer.running -> "On, but this TV's network address couldn't be found. PIN ${transfer.pin}."
                        else -> transfer.note ?: "Off. Switch on to download or upload scripts from a browser on your network."
                    },
                )
            },
            trailingContent = { Switch(checked = transfer.running, onCheckedChange = null) },
        )
        Text("Options (or hold OK on a document) to move or delete it.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val targets by produceState(emptyList<dev.nitrate.android.writing.SharedWriting.SyncOption>(), confirmDelete) { this.value = moveTargets() }
        LazyColumn(Modifier.fillMaxSize()) {
            sharedWithYou?.let { content -> item(key = "shared-with-you") { Column { content() } } }
            items(docs, key = { it.id }) { doc ->
                val confirming = confirmDelete == doc.id
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ListItem(
                        selected = doc.id == current,
                        onClick = { onOpen(doc.id) },
                        onLongClick = { confirmDelete = doc.id },
                        headlineContent = { Text(doc.title) },
                        supportingContent = { Text(doc.placeLabel + " · " + format.format(Date(doc.modifiedMs))) },
                        modifier = Modifier.weight(1f),
                    )
                    // The visible way to what holding OK on a document does.
                    OutlinedButton(onClick = { confirmDelete = if (confirming) null else doc.id; reallyDelete = false }) {
                        Text("Options", Modifier.semantics { contentDescription = "Options for ${doc.title}" })
                    }
                }
                if (confirming) ListItem(
                    selected = false,
                    onClick = { if (reallyDelete) { onDelete(doc.id); confirmDelete = null; reallyDelete = false } else reallyDelete = true },
                    headlineContent = { Text(if (reallyDelete) "Select again to delete “${doc.title}”" else "Delete") },
                    modifier = Modifier.padding(start = 24.dp),
                )
                // Options open: where else it can go (someone else's script stays theirs).
                if (confirming && doc.ownerName == null) {
                    targets.forEach { o ->
                        ListItem(
                            selected = false,
                            onClick = { onMove(doc.id, o.account.key); confirmDelete = null },
                            headlineContent = { Text("Move to ${o.account.serverName} (${o.account.userName})") },
                            supportingContent = { Text("Becomes that account's script, synced there when it's in use" + if (doc.isShared) "; sharing here stops" else "") },
                            modifier = Modifier.padding(start = 24.dp),
                        )
                    }
                    if (doc.sharedId != null) ListItem(
                        selected = false,
                        onClick = { onMove(doc.id, null); confirmDelete = null },
                        headlineContent = { Text("Keep on this TV only") },
                        supportingContent = { Text("Removed from the server; its history stays on the server copy until then") },
                        modifier = Modifier.padding(start = 24.dp),
                    )
                    ListItem(selected = false, onClick = { confirmDelete = null }, headlineContent = { Text("Cancel") }, modifier = Modifier.padding(start = 24.dp))
                }
            }
        }
    }
}

@Composable
private fun Help(withVideo: Boolean, modifier: Modifier) {
    val rows = buildList {
        add("Ctrl+1" to "Scene heading")
        add("Ctrl+2" to "Action")
        add("Ctrl+3" to "Character")
        add("Ctrl+4" to "Parenthetical")
        add("Ctrl+5" to "Dialogue")
        add("Ctrl+6" to "Transition")
        add("Ctrl+S" to "Save now (it also saves as you type)")
        add("Ctrl+N" to "New document")
        add("Ctrl+O" to "Documents")
        if (withVideo) {
            add("Ctrl+K" to "Play / pause the video")
            add("Ctrl+J / Ctrl+L" to "Back 10 s / forward 30 s")
            add("Ctrl+E" to "Hide the writing pane")
        }
    }
    LazyColumn(modifier) {
        item {
            Text(
                "Write in Fountain: plain text that screenwriting apps open as a formatted script. Export .fadein to open it " +
                    "directly in Fade In (desktop or mobile), or .fountain for other apps. " +
                    "Scene headings start with INT. or EXT., characters are in capitals above their dialogue.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        items(rows) { (key, action) ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text(key, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(0.4f))
                Text(action, modifier = Modifier.weight(0.6f))
            }
        }
    }
}

/** Colours Fountain elements in place; the text itself is unchanged, so offsets map 1:1. */
private class FountainHighlight(
    private val heading: Color,
    private val character: Color,
    private val muted: Color,
    /** Where the others writing in a shared script are. */
    private val cursors: List<Int> = emptyList(),
    private val cursorColor: Color = muted,
    /** Who wrote what ("who wrote this"), or null. */
    private val writers: dev.nitrate.core.writing.story.Attribution? = null,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        // Scene ids are hidden: the editor shows the script without them.
        val hidden = dev.nitrate.core.writing.story.Outline.Hidden(text.text)
        val shown = hidden.shown
        val elements = Fountain.classify(shown)
        val styled = buildAnnotatedString {
            append(shown)
            writers?.takeIf { it.length == text.text.length }?.let { w ->
                var at = 0
                for (r in w.runs()) {
                    val a = hidden.toShown(at)
                    val b = hidden.toShown(at + r.length)
                    if (a < b) addStyle(SpanStyle(background = Color(dev.nitrate.core.writing.story.StoryColors.writer(r.userId)).copy(alpha = 0.25f)), a, b)
                    at += r.length
                }
            }
            var offset = 0
            for ((i, line) in shown.split('\n').withIndex()) {
                val style = when (elements.getOrNull(i)) {
                    Element.SCENE_HEADING -> SpanStyle(color = heading, fontWeight = FontWeight.Bold)
                    Element.CHARACTER -> SpanStyle(color = character, fontWeight = FontWeight.Bold)
                    Element.PARENTHETICAL -> SpanStyle(color = muted, fontStyle = FontStyle.Italic)
                    Element.TRANSITION -> SpanStyle(color = heading)
                    Element.NOTE, Element.BONEYARD, Element.SYNOPSIS -> SpanStyle(color = muted, fontStyle = FontStyle.Italic)
                    Element.SECTION, Element.TITLE_PAGE -> SpanStyle(color = muted, fontWeight = FontWeight.Bold)
                    else -> null
                }
                if (style != null && line.isNotEmpty()) addStyle(style, offset, offset + line.length)
                offset += line.length + 1
            }
            // Others' cursors: the character they're at is marked (the one before, at the end of a line).
            for (original in cursors) {
                val c = hidden.toShown(original)
                val at = if (c < shown.length && shown[c] != '\n') c else c - 1
                if (at in shown.indices) addStyle(SpanStyle(background = cursorColor), at, at + 1)
            }
        }
        return TransformedText(styled, object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = hidden.toShown(offset)
            override fun transformedToOriginal(offset: Int) = hidden.toOriginal(offset)
        })
    }
}

/** Writes to Downloads/Nitrate where a file manager or USB connection can reach it. */
private fun exportToDownloads(context: android.content.Context, name: String, mime: String, bytes: ByteArray): String = runCatching {
    if (Build.VERSION.SDK_INT >= 29) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Nitrate")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("no Downloads folder")
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        "Exported to Downloads/Nitrate/$name"
    } else {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: error("no shared storage")
        java.io.File(dir, name).writeBytes(bytes)
        "Exported to ${dir.absolutePath}/$name"
    }
}.getOrElse { "Export failed: ${it.message}" }

