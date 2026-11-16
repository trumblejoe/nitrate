package dev.nitrate.mobile.ui

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import dev.nitrate.core.playback.WritingSplit
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.rememberCoroutineScope
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
import dev.nitrate.mobile.platform.rememberBrightnessControl
import dev.nitrate.core.episodes.UpNext
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.playback.PlaybackMoments
import kotlinx.coroutines.delay
import dev.nitrate.mobile.platform.VideoSurface

private enum class PlayerDialog { NONE, AUDIO, SUBTITLES, INFO, MEDIA_INFO, MORE, CHAPTERS, FIX, CHAT }

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
    /** A recap clip: plays from [startMs] to here, unreported, then ends. */
    clipEndMs: Long? = null,
) {
    val context = LocalContext.current
    val downloads = actions.container.downloads
    var upNext by remember { mutableStateOf<MediaItem?>(null) }
    var upNextHidden by remember { mutableStateOf(false) }
    // At the end: roll into the next episode unless Up Next was dismissed.
    var sleepAtEndRef by remember { mutableStateOf(false) }
    val ended by rememberUpdatedState {
        val next = upNext
        when {
            // "Sleep at the end of this": stop here, not on to the next episode or queue item.
            sleepAtEndRef -> onExit()
            // The next episode rolls on; the next film in a collection waits to be asked for.
            next != null && onPlayNext != null && !upNextHidden && next.isEpisode -> onPlayNext(next)
            else -> onEnded()
        }
    }
    var logMessage by remember { mutableStateOf<String?>(null) }
    val logScope = rememberCoroutineScope()
    val session = remember(itemId) {
        PlaybackSession(
            context, actions.server, actions.container.playbackSettings, deviceNoun = "This device", onEnded = { ended() },
            trackMemory = actions.container.trackMemory, describedAudio = actions.container::prefersDescribedAudio,
        )
            .also {
                if (clipEndMs != null) it.loadClip(itemId, startMs ?: 0, clipEndMs)
                else if (!local) {
                    it.followGroup(actions.container.syncPlay)
                    it.load(itemId, fromStart, startMs)
                }
            }
    }
    // Watch-together chat, while this device watches in a group (Companion plugin on the server).
    val syncGroup = actions.container.syncPlay.group.collectAsState().value
    val chatGroup = actions.container.chat.groupId.collectAsState().value?.takeIf { !local && syncGroup != null && it == syncGroup.id }
    if (local) {
        LaunchedEffect(session) {
            // Downloads saved before they kept the server's track picks learn them now (if it answers).
            val record = downloads.find(itemId)?.let { downloads.withServerDefaults(it) }
            // Converted before downloads were indexed: give it a length and seeking first.
            record?.let { downloads.makeSeekable(it) }
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
        // Shown in a PiP window the activity is only paused, so a stop here means the window was
        // closed (Android stops the activity before telling it PiP ended): same as leaving.
        when {
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
    val volume = remember { dev.nitrate.mobile.platform.VolumeControl(context) }
    val view = androidx.compose.ui.platform.LocalView.current
    val swipesOn by rememberUpdatedState(playbackSettings.swipeControls)

    // The next episode in viewing order: from the server, or from downloads when offline.
    LaunchedEffect(item?.id) {
        val current = item ?: return@LaunchedEffect
        if (onPlayNext == null) return@LaunchedEffect
        if (current.kind == dev.nitrate.core.model.ItemKind.MOVIE) {
            // A film in a saga: the next one, offered at the credits.
            if (!local) upNext = runCatching { actions.server.nextInCollection(current) }.getOrNull()
            return@LaunchedEffect
        }
        if (!current.isEpisode) return@LaunchedEffect
        upNext = (if (local) null else current.seriesId?.let { series -> runCatching { UpNext.after(current, actions.server.allEpisodes(series)) }.getOrNull() })
            ?: downloads.nextDownloaded(current)
    }
    // Gesture feedback fades shortly after the last change.
    LaunchedEffect(feedbackAt) {
        // A level stays a little longer, so its "Set to 50%" can be reached.
        delay(if (feedback is GestureFeedback.Level) 3_000 else 800)
        feedback = null
    }
    fun show(f: GestureFeedback) {
        feedback = f
        feedbackAt = System.nanoTime()
    }
    // The swipe under way: its level follows the finger and rests at 50% for a stretch.
    var drag by remember { mutableStateOf<dev.nitrate.core.playback.LevelDrag?>(null) }
    fun setLevel(isBrightness: Boolean, level: Float) =
        show(GestureFeedback.Level(isBrightness, if (isBrightness) brightness.set(level) else volume.set(level)))
    val gestures = remember(session) {
        object : PlayerGestures {
            override val swipes: Boolean get() = swipesOn
            override fun onSwipe(isBrightness: Boolean, delta: Float) {
                val d = drag ?: dev.nitrate.core.playback.LevelDrag(if (isBrightness) brightness.level else volume.level).also { drag = it }
                setLevel(isBrightness, d.move(delta))
                if (d.reachedMiddle) view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            }
            override fun onSwipeEnd() { drag = null }
            override fun onSeek(deltaMs: Long) {
                // Repeated taps on the same side add up: +10s, +20s...
                val previous = (feedback as? GestureFeedback.Seek)?.totalMs?.takeIf { (it > 0) == (deltaMs > 0) } ?: 0L
                session.seekTo(session.player.currentPosition + deltaMs)
                show(GestureFeedback.Seek(previous + deltaMs))
            }
            override fun onTogglePlay() = session.controls.togglePlay()
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

    val playingItem by session.item.collectAsState()
    PlayerLayout(
        writing = writing && canWrite,
        split = playbackSettings.writingSplit,
        videoAspect = aspect?.toFloat(),
        modifier = Modifier.focusRequester(keys).focusable().onPreviewKeyEvent { event ->
            if (canWrite && event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.E) {
                writing = !writing
                true
            } else {
                false
            }
        },
        pane = {
            WritingScreen(
                actions, onBack = { writing = false }, pane = true, playback = session.controls,
                headerActions = {
                    SplitChooser(playbackSettings.writingSplit) { choice ->
                        logScope.launch { actions.container.playbackSettings.update { it.copy(writingSplit = choice) } }
                    }
                },
            )
        },
    ) {
        VideoSurface(
            session.player,
            onControlsVisible = { controlsVisible = it },
            modifier = Modifier.fillMaxSize(),
            gestures = gestures.takeIf { !inPip },
            fill = fill,
            subtitleStyle = playbackSettings.subtitleStyle,
            styledSubtitle = plan?.styledSubtitle == true,
            assTrack = session.assTrack.collectAsState().value,
            subtitleOffsetMs = session.subtitleOffsetMs.collectAsState().value,
            hideControls = inPip,
            coverUrl = playingItem?.takeIf { it.mediaKind == dev.nitrate.core.model.MediaKind.AUDIO }?.let { actions.server.imageUrl(it, dev.nitrate.core.model.ImageKind.PRIMARY, 720) },
        )
        GestureFeedbackView(feedback, onMiddle = { isBrightness -> setLevel(isBrightness, 0.5f) })

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
                if ((item?.chapters?.size ?: 0) > 1) TextButton(onClick = { dialog = PlayerDialog.CHAPTERS }) { Text("Chapters", color = Color.White) }
                TextButton(onClick = { dialog = PlayerDialog.AUDIO }, enabled = plan != null) { Text("Audio", color = Color.White) }
                TextButton(onClick = { dialog = PlayerDialog.SUBTITLES }, enabled = plan != null) { Text("Subtitles", color = Color.White) }
                if (chatGroup != null) IconButton(onClick = { dialog = PlayerDialog.CHAT }) { Icon(Icons.Default.Email, "Chat", tint = Color.White) }
                IconButton(onClick = { dialog = PlayerDialog.INFO }, enabled = plan != null) { Icon(Icons.Default.Info, "Playback info", tint = Color.White) }
                IconButton(onClick = { dialog = PlayerDialog.MORE }, enabled = plan != null) { Icon(Icons.Default.MoreVert, "More", tint = Color.White) }
            }
        }

        val next = upNext
        val film = next != null && !next.isEpisode
        val upNextAt = if (film) dev.nitrate.core.playback.NextInCollection.offerAtMs(progress.durationMs, segments, item?.chapters.orEmpty())
            else PlaybackMoments.upNextAtMs(progress.durationMs, segments)
        val showUpNext = next != null && onPlayNext != null && !upNextHidden && !sleepAtEnd && upNextAt != null && progress.positionMs >= upNextAt && scrub == null
        SkipButton(PlaybackMoments.skippable(segments, progress.positionMs).takeIf { !showUpNext && scrub == null }) { session.seekTo(it.endMs) }
        if (showUpNext && next != null) {
            val remaining = ((progress.durationMs - progress.positionMs) / 1000).toInt()
            UpNextCard(next, actions.server, seconds = remaining.coerceIn(5, 15).takeIf { !film }, onPlay = { onPlayNext?.invoke(next) }, onHide = { upNextHidden = true })
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
        if (chatGroup != null && dialog != PlayerDialog.CHAT) ChatToasts(actions.container.chat, Modifier.align(Alignment.CenterStart).padding(start = 16.dp))
    }

    val p = plan
    when {
        p == null || dialog == PlayerDialog.NONE -> Unit
        dialog == PlayerDialog.AUDIO -> TrackPicker("Audio", p.audioChoices(), p.audioIndex, { session.selectTrack(StreamKind.AUDIO, it); dialog = PlayerDialog.NONE }) { dialog = PlayerDialog.NONE }
        dialog == PlayerDialog.SUBTITLES -> TrackPicker("Subtitles", p.subtitleChoices(), p.subtitleIndex, { session.selectTrack(StreamKind.SUBTITLE, it); dialog = PlayerDialog.NONE }) { dialog = PlayerDialog.NONE }
        dialog == PlayerDialog.CHAPTERS && item != null -> ChapterSheet(item!!, actions.server, session.player.currentPosition, onSeek = { session.seekTo(it); dialog = PlayerDialog.NONE }) { dialog = PlayerDialog.NONE }
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
        dialog == PlayerDialog.CHAT -> ChatSheet(actions.container.chat, "Chat" + (syncGroup?.name?.let { " · $it" } ?: ""), positionMs = { session.player.currentPosition }) { dialog = PlayerDialog.NONE }
        dialog == PlayerDialog.FIX -> {
            val diagnosis by androidx.compose.runtime.produceState<dev.nitrate.core.playback.PlaybackDiagnosis?>(null, p) {
                value = session.diagnosis(actions.container.capabilities)
            }
            PlaybackFixDialog(
                diagnosis = diagnosis,
                error = null,
                onFix = { fix -> session.applyFix(fix); dialog = PlayerDialog.NONE },
                onDismiss = { dialog = PlayerDialog.NONE },
            )
        }
        dialog == PlayerDialog.MEDIA_INFO -> {
            val text by androidx.compose.runtime.produceState<String?>(null, p) { value = session.mediaInfo(playbackSettings.subtitleStyle.styled) }
            MediaInfoDialog(text) { dialog = PlayerDialog.INFO }
        }
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
                    logMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = accentColor(), modifier = Modifier.padding(top = 10.dp)) }
                    androidx.compose.material3.OutlinedButton(onClick = { dialog = PlayerDialog.MEDIA_INFO }, modifier = Modifier.padding(top = 12.dp)) {
                        Icon(CopyIcon, null, modifier = Modifier.size(16.dp))
                        Text("Media info", modifier = Modifier.padding(start = 8.dp))
                    }
                    if (p.method != dev.nitrate.core.model.PlayMethod.DIRECT_PLAY && !local) {
                        androidx.compose.material3.OutlinedButton(onClick = { dialog = PlayerDialog.FIX }, modifier = Modifier.padding(top = 12.dp)) { Text("Why converting? Fix it") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = PlayerDialog.NONE }) { Text("Close") } },
            dismissButton = {
                Row {
                    // A report of this playback: to the Jellyfin server's logs, or out through the share sheet.
                    TextButton(onClick = {
                        logMessage = "Sending…"
                        logScope.launch {
                            logMessage = actions.container.sendPlaybackReport(session).fold(
                                { name -> "Saved on your server as $name (Jellyfin Dashboard → Logs)" },
                                { e -> "Couldn't send: ${e.message ?: e::class.simpleName}" },
                            )
                        }
                    }) { Text("Send log") }
                    TextButton(onClick = {
                        logScope.launch {
                            val text = actions.container.playbackReport(session)
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(android.content.Intent.EXTRA_SUBJECT, "Nitrate playback log")
                                .putExtra(android.content.Intent.EXTRA_TEXT, text)
                            context.startActivity(android.content.Intent.createChooser(send, "Share playback log"))
                        }
                    }) { Text("Share") }
                }
            },
        )
    }
}

@Composable
internal fun TrackPicker(title: String, choices: List<TrackChoice>, selected: Int?, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
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
 * The video alone, or with the writing [pane] beside it or below it, as the user chose in the
 * pane ([split]; Auto picks whichever leaves the picture bigger for this window and video). The
 * video keeps playing either way.
 */
@Composable
internal fun PlayerLayout(
    writing: Boolean,
    split: WritingSplit,
    videoAspect: Float?,
    modifier: Modifier,
    pane: @Composable () -> Unit,
    video: @Composable BoxScope.() -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black)) {
        val w = maxWidth.value
        val h = maxHeight.value
        when {
            !writing -> Box(Modifier.fillMaxSize(), content = video)
            split.sideBySide(w, h, videoAspect) -> Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight(), content = video)
                Surface(Modifier.width(WritingSplit.sidePaneWidth(w).dp).fillMaxHeight(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Top))) { pane() }
                }
            }
            else -> Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier.fillMaxWidth().height(WritingSplit.stackedVideoHeight(w, h, videoAspect).dp)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
                    content = video,
                )
                Surface(Modifier.weight(1f).fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) { pane() }
                }
            }
        }
    }
}

/** The writing pane's choice of split: Auto, side by side, or video on top. Saved on this device. */
@Composable
private fun SplitChooser(current: WritingSplit, onChoose: (WritingSplit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(NitrateIcons.SplitView, "Split: ${current.label}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            WritingSplit.entries.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.label) },
                    onClick = { open = false; onChoose(choice) },
                    trailingIcon = if (choice == current) ({ Icon(Icons.Default.Check, null) }) else null,
                )
            }
        }
    }
}

/** The title's chapters with their pictures, and previous/next chapter. */
@Composable
private fun ChapterSheet(item: dev.nitrate.core.model.MediaItem, server: dev.nitrate.core.MediaServer, positionMs: Long, onSeek: (Long) -> Unit, onDismiss: () -> Unit) {
    val chapters = item.chapters
    val now = dev.nitrate.core.playback.Chapters.current(chapters, positionMs).coerceAtLeast(0)
    val list = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = (now - 1).coerceAtLeast(0))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chapters") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(state = list, modifier = Modifier.heightIn(max = 440.dp)) {
                items(chapters.size) { i ->
                    val c = chapters[i]
                    Row(
                        Modifier.fillMaxWidth().clickable { onSeek(c.startMs) }.padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            server.chapterImageUrl(item, i, 320), null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.width(112.dp).aspectRatio(16f / 9f).clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.08f)),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(dev.nitrate.core.playback.Chapters.title(chapters, i), fontWeight = if (i == now) FontWeight.Bold else null, color = if (i == now) accentColor() else Color.Unspecified)
                            Text(dev.nitrate.core.model.formatClock(c.startMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                dev.nitrate.core.playback.Chapters.previous(chapters, positionMs)?.let { at -> TextButton(onClick = { onSeek(at) }) { Text("Previous") } }
                dev.nitrate.core.playback.Chapters.next(chapters, positionMs)?.let { at -> TextButton(onClick = { onSeek(at) }) { Text("Next chapter") } }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
