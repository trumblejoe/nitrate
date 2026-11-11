package dev.nitrate.mobile.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Surface
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import dev.nitrate.core.writing.WritingPlugin
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nitrate.android.playback.PlaybackSession
import dev.nitrate.core.model.PlayMethod
import dev.nitrate.core.model.StreamKind
import dev.nitrate.core.playback.TrackChoice
import dev.nitrate.core.playback.audioChoices
import dev.nitrate.core.playback.infoRows
import dev.nitrate.core.playback.subtitleChoices
import dev.nitrate.mobile.platform.FullscreenPlayback
import dev.nitrate.mobile.platform.BackgroundPlayback
import dev.nitrate.mobile.platform.rememberPictureInPicture
import dev.nitrate.mobile.platform.rememberPictureInPictureController
import androidx.compose.material.icons.filled.MoreVert
import dev.nitrate.mobile.platform.PlayerGestures
import dev.nitrate.mobile.platform.Scrub
import dev.nitrate.mobile.platform.VolumeControl
import dev.nitrate.mobile.platform.rememberBrightnessControl
import dev.nitrate.core.episodes.UpNext
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.playback.PlaybackMoments
import kotlinx.coroutines.delay
import dev.nitrate.mobile.platform.VideoSurface

private enum class PlayerDialog { NONE, AUDIO, SUBTITLES, INFO, MORE }

@Composable
fun PlayerScreen(
    actions: AppActions,
    itemId: String,
    fromStart: Boolean,
    onExit: () -> Unit,
    onEnded: () -> Unit,
    local: Boolean = false,
    /** Start here instead of the resume point (e.g. handed over from another device). */
    startMs: Long? = null,
    /** Plays the following episode; null turns Up Next off (e.g. playlists, which queue their own). */
    onPlayNext: ((MediaItem) -> Unit)? = null,
) {
    val context = LocalContext.current
    val downloads = actions.container.downloads
    var upNext by remember { mutableStateOf<MediaItem?>(null) }
    var upNextHidden by remember { mutableStateOf(false) }
    // At the end: roll into the next episode unless Up Next was dismissed.
    var sleepAtEndRef by remember { mutableStateOf(false) }
    val ended by rememberUpdatedState {
        val next = upNext.takeIf { !sleepAtEndRef }
        if (next != null && onPlayNext != null && !upNextHidden) onPlayNext(next) else onEnded()
    }
    val session = remember(itemId) {
        PlaybackSession(context, actions.server, actions.container.playbackSettings, deviceNoun = "This device", onEnded = { ended() }, trackMemory = actions.container.trackMemory)
            .also {
                if (!local) {
                    it.followGroup(actions.container.syncPlay)
                    it.load(itemId, fromStart, startMs)
                }
            }
    }
    if (local) {
        LaunchedEffect(session) {
            val record = downloads.find(itemId)
            val plan = record?.let(downloads::playbackPlan)
            if (record == null || plan == null) {
                session.load(itemId, fromStart) // Deleted meanwhile: stream it instead.
                return@LaunchedEffect
            }
            val start = if (fromStart) 0L else downloads.resumePositionMs(record)
            session.loadLocal(record.item, plan, start) { position, duration, ended ->
                downloads.recordPlayback(itemId, position, duration, ended)
            }
        }
    }
    // Commands from other apps (pause, seek...) reach this player while it's on screen.
    DisposableEffect(session) {
        actions.container.remote.active = session
        onDispose {
            if (actions.container.remote.active === session) actions.container.remote.active = null
            BackgroundPlayback.stop()
            session.release()
        }
    }
    val playbackSettings by actions.container.playbackSettings.settings.collectAsState(dev.nitrate.core.playback.PlaybackSettings())
    val inPip by rememberPictureInPicture()
    val pip = rememberPictureInPictureController()
    var playingNow by remember { mutableStateOf(false) }
    DisposableEffect(session) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playingNow = isPlaying }
        }
        session.player.addListener(listener)
        onDispose { session.player.removeListener(listener); pip.disable() }
    }
    val aspect = session.player.videoSize.let { v -> if (v.width > 0 && v.height > 0) android.util.Rational(v.width, v.height) else null }
    LaunchedEffect(playbackSettings.pictureInPicture, playingNow, aspect) { pip.update(playbackSettings.pictureInPicture, playingNow, aspect) }
    // Leaving the app: keep audio going if asked to (with a media notification), else pause, unless shrunk to a window.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) {
        when {
            inPip -> Unit
            playbackSettings.backgroundAudio && session.player.isPlaying -> BackgroundPlayback.start(context, session.player)
            else -> session.player.pause()
        }
    }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_START) { BackgroundPlayback.stop() }
    // Sleep timer: pauses at a time or at the end of this episode.
    var sleepAt by remember { mutableStateOf<Long?>(null) }
    var sleepAtEnd by remember { mutableStateOf(false) }
    LaunchedEffect(sleepAtEnd) { sleepAtEndRef = sleepAtEnd }
    LaunchedEffect(sleepAt) {
        val at = sleepAt ?: return@LaunchedEffect
        kotlinx.coroutines.delay((at - System.currentTimeMillis()).coerceAtLeast(0))
        session.player.pause()
        sleepAt = null
    }
    // The writing pane beside the video (Writing plugin): Write button or Ctrl+E.
    val enabledPlugins by actions.container.plugins.enabledIds.collectAsState(emptySet())
    val canWrite = WritingPlugin.id in enabledPlugins
    var writing by rememberSaveable { mutableStateOf(false) }
    FullscreenPlayback(lockLandscape = !writing)
    BackHandler(onBack = { if (writing) writing = false else onExit() })
    val keys = remember { FocusRequester() }
    LaunchedEffect(writing) { if (!writing) runCatching { keys.requestFocus() } }

    val item by session.item.collectAsState()
    val plan by session.plan.collectAsState()
    val error by session.error.collectAsState()
    val notice by session.notice.collectAsState()
    val videoDecoder by session.videoDecoder.collectAsState()
    val audioDecoder by session.audioDecoder.collectAsState()
    val segments by session.segments.collectAsState()
    val trickplay by session.trickplay.collectAsState()
    val progress by session.progress.collectAsState()
    var controlsVisible by remember { mutableStateOf(true) }
    var dialog by remember { mutableStateOf(PlayerDialog.NONE) }
    val fillState = remember { mutableStateOf(false) }
    val scrubState = remember { mutableStateOf<Scrub?>(null) }
    val fill by fillState
    val scrub by scrubState
    var feedback by remember { mutableStateOf<GestureFeedback?>(null) }
    var feedbackAt by remember { mutableStateOf(0L) }
    val brightness = rememberBrightnessControl()
    val volume = remember { VolumeControl(context) }

    // The next episode in viewing order: from the server, or from downloads when offline.
    LaunchedEffect(item?.id) {
        val current = item ?: return@LaunchedEffect
        if (onPlayNext == null || !current.isEpisode) return@LaunchedEffect
        upNext = (if (local) null else current.seriesId?.let { series -> runCatching { UpNext.after(current, actions.server.allEpisodes(series)) }.getOrNull() })
            ?: downloads.nextDownloaded(current)
    }
    // Gesture feedback fades shortly after the last change.
    LaunchedEffect(feedbackAt) {
        delay(800)
        feedback = null
    }
    fun show(f: GestureFeedback) {
        feedback = f
        feedbackAt = System.nanoTime()
    }
    val gestures = remember(session) {
        object : PlayerGestures {
            override fun onSeek(deltaMs: Long) {
                // Repeated taps on the same side add up: +10s, +20s...
                val previous = (feedback as? GestureFeedback.Seek)?.totalMs?.takeIf { (it > 0) == (deltaMs > 0) } ?: 0L
                session.seekTo(session.player.currentPosition + deltaMs)
                show(GestureFeedback.Seek(previous + deltaMs))
            }
            override fun onTogglePlay() = session.controls.togglePlay()
            override fun onBrightness(delta: Float) = show(GestureFeedback.Level(true, brightness.adjust(delta)))
            override fun onVolume(delta: Float) = show(GestureFeedback.Level(false, volume.adjust(delta)))
            override fun onZoom(fill: Boolean) {
                if (fill == fillState.value) return
                fillState.value = fill
                show(GestureFeedback.Zoom(fill))
            }
            override fun onScrub(scrub: Scrub?) {
                scrubState.value = scrub
            }
        }
    }

    PlayerLayout(
        writing = writing && canWrite,
        modifier = Modifier.focusRequester(keys).focusable().onPreviewKeyEvent { event ->
            if (canWrite && event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.E) {
                writing = !writing
                true
            } else {
                false
            }
        },
        pane = { WritingScreen(actions, onBack = { writing = false }, pane = true, playback = session.controls) },
    ) {
        VideoSurface(
            session.player,
            onControlsVisible = { controlsVisible = it },
            modifier = Modifier.fillMaxSize(),
            gestures = gestures.takeIf { !inPip },
            fill = fill,
            subtitleStyle = playbackSettings.subtitleStyle,
            hideControls = inPip,
        )
        GestureFeedbackView(feedback)

        // Top bar that shows and hides with the player's own controls.
        AnimatedVisibility(controlsVisible && !inPip, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopStart)) {
            Row(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(item?.displayTitle.orEmpty(), color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = listOfNotNull(item?.episodeLabel, "Downloaded".takeIf { local }, plan?.method?.takeIf { it != PlayMethod.DIRECT_PLAY }?.let { if (it == PlayMethod.TRANSCODE) "Transcoding" else "Direct stream" })
                    if (sub.isNotEmpty()) Text(sub.joinToString(" · "), color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
                if (canWrite && !writing) IconButton(onClick = { writing = true }) { Icon(Icons.Default.Edit, "Write beside the video (Ctrl+E)", tint = Color.White) }
                TextButton(onClick = { dialog = PlayerDialog.AUDIO }, enabled = plan != null) { Text("Audio", color = Color.White) }
                TextButton(onClick = { dialog = PlayerDialog.SUBTITLES }, enabled = plan != null) { Text("Subtitles", color = Color.White) }
                IconButton(onClick = { dialog = PlayerDialog.INFO }, enabled = plan != null) { Icon(Icons.Default.Info, "Playback info", tint = Color.White) }
                IconButton(onClick = { dialog = PlayerDialog.MORE }, enabled = plan != null) { Icon(Icons.Default.MoreVert, "More", tint = Color.White) }
            }
        }

        val upNextAt = PlaybackMoments.upNextAtMs(progress.durationMs, segments)
        val next = upNext
        val showUpNext = next != null && onPlayNext != null && !upNextHidden && !sleepAtEnd && upNextAt != null && progress.positionMs >= upNextAt && scrub == null
        SkipButton(PlaybackMoments.skippable(segments, progress.positionMs).takeIf { !showUpNext && scrub == null }) { session.seekTo(it.endMs) }
        if (showUpNext && next != null) {
            val remaining = ((progress.durationMs - progress.positionMs) / 1000).toInt()
            UpNextCard(next, actions.server, seconds = remaining.coerceIn(5, 15), onPlay = { onPlayNext?.invoke(next) }, onHide = { upNextHidden = true })
        }
        scrub?.let { s -> item?.let { TrickplayPreview(actions.server, it.id, trickplay, s.positionMs, s.x, s.barTop) } }

        notice?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.Center).padding(24.dp).width(420.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium).padding(16.dp),
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.Center).padding(24.dp)) }
    }

    val p = plan
    when {
        p == null || dialog == PlayerDialog.NONE -> Unit
        dialog == PlayerDialog.AUDIO -> TrackPicker("Audio", p.audioChoices(), p.audioIndex, { session.selectTrack(StreamKind.AUDIO, it); dialog = PlayerDialog.NONE }) { dialog = PlayerDialog.NONE }
        dialog == PlayerDialog.SUBTITLES -> TrackPicker("Subtitles", p.subtitleChoices(), p.subtitleIndex, { session.selectTrack(StreamKind.SUBTITLE, it); dialog = PlayerDialog.NONE }) { dialog = PlayerDialog.NONE }
        dialog == PlayerDialog.MORE -> PlayerMoreSheet(
            actions = actions,
            session = session,
            sleepAt = sleepAt,
            sleepAtEnd = sleepAtEnd,
            onSleep = { at, end -> sleepAt = at; sleepAtEnd = end },
            onPictureInPicture = { dialog = PlayerDialog.NONE; pip.enterNow(aspect) },
            onPlayedElsewhere = { dialog = PlayerDialog.NONE; onExit() },
            onDismiss = { dialog = PlayerDialog.NONE },
        )
        dialog == PlayerDialog.INFO -> AlertDialog(
            onDismissRequest = { dialog = PlayerDialog.NONE },
            title = { Text("Playback info") },
            text = {
                Column {
                    p.infoRows(videoDecoder, audioDecoder).forEach { (label, value) ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
                            Text(value, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = PlayerDialog.NONE }) { Text("Close") } },
        )
    }
}

@Composable
private fun TrackPicker(title: String, choices: List<TrackChoice>, selected: Int?, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (choices.isEmpty()) Text("No tracks available")
            LazyColumn {
                items(choices, key = { it.index }) { choice ->
                    ListItem(
                        modifier = Modifier.clickable { onSelect(choice.index) },
                        leadingContent = { RadioButton(selected = choice.index == selected, onClick = { onSelect(choice.index) }) },
                        headlineContent = { Text(choice.title) },
                        supportingContent = choice.detail?.let { { Text(it) } },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * The video alone, or with the writing [pane]: side by side when the screen is wide, video on
 * top when it's upright (so the keyboard has room). The video keeps playing either way.
 */
@Composable
internal fun PlayerLayout(writing: Boolean, modifier: Modifier, pane: @Composable () -> Unit, video: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black)) {
        val paneWidth = (maxWidth * 0.45f).coerceIn(320.dp, 560.dp)
        when {
            !writing -> Box(Modifier.fillMaxSize(), content = video)
            maxWidth > maxHeight -> Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight(), content = video)
                Surface(Modifier.width(paneWidth).fillMaxHeight(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Top))) { pane() }
                }
            }
            else -> Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f), content = video)
                Surface(Modifier.weight(1f).fillMaxWidth(), color = MaterialTheme.colorScheme.background) { pane() }
            }
        }
    }
}
