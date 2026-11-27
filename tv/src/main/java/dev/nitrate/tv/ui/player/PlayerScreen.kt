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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
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
import dev.nitrate.android.playback.runTestPlay
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


private enum class Panel { NONE, AUDIO, SUBTITLES, INFO, MORE, FIX, GROUP, CHAT }

/** Panels shown as dialogs (their own window, with focus of their own). */
private val DIALOG_PANELS = setOf(Panel.AUDIO, Panel.SUBTITLES, Panel.FIX)

/** The With… button's place for focus (its label changes with who's watching). */
private const val WITH_KEY = "with"
private const val CHAT_KEY = "chat"

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
    /** SyncPlay, kept for compatibility (older apps' groups): follows this device's group when it's in one. */
    syncPlay: dev.nitrate.core.syncplay.SyncPlayController? = null,
    /** Sends a playback report for this session to the server; returns the saved file's name. */
    sendLog: (suspend (PlaybackSession) -> Result<String>)? = null,
    /** What this TV reports, for "Why converting?" (left out: no fixer). */
    capabilities: dev.nitrate.core.playback.DeviceCapabilities? = null,
    /** Watch-together chat (Companion plugin), live while this TV is in a SyncPlay group. */
    chat: dev.nitrate.core.companion.GroupChat? = null,
    /**
     * Group chat for this playback: the watch group's notes pinned along the title pop up as they're
     * reached, and the chat panel writes new ones here (Companion `notes`; live in a SyncPlay group too).
     */
    groupChat: dev.nitrate.core.companion.TimelineChat? = null,
    /** A recap clip: plays from [startMs] to here, unreported, then ends. */
    clipEndMs: Long? = null,
    /** The user's audio-description preference. */
    describedAudio: suspend () -> Boolean = { false },
    /** Plays the next episode, or the next film in a collection (null: nothing follows, e.g. in a queue). */
    onPlayNext: ((MediaItem) -> Unit)? = null,
    /** Plays an extra (trailer, featurette…) on top of this, coming back to where this was (null: no extras strip). */
    onPlayExtra: ((MediaItem, Long) -> Unit)? = null,
    /** "Still watching?" (episodes rolling on with nothing pressed), and putting things back. */
    stillWatching: dev.nitrate.core.playback.StillWatching? = null,
    stillWatchingRewind: ((dev.nitrate.core.playback.StillWatching.Rewind?, (MediaItem?) -> Unit) -> Unit)? = null,
    /**
     * A test play from the playback overview: [TestPlay.PLAY_MS] of the title a few minutes in,
     * unreported (like a recap clip), then its trail is handed here before the player leaves.
     */
    testPlay: ((dev.nitrate.core.playback.TestPlayResult) -> Unit)? = null,
    /** The playlist this title is being watched through, if any, and what follows it there (`PlaylistPlaying.contextOf`). */
    playlistNext: (suspend (MediaItem) -> dev.nitrate.android.playlists.PlaylistContext?)? = null,
    /** Hears skips and jumps back into what was skipped, so wrong intro/recap marks get fixed (Companion). */
    skipWatch: dev.nitrate.core.companion.SkipWatch? = null,
    /**
     * Who this is watched with (the With… button: watch groups, watch parties, the same room), from
     * the app's groups, standings and watch party; null: no button.
     */
    watchGroups: dev.nitrate.android.AppContainer? = null,
) {
    val context = LocalContext.current
    // "End of this" sleep timer: when it ends, leave instead of rolling on through a queue.
    val sleepAtEndState = remember { mutableStateOf(false) }
    var upNext by remember { mutableStateOf<MediaItem?>(null) }
    // Watching through a playlist: what follows is the playlist's next, and it rolls on whatever it is.
    var inPlaylist by remember { mutableStateOf(false) }
    // The playlist whose order Up Next follows (its name), for the card's heading.
    var upNextFrom by remember { mutableStateOf<String?>(null) }
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
            next != null && (next.isEpisode || inPlaylist) && !upNextHidden && onPlayNext != null -> rollOn(next)
            else -> onEnded()
        }
    }
    // Negotiation, track mapping, reporting and transcode fallback live in the shared session.
    val session = remember(itemId) {
        PlaybackSession(
            context, repository, playbackSettings, deviceNoun = "This TV", onEnded = { ended() }, trackMemory = trackMemory, describedAudio = describedAudio,
            // A profile 7 file's kind remembered for How it plays; this playback's log kept on the TV.
            onDvLayers = { id, layers -> watchGroups?.rememberDvLayers(id, layers) },
            layersOf = { id -> watchGroups?.dvLayersOf(id) },
            keepLog = { report -> if (testPlay == null) watchGroups?.keepPlaybackLog(report) },
            // Away or on a slow line: a lower quality (test plays check the file as it is).
            qualityFor = { if (testPlay == null) watchGroups?.qualityDecision() else null },
        )
            .also {
                if (testPlay != null) Unit // loaded below, once the title's length is known
                else if (clipEndMs != null) it.loadClip(itemId, startMs ?: 0, clipEndMs)
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
    // A test play: a short stretch a few minutes in, then what happened on the way out, for the overview.
    var testLeft by remember { mutableStateOf<Long?>(null) }
    if (testPlay != null) LaunchedEffect(session) {
        val caps = capabilities ?: return@LaunchedEffect
        val result = session.runTestPlay(repository, itemId, caps) { testLeft = it }
        testPlay(result)
        if (result.error != null) onExit()
    }
    val settingsNow by playbackSettings.settings.collectAsState(dev.nitrate.core.playback.PlaybackSettings())
    val assTrack by session.assTrack.collectAsState()
    val subtitleOffset by session.subtitleOffsetMs.collectAsState()
    // Sound and picture knocked out of step (SyncWatch): the fix panel offers what helps.
    val outOfStep by session.syncWarning.collectAsState()
    // The TV's refresh rate follows the video's frame rate, as set in Playback settings.
    // This playback's choice from More → Settings, else the device's.
    var frameRateOverride by remember { mutableStateOf<dev.nitrate.core.playback.FrameRateMatch?>(null) }
    // Null until the device's settings have loaded (the default read first would hand a kept rate back).
    val savedFrameRate by playbackSettings.settings.map<dev.nitrate.core.playback.PlaybackSettings, dev.nitrate.core.playback.FrameRateMatch?> { it.frameRateMatch }.collectAsState(null)
    val refreshHold by playbackSettings.settings.map { it.refreshHold }.collectAsState(dev.nitrate.core.playback.RefreshHold.ALWAYS)
    val matchingRate = FrameRateMatcher(session, frameRateOverride ?: savedFrameRate, refreshHold)
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
    val lowerOffer by session.lowerQualityOffer.collectAsState()
    // The result of "Send log", shown like a notice for a while.
    var logMessage by remember { mutableStateOf<String?>(null) }
    val logScope = rememberCoroutineScope()
    LaunchedEffect(logMessage) { if (logMessage != null) { kotlinx.coroutines.delay(12_000); logMessage = null } }
    // Live chat is there while this TV is in a Group Watch Session and the server has the Companion plugin
    // (or, kept for older apps, a SyncPlay group).
    val syncGroup = syncPlay?.group?.collectAsState()?.value
    val groupSession = watchGroups?.groupSessions?.current?.collectAsState()?.value
    val chatGroup = chat?.groupId?.collectAsState()?.value?.takeIf { (groupSession != null && it == groupSession.id) || (syncGroup != null && it == syncGroup.id) }
    val videoDecoder by session.videoDecoder.collectAsState()
    val audioDecoder by session.audioDecoder.collectAsState()
    val segments by session.segments.collectAsState()
    // A song's lyrics, when the server has them; shown beside the cover when asked for.
    val lyrics by androidx.compose.runtime.produceState<dev.nitrate.core.music.Lyrics?>(null, item?.id) {
        value = item?.takeIf { it.mediaKind == dev.nitrate.core.model.MediaKind.AUDIO }?.let { runCatching { repository.lyrics(it.id) }.getOrNull() }
    }
    var showLyrics by remember { mutableStateOf(false) }
    val easy = dev.nitrate.tv.ui.components.LocalDisplayPrefs.current.easyMode
    // Group chat: the title's notes load once as it starts; on or off as remembered for its groups.
    val displayPrefs by rememberUpdatedState(dev.nitrate.tv.ui.components.LocalDisplayPrefs.current)
    val noteGroups = groupChat?.groups?.collectAsState()?.value.orEmpty()
    val chatAvailable = groupChat != null && clipEndMs == null && testPlay == null && (noteGroups.isNotEmpty() || chatGroup != null)
    LaunchedEffect(groupChat, item?.id) {
        val i = item ?: return@LaunchedEffect
        if (groupChat == null || clipEndMs != null || testPlay != null) return@LaunchedEffect
        // As picked on the details page it was played from, else as remembered for its groups.
        groupChat.start(i.id, player.currentPosition, dev.nitrate.android.playback.ChatChoices.take(i.id)) { ids -> displayPrefs.groupChatOn(ids) }
    }
    DisposableEffect(groupChat) { onDispose { groupChat?.close() } }
    // Who it's watched with: the group button, for titles shared with a watch group and Group Watch Sessions.
    val company = rememberWatchCompany(
        watchGroups, item, enabled = clipEndMs == null && testPlay == null, groupChat,
        positionMs = { player.currentPosition }, durationMs = { player.duration.coerceAtLeast(0L) },
    )
    val withChip = company?.chip?.collectAsState()?.value
    // The group button (counting for, Group Watch Session): only where a group is involved.
    val groupLabel = groupButtonLabel(withChip).takeIf { !easy }
    // The Chat button (its strip across the bottom), with a dot when something popped up since it was last open.
    val chatNew = (groupChat?.unread?.collectAsState()?.value ?: 0) > 0
    val chatLabel = "Chat".takeIf { chatAvailable && !easy }
    var chatLog by remember { mutableStateOf(false) }
    // A Group Watch Session on this part: the countdown, reports, and the others' pauses and jumps offered.
    val sessionPlayback = rememberSessionPlayback(watchGroups, item?.id, player, enabled = clipEndMs == null && testPlay == null)
    val sessionNotice = sessionPlayback?.notice?.collectAsState()?.value
    val sessionCountdown = sessionPlayback?.countdown?.collectAsState()?.value
    val sessionWaiting = sessionPlayback?.waiting?.collectAsState()?.value == true
    // What follows: the next episode in viewing order, or the next film in its collection.
    LaunchedEffect(item?.id) {
        val current = item ?: return@LaunchedEffect
        upNext = null
        upNextHidden = false
        inPlaylist = false
        upNextFrom = null
        if (clipEndMs != null) return@LaunchedEffect
        // A sitting of a playlist (started from it: its page, its card, the one before it in it; queues
        // too, so the playlist's own record counts it): its order decides what's next. Asked first.
        val inList = playlistNext?.let { runCatching { it(current) }.getOrNull() }
        if (onPlayNext == null) return@LaunchedEffect
        if (inList != null) {
            inPlaylist = true
            upNext = inList.next
            upNextFrom = inList.name
            return@LaunchedEffect
        }
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
    // The More panel's tab, kept between openings.
    var moreTab by remember { mutableStateOf<String?>(null) }
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
        if (clipEndMs == null && testPlay == null) stillWatching?.started(i.id, i.user.positionMs, i.user.played)
    }
    var lastPress by remember { mutableLongStateOf(0L) }
    fun pressed() {
        val i = item ?: return
        val now = System.currentTimeMillis()
        if (now - lastPress < 2_000 || clipEndMs != null || testPlay != null) return
        lastPress = now
        stillWatching?.input(i.id, player.currentPosition, i.user.played)
    }

    fun touch() {
        lastInput = System.currentTimeMillis()
        controlsVisible = true
    }

    // Where focus is: on the controls, anywhere in the player, or (side panel) beside it.
    var controlsHaveFocus by remember { mutableStateOf(false) }
    var playerHasFocus by remember { mutableStateOf(false) }

    /**
     * Something focused went away (a banner, Up Next, a pop-up): focus goes to the controls' last
     * button when they're showing, else to the player itself, never nowhere (keys would do nothing).
     */
    fun refocus() {
        if (controlsVisible && panel == Panel.NONE) controlFocus.restore() else runCatching { rootFocus.requestFocus() }
    }

    fun selectTrack(kind: StreamKind, index: Int) = session.selectTrack(kind, index)

    var lostFocusTicks by remember { mutableIntStateOf(0) }
    // Clock for the seek bar, and auto-hide of the controls while playing.
    LaunchedEffect(Unit) {
        while (isActive) {
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0L)
            playing = player.isPlaying
            if (controlsVisible && playing && panel == Panel.NONE && person == null && System.currentTimeMillis() - lastInput > CONTROLS_TIMEOUT_MS) {
                controlsVisible = false
                // Only take focus from the controls themselves (not a banner, Up Next or a side panel).
                if (controlsHaveFocus) runCatching { rootFocus.requestFocus() }
            }
            // Focus lost (what had it went away) with no pop-up, dialog or side panel open: put it
            // back after a moment, so the remote never goes dead.
            val dialogOpen = panel in DIALOG_PANELS || person != null || stillAsk != null || openPanel != null || panelFocused
            lostFocusTicks = if (!playerHasFocus && !dialogOpen) lostFocusTicks + 1 else 0
            if (lostFocusTicks >= 3) { lostFocusTicks = 0; refocus() }
            delay(250)
        }
    }

    LaunchedEffect(controlsVisible) {
        // Focus goes to play/pause only from the player itself (or from nowhere): a pop-up that has
        // it (Up Next, Skip intro/recap/credits, a credits-scene banner) keeps it, so the key that
        // brought the controls back, and OK after it, act on the pop-up.
        if (controlsVisible && (rootFocused || !playerHasFocus) && creditAction == null && lowerOffer == null) controlFocus.playPause()
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

    // Extras (the title's, else its show's) and what it sits among: the season's episodes, or the
    // films of its collection. Not in easy mode, recap clips or (for extras) a watch-together group.
    val extras by androidx.compose.runtime.produceState(emptyList<MediaItem>(), item?.id, easy) {
        val i = item
        value = if (i == null || easy || clipEndMs != null || onPlayExtra == null) emptyList() else runCatching {
            repository.extras(i.id).ifEmpty { i.seriesId?.let { repository.extras(it) }.orEmpty() }
        }.getOrDefault(emptyList())
    }
    val siblings by androidx.compose.runtime.produceState(emptyList<MediaItem>(), item?.id, easy) {
        val i = item
        value = if (i == null || easy || onPlayNext == null) emptyList() else runCatching {
            when {
                i.isEpisode -> {
                    val series = i.seriesId
                    val season = i.seasonId
                    if (series == null || season == null) emptyList() else repository.episodes(series, season).filter { !it.isMissing }
                }
                i.kind == dev.nitrate.core.model.ItemKind.MOVIE ->
                    dev.nitrate.core.playback.NextInCollection.saga(i, repository.collectionsOf(i.id).take(5).map { repository.collectionItems(it.id) })
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
    }
    val titleStrips = run {
        val current = item
        listOfNotNull(
            extras.takeIf { it.isNotEmpty() && syncGroup == null && groupSession == null }?.let { list ->
                TitleStrip(EXTRAS, list.map { e -> TitleCard(e, e.name, listOfNotNull(e.extraType, e.runtimeMs?.let { "${(it / 60_000).coerceAtLeast(1)} min" }).joinToString(" · ").ifEmpty { null }, repository.imageUrl(e, dev.nitrate.core.model.ImageKind.PRIMARY, 320)) }) { e ->
                    onPlayExtra?.invoke(e, player.currentPosition)
                }
            },
            siblings.takeIf { it.size > 1 && current != null }?.let { list ->
                val episodes = current!!.isEpisode
                TitleStrip(
                    if (episodes) current.seasonNumber?.let { "Season $it" } ?: "Episodes" else "In this collection",
                    list.map { t ->
                        TitleCard(
                            t,
                            t.name,
                            if (episodes) listOfNotNull(t.indexNumber?.let { "Episode $it" }, t.runtimeMs?.let { "${it / 60_000} min" }).joinToString(" · ").ifEmpty { null }
                            else t.productionYear?.toString(),
                            if (episodes) repository.imageUrl(t, dev.nitrate.core.model.ImageKind.PRIMARY, 320)
                            else repository.imageUrl(t, dev.nitrate.core.model.ImageKind.BACKDROP, 320) ?: repository.imageUrl(t, dev.nitrate.core.model.ImageKind.PRIMARY, 320),
                            now = t.id == current.id,
                        )
                    },
                    start = list.indexOfFirst { it.id == current.id }.coerceAtLeast(0),
                ) { t -> if (t.id != current.id) onPlayNext?.invoke(t) }
            },
        )
    }
    val nextUp = upNext
    val film = nextUp != null && !nextUp.isEpisode && !inPlaylist
    val creditScenes by session.creditScenes.collectAsState()
    val upNextAt = (if (film) dev.nitrate.core.playback.NextInCollection.offerAtMs(duration, segments, item?.chapters.orEmpty())
        else dev.nitrate.core.playback.PlaybackMoments.upNextAtMs(duration, segments))
        .let { at -> creditScenes?.upNextAt(at, duration) ?: at }

    // Scenes in the credits: skipped to when the credits start (not in a watch-together group,
    // where a seek moves everyone), else offered; a heads-up when only the tags know of one.
    val autoSkip = dev.nitrate.tv.ui.components.LocalDisplayPrefs.current.creditScenes && syncGroup == null && groupSession == null
    val skipper = remember(creditScenes, autoSkip) { creditScenes?.let { dev.nitrate.core.playback.CreditSkipper(it, autoSkip) } }
    // The clock (`position`, every 250 ms) is read only where it's shown or in effects like these
    // (snapshotFlow, derivedStateOf): read in the screen itself, it rebuilt the whole player (controls,
    // More's tabs, the subtitle view) four times a second, and the menus lagged on slower TVs.
    LaunchedEffect(skipper) {
        val s = skipper ?: return@LaunchedEffect
        snapshotFlow { position / 500 }.collect {
            val a = s.at(position) ?: return@collect
            if (a is dev.nitrate.core.playback.CreditSkipper.Action.Skip) player.seekTo(a.toMs)
            creditAction = a
        }
    }
    // Skip intro / Skip recap: shown on entering one for 10 s by itself, and whenever the controls are up
    // while it lasts. Not in recap clips or test plays.
    val skippableNow by remember(segments) {
        derivedStateOf { dev.nitrate.core.playback.PlaybackMoments.skippable(segments, position)?.takeIf { skipLabel(it) != null } }
    }
    val skipSegment = skippableNow?.takeIf { clipEndMs == null && testPlay == null }
    var skipFaded by remember { mutableStateOf<dev.nitrate.core.model.MediaSegment?>(null) }
    var skipFocused by remember { mutableStateOf(false) }
    LaunchedEffect(skipSegment) {
        if (skipSegment == null) return@LaunchedEffect
        delay(10_000)
        skipFaded = skipSegment
    }
    LaunchedEffect(item?.id) {
        val i = item ?: return@LaunchedEffect
        snapshotFlow { position / 1000 }.collect {
            skipWatch?.position(i.id, position, System.currentTimeMillis())
            // Notes pop up as playback reaches them (never ahead).
            if (clipEndMs == null && testPlay == null) groupChat?.at(position)
        }
    }
    // For this playback only: the setting (Settings, Group chat) stays as it is.
    fun turnGroupChat(on: Boolean) {
        groupChat?.turn(on, player.currentPosition)
    }
    val pastUpNext by remember(upNextAt) { derivedStateOf { upNextAt != null && position >= upNextAt } }
    val showUpNext = nextUp != null && onPlayNext != null && !upNextHidden && !sleepAtEndState.value && panel == Panel.NONE && pastUpNext
    val showSkip = skipSegment != null && (skipSegment != skipFaded || controlsVisible) && !showUpNext && creditAction == null &&
        panel == Panel.NONE && person == null && stillAsk == null
    // The controls as drawn (More, Info and Chat take their place).
    val controlsShown = controlsVisible && panel != Panel.MORE && panel != Panel.INFO && panel != Panel.CHAT

    // How smoothly the screen draws, by what's open, for the playback report ("Screen drawing").
    // What's open is kept in a plain holder (not state), so measuring never redraws anything.
    val drawingWhat = remember { arrayOf("nothing open") }
    drawingWhat[0] = when {
        panel == Panel.MORE -> "More open"
        panel == Panel.INFO -> "Info open"
        panel == Panel.CHAT -> "the chat open"
        panel != Panel.NONE || person != null || openPanel != null -> "a pop-up or panel open"
        controlsShown -> "the controls up"
        else -> "nothing open"
    }
    val hostView = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(session, hostView) {
        val window = hostView.context.findActivity()?.window
        val listener = android.view.Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            val first = android.os.Build.VERSION.SDK_INT >= 26 && metrics.getMetric(android.view.FrameMetrics.FIRST_DRAW_FRAME) == 1L
            if (!first) {
                val hz = hostView.display?.refreshRate?.takeIf { it > 1f } ?: 60f
                session.frames.record(drawingWhat[0], metrics.getMetric(android.view.FrameMetrics.TOTAL_DURATION) / 1_000_000.0, 1000.0 / hz)
            }
        }
        runCatching { window?.addOnFrameMetricsAvailableListener(listener, android.os.Handler(android.os.Looper.getMainLooper())) }
        onDispose { runCatching { window?.removeOnFrameMetricsAvailableListener(listener) } }
    }

    // Where things go over the video. Everything OK acts on (Up Next, Skip intro/recap/credits, a
    // scene in the credits; one at a time) is in the action corner, bottom right: 48 dp up with the
    // controls hidden, docked at the right end of their buttons' row when they're up and there's
    // room (else just above them), gliding between. Subtitles lift above it, and above the controls.
    // It kept buffering: "Lower the quality", when nothing else is in the corner or open.
    val showLower = lowerOffer != null && !showUpNext && !showSkip && creditAction == null && panel == Panel.NONE && person == null && stillAsk == null
    val actionShowing = showUpNext || showSkip || creditAction != null || showLower
    var actionSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    // What the subtitle view was last styled with (a plain holder: not state, nothing redraws for it).
    val subtitleLook = remember { arrayOfNulls<Any>(1) }
    var buttonsRow by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var playerBox by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val actionBottom = with(density) {
        val row = buttonsRow
        val box = playerBox
        when {
            !controlsShown -> 48.dp
            row == null || box == null || actionSize.width == 0 -> CONTROLS_HEIGHT
            // Docked: centred on the buttons, with a gap after the last one.
            box.right - 48.dp.toPx() - actionSize.width >= row.right + 24.dp.toPx() -> (box.bottom - row.center.y - actionSize.height / 2f).coerceAtLeast(0f).toDp()
            else -> CONTROLS_HEIGHT
        }
    }
    val subtitleLift = with(density) {
        when {
            controlsShown -> CONTROLS_HEIGHT + 8.dp
            actionShowing && actionSize.height > 0 -> 48.dp + actionSize.height.toDp() + 12.dp
            else -> 0.dp
        }
    }

    // Back closes the topmost thing. Handled as a key (below) as well as here: unhandled, Compose's own
    // Back first moves focus out of the group it's in (More's tabs or row, Info), so it took two presses.
    fun goBack() {
        when {
            // What's open goes first (a banner appearing meanwhile doesn't take Back from it).
            panel != Panel.NONE -> panel = Panel.NONE
            openPanel != null -> { openPanel = null; touch() }
            showUpNext -> { upNextHidden = true; refocus() }
            creditAction != null -> { creditAction = null; refocus() }
            showLower -> { session.dismissLowerQuality(); refocus() }
            skipSegment != null && skipFocused -> { skipFaded = skipSegment; skipFocused = false; refocus() }
            // The same whether playing or paused: Back hides the controls, then leaves.
            controlsVisible -> { controlsVisible = false; runCatching { rootFocus.requestFocus() } }
            else -> onExit()
        }
    }
    BackHandler { goBack() }

    // Home while playing: on in a corner window (not for recap clips or test plays).
    val inPip by rememberTvPictureInPicture(player, settingsNow.pictureInPicture && clipEndMs == null)
    // Out of sight (Home without the small window, or the small window closed): pause, never play on unseen.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle, player) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Row(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Back as a key, after anything inside had its say (a More sub-page goes back one step first).
            .onKeyEvent { event ->
                if (event.key != Key.Back && event.key != Key.Escape) return@onKeyEvent false
                if (event.type == KeyEventType.KeyUp) goBack()
                true
            }
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
                // The remote's menu key (≡) shows the controls, and hides them again; with More or
                // Info open it closes that, back to the controls.
                if (event.key == Key.Menu) {
                    if (event.type == KeyEventType.KeyUp) when {
                        panel == Panel.MORE || panel == Panel.INFO || panel == Panel.CHAT -> panel = Panel.NONE
                        controlsVisible -> { controlsVisible = false; runCatching { rootFocus.requestFocus() } }
                        else -> touch()
                    }
                    return@onPreviewKeyEvent true
                }
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
                    // Next/previous: by chapter, when the title has them.
                    Key.MediaNext -> item?.chapters?.let { dev.nitrate.core.playback.Chapters.next(it, player.currentPosition) }?.let { player.seekTo(it); true } ?: false
                    Key.MediaPrevious -> item?.chapters?.let { dev.nitrate.core.playback.Chapters.previous(it, player.currentPosition) }?.let { player.seekTo(it); true } ?: false
                    else -> false
                }
            }
            .onFocusChanged { rootFocused = it.isFocused; playerHasFocus = it.hasFocus }
            .onGloballyPositioned { playerBox = it.boundsInRoot() }
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
                // Restyled only when something about it changed (each restyle lays the subtitles out again).
                val look = listOf(settingsNow.subtitleStyle, plan?.styledSubtitle == true, subtitleLift, it.height)
                if (look != subtitleLook[0]) {
                    subtitleLook[0] = look
                    it.applySubtitleStyle(settingsNow.subtitleStyle, plan?.styledSubtitle == true)
                    // Above the action pop-up or the controls, never under them (and never lower than the user's own margin).
                    if (subtitleLift > 0.dp && it.height > 0) {
                        it.subtitleView?.setBottomPaddingFraction(maxOf(settingsNow.subtitleStyle.bottomMargin, with(density) { subtitleLift.toPx() } / it.height))
                    }
                }
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
        // In the small window: just the picture, no controls or pop-ups.
        if (inPip) return@Box

        lyrics?.takeIf { showLyrics }?.let { l -> val at = Modifier.align(Alignment.CenterEnd); Reading({ position }) { LyricsView(l, it, at) } }

        // The TV changing its refresh rate: a quiet screen rather than a frozen frame before the black.
        if (matchingRate) MatchingRefreshRate(item?.displayTitle)

        val p = plan
        // The More panel takes the controls' place while it's open.
        // More and Info take the controls' place while they're open.
        androidx.compose.animation.AnimatedVisibility(
            visible = controlsShown, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart).onFocusChanged { controlsHaveFocus = it.hasFocus },
        ) {
            // Info and Chat open beside the video: their buttons close them again.
            fun toggle(target: Panel) { panel = if (panel == target) Panel.NONE else target }
            val ready = p != null
            PlayerControls(
                item = item,
                plan = p,
                position = { position },
                duration = { duration },
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
                    // The Group Watch Session: the starter's Start now while it gathers; the others' pause or jump offered ("Pause too", "Catch up").
                    PlayerAction("Start now", key = "session-start") { sessionPlayback?.startNow(); controlFocus.last = null; refocus() }.takeIf { sessionWaiting && groupSession?.startedByMe == true },
                    sessionNotice?.action?.let { a -> PlayerAction(a, key = SESSION_KEY) { sessionPlayback?.act(); controlFocus.last = null; refocus() } },
                    PlayerAction("Audio", ready) { panel = Panel.AUDIO },
                    PlayerAction("Subtitles", ready) { panel = Panel.SUBTITLES },
                    // The group button (who it counts for, Group Watch Sessions); in easy mode only who it counts for, said beside the buttons.
                    groupLabel?.let { PlayerAction(it, key = WITH_KEY) { toggle(Panel.GROUP) } },
                    // The chat: a strip across the bottom, the video above.
                    chatLabel?.let { PlayerAction(it, key = CHAT_KEY, mark = chatNew, markWords = "New messages") { panel = Panel.CHAT } },
                    PlayerAction("Info", ready) { panel = Panel.INFO }.takeIf { !easy },
                    PlayerAction("More", ready) { panel = Panel.MORE }.takeIf { !easy },
                    PlayerAction(if (showLyrics) "Hide lyrics" else "Lyrics") { showLyrics = !showLyrics }.takeIf { lyrics != null },
                    PlayerAction(if (outOfStep != null) "Out of step? Fix it" else "Why converting?") { panel = Panel.FIX }
                        .takeIf { !easy && capabilities != null && p != null && (outOfStep != null || p.method != dev.nitrate.core.model.PlayMethod.DIRECT_PLAY) },
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
                // Down opens More (not in easy mode, which has no More).
                onDown = { if (!easy && ready) panel = Panel.MORE },
                note = withChip?.plain?.takeIf { easy },
                onButtonsPlaced = { buttonsRow = it },
            )
        }

        if (groupChat != null && chatAvailable && !(panel == Panel.CHAT && chatLog)) {
            // Pop-ups from the others, out of the way of the controls (with the chat so far showing, it says the same).
            dev.nitrate.tv.ui.together.NoteToasts(groupChat.popups, Modifier.align(Alignment.CenterStart).padding(start = 32.dp))
        }
        if (panel == Panel.GROUP && groupLabel != null && withChip != null) {
            // Back goes back a page, then closes it; focus returns to the group button.
            PlayerGroupPanel(
                watchGroups, company, withChip, item, positionMs = { player.currentPosition }, onClose = { panel = Panel.NONE },
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
        if (groupChat != null && chatLabel != null && item != null) {
            // The chat on the user's phones' remotes too: what they send is posted here, as this TV.
            val chatPicks by androidx.compose.runtime.produceState(dev.nitrate.core.companion.QuickReplies.forTitle(item), item?.id) {
                item?.let { i -> watchGroups?.let { value = it.quickPicks(i) } }
            }
            ChatOnPhones(
                remote, repository.deviceId, item!!.id, groupChat,
                who = withChip?.group?.let(dev.nitrate.core.partners.WithChip::nameOf) ?: groupSession?.title ?: syncGroup?.name ?: noteGroups.firstOrNull()?.title,
                picks = chatPicks, positionMs = { player.currentPosition }, onTurn = ::turnGroupChat,
            )
        }
        if (panel == Panel.CHAT && groupChat != null && chatLabel != null) {
            // Stays until Back (Chats, then the chat so far, close first); focus returns to the Chat button.
            PlayerChatStrip(
                watchGroups, withChip, item, groupChat,
                chatTitle = groupSession?.title ?: syncGroup?.name ?: noteGroups.firstOrNull()?.title,
                onTurn = ::turnGroupChat, positionMs = { player.currentPosition },
                log = chatLog, onLog = { chatLog = it },
            )
        }

        if (showUpNext && nextUp != null) {
            // Read once (the card counts down by itself), not from the clock state, so the screen isn't rebuilt by it.
            val remaining = remember(nextUp.id) { ((player.duration - player.currentPosition) / 1000).toInt() }
            ActionCorner(actionBottom, onSize = { actionSize = it }) {
                UpNextCard(
                    nextUp, repository, seconds = remaining.coerceIn(5, 15).takeIf { !film },
                    onPlay = { onPlayNext?.invoke(nextUp) },
                    onCountdownEnd = { rollOn(nextUp) },
                    onHide = { upNextHidden = true; refocus() },
                    compact = controlsShown,
                    playlist = upNextFrom,
                )
            }
            // The same question on the phone's remote.
            val ask = remember(nextUp.id) {
                dev.nitrate.core.remote.RemotePrompt(
                    "upnext-${nextUp.id}-${System.currentTimeMillis()}", dev.nitrate.core.remote.RemotePrompt.Kind.UP_NEXT, repository.deviceId.orEmpty(),
                    "Up next: ${nextUp.displayTitle}", nextUp.episodeLabel,
                    listOf(dev.nitrate.core.remote.RemotePrompt.Choice("play", "Play now"), dev.nitrate.core.remote.RemotePrompt.Choice("hide", "Not now")),
                )
            }
            AskPhones(remote, ask) { choice -> if (choice == "play") onPlayNext?.invoke(nextUp) else { upNextHidden = true; refocus() } }
        }


        // The Group Watch Session: the countdown in the middle, the others' news top right; neither ever takes focus.
        SessionCountdown(
            sessionCountdown,
            if (sessionWaiting) groupSession?.let { s -> dev.nitrate.core.partners.SessionPrompt.waiting(s, watchGroups?.partners?.value?.groups?.firstOrNull { it.id == s.groupId }) } else null,
            starter = groupSession?.startedByMe == true,
            modifier = Modifier.align(Alignment.Center),
        )
        // The second subtitles keep the top centre to themselves (measured, so the notices sit below them).
        var secondLineHeight by remember { mutableIntStateOf(0) }
        Box(Modifier.align(Alignment.TopCenter).padding(top = 36.dp, start = 96.dp, end = 96.dp).onSizeChanged { secondLineHeight = it.height }) {
            SecondSubtitleLine(session, settingsNow.subtitleStyle.scale)
        }

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
        // Notices that never take focus, in one stack at the top right (below the second subtitles when
        // they're showing): the Group Watch Session's news, which group this counts for, a new sound output.
        val stackTop by androidx.compose.animation.core.animateDpAsState(
            if (secondLineHeight > 0) 36.dp + with(density) { secondLineHeight.toDp() } + 16.dp else 32.dp, label = "notices",
        )
        androidx.compose.foundation.layout.Column(
            Modifier.align(Alignment.TopEnd).padding(top = stackTop, end = 48.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SessionNoticeBanner(sessionNotice, controlsUp = controlsVisible)
            // More than one group would count this: which one, plainly, as it starts (the With… button changes it).
            CountingBanner(withChip)
            speakerBanner?.let { o -> SpeakersBanner(o, { speakerBanner = null }) }
        }

        stillAsk?.let { next ->
            val rewind = remember(next) { stillWatching?.rewind() }
            val keep = { stillAsk = null; stillWatching?.clear(); onPlayNext?.invoke(next); Unit }
            val goBack = {
                stillAsk = null
                stillWatchingRewind?.invoke(rewind) { back -> if (back != null) onPlayNext?.invoke(back) else onExit() } ?: onExit()
            }
            val stop = { stillAsk = null; stillWatching?.clear(); onExit() }
            val ask = remember(next.id) {
                dev.nitrate.core.remote.RemotePrompt(
                    "still-${next.id}-${System.currentTimeMillis()}", dev.nitrate.core.remote.RemotePrompt.Kind.STILL_WATCHING, repository.deviceId.orEmpty(),
                    "Still watching?", "Up next: ${next.displayTitle}" + (next.episodeLabel?.let { " · $it" } ?: ""),
                    listOfNotNull(
                        dev.nitrate.core.remote.RemotePrompt.Choice("keep", "Keep watching"),
                        dev.nitrate.core.remote.RemotePrompt.Choice("back", "Go back").takeIf { rewind != null },
                        dev.nitrate.core.remote.RemotePrompt.Choice("stop", "Stop"),
                    ),
                )
            }
            AskPhones(remote, ask) { choice -> when (choice) { "keep" -> keep(); "back" -> goBack(); else -> stop() } }
            StillWatchingDialog(
                next = next,
                rewind = rewind,
                onKeepWatching = keep,
                onGoBack = goBack,
                onStop = stop,
                // Dozed off: put things back as they were at the last press, and stop.
                onNoAnswer = { stillWatchingRewind?.invoke(rewind) { onExit() } ?: onExit() },
            )
        }

        if (showSkip && skipSegment != null) {
            val seg = skipSegment
            ActionCorner(actionBottom, onSize = { actionSize = it }) { SkipSegmentButton(
                seg,
                // Only when nothing else is in use: never from the controls someone is moving through.
                takeFocus = rootFocused,
                onSkip = {
                    item?.let { skipWatch?.skipped(it.id, seg, System.currentTimeMillis()) }
                    player.seekTo(seg.endMs)
                    skipFaded = seg
                    skipFocused = false
                    refocus()
                },
                onFocus = { skipFocused = it },
            ) }
        }
        // Gone while it had focus (faded or its part ended): focus goes back where it belongs.
        LaunchedEffect(showSkip) { if (!showSkip && skipFocused) { skipFocused = false; refocus() } }

        if (showLower) lowerOffer?.let { to ->
            ActionCorner(actionBottom, onSize = { actionSize = it }) {
                LowerQualityBanner(to, takeFocus = true, onLower = { session.lowerQuality(to); refocus() })
            }
        }

        creditAction?.takeIf { !showUpNext }?.let { a ->
            // The banner's button on the phone too.
            val actLabel = when (a) {
                is dev.nitrate.core.playback.CreditSkipper.Action.Skip -> "Watch credits"
                is dev.nitrate.core.playback.CreditSkipper.Action.Offer -> "Skip to it"
                else -> null
            }
            val ask = remember(a) {
                actLabel?.let {
                    dev.nitrate.core.remote.RemotePrompt(
                        "credits-${System.currentTimeMillis()}", dev.nitrate.core.remote.RemotePrompt.Kind.CREDIT_SCENE, repository.deviceId.orEmpty(),
                        dev.nitrate.core.playback.CreditSkipper.message(a), null, listOf(dev.nitrate.core.remote.RemotePrompt.Choice("act", it)),
                    )
                }
            }
            AskPhones(remote, ask) {
                when (a) {
                    is dev.nitrate.core.playback.CreditSkipper.Action.Skip -> player.seekTo(a.backMs)
                    is dev.nitrate.core.playback.CreditSkipper.Action.Offer -> player.seekTo(a.toMs)
                    else -> Unit
                }
                creditAction = null
                refocus()
            }
            ActionCorner(actionBottom, onSize = { actionSize = it }) { CreditSceneBanner(
                a,
                // Never takes focus from More, Info or a dialog; Back closes those first anyway.
                takeFocus = panel == Panel.NONE && person == null,
                onAct = { act ->
                    when (act) {
                        is dev.nitrate.core.playback.CreditSkipper.Action.Skip -> player.seekTo(act.backMs)
                        is dev.nitrate.core.playback.CreditSkipper.Action.Offer -> player.seekTo(act.toMs)
                        else -> Unit
                    }
                },
                onDone = {
                    creditAction = null
                    refocus()
                },
            ) }
        }

        if (panel == Panel.MORE && p != null) {
            MoreSheet(
                moreTabs(
                    current = item,
                    titleStrips = titleStrips,
                    // The cast (as on the details page, when shown there); OK opens a pop-up over the video.
                    cast = if (showCast) item?.people.orEmpty().filter { it.imageTag != null || !it.role.isNullOrBlank() }.take(30) else emptyList(),
                    chapterImage = { i -> item?.let { repository.chapterImageUrl(it, i, 320) } },
                    personImage = repository::personImageUrl,
                    position = { position },
                    onChapter = { player.seekTo(it) },
                    onPerson = { person = it },
                    choices = rememberPlayerChoices(session, playbackSettings, repository, sleepAt, sleepAtEndState.value,
                        onSleep = { at, end -> sleepAt = at; sleepAtEndState.value = end }, onDone = { panel = Panel.NONE },
                        frameRate = frameRateOverride, onFrameRate = { frameRateOverride = it }, displayHz = rememberDisplayHz()),
                ),
                // Never reopens on the episodes or collection (spoilers): Settings instead.
                initial = moreTab?.takeIf { it != "siblings" },
                onTab = { moreTab = it },
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }

        if (panel == Panel.INFO && p != null) {
            // Over the video with the controls out of the way; ▲▼ scroll it, Back closes it.
            PlaybackInfoPanel(p, videoDecoder, audioDecoder, Modifier.align(Alignment.Center).padding(horizontal = 32.dp, vertical = 24.dp), audio = session.audioPath.collectAsState().value,
                quality = session.quality.collectAsState().value,
                video = androidx.compose.runtime.produceState<dev.nitrate.core.playback.VideoPath?>(null, session) { session.videoPath().collect { value = it } }.value,
                trail = androidx.compose.runtime.produceState<dev.nitrate.core.playback.PlaybackTrail?>(null, session, capabilities) {
                    val caps = capabilities ?: return@produceState
                    while (true) {
                        value = session.trail(caps)
                        kotlinx.coroutines.delay(2_000)
                    }
                }.value)
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
        if (testPlay != null) Text(
            testLeft?.let { "Test play · ${(it + 999) / 1000} s left · Back stops it" } ?: "Test play · starting…",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.TopEnd).padding(32.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), MaterialTheme.shapes.medium).padding(horizontal = 16.dp, vertical = 10.dp),
        )
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
    // The button went (the title stopped being shared, or the session ended) while its panel was open.
    LaunchedEffect(groupLabel == null) { if (groupLabel == null && panel == Panel.GROUP) panel = Panel.NONE }
    LaunchedEffect(chatLabel == null) { if (chatLabel == null && panel == Panel.CHAT) panel = Panel.NONE }
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

/**
 * The action corner, bottom right: where whatever OK acts on over the video shows (Up Next, Skip
 * intro/recap/credits, a scene in the credits), sliding in from the right; [bottom] glides as the
 * controls come and go. [onSize]: how big it is, so subtitles lift above it and it docks in the controls.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.ActionCorner(bottom: androidx.compose.ui.unit.Dp, onSize: (androidx.compose.ui.unit.IntSize) -> Unit, content: @Composable () -> Unit) {
    val y by androidx.compose.animation.core.animateDpAsState(bottom, label = "action-corner")
    val shown = remember { androidx.compose.animation.core.MutableTransitionState(false) }.apply { targetState = true }
    androidx.compose.animation.AnimatedVisibility(
        visibleState = shown,
        enter = androidx.compose.animation.slideInHorizontally { it / 2 } + fadeIn(),
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = y).onSizeChanged(onSize),
    ) { content() }
}

/** [read] read in a scope of its own, so a value that changes often (the clock) redraws only [content]. */
@Composable
internal fun <T> Reading(read: () -> T, content: @Composable (T) -> Unit) = content(read())
