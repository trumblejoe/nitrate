package dev.nitrate.tv.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.nitrate.tv.data.JellyfinRepository
import dev.nitrate.tv.data.ticksToMs
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.UUID

private const val PROGRESS_INTERVAL_MS = 10_000L

@Composable
fun PlayerScreen(
    repository: JellyfinRepository,
    itemId: String,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val id = remember(itemId) { UUID.fromString(itemId) }
    val player = remember { ExoPlayer.Builder(context).build() }
    var error by remember { mutableStateOf<String?>(null) }
    var started by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        runCatching { repository.getItem(id) }
            .onSuccess { item ->
                val media = MediaItem.Builder()
                    .setUri(repository.streamUrl(id, item.mediaSources?.firstOrNull()?.id))
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(item.name).build())
                    .build()
                val resumeMs = item.userData?.playbackPositionTicks?.ticksToMs() ?: 0L
                player.setMediaItem(media, resumeMs)
                player.prepare()
                player.playWhenReady = true
                repository.reportStart(id, resumeMs)
                started = true
            }
            .onFailure { error = it.message ?: "Could not load item" }
    }

    // Periodic progress so the server's "Continue Watching" stays accurate.
    LaunchedEffect(started) {
        if (!started) return@LaunchedEffect
        while (isActive) {
            delay(PROGRESS_INTERVAL_MS)
            repository.reportProgress(id, player.currentPosition, isPaused = !player.isPlaying)
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                error = "Playback failed: ${e.errorCodeName}. This file may need transcoding (not yet supported)."
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) onExit()
            }
        }
        player.addListener(listener)
        onDispose {
            val position = player.currentPosition
            player.removeListener(listener)
            player.release()
            if (started) repository.reportStopped(id, position)
        }
    }

    BackHandler { onExit() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    keepScreenOn = true
                    requestFocus()
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.Center))
        }
    }
}
