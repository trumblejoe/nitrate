package dev.nitrate.mobile.ui

import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import dev.nitrate.android.playback.ChatChoices
import dev.nitrate.core.companion.ChatPick
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.StreamKind
import dev.nitrate.core.playback.TrackSetup
import dev.nitrate.core.playback.audioChoices
import dev.nitrate.core.playback.subtitleChoices
import kotlinx.coroutines.launch

/**
 * "Audio: Japanese · 5.1" and "Subtitles: English": what [item] will start with (the pick for its
 * show or itself, else the server's picks), each opening the player's track list. A pick is
 * remembered like one made while playing, so Play and the next episode follow it.
 */
@Composable
fun TrackLine(actions: AppActions, item: MediaItem, modifier: Modifier = Modifier, chat: dev.nitrate.android.playback.DetailsChat? = null) {
    val scope = rememberCoroutineScope()
    var setup by remember(item.id) { mutableStateOf<TrackSetup?>(null) }
    LaunchedEffect(item.id) { setup = runCatching { actions.container.trackSetup(item) }.getOrNull() }
    val s = setup?.takeIf { it.hasChoices }
    // Chat (a title in one of the user's watch groups): Off, On or an older thread replayed too, for the playback Play starts here only.
    val chatFor = item.takeIf { it.isPlayable && chat?.choosable?.invoke(it) == true }
    val picks by dev.nitrate.android.playback.ChatChoices.picks.collectAsState()
    // Until picked here, the user's own setting (Settings, Group chat).
    val chatDefault = ChatPick.default(chat?.chatOn?.invoke(LocalDisplayPrefs.current) ?: true)
    val chatPick = chatFor?.let { picks[it.id] } ?: chatDefault
    var threads by remember(chatFor?.id) { mutableStateOf<List<dev.nitrate.core.companion.ThreadGroup>?>(null) }
    val offsetAt = { ms: Long -> (dev.nitrate.mobile.platform.timeZoneOffsetMsAt(ms) / 60_000).toInt() }
    if (s == null && chatFor == null) return
    var picking by remember { mutableStateOf<StreamKind?>(null) }
    var choosingChat by remember { mutableStateOf(false) }
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (s != null) {
            TrackPill("Audio", s.audioName) { picking = StreamKind.AUDIO }
            TrackPill("Subtitles", s.subtitleName) { picking = StreamKind.SUBTITLE }
        }
        if (chatFor != null) TrackPill("Chat", ChatPick.label(chatPick, threads.orEmpty(), offsetAt)) { choosingChat = true }
    }
    if (s != null) picking?.let { kind ->
        val audio = kind == StreamKind.AUDIO
        TrackPicker(
            if (audio) "Audio" else "Subtitles",
            if (audio) s.source.audioChoices() else s.source.subtitleChoices(),
            if (audio) s.audioIndex else s.subtitleIndex,
            onSelect = { index ->
                picking = null
                scope.launch { setup = actions.container.pickTrack(item, s, kind, index) }
            },
        ) { picking = null }
    }
    if (choosingChat && chatFor != null && chat != null) {
        LaunchedEffect(chatFor.id) { if (threads == null) threads = chat.threads(chatFor) }
        ChatPicker(
            chatPick, threads, offsetAt,
            onPick = { p -> ChatChoices.choose(chatFor.id, p); choosingChat = false },
            onFavourite = { t -> scope.launch { runCatching { chat.favourite(chatFor, t.id, !t.favourite) }.onSuccess { threads = chat.threads(chatFor) } } },
            onStart = { g -> scope.launch { runCatching { chat.startThread(chatFor, g.groupId) }.onSuccess { threads = chat.threads(chatFor) } } },
        ) { choosingChat = false }
    }
}

/**
 * The details page's Chat choice: Off, On, or an older thread replayed too (each with a heart; null:
 * still looking for them), and the group's creator's Start a new thread (asked first).
 */
@Composable
private fun ChatPicker(
    chosen: ChatPick,
    groups: List<dev.nitrate.core.companion.ThreadGroup>?,
    offsetAt: (Long) -> Int,
    onPick: (ChatPick) -> Unit,
    onFavourite: (dev.nitrate.core.companion.ChatThread) -> Unit,
    onStart: (dev.nitrate.core.companion.ThreadGroup) -> Unit,
    onDismiss: () -> Unit,
) {
    var asking by remember { mutableStateOf<dev.nitrate.core.companion.ThreadGroup?>(null) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chat") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn {
                item { Text("For this playback only. Your setting: Settings, Group chat.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { ChatChoice("Off", "Nothing pops up; turn it on in the player any time", chosen == ChatPick.Off) { onPick(ChatPick.Off) } }
                item { ChatChoice("On", "The group's conversation pops up as you reach it", chosen == ChatPick.On) { onPick(ChatPick.On) } }
                if (groups == null) item { Text("Looking for earlier threads…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                groups.orEmpty().forEach { g ->
                    if (g.older.isNotEmpty()) item(key = "h-${g.groupId}") {
                        Text("Replay an older thread with ${g.title}: its messages pop up too", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    }
                    items(g.older.size, key = { g.older[it].id }) { i ->
                        val t = g.older[i]
                        val p = ChatPick.Replay(t.id)
                        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                                ChatChoice("Replay " + dev.nitrate.core.companion.Threads.date(t.startedMs, offsetAt(t.startedMs)), dev.nitrate.core.companion.Threads.detail(t), chosen == p) { onPick(p) }
                            }
                            // The heart keeps a thread: a favourite is never deleted by the server's time rules.
                            androidx.compose.material3.IconButton(onClick = { onFavourite(t) }) {
                                androidx.compose.material3.Icon(
                                    if (t.favourite) androidx.compose.material.icons.Icons.Filled.Favorite else androidx.compose.material.icons.Icons.Filled.FavoriteBorder,
                                    dev.nitrate.core.companion.Threads.heart(t),
                                    tint = if (t.favourite) accentColor() else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (g.canStart && g.current != null) item(key = "new-${g.groupId}") {
                        androidx.compose.material3.TextButton(onClick = { asking = g }) { Text("Start a new thread with ${g.title}") }
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Close") } },
    )
    asking?.let { g ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text("Start a new thread with ${g.title}?") },
            text = { Text("Everyone's messages from now go into a new conversation. This one is kept, to replay.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { asking = null; onStart(g) }) { Text("Start it") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { asking = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ChatChoice(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.ListItem(
        modifier = Modifier.selectable(selected = selected, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = onClick),
        leadingContent = { androidx.compose.material3.RadioButton(selected = selected, onClick = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
    )
}

@Composable
fun TrackPill(label: String, value: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.10f)).pressable(onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = accentColor())
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Color.White)
    }
}
