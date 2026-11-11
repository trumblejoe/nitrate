package dev.nitrate.tv.ui.player

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.nitrate.tv.data.JellyfinRepository
import dev.nitrate.tv.data.PlaybackPlan
import dev.nitrate.tv.data.ticksToMs
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.PlayMethod
import java.util.UUID

private const val PROGRESS_INTERVAL_MS = 10_000L

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    repository: JellyfinRepository,
    itemId: String,
    fromStart: Boolean,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val id = remember(itemId) { UUID.fromString(itemId) }
    val player = remember {
        // The auth header covers direct-play, HLS playlists and segments alike.
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Authorization" to repository.authorizationHeader()))
        ExoPlayer.Builder(context)
            .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(http))
            .build()
    }
    var plan by remember { mutableStateOf<PlaybackPlan?>(null) }
    var title by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun start(startMs: Long, forceTranscode: Boolean) {
        runCatching { repository.resolvePlayback(id, startMs, forceTranscode) }
            .onSuccess { p ->
                plan = p
                val media = MediaItem.Builder()
                    .setUri(p.url)
                    .apply { if (p.isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                    .build()
                // Jellyfin's HLS playlists span the whole file, so every method seeks client-side.
                player.setMediaItem(media, startMs)
                player.prepare()
                player.playWhenReady = true
                repository.reportStart(p, startMs)
            }
            .onFailure { error = it.message ?: "Could not start playback" }
    }

    LaunchedEffect(id) {
        runCatching { repository.getItem(id) }
            .onSuccess { item ->
                title = item.name
                val resumeMs = if (fromStart) 0L else item.userData?.playbackPositionTicks?.ticksToMs() ?: 0L
                start(resumeMs, forceTranscode = false)
            }
            .onFailure { error = it.message ?: "Could not load item" }
    }

    fun position(): Long = player.currentPosition

    // Periodic progress so the server's "Continue Watching" stays accurate.
    LaunchedEffect(plan) {
        val p = plan ?: return@LaunchedEffect
        while (isActive) {
            delay(PROGRESS_INTERVAL_MS)
            repository.reportProgress(p, position(), isPaused = !player.isPlaying)
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                val failed = plan
                if (failed != null && failed.playMethod != PlayMethod.TRANSCODE) {
                    // Most direct-play failures are codecs the device claimed but can't handle.
                    // Retry once with a server transcode from the same spot.
                    val at = position()
                    repository.reportStopped(failed, at)
                    plan = null
                    scope.launch { start(at, forceTranscode = true) }
                } else {
                    error = "Playback failed: ${e.errorCodeName}"
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) onExit()
            }
        }
        player.addListener(listener)
        onDispose {
            plan?.let { repository.reportStopped(it, position()) }
            player.removeListener(listener)
            player.release()
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
        plan?.let {
            if (it.playMethod != PlayMethod.DIRECT_PLAY) {
                Text(
                    text = if (it.playMethod == PlayMethod.TRANSCODE) "Transcoding" else "Direct stream",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.align(Alignment.TopEnd).padding(24.dp),
                )
            }
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.Center))
        }
    }
}
