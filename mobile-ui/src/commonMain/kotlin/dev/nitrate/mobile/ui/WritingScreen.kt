package dev.nitrate.mobile.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import dev.nitrate.core.plugins.PlaybackControls
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Tab
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.produceState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nitrate.core.plugins.settings
import dev.nitrate.core.writing.Element
import dev.nitrate.core.writing.ExportFormat
import dev.nitrate.core.writing.Fountain
import dev.nitrate.core.writing.Scripts
import dev.nitrate.core.writing.WritingPlugin
import dev.nitrate.core.writing.WritingSettings
import dev.nitrate.mobile.platform.rememberFilePickers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ELEMENTS = listOf(
    "Scene" to Element.SCENE_HEADING,
    "Action" to Element.ACTION,
    "Character" to Element.CHARACTER,
    "Paren" to Element.PARENTHETICAL,
    "Dialogue" to Element.DIALOGUE,
    "Transition" to Element.TRANSITION,
)

/** Hardware-keyboard shortcuts, the same as on TV: Ctrl+1-6 set the line's element. */
private val ELEMENT_KEYS = mapOf(
    Key.One to Element.SCENE_HEADING,
    Key.Two to Element.ACTION,
    Key.Three to Element.CHARACTER,
    Key.Four to Element.PARENTHETICAL,
    Key.Five to Element.DIALOGUE,
    Key.Six to Element.TRANSITION,
)

/**
 * Fountain editor: element buttons for touch, Ctrl shortcuts for a keyboard, saves as you type.
 * As a [pane] beside the video it has a compact header and [playback] keys (Ctrl+K play/pause,
 * Ctrl+J back 10 s, Ctrl+L forward 30 s).
 */
@Composable
fun WritingScreen(
    actions: AppActions,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    pane: Boolean = false,
    playback: PlaybackControls? = null,
    /** Extra header buttons, e.g. the player's split choice when shown as a [pane]. */
    headerActions: @Composable () -> Unit = {},
) {
    val store = actions.container.writing
    val settings by actions.container.plugins.host.store.settings(WritingPlugin.id, WritingSettings()).collectAsState(WritingSettings())
    val docs by store.docs.collectAsState()
    var docId by remember { mutableStateOf<String?>(null) }
    var value by remember { mutableStateOf(TextFieldValue("")) }
    var saved by remember { mutableStateOf("") }
    var showDocs by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var showTransfer by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val platformActions = dev.nitrate.mobile.platform.rememberPlatformActions()
    var sharing by remember { mutableStateOf(false) }

    // A shared script (Companion plugin): everyone's changes merge as they type.
    val shared = actions.container.sharedWriting
    val sharedOn by shared.available.collectAsState(false)
    val linkedId = docs.firstOrNull { it.id == docId }?.sharedId
    val session = remember(docId, linkedId, sharedOn) { docId?.takeIf { linkedId != null && sharedOn }?.let { shared.session(it, scope) } }
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
        // Others' changes: the text moves under the cursor, which keeps its place in the words.
        s.remote.collect { change ->
            val t = s.text.value
            val sel = value.selection
            value = TextFieldValue(t, TextRange(change.op.transformIndex(sel.start).coerceIn(0, t.length), change.op.transformIndex(sel.end).coerceIn(0, t.length)))
        }
    }
    LaunchedEffect(sessionStatus) {
        if (sessionStatus == dev.nitrate.core.writing.SharedScriptSession.Status.UNAVAILABLE) {
            docId?.let { shared.lost(it) }
            status = "No longer shared with you: kept here as your own"
        }
    }
    /** Every change to the text goes through here, so a shared script hears of it. */
    fun change(v: TextFieldValue) {
        value = v
        session?.edit(v.text, v.selection.start)
    }

    /** A change made from the scene cards or a restore: applied with the cursor kept in place. */
    fun applyOp(op: dev.nitrate.core.writing.TextOperation) {
        val sel = value.selection
        val t = op.apply(value.text)
        change(TextFieldValue(t, TextRange(op.transformIndex(sel.start).coerceIn(0, t.length), op.transformIndex(sel.end).coerceIn(0, t.length))))
    }

    // ---- Story: scenes, lists, map, comments, activity ----
    var tab by remember { mutableStateOf(StoryTab.SCRIPT) }
    val account by actions.container.account.collectAsState()
    val scriptStory = remember(docId, session) { docId?.let { shared.storyFor(it, session, scope) } }
    val story by (scriptStory?.story ?: kotlinx.coroutines.flow.MutableStateFlow(dev.nitrate.core.writing.story.Story())).collectAsState()
    val scenes = remember(value.text) { dev.nitrate.core.writing.story.Outline.scenes(value.text) }
    // New headings get their hidden id once the writer has moved off the line.
    LaunchedEffect(value.text) {
        delay(1_500)
        dev.nitrate.core.writing.story.Outline.addIds(value.text, skipAt = value.selection.start)?.let(::applyOp)
    }
    val people by produceState(emptyList<dev.nitrate.core.writing.UserRef>(), docId, linkedId, account) {
        val me = listOfNotNull(account?.let { dev.nitrate.core.writing.UserRef(actions.server.userId.orEmpty(), it.name) })
        this.value = me + (docId?.takeIf { linkedId != null }?.let { id -> runCatching { shared.info(id) }.getOrNull() }
            ?.let { i -> (listOf(i.owner) + i.members).filter { it.id != actions.server.userId } }.orEmpty())
    }
    val history = remember(docId, linkedId) {
        docId?.takeIf { linkedId != null }?.let { id ->
            object : dev.nitrate.android.writing.StoryHistory {
                override suspend fun log() = shared.log(id)
                override suspend fun textAt(version: Long) = shared.at(id, version)?.text.orEmpty()
                override suspend fun recordAt(version: Long, id2: String) = shared.at(id, version)?.records?.firstOrNull { it.id == id2 }
            }
        }
    }
    fun showScene(scene: dev.nitrate.core.writing.story.Scene) {
        tab = StoryTab.SCRIPT
        value = value.copy(selection = TextRange(scene.start))
    }
    val storyCtx = dev.nitrate.android.writing.StoryContext(
        text = value.text, scenes = scenes, story = story, selfId = actions.server.userId, selfName = account?.name ?: "Me",
        people = people, changeStory = { scriptStory?.change(it) }, applyText = ::applyOp, openInScript = ::showScene, history = history,
    )
    var commenting by remember { mutableStateOf(false) }

    // "Who wrote this" (shared scripts): the script coloured by writer.
    var whoWrote by remember(docId) { mutableStateOf(false) }
    var authorship by remember(docId) { mutableStateOf<Pair<String, dev.nitrate.core.writing.story.Attribution>?>(null) }
    LaunchedEffect(whoWrote, docId) {
        while (whoWrote && docId != null) {
            authorship = runCatching { shared.authorship(docId!!) }.getOrNull()
            delay(15_000)
        }
        if (!whoWrote) authorship = null
    }
    val writers = remember(authorship, value.text) {
        authorship?.let { (base, a) ->
            // Typed here since the server's copy: counted as this writer's until the next refresh.
            if (base == value.text) a else runCatching { a.apply(dev.nitrate.core.writing.TextOperation.diff(base, value.text), actions.server.userId.orEmpty(), base) }.getOrNull()
        }
    }

    // Computer transfer: off unless switched on, and off again when the editor closes or the
    // app goes to the background.
    val transfer = dev.nitrate.mobile.platform.rememberScriptTransfer(store)
    val transferState by (transfer?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(dev.nitrate.mobile.platform.TransferState()) }).collectAsState()
    DisposableEffect(transfer) { onDispose { transfer?.stop() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (transfer?.state?.value?.running == true) transfer.stop("Turned off when Nitrate went to the background.")
    }

    fun open(id: String) = scope.launch {
        docId = id
        saved = store.read(id)
        value = TextFieldValue(saved)
        showDocs = false
    }

    val pickers = rememberFilePickers(
        onOpened = { bytes -> scope.launch { runCatching { open(store.create(Scripts.importText(bytes))) }.onFailure { status = "Couldn't import: ${it.message}" } } },
        onResult = { status = it },
    )

    LaunchedEffect(Unit) {
        if (settings.backupToServer) {
            store.flushPending()
            store.restore().takeIf { it > 0 }?.let { status = "Restored $it from server" }
        }
        open(store.docs.value.firstOrNull()?.id ?: store.create())
    }
    // Autosave shortly after typing stops; back up a little later.
    LaunchedEffect(value.text, docId) {
        val id = docId ?: return@LaunchedEffect
        if (value.text == saved) return@LaunchedEffect
        delay(800)
        store.save(id, value.text)
        saved = value.text
        status = "Saved"
        if (settings.backupToServer) {
            delay(5_000)
            status = if (store.backUp(id)) "Saved · backed up" else "Saved here · backup waiting for server"
        }
    }

    fun convert(element: Element) {
        val text = value.text
        val caret = value.selection.start
        val start = if (caret == 0) 0 else text.lastIndexOf('\n', caret - 1) + 1
        val end = text.indexOf('\n', caret).let { if (it < 0) text.length else it }
        val converted = Fountain.convertLine(text.substring(start, end), element)
        change(TextFieldValue(text.replaceRange(start, end, converted), TextRange(start + converted.length)))
    }

    val colors = MaterialTheme.colorScheme
    val highlight = remember(colors, others, writers) {
        FountainHighlight(colors.primary, colors.secondary, colors.onSurfaceVariant, others.map { it.cursor }, colors.primary.copy(alpha = 0.55f), writers)
    }

    if (sharing) docId?.let { id -> ShareScriptDialog(shared, id, onMessage = { status = it }) { sharing = false } }
    if (commenting) LineCommentDialog(storyCtx, value.selection.start) { commenting = false }
    if (showTransfer) {
        TransferDialog(transferState, onToggle = { on -> if (on) transfer?.start() else transfer?.stop() }, onDismiss = { showTransfer = false })
    }
    Column(modifier.fillMaxSize().imePadding()) {
        if (!pane) Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = if (pane) 4.dp else 0.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(if (pane) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack, if (pane) "Close writing" else "Back") }
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(Scripts.titleOf(value.text), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sharedNote = when {
                    session == null -> ""
                    sessionStatus == dev.nitrate.core.writing.SharedScriptSession.Status.OFFLINE -> " · Shared: offline, changes kept"
                    others.isNotEmpty() -> " · With " + others.joinToString { it.userName }
                    sessionPending -> " · Sending…"
                    else -> docs.firstOrNull { it.id == docId }?.let { " · " + it.placeLabel }.orEmpty()
                }
                Text(
                    "${Fountain.wordCount(value.text)} words · ~${dev.nitrate.mobile.platform.formatDecimal(Fountain.estimatePages(value.text).toDouble(), 1)} pages$sharedNote${if (status.isNotEmpty()) " · $status" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            headerActions()
            IconButton(onClick = { showDocs = !showDocs }) { Icon(Icons.AutoMirrored.Filled.List, "Documents") }
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.Add, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("New script") }, onClick = { menu = false; scope.launch { open(store.create()) } })
                if (sharedOn) DropdownMenuItem(text = { Text(if (linkedId != null) "Sharing…" else "Share with people…") }, onClick = { menu = false; sharing = true })
                DropdownMenuItem(text = { Text("Export .fadein (Fade In)") }, onClick = {
                    menu = false
                    pickers.save(Scripts.fileName(value.text, ExportFormat.FADE_IN), ExportFormat.FADE_IN.mime, ExportFormat.FADE_IN.bytes(value.text))
                })
                DropdownMenuItem(text = { Text("Export .fountain") }, onClick = {
                    menu = false
                    pickers.save(Scripts.fileName(value.text, ExportFormat.FOUNTAIN), ExportFormat.FOUNTAIN.mime, ExportFormat.FOUNTAIN.bytes(value.text))
                })
                DropdownMenuItem(text = { Text("Import .fadein or .fountain") }, onClick = { menu = false; pickers.open() })
                if (transfer != null) DropdownMenuItem(
                    text = { Text(if (transferState.running) "Transfer to a computer (on)" else "Transfer to a computer") },
                    onClick = { menu = false; showTransfer = true },
                )
            }
        }

        if (!showDocs) {
            ScrollableTabRow(selectedTabIndex = StoryTab.entries.indexOf(tab), edgePadding = 8.dp, divider = {}) {
                StoryTab.entries.filter { it != StoryTab.ACTIVITY || history != null }.forEach { t ->
                    Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.label) })
                }
            }
        }
        if (showDocs) {
            LazyColumn(Modifier.weight(1f)) {
                if (sharedOn) item(key = "shared-with-you") {
                    SharedWithYou(shared, docs.mapNotNull { it.sharedId }.toSet()) { s ->
                        scope.launch { runCatching { open(shared.open(s.id)) }.onFailure { status = "Couldn't open: ${it.message}" } }
                    }
                }
                items(docs, key = { it.id }) { doc ->
                    var confirm by remember { mutableStateOf(false) }
                    var actions by remember { mutableStateOf(false) }
                    ListItem(
                        modifier = Modifier.clickable { open(doc.id) },
                        headlineContent = { Text(doc.title) },
                        supportingContent = {
                            Text(doc.placeLabel + " · " + dev.nitrate.mobile.platform.formatDateTime(doc.modifiedMs))
                        },
                        trailingContent = {
                            if (confirm) {
                                TextButton(onClick = {
                                    scope.launch {
                                        store.delete(doc.id, settings.backupToServer)
                                        if (doc.id == docId) open(store.docs.value.firstOrNull()?.id ?: store.create())
                                    }
                                }) { Text("Delete", color = colors.error) }
                            } else androidx.compose.foundation.layout.Box {
                                IconButton(onClick = { actions = true }) { Icon(Icons.Default.MoreVert, "Script actions") }
                                ScriptActionsMenu(actions, doc, shared, onDismiss = { actions = false }, onDelete = { confirm = true }, onMessage = { status = it }) { moved ->
                                    if (moved && doc.id == docId) scope.launch { open(store.docs.value.firstOrNull()?.id ?: store.create()) }
                                }
                            }
                        },
                    )
                }
            }
        } else if (tab == StoryTab.SCENES) {
            ScenesPanel(storyCtx, Modifier.weight(1f))
        } else if (tab == StoryTab.LISTS) {
            ListsPanel(storyCtx, Modifier.weight(1f))
        } else if (tab == StoryTab.MAP) {
            MapPanel(storyCtx, Modifier.weight(1f))
        } else if (tab == StoryTab.ACTIVITY) {
            ActivityPanel(storyCtx, Modifier.weight(1f))
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ELEMENTS) { (label, element) -> AssistChip(onClick = { convert(element) }, label = { Text(label) }) }
                // A comment on the line being written (kept on its scene, quoting the line).
                item { AssistChip(onClick = { commenting = true }, enabled = scenes.any { it.start <= value.selection.start && it.id != null }, label = { Text("Comment") }) }
                if (history != null) item { FilterChip(whoWrote, { whoWrote = !whoWrote }, label = { Text("Who wrote this") }) }
            }
            if (whoWrote) {
                val names = people.associate { it.id to it.name }
                Row(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    (writers?.shares()?.keys ?: emptySet()).forEach { id ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            androidx.compose.foundation.layout.Box(Modifier.size(10.dp).background(Color(dev.nitrate.core.writing.story.StoryColors.writer(id)), androidx.compose.foundation.shape.CircleShape))
                            Text(" " + (names[id] ?: "someone"), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (writers == null) Text("Working out who wrote what…", style = MaterialTheme.typography.labelSmall)
                }
            }
            BasicTextField(
                value = value,
                onValueChange = { change(it) },
                visualTransformation = highlight,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, lineHeight = 22.sp, color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                modifier = Modifier.weight(1f).fillMaxWidth().padding(if (pane) 8.dp else 12.dp)
                    .background(colors.surfaceContainer, MaterialTheme.shapes.medium).padding(14.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return@onPreviewKeyEvent false
                        ELEMENT_KEYS[event.key]?.let { convert(it); return@onPreviewKeyEvent true }
                        when (event.key) {
                            Key.S -> { docId?.let { id -> scope.launch { store.save(id, value.text); saved = value.text; status = "Saved" } }; true }
                            Key.N -> { scope.launch { open(store.create()) }; true }
                            Key.O -> { showDocs = true; true }
                            Key.K -> { playback?.togglePlay(); playback != null }
                            Key.J -> { playback?.seekBy(-10_000); playback != null }
                            Key.L -> { playback?.seekBy(30_000); playback != null }
                            else -> false
                        }
                    },
            )
        }
    }
}

/**
 * A script's actions in the documents list: move it to another account's server (it's then that
 * account's, and synced there when that account is in use), keep it on this device only, delete.
 */
@Composable
private fun ScriptActionsMenu(
    expanded: Boolean,
    doc: dev.nitrate.core.writing.WritingDoc,
    shared: dev.nitrate.android.writing.SharedWriting,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onMessage: (String) -> Unit,
    onMoved: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val options by produceState(emptyList<dev.nitrate.android.writing.SharedWriting.SyncOption>(), expanded) {
        if (expanded) this.value = runCatching { shared.syncOptions() }.getOrDefault(emptyList())
    }
    var confirmMove by remember { mutableStateOf<dev.nitrate.android.writing.SharedWriting.SyncOption?>(null) }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // Someone else's script can't be moved (it's theirs); the owner's shared one stops being shared.
        if (doc.ownerName == null) {
            options.filter { it.account.key != shared.accountKey }.forEach { o ->
                DropdownMenuItem(
                    text = { Text("Move to ${o.account.serverName} (${o.account.userName})") },
                    onClick = { onDismiss(); confirmMove = o },
                )
            }
            if (doc.sharedId != null) DropdownMenuItem(text = { Text("Keep on this device only") }, onClick = {
                onDismiss()
                scope.launch {
                    runCatching { shared.move(doc.id, null) }
                        .onSuccess { onMessage("Kept on this device only") }
                        .onFailure { onMessage("Couldn't: ${it.message}") }
                }
            })
        }
        DropdownMenuItem(text = { Text("Delete…") }, onClick = { onDismiss(); onDelete() })
    }
    confirmMove?.let { o ->
        AlertDialog(
            onDismissRequest = { confirmMove = null },
            title = { Text("Move to ${o.account.serverName}?") },
            text = {
                Text(
                    "“${doc.title}” becomes ${o.account.userName}'s script and is synced with ${o.account.serverName} the next time that account is in use" +
                        (if (doc.sharedId != null) ". It's removed from this server, with its history here" else "") +
                        (if (doc.isShared) ", and the others stop writing in it (they keep their copies)." else "."),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmMove = null
                    scope.launch {
                        runCatching { shared.move(doc.id, o.account.key) }
                            .onSuccess { onMessage("Moved to ${o.account.serverName}"); onMoved(true) }
                            .onFailure { onMessage("Couldn't move: ${it.message}") }
                    }
                }) { Text("Move") }
            },
            dismissButton = { TextButton(onClick = { confirmMove = null }) { Text("Cancel") } },
        )
    }
}

/** The editor's tabs: the script itself, then its story. */
private enum class StoryTab(val label: String) { SCRIPT("Script"), SCENES("Scenes"), LISTS("Lists"), MAP("Map"), ACTIVITY("Activity") }

/** A comment on the line at the cursor, kept on its scene with the line quoted. */
@Composable
private fun LineCommentDialog(ctx: dev.nitrate.android.writing.StoryContext, cursor: Int, onDismiss: () -> Unit) {
    val scene = ctx.scenes.lastOrNull { it.start <= cursor && it.id != null } ?: return onDismiss()
    val lineStart = ctx.text.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0)).let { if (it < 0 || cursor == 0) 0 else it + 1 }
    val lineEnd = ctx.text.indexOf('\n', cursor).let { if (it < 0) ctx.text.length else it }
    val line = dev.nitrate.core.writing.story.Outline.stripMarkers(ctx.text.substring(lineStart, lineEnd)).trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(scene.heading, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = { Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) { CommentThread(ctx, "scene:${scene.id}", quote = line.takeIf { it.isNotEmpty() }) } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** Switches the computer transfer page on or off and shows where to find it. */
@Composable
fun TransferDialog(state: dev.nitrate.mobile.platform.TransferState, onToggle: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transfer to a computer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    Modifier.fillMaxWidth().clickable { onToggle(!state.running) }.padding(vertical = 4.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text("Transfer page", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = state.running, onCheckedChange = null)
                }
                when {
                    state.running && state.address != null -> {
                        Text("On a computer on the same Wi-Fi, open:", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            state.address!!,
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { clipboard.setText(AnnotatedString(state.address!!)) },
                        )
                        Text("PIN", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(state.pin.orEmpty(), style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 6.sp))
                        Text(
                            "Download and upload .fadein and .fountain scripts there. Keep Nitrate open on this screen; it turns off after 15 minutes unused or when Nitrate goes to the background.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.running -> Text("On, but this phone's network address couldn't be found. Connect to Wi-Fi. PIN ${state.pin}.")
                    else -> Text(
                        state.note ?: "Off. Switch on to download or upload scripts from a browser on a computer on the same Wi-Fi.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** Colours Fountain elements in place; the text is unchanged, so offsets map 1:1. */
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
                    if (a < b) addStyle(SpanStyle(background = Color(dev.nitrate.core.writing.story.StoryColors.writer(r.userId)).copy(alpha = 0.22f)), a, b)
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
