package dev.nitrate.android.playback

import android.content.Context
import android.net.Uri
import java.io.File
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import dev.nitrate.core.syncplay.SyncPlayController
import dev.nitrate.core.syncplay.SyncPlayer
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import dev.nitrate.core.model.ExternalSubtitle
import dev.nitrate.core.playback.SubtitleShift
import dev.nitrate.core.playback.trackName
import dev.nitrate.core.playback.TrackMemory
import dev.nitrate.core.playback.TrackMemoryStore
import kotlinx.coroutines.withContext
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.MediaKind
import dev.nitrate.core.model.MediaSegment
import dev.nitrate.core.model.TrickplayInfo
import dev.nitrate.core.model.PlayMethod
import dev.nitrate.core.model.PlaybackPlan
import dev.nitrate.core.model.StreamKind
import dev.nitrate.core.model.SubtitleDelivery
import dev.nitrate.core.playback.CapabilityRules
import dev.nitrate.core.playback.PlaybackSettingsRepository
import dev.nitrate.core.plugins.PlaybackControls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.media3.common.MediaItem as ExoMediaItem

/**
 * One item playing in Media3, shared by the TV and phone apps (each draws its own controls).
 *
 * Negotiates with the server, side-loads external subtitles, maps the server's audio/subtitle
 * streams onto the exo's tracks, reports progress, and when direct play fails with a decoder
 * error, remembers the format and retries as a transcode from the same spot.
 *
 * [loadLocal] plays a downloaded file instead: progress goes to a callback (saved on the device
 * and synced later) rather than to the server.
 */
@OptIn(UnstableApi::class)
class PlaybackSession(
    context: Context,
    private val server: MediaServer,
    private val settings: PlaybackSettingsRepository,
    /** "This TV" or "This device", for the message shown after a decoder failure. */
    private val deviceNoun: String,
    private val onEnded: () -> Unit,
    /** Remembers audio and subtitle languages per show (null: don't). */
    private val trackMemory: TrackMemoryStore? = null,
    /** The user's audio-description preference (DisplayPrefs.describedAudio), read when each title starts. */
    private val describedAudio: suspend () -> Boolean = { false },
    /** What this device reports it can play (probed once per app run). */
    private val capabilities: () -> dev.nitrate.core.playback.DeviceCapabilities = { DeviceProbe.cached(context) },
) {
    private val appContext = context.applicationContext
    private val nightMode = NightModeAudioProcessor()

    // Upmixing stereo to the speakers of the output in use (films and episodes only).
    @Volatile private var upmixOn = false
    @Volatile private var speakerLayouts: Map<String, dev.nitrate.core.playback.SpeakerLayout> = emptyMap()
    private val upmix = UpmixAudioProcessor { upmixLayout() }

    /** Where sound goes now (HDMI to a receiver, the TV, headphones...), as outputs come and go. */
    val soundOutput: StateFlow<dev.nitrate.core.playback.SoundOutput?> = SoundOutputs.current(context)

    private fun upmixLayout(): dev.nitrate.core.playback.SpeakerLayout? {
        val output = soundOutput.value
        val video = _item.value?.mediaKind != dev.nitrate.core.model.MediaKind.AUDIO
        return dev.nitrate.core.playback.Speakers.upmixTo(upmixOn, 2, video, output, speakerLayouts)
            ?.let { dev.nitrate.core.playback.Speakers.layout(output, speakerLayouts) }
    }

    /** Stereo playing now would be upmixed differently: set the sound up again from here. */
    private fun recheckUpmix(why: String) {
        val format = exo.audioFormat ?: return
        if (format.channelCount != 2 || _plan.value == null) return
        if (upmixLayout() == upmix.active) return
        log("Upmix ${upmixLayout()?.label ?: "off"} ($why): setting the sound up again")
        _plan.value?.let { play(it, exo.currentPosition, _item.value?.name.orEmpty(), keepPaused = !exo.playWhenReady) }
    }

    /** Audio sync, read on the playback thread by the audio sink. */
    @Volatile private var audioDelayUs = 0L
    private val _audioDelayMs = MutableStateFlow(0)

    /** How far the sound is moved against the picture (positive: later). */
    val audioDelayMs: StateFlow<Int> = _audioDelayMs

    private val _speed = MutableStateFlow(1f)

    /** Playback speed, 0.5x to 2x. */
    val speed: StateFlow<Float> = _speed
    private val _subtitleOffsetMs = MutableStateFlow(0L)

    /** How far subtitles are moved: positive shows them later. */
    val subtitleOffsetMs: StateFlow<Long> = _subtitleOffsetMs
    private val _nightModeOn = MutableStateFlow(false)
    val nightModeOn: StateFlow<Boolean> = _nightModeOn
    private val _dialogueBoostOn = MutableStateFlow(false)

    /** Voices lifted over music and effects (decoded sound only). */
    val dialogueBoostOn: StateFlow<Boolean> = _dialogueBoostOn

    /** Original text of retimed subtitles, so repeated changes shift from the original. */
    private val subtitleOriginals = mutableMapOf<Int, String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _item = MutableStateFlow<MediaItem?>(null)
    val item: StateFlow<MediaItem?> = _item
    private val _plan = MutableStateFlow<PlaybackPlan?>(null)
    val plan: StateFlow<PlaybackPlan?> = _plan
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val _notice = MutableStateFlow<String?>(null)

    /** A one-off message, e.g. "switched to transcoding"; cleared after a few seconds. */
    val notice: StateFlow<String?> = _notice
    private val _videoFrameRate = MutableStateFlow<Float?>(null)

    /** The playing video's frames per second, from the file or else the server's stream info. */
    val videoFrameRate: StateFlow<Float?> = _videoFrameRate

    /**
     * How the player itself follows the frame rate: only seamlessly, or not at all (when the app
     * switches the display mode itself, or the user turned it off).
     */
    fun setFrameRateMatch(match: dev.nitrate.core.playback.FrameRateMatch) {
        exo.videoChangeFrameRateStrategy = if (match == dev.nitrate.core.playback.FrameRateMatch.SEAMLESS) C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
        else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
    }

    private val _audioPath = MutableStateFlow(dev.nitrate.core.playback.AudioPath())

    /** The sound's path: the file's audio, how it's handled here, what goes to the TV or receiver. */
    val audioPath: StateFlow<dev.nitrate.core.playback.AudioPath> = _audioPath

    private fun updateProcessing() {
        _audioPath.value = _audioPath.value.copy(
            processing = listOfNotNull("dialogue boost".takeIf { nightMode.dialogueBoost }, "night mode".takeIf { nightMode.enabled }),
        )
    }

    @Volatile private var videoFormat: androidx.media3.common.Format? = null
    @Volatile private var droppedFrames = 0
    @Volatile private var networkBps: Long? = null

    /**
     * The picture's path, refreshed every second while collected (the Info panel): what the decoder
     * gets, HDR and how it's shown, the display's mode, smoothness and how subtitles are drawn.
     */
    fun videoPath(): kotlinx.coroutines.flow.Flow<dev.nitrate.core.playback.VideoPath> = kotlinx.coroutines.flow.flow {
        while (true) {
            emit(videoSnapshot())
            delay(1_000)
        }
    }

    private fun videoSnapshot(): dev.nitrate.core.playback.VideoPath {
        val p = _plan.value
        val stream = p?.source?.streams?.firstOrNull { it.kind == StreamKind.VIDEO }
        val f = videoFormat
        val shownRange = when {
            f == null -> null
            f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
            f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10"
            f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
            else -> "SDR"
        }
        val type = stream?.rangeType.orEmpty()
        val fileRange = when {
            type.startsWith("DOVIWithEL", ignoreCase = true) -> "Dolby Vision (profile 7)"
            type.startsWith("DOVI", ignoreCase = true) -> "Dolby Vision"
            type.equals("HDR10Plus", ignoreCase = true) -> "HDR10+"
            type.equals("HDR10", ignoreCase = true) -> "HDR10"
            type.equals("HLG", ignoreCase = true) -> "HLG"
            else -> null
        }
        val display = DisplayInfo.read(appContext)
        return dev.nitrate.core.playback.VideoPath(
            inCodec = stream?.codec ?: f?.sampleMimeType?.substringAfter('/'),
            inWidth = f?.width?.takeIf { it > 0 } ?: stream?.width,
            inHeight = f?.height?.takeIf { it > 0 } ?: stream?.height,
            inFps = f?.frameRate?.takeIf { it > 0 } ?: stream?.frameRate,
            inRange = shownRange,
            fileRange = fileRange?.takeIf { it != shownRange },
            profile7 = profile7.takeIf { type.startsWith("DOVIWithEL", ignoreCase = true) },
            software = _videoDecoder.value?.startsWith(dev.nitrate.core.playback.DeviceCapabilities.SOFTWARE_VIDEO_DECODER) == true,
            tunneled = runCatching { exo.isTunnelingEnabled }.getOrDefault(false),
            displayWidth = display?.width,
            displayHeight = display?.height,
            displayHz = display?.hz,
            displayHdr = display?.hdr,
            droppedFrames = droppedFrames,
            bufferedMs = exo.totalBufferedDuration.takeIf { _plan.value != null },
            networkBps = networkBps?.takeIf { p?.url?.startsWith("/") != true },
            fileBps = p?.source?.bitrate?.toLong()?.takeIf { it > 0 && p.url.startsWith("/").not() },
            subtitles = p?.let { subtitleMethod(it) },
            secondSubtitle = _secondSubtitle.value?.label,
            convertingTo = p?.takeIf { it.method == PlayMethod.TRANSCODE }?.let { dev.nitrate.core.playback.VideoPath.convertingTo(it.url) },
        )
    }

    /** How the chosen subtitle is drawn, in plain words. */
    private fun subtitleMethod(p: PlaybackPlan): String {
        val s = p.source.streams.firstOrNull { it.kind == StreamKind.SUBTITLE && it.index == p.subtitleIndex } ?: return "Off"
        val codec = s.codec?.lowercase()
        return when {
            s.delivery == SubtitleDelivery.ENCODE -> "Burned into the picture by the server"
            drawsWithLibass(p) -> "Styled (ASS), drawn by libass as the file styles them"
            dev.nitrate.core.playback.isStyledSubtitle(codec) -> "Styled (ASS), shown " + if (styledMode == dev.nitrate.core.playback.StyledSubtitles.MINE) "in your subtitle style" else "more simply"
            codec in setOf("pgssub", "pgs", "dvdsub", "dvbsub", "vobsub", "sup") -> "Picture subtitles (${codec?.uppercase()}), as the disc drew them"
            else -> "Text (${codec?.uppercase() ?: "?"}), in your subtitle style"
        }
    }

    private val _videoDecoder = MutableStateFlow<String?>(null)
    val videoDecoder: StateFlow<String?> = _videoDecoder
    private val _audioDecoder = MutableStateFlow<String?>(null)
    val audioDecoder: StateFlow<String?> = _audioDecoder

    private val _segments = MutableStateFlow<List<MediaSegment>>(emptyList())

    /** Intro, credits... from the server, for skip buttons and Up Next timing. */
    val segments: StateFlow<List<MediaSegment>> = _segments
    /** A second subtitle shown at the top of the picture with the usual one (dual subtitles). */
    data class SecondSubtitle(val itemId: String, val index: Int, val label: String, val cues: List<dev.nitrate.core.playback.Cue>)

    private val _secondSubtitle = MutableStateFlow<SecondSubtitle?>(null)
    val secondSubtitle: StateFlow<SecondSubtitle?> = _secondSubtitle
    private var secondJob: Job? = null

    private val _creditScenes = MutableStateFlow<dev.nitrate.core.playback.CreditScenesInfo?>(null)

    /** Scenes during and after this title's credits: where (to skip to them) or only that there are some. */
    val creditScenes: StateFlow<dev.nitrate.core.playback.CreditScenesInfo?> = _creditScenes
    private val _trickplay = MutableStateFlow<TrickplayInfo?>(null)

    /** Scrubbing thumbnails, when the server has them. */
    val trickplay: StateFlow<TrickplayInfo?> = _trickplay

    /** Position and duration (ms), updated twice a second while playing. */
    data class Progress(val positionMs: Long, val durationMs: Long)

    private val _progress = MutableStateFlow(Progress(0, 0))
    val progress: StateFlow<Progress> = _progress

    private var selectionApplied = false
    private var progressJob: Job? = null

    /** The one start in flight: a newer request (track change, retry, next item) replaces it. */
    private var startJob: Job? = null

    /** Subtitle timing per subtitle stream of this item, and the retime in flight. */
    private val subtitleOffsets = mutableMapOf<Int, Long>()
    private var retimeJob: Job? = null

    /** The last few hundred things the player did, for a playback report. */
    private val events = ArrayDeque<String>()
    private val clock = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)

    private fun log(message: String) = synchronized(events) {
        if (events.size >= MAX_EVENTS) events.removeFirst()
        events.addLast("${clock.format(java.util.Date())}  $message")
    }

    /**
     * Extended media information for what's playing: the file and its streams, the server's
     * picks, what the player has and what's on, how subtitles are drawn ([styled] is the user's
     * choice for ASS/SSA), and for a download what its saved ASS files hold.
     */
    suspend fun mediaInfo(styled: dev.nitrate.core.playback.StyledSubtitles): String {
        val p = _plan.value ?: return "Nothing is playing yet."
        val tracks = exo.currentTracks.groups.flatMap { g ->
            val type = when (g.type) { C.TRACK_TYPE_VIDEO -> "video"; C.TRACK_TYPE_AUDIO -> "audio"; C.TRACK_TYPE_TEXT -> "subtitles"; else -> "other" }
            (0 until g.length).map { i ->
                val f = g.getTrackFormat(i)
                dev.nitrate.core.playback.PlayerTrack(type, f.id, f.language, f.label, f.sampleMimeType, f.codecs, g.isTrackSelected(i), g.isTrackSupported(i))
            }
        }
        val local = localProgress != null
        val files = if (!local) emptyMap() else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            p.externalSubtitles.filter { it.mimeType == MimeTypes.TEXT_SSA }.associate { s ->
                val file = File(s.url)
                file.name to runCatching { dev.nitrate.core.playback.MediaInfo.aboutAss(file.readText()) }.getOrElse { listOf("Couldn't read it: ${it.message}") }
            }
        }
        return dev.nitrate.core.playback.MediaInfo.render(
            item = _item.value, source = p.source, plan = p, local = local, playerTracks = tracks,
            subtitles = dev.nitrate.core.playback.SubtitleDrawing(
                styled, p.styledSubtitle && styled != dev.nitrate.core.playback.StyledSubtitles.MINE,
                libass = if (!AssSubtitleView.isAvailable) null else _assTrack.value != null,
                fonts = _assTrack.value?.fonts?.size ?: 0,
            ),
            videoDecoder = _videoDecoder.value, audioDecoder = _audioDecoder.value, subtitleFiles = files,
        )
    }

    /** What the player did recently, oldest first. */
    fun eventLog(): List<String> = synchronized(events) { events.toList() }

    /** Set for a downloaded file: where progress goes instead of the server. */
    private var localProgress: (suspend (positionMs: Long, durationMs: Long, ended: Boolean) -> Unit)? = null

    private val exo: ExoPlayer = run {
        // The auth header covers direct play, HLS playlists and segments, and subtitle files.
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Authorization" to server.authorizationHeader()))
        // Night mode sits in the audio path; it's off (passes audio through) until switched on.
        val renderers = object : DefaultRenderersFactory(context) {
            // Nitrate's software video decoder, after the device's own: only for video it can't decode.
            override fun buildVideoRenderers(
                context: Context, extensionRendererMode: Int, mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
                enableDecoderFallback: Boolean, eventHandler: android.os.Handler, eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long, out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>,
            ) {
                super.buildVideoRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, allowedVideoJoiningTimeMs, out)
                out += dev.nitrate.ffmpeg.FfmpegVideoRenderer(allowedVideoJoiningTimeMs, eventHandler, eventListener, 50)
            }

            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink {
                val sink = DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(upmix, nightMode)).setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams).build()
                // Audio sync: the player follows the audio clock, so moving the clock moves the
                // picture against the sound (decoded or passed through to a receiver alike).
                return object : androidx.media3.exoplayer.audio.ForwardingAudioSink(sink) {
                    // Night mode only works on decoded sound: while it's on, encoded formats this
                    // device can decode aren't passed through, so the player decodes them instead.
                    override fun getFormatSupport(format: androidx.media3.common.Format): Int {
                        if (nightMode.active && NightModeDecoding.decodeInstead(format)) return androidx.media3.exoplayer.audio.AudioSink.SINK_FORMAT_UNSUPPORTED
                        return super.getFormatSupport(format)
                    }

                    override fun supportsFormat(format: androidx.media3.common.Format): Boolean =
                        getFormatSupport(format) != androidx.media3.exoplayer.audio.AudioSink.SINK_FORMAT_UNSUPPORTED

                    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
                        val position = super.getCurrentPositionUs(sourceEnded)
                        if (position == androidx.media3.exoplayer.audio.AudioSink.CURRENT_POSITION_NOT_SET) return position
                        return dev.nitrate.core.playback.AudioSync.shiftedPositionUs(position, audioDelayUs)
                    }
                }
            }
        }.setEnableDecoderFallback(true)
            // FFmpeg (ffmpeg-decoder) only for audio the device has no decoder for.
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        ExoPlayer.Builder(context)
            .setRenderersFactory(renderers)
            // DefaultDataSource sends http(s) through the authorised source and reads downloaded
            // files (file://) and their subtitles itself; the http source alone can't open files.
            // Dual-layer Dolby Vision (profile 7): as it is, rewritten as profile 8.1, or its HDR10
            // base layer, by what this device decodes.
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context, DolbyVisionProfile7({ profile7 }, onFailures = { n -> log("Dolby Vision: $n frames' metadata couldn't be converted (shown as HDR10)") }))
                    .setDataSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(context, http)),
            )
            .build()
    }

    /** The group this player follows, while watching together. */
    private var group: SyncPlayController? = null
    private val inGroup get() = group != null

    /**
     * The player for screens, notifications and media buttons. While watching together, play,
     * pause and seek here ask the group instead, and the group's commands drive [exo].
     */
    val player: Player = object : ForwardingPlayer(exo) {
        override fun play() = group?.requestPlay() ?: super.play()
        override fun pause() = group?.requestPause() ?: super.pause()
        override fun setPlayWhenReady(playWhenReady: Boolean) {
            val g = group ?: return super.setPlayWhenReady(playWhenReady)
            if (playWhenReady) g.requestPlay() else g.requestPause()
        }
        override fun seekTo(positionMs: Long) = group?.requestSeek(positionMs) ?: super.seekTo(positionMs)
        override fun seekTo(mediaItemIndex: Int, positionMs: Long) = group?.requestSeek(positionMs) ?: super.seekTo(mediaItemIndex, positionMs)
        override fun seekBack() = group?.requestSeek(exo.currentPosition - exo.seekBackIncrement) ?: super.seekBack()
        override fun seekForward() = group?.requestSeek(exo.currentPosition + exo.seekForwardIncrement) ?: super.seekForward()
    }

    val controls = object : PlaybackControls {
        override val isPlaying get() = exo.isPlaying
        override fun togglePlay() = if (exo.isPlaying) player.pause() else player.play()
        override fun seekBy(deltaMs: Long) = player.seekTo((exo.currentPosition + deltaMs).coerceAtLeast(0))
    }

    /** What SyncPlay drives: loads start paused, and the group's commands act on [exo] directly. */
    private val syncPlayer = object : SyncPlayer {
        var loading: String? = null
        override val itemId get() = loading ?: _item.value?.id
        override val positionMs get() = exo.currentPosition
        override val isPlaying get() = exo.isPlaying
        override val isReady get() = loading == null && _plan.value != null && exo.playbackState == Player.STATE_READY
        override fun load(itemId: String, startMs: Long) {
            loading = itemId
            scope.launch {
                runCatching { server.getItem(itemId) }
                    .onSuccess {
                        startJob?.cancel()
                        forgetItemState()
                        _item.value = it
                        _plan.value?.let { old -> server.reportStopped(old, exo.currentPosition) }
                        _plan.value = null
                        loading = null
                        restart(startMs, forceTranscode = false, useMemory = true)
                        loadExtras(it.id)
                    }
                    .onFailure {
                        loading = null
                        _error.value = it.message ?: "Could not load item"
                    }
            }
        }
        override fun play() = exo.play()
        override fun pause() = exo.pause()
        override fun seek(positionMs: Long) = exo.seekTo(positionMs.coerceAtLeast(0))
        override fun setRate(rate: Float) = exo.setPlaybackSpeed(rate)
        override fun stop() = onEnded()
    }

    /**
     * Follows [syncPlay]'s group whenever this device is in one: joining mid-play hands the
     * current item to the group; leaving carries on alone.
     */
    fun followGroup(syncPlay: SyncPlayController) {
        groupWatch?.cancel()
        scope.launch { syncPlay.notices.collect { showNotice(it) } }
        groupWatch = scope.launch {
            syncPlay.group.map { it != null }.distinctUntilChanged().collect { joined ->
                if (joined && group == null) {
                    group = syncPlay
                    _speed.value = 1f
                    exo.setPlaybackSpeed(1f)
                    val id = syncPlayer.itemId ?: pendingItem?.first
                    if (id != null) syncPlay.attach(syncPlayer, id, if (_plan.value != null) exo.currentPosition else pendingItem?.second ?: 0)
                } else if (!joined && group != null) {
                    syncPlay.detach(syncPlayer)
                    group = null
                }
            }
        }
    }
    private var groupWatch: Job? = null

    /** What [load] was asked for, in case a group is joined before it finished. */
    private var pendingItem: Pair<String, Long>? = null

    private val listener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            val p = _plan.value ?: return
            if (!selectionApplied && !tracks.isEmpty) {
                selectionApplied = true
                applySelection(p)
            }
        }

        override fun onPlayerError(e: PlaybackException) {
            val format = (e as? ExoPlaybackException)?.takeIf { it.type == ExoPlaybackException.TYPE_RENDERER }?.rendererFormat
            log("Error ${e.errorCodeName}: ${e.message}" + (e.cause?.let { "; cause ${it::class.simpleName}: ${it.message}" } ?: "") +
                (format?.let { "; format ${it.sampleMimeType} ${it.codecs ?: ""} ${it.width}x${it.height}" } ?: ""))
            val failed = _plan.value
            if (failed == null || failed.method == PlayMethod.TRANSCODE || localProgress != null) {
                _error.value = "Playback failed: ${e.errorCodeName}"
                return
            }
            val at = exo.currentPosition
            if (e.errorCode in DECODER_ERRORS && failed.method == PlayMethod.DIRECT_PLAY) {
                val renderer = (e as? ExoPlaybackException)?.takeIf { it.type == ExoPlaybackException.TYPE_RENDERER }
                val videoFailed = !MimeTypes.isAudio(renderer?.rendererFormat?.sampleMimeType)
                CapabilityRules.failureCapabilityId(failed, videoFailed)?.let { id ->
                    scope.launch { settings.recordFailure(id, e.errorCodeName, System.currentTimeMillis()) }
                }
                showNotice(
                    "$deviceNoun couldn't decode it (${e.errorCodeName}). Switched to transcoding; " +
                        "it will transcode this format from now on. Change this in Playback settings.",
                )
            }
            log("Direct ${failed.method} failed at ${at}ms: retrying as a transcode")
            server.reportStopped(failed, at)
            _plan.value = null
            restart(at, forceTranscode = true, audioIndex = failed.audioIndex, subtitleIndex = failed.subtitleIndex)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            group?.onPlayerState()
        }

        override fun onPlaybackStateChanged(state: Int) {
            log("State ${when (state) { Player.STATE_BUFFERING -> "buffering"; Player.STATE_READY -> "ready"; Player.STATE_ENDED -> "ended"; else -> "idle" }} at ${exo.currentPosition}ms")
            group?.onPlayerState()
            if (state != Player.STATE_ENDED) return
            group?.let { g ->
                // The group moves on to its next item, or everyone's done.
                val q = g.queue.value
                g.onEnded()
                if (q != null && q.index < q.itemIds.lastIndex) return
            }
            localProgress?.let { record ->
                val duration = exo.duration.coerceAtLeast(0)
                localProgress = null // Recorded as watched here, not again on release.
                appScope.launch { record(duration, duration, true) }
            }
            // A title in several files: on to the next part.
            remainingParts.firstOrNull()?.let { next ->
                if (group == null) {
                    remainingParts = remainingParts.drop(1)
                    load(next.id, fromStart = true, startMs = 0L, nextPart = true)
                    return
                }
            }
            onEnded()
        }
    }

    init {
        // A soundbar turning on or off, headphones plugged in: upmix for the new output, or stop.
        scope.launch {
            soundOutput.collect { o ->
                log("Sound output: ${o?.label ?: "unknown"}${o?.maxChannels?.let { " (up to $it channels)" } ?: ""}")
                recheckUpmix("output changed")
            }
        }
        scope.launch { setNightMode(settings.current().nightMode) }
        scope.launch { setDialogueBoost(settings.current().dialogueBoost) }
        scope.launch { setAudioDelay(settings.current().audioDelayMs, resync = false) }
        scope.launch { setFrameRateMatch(settings.current().frameRateMatch) }
        // How styled subtitles are shown can change from the player's settings while playing.
        scope.launch {
            settings.settings.collect { s ->
                threeDMode = s.threeD
                val upmixChanged = s.upmixStereo != upmixOn || s.speakerLayouts != speakerLayouts
                upmixOn = s.upmixStereo
                speakerLayouts = s.speakerLayouts
                if (upmixChanged) recheckUpmix("settings")
                profile7 = runCatching { dev.nitrate.core.playback.CapabilityRules.profile7(capabilities(), s) }.getOrDefault(dev.nitrate.core.playback.Profile7Playback.BASE_LAYER)
                val mode = s.subtitleStyle.styled
                if (mode != styledMode) {
                    styledMode = mode
                    _plan.value?.let { applySelection(it); updateAss(it) }
                }
            }
        }
        // Tunneled playback, when the device's settings ask for it (the player picks it only
        // when the device's decoders can tunnel this video and audio).
        scope.launch {
            val on = settings.current().tunneling
            (exo.trackSelector as? androidx.media3.exoplayer.trackselection.DefaultTrackSelector)?.let { selector ->
                selector.parameters = selector.buildUponParameters().setTunnelingEnabled(on).build()
            }
            if (on) log("Tunneled playback on")
        }
        scope.launch {
            while (isActive) {
                _progress.value = Progress(exo.currentPosition, exo.duration.coerceAtLeast(0))
                delay(500)
            }
        }
        exo.addListener(listener)
        exo.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(e: AnalyticsListener.EventTime, name: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                // Nitrate's software decoder reads as such in the Info panel and reports.
                _videoDecoder.value = if (name.startsWith("nitrate.ffmpeg.")) "${dev.nitrate.core.playback.DeviceCapabilities.SOFTWARE_VIDEO_DECODER} ${name.removePrefix("nitrate.ffmpeg.")}" else name
                log("Video decoder $name (${initializationDurationMs}ms to start)")
            }

            override fun onAudioDecoderInitialized(e: AnalyticsListener.EventTime, name: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                _audioDecoder.value = name
                log("Audio decoder $name (${initializationDurationMs}ms to start)")
            }

            override fun onVideoInputFormatChanged(e: AnalyticsListener.EventTime, format: androidx.media3.common.Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
                videoFormat = format
                log("Video in: ${format.sampleMimeType} ${format.codecs ?: ""} ${format.width}x${format.height} ${format.frameRate}fps ${format.colorInfo?.toLogString() ?: ""}")
                val fromServer = _plan.value?.source?.streams?.firstOrNull { it.kind == StreamKind.VIDEO }?.frameRate
                _videoFrameRate.value = format.frameRate.takeIf { it > 0 } ?: fromServer
            }

            override fun onAudioInputFormatChanged(e: AnalyticsListener.EventTime, format: androidx.media3.common.Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
                log("Audio in: ${format.sampleMimeType} ${format.codecs ?: ""} ${format.channelCount}ch ${format.sampleRate}Hz")
                val stream = _plan.value?.let { p -> p.source.streams.firstOrNull { it.index == p.audioIndex } }
                _audioPath.value = _audioPath.value.copy(
                    inCodec = stream?.codec ?: format.sampleMimeType?.substringAfter('/'),
                    inChannels = format.channelCount.takeIf { it > 0 } ?: stream?.channels,
                    inRateHz = format.sampleRate.takeIf { it > 0 } ?: stream?.sampleRate,
                    inBits = stream?.bitDepth,
                )
            }

            override fun onAudioTrackInitialized(e: AnalyticsListener.EventTime, config: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig) {
                val decoded = config.encoding in setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_8BIT)
                val mixer = runCatching {
                    (appContext.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager)
                        .getProperty(android.media.AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
                }.getOrNull()
                val output = soundOutput.value
                val layout = dev.nitrate.core.playback.Speakers.layout(output, speakerLayouts)
                _audioPath.value = _audioPath.value.copy(
                    output = output?.let { it.label + (layout?.let { l -> " · speakers ${l.label}" } ?: "") },
                    upmixedTo = upmix.active?.label,
                    delivery = if (decoded) dev.nitrate.core.playback.AudioDelivery.DECODED else dev.nitrate.core.playback.AudioDelivery.BITSTREAM,
                    outFormat = encodingName(config.encoding),
                    outChannels = Integer.bitCount(config.channelConfig).takeIf { it > 0 },
                    outRateHz = config.sampleRate,
                    mixerRateHz = mixer,
                )
                updateProcessing()
                log("Audio out: ${encodingName(config.encoding)} ${Integer.bitCount(config.channelConfig)}ch ${config.sampleRate}Hz${if (config.tunneling) " tunneled" else ""}${if (config.offload) " offloaded" else ""}; mixer ${mixer ?: "?"}Hz")
            }

            override fun onBandwidthEstimate(e: AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) {
                networkBps = bitrateEstimate
            }

            override fun onDroppedVideoFrames(e: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                this@PlaybackSession.droppedFrames += droppedFrames
                log("Dropped $droppedFrames video frames in ${elapsedMs}ms")
            }

            override fun onAudioSinkError(e: AnalyticsListener.EventTime, audioSinkError: Exception) {
                log("Audio output error: ${audioSinkError::class.simpleName}: ${audioSinkError.message}")
            }

            override fun onAudioCodecError(e: AnalyticsListener.EventTime, audioCodecError: Exception) {
                log("Audio decoder error: ${audioCodecError::class.simpleName}: ${audioCodecError.message}")
            }

            override fun onVideoCodecError(e: AnalyticsListener.EventTime, videoCodecError: Exception) {
                log("Video decoder error: ${videoCodecError::class.simpleName}: ${videoCodecError.message}")
            }
        })
    }

    /** Loads [itemId] and starts playing: at [startMs] if given, else resuming unless [fromStart]. */
    /** The rest of a title split across several files, played on when each part ends. */
    private var remainingParts: List<MediaItem> = emptyList()

    /**
     * Plays [itemId]. [audioIndex]/[subtitleIndex] (-1: subtitles off) are picks made elsewhere, e.g.
     * on a phone that sent this here; without them the show's remembered tracks apply.
     */
    fun load(itemId: String, fromStart: Boolean, startMs: Long? = null, audioIndex: Int? = null, subtitleIndex: Int? = null) {
        requestedTracks = (audioIndex to subtitleIndex).takeIf { audioIndex != null || subtitleIndex != null }
        load(itemId, fromStart, startMs, nextPart = false)
    }

    /** Tracks asked for by whoever started this (another device), used once. */
    private var requestedTracks: Pair<Int?, Int?>? = null

    /** A recap clip: nothing is reported to the server (watch history and resume points stay as they were). */
    private var quiet = false

    /** Where a recap clip stops (null: play to the end). */
    private var clipEndMs: Long? = null

    /**
     * Plays a recap clip: [itemId] from [startMs] to [endMs], then ends. It isn't reported, so the
     * user's watch history and resume points don't change, and nothing plays on after it.
     */
    fun loadClip(itemId: String, startMs: Long, endMs: Long) {
        quiet = true
        clipEndMs = endMs
        log("Recap clip $startMs–$endMs")
        load(itemId, fromStart = false, startMs = startMs, nextPart = true)
        scope.launch {
            while (isActive) {
                delay(250)
                if (exo.currentPosition >= endMs && _plan.value != null) {
                    exo.pause()
                    onEnded()
                    break
                }
            }
        }
    }

    private fun load(itemId: String, fromStart: Boolean, startMs: Long?, nextPart: Boolean) {
        log("Load $itemId (fromStart=$fromStart, startMs=$startMs${if (nextPart) ", next part" else ""})")
        if (!nextPart) remainingParts = emptyList()
        pendingItem = itemId to (startMs ?: 0)
        // A recap clip is this device's own, even in a watch-together group.
        group?.takeIf { !quiet }?.let { g ->
            // Watching together: the group decides where everyone starts.
            g.attach(syncPlayer, itemId, startMs ?: 0)
            return
        }
        forgetItemState()
        startJob?.cancel()
        startJob = scope.launch {
            runCatching { server.getItem(itemId) }
                .onSuccess {
                    _item.value = it
                    val asked = requestedTracks.also { requestedTracks = null }
                    start(
                        startMs ?: if (fromStart) 0L else it.user.positionMs, forceTranscode = false,
                        audioIndex = asked?.first, subtitleIndex = asked?.second, useMemory = asked == null,
                    )
                    loadExtras(it.id)
                    if (!nextPart && (it.partCount ?: 1) > 1) {
                        remainingParts = runCatching { server.additionalParts(it.id) }.getOrDefault(emptyList())
                        log("${remainingParts.size} more part(s) to play after this one")
                    }
                }
                .onFailure { _error.value = it.message ?: "Could not load item" }
        }
    }

    private suspend fun withRememberedLanguages(item: MediaItem, plan: PlaybackPlan): PlaybackPlan {
        val series = dev.nitrate.core.playback.TrackSelection.memoryKey(item)
        val choice = runCatching { trackMemory?.get(series) }.getOrNull() ?: return plan
        val (audio, subtitle) = TrackMemory.pick(plan.source.streams, choice)
        return plan.copy(audioIndex = audio ?: plan.audioIndex, subtitleIndex = subtitle ?: plan.subtitleIndex)
    }

    /** Plays a downloaded file from [plan] (local paths), reporting progress to [onProgress]. */
    fun loadLocal(item: MediaItem, plan: PlaybackPlan, startMs: Long, onProgress: suspend (positionMs: Long, durationMs: Long, ended: Boolean) -> Unit) {
        localProgress = onProgress
        _item.value = item
        // The show's remembered languages win over the download's defaults, as when streaming; the
        // audio-description preference applies when nothing was picked.
        scope.launch {
            val remembered = withRememberedLanguages(item, plan)
            val want = runCatching { describedAudio() }.getOrDefault(false)
            val picked = remembered.audioIndex != plan.audioIndex
            val audio = if (picked) remembered.audioIndex else dev.nitrate.core.playback.AudioDescription.apply(remembered.source, remembered.audioIndex, want)
            play(remembered.copy(audioIndex = audio), startMs, item.name)
        }
        // Skip markers still work offline if the server answers; thumbnails need it anyway.
        loadExtras(item.id, thumbnails = false)
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                localProgress?.invoke(exo.currentPosition, exo.duration.coerceAtLeast(0), false)
            }
        }
    }

    private fun loadExtras(itemId: String, thumbnails: Boolean = true) {
        _creditScenes.value = null
        scope.launch {
            _segments.value = runCatching { server.mediaSegments(itemId) }.getOrDefault(emptyList())
            if (!quiet) runCatching { findCreditScenes(itemId, thumbnails) }.onFailure { log("Credit scenes: ${it.message}") }
        }
        if (thumbnails) scope.launch { _trickplay.value = runCatching { server.trickplay(itemId, _plan.value?.mediaSourceId) }.getOrNull() }
    }

    /**
     * Scenes in the credits: from the title's tags, the server's credit markers and the chapters
     * first; then, for films (or anything tagged as having one) with no marker, from the scrub
     * thumbnails of the last stretch.
     */
    private suspend fun findCreditScenes(itemId: String, thumbnails: Boolean) {
        val item = _item.value?.takeIf { it.id == itemId } ?: return
        val runtime = item.runtimeMs?.takeIf { it > 0 } ?: return
        if (item.mediaKind == dev.nitrate.core.model.MediaKind.AUDIO) return
        val first = dev.nitrate.core.playback.CreditScenes.info(runtime, item.tags, _segments.value, item.chapters)
        _creditScenes.value = first
        if (first.skips.isNotEmpty() || !thumbnails) return
        if (item.kind != dev.nitrate.core.model.ItemKind.MOVIE && !first.tagged) return
        val info = server.trickplay(itemId, _plan.value?.mediaSourceId) ?: return
        val frames = withContext(Dispatchers.IO) { CreditFrames.read(server, itemId, info, runtime) } ?: return
        if (_item.value?.id != itemId) return
        val read = dev.nitrate.core.playback.CreditScenes.info(runtime, item.tags, _segments.value, item.chapters, frames, info.intervalMs)
        log("Credit scenes from thumbnails: ${read.skips} (${read.certainty})")
        _creditScenes.value = read
    }

    fun seekTo(positionMs: Long) = player.seekTo(positionMs.coerceAtLeast(0))

    /**
     * Shows subtitle stream [index] at the top as well as the usual one (null: off). Remembered
     * for the show (or film) while the app runs, so the next episode shows the same language.
     */
    fun setSecondSubtitle(index: Int?) {
        val p = _plan.value ?: return
        val key = _item.value?.let { dev.nitrate.core.playback.TrackSelection.memoryKey(it) }
        secondJob?.cancel()
        if (index == null) {
            _secondSubtitle.value = null
            key?.let { secondMemory.remove(it) }
            return
        }
        val stream = p.source.streams.firstOrNull { it.index == index } ?: return
        key?.let { secondMemory[it] = stream.language to stream.title }
        loadSecondSubtitle(p, index)
    }

    private fun loadSecondSubtitle(p: PlaybackPlan, index: Int) {
        val label = p.source.trackName(index)
        secondJob = scope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) {
                    // A download's own subtitle file (SRT or WebVTT), else the server's copy as SRT.
                    val local = p.externalSubtitles.firstOrNull { it.streamIndex == index }
                        ?.takeIf { it.url.startsWith("/") && (it.mimeType.contains("subrip", true) || it.mimeType.contains("vtt", true)) }
                    if (local != null) File(local.url).readText() else fetchText(server.subtitleFileUrl(p.itemId, p.source, index, "srt"))
                }
                val cues = dev.nitrate.core.playback.SecondSubtitles.parse(text)
                log("Second subtitle $label: ${cues.size} lines")
                _secondSubtitle.value = SecondSubtitle(p.itemId, index, label, cues)
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                showNotice("Couldn't load the second subtitle: ${it.message}")
            }
        }
    }

    /** The same second language as last time for this show, when this file has it. */
    private fun restoreSecondSubtitle(p: PlaybackPlan) {
        val item = _item.value ?: return
        val (language, title) = secondMemory[dev.nitrate.core.playback.TrackSelection.memoryKey(item)] ?: return
        val choices = dev.nitrate.core.playback.SecondSubtitles.choices(p.source, p.subtitleIndex).map { c -> p.source.streams.first { it.index == c.index } }
        val match = choices.firstOrNull { it.language == language && it.title == title } ?: choices.firstOrNull { it.language == language } ?: return
        loadSecondSubtitle(p, match.index)
    }

    private fun uriOf(url: String): Uri = if (url.startsWith("/")) Uri.fromFile(File(url)) else Uri.parse(url)

    private fun play(p: PlaybackPlan, startMs: Long, title: String, keepPaused: Boolean? = null) {
        _plan.value = p
        if (_secondSubtitle.value?.itemId != p.itemId) {
            _secondSubtitle.value = null
            restoreSecondSubtitle(p)
        }
        updateAss(p)
        selectionApplied = false
        _videoDecoder.value = null
        _audioDecoder.value = null
        val subtitles = p.externalSubtitles.map { sub ->
            ExoMediaItem.SubtitleConfiguration.Builder(uriOf(sub.url))
                .setId("jf-${sub.streamIndex}")
                .setMimeType(sub.mimeType)
                .setLanguage(sub.language)
                .setLabel(sub.label)
                .build()
        }
        val media = ExoMediaItem.Builder()
            .setUri(uriOf(p.url))
            .apply { if (p.isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .setSubtitleConfigurations(subtitles)
            .setMediaMetadata(metadata(title))
            .build()
        applyThreeD()
        // Jellyfin's HLS playlists span the whole file, so every method seeks client-side.
        exo.setMediaItem(media, startMs)
        exo.prepare()
        _error.value = null
        // In a group, the group says when to play once everyone is ready; a reload of what was
        // paused (e.g. a subtitle retime) stays paused.
        exo.playWhenReady = !inGroup && (keepPaused?.not() ?: true)
    }

    /** Title, and for music the artist, album and cover (shown by the player view and lock screen). */
    private fun metadata(title: String): MediaMetadata {
        val item = _item.value
        return MediaMetadata.Builder()
            .setTitle(title)
            .apply {
                if (item != null) {
                    (item.artists.firstOrNull() ?: item.albumArtist ?: item.seriesName)?.let(::setArtist)
                    item.album?.let(::setAlbumTitle)
                    item.albumArtist?.let(::setAlbumArtist)
                    runCatching { server.imageUrl(item, dev.nitrate.core.model.ImageKind.PRIMARY, 720) }.getOrNull()?.let { setArtworkUri(Uri.parse(it)) }
                }
            }
            .build()
    }

    /** Starts (or restarts) playback, cancelling a start still waiting on the server. */
    private fun restart(
        startMs: Long, forceTranscode: Boolean, audioIndex: Int? = null, subtitleIndex: Int? = null, useMemory: Boolean = false,
        sourceId: String? = null, before: (suspend () -> Unit)? = null,
    ) {
        startJob?.cancel()
        startJob = scope.launch {
            before?.invoke()
            start(startMs, forceTranscode, audioIndex, subtitleIndex, useMemory, sourceId)
        }
    }

    /**
     * True while this item converts because Nitrate asked for it after direct play failed (not
     * because the server decided), so track changes keep converting and the fixer can offer a retry.
     */
    @Volatile private var forcedTranscode = false

    /** Why the current item doesn't play as it is here, and what would fix it. */
    suspend fun diagnosis(caps: dev.nitrate.core.playback.DeviceCapabilities): dev.nitrate.core.playback.PlaybackDiagnosis? {
        val p = _plan.value ?: return null
        if (localProgress != null) return null
        return dev.nitrate.core.playback.PlaybackDoctor.diagnose(p, caps, settings.current(), forcedTranscode)
    }

    /** Applies a fix from [diagnosis] and asks the server again from the same spot. */
    fun applyFix(fix: dev.nitrate.core.playback.PlaybackFix) {
        val p = _plan.value ?: return
        val at = exo.currentPosition
        log("Fix: ${fix.label}")
        when (fix) {
            is dev.nitrate.core.playback.PlaybackFix.UseAudio -> { forcedTranscode = false; selectTrack(StreamKind.AUDIO, fix.index) }
            is dev.nitrate.core.playback.PlaybackFix.UseSubtitle -> { forcedTranscode = false; selectTrack(StreamKind.SUBTITLE, fix.index) }
            is dev.nitrate.core.playback.PlaybackFix.UseVersion -> {
                _item.value?.let { VersionChoices.set(it.id, fix.versionId) }
                restart(at, forceTranscode = false, sourceId = fix.versionId)
            }
            is dev.nitrate.core.playback.PlaybackFix.Retry ->
                restart(at, forceTranscode = false, audioIndex = p.audioIndex, subtitleIndex = p.subtitleIndex)
            is dev.nitrate.core.playback.PlaybackFix.Change ->
                restart(at, forceTranscode = false, audioIndex = p.audioIndex, subtitleIndex = p.subtitleIndex, before = { settings.update(fix.change::apply) })
        }
    }

    /** A new item: subtitle timing and its cached files belong to the old one. */
    private fun forgetItemState() {
        videoFormat = null
        droppedFrames = 0
        _audioPath.value = dev.nitrate.core.playback.AudioPath()
        secondJob?.cancel()
        _secondSubtitle.value = null
        retimeJob?.cancel()
        assJob?.cancel()
        _assTrack.value = null
        subtitleOriginals.clear()
        subtitleOffsets.clear()
        _subtitleOffsetMs.value = 0
        _error.value = null
    }

    private suspend fun start(startMs: Long, forceTranscode: Boolean, audioIndex: Int? = null, subtitleIndex: Int? = null, useMemory: Boolean = false, sourceId: String? = null) {
        val current = _item.value ?: return
        forcedTranscode = forceTranscode
        // What the user picked for this show (matched to this episode's streams) or this title.
        var audio = audioIndex
        var subtitle = subtitleIndex
        if (useMemory && audio == null && subtitle == null) {
            val choice = runCatching { trackMemory?.get(dev.nitrate.core.playback.TrackSelection.memoryKey(current)) }.getOrNull()
            val wantDescribed = runCatching { describedAudio() }.getOrDefault(false)
            // The file's tracks are only needed for a remembered pick or the audio-description choice
            // (which also steers away from a described track the server would start with).
            runCatching { server.mediaSource(current.id) }.getOrNull()?.let { src ->
                if (choice != null) {
                    val (a, sub) = TrackMemory.pick(src.streams, choice)
                    audio = a
                    subtitle = sub
                }
                if (audio == null) {
                    val serverPick = src.defaultAudioIndex.takeIf { src.defaultsKnown }
                    dev.nitrate.core.playback.AudioDescription.apply(src, serverPick, wantDescribed)
                        ?.takeIf { it != serverPick }?.let { audio = it; log("Audio description ${if (wantDescribed) "on" else "off"}: audio #$it") }
                }
            }
        }
        val audioIndex = audio
        val subtitleIndex = subtitle
        log("Ask the server how to play ${current.id} from ${startMs}ms (forceTranscode=$forceTranscode, audio=$audioIndex, subtitle=$subtitleIndex)")
        runCatching {
            server.resolvePlayback(
                itemId = current.id,
                isAudio = current.mediaKind == MediaKind.AUDIO,
                startMs = startMs,
                forceTranscode = forceTranscode,
                audioIndex = audioIndex,
                subtitleIndex = subtitleIndex,
                // The same version on a track change; otherwise the one chosen on the details page, if any.
                mediaSourceId = sourceId ?: _plan.value?.takeIf { it.itemId == current.id }?.mediaSourceId ?: VersionChoices[current.id],
            )
        }.onSuccess { p ->
            // The plan this replaces (a track change): stopped now that the new one exists, so the
            // server doesn't end the old stream while it's still playing.
            _plan.value?.takeIf { !quiet && (it.playSessionId != p.playSessionId || it.itemId != p.itemId) }?.let { old ->
                server.reportStopped(old, exo.currentPosition)
            }
            log("Server: ${p.method}" + (if (p.transcodeReasons.isNotEmpty()) " because ${p.transcodeReasons.joinToString(", ")}" else "") + "; ${dev.nitrate.core.playback.PlaybackReport.streamSummary(p)}")
            play(p, startMs, current.name)
            if (!quiet) {
                server.reportStart(p, startMs)
                startProgressReports(p)
            }
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            log("Couldn't start: ${it.message ?: it::class.simpleName}")
            _error.value = it.message ?: "Could not start playback"
        }
    }

    /** Periodic progress so the server's "Continue Watching" stays accurate. */
    private fun startProgressReports(p: PlaybackPlan) {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                // Track changes made on the device keep the session (and update the plan).
                val now = _plan.value ?: break
                if (now.playSessionId != p.playSessionId || now.itemId != p.itemId) break
                server.reportProgress(now, exo.currentPosition, isPaused = !exo.isPlaying)
            }
        }
    }

    /** Picks a new audio or subtitle stream: in the exo when it has the track, else via the server. */
    fun selectTrack(kind: StreamKind, index: Int) {
        val p = _plan.value ?: return
        val stream = p.source.streams.firstOrNull { it.index == index }
        val clientSide = when {
            kind == StreamKind.SUBTITLE && index == -1 -> true
            kind == StreamKind.SUBTITLE && stream?.delivery == SubtitleDelivery.EXTERNAL -> true
            // Direct play has every embedded track; HLS only carries what the server chose.
            p.method == PlayMethod.DIRECT_PLAY -> stream?.delivery != SubtitleDelivery.ENCODE
            else -> false
        }
        val updated = if (kind == StreamKind.AUDIO) p.copy(audioIndex = index) else p.copy(subtitleIndex = index)
        rememberLanguages(kind, index)
        // Each subtitle keeps its own timing.
        if (kind == StreamKind.SUBTITLE) _subtitleOffsetMs.value = subtitleOffsets[index] ?: 0
        // A downloaded file has only what's in it.
        if (clientSide || localProgress != null) {
            _plan.value = updated
            applySelection(updated)
            updateAss(updated)
            // Tell the server straight away (same session): the dashboard shows it, and with
            // Remember selections on, the server keeps it for this title.
            if (!quiet && localProgress == null) scope.launch { server.reportProgress(updated, exo.currentPosition, isPaused = !exo.isPlaying) }
        } else {
            // The current stream plays on until the new one is ready (start() then stops it on the server).
            val at = exo.currentPosition
            // Converting stays on only when Nitrate forced it; otherwise the server decides afresh
            // (a new track, like text subtitles instead of burned-in ones, may play as it is).
            restart(at, forceTranscode = forcedTranscode, audioIndex = updated.audioIndex, subtitleIndex = updated.subtitleIndex)
        }
    }

    /** Remembers, for the show (or this title), only the kind of track the user just picked. */
    private fun rememberLanguages(kind: StreamKind, index: Int) {
        val series = _item.value?.let(dev.nitrate.core.playback.TrackSelection::memoryKey) ?: return
        val memory = trackMemory ?: return
        val streams = _plan.value?.source?.streams ?: return
        appScope.launch { memory.set(series, TrackMemory.remember(kind, streams, index, memory.get(series))) }
    }

    // ---- Speed, night mode, subtitle timing ----

    fun setSpeed(speed: Float) {
        if (inGroup) {
            showNotice("Speed stays at normal while watching together")
            return
        }
        _speed.value = speed
        exo.setPlaybackSpeed(speed)
    }

    /**
     * Moves the sound by [ms] against the picture (see [dev.nitrate.core.playback.AudioSync]).
     * Applied at once by re-seeking where it is (the player won't step its clock back otherwise).
     */
    fun setAudioDelay(ms: Int, resync: Boolean = true) {
        val clamped = dev.nitrate.core.playback.AudioSync.clamp(ms)
        _audioDelayMs.value = clamped
        audioDelayUs = clamped * 1000L
        if (resync && exo.playbackState != Player.STATE_IDLE) exo.seekTo(exo.currentPosition)
    }

    private fun encodingName(encoding: Int): String = when (encoding) {
        C.ENCODING_PCM_16BIT -> "PCM 16-bit"
        C.ENCODING_PCM_24BIT -> "PCM 24-bit"
        C.ENCODING_PCM_32BIT -> "PCM 32-bit"
        C.ENCODING_PCM_FLOAT -> "PCM float"
        C.ENCODING_PCM_8BIT -> "PCM 8-bit"
        C.ENCODING_AC3 -> "Dolby Digital"
        C.ENCODING_E_AC3 -> "Dolby Digital Plus"
        C.ENCODING_E_AC3_JOC -> "Dolby Digital Plus (Atmos)"
        C.ENCODING_AC4 -> "Dolby AC-4"
        C.ENCODING_DOLBY_TRUEHD -> "Dolby TrueHD"
        C.ENCODING_DTS -> "DTS"
        C.ENCODING_DTS_HD -> "DTS-HD"
        C.ENCODING_DTS_UHD_P2 -> "DTS:X"
        else -> "Encoding $encoding"
    }

    fun setNightMode(on: Boolean) {
        val wasActive = nightMode.active
        _nightModeOn.value = on
        nightMode.enabled = on
        redecideAudio(wasActive, "Night mode", on)
        updateProcessing()
    }

    fun setDialogueBoost(on: Boolean) {
        val wasActive = nightMode.active
        _dialogueBoostOn.value = on
        nightMode.dialogueBoost = on
        redecideAudio(wasActive, "Dialogue boost", on)
        updateProcessing()
    }

    private fun redecideAudio(wasActive: Boolean, what: String, on: Boolean) {
        val changed = wasActive != nightMode.active
        // Sound passed through to a receiver can't be evened out: switching the audio off and on
        // makes the player choose again, decoding it here while night mode is on (and passing it
        // through again once it's off).
        if (changed && exo.playbackState != Player.STATE_IDLE && exo.audioFormat?.sampleMimeType?.let { it != MimeTypes.AUDIO_RAW && NightModeDecoding.decodeInstead(exo.audioFormat!!) } == true) {
            log("$what ${if (on) "on: decoding the sound here" else "off: passing the sound through again"}")
            val params = exo.trackSelectionParameters
            exo.trackSelectionParameters = params.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            exo.trackSelectionParameters = params
        }
    }

    /**
     * Moves the shown subtitles by [offsetMs]. The subtitle is fetched as a file, retimed and
     * side-loaded in its place (text subtitles only), then playback continues where it was.
     */
    fun setSubtitleOffset(offsetMs: Long) {
        val p = _plan.value ?: return
        val index = p.subtitleIndex.takeIf { it >= 0 } ?: run { showNotice("Turn on a subtitle first"); return }
        val stream = p.source.streams.firstOrNull { it.index == index } ?: return
        // libass draws it: its layer just shows each moment that much later or earlier.
        if (drawsWithLibass(p)) {
            subtitleOffsets[index] = offsetMs
            _subtitleOffsetMs.value = offsetMs
            return
        }
        val codec = stream.codec?.lowercase()
        if (codec !in setOf("srt", "subrip", "vtt", "webvtt")) {
            showNotice("Timing can be changed for SRT, WebVTT and (drawn by libass) ASS subtitles")
            return
        }
        val itemId = _item.value?.id ?: return
        // Shown at once and taken from here by the next press; only the latest retime runs.
        val previous = subtitleOffsets[index] ?: 0
        subtitleOffsets[index] = offsetMs
        _subtitleOffsetMs.value = offsetMs
        retimeJob?.cancel()
        retimeJob = scope.launch {
            runCatching {
                val original = subtitleOriginals[index] ?: withContext(Dispatchers.IO) {
                    val local = p.externalSubtitles.firstOrNull { it.streamIndex == index }?.url?.takeIf { it.startsWith("/") }
                    if (local != null) File(local).readText() else fetchText(server.subtitleFileUrl(itemId, p.source, index, "srt"))
                }.also { subtitleOriginals[index] = it }
                val file = File(appContext.cacheDir, "subtitle-$index-shifted.srt")
                withContext(Dispatchers.IO) { file.writeText(SubtitleShift.shift(original, offsetMs)) }
                val shifted = ExternalSubtitle(index, file.absolutePath, "application/x-subrip", stream.language, stream.title ?: stream.language ?: "Subtitle")
                val updated = p.copy(
                    externalSubtitles = p.externalSubtitles.filterNot { it.streamIndex == index } + shifted,
                    // Delivered from the side-loaded file now, but still in the container: embedded
                    // streams keep their order (it stays non-external for that mapping).
                    source = p.source.copy(streams = p.source.streams.map { if (it.index == index) it.copy(delivery = SubtitleDelivery.EXTERNAL) else it }),
                )
                play(updated, exo.currentPosition, _item.value?.name.orEmpty(), keepPaused = !exo.playWhenReady)
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                subtitleOffsets[index] = previous
                if (_plan.value?.subtitleIndex == index) _subtitleOffsetMs.value = previous
                showNotice("Couldn't retime subtitles: ${it.message}")
            }
        }
    }

    private fun fetchBytes(url: String): ByteArray {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        connection.setRequestProperty("Authorization", server.authorizationHeader())
        return connection.inputStream.use { it.readBytes() }
    }

    // ---- Styled subtitles with libass ----

    private val _assTrack = MutableStateFlow<AssTrack?>(null)

    /** The styled subtitle libass draws over the picture (in each app's player view), or null. */
    val assTrack: StateFlow<AssTrack?> = _assTrack
    @Volatile private var threeDMode = dev.nitrate.core.playback.ThreeDMode.TWO_D
    private var threeDApplied = false

    /**
     * Side-by-side or top-and-bottom 3D shown in 2D: one eye's view, stretched back to its shape,
     * through Media3's video effects. Only 3D films go through them (they're SDR, and effects
     * would get in the way of HDR and tunneling); the effects are taken off again afterwards.
     */
    private fun applyThreeD() {
        val view = dev.nitrate.core.playback.ThreeD.view(_item.value?.video3D, threeDMode)
        if (view == null) {
            if (threeDApplied) { exo.setVideoEffects(emptyList()); threeDApplied = false }
            return
        }
        runCatching {
            exo.setVideoEffects(
                listOf(
                    androidx.media3.effect.Crop(view.left, view.right, view.bottom, view.top),
                    androidx.media3.effect.ScaleAndRotateTransformation.Builder().setScale(view.scaleX, view.scaleY).build(),
                ),
            )
            threeDApplied = true
            log("3D (${_item.value?.video3D?.label}) shown in 2D: one eye's view")
        }.onFailure { log("Couldn't show 3D in 2D: ${it.message}") }
    }

    /** Read when each file is opened: how dual-layer Dolby Vision (profile 7) plays. */
    @Volatile private var profile7 = dev.nitrate.core.playback.Profile7Playback.BASE_LAYER

    @Volatile private var styledMode = dev.nitrate.core.playback.StyledSubtitles.FILE
    private var assJob: Job? = null

    /** Tracks libass couldn't load: Media3 shows those its simpler way. */
    private val assFailed = mutableSetOf<String>()

    private fun assKey(p: PlaybackPlan) = "${p.itemId}/${p.mediaSourceId}/${p.subtitleIndex}"

    /** Whether libass draws [p]'s subtitle: ASS/SSA, shown as the file styles it, not burned in, and libass is built in. */
    private fun drawsWithLibass(p: PlaybackPlan): Boolean {
        if (!AssSubtitleView.isAvailable || styledMode != dev.nitrate.core.playback.StyledSubtitles.FILE) return false
        val stream = p.source.streams.firstOrNull { it.kind == StreamKind.SUBTITLE && it.index == p.subtitleIndex } ?: return false
        return dev.nitrate.core.playback.isStyledSubtitle(stream.codec) && stream.delivery != SubtitleDelivery.ENCODE && assKey(p) !in assFailed
    }

    /** Loads (or drops) the libass track for [p]: the whole script and its fonts, from the download or the server. */
    private fun updateAss(p: PlaybackPlan) {
        if (!drawsWithLibass(p)) {
            assJob?.cancel()
            _assTrack.value = null
            return
        }
        val key = assKey(p)
        if (_assTrack.value?.key == key) return
        assJob?.cancel()
        val itemId = p.itemId
        val index = p.subtitleIndex
        assJob = scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val local = p.assScripts[index]
                        ?: p.externalSubtitles.firstOrNull { it.streamIndex == index && it.url.startsWith("/") && it.mimeType == MimeTypes.TEXT_SSA }?.url
                    val script = local?.let { File(it).readBytes() } ?: fetchBytes(server.subtitleFileUrl(itemId, p.source, index, "ass"))
                    val fonts = if (p.fontFiles.isNotEmpty()) {
                        p.fontFiles.mapNotNull { path -> runCatching { File(path).name to File(path).readBytes() }.getOrNull() }
                    } else if (localProgress == null) {
                        p.source.attachments.filter { it.isFont }.mapNotNull { a ->
                            runCatching { (a.fileName ?: "font-${a.index}") to fetchBytes(server.attachmentUrl(itemId, p.source, a.index)) }.getOrNull()
                        }
                    } else emptyList()
                    AssTrack(key, script, fonts)
                }
            }.onSuccess { track ->
                log("Styled subtitles drawn by libass (${track.script.size / 1024} KB, ${track.fonts.size} fonts)")
                _assTrack.value = track
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                log("libass couldn't load the subtitle (${e.message}): shown the simpler way")
                assFailed += key
                _assTrack.value = null
                _plan.value?.let(::applySelection)
            }
        }
    }

    private fun fetchText(url: String): String {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        connection.setRequestProperty("Authorization", server.authorizationHeader())
        return connection.inputStream.use { it.readBytes().decodeToString() }
    }

    /** After downloading a subtitle on the server: picks it up and switches to it. */
    fun reloadWithNewSubtitle(language: String?) {
        val p = _plan.value ?: return
        val before = p.source.streams.filter { it.kind == StreamKind.SUBTITLE }.map { it.index }.toSet()
        val at = exo.currentPosition
        startJob?.cancel()
        startJob = scope.launch {
            val source = runCatching { server.mediaSource(p.itemId) }.getOrNull()
            val added = source?.streams?.filter { it.kind == StreamKind.SUBTITLE && it.index !in before }
                ?.let { list -> list.firstOrNull { it.language.equals(language, ignoreCase = true) } ?: list.firstOrNull() }
            // A new playback session (the server's new subtitle list); start() stops the old one.
            start(at, forceTranscode = forcedTranscode, audioIndex = p.audioIndex, subtitleIndex = added?.index ?: p.subtitleIndex)
        }
    }

    /** Carries out a playback command sent from another app (pause, seek, next...). */
    fun handleRemote(command: String, seekMs: Long?) {
        when (command) {
            "Pause" -> player.pause()
            "Unpause" -> player.play()
            "PlayPause" -> controls.togglePlay()
            "Seek" -> seekMs?.let(::seekTo)
            "Rewind" -> controls.seekBy(-10_000)
            "FastForward" -> controls.seekBy(30_000)
            "Stop" -> onEnded()
            "NextTrack" -> player.seekTo(exo.duration.coerceAtLeast(0))
            "PreviousTrack" -> seekTo(0)
        }
    }

    private fun showNotice(message: String) {
        _notice.value = message
        scope.launch {
            delay(NOTICE_MS)
            if (_notice.value == message) _notice.value = null
        }
    }

    /**
     * Points the exo at the plan's audio and subtitle streams. Embedded tracks line up with the
     * server's streams by order within their type; side-loaded subtitles carry a "jf-<index>" id.
     */
    private fun applySelection(plan: PlaybackPlan) {
        val streams = plan.source.streams
        val groups = exo.currentTracks.groups
        val params = exo.trackSelectionParameters.buildUpon()

        // HLS carries only the audio the server picked, so there is nothing to choose client-side.
        if (plan.method == PlayMethod.DIRECT_PLAY) {
            val embeddedAudio = streams.filter { it.kind == StreamKind.AUDIO && !it.isExternal }.map { it.index }
            val audioGroups = groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            audioGroups.getOrNull(embeddedAudio.indexOf(plan.audioIndex))?.let {
                params.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, 0))
            }
        }

        val textGroups = groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val (sideLoaded, embeddedText) = textGroups.partition { g -> g.getTrackFormat(0).id?.contains("jf-") == true }
        val subtitle = streams.firstOrNull { it.index == plan.subtitleIndex && it.kind == StreamKind.SUBTITLE }
        // Drawn by libass in its own layer: Media3 shows no text for it.
        val target = when {
            drawsWithLibass(plan) -> null
            subtitle == null -> null
            subtitle.delivery == SubtitleDelivery.EXTERNAL ->
                sideLoaded.firstOrNull { it.getTrackFormat(0).id?.endsWith("jf-${subtitle.index}") == true }
            // Burned in by the server: nothing to show client-side.
            subtitle.delivery == SubtitleDelivery.ENCODE -> null
            plan.method == PlayMethod.DIRECT_PLAY -> {
                val embedded = streams.filter { it.kind == StreamKind.SUBTITLE && !it.isExternal }.map { it.index }
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
        exo.trackSelectionParameters = params.build()
    }

    /** Reports the stop to the server and frees the exo. Call once, when leaving playback. */
    fun release() {
        val local = localProgress
        if (local != null) {
            val position = exo.currentPosition
            val duration = exo.duration.coerceAtLeast(0)
            // Outlives this session, so leaving the exo still saves where it stopped.
            appScope.launch { local(position, duration, false) }
        } else if (!quiet) {
            _plan.value?.let { server.reportStopped(it, exo.currentPosition) }
        }
        group?.detach(syncPlayer)
        group = null
        exo.removeListener(listener)
        exo.release()
        scope.cancel()
    }

    private companion object {
        /** Second subtitle language per show (or film), for this app run. */
        private val secondMemory = java.util.concurrent.ConcurrentHashMap<String, Pair<String?, String?>>()

        const val PROGRESS_INTERVAL_MS = 10_000L
        const val NOTICE_MS = 8_000L

        /** Events kept for a playback report. */
        const val MAX_EVENTS = 300

        /** Error codes that mean "this device couldn't decode it", as opposed to network or server errors. */
        val DECODER_ERRORS = setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        )
    }
}

/** Which encoded audio night mode decodes on the device instead of passing it through. */
internal object NightModeDecoding {
    private val known = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** An encoded (not PCM) format this device can decode itself, with its own decoder or FFmpeg. */
    fun decodeInstead(format: androidx.media3.common.Format): Boolean {
        val mime = format.sampleMimeType ?: return false
        if (mime == MimeTypes.AUDIO_RAW || !MimeTypes.isAudio(mime)) return false
        return known.getOrPut(mime) {
            val device = runCatching { androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(mime, false, false).isNotEmpty() }.getOrDefault(false)
            val ffmpeg = runCatching { androidx.media3.decoder.ffmpeg.FfmpegLibrary.isAvailable() && androidx.media3.decoder.ffmpeg.FfmpegLibrary.supportsFormat(mime) }.getOrDefault(false)
            device || ffmpeg
        }
    }
}
