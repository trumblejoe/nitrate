package dev.nitrate.tv.ui.player

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.nitrate.tv.data.JellyfinRepository
import dev.nitrate.tv.data.PlaybackPlan
import dev.nitrate.tv.data.ticksToMs
import dev.nitrate.tv.plugins.core.PlaybackControls
import dev.nitrate.tv.plugins.core.PlayerPanel
import dev.nitrate.tv.plugins.core.PluginContext
import dev.nitrate.tv.ui.components.displayTitle
import dev.nitrate.tv.ui.components.episodeLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import java.util.UUID

private const val PROGRESS_INTERVAL_MS = 10_000L
private const val CONTROLS_TIMEOUT_MS = 5_000L
private const val SEEK_BACK_MS = 10_000L
private const val SEEK_FORWARD_MS = 30_000L

/** Error codes that mean "this device couldn't decode it", as opposed to network or server errors. */
private val DECODER_ERRORS = setOf(
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
)

private enum class Panel { NONE, AUDIO, SUBTITLES, INFO }

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    repository: JellyfinRepository,
    itemId: String,
    fromStart: Boolean,
    onExit: () -> Unit,
    onEnded: () -> Unit = onExit,
    /** Plugin panels that can open beside the video (e.g. the writing pane). */
    panels: List<PlayerPanel> = emptyList(),
    pluginContext: PluginContext? = null,
) {
    val context = LocalContext.current
    val id = remember(itemId) { UUID.fromString(itemId) }
    var videoDecoder by remember { mutableStateOf<String?>(null) }
    var audioDecoder by remember { mutableStateOf<String?>(null) }
    val player = remember {
        // The auth header covers direct play, HLS playlists and segments, and subtitle files.
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Authorization" to repository.authorizationHeader()))
        ExoPlayer.Builder(context)
            .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(http))
            .build()
            .apply {
                addAnalyticsListener(object : AnalyticsListener {
                    override fun onVideoDecoderInitialized(e: AnalyticsListener.EventTime, name: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                        videoDecoder = name
                    }
                    override fun onAudioDecoderInitialized(e: AnalyticsListener.EventTime, name: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                        audioDecoder = name
                    }
                })
            }
    }

    var item by remember { mutableStateOf<BaseItemDto?>(null) }
    var plan by remember { mutableStateOf<PlaybackPlan?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var selectionApplied by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var lastInput by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var panel by remember { mutableStateOf(Panel.NONE) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var openPanel by remember { mutableStateOf<PlayerPanel?>(null) }
    var panelFocused by remember { mutableStateOf(false) }
    val playbackControls = remember(player) {
        object : PlaybackControls {
            override val isPlaying get() = player.isPlaying
            override fun togglePlay() = if (player.isPlaying) player.pause() else player.play()
            override fun seekBy(deltaMs: Long) = player.seekTo((player.currentPosition + deltaMs).coerceAtLeast(0))
        }
    }
    val rootFocus = remember { FocusRequester() }
    val playPauseFocus = remember { FocusRequester() }

    fun touch() {
        lastInput = System.currentTimeMillis()
        controlsVisible = true
    }

    suspend fun start(startMs: Long, forceTranscode: Boolean, audioIndex: Int? = null, subtitleIndex: Int? = null) {
        val current = item ?: return
        runCatching {
            repository.resolvePlayback(
                itemId = id,
                isAudio = current.mediaType == MediaType.AUDIO,
                startMs = startMs,
                forceTranscode = forceTranscode,
                audioIndex = audioIndex,
                subtitleIndex = subtitleIndex,
                mediaSourceId = plan?.mediaSourceId,
            )
        }.onSuccess { p ->
            plan = p
            selectionApplied = false
            videoDecoder = null
            audioDecoder = null
            val subtitles = p.externalSubtitles.map { sub ->
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
                    .setId("jf-${sub.streamIndex}")
                    .setMimeType(sub.mimeType)
                    .setLanguage(sub.language)
                    .setLabel(sub.label)
                    .build()
            }
            val media = MediaItem.Builder()
                .setUri(p.url)
                .apply { if (p.isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
                .setSubtitleConfigurations(subtitles)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(current.name).build())
                .build()
            // Jellyfin's HLS playlists span the whole file, so every method seeks client-side.
            player.setMediaItem(media, startMs)
            player.prepare()
            player.playWhenReady = true
            repository.reportStart(p, startMs)
        }.onFailure { error = it.message ?: "Could not start playback" }
    }

    LaunchedEffect(id) {
        runCatching { repository.getItem(id) }
            .onSuccess {
                item = it
                val resumeMs = if (fromStart) 0L else it.userData?.playbackPositionTicks?.ticksToMs() ?: 0L
                start(resumeMs, forceTranscode = false)
            }
            .onFailure { error = it.message ?: "Could not load item" }
    }

    /** Picks a new audio or subtitle stream: in the player when it has the track, else via the server. */
    fun selectTrack(type: MediaStreamType, index: Int) {
        val p = plan ?: return
        val stream = p.source.mediaStreams.orEmpty().firstOrNull { it.index == index }
        val clientSide = when {
            type == MediaStreamType.SUBTITLE && index == -1 -> true
            type == MediaStreamType.SUBTITLE && stream?.deliveryMethod == SubtitleDeliveryMethod.EXTERNAL -> true
            // Direct play has every embedded track; HLS only carries what the server chose.
            p.playMethod == PlayMethod.DIRECT_PLAY -> stream?.deliveryMethod != SubtitleDeliveryMethod.ENCODE
            else -> false
        }
        val updated = if (type == MediaStreamType.AUDIO) p.copy(audioIndex = index) else p.copy(subtitleIndex = index)
        if (clientSide) {
            plan = updated
            applySelection(player, updated)
        } else {
            val at = player.currentPosition
            repository.reportStopped(p, at)
            scope.launch {
                start(
                    at,
                    forceTranscode = p.playMethod == PlayMethod.TRANSCODE,
                    audioIndex = updated.audioIndex,
                    subtitleIndex = updated.subtitleIndex,
                )
            }
        }
    }

    // Periodic progress so the server's "Continue Watching" stays accurate.
    LaunchedEffect(plan) {
        val p = plan ?: return@LaunchedEffect
        while (isActive) {
            delay(PROGRESS_INTERVAL_MS)
            repository.reportProgress(p, player.currentPosition, isPaused = !player.isPlaying)
        }
    }

    // Clock for the seek bar, and auto-hide of the controls while playing.
    LaunchedEffect(Unit) {
        while (isActive) {
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0L)
            playing = player.isPlaying
            if (controlsVisible && playing && panel == Panel.NONE && System.currentTimeMillis() - lastInput > CONTROLS_TIMEOUT_MS) {
                controlsVisible = false
                // Never pull focus away from someone typing in a side panel.
                if (!panelFocused) runCatching { rootFocus.requestFocus() }
            }
            delay(250)
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                val p = plan ?: return
                if (!selectionApplied && !tracks.isEmpty) {
                    selectionApplied = true
                    applySelection(player, p)
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                val failed = plan
                if (failed != null && failed.playMethod != PlayMethod.TRANSCODE) {
                    val at = player.currentPosition
                    val renderer = (e as? ExoPlaybackException)?.takeIf { it.type == ExoPlaybackException.TYPE_RENDERER }
                    if (e.errorCode in DECODER_ERRORS && failed.playMethod == PlayMethod.DIRECT_PLAY) {
                        val videoFailed = !MimeTypes.isAudio(renderer?.rendererFormat?.sampleMimeType)
                        scope.launch { repository.recordDirectPlayFailure(failed, videoFailed, e.errorCodeName) }
                        notice = "This TV couldn't decode it (${e.errorCodeName}). Switched to transcoding; " +
                            "it will transcode this format from now on. Change this in Playback settings."
                    }
                    repository.reportStopped(failed, at)
                    plan = null
                    scope.launch { start(at, forceTranscode = true, audioIndex = failed.audioIndex, subtitleIndex = failed.subtitleIndex) }
                } else {
                    error = "Playback failed: ${e.errorCodeName}"
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) onEnded()
            }
        }
        player.addListener(listener)
        onDispose {
            plan?.let { repository.reportStopped(it, player.currentPosition) }
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(8_000)
            notice = null
        }
    }
    LaunchedEffect(controlsVisible) {
        if (controlsVisible) runCatching { playPauseFocus.requestFocus() }
    }

    fun togglePanel(target: PlayerPanel) {
        if (openPanel == target) {
            openPanel = null
            touch()
        } else {
            openPanel = target
        }
    }

    BackHandler {
        when {
            panel != Panel.NONE -> panel = Panel.NONE
            openPanel != null -> { openPanel = null; touch() }
            controlsVisible && player.isPlaying -> { controlsVisible = false; runCatching { rootFocus.requestFocus() } }
            else -> onExit()
        }
    }

    Row(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Ctrl + a panel's key toggles it from anywhere, including while typing in it.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return@onPreviewKeyEvent false
                val target = panels.firstOrNull { it.shortcut == event.key } ?: return@onPreviewKeyEvent false
                togglePanel(target)
                true
            },
    ) {
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .onPreviewKeyEvent { touch(); false }
            // While controls are hidden the root has focus; keys act directly on playback.
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
                when (event.key) {
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { if (player.isPlaying) player.pause() else player.play(); true }
                    Key.MediaFastForward -> { player.seekTo(player.currentPosition + SEEK_FORWARD_MS); true }
                    Key.MediaRewind -> { player.seekTo(player.currentPosition - SEEK_BACK_MS); true }
                    Key.Captions -> { panel = Panel.SUBTITLES; true }
                    else -> false
                }
            }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    keepScreenOn = true
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        val p = plan
        androidx.compose.animation.AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomStart)) {
            Controls(
                item = item,
                plan = p,
                position = position,
                duration = duration,
                playing = playing,
                playPauseFocus = playPauseFocus,
                onPlayPause = { if (player.isPlaying) player.pause() else player.play() },
                onSeek = { delta -> player.seekTo((player.currentPosition + delta).coerceIn(0, duration)) },
                onOpen = { panel = it },
                extraButtons = panels.map { it.panelLabel to { togglePanel(it) } },
            )
        }

        if (panel == Panel.INFO && p != null) {
            PlaybackInfoPanel(p, videoDecoder, audioDecoder, Modifier.align(Alignment.TopEnd).padding(32.dp))
        }

        notice?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.TopStart).padding(32.dp).fillMaxWidth(0.55f)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), MaterialTheme.shapes.medium).padding(16.dp),
            )
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.Center))
        }
    }
        val shownPanel = openPanel
        if (shownPanel != null && pluginContext != null) {
            shownPanel.Panel(
                pluginContext,
                playbackControls,
                Modifier.fillMaxWidth(0.42f).fillMaxHeight().onFocusChanged { panelFocused = it.hasFocus },
            )
        }
    }

    val p = plan
    if (p != null && panel == Panel.AUDIO) {
        TrackDialog("Audio", p.audioChoices(), p.audioIndex, onSelect = { selectTrack(MediaStreamType.AUDIO, it); panel = Panel.NONE }, onDismiss = { panel = Panel.NONE })
    }
    if (p != null && panel == Panel.SUBTITLES) {
        TrackDialog("Subtitles", p.subtitleChoices(), p.subtitleIndex, onSelect = { selectTrack(MediaStreamType.SUBTITLE, it); panel = Panel.NONE }, onDismiss = { panel = Panel.NONE })
    }
}

@Composable
private fun Controls(
    item: BaseItemDto?,
    plan: PlaybackPlan?,
    position: Long,
    duration: Long,
    playing: Boolean,
    playPauseFocus: FocusRequester,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpen: (Panel) -> Unit,
    extraButtons: List<Pair<String, () -> Unit>> = emptyList(),
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
            .padding(start = 48.dp, end = 48.dp, top = 80.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item?.let {
            Text(it.displayTitle(), style = MaterialTheme.typography.headlineSmall, color = Color.White)
            it.episodeLabel()?.let { label -> Text(label, style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.8f)) }
        }
        SeekBar(position, duration, onSeek)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onPlayPause, modifier = Modifier.focusRequester(playPauseFocus)) { Text(if (playing) "Pause" else "Play") }
            OutlinedButton(onClick = { onOpen(Panel.AUDIO) }, enabled = plan != null) { Text("Audio") }
            OutlinedButton(onClick = { onOpen(Panel.SUBTITLES) }, enabled = plan != null) { Text("Subtitles") }
            OutlinedButton(onClick = { onOpen(Panel.INFO) }, enabled = plan != null) { Text("Info") }
            for ((label, action) in extraButtons) OutlinedButton(onClick = action) { Text(label) }
            plan?.takeIf { it.playMethod != PlayMethod.DIRECT_PLAY }?.let {
                Text(
                    if (it.playMethod == PlayMethod.TRANSCODE) "Transcoding" else "Direct stream",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

/** Focusable progress bar: Left/Right seek while it has focus. */
@Composable
private fun SeekBar(position: Long, duration: Long, onSeek: (Long) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (focused) 10.dp else 6.dp)
                .background(Color.White.copy(alpha = 0.25f), MaterialTheme.shapes.small)
                .onFocusChanged { focused = it.isFocused }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> { onSeek(-SEEK_BACK_MS); true }
                        Key.DirectionRight -> { onSeek(SEEK_FORWARD_MS); true }
                        else -> false
                    }
                }
                .focusable(),
        ) {
            val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth(fraction).height(if (focused) 10.dp else 6.dp).background(accent, MaterialTheme.shapes.small))
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(position), style = MaterialTheme.typography.labelMedium, color = Color.White)
            Text("-" + formatTime((duration - position).coerceAtLeast(0)), style = MaterialTheme.typography.labelMedium, color = Color.White)
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * Points ExoPlayer at the plan's audio and subtitle streams. Embedded tracks line up with
 * Jellyfin's streams by order within their type; side-loaded subtitles carry a "jf-<index>" id.
 */
@OptIn(UnstableApi::class)
private fun applySelection(player: ExoPlayer, plan: PlaybackPlan) {
    val streams = plan.source.mediaStreams.orEmpty()
    val groups = player.currentTracks.groups
    val params = player.trackSelectionParameters.buildUpon()

    // HLS carries only the audio the server picked, so there is nothing to choose client-side.
    if (plan.playMethod == PlayMethod.DIRECT_PLAY) {
        val embeddedAudio = streams.filter { it.type == MediaStreamType.AUDIO && !it.isExternal }.map { it.index }
        val audioGroups = groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        audioGroups.getOrNull(embeddedAudio.indexOf(plan.audioIndex))?.let {
            params.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, 0))
        }
    }

    val textGroups = groups.filter { it.type == C.TRACK_TYPE_TEXT }
    val (sideLoaded, embeddedText) = textGroups.partition { g -> g.getTrackFormat(0).id?.contains("jf-") == true }
    val subtitle = streams.firstOrNull { it.index == plan.subtitleIndex && it.type == MediaStreamType.SUBTITLE }
    val target = when {
        subtitle == null -> null
        subtitle.deliveryMethod == SubtitleDeliveryMethod.EXTERNAL ->
            sideLoaded.firstOrNull { it.getTrackFormat(0).id?.endsWith("jf-${subtitle.index}") == true }
        // Burned in by the server: nothing to show client-side.
        subtitle.deliveryMethod == SubtitleDeliveryMethod.ENCODE -> null
        plan.playMethod == PlayMethod.DIRECT_PLAY -> {
            val embedded = streams.filter { it.type == MediaStreamType.SUBTITLE && !it.isExternal }.map { it.index }
            embeddedText.getOrNull(embedded.indexOf(subtitle.index))
        }
        else -> embeddedText.firstOrNull()
    }
    if (target != null) {
        params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        params.setOverrideForType(TrackSelectionOverride(target.mediaTrackGroup, 0))
    } else {
        params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
    }
    player.trackSelectionParameters = params.build()
}
