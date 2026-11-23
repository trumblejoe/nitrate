package dev.nitrate.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.StreamKind
import dev.nitrate.core.model.formatClock
import dev.nitrate.core.playback.CreditSkipper
import dev.nitrate.core.playback.NextInCollection
import dev.nitrate.core.playback.PlaybackMoments
import dev.nitrate.core.playback.audioChoices
import dev.nitrate.core.playback.infoRows
import dev.nitrate.core.playback.subtitleChoices
import dev.nitrate.mobile.platform.DesktopVolume
import dev.nitrate.mobile.platform.DesktopWindow
import dev.nitrate.mobile.platform.PlatformBackHandler
import kotlinx.coroutines.launch

/**
 * The desktop player: libmpv (LGPL build) for the picture and sound, Nitrate's own controls over
 * it. Plays [itemId] from its resume point (or the start, or [startMs]); [local] plays a download.
 * Without libmpv (a build that has none) it says so.
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
    testPlay: ((dev.nitrate.core.playback.TestPlayResult) -> Unit)?,
) {
    val factory = DesktopMpv.factory
    if (factory == null) {
        NoPlayer(onExit)
        return
    }
    val container = actions.container
    val settings = remember(container) { DesktopPlayerSettings.shared(container.platform.filesDir) }
    val session = remember(itemId) {
        val s = settings.values.value
        val player = factory(
            MpvConfig(
                output = s.output,
                hardwareDecoding = s.hardwareDecoding,
                spdif = dev.nitrate.core.playback.DesktopPlayback.spdifOption(s.bitstream),
                volume = s.volume,
                userAgent = "Nitrate/${container.platform.identity.version}",
            ),
        )
        DesktopPlayerSession(container, reported = clipEndMs == null, player = player)
    }
    DisposableEffect(session) {
        if (local) session.loadLocal(itemId, fromStart, startMs) else session.load(itemId, fromStart, startMs)
        session.setVolume(settings.values.value.volume)
        container.remote.active = session
        DesktopVolume.handler = session::adjustVolume
        onDispose {
            if (container.remote.active === session) container.remote.active = null
            if (DesktopVolume.handler != null) DesktopVolume.handler = null
            settings.update { it.copy(volume = session.volume.value) }
            session.release()
        }
    }
    DesktopPlayer(actions, session, settings, local, onExit, onEnded, onPlayNext, clipEndMs, testPlay != null)
}

/** No libmpv in this build: what's missing, and back. */
@Composable
private fun NoPlayer(onExit: () -> Unit) {
    PlatformBackHandler(onBack = onExit)
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(24.dp)) {
            Text("Can't play here yet", color = Color.White, style = MaterialTheme.typography.headlineSmall)
            Text(DesktopMpv.missing, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center)
            Button(onClick = onExit) { Text("Back") }
        }
    }
}

/** What's open over the video besides the controls. */
private enum class Panel { AUDIO, SUBTITLES, CHAPTERS, MORE, INFO, CHAT, WITH }

/**
 * The player's screen for a [session]: the video, Nitrate's controls (shown on mouse movement,
 * hidden after a few seconds while playing), the keyboard (Space/K play-pause, ←/→ 10 s (with
 * Shift 30 s), ↑/↓ volume, M mute, F or a double-click full screen, Esc back), and the shared
 * overlays: skip intro/recap, Up Next, scenes in the credits, "Still watching?", group chat notes,
 * the With chip, a second subtitle line.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DesktopPlayer(
    actions: AppActions,
    session: DesktopPlayerSession,
    settings: DesktopPlayerSettings,
    local: Boolean,
    onExit: () -> Unit,
    onEnded: () -> Unit,
    onPlayNext: ((MediaItem) -> Unit)?,
    clipEndMs: Long?,
    testPlay: Boolean = false,
) {
    val container = actions.container
    val item by session.item.collectAsState()
    val plan by session.plan.collectAsState()
    val error by session.error.collectAsState()
    val positionMs by session.positionMs.collectAsState()
    val durationMs by session.durationMs.collectAsState()
    val segments by session.segments.collectAsState()
    val creditScenes by session.creditScenes.collectAsState()
    val ended by session.ended.collectAsState()
    val paused by session.paused.collectAsState()
    val buffering by session.buffering.collectAsState()
    val easy = LocalDisplayPrefs.current.easyMode
    var panel by remember { mutableStateOf<Panel?>(null) }
    var upNext by remember(item?.id) { mutableStateOf<MediaItem?>(null) }
    var inPlaylist by remember(item?.id) { mutableStateOf(false) }
    var upNextHidden by remember(item?.id) { mutableStateOf(false) }
    val fullscreen by DesktopWindow.fullscreen.collectAsState()

    // Controls: up while the mouse moves or something's pressed, gone a few seconds later while playing.
    var shownAt by remember { mutableLongStateOf(dev.nitrate.android.nowMs()) }
    var controls by remember { mutableStateOf(true) }
    var dragging by remember { mutableStateOf(false) }
    fun touch() { controls = true; shownAt = dev.nitrate.android.nowMs() }
    LaunchedEffect(controls, shownAt, paused, panel, dragging) {
        if (!controls || paused || panel != null || dragging) return@LaunchedEffect
        kotlinx.coroutines.delay(HIDE_AFTER_MS)
        controls = false
    }

    // "Still watching?": asked instead of rolling on when nothing's been pressed for a while.
    val still = container.stillWatching
    var stillAsk by remember { mutableStateOf<MediaItem?>(null) }
    val playerPrefs by container.playbackSettings.settings.collectAsState(null)
    LaunchedEffect(item?.id) { item?.let { i -> if (clipEndMs == null) still.started(i.id, i.user.positionMs, i.user.played) } }
    DisposableEffect(session) {
        session.onInput = { session.item.value?.let { i -> still.input(i.id, session.currentMs(), i.user.played) } }
        onDispose { session.onInput = null }
    }
    fun pressed() { touch(); session.onInput?.invoke() }
    fun rollOn(next: MediaItem) {
        if (playerPrefs?.askStillWatching != false && still.shouldAsk()) stillAsk = next
        else { still.rollingOn(next.id); onPlayNext?.invoke(next) }
    }

    // Sleep timer: pauses at a time, or stops at the end of this instead of rolling on.
    var sleepAt by remember { mutableStateOf<Long?>(null) }
    var sleepAtEnd by remember { mutableStateOf(false) }
    LaunchedEffect(sleepAt) {
        val at = sleepAt ?: return@LaunchedEffect
        kotlinx.coroutines.delay((at - dev.nitrate.android.nowMs()).coerceAtLeast(0))
        session.pause()
        sleepAt = null
    }

    // A recap clip ends at its end; an episode rolls on (unless Up Next was put away); else it ends.
    LaunchedEffect(positionMs, ended) {
        val next = upNext
        when {
            clipEndMs != null && positionMs >= clipEndMs -> onEnded()
            !ended -> Unit
            sleepAtEnd -> onExit()
            clipEndMs == null && next != null && (next.isEpisode || inPlaylist) && onPlayNext != null && !upNextHidden -> rollOn(next)
            else -> onEnded()
        }
    }
    // What comes next: the following episode, the playlist's next, or the next film of a small collection.
    LaunchedEffect(item?.id) {
        val current = item ?: return@LaunchedEffect
        if (onPlayNext == null) return@LaunchedEffect
        if (!local && clipEndMs == null) runCatching { container.playlists.contextOf(current) }.getOrNull()?.let { ctx ->
            inPlaylist = true
            upNext = ctx.next
            return@LaunchedEffect
        }
        upNext = when {
            current.kind == ItemKind.MOVIE -> runCatching { actions.server.nextInCollection(current) }.getOrNull()
            local -> container.downloads.nextDownloaded(current)
            current.isEpisode -> current.seriesId?.let { s -> runCatching { dev.nitrate.core.episodes.UpNext.after(current, actions.server.allEpisodes(s)) }.getOrNull() }
            else -> null
        }
    }

    // Group chat (Companion) and who this is watched with, as on the other players.
    val syncGroup = container.syncPlay.group.collectAsState().value
    val chatGroup = container.chat.groupId.collectAsState().value?.takeIf { !local && syncGroup != null && it == syncGroup.id }
    val groupChat = if (easy) null else rememberGroupChat(actions, item?.id, enabled = !local && clipEndMs == null) { session.currentMs() }
    val chatAvailable = groupChatAvailable(groupChat, chatGroup != null)
    val chatOn = groupChat?.on?.collectAsState()?.value ?: false
    val company = rememberWatchCompany(
        actions, item, enabled = !local && clipEndMs == null && !testPlay, groupChat,
        positionMs = { session.currentMs() }, durationMs = { session.durationMs.value },
    )
    val withChip = company?.chip?.collectAsState()?.value

    fun back() {
        when {
            panel != null -> panel = null
            fullscreen -> DesktopWindow.fullscreen.value = false
            else -> onExit()
        }
    }
    PlatformBackHandler(onBack = ::back)

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val keys = rememberUpdatedState { e: KeyEvent -> handleKey(e, session, ::pressed, ::back) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        session.player.Video(Modifier.fillMaxSize())
        OverlayHost(native = session.player.nativeWindow) {
            Box(
                Modifier.fillMaxSize()
                    // Almost clear rather than clear: a window drawn fully transparent lets clicks through to mpv's.
                    .background(Color.Black.copy(alpha = 0.004f))
                    .focusRequester(focus).focusable()
                    .onPreviewKeyEvent { keys.value(it) }
                    .onPointerEvent(PointerEventType.Move) { touch() }
                    .pointerHoverIcon(if (controls) PointerIcon.Default else HiddenCursor)
                    .pointerInput(session) {
                        detectTapGestures(
                            onTap = { if (controls) { session.togglePlay(); pressed() } else touch() },
                            onDoubleTap = { DesktopWindow.toggleFullscreen(); touch() },
                        )
                    }
                    .testTag("player"),
            ) {
                // Scenes in the credits: skipped to when sure (and allowed), else offered or a heads-up.
                val next = upNext
                val film = next != null && !next.isEpisode && !inPlaylist
                val upNextAt = (if (film) NextInCollection.offerAtMs(durationMs, segments, item?.chapters.orEmpty()) else PlaybackMoments.upNextAtMs(durationMs, segments))
                    .let { at -> creditScenes?.upNextAt(at, durationMs) ?: at }
                val showUpNext = next != null && onPlayNext != null && !upNextHidden && !sleepAtEnd && upNextAt != null && positionMs >= upNextAt

                session.secondSubtitle.collectAsState().value?.let { second ->
                    dev.nitrate.core.playback.SecondSubtitles.at(second.cues, positionMs)?.let { line ->
                        Text(
                            line, color = Color.White,
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize * (playerPrefs?.subtitleStyle?.scale ?: 1f)),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp, start = 24.dp, end = 24.dp)
                                .background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }

                AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) {
                        TopBar(
                            item = item, plan = plan != null && clipEndMs == null, easy = easy,
                            chapters = !easy && item?.chapters.orEmpty().isNotEmpty(),
                            chat = if (chatAvailable) chatOn else null, withChip = withChip,
                            onBack = { pressed(); onExit() }, onPanel = { pressed(); panel = it },
                        )
                        BottomBar(
                            actions, session, item, positionMs, durationMs, paused, fullscreen,
                            onDragging = { dragging = it; touch() }, onPressed = ::pressed,
                        )
                    }
                }

                if (clipEndMs == null) SkipButton(PlaybackMoments.skippable(segments, positionMs).takeIf { !showUpNext }) {
                    item?.let { i -> container.skipWatch.skipped(i.id, it, dev.nitrate.android.nowMs()) }
                    session.seekTo(it.endMs)
                }
                LaunchedEffect(positionMs / 1000) { item?.let { i -> container.skipWatch.position(i.id, positionMs, dev.nitrate.android.nowMs()) } }
                if (showUpNext && next != null) {
                    val remaining = ((durationMs - positionMs) / 1000).toInt()
                    UpNextCard(next, actions.server, seconds = remaining.coerceIn(5, 15).takeIf { !film }, onPlay = { pressed(); onPlayNext?.invoke(next) }, onHide = { pressed(); upNextHidden = true }, onCountdownEnd = { rollOn(next) })
                }

                val autoSkip = LocalDisplayPrefs.current.creditScenes && syncGroup == null
                val skipper = remember(creditScenes, autoSkip) { creditScenes?.let { CreditSkipper(it, autoSkip) } }
                var creditAction by remember(item?.id) { mutableStateOf<CreditSkipper.Action?>(null) }
                LaunchedEffect(skipper, positionMs / 500) {
                    if (clipEndMs != null) return@LaunchedEffect
                    val a = skipper?.at(positionMs) ?: return@LaunchedEffect
                    if (a is CreditSkipper.Action.Skip) session.seekTo(a.toMs)
                    creditAction = a
                }
                CreditSceneBanner(creditAction.takeIf { !showUpNext }, onAct = { a ->
                    when (a) {
                        is CreditSkipper.Action.Skip -> session.seekTo(a.backMs)
                        is CreditSkipper.Action.Offer -> session.seekTo(a.toMs)
                        else -> Unit
                    }
                }) { creditAction = null }

                if (groupChat != null && chatAvailable && panel != Panel.CHAT) NoteToasts(groupChat.popups, Modifier.align(Alignment.CenterStart).padding(start = 16.dp))
                CountingBanner(withChip, easy, onOpen = { panel = Panel.WITH }, modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp))

                when {
                    error != null -> Column(
                        Modifier.align(Alignment.Center).background(Color(0xCC000000), RoundedCornerShape(12.dp)).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(error!!, color = MaterialTheme.colorScheme.error)
                        Button(onClick = onExit) { Text("Back") }
                    }
                    plan == null || buffering -> CircularProgressIndicator(Modifier.align(Alignment.Center).semantics { contentDescription = "Loading" }, color = accentColor())
                }

                stillAsk?.let { nextUp ->
                    val rewind = remember(nextUp) { still.rewind() }
                    StillWatchingPrompt(
                        next = nextUp,
                        rewind = rewind,
                        onKeepWatching = { stillAsk = null; still.clear(); onPlayNext?.invoke(nextUp) },
                        onGoBack = {
                            stillAsk = null
                            actions.scope.launch {
                                rewind?.let { container.rewindStillWatching(it) }
                                val back = rewind?.let { runCatching { actions.server.getItem(it.back.itemId) }.getOrNull() }
                                if (back != null) onPlayNext?.invoke(back) else onExit()
                            }
                        },
                        onStop = { stillAsk = null; still.clear(); onExit() },
                        onNoAnswer = {
                            actions.scope.launch {
                                rewind?.let { container.rewindStillWatching(it) }
                                onExit()
                            }
                        },
                    )
                }

                val p = plan
                val close = { panel = null; runCatching { focus.requestFocus() }; Unit }
                when (panel) {
                    null -> Unit
                    Panel.AUDIO -> if (p != null) TrackPicker("Audio", p.audioChoices(), p.audioIndex, { session.selectTrack(StreamKind.AUDIO, it); close() }, close)
                    Panel.SUBTITLES -> if (p != null) TrackPicker("Subtitles", p.subtitleChoices(), p.subtitleIndex, { session.selectTrack(StreamKind.SUBTITLE, it); close() }, close)
                    Panel.CHAPTERS -> ChaptersDialog(item?.chapters.orEmpty(), positionMs, onPick = { session.seekTo(it); close() }, onDismiss = close)
                    Panel.INFO -> if (p != null) PlayerInfoDialog(session, p, local, onDismiss = close)
                    Panel.MORE -> if (p != null) DesktopPlayerMore(
                        session, settings, p, sleepAt, sleepAtEnd, onSleep = { at, end -> sleepAt = at; sleepAtEnd = end },
                        groupChat = if (groupChat != null && chatAvailable) { {
                            GroupChatRow(groupChat, onTurn = { turnGroupChat(actions, groupChat, it, session.currentMs()) }, onShowMissed = { groupChat.showMissed(); panel = Panel.CHAT })
                        } } else null,
                        onDismiss = close,
                    )
                    Panel.CHAT -> if (groupChat != null) GroupChatSheet(
                        groupChat, syncGroup?.name ?: groupChat.groups.collectAsState().value.firstOrNull()?.title, positionMs = { session.currentMs() },
                        onTurn = { turnGroupChat(actions, groupChat, it, session.currentMs()) }, onDismiss = close,
                    )
                    Panel.WITH -> if (!easy && company != null && withChip != null) {
                        PlayerWithSheet(actions, company, withChip, item, groupChat, chatAvailable, positionMs = { session.currentMs() }, onDismiss = close)
                    }
                }
            }
        }
    }
}

/** The keyboard in the player; true when it used the key. */
private fun handleKey(e: KeyEvent, session: DesktopPlayerSession, pressed: () -> Unit, back: () -> Unit): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    val step = if (e.isShiftPressed) 30_000L else 10_000L
    when (e.key) {
        Key.Spacebar, Key.K, Key.MediaPlayPause -> session.togglePlay()
        Key.MediaPlay -> if (session.isPaused) session.togglePlay()
        Key.MediaPause -> session.pause()
        Key.DirectionLeft, Key.J -> session.seekBy(-step)
        Key.DirectionRight, Key.L -> session.seekBy(step)
        Key.DirectionUp -> session.setVolume(session.volume.value + 5)
        Key.DirectionDown -> session.setVolume(session.volume.value - 5)
        Key.M -> session.setMuted(!session.muted.value)
        Key.F -> DesktopWindow.toggleFullscreen()
        Key.Escape -> back()
        else -> return false
    }
    pressed()
    return true
}

/**
 * Where the controls go: over mpv's native window they need a window of their own (a popup, which
 * Compose makes a separate window with `compose.layers.type=WINDOW`); over frames Nitrate draws
 * itself they're simply on top.
 */
@Composable
private fun OverlayHost(native: Boolean, content: @Composable () -> Unit) {
    if (native) {
        Popup(properties = PopupProperties(focusable = true, dismissOnBackPress = false, dismissOnClickOutside = false)) {
            Box(Modifier.fillMaxSize()) { content() }
        }
    } else content()
}

/** Back, the title, the With chip and the player's menus. */
@Composable
private fun BoxScope.TopBar(
    item: MediaItem?, plan: Boolean, easy: Boolean, chapters: Boolean, chat: Boolean?, withChip: dev.nitrate.core.partners.WithChip?,
    onBack: () -> Unit, onPanel: (Panel) -> Unit,
) {
    Row(
        Modifier.align(Alignment.TopStart).fillMaxWidth()
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xAA000000), Color.Transparent)))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
        Text(
            listOfNotNull(item?.displayTitle, item?.episodeLabel).joinToString(" · "),
            color = Color.White, style = MaterialTheme.typography.titleMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        withChip?.let { WithChipButton(it, easy, onOpen = { onPanel(Panel.WITH) }, modifier = Modifier.padding(horizontal = 4.dp)) }
        if (plan) {
            TextButton(onClick = { onPanel(Panel.AUDIO) }) { Text("Audio", color = Color.White) }
            TextButton(onClick = { onPanel(Panel.SUBTITLES) }) { Text("Subtitles", color = Color.White) }
            if (chapters) TextButton(onClick = { onPanel(Panel.CHAPTERS) }) { Text("Chapters", color = Color.White) }
            if (!easy) {
                if (chat != null) TextButton(onClick = { onPanel(Panel.CHAT) }) { Text("Group chat: " + if (chat) "On" else "Off", color = Color.White) }
                TextButton(onClick = { onPanel(Panel.INFO) }) { Text("Info", color = Color.White) }
                TextButton(onClick = { onPanel(Panel.MORE) }) { Text("More", color = Color.White) }
            }
        }
    }
}

/** The timeline (with scrub thumbnails on hover and drag), times, play/pause, jumps, volume and full screen. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BoxScope.BottomBar(
    actions: AppActions, session: DesktopPlayerSession, item: MediaItem?, positionMs: Long, durationMs: Long,
    paused: Boolean, fullscreen: Boolean, onDragging: (Boolean) -> Unit, onPressed: () -> Unit,
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    var hover by remember { mutableStateOf<Float?>(null) }
    var barX by remember { mutableStateOf(0f) }
    var barWidth by remember { mutableStateOf(1f) }
    var barTop by remember { mutableStateOf(0f) }
    val trickplay by session.trickplay.collectAsState()
    val volume by session.volume.collectAsState()
    val muted by session.muted.collectAsState()
    val duration = durationMs.coerceAtLeast(1)
    // Where the scrub preview points: the dragged spot, else the one under the mouse.
    (drag ?: hover)?.let { f ->
        item?.let { TrickplayPreview(actions.server, it.id, trickplay, (f * duration).toLong(), barX + f * barWidth, barTop) }
    }
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Slider(
            value = drag ?: (positionMs.toFloat() / duration).coerceIn(0f, 1f),
            onValueChange = { drag = it; onDragging(true) },
            onValueChangeFinished = { drag?.let { session.seekTo((it * duration).toLong()) }; drag = null; onDragging(false); onPressed() },
            modifier = Modifier.fillMaxWidth()
                .onGloballyPositioned { c -> barX = c.positionInRoot().x; barWidth = c.size.width.toFloat().coerceAtLeast(1f); barTop = c.positionInRoot().y }
                .onPointerEvent(PointerEventType.Move) { e -> hover = (e.changes.first().position.x / barWidth).coerceIn(0f, 1f) }
                .onPointerEvent(PointerEventType.Exit) { hover = null }
                .semantics { contentDescription = "Timeline"; stateDescription = "${formatClock(positionMs)} of ${formatClock(durationMs)}" },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { session.togglePlay(); onPressed() }) {
                Icon(if (paused) Icons.Default.PlayArrow else PauseIcon, if (paused) "Play" else "Pause", tint = Color.White, modifier = Modifier.size(32.dp))
            }
            TextButton(onClick = { session.seekBy(-10_000); onPressed() }) { Text("−10 s", color = Color.White) }
            TextButton(onClick = { session.seekBy(10_000); onPressed() }) { Text("+10 s", color = Color.White) }
            Text(
                "${formatClock(drag?.let { (it * duration).toLong() } ?: positionMs)} / ${formatClock(durationMs)}",
                color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp),
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { session.setMuted(!muted); onPressed() }) { Text(if (muted) "Unmute" else "Mute", color = Color.White) }
            Slider(
                value = if (muted) 0f else volume / 130f,
                onValueChange = { session.setVolume((it * 130).toInt()); onPressed() },
                modifier = Modifier.width(120.dp).semantics { contentDescription = "Volume"; stateDescription = if (muted) "Muted" else "$volume%" },
            )
            TextButton(onClick = { DesktopWindow.toggleFullscreen(); onPressed() }) {
                Text(if (fullscreen) "Exit full screen" else "Full screen", color = Color.White)
            }
        }
    }
}

/** The chapters, the one playing marked; picking one jumps there. */
@Composable
private fun ChaptersDialog(chapters: List<dev.nitrate.core.model.Chapter>, positionMs: Long, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val current = chapters.indexOfLast { it.startMs <= positionMs }
    TrackPicker(
        "Chapters",
        chapters.mapIndexed { i, c -> dev.nitrate.core.playback.TrackChoice(i, c.name.ifBlank { "Chapter ${i + 1}" }, formatClock(c.startMs)) },
        current.takeIf { it >= 0 },
        { onPick(chapters[it].startMs) },
        onDismiss,
    )
}

/** Info: how it plays (the server's decision) and what mpv says (decoder, picture, sound), refreshed each second. */
@Composable
private fun PlayerInfoDialog(session: DesktopPlayerSession, plan: dev.nitrate.core.model.PlaybackPlan, local: Boolean, onDismiss: () -> Unit) {
    var mpvRows by remember { mutableStateOf(session.player.describe()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1_000)
            mpvRows = session.player.describe()
        }
    }
    val rows = listOf("Player" to "mpv", "Source" to (if (local) "Downloaded copy" else "Streaming")) + plan.infoRows(null, null) + mpvRows
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Info") },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                rows.forEach { (label, value) ->
                    Row(Modifier.padding(vertical = 2.dp)) {
                        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(140.dp))
                        Text(value, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private const val HIDE_AFTER_MS = 3_000L

/** No pointer while the controls are hidden over the video. */
private val HiddenCursor: PointerIcon by lazy {
    runCatching {
        PointerIcon(java.awt.Toolkit.getDefaultToolkit().createCustomCursor(java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB), java.awt.Point(0, 0), "none"))
    }.getOrDefault(PointerIcon.Default)
}

/** Two bars (Material's core icons have no pause). */
private val PauseIcon: ImageVector by lazy {
    ImageVector.Builder("Pause", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(6f, 5f); lineTo(10f, 5f); lineTo(10f, 19f); lineTo(6f, 19f); close()
            moveTo(14f, 5f); lineTo(18f, 5f); lineTo(18f, 19f); lineTo(14f, 19f); close()
        }
    }.build()
}
