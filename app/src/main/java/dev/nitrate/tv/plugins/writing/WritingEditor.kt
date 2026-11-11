package dev.nitrate.tv.plugins.writing

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
import dev.nitrate.tv.plugins.core.PlaybackControls
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private enum class Mode { EDIT, DOCUMENTS, HELP }

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
                val text = runCatching { context.contentResolver.openInputStream(uri)?.use { importText(it.readBytes()) } }.getOrNull()
                if (text != null) open(store.create(text)) else status = "Couldn't read that file"
            }
        }
    }
    fun exportNow(format: ExportFormat) {
        val name = fileName(value.text, format)
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
        value = TextFieldValue(text.replaceRange(start, end, converted), TextRange(start + converted.length))
    }

    // Computer transfer: off unless switched on here, and always off once the editor closes.
    val transfer = remember(store) { TransferServer(store.asTransferDocs()) }
    DisposableEffect(transfer) { onDispose { transfer.stop() } }
    val transferState by transfer.state.collectAsState()

    val colors = MaterialTheme.colorScheme
    val highlight = remember(colors) { FountainHighlight(colors.primary, colors.secondary, colors.onSurfaceVariant) }

    Column(modifier.background(colors.surface).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(WritingStore.titleOf(value.text), style = MaterialTheme.typography.titleLarge, maxLines = 1)
                Text(
                    "%d words · ~%.1f pages%s".format(Fountain.wordCount(value.text), Fountain.estimatePages(value.text), if (status.isNotEmpty()) " · $status" else ""),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = { mode = if (mode == Mode.DOCUMENTS) Mode.EDIT else Mode.DOCUMENTS }) { Text("Documents") }
            OutlinedButton(onClick = { mode = if (mode == Mode.HELP) Mode.EDIT else Mode.HELP }) { Text("Keys") }
        }

        when (mode) {
            Mode.EDIT -> BasicTextField(
                value = value,
                onValueChange = { value = it },
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
                onExport = ::exportNow,
                onImport = ::importNow,
                transfer = transferState,
                onTransfer = { on -> if (on) transfer.start(lanAddress(context)) else transfer.stop() },
                modifier = Modifier.weight(1f),
            )
            Mode.HELP -> Help(playback != null, Modifier.weight(1f))
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
    onExport: (ExportFormat) -> Unit,
    onImport: () -> Unit,
    transfer: TransferServer.State,
    onTransfer: (Boolean) -> Unit,
    modifier: Modifier,
) {
    var confirmDelete by remember { mutableStateOf<String?>(null) }
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
                        transfer.note != null -> transfer.note
                        else -> "Off. Switch on to download or upload scripts from a browser on your network."
                    },
                )
            },
            trailingContent = { Switch(checked = transfer.running, onCheckedChange = null) },
        )
        Text("Long-press a document to delete it.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.fillMaxSize()) {
            items(docs, key = { it.id }) { doc ->
                val confirming = confirmDelete == doc.id
                ListItem(
                    selected = doc.id == current,
                    onClick = { if (confirming) { onDelete(doc.id); confirmDelete = null } else onOpen(doc.id) },
                    onLongClick = { confirmDelete = doc.id },
                    headlineContent = { Text(if (confirming) "Select again to delete “${doc.title}”" else doc.title) },
                    supportingContent = { Text(format.format(Date(doc.modifiedMs))) },
                )
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
private class FountainHighlight(private val heading: Color, private val character: Color, private val muted: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val elements = Fountain.classify(text.text)
        val styled = buildAnnotatedString {
            append(text.text)
            var offset = 0
            for ((i, line) in text.text.split('\n').withIndex()) {
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
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }
}

enum class ExportFormat(val extension: String, val mime: String) {
    FOUNTAIN("fountain", "text/plain"),
    FADE_IN("fadein", "application/octet-stream"),
    ;

    fun bytes(text: String): ByteArray = when (this) {
        FOUNTAIN -> text.toByteArray()
        FADE_IN -> FadeIn.export(text)
    }
}

fun fileName(text: String, format: ExportFormat): String =
    WritingStore.titleOf(text).replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "Untitled" } + "." + format.extension

/** Fountain text from an imported file: `.fadein` ZIPs and OSF XML are converted, anything else is read as text. */
fun importText(bytes: ByteArray): String {
    val head = bytes.take(64).toByteArray().decodeToString().trimStart()
    val isZip = bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
    return if (isZip || head.startsWith("<?xml") || head.startsWith("<document")) FadeIn.import(bytes) else bytes.decodeToString()
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

/** This TV's IPv4 address on the active network (Wi-Fi or Ethernet), for the transfer page link. */
private fun lanAddress(context: android.content.Context): String? {
    val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return null
    val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
    return props.linkAddresses.map { it.address }
        .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }?.hostAddress
}
