package dev.nitrate.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import dev.nitrate.core.model.PlaybackPlan
import kotlinx.coroutines.delay
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
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
import platform.Foundation.NSURL

/**
 * Plays a title with Apple's player: the server is asked how (direct play, or HLS it repackages
 * or converts), the stream opens with the account's Authorization header, and start, progress
 * and stop are reported so resume points and watched marks follow.
 */
@Composable
internal fun PlayerScreen(container: dev.nitrate.android.AppContainer, itemId: String, startMs: Long, onClose: () -> Unit) {
    val server = container.server
    val player = remember { AVPlayer() }
    var plan by remember { mutableStateOf<PlaybackPlan?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(itemId) {
        runCatching { server.resolvePlayback(itemId, isAudio = false, startMs = startMs) }
            .onSuccess { p ->
                val url = NSURL.URLWithString(p.url) ?: return@onSuccess run { error = "The server gave an address the player can't open." }
                val headers = mapOf<Any?, Any?>("Authorization" to server.authorizationHeader())
                val asset = AVURLAsset(uRL = url, options = mapOf<Any?, Any?>(HEADER_FIELDS to headers))
                player.replaceCurrentItemWithPlayerItem(AVPlayerItem(asset = asset))
                if (startMs > 0) player.seekToTime(CMTimeMakeWithSeconds(startMs / 1000.0, 600))
                player.play()
                plan = p
                runCatching { server.reportStart(p, startMs) }
            }
            .onFailure { error = "Couldn't start: ${it.message}" }
    }

    // Progress every 10 seconds while open.
    LaunchedEffect(plan) {
        val p = plan ?: return@LaunchedEffect
        while (true) {
            delay(10_000)
            runCatching { server.reportProgress(p, player.positionMs(), isPaused = player.rate == 0f) }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            plan?.let { server.reportStopped(it, player.positionMs()) }
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
                Button(onClick = onClose) { Text("Back") }
            }
            plan == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Accent)
        }
        // AVPlayerViewController has its own Done button only when presented; this closes it here.
        androidx.compose.material3.TextButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 44.dp, end = 12.dp),
        ) { Text("Close", color = Color.White) }
    }
}

/** AVURLAsset's (long-standing, unlisted) option for request headers, used by most players. */
private const val HEADER_FIELDS = "AVURLAssetHTTPHeaderFieldsKey"

private fun AVPlayer.positionMs(): Long {
    if (currentItem == null) return 0
    val seconds = CMTimeGetSeconds(currentTime())
    return if (seconds.isNaN()) 0 else (seconds * 1000).toLong()
}
