package dev.nitrate.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.media3.common.Player
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.nitrate.android.AppContainer
import dev.nitrate.android.partners.SessionPlayback
import dev.nitrate.core.partners.SessionNotice

/**
 * This player's part in the device's Group Watch Session, while it plays the session's part:
 * held at the start until the countdown ends, reporting where it is, the others' pauses and jumps
 * offered as notices (null outside a session, in recap clips and test plays). Leaving the player
 * (or moving on to another item) leaves the session.
 */
@Composable
internal fun rememberSessionPlayback(container: AppContainer?, itemId: String?, player: Player, enabled: Boolean): SessionPlayback? {
    if (container == null || !enabled || itemId == null) return null
    val current by container.groupSessions.current.collectAsState()
    val inIt = current?.itemId == itemId
    val scope = rememberCoroutineScope()
    val playback = remember(itemId, inIt) {
        if (!inIt) null else SessionPlayback(
            container.groupSessions, scope, itemId, System::currentTimeMillis,
            position = { player.currentPosition }, paused = { !player.playWhenReady },
            play = { player.play() }, pause = { player.pause() }, seekTo = { player.seekTo(it) },
        )
    }
    DisposableEffect(playback) {
        playback?.begin()
        onDispose { playback?.close() }
    }
    return playback
}

/**
 * The countdown before a Group Watch Session starts ("Starting together in 3"), or while it gathers
 * "Waiting for Suzy to join" (the starter's Start now is a button in the controls). Never focusable.
 */
@Composable
fun SessionCountdown(seconds: Int?, waiting: String?, starter: Boolean, modifier: Modifier = Modifier) {
    if (seconds == null && waiting == null) return
    Column(
        modifier.widthIn(max = 760.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xE6101216)).padding(horizontal = 48.dp, vertical = 28.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Group Watch Session", style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.8f))
        if (seconds != null) {
            Text("Starting together in", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text("$seconds", fontSize = 96.sp, fontWeight = FontWeight.Bold, color = Color.White)
        } else {
            Text(waiting!!, style = MaterialTheme.typography.headlineSmall, color = Color.White, textAlign = TextAlign.Center)
            Text(
                if (starter) "Everyone who joins in the first minute starts together. Start now is in the controls."
                else "Everyone who joins in the first minute starts together.",
                style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * What the others in the session did ("Sam paused", "12 s behind Sam"), over the video, top
 * right. It never takes focus: its action ("Pause too", "Catch up") is a button in the controls,
 * which OK brings up; [controlsUp] says so in the note.
 */
@Composable
fun SessionNoticeBanner(notice: SessionNotice?, controlsUp: Boolean, modifier: Modifier = Modifier) {
    val n = notice ?: return
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xE6101216)).padding(horizontal = 24.dp, vertical = 14.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(n.text, style = MaterialTheme.typography.titleMedium, color = Color.White)
        n.action?.let { a ->
            Text(
                if (controlsUp) "$a: in the controls below" else "$a: press OK, then $a",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** The key of the notice's button in the controls (its place is kept as the label changes). */
internal const val SESSION_KEY = "session"
