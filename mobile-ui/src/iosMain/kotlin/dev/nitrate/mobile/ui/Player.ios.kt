package dev.nitrate.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import dev.nitrate.core.playback.infoRows
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitViewController
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.StreamKind
import dev.nitrate.core.playback.CreditSkipper
import dev.nitrate.core.playback.NextInCollection
import dev.nitrate.core.playback.PlaybackMoments
import dev.nitrate.core.playback.audioChoices
import dev.nitrate.core.playback.subtitleChoices
import kotlinx.coroutines.launch
import platform.AVKit.AVPlayerViewController

/**
 * Apple's player (AVPlayerViewController: its own controls, picture-in-picture and AirPlay) with
 * Nitrate's shared overlays on top: Audio and Subtitles menus, skip intro/recap, Up Next for
 * episodes and the next film of a collection, and scenes in the credits.
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
    val container = actions.container
    val session = remember(itemId) { ApplePlayerSession(container, reported = clipEndMs == null) }
    DisposableEffect(session) {
        if (local) session.loadLocal(itemId, fromStart, startMs) else session.load(itemId, fromStart, startMs)
        container.remote.active = session
        onDispose {
            if (container.remote.active === session) container.remote.active = null
            session.release()
        }
    }

    val item by session.item.collectAsState()
    val plan by session.plan.collectAsState()
    val error by session.error.collectAsState()
    val positionMs by session.positionMs.collectAsState()
    val durationMs by session.durationMs.collectAsState()
    val segments by session.segments.collectAsState()
    val creditScenes by session.creditScenes.collectAsState()
    val ended by session.ended.collectAsState()
    var picking by remember { mutableStateOf<StreamKind?>(null) }
    var upNext by remember(itemId) { mutableStateOf<MediaItem?>(null) }
    var inPlaylist by remember(itemId) { mutableStateOf(false) }
    var upNextHidden by remember(itemId) { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val easy = LocalDisplayPrefs.current.easyMode

    // "Still watching?": asked instead of rolling on when nothing's been pressed for a while.
    val still = container.stillWatching
    var stillAsk by remember { mutableStateOf<MediaItem?>(null) }
    val askStill by container.playbackSettings.settings.collectAsState(null)
    LaunchedEffect(item?.id) { item?.let { i -> if (clipEndMs == null) still.started(i.id, i.user.positionMs, i.user.played) } }
    DisposableEffect(session) {
        session.onInput = { session.item.value?.let { i -> still.input(i.id, session.currentMs(), i.user.played) } }
        onDispose { session.onInput = null }
    }
    fun pressed() = session.onInput?.invoke()
    fun rollOn(next: MediaItem) {
        if (askStill?.askStillWatching != false && still.shouldAsk()) stillAsk = next
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

    // A recap clip ends at its end; an episode rolls on to the next (unless Up Next was put away);
    // anything else ends when the stream does.
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
    // What comes next: the following episode, or the next film of a small collection.
    LaunchedEffect(item?.id) {
        val current = item ?: return@LaunchedEffect
        if (onPlayNext == null) return@LaunchedEffect
        // Part of a playlist being watched through: its order decides, and it rolls on whatever comes next.
        if (!local && clipEndMs == null) runCatching { actions.container.playlists.contextOf(current) }.getOrNull()?.let { ctx ->
            inPlaylist = true
            upNext = ctx.next
            return@LaunchedEffect
        }
        upNext = when {
            current.kind == ItemKind.MOVIE -> runCatching { actions.server.nextInCollection(current) }.getOrNull()
            local -> actions.container.downloads.nextDownloaded(current)
            current.isEpisode -> current.seriesId?.let { s -> runCatching { dev.nitrate.core.episodes.UpNext.after(current, actions.server.allEpisodes(s)) }.getOrNull() }
            else -> null
        }
    }

    // Live chat, while this device is in a Group Watch Session (or, kept for older apps, a SyncPlay group) with chat (Companion plugin).
    val syncGroup = container.syncPlay.group.collectAsState().value
    val groupSession = container.groupSessions.current.collectAsState().value
    val chatGroup = container.chat.groupId.collectAsState().value?.takeIf { !local && ((groupSession != null && it == groupSession.id) || (syncGroup != null && it == syncGroup.id)) }
    // Group chat: the watch group's notes along this title pop up as they're reached (live in a Group Watch Session too).
    val groupChat = rememberGroupChat(actions, item?.id, enabled = !local && clipEndMs == null) { session.currentMs() }
    val chatAvailable = groupChatAvailable(groupChat, chatGroup != null)
    val playerPrefs by container.playbackSettings.settings.collectAsState(null)
    // Who this is watched with: the group button, for titles shared with a watch group and Group Watch Sessions.
    val company = rememberWatchCompany(
        actions, item, enabled = !local && clipEndMs == null && testPlay == null, groupChat,
        positionMs = { session.currentMs() }, durationMs = { session.durationMs.value },
    )
    val withChip = company?.chip?.collectAsState()?.value
    var withOpen by remember { mutableStateOf(false) }
    var chatOpen by remember { mutableStateOf(false) }
    // A Group Watch Session on this part: the countdown, reports, and the others' pauses and jumps offered.
    val sessionPlayback = rememberSessionPlayback(
        container, item?.id, enabled = !local && clipEndMs == null && testPlay == null,
        position = { session.currentMs() }, paused = { session.isPaused },
        play = { session.play() }, pause = { session.pause() }, seekTo = { session.seekTo(it) },
    )

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val engine by session.engine.collectAsState()
        val mpv = session.mpv
        if (engine == IosEngine.MPV && mpv != null) {
            androidx.compose.ui.viewinterop.UIKitView(factory = { mpv.view() }, modifier = Modifier.fillMaxSize())
            MpvControls(session, positionMs, durationMs)
        } else {
            UIKitViewController(
                factory = { AVPlayerViewController().apply { player = session.player; showsPlaybackControls = true } },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Back, the title, and the track menus (Apple's own controls stay below).
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { pressed(); onExit() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Text(
                listOfNotNull(item?.displayTitle, item?.episodeLabel).joinToString(" · "),
                color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            // The one group button: who it counts for, the chat and Group Watch Sessions (easy mode: who it counts for, said).
            GroupButton(withChip, easy, onOpen = { pressed(); withOpen = true }, modifier = Modifier.padding(horizontal = 4.dp))
            if (groupChat != null && chatAvailable && !easy) ChatButton(groupChat, onOpen = { pressed(); chatOpen = true })
            if (plan != null && clipEndMs == null) {
                TextButton(onClick = { pressed(); picking = StreamKind.AUDIO }) { Text("Audio", color = Color.White) }
                TextButton(onClick = { pressed(); picking = StreamKind.SUBTITLE }) { Text("Subtitles", color = Color.White) }
                if (!easy) {
                    TextButton(onClick = { pressed(); more = true }) { Text("More", color = Color.White) }
                }
            }
        }

        val next = upNext
        val film = next != null && !next.isEpisode && !inPlaylist
        val upNextAt = (if (film) NextInCollection.offerAtMs(durationMs, segments, item?.chapters.orEmpty()) else PlaybackMoments.upNextAtMs(durationMs, segments))
            .let { at -> creditScenes?.upNextAt(at, durationMs) ?: at }
        val showUpNext = next != null && onPlayNext != null && !upNextHidden && !sleepAtEnd && upNextAt != null && positionMs >= upNextAt
        val skipNow = PlaybackMoments.skippable(segments, positionMs).takeIf { !showUpNext && clipEndMs == null }
        val lowerOffer by session.travel.offer.collectAsState()
        LowerQualityPrompt(lowerOffer?.takeIf { skipNow == null && !showUpNext }, onLower = session::lowerQuality, onDismiss = session.travel::dismiss)
        session.notice.collectAsState().value?.let {
            Text(
                it, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp, start = 24.dp, end = 24.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium).padding(16.dp),
            )
        }
        if (clipEndMs == null) SkipButton(skipNow) {
            item?.let { i -> container.skipWatch.skipped(i.id, it, dev.nitrate.android.nowMs()) }
            session.seekTo(it.endMs)
        }
        LaunchedEffect(positionMs / 1000) { item?.let { i -> container.skipWatch.position(i.id, positionMs, dev.nitrate.android.nowMs()) } }
        if (showUpNext && next != null) {
            val remaining = ((durationMs - positionMs) / 1000).toInt()
            UpNextCard(next, actions.server, seconds = remaining.coerceIn(5, 15).takeIf { !film }, onPlay = { pressed(); onPlayNext?.invoke(next) }, onHide = { pressed(); upNextHidden = true }, onCountdownEnd = { rollOn(next) })
        }

        // Scenes in the credits: skipped to when sure (and allowed), else offered or a heads-up.
        val autoSkip = LocalDisplayPrefs.current.creditScenes && container.syncPlay.group.collectAsState().value == null && groupSession == null
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

        // Dual subtitles: the second one's current line at the top.
        session.secondSubtitle.collectAsState().value?.let { second ->
            dev.nitrate.core.playback.SecondSubtitles.at(second.cues, positionMs)?.let { line ->
                Text(
                    line, color = Color.White,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize * (playerPrefs?.subtitleStyle?.scale ?: 1f)),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 52.dp, start = 24.dp, end = 24.dp)
                        .background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        if (groupChat != null && chatAvailable && !chatOpen) NoteToasts(groupChat.popups, Modifier.align(Alignment.CenterStart).padding(start = 16.dp))
        // More than one group would count this: which one, plainly, as it starts.
        CountingBanner(withChip, easy, onOpen = { withOpen = true }, modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 56.dp))
        SessionOverlay(sessionPlayback, container, Modifier.matchParentSize().windowInsetsPadding(WindowInsets.safeDrawing))

        stillAsk?.let { next ->
            val rewind = remember(next) { still.rewind() }
            StillWatchingPrompt(
                next = next,
                rewind = rewind,
                onKeepWatching = { stillAsk = null; still.clear(); onPlayNext?.invoke(next) },
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
                    // Dozed off: put things back as they were at the last press, and stop.
                    actions.scope.launch {
                        rewind?.let { container.rewindStillWatching(it) }
                        onExit()
                    }
                },
            )
        }

        when {
            error != null -> Column(Modifier.align(Alignment.Center).background(Color(0xCC000000), RoundedCornerShape(12.dp)).padding(24.dp)) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
                Button(onClick = onExit, modifier = Modifier.padding(top = 12.dp)) { Text("Back") }
            }
            plan == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = accentColor())
        }
    }

    val p = plan
    if (withOpen && !easy && withChip != null) {
        PlayerGroupSheet(actions, company, withChip, item, positionMs = { session.currentMs() }) { withOpen = false }
    }
    if (chatOpen && !easy && groupChat != null && chatAvailable) {
        PlayerChatSheet(
            actions, withChip, item, groupChat,
            chatTitle = groupSession?.title ?: syncGroup?.name ?: groupChat.groups.collectAsState().value.firstOrNull()?.title,
            positionMs = { session.currentMs() },
        ) { chatOpen = false }
    }
    if (more && p != null) IosPlayerMore(
        actions, session, p, local, sleepAt, sleepAtEnd, onSleep = { at, end -> sleepAt = at; sleepAtEnd = end },
    ) { more = false }
    when {
        p == null || picking == null -> Unit
        picking == StreamKind.AUDIO -> TrackPicker("Audio", p.audioChoices(), p.audioIndex, { session.selectTrack(StreamKind.AUDIO, it); picking = null }) { picking = null }
        else -> TrackPicker("Subtitles", p.subtitleChoices(), p.subtitleIndex, { session.selectTrack(StreamKind.SUBTITLE, it); picking = null }) { picking = null }
    }
}

/**
 * mpv has no controls of its own: a tap shows these (play/pause, 10 seconds back and on, the
 * timeline with times) and they fade after a few seconds.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.MpvControls(session: ApplePlayerSession, positionMs: Long, durationMs: Long) {
    var shown by remember { mutableStateOf(true) }
    var touchedAt by remember { mutableStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(shown, touchedAt) {
        if (!shown) return@LaunchedEffect
        kotlinx.coroutines.delay(4_000)
        if (dragging == null) shown = false
    }
    fun touch() { shown = true; touchedAt = dev.nitrate.android.nowMs() }
    Box(
        Modifier.fillMaxSize().clickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null,
        ) { if (shown) shown = false else touch() },
    )
    androidx.compose.animation.AnimatedVisibility(shown, modifier = Modifier.align(Alignment.Center)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { session.seekTo(positionMs - 10_000); touch() }) { Text("−10", color = Color.White, style = MaterialTheme.typography.titleLarge) }
            TextButton(onClick = { session.togglePlay(); touch() }, modifier = Modifier.padding(horizontal = 24.dp)) {
                Text(if (session.isPaused) "Play" else "Pause", color = Color.White, style = MaterialTheme.typography.headlineSmall)
            }
            TextButton(onClick = { session.seekTo(positionMs + 10_000); touch() }) { Text("+10", color = Color.White, style = MaterialTheme.typography.titleLarge) }
        }
    }
    androidx.compose.animation.AnimatedVisibility(shown, modifier = Modifier.align(Alignment.BottomCenter)) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp, vertical = 8.dp)) {
            val duration = durationMs.coerceAtLeast(1)
            androidx.compose.material3.Slider(
                value = dragging ?: (positionMs.toFloat() / duration),
                onValueChange = { dragging = it; touch() },
                onValueChangeFinished = { dragging?.let { session.seekTo((it * duration).toLong()) }; dragging = null },
            )
            Row {
                Text(clockOf(dragging?.let { (it * duration).toLong() } ?: positionMs), color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Text(clockOf(durationMs), color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private fun clockOf(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    fun two(n: Long) = n.toString().padStart(2, '0')
    return if (h > 0) "$h:${two(m)}:${two(s)}" else "$m:${two(s)}"
}

/**
 * The iOS player's More: speed (Apple's player), a second subtitle at the top, and how it plays
 * (the plan's rows, with the full media info a press away). Android's has more (sound, timings,
 * sleep...), which Apple's player does itself or doesn't offer.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun IosPlayerMore(
    actions: AppActions, session: ApplePlayerSession, plan: dev.nitrate.core.model.PlaybackPlan, local: Boolean,
    sleepAt: Long?, sleepAtEnd: Boolean, onSleep: (at: Long?, atEnd: Boolean) -> Unit,
    groupChat: (@Composable () -> Unit)? = null, onDismiss: () -> Unit,
) {
    val speed by session.speed.collectAsState()
    val second by session.secondSubtitle.collectAsState()
    val item by session.item.collectAsState()
    var info by remember { mutableStateOf(false) }
    if (info) {
        val text = remember(plan, item) { dev.nitrate.core.playback.MediaInfo.render(item, plan.source, plan, local = local) }
        MediaInfoDialog(text) { info = false }
        return
    }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
        ) {
            groupChat?.invoke()
            if (session.canChangeSpeed) {
                Text("Speed", style = MaterialTheme.typography.titleSmall)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
                        androidx.compose.material3.FilterChip(selected = speed == s, onClick = { session.setSpeed(s) }, label = { Text(if (s == 1f) "Normal" else "${s}×") })
                    }
                }
            }
            // Sleep timer, as on Android: after a while, or at the end of this (no rolling on).
            Text(
                "Sleep timer" + (sleepAt?.let { " · stops at ${dev.nitrate.mobile.platform.formatTime(it)}" } ?: if (sleepAtEnd) " · end of this" else ""),
                style = MaterialTheme.typography.titleSmall,
            )
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.FilterChip(selected = sleepAt == null && !sleepAtEnd, onClick = { onSleep(null, false) }, label = { Text("Off") })
                listOf(15, 30, 45, 60, 90).forEach { m ->
                    androidx.compose.material3.FilterChip(selected = false, onClick = { onSleep(dev.nitrate.android.nowMs() + m * 60_000L, false) }, label = { Text("$m min") })
                }
                androidx.compose.material3.FilterChip(selected = sleepAtEnd, onClick = { onSleep(null, true) }, label = { Text("End of this") })
            }
            val choices = dev.nitrate.core.playback.SecondSubtitles.choices(plan.source, plan.subtitleIndex)
            if (choices.isNotEmpty()) {
                Text("Second subtitles", style = MaterialTheme.typography.titleSmall)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilterChip(selected = second == null, onClick = { session.setSecondSubtitle(null) }, label = { Text("Off") })
                    choices.forEach { c ->
                        androidx.compose.material3.FilterChip(selected = second?.index == c.index, onClick = { session.setSecondSubtitle(c.index) }, label = { Text(c.title + (c.detail?.let { " · $it" } ?: "")) })
                    }
                }
            }
            Text("How it plays", style = MaterialTheme.typography.titleSmall)
            val engine by session.engine.collectAsState()
            // Who decodes: mpv says (its properties); Apple's player names no decoder.
            val decoding = remember(engine) { session.mpv?.takeIf { engine == IosEngine.MPV }?.let { m -> dev.nitrate.core.playback.MpvDecoding.read { m.property(it) } } }
            val rows = listOf("Player" to (if (engine == IosEngine.MPV) "mpv" else "Apple's player")) +
                ("Source" to (if (local) "Downloaded copy" else "Streaming")).let(::listOf) +
                plan.infoRows(
                    null, null,
                    pictureWords = decoding?.let { dev.nitrate.core.playback.Decoders.mpvPicture(it, "This device's hardware") } ?: dev.nitrate.core.playback.Decoders.APPLE_PLAYER.takeIf { decoding == null },
                    soundWords = decoding?.let { dev.nitrate.core.playback.Decoders.mpvSound(it) } ?: dev.nitrate.core.playback.Decoders.APPLE_PLAYER.takeIf { decoding == null },
                    quality = session.travel.quality.collectAsState().value,
                )
            rows.forEach { (label, value) ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
                    Text(value, style = MaterialTheme.typography.bodyMedium)
                }
            }
            TextButton(onClick = { info = true }) { Text("Media info") }
        }
    }
}
