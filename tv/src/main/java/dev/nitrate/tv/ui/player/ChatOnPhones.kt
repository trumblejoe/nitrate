package dev.nitrate.tv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import dev.nitrate.android.remote.RemoteControlHub
import dev.nitrate.core.companion.ChatKind
import dev.nitrate.core.companion.QuickPicks
import dev.nitrate.core.companion.TimelineChat
import dev.nitrate.core.remote.RemoteChatDo
import dev.nitrate.core.remote.RemoteChatState
import dev.nitrate.core.remote.RemotePrompt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * This playback's chat on the user's phones: their remote shows what it shows (told on each change,
 * and again every [RemotePrompt.REPEAT_MS]) and hands back what to do, which is done here, as this
 * TV: a message or reaction pinned at this player's position in the group's conversation, Group
 * chat on or off for this playback. The phones never write to the server themselves.
 */
@Composable
internal fun ChatOnPhones(
    remote: RemoteControlHub?,
    deviceId: String?,
    itemId: String,
    chat: TimelineChat,
    who: String?,
    picks: QuickPicks,
    positionMs: () -> Long,
    onTurn: (Boolean) -> Unit,
) {
    if (remote == null || deviceId == null) return
    val currentWho by rememberUpdatedState(who)
    val currentPicks by rememberUpdatedState(picks)
    val turn by rememberUpdatedState(onTurn)
    val position by rememberUpdatedState(positionMs)
    val offsetAt = { ms: Long -> java.util.TimeZone.getDefault().getOffset(ms) / 60_000 }
    LaunchedEffect(itemId, chat) {
        launch {
            remote.chatDos.collect { d ->
                when (d.kind) {
                    RemoteChatDo.Kind.TEXT -> d.text?.trim()?.takeIf { it.isNotEmpty() }?.let { chat.send(it.take(500), ChatKind.TEXT, position()) }
                    RemoteChatDo.Kind.REACTION -> d.text?.takeIf { it.isNotEmpty() }?.let { chat.send(it.take(16), ChatKind.REACTION, position()) }
                    RemoteChatDo.Kind.TURN -> d.on?.let(turn)
                }
            }
        }
        var last: RemoteChatState? = null
        var lastAt = 0L
        while (true) {
            val now = System.currentTimeMillis()
            val state = RemoteChatState.of(
                deviceId, itemId, chat.on.value, currentWho, chat.replaying.value, chat.threads.value,
                chat.history.value, currentPicks.emojis, currentPicks.lines, offsetAt,
            )
            // Told at once when something changed (a phone's own message comes back this way), else now and then.
            if (state != last || now - lastAt >= RemotePrompt.REPEAT_MS) {
                runCatching { remote.tellChat(state) }
                last = state
                lastAt = now
            }
            delay(1_000)
        }
    }
    DisposableEffect(itemId, chat) { onDispose { remote.chatGoneSoon() } }
}
