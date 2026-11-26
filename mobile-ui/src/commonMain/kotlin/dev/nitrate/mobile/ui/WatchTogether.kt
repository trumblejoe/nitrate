package dev.nitrate.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nitrate.android.AppContainer
import dev.nitrate.android.partners.SessionPlayback
import dev.nitrate.core.partners.SessionChoice
import dev.nitrate.core.partners.SessionNotice
import dev.nitrate.core.partners.SessionPrompt
import kotlinx.coroutines.launch

/**
 * "Tommy started a Group Watch Session · S1:E4 of The Bear": someone in a group started a Group Watch
 * Session. Join (late: "Join at 12:30", "Start from the beginning") opens the player, which waits
 * for the countdown; Not now. Over the player too ([inPlayer]): it plays on meanwhile, and Join
 * closes it (its place kept) for the session's part.
 */
@Composable
fun SessionJoinPrompt(actions: AppActions, inPlayer: Boolean) {
    val container = actions.container
    val asking by container.groupSessions.asking.collectAsState()
    val scope = rememberCoroutineScope()
    var problem by remember { mutableStateOf<String?>(null) }
    val s = asking ?: return
    val prompt = SessionPrompt.of(s, container.groupSessions.serverNow(), LocalDisplayPrefs.current.easyMode) ?: return
    SessionJoinDialog(prompt, problem, onDismiss = { container.groupSessions.dismiss() }) { c ->
        scope.launch { problem = container.groupSessions.choose(s, c) }
    }
}

/** The join prompt: who started it and what, then Join, the beginning for a late joiner, Not now. */
@Composable
fun SessionJoinDialog(prompt: SessionPrompt, problem: String? = null, onDismiss: () -> Unit, onChoose: (SessionChoice) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(prompt.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(prompt.text)
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                prompt.choices.reversed().forEach { c -> TextButton(onClick = { onChoose(c) }) { Text(c.label) } }
            }
        },
    )
}

/**
 * This player's part in the device's Group Watch Session, while it plays the session's part:
 * held at the start until the countdown ends, reporting where it is, the others' pauses and jumps
 * offered as notices. Null outside a session. Leaving the player (or moving on) leaves the session.
 */
@Composable
fun rememberSessionPlayback(
    container: AppContainer,
    itemId: String?,
    enabled: Boolean,
    position: () -> Long,
    paused: () -> Boolean,
    play: () -> Unit,
    pause: () -> Unit,
    seekTo: (Long) -> Unit,
): SessionPlayback? {
    if (!enabled || itemId == null) return null
    val current by container.groupSessions.current.collectAsState()
    val inIt = current?.itemId == itemId
    val scope = rememberCoroutineScope()
    val playback = remember(itemId, inIt) {
        if (!inIt) null else SessionPlayback(container.groupSessions, scope, itemId, { dev.nitrate.android.nowMs() }, position, paused, play, pause, seekTo)
    }
    DisposableEffect(playback) {
        playback?.begin()
        onDispose { playback?.close() }
    }
    return playback
}

/**
 * The session over the video: the countdown in the middle ("Starting together in 3", or while it
 * gathers "Waiting for Suzy to join" with the starter's Start now), and the others' news at the
 * top ("Sam paused · Pause too", "12 s behind Sam · Catch up") with its action as a button.
 */
@Composable
fun SessionOverlay(playback: SessionPlayback?, container: AppContainer, modifier: Modifier = Modifier) {
    val p = playback ?: return
    val notice by p.notice.collectAsState()
    val countdown by p.countdown.collectAsState()
    val waiting by p.waiting.collectAsState()
    val session by p.session.collectAsState()
    val groups by container.partners.collectAsState()
    androidx.compose.foundation.layout.Box(modifier) {
        SessionCountdown(
            countdown,
            if (waiting) session?.let { s -> SessionPrompt.waiting(s, groups?.groups?.firstOrNull { it.id == s.groupId }) } else null,
            onStartNow = if (waiting && session?.startedByMe == true) p::startNow else null,
            modifier = Modifier.align(Alignment.Center),
        )
        SessionNoticeCard(notice, onAct = { p.act() }, onDismiss = { p.dismiss() }, modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp, start = 16.dp, end = 16.dp))
    }
}

/** "Starting together in 3", or "Waiting for Suzy to join" (with Start now for whoever started it). */
@Composable
fun SessionCountdown(seconds: Int?, waiting: String?, onStartNow: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    if (seconds == null && waiting == null) return
    Column(
        modifier.widthIn(max = 420.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xE6101216)).padding(horizontal = 28.dp, vertical = 20.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Group Watch Session", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.8f))
        if (seconds != null) {
            Text("Starting together in", style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text("$seconds", fontSize = 64.sp, fontWeight = FontWeight.Bold, color = Color.White)
        } else {
            Text(waiting!!, style = MaterialTheme.typography.titleMedium, color = Color.White, textAlign = TextAlign.Center)
            Text("Everyone who joins in the first minute starts together.", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center)
            onStartNow?.let { TextButton(onClick = it) { Text("Start now") } }
        }
    }
}

/** What the others did, with its one action ("Pause too", "Jump too", "Catch up"); never done by itself. */
@Composable
fun SessionNoticeCard(notice: SessionNotice?, onAct: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val n = notice ?: return
    Row(
        modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xE6101216)).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(n.text, style = MaterialTheme.typography.bodyMedium, color = Color.White, modifier = Modifier.padding(vertical = 10.dp))
        n.action?.let { a -> TextButton(onClick = onAct) { Text(a) } }
        TextButton(onClick = onDismiss) { Text(if (n.action == null) "OK" else "Not now", color = Color.White.copy(alpha = 0.8f)) }
    }
}

/**
 * A watch-together invite from an older Nitrate app (checked every two minutes while the app is open, and as it comes back into view),
 * kept for compatibility: Join puts this device in that session (a Group Watch Session's, else the
 * older app's SyncPlay group), Not now turns it down. Nitrate's own Group Watch Session sends no
 * invites: the group's apps are told at once ([SessionJoinPrompt]).
 */
@Composable
fun WatchInvitePrompt(actions: AppActions) {
    val container = actions.container
    val companion by container.companion.collectAsState()
    val shown by container.appShown.collectAsState()
    androidx.compose.runtime.LaunchedEffect(companion, shown) {
        while (true) {
            container.refreshInvites()
            kotlinx.coroutines.delay(dev.nitrate.core.partners.WatchInvite.CHECK_MS)
        }
    }
    val invites by container.watchInvites.collectAsState()
    val scope = rememberCoroutineScope()
    val invite = invites.firstOrNull() ?: return
    AlertDialog(
        onDismissRequest = { scope.launch { container.dismissInvite(invite) } },
        title = { Text("Watch with them?") },
        text = { Text(invite.text + ". Joining puts you in their session: what they play, plays for you.") },
        confirmButton = { TextButton(onClick = { scope.launch { runCatching { container.joinInvite(invite) } } }) { Text("Join") } },
        dismissButton = { TextButton(onClick = { scope.launch { container.dismissInvite(invite) } }) { Text("Not now") } },
    )
}

/**
 * Asks to watch together on the phone: someone's ask ("Grandma would like to watch together ·
 * S1:E4 of The Bear · now", Start it / Not now), the reminder when a later one this user took is due,
 * what the user's own asks came to (said in passing), and starting one here as host (the session,
 * then its part plays through the guards). Questions wait while the player is open.
 */
@Composable
fun WatchAskPrompt(actions: AppActions, inPlayer: Boolean) {
    val container = actions.container
    val asks = container.groupAsks
    val asking by asks.asking.collectAsState()
    val due by asks.due.collectAsState()
    val scope = rememberCoroutineScope()
    var problem by remember { mutableStateOf<String?>(null) }
    val platform = dev.nitrate.mobile.platform.rememberPlatformActions()
    LaunchedEffect(asks) { asks.toAsker.collect { platform.toast(it) } }
    LaunchedEffect(asks) {
        asks.starts.collect { a ->
            val (item, error) = container.startAsked(a)
            if (item != null) actions.play(item, false) else problem = error
        }
    }
    problem?.takeIf { asking == null && due == null }?.let { p ->
        AlertDialog(
            onDismissRequest = { problem = null },
            title = { Text("Couldn't start the Group Watch Session") },
            text = { Text(p) },
            confirmButton = { TextButton(onClick = { problem = null }) { Text("OK") } },
        )
    }
    if (inPlayer) return
    due?.let { a ->
        val (title, text) = dev.nitrate.core.partners.AskPrompt.due(a)
        AlertDialog(
            onDismissRequest = { asks.startDue(false) },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { asks.startDue(true) }) { Text("Start it now") } },
            dismissButton = { TextButton(onClick = { asks.startDue(false) }) { Text("Not now") } },
        )
        return
    }
    val a = asking ?: return
    WatchAskDialog(a, problem, onStart = { scope.launch { problem = asks.take(a) } }, onNotNow = { asks.decline(a) })
}

/** Someone's ask to watch together: who, what and when, then "Start it" (this user hosts it) or Not now. */
@Composable
fun WatchAskDialog(ask: dev.nitrate.core.partners.WatchAsk, problem: String? = null, onStart: () -> Unit, onNotNow: () -> Unit) {
    val clock = { ms: Long -> dev.nitrate.mobile.platform.formatTime(ms) }
    val (title, text) = dev.nitrate.core.partners.AskPrompt.question(ask, clock)
    AlertDialog(
        onDismissRequest = onNotNow,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text)
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = onStart) { Text(dev.nitrate.core.partners.AskPrompt.startLabel(ask, clock)) } },
        dismissButton = { TextButton(onClick = onNotNow) { Text("Not now") } },
    )
}
