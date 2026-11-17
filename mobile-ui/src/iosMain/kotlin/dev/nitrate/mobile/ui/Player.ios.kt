package dev.nitrate.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitViewController
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.PlaybackPlan
import kotlinx.coroutines.delay
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.rate
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVKit.AVPlayerViewController
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL

/**
 * Apple's player (AVPlayer in AVPlayerViewController, with its own controls, picture-in-picture
 * and AirPlay). The server is asked how to play (direct, or HLS it repackages or converts), the
 * stream opens with the account's Authorization header, and start, progress and stop are reported.
 * Up Next, credits skipping, chat and the rest of the Android player's extras come later.
 */
@Composable
actual fun PlayerScreen(
    actions: AppActions,
    itemId: String,
    fromStart: Boolean,
    onExit: () -> Unit,
    onEnded: () -> Unit,
    local: Boolean,
    startMs: Long?,
    onPlayNext: ((MediaItem) -> Unit)?,
    clipEndMs: Long?,
) {
    val server = actions.server
    val player = remember { AVPlayer() }
    var plan by remember { mutableStateOf<PlaybackPlan?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(itemId) {
        runCatching {
            val start = startMs ?: if (fromStart) 0L else server.getItem(itemId).user.positionMs
            server.resolvePlayback(itemId, isAudio = false, startMs = start) to start
        }.onSuccess { (p, start) ->
            val url = NSURL.URLWithString(p.url)
            if (url == null) {
                error = "The server gave an address the player can't open."
                return@onSuccess
            }
            val headers = mapOf<Any?, Any?>("Authorization" to server.authorizationHeader())
            val asset = AVURLAsset(uRL = url, options = mapOf<Any?, Any?>(HEADER_FIELDS to headers))
            player.replaceCurrentItemWithPlayerItem(AVPlayerItem(asset = asset))
            if (start > 0) player.seekToTime(CMTimeMakeWithSeconds(start / 1000.0, 600))
            player.play()
            plan = p
            if (clipEndMs == null) runCatching { server.reportStart(p, start) }
        }.onFailure { error = "Couldn't start: ${it.message}" }
    }

    // Progress every 10 seconds (recap clips aren't reported), and a clip's end.
    LaunchedEffect(plan) {
        val p = plan ?: return@LaunchedEffect
        while (true) {
            delay(if (clipEndMs != null) 500 else 10_000)
            val position = player.positionMs()
            if (clipEndMs != null) {
                if (position >= clipEndMs) { onEnded(); return@LaunchedEffect }
            } else {
                runCatching { server.reportProgress(p, position, isPaused = player.rate == 0f) }
            }
        }
    }

    DisposableEffect(Unit) {
        val ended = NSNotificationCenter.defaultCenter.addObserverForName(AVPlayerItemDidPlayToEndTimeNotification, null, NSOperationQueue.mainQueue) { _ -> onEnded() }
        onDispose {
            NSNotificationCenter.defaultCenter.removeObserver(ended)
            if (clipEndMs == null) plan?.let { server.reportStopped(it, player.positionMs()) }
            player.pause()
            player.replaceCurrentItemWithPlayerItem(null)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        UIKitViewController(
            factory = { AVPlayerViewController().apply { this.player = player; showsPlaybackControls = true } },
            modifier = Modifier.fillMaxSize(),
        )
        when {
            error != null -> Column(Modifier.align(Alignment.Center).padding(24.dp)) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
                Button(onClick = onExit) { Text("Back") }
            }
            plan == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = accentColor())
        }
        TextButton(onClick = onExit, modifier = Modifier.align(Alignment.TopEnd).padding(top = 44.dp, end = 12.dp)) { Text("Close", color = Color.White) }
    }
}

/** AVURLAsset's (long-standing, unlisted) option for request headers, used by most players. */
private const val HEADER_FIELDS = "AVURLAssetHTTPHeaderFieldsKey"

private fun AVPlayer.positionMs(): Long {
    if (currentItem == null) return 0
    val seconds = CMTimeGetSeconds(currentTime())
    return if (seconds.isNaN()) 0 else (seconds * 1000).toLong()
}
