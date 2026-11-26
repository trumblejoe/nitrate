package dev.nitrate.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.nitrate.android.AppContainer
import dev.nitrate.core.companion.ChatKind
import dev.nitrate.core.companion.EmojiGroup
import dev.nitrate.core.companion.Emojis
import dev.nitrate.core.companion.QuickPicks
import dev.nitrate.core.companion.QuickReplies
import dev.nitrate.core.companion.TimelineChat
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.partners.WithChip
import dev.nitrate.tv.ui.components.TvTextField
import dev.nitrate.tv.ui.together.NoteLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A page with written lines: the strip's button for the chat so far. */
internal val ChatSoFarIcon: ImageVector = ImageVector.Builder("ChatSoFar", 24.dp, 24.dp, 24f, 24f).addPath(
    addPathNodes("M14,2H6C4.9,2 4,2.9 4,4v16c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V8l-6,-6zM16,18H8v-2h8v2zM16,14H8v-2h8v2zM13,9V3.5L18.5,9H13z"),
    fill = SolidColor(Color.White),
).build()

/** The chat strip wired to the app (see [ChatStrip]). */
@Composable
internal fun PlayerChatStrip(
    container: AppContainer?,
    chip: WithChip?,
    item: MediaItem?,
    chat: TimelineChat,
    chatTitle: String?,
    onTurn: (Boolean) -> Unit,
    /** Where this player is now: what's sent is pinned here. */
    positionMs: () -> Long,
    log: Boolean,
    onLog: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The title's quick replies (the usual ones until its own are worked out: an episode's show is asked for once).
    val picks by produceState(QuickReplies.forTitle(item), item?.id) {
        if (item != null && container != null) value = container.quickPicks(item)
    }
    ChatStrip(
        chat = chat,
        who = chip?.group?.let(WithChip::nameOf) ?: chatTitle,
        positionMs = positionMs,
        picks = picks,
        onTurn = onTurn,
        log = log,
        onLog = onLog,
        modifier = modifier,
    )
}

/**
 * The player's chat, a strip across the bottom with the video above (the Chat button opens it, and
 * it stays until Back): which conversation this is ("Group chat · Will", "Replaying 2026/10/02 ·
 * Will"), the emoji button (focus starts there; OK opens the full emoji list above the strip,
 * [Emojis.groupsFor], and picking one sends it and closes the list), the title's quick lines,
 * Turn off (for this playback) and the page button that shows the chat so far above the strip
 * (hidden at first: what's said pops up anyway); under them the text field. Which conversation is
 * chosen before playing (the details page); here it's talked in. Back closes the emoji list, then
 * the chat so far, then (left to the player) the strip.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ChatStrip(
    chat: TimelineChat,
    /** Who it's with ("Will", "Tommy & Suzy"), for the line. */
    who: String?,
    positionMs: () -> Long = { 0L },
    picks: QuickPicks = QuickPicks.PLAIN,
    onTurn: (Boolean) -> Unit = {},
    /** The chat so far showing above the strip. */
    log: Boolean = false,
    onLog: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    /** The device's offset from UTC (minutes) at a moment, for a replay's date. */
    offsetAt: (Long) -> Int = { java.util.TimeZone.getDefault().getOffset(it) / 60_000 },
) {
    val on by chat.on.collectAsState()
    val unread by chat.unread.collectAsState()
    val replaying by chat.replaying.collectAsState()
    val threads by chat.threads.collectAsState()
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }
    var returnTo by remember { mutableStateOf<FocusRequester?>(null) }
    var draft by remember { mutableStateOf("") }
    var sendProblem by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    val turnButton = remember { FocusRequester() }
    val pickerFirst = remember { FocusRequester() }
    LaunchedEffect(Unit) { chat.opened() }
    // Open: what pops up meanwhile is seen.
    LaunchedEffect(unread) { if (unread > 0) chat.read() }
    fun send(text: String, kind: ChatKind) {
        scope.launch {
            chat.send(text, kind, positionMs()).onSuccess { if (kind == ChatKind.TEXT) draft = ""; sendProblem = null }
                .onFailure { sendProblem = it.message ?: "Couldn't send" }
        }
    }
    Box(
        modifier.fillMaxSize().onKeyEvent { e ->
            if (e.key != Key.Back && e.key != Key.Escape) return@onKeyEvent false
            when {
                picking -> { if (e.type == KeyEventType.KeyUp) picking = false; true }
                log -> { if (e.type == KeyEventType.KeyUp) onLog(false); true }
                else -> false
            }
        },
    ) {
        Column(
            // Focus stays in the strip.
            Modifier.align(Alignment.BottomStart).fillMaxWidth().focusProperties { exit = { FocusRequester.Cancel } }.focusGroup(),
        ) {
            if (on && (picking || log)) Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp).padding(bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
                if (picking) EmojiList(Emojis.groupsFor(picks), pickerFirst) { emoji -> send(emoji, ChatKind.REACTION); picking = false }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                if (log) ChatSoFar(chat, Modifier)
            }
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)).padding(horizontal = 48.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Which conversation this is (chosen before playing), and Turn off for this playback.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (on) dev.nitrate.core.companion.Threads.line(replaying, threads, who, offsetAt)
                        else "Group chat is off: nothing pops up, and nothing is sent.",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    if (on) {
                        OutlinedButton(onClick = { returnTo = turnButton; onTurn(false) }, modifier = Modifier.focusRequester(turnButton)) { Text("Turn off") }
                    } else {
                        Button(onClick = { returnTo = null; onTurn(true) }, modifier = Modifier.focusRequester(first)) { Text("Turn on") }
                    }
                }
                if (on) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // One emoji button opens the whole list, as chat apps do.
                    OutlinedButton(
                        onClick = { returnTo = first; picking = !picking },
                        modifier = Modifier.focusRequester(first).semantics(mergeDescendants = true) { contentDescription = "Emoji" },
                    ) { Text("🙂", fontSize = 22.sp) }
                    // Its characters' names and short lines, one press sending each as a message.
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).focusGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        picks.lines.forEach { line -> OutlinedButton(onClick = { send(line, ChatKind.TEXT) }) { Text(line, maxLines = 1) } }
                    }
                    IconButton(onClick = { onLog(!log) }) {
                        Icon(ChatSoFarIcon, contentDescription = if (log) "Hide the chat so far" else "Show the chat so far", tint = LocalContentColor.current)
                    }
                }
                if (on) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvTextField(
                        draft, { draft = it.take(500) }, "Type a message",
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (draft.isNotBlank()) send(draft, ChatKind.TEXT) }),
                    )
                    Button(onClick = { send(draft, ChatKind.TEXT) }, enabled = draft.isNotBlank()) { Text("Send") }
                }
                sendProblem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    // Opens on the emoji button (once more a moment later: focus may still be on its way from the
    // button); the emoji list opens on its first emoji, and closing it returns focus to its button.
    // Turned on or off, focus goes to what's there now (the emoji button, or Turn on).
    LaunchedEffect(picking, on) {
        delay(50)
        runCatching { (if (picking) pickerFirst else returnTo?.takeIf { on } ?: first).requestFocus() }
        if (picking || returnTo != null) return@LaunchedEffect
        delay(300)
        runCatching { first.requestFocus() }
    }
}

/** The full emoji list above the strip, by heading ("For this" first); OK sends one. */
@Composable
private fun EmojiList(groups: List<EmojiGroup>, first: FocusRequester, onPick: (String) -> Unit) {
    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
        androidx.compose.foundation.lazy.grid.GridCells.Fixed(10),
        Modifier.width(640.dp).heightIn(max = 300.dp).background(Color(0xE6101014), RoundedCornerShape(16.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        groups.forEachIndexed { g, group ->
            item(key = "h-${group.name}", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Text(group.name, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.75f), modifier = Modifier.padding(top = 4.dp, start = 4.dp))
            }
            group.emojis.forEachIndexed { i, emoji ->
                item(key = "e-$emoji") {
                    OutlinedButton(
                        onClick = { onPick(emoji) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp),
                        modifier = if (g == 0 && i == 0) Modifier.focusRequester(first) else Modifier,
                    ) { Text(emoji, fontSize = 22.sp) }
                }
            }
        }
    }
}

/** The chat so far (up to here, never further on), newest at the bottom, above the strip; "Show what I missed" when there is some. */
@Composable
private fun ChatSoFar(chat: TimelineChat, modifier: Modifier) {
    val history by chat.history.collectAsState()
    val missed by chat.missed.collectAsState()
    val list = rememberLazyListState()
    LaunchedEffect(history.size) { if (history.isNotEmpty()) list.scrollToItem(history.size - 1) }
    Column(
        modifier.width(460.dp).heightIn(max = 260.dp).background(Color(0xE6101014), RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("The chat so far", style = MaterialTheme.typography.titleSmall, color = Color.White)
        if (missed.isNotEmpty()) OutlinedButton(onClick = { chat.showMissed() }) { Text("Show what I missed (${missed.size})") }
        LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (history.isEmpty()) item {
                Text("Nothing yet up to here. A reaction or a message stays at this point for your group.", color = Color.White.copy(alpha = 0.7f))
            }
            items(history, key = { it.id }) { NoteLine(it) }
        }
    }
}
