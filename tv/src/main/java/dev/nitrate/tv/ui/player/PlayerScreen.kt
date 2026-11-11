package dev.nitrate.tv.ui.player

import dev.nitrate.android.playback.applySubtitleStyle
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.media3.common.MediaItem as ExoMediaItem
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
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.PlaybackPlan
import dev.nitrate.core.plugins.PlaybackControls
import dev.nitrate.tv.plugins.core.PlayerPanel
import dev.nitrate.tv.plugins.core.PluginContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import dev.nitrate.android.playback.PlaybackSession
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.playback.audioChoices
import dev.nitrate.core.playback.subtitleChoices
import dev.nitrate.core.playback.CapabilityRules
import dev.nitrate.core.playback.PlaybackSettingsRepository
import dev.nitrate.core.model.StreamKind
import dev.nitrate.core.model.MediaKind
import dev.nitrate.core.model.PlayMethod
import dev.nitrate.core.model.SubtitleDelivery

private const val CONTROLS_TIMEOUT_MS = 5_000L
private const val SEEK_BACK_MS = 10_000L
private const val SEEK_FORWARD_MS = 30_000L


private enum class Panel { NONE, AUDIO, SUBTITLES, INFO, MORE }

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    repository: MediaServer,
    playbackSettings: PlaybackSettingsRepository,
    itemId: String,
    fromStart: Boolean,
    onExit: () -> Unit,
    onEnded: () -> Unit = onExit,
    /** Plugin panels that can open beside the video (e.g. the writing pane). */
    panels: List<PlayerPanel> = emptyList(),
    pluginContext: PluginContext? = null,
    /** Shared per-show language memory and the live-connection hub (remote commands). */
    trackMemory: dev.nitrate.core.playback.TrackMemoryStore? = null,
    remote: dev.nitrate.android.remote.RemoteControlHub? = null,
    startMs: Long? = null,
    /** Watch together: follows this device's group when it's in one. */
    syncPlay: dev.nitrate.core.syncplay.SyncPlayController? = null,
) {
    val context = LocalContext.current
    // "End of this" sleep timer: when it ends, leave instead of rolling on through a queue.
    val sleepAtEndState = remember { mutableStateOf(false) }
    val ended by rememberUpdatedState(if (sleepAtEndState.value) onExit else onEnded)
    // Negotiation, track mapping, reporting and transcode fallback live in the shared session.
    val session = remember(itemId) {
        PlaybackSession(context, repository, playbackSettings, deviceNoun = "This TV", onEnded = { ended() }, trackMemory = trackMemory)
            .also {
                syncPlay?.let(it::followGroup)
                it.load(itemId, fromStart, startMs)
            }
    }
    DisposableEffect(session) {
        remote?.active = session
        onDispose {
            if (remote?.active === session) remote.active = null
            session.release()
        }
    }
    val settingsNow by playbackSettings.settings.collectAsState(dev.nitrate.core.playback.PlaybackSettings())
    var sleepAt by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(sleepAt) {
        val at = sleepAt ?: return@LaunchedEffect
        kotlinx.coroutines.delay((at - System.currentTimeMillis()).coerceAtLeast(0))
        session.player.pause()
        sleepAt = null
    }
    val player = session.player
    val item by session.item.collectAsState()
    val plan by session.plan.collectAsState()
    val error by session.error.collectAsState()
    val notice by session.notice.collectAsState()
    val videoDecoder by session.videoDecoder.collectAsState()
    val audioDecoder by session.audioDecoder.collectAsState()

    var controlsVisible by remember { mutableStateOf(true) }
    var lastInput by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var panel by remember { mutableStateOf(Panel.NONE) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var openPanel by remember { mutableStateOf<PlayerPanel?>(null) }
    var panelFocused by remember { mutableStateOf(false) }
    val playbackControls = session.controls
    val rootFocus = remember { FocusRequester() }
    val playPauseFocus = remember { FocusRequester() }

    fun touch() {
        lastInput = System.currentTimeMillis()
        controlsVisible = true
    }

    fun selectTrack(kind: StreamKind, index: Int) = session.selectTrack(kind, index)

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
            update = { it.applySubtitleStyle(settingsNow.subtitleStyle) },
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
    if (p != null && panel == Panel.MORE) {
        PlayerMoreDialog(repository, playbackSettings, session, sleepAt, sleepAtEndState.value,
            onSleep = { at, end -> sleepAt = at; sleepAtEndState.value = end }, onDismiss = { panel = Panel.NONE }, syncPlay = syncPlay)
    }
    if (p != null && panel == Panel.AUDIO) {
        TrackDialog("Audio", p.audioChoices(), p.audioIndex, onSelect = { selectTrack(StreamKind.AUDIO, it); panel = Panel.NONE }, onDismiss = { panel = Panel.NONE })
    }
    if (p != null && panel == Panel.SUBTITLES) {
        TrackDialog("Subtitles", p.subtitleChoices(), p.subtitleIndex, onSelect = { selectTrack(StreamKind.SUBTITLE, it); panel = Panel.NONE }, onDismiss = { panel = Panel.NONE })
    }
}

@Composable
private fun Controls(
    item: MediaItem?,
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
            Text(it.displayTitle, style = MaterialTheme.typography.headlineSmall, color = Color.White)
            it.episodeLabel?.let { label -> Text(label, style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.8f)) }
        }
        SeekBar(position, duration, onSeek)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onPlayPause, modifier = Modifier.focusRequester(playPauseFocus)) { Text(if (playing) "Pause" else "Play") }
            OutlinedButton(onClick = { onOpen(Panel.AUDIO) }, enabled = plan != null) { Text("Audio") }
            OutlinedButton(onClick = { onOpen(Panel.SUBTITLES) }, enabled = plan != null) { Text("Subtitles") }
            OutlinedButton(onClick = { onOpen(Panel.INFO) }, enabled = plan != null) { Text("Info") }
            OutlinedButton(onClick = { onOpen(Panel.MORE) }, enabled = plan != null) { Text("More") }
            for ((label, action) in extraButtons) OutlinedButton(onClick = action) { Text(label) }
            plan?.takeIf { it.method != PlayMethod.DIRECT_PLAY }?.let {
                Text(
                    if (it.method == PlayMethod.TRANSCODE) "Transcoding" else "Direct stream",
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

