package dev.nitrate.tv.ui.player

import kotlinx.coroutines.flow.map
import dev.nitrate.android.playback.applySubtitleStyle
import dev.nitrate.android.playback.assSubtitles
import dev.nitrate.android.playback.showCover
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.tv.material3.MaterialTheme
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


private enum class Panel { NONE, AUDIO, SUBTITLES, INFO, MORE, FIX, CHAT }

/** Keys that, with the controls hidden, only bring them back. */
private val WAKE_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)

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
    /** Tracks another device asked for when it sent this here (null: the usual picks). */
    audioIndex: Int? = null,
    subtitleIndex: Int? = null,
    /** Watch together: follows this device's group when it's in one. */
    syncPlay: dev.nitrate.core.syncplay.SyncPlayController? = null,
    /** Sends a playback report for this session to the server; returns the saved file's name. */
    sendLog: (suspend (PlaybackSession) -> Result<String>)? = null,
    /** What this TV reports, for "Why converting?" (left out: no fixer). */
    capabilities: dev.nitrate.core.playback.DeviceCapabilities? = null,
    /** Watch-together chat (Companion plugin), shown while this TV is in a group. */
    chat: dev.nitrate.core.companion.GroupChat? = null,
    /** A recap clip: plays from [startMs] to here, unreported, then ends. */
    clipEndMs: Long? = null,
    /** The user's audio-description preference. */
    describedAudio: suspend () -> Boolean = { false },
    /** Plays the next episode, or the next film in a collection (null: nothing follows, e.g. in a queue). */
    onPlayNext: ((MediaItem) -> Unit)? = null,
    /** "Still watching?" (episodes rolling on with nothing pressed), and putting things back. */
    stillWatching: dev.nitrate.core.playback.StillWatching? = null,
    stillWatchingRewind: ((dev.nitrate.core.playback.StillWatching.Rewind?, (MediaItem?) -> Unit) -> Unit)? = null,
) {
    val context = LocalContext.current
    // "End of this" sleep timer: when it ends, leave instead of rolling on through a queue.
    val sleepAtEndState = remember { mutableStateOf(false) }
    var upNext by remember { mutableStateOf<MediaItem?>(null) }
    var upNextHidden by remember { mutableStateOf(false) }
    var stillAsk by remember { mutableStateOf<MediaItem?>(null) }
    val askStill by playbackSettings.settings.map { it.askStillWatching }.collectAsState(true)
    val rollOn by rememberUpdatedState { next: MediaItem ->
        if (askStill && stillWatching?.shouldAsk() == true) stillAsk = next
        else { stillWatching?.rollingOn(next.id); onPlayNext?.invoke(next) }
    }
    val ended by rememberUpdatedState {
        val next = upNext
        when {
            sleepAtEndState.value -> onExit()
            // The next episode rolls on; the next film in a collection waits to be asked for.
            next != null && next.isEpisode && !upNextHidden && onPlayNext != null -> rollOn(next)
            else -> onEnded()
        }
    }
    // Negotiation, track mapping, reporting and transcode fallback live in the shared session.
    val session = remember(itemId) {
        PlaybackSession(context, repository, playbackSettings, deviceNoun = "This TV", onEnded = { ended() }, trackMemory = trackMemory, describedAudio = describedAudio)
            .also {
                if (clipEndMs != null) it.loadClip(itemId, startMs ?: 0, clipEndMs)
                else {
                    syncPlay?.let(it::followGroup)
                    it.load(itemId, fromStart, startMs, audioIndex, subtitleIndex)
                }
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
    val assTrack by session.assTrack.collectAsState()
    val subtitleOffset by session.subtitleOffsetMs.collectAsState()
    // The TV's refresh rate follows the video's frame rate, as set in Playback settings.
    FrameRateMatcher(session, settingsNow.frameRateMatch)
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
    // The result of "Send log", shown like a notice for a while.
    var logMessage by remember { mutableStateOf<String?>(null) }
    val logScope = rememberCoroutineScope()
    LaunchedEffect(logMessage) { if (logMessage != null) { kotlinx.coroutines.delay(12_000); logMessage = null } }
    // Chat is there while this TV watches in a group and the server has the Companion plugin.
    val syncGroup = syncPlay?.group?.collectAsState()?.value
    val chatGroup = chat?.groupId?.collectAsState()?.value?.takeIf { syncGroup != null && it == syncGroup.id }
    val videoDecoder by session.videoDecoder.collectAsState()
    val audioDecoder by session.audioDecoder.collectAsState()
    val segments by session.segments.collectAsState()
    // A song's lyrics, when the server has them; shown beside the cover when asked for.
    val lyrics by androidx.compose.runtime.produceState<dev.nitrate.core.music.Lyrics?>(null, item?.id) {
        value = item?.takeIf { it.mediaKind == dev.nitrate.core.model.MediaKind.AUDIO }?.let { runCatching { repository.lyrics(it.id) }.getOrNull() }
    }
    var showLyrics by remember { mutableStateOf(false) }
    val easy = dev.nitrate.tv.ui.components.LocalDisplayPrefs.current.easyMode
    // What follows: the next episode in viewing order, or the next film in its collection.
    LaunchedEffect(item?.id) {
        val current = item ?: return@LaunchedEffect
        upNext = null
        upNextHidden = false
        if (onPlayNext == null || clipEndMs != null) return@LaunchedEffect
        upNext = runCatching {
            when {
                current.isEpisode -> current.seriesId?.let { dev.nitrate.core.episodes.UpNext.after(current, repository.allEpisodes(it)) }
                else -> repository.nextInCollection(current)
            }
        }.getOrNull()
    }

    var controlsVisible by remember { mutableStateOf(true) }
    var lastInput by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var panel by remember { mutableStateOf(Panel.NONE) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var openPanel by remember { mutableStateOf<PlayerPanel?>(null) }
    var creditAction by remember(item?.id) { mutableStateOf<dev.nitrate.core.playback.CreditSkipper.Action?>(null) }
    var panelFocused by remember { mutableStateOf(false) }
    var stripOpen by remember { mutableStateOf(false) }
    var person by remember { mutableStateOf<dev.nitrate.core.model.Person?>(null) }
    val showCast = dev.nitrate.tv.ui.components.LocalDisplayPrefs.current.cast
    val playbackControls = session.controls
    val rootFocus = remember { FocusRequester() }
    val controlFocus = remember { ControlFocus() }
    // The OK or arrow press that brings the controls back only does that (its release is ignored too).
    var swallowRelease by remember { mutableStateOf<Key?>(null) }
    var rootFocused by remember { mutableStateOf(false) }

    // Started by itself (rolled on) or asked for; any key after is a press.
    LaunchedEffect(item?.id) {
        val i = item ?: return@LaunchedEffect
        if (clipEndMs == null) stillWatching?.started(i.id, i.user.positionMs, i.user.played)
    }
    var lastPress by remember { mutableLongStateOf(0L) }
    fun pressed() {
        val i = item ?: return
        val now = System.currentTimeMillis()
        if (now - lastPress < 2_000 || clipEndMs != null) return
        lastPress = now
        stillWatching?.input(i.id, player.currentPosition, i.user.played)
    }

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
            if (controlsVisible && playing && panel == Panel.NONE && person == null && System.currentTimeMillis() - lastInput > CONTROLS_TIMEOUT_MS) {
                controlsVisible = false
                // Never pull focus away from someone typing in a side panel.
                if (!panelFocused) runCatching { rootFocus.requestFocus() }
            }
            delay(250)
        }
    }

    LaunchedEffect(controlsVisible) {
        // A credits-scene banner keeps focus on its button, so OK acts on it.
        if (controlsVisible && creditAction == null) controlFocus.playPause()
    }
    // A pop-up closed: the controls stay up a while longer (presses in it weren't counted) and focus
    // goes back to the button that opened it, so one Back closes it and the next hides the controls.
    var panelWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(panel) {
        touch()
        if (panel != Panel.NONE) { panelWasOpen = true; return@LaunchedEffect }
        if (!panelWasOpen) return@LaunchedEffect
        panelWasOpen = false
        // Once the dialog's window has gone and this one has focus again.
        delay(50)
        controlFocus.restore()
    }

    fun togglePanel(target: PlayerPanel) {
        if (openPanel == target) {
            openPanel = null
            touch()
        } else {
            openPanel = target
        }
    }

    val foldedStrips = if (easy) 0 else listOf(
        (item?.chapters?.size ?: 0) > 1,
        showCast && item?.people.orEmpty().any { it.imageTag != null || !it.role.isNullOrBlank() },
    ).count { it }
    val nextUp = upNext
    val film = nextUp != null && !nextUp.isEpisode
    val creditScenes by session.creditScenes.collectAsState()
    val upNextAt = (if (film) dev.nitrate.core.playback.NextInCollection.offerAtMs(duration, segments, item?.chapters.orEmpty())
        else dev.nitrate.core.playback.PlaybackMoments.upNextAtMs(duration, segments))
        .let { at -> creditScenes?.upNextAt(at, duration) ?: at }

    // Scenes in the credits: skipped to when the credits start (not in a watch-together group,
    // where a seek moves everyone), else offered; a heads-up when only the tags know of one.
    val autoSkip = dev.nitrate.tv.ui.components.LocalDisplayPrefs.current.creditScenes && syncGroup == null
    val skipper = remember(creditScenes, autoSkip) { creditScenes?.let { dev.nitrate.core.playback.CreditSkipper(it, autoSkip) } }
    LaunchedEffect(skipper, position / 500) {
        val a = skipper?.at(position) ?: return@LaunchedEffect
        if (a is dev.nitrate.core.playback.CreditSkipper.Action.Skip) player.seekTo(a.toMs)
        creditAction = a
    }
    val showUpNext = nextUp != null && onPlayNext != null && !upNextHidden && !sleepAtEndState.value && panel == Panel.NONE &&
        upNextAt != null && position >= upNextAt

    BackHandler {
        when {
            showUpNext -> upNextHidden = true
            creditAction != null -> { creditAction = null; runCatching { rootFocus.requestFocus() } }
            panel != Panel.NONE -> panel = Panel.NONE
            openPanel != null -> { openPanel = null; touch() }
            // The same whether playing or paused: Back hides the controls, then leaves.
            controlsVisible -> { controlsVisible = false; runCatching { rootFocus.requestFocus() } }
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
            .onPreviewKeyEvent { event ->
                pressed()
                // Back is handled on its own (BackHandler): showing the controls here would make
                // it hide them again instead of leaving.
                if (event.key == Key.Back || event.key == Key.Escape) return@onPreviewKeyEvent false
                val wasHidden = !controlsVisible
                touch()
                // With the controls hidden, OK or an arrow only brings them back (focus on play/pause),
                // never also presses or moves on the button that gets focus.
                if (event.key in WAKE_KEYS) {
                    if (event.type == KeyEventType.KeyDown && wasHidden && rootFocused) { swallowRelease = event.key; return@onPreviewKeyEvent true }
                    if (event.type == KeyEventType.KeyDown && swallowRelease == event.key) return@onPreviewKeyEvent true
                    if (event.type == KeyEventType.KeyUp && swallowRelease == event.key) { swallowRelease = null; return@onPreviewKeyEvent true }
                }
                false
            }
            // While controls are hidden the root has focus; keys act directly on playback.
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
                when (event.key) {
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { if (player.isPlaying) player.pause() else player.play(); true }
                    Key.MediaFastForward -> { player.seekTo(player.currentPosition + SEEK_FORWARD_MS); true }
                    Key.MediaRewind -> { player.seekTo(player.currentPosition - SEEK_BACK_MS); true }
                    Key.Captions -> { panel = Panel.SUBTITLES; true }
                    // The remote's menu key (≡ on Fire TV) opens More.
                    Key.Menu -> if (!easy && plan != null) { panel = Panel.MORE; true } else false
                    // Next/previous: by chapter, when the title has them.
                    Key.MediaNext -> item?.chapters?.let { dev.nitrate.core.playback.Chapters.next(it, player.currentPosition) }?.let { player.seekTo(it); true } ?: false
                    Key.MediaPrevious -> item?.chapters?.let { dev.nitrate.core.playback.Chapters.previous(it, player.currentPosition) }?.let { player.seekTo(it); true } ?: false
                    else -> false
                }
            }
            .onFocusChanged { rootFocused = it.isFocused }
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
            update = {
                it.applySubtitleStyle(settingsNow.subtitleStyle, plan?.styledSubtitle == true)
                // Styled subtitles drawn by libass in their own layer over the picture.
                it.assSubtitles().apply {
                    this.player = it.player
                    offsetMs = subtitleOffset
                    setTrack(assTrack)
                }
                it.showCover(item?.takeIf { i -> i.mediaKind == dev.nitrate.core.model.MediaKind.AUDIO }?.let { i -> repository.imageUrl(i, dev.nitrate.core.model.ImageKind.PRIMARY, 720) })
            },
            modifier = Modifier.fillMaxSize(),
        )

        lyrics?.takeIf { showLyrics }?.let { LyricsView(it, position, Modifier.align(Alignment.CenterEnd)) }

        val p = plan
        androidx.compose.animation.AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomStart)) {
            // Info and Chat open beside the video: their buttons close them again.
            fun toggle(target: Panel) { panel = if (panel == target) Panel.NONE else target }
            val ready = p != null
            val castShown = if (easy || !showCast) emptyList() else item?.people.orEmpty().filter { it.imageTag != null || !it.role.isNullOrBlank() }.take(30)
            PlayerControls(
                item = item,
                plan = p,
                position = position,
                duration = duration,
                playing = playing,
                focus = controlFocus,
                onPlayPause = { if (player.isPlaying) player.pause() else player.play() },
                onSeek = { delta ->
                    // Duration is 0 while unknown (live TV, still loading): don't clamp to it.
                    val target = (player.currentPosition + delta).coerceAtLeast(0)
                    player.seekTo(if (duration > 0) target.coerceAtMost(duration) else target)
                },
                // Easy mode: play/pause, the seek bar, audio and subtitles (and lyrics) only.
                buttons = listOfNotNull(
                    PlayerAction("Audio", ready) { panel = Panel.AUDIO },
                    PlayerAction("Subtitles", ready) { panel = Panel.SUBTITLES },
                    PlayerAction(if (panel == Panel.INFO) "Hide info" else "Info", ready) { toggle(Panel.INFO) }.takeIf { !easy },
                    PlayerAction("More", ready) { panel = Panel.MORE }.takeIf { !easy },
                    PlayerAction(if (showLyrics) "Hide lyrics" else "Lyrics") { showLyrics = !showLyrics }.takeIf { lyrics != null },
                    PlayerAction("Why converting?") { panel = Panel.FIX }.takeIf { !easy && capabilities != null && p != null && p.method != dev.nitrate.core.model.PlayMethod.DIRECT_PLAY },
                    PlayerAction(if (panel == Panel.CHAT) "Hide chat" else "Chat") { toggle(Panel.CHAT) }.takeIf { !easy && chatGroup != null },
                ) + (if (easy) emptyList() else panels.map { PlayerAction(it.panelLabel) { togglePanel(it) } }) +
                    listOfNotNull(sendLog?.takeIf { !easy }?.let { send ->
                        PlayerAction("Send log") {
                            logMessage = "Sending the playback log…"
                            logScope.launch {
                                logMessage = send(session).fold(
                                    { name -> "Playback log saved on your server as $name (Jellyfin Dashboard → Logs)." },
                                    { e -> "Couldn't send the log: ${e.message ?: e::class.simpleName}" },
                                )
                            }
                        }
                    }),
                // Easy mode keeps to the essentials: no chapter strip.
                chapters = if (easy) emptyList() else item?.chapters.orEmpty(),
                chapterImage = { i -> item?.let { repository.chapterImageUrl(it, i, 320) } },
                onChapter = { player.seekTo(it) },
                // The cast (as on the details page, when shown there); OK opens a pop-up over the video.
                cast = castShown,
                personImage = repository::personImageUrl,
                onPerson = { person = it },
                onStripOpen = { stripOpen = it },
            )
        }

        if (chat != null && chatGroup != null) {
            // Pop-ups from the others, out of the way of the controls and any open chat panel.
            if (panel != Panel.CHAT) ChatToastsSlot(chat, Modifier.align(Alignment.CenterStart).padding(start = 32.dp))
            if (panel == Panel.CHAT) {
                dev.nitrate.tv.ui.together.ChatPanel(chat, syncGroup?.name, positionMs = { player.currentPosition }, modifier = Modifier.align(Alignment.TopEnd))
            }
        }

        if (showUpNext && nextUp != null) {
            val remaining = ((duration - position) / 1000).toInt()
            UpNextCard(
                nextUp, repository, seconds = remaining.coerceIn(5, 15).takeIf { !film },
                onPlay = { onPlayNext?.invoke(nextUp) },
                onCountdownEnd = { rollOn(nextUp) },
                onHide = { upNextHidden = true; runCatching { rootFocus.requestFocus() } },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = if (controlsVisible) controlsHeight(foldedStrips, stripOpen) else 48.dp),
            )
        }

        SecondSubtitleLine(session, settingsNow.subtitleStyle.scale, Modifier.align(Alignment.TopCenter).padding(top = 36.dp, start = 96.dp, end = 96.dp))

        // A stereo film or episode is playing on a new output with more than two speakers: a quiet
        // note once a day per output, never a dialog over the video or a change of focus.
        val output by session.soundOutput.collectAsState()
        val stereoNow = session.audioPath.collectAsState().value.inChannels == 2 && session.item.collectAsState().value?.mediaKind != dev.nitrate.core.model.MediaKind.AUDIO
        var speakerBanner by remember { mutableStateOf<dev.nitrate.core.playback.SoundOutput?>(null) }
        LaunchedEffect(output?.key, stereoNow) {
            val o = output ?: return@LaunchedEffect
            val s = settingsNow
            if (stereoNow && s.upmixStereo && dev.nitrate.core.playback.Speakers.shouldAsk(o, s.speakerLayouts, s.speakerAskLater, System.currentTimeMillis())) {
                speakerBanner = o
                playbackSettings.update { it.copy(speakerAskLater = dev.nitrate.core.playback.Speakers.askLater(it.speakerAskLater, o, System.currentTimeMillis())) }
            }
        }
        speakerBanner?.let { o -> SpeakersBanner(o) { speakerBanner = null } }

        stillAsk?.let { next ->
            val rewind = remember(next) { stillWatching?.rewind() }
            StillWatchingDialog(
                next = next,
                rewind = rewind,
                onKeepWatching = { stillAsk = null; stillWatching?.clear(); onPlayNext?.invoke(next) },
                onGoBack = {
                    stillAsk = null
                    stillWatchingRewind?.invoke(rewind) { back -> if (back != null) onPlayNext?.invoke(back) else onExit() } ?: onExit()
                },
                onStop = { stillAsk = null; stillWatching?.clear(); onExit() },
                // Dozed off: put things back as they were at the last press, and stop.
                onNoAnswer = { stillWatchingRewind?.invoke(rewind) { onExit() } ?: onExit() },
            )
        }

        creditAction?.takeIf { !showUpNext }?.let { a ->
            CreditSceneBanner(
                a,
                onAct = { act ->
                    when (act) {
                        is dev.nitrate.core.playback.CreditSkipper.Action.Skip -> player.seekTo(act.backMs)
                        is dev.nitrate.core.playback.CreditSkipper.Action.Offer -> player.seekTo(act.toMs)
                        else -> Unit
                    }
                },
                onDone = {
                    creditAction = null
                    if (!controlsVisible) runCatching { rootFocus.requestFocus() }
                },
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = if (controlsVisible) controlsHeight(foldedStrips, stripOpen) else 48.dp),
            )
        }

        if (panel == Panel.INFO && p != null) {
            PlaybackInfoPanel(p, videoDecoder, audioDecoder, Modifier.align(Alignment.TopEnd).padding(32.dp), audio = session.audioPath.collectAsState().value,
                video = androidx.compose.runtime.produceState<dev.nitrate.core.playback.VideoPath?>(null, session) { session.videoPath().collect { value = it } }.value)
        }

        (logMessage ?: notice)?.let {
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
    if (panel == Panel.FIX && capabilities != null) {
        val diagnosis by androidx.compose.runtime.produceState<dev.nitrate.core.playback.PlaybackDiagnosis?>(null, plan) {
            value = session.diagnosis(capabilities)
        }
        PlaybackFixDialog(
            diagnosis = diagnosis,
            error = null,
            onFix = { fix -> session.applyFix(fix); panel = Panel.NONE },
            onDismiss = { panel = Panel.NONE },
        )
    }
    person?.let { who ->
        // Focus stays on their photo in the cast strip; the controls stay up a while longer.
        PersonDialog(repository, who, item, onDismiss = { person = null; touch() })
    }
    if (p != null && panel == Panel.AUDIO) {
        TrackDialog("Audio", p.audioChoices(), p.audioIndex, onSelect = { selectTrack(StreamKind.AUDIO, it); panel = Panel.NONE }, onDismiss = { panel = Panel.NONE })
    }
    if (p != null && panel == Panel.SUBTITLES) {
        TrackDialog("Subtitles", p.subtitleChoices(), p.subtitleIndex, onSelect = { selectTrack(StreamKind.SUBTITLE, it); panel = Panel.NONE }, onDismiss = { panel = Panel.NONE })
    }
}

@Composable
private fun ChatToastsSlot(chat: dev.nitrate.core.companion.GroupChat, modifier: Modifier) =
    dev.nitrate.tv.ui.together.ChatToasts(chat, modifier)

