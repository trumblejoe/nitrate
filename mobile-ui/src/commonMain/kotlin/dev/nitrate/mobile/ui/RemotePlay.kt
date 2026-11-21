package dev.nitrate.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.nitrate.core.admin.ServerSession
import dev.nitrate.core.admin.SessionCommand
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.remote.RemoteDevices
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The signed-in user's other devices that take remote playback, or null while asking. */
@Composable
fun rememberRemoteDevices(actions: AppActions, refreshMs: Long? = null): Result<List<ServerSession>>? {
    val found by produceState<Result<List<ServerSession>>?>(null) {
        while (true) {
            value = runCatching { RemoteDevices.controllable(actions.server.controllableSessions(), actions.server.deviceId, actions.server.userId) }
            delay(refreshMs ?: return@produceState)
        }
    }
    return found
}

/**
 * "Play on…": sends [item] to another of the user's devices, from [startMs], with the audio and
 * subtitles it would start with here (the user's picks, else the server's), then opens the remote.
 */
@Composable
fun PlayOnDialog(actions: AppActions, item: MediaItem, startMs: Long, onDismiss: () -> Unit, onSent: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val devices = rememberRemoteDevices(actions)
    var sending by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    val cast = dev.nitrate.mobile.platform.rememberCast()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Play on…") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val list = devices?.getOrNull()
                when {
                    devices == null -> CircularProgressIndicator()
                    devices.isFailure -> Text("Couldn't ask your server which devices are on: ${devices.exceptionOrNull()?.message}")
                    list.isNullOrEmpty() -> Text(
                        "None of your other devices can be played on right now. Open Nitrate (or another Jellyfin app) on your TV, then try again." +
                            if (cast != null) " Or cast to a Chromecast below." else "",
                    )
                    else -> list.forEach { d ->
                        val name = RemoteDevices.name(d)
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = sending == null) {
                                sending = d.id
                                failed = null
                                scope.launch {
                                    runCatching {
                                        val tracks = runCatching { actions.container.trackSetup(item) }.getOrNull()
                                        actions.server.playOn(
                                            d.id, listOf(item.id), startMs,
                                            audioIndex = tracks?.audioIndex, subtitleIndex = tracks?.subtitleIndex,
                                            mediaSourceId = dev.nitrate.android.playback.VersionChoices[item.id],
                                        )
                                    }.onSuccess {
                                        onSent()
                                        onDismiss()
                                        actions.nav.navigate(RemoteRoute(d.id))
                                    }.onFailure { failed = "Couldn't play on $name: ${it.message}" }
                                    sending = null
                                }
                            }.padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(NitrateIcons.Tv, null, tint = accentColor(), modifier = Modifier.padding(end = 14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    d.nowPlaying?.let { "Playing ${it.displayTitle}" } ?: d.client.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (sending == d.id) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    }
                }
                // Chromecasts on the network, through Jellyfin's own receiver app.
                if (cast != null) CastDeviceList(cast, busy = sending) { d ->
                    sending = d.id
                    failed = null
                    scope.launch {
                        val ok = runCatching { castTo(actions.container, cast, d, castPlayFor(actions.container, item, fromStart = false, startMs = startMs)) }.getOrDefault(false)
                        sending = null
                        if (ok) { onSent(); onDismiss() } else failed = "Couldn't cast to ${d.name}. Check it's on the same network, then try again."
                    }
                }
                failed?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * While another of the user's devices plays something: what and where, with play/pause, and a
 * tap opens the remote. Sits above the navigation pill; nothing shows when nothing plays.
 */
@Composable
fun PlayingElsewhereBar(actions: AppActions, modifier: Modifier = Modifier, onShown: (Boolean) -> Unit = {}) {
    var tick by remember { mutableIntStateOf(0) }
    // Keyed on the account: switching clears it at once (and asks again as the new one).
    val account = actions.server.currentAccountKey ?: actions.server.userId
    val playing by produceState<ServerSession?>(null, tick, account) {
        while (true) {
            value = runCatching { RemoteDevices.playing(actions.server.controllableSessions(), actions.server.deviceId, actions.server.userId).firstOrNull() }.getOrNull()
            delay(if (value != null) 5_000 else 20_000)
        }
    }
    androidx.compose.runtime.LaunchedEffect(playing?.nowPlaying != null) { onShown(playing?.nowPlaying != null) }
    val s = playing ?: return
    val item = s.nowPlaying ?: return
    val scope = rememberCoroutineScope()
    Row(
        modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .pressable({ actions.nav.navigate(RemoteRoute(s.id)) }).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.fillMaxHeight().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.08f))) {
            actions.server.cardImage(item, CardShape.POSTER, 160)?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxHeight().aspectRatio(1f)) }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text("Playing on ${RemoteDevices.name(s)}", style = MaterialTheme.typography.labelMedium, color = accentColor(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.displayTitle + (item.episodeLabel?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = {
            scope.launch {
                runCatching { actions.server.sessionCommand(s.id, SessionCommand.PLAY_PAUSE) }
                delay(400)
                tick++
            }
        }) {
            if (s.isPaused) Icon(Icons.Default.PlayArrow, "Play") else Icon(NitrateIcons.Pause, "Pause")
        }
    }
}
