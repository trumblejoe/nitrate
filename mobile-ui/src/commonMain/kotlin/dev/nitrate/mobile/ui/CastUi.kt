package dev.nitrate.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nitrate.android.nowMs
import dev.nitrate.core.cast.Cast
import dev.nitrate.core.cast.CastCommand
import dev.nitrate.core.cast.CastPlay
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.mobile.platform.CastControl
import dev.nitrate.mobile.platform.CastDevice
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What casting [item] starts: a song with its album around it, anything else on its own from where
 * it was left (from the start when asked or once finished), with the user's audio and subtitles.
 */
suspend fun castPlayFor(container: dev.nitrate.android.AppContainer, item: MediaItem, fromStart: Boolean, startMs: Long? = null): CastPlay {
    if (item.kind == ItemKind.AUDIO) {
        val album = item.albumId?.let { runCatching { container.server.albumTracks(it) }.getOrNull() }.orEmpty()
        val at = album.indexOfFirst { it.id == item.id }
        return if (at >= 0) CastPlay(album, at) else CastPlay(listOf(item))
    }
    val tracks = runCatching { container.trackSetup(item) }.getOrNull()
    val from = startMs ?: if (fromStart || item.user.played) 0 else item.user.positionMs
    return CastPlay(
        listOf(item), startMs = from, audioIndex = tracks?.audioIndex, subtitleIndex = tracks?.subtitleIndex,
        mediaSourceId = dev.nitrate.android.playback.VersionChoices[item.id],
    )
}

/** Connects to [device] when needed, then has Jellyfin's receiver play [play]. False when that didn't work. */
suspend fun castTo(container: dev.nitrate.android.AppContainer, cast: CastControl, device: CastDevice?, play: CastPlay): Boolean {
    if (device != null && cast.state.value.connected?.id != device.id) {
        cast.connect(device.id)
        withTimeoutOrNull(25_000) { cast.state.first { it.connected != null } } ?: return false
    }
    if (cast.state.value.connected == null) return false
    val message = container.server.castPlay(play) ?: return false
    cast.send(message)
    return true
}

/** The Chromecasts found, for "Play on…": tap one to cast there. Looks for them while showing. */
@Composable
fun CastDeviceList(cast: CastControl, busy: String?, onPick: (CastDevice) -> Unit) {
    DisposableEffect(cast) {
        cast.discover(true)
        onDispose { cast.discover(false) }
    }
    val state by cast.state.collectAsState()
    val devices = (listOfNotNull(state.connected) + state.devices).distinctBy { it.name }
    Text("Chromecast", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    if (devices.isEmpty()) Text("Looking for Chromecasts on your network…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    devices.forEach { d ->
        val connected = state.connected?.name == d.name
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = busy == null) { onPick(d) }.padding(vertical = 12.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(NitrateIcons.Cast, null, tint = accentColor(), modifier = Modifier.padding(end = 14.dp))
            Column(Modifier.weight(1f)) {
                Text(d.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(if (connected) "Connected" else d.description ?: "Chromecast", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (busy == d.id || state.connecting?.id == d.id) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = (s % 60).toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$sec" else "$m:$sec"
}

/**
 * While casting: the device, what it plays and where, with back/ahead 10 s, play/pause and stop
 * casting. Sits above the navigation pill like the other bars; nothing shows when not casting.
 */
@Composable
fun CastBar(actions: AppActions, cast: CastControl?, modifier: Modifier = Modifier, onShown: (Boolean) -> Unit = {}) {
    val state = cast?.state?.collectAsState()?.value
    val device = state?.connected
    LaunchedEffect(device != null) { onShown(device != null) }
    if (cast == null || device == null) return
    val status = state.status?.takeIf { !it.stopped }
    var now by remember { mutableLongStateOf(nowMs()) }
    LaunchedEffect(status) { while (true) { now = nowMs(); delay(1_000) } }
    val at = status?.positionAt(now - state.statusAtMs) ?: 0
    fun send(command: CastCommand, value: Long? = null) { actions.server.castCommand(command, value)?.let(cast::send) }
    Row(
        modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            // The whole remote: seeking, tracks, chapters, episodes.
            .pressable({ actions.nav.navigate(RemoteRoute(CAST_REMOTE)) }).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(NitrateIcons.Cast, "Casting to ${device.name}", tint = accentColor(), modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            // The cast icon says "casting"; where it is and the device sit above the title.
            Text(
                // Where it is first: a long device name is what gets cut short.
                (status?.let { st -> clock(at) + (st.durationMs?.let { " / " + clock(it) } ?: "") + " · " } ?: "") + device.name,
                style = MaterialTheme.typography.labelMedium, color = accentColor(), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                status?.title ?: if (status != null) "Playing" else "Ready: press Play on anything",
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (status != null) {
            IconButton(modifier = Modifier.size(44.dp), onClick = { send(CastCommand.SEEK, (at - Cast.JUMP_MS).coerceAtLeast(0)) }) { Text("−10", fontWeight = FontWeight.Bold) }
            IconButton(modifier = Modifier.size(44.dp), onClick = { send(if (status.paused) CastCommand.UNPAUSE else CastCommand.PAUSE) }) {
                if (status.paused) Icon(Icons.Default.PlayArrow, "Play") else Icon(NitrateIcons.Pause, "Pause")
            }
            IconButton(modifier = Modifier.size(44.dp), onClick = { send(CastCommand.SEEK, at + Cast.JUMP_MS) }) { Text("+10", fontWeight = FontWeight.Bold) }
        }
        IconButton(modifier = Modifier.size(44.dp), onClick = { cast.disconnect() }) { Icon(NitrateIcons.Stop, "Stop casting") }
    }
}
