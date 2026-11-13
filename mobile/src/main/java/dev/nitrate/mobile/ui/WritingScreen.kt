package dev.nitrate.mobile.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.nitrate.android.writing.lanAddress
import dev.nitrate.core.writing.TransferServer
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
    val context = LocalContext.current

    // Computer transfer: off unless switched on, and off again when the editor closes or the
    // app goes to the background.
    val transfer = remember(store) { TransferServer(store.asTransferDocs()) }
    val transferState by transfer.state.collectAsState()
    DisposableEffect(transfer) { onDispose { transfer.stop() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (transfer.state.value.running) transfer.stop("Turned off when Nitrate went to the background.")
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
        value = TextFieldValue(text.replaceRange(start, end, converted), TextRange(start + converted.length))
    }

    val colors = MaterialTheme.colorScheme
    val highlight = remember(colors) { FountainHighlight(colors.primary, colors.secondary, colors.onSurfaceVariant) }

    if (showTransfer) {
        TransferDialog(transferState, onToggle = { on -> if (on) transfer.start(lanAddress(context)) else transfer.stop() }, onDismiss = { showTransfer = false })
    }
    Column(modifier.fillMaxSize().imePadding()) {
        if (!pane) Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = if (pane) 4.dp else 0.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(if (pane) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack, if (pane) "Close writing" else "Back") }
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(Scripts.titleOf(value.text), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "%d words · ~%.1f pages%s".format(Fountain.wordCount(value.text), Fountain.estimatePages(value.text), if (status.isNotEmpty()) " · $status" else ""),
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
                DropdownMenuItem(text = { Text("Export .fadein (Fade In)") }, onClick = {
                    menu = false
                    pickers.save(Scripts.fileName(value.text, ExportFormat.FADE_IN), ExportFormat.FADE_IN.mime, ExportFormat.FADE_IN.bytes(value.text))
                })
                DropdownMenuItem(text = { Text("Export .fountain") }, onClick = {
                    menu = false
                    pickers.save(Scripts.fileName(value.text, ExportFormat.FOUNTAIN), ExportFormat.FOUNTAIN.mime, ExportFormat.FOUNTAIN.bytes(value.text))
                })
                DropdownMenuItem(text = { Text("Import .fadein or .fountain") }, onClick = { menu = false; pickers.open() })
                DropdownMenuItem(
                    text = { Text(if (transferState.running) "Transfer to a computer (on)" else "Transfer to a computer") },
                    onClick = { menu = false; showTransfer = true },
                )
            }
        }

        if (showDocs) {
            LazyColumn(Modifier.weight(1f)) {
                items(docs, key = { it.id }) { doc ->
                    var confirm by remember { mutableStateOf(false) }
                    ListItem(
                        modifier = Modifier.clickable { open(doc.id) },
                        headlineContent = { Text(doc.title) },
                        supportingContent = { Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(doc.modifiedMs))) },
                        trailingContent = {
                            if (confirm) {
                                TextButton(onClick = {
                                    scope.launch {
                                        store.delete(doc.id, settings.backupToServer)
                                        if (doc.id == docId) open(store.docs.value.firstOrNull()?.id ?: store.create())
                                    }
                                }) { Text("Delete", color = colors.error) }
                            } else {
                                IconButton(onClick = { confirm = true }) { Icon(Icons.Default.Delete, "Delete") }
                            }
                        },
                    )
                }
            }
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ELEMENTS) { (label, element) -> AssistChip(onClick = { convert(element) }, label = { Text(label) }) }
            }
            BasicTextField(
                value = value,
                onValueChange = { value = it },
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

/** Switches the computer transfer page on or off and shows where to find it. */
@Composable
internal fun TransferDialog(state: TransferServer.State, onToggle: (Boolean) -> Unit, onDismiss: () -> Unit) {
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
