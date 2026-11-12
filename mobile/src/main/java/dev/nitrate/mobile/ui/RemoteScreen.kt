package dev.nitrate.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.nitrate.core.admin.GeneralCommand
import dev.nitrate.core.admin.ServerSession
import dev.nitrate.core.admin.SessionCommand
import dev.nitrate.core.model.formatClock
import dev.nitrate.mobile.platform.rememberArtworkColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Controls another device's playback: play/pause, seek, skip, stop and volume. */
@Composable
fun RemoteScreen(actions: AppActions, sessionId: String) {
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val session by produceState<ServerSession?>(null, tick) {
        while (true) {
            value = runCatching { actions.server.sessions().firstOrNull { it.id == sessionId } }.getOrNull()
            delay(1_500)
        }
    }
    var seeking by remember { mutableStateOf<Float?>(null) }
    fun send(block: suspend () -> Unit) = scope.launch { runCatching { block() }; delay(300); tick++ }
    val np = session?.nowPlaying
    val tint by rememberArtworkColor(np?.let { actions.server.backdropUrl(it, 320) ?: actions.server.imageUrl(it) })

    TintedBackdrop(tint, strength = 0.45f) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(ScreenPadding), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { actions.nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Column(Modifier.weight(1f)) {
                    Text("Remote", style = MaterialTheme.typography.labelLarge, color = accentColor())
                    Text(session?.deviceName ?: "Device", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(24.dp))
            Box(Modifier.width(220.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant), Alignment.Center) {
                val art = np?.let { actions.server.cardImage(it, CardShape.POSTER, 600) }
                if (art != null) AsyncImage(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Text(if (session == null) "Not connected" else "Nothing playing", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(20.dp))
            Text(np?.displayTitle ?: "", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            np?.episodeLabel?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val duration = np?.runtimeMs ?: 0L
            val position = session?.positionMs ?: 0L
            if (np != null && duration > 0) {
                Slider(
                    value = seeking ?: (position.toFloat() / duration).coerceIn(0f, 1f),
                    onValueChange = { seeking = it },
                    onValueChangeFinished = {
                        val target = ((seeking ?: 0f) * duration).toLong()
                        seeking = null
                        send { actions.server.seekSession(sessionId, target) }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(formatClock(seeking?.let { (it * duration).toLong() } ?: position), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    Text(formatClock(duration), style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RemoteKey("⏮") { send { actions.server.sessionCommand(sessionId, SessionCommand.PREVIOUS) } }
                RemoteKey("−10") { send { actions.server.seekSession(sessionId, (position - 10_000).coerceAtLeast(0)) } }
                Box(
                    Modifier.size(76.dp).clip(CircleShape).background(accentColor()).pressable({ send { actions.server.sessionCommand(sessionId, SessionCommand.PLAY_PAUSE) } }),
                    Alignment.Center,
                ) {
                    if (session?.isPaused == false && np != null) Text("❚❚", color = contentOn(accentColor()), fontSize = 24.sp, fontWeight = FontWeight.Black)
                    else Icon(Icons.Default.PlayArrow, "Play", tint = contentOn(accentColor()), modifier = Modifier.size(40.dp))
                }
                RemoteKey("+30") { send { actions.server.seekSession(sessionId, position + 30_000) } }
                RemoteKey("⏭") { send { actions.server.sessionCommand(sessionId, SessionCommand.NEXT) } }
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RemoteKey("Vol −") { send { actions.server.sendGeneralCommand(sessionId, GeneralCommand.VOLUME_DOWN) } }
                RemoteKey("Mute") { send { actions.server.sendGeneralCommand(sessionId, GeneralCommand.TOGGLE_MUTE) } }
                RemoteKey("Vol +") { send { actions.server.sendGeneralCommand(sessionId, GeneralCommand.VOLUME_UP) } }
                RemoteKey("Stop") { send { actions.server.sessionCommand(sessionId, SessionCommand.STOP) } }
            }
        }
    }
}

@Composable
private fun RemoteKey(label: String, onClick: () -> Unit) {
    Box(
        Modifier.height(52.dp).width(64.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.1f)).pressable(onClick),
        Alignment.Center,
    ) { Text(label, fontWeight = FontWeight.Bold) }
}
