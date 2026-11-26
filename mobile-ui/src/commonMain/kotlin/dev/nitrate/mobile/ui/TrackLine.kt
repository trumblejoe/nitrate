package dev.nitrate.mobile.ui

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
    // Chat (a title in one of the user's watch groups): Off, New chat or an earlier chat to replay, for the playback Play starts here only.
    val chatFor = item.takeIf { it.isPlayable && chat?.choosable?.invoke(it) == true }
    val picks by dev.nitrate.android.playback.ChatChoices.picks.collectAsState()
    val chatPick = chatFor?.let { picks[it.id] } ?: ChatPick.Off
    var chats by remember(chatFor?.id) { mutableStateOf<List<dev.nitrate.core.companion.NoteChat>?>(null) }
    val offsetAt = { ms: Long -> (dev.nitrate.mobile.platform.timeZoneOffsetMsAt(ms) / 60_000).toInt() }
    if (s == null && chatFor == null) return
    var picking by remember { mutableStateOf<StreamKind?>(null) }
    var choosingChat by remember { mutableStateOf(false) }
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (s != null) {
            TrackPill("Audio", s.audioName) { picking = StreamKind.AUDIO }
            TrackPill("Subtitles", s.subtitleName) { picking = StreamKind.SUBTITLE }
        }
        if (chatFor != null) TrackPill("Chat", ChatPick.label(chatPick, chats.orEmpty(), offsetAt)) { choosingChat = true }
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
    if (choosingChat && chatFor != null) {
        LaunchedEffect(chatFor.id) { if (chats == null) chats = chat?.replayable?.invoke(chatFor).orEmpty() }
        ChatPicker(chatPick, chats, offsetAt, onPick = { p -> ChatChoices.choose(chatFor.id, p); choosingChat = false }) { choosingChat = false }
    }
}

/** The details page's Chat choice: Off, New chat, or an earlier chat to replay (null: still looking for them). */
@Composable
private fun ChatPicker(
    chosen: ChatPick,
    chats: List<dev.nitrate.core.companion.NoteChat>?,
    offsetAt: (Long) -> Int,
    onPick: (ChatPick) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chat") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn {
                item { Text("For this playback only.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { ChatChoice("Off", "Nothing pops up; turn it on in the player any time", chosen == ChatPick.Off) { onPick(ChatPick.Off) } }
                item { ChatChoice(dev.nitrate.core.companion.NoteChats.NEW_CHAT, "Nothing earlier pops up", chosen == ChatPick.New) { onPick(ChatPick.New) } }
                if (chats == null) item { Text("Looking for earlier chats…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else if (chats.isNotEmpty()) {
                    item { Text("Replay an earlier chat: its messages pop up as you reach them.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
                    items(chats.size, key = { chats[it].id }) { i ->
                        val c = chats[i]
                        val p = ChatPick.Replay(c.id)
                        ChatChoice(dev.nitrate.core.companion.NoteChats.title(c, chats, offsetAt), dev.nitrate.core.companion.NoteChats.detail(c, chats, offsetAt), chosen == p) { onPick(p) }
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Close") } },
    )
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
