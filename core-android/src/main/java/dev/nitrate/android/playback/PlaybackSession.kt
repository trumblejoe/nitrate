package dev.nitrate.android.playback

import android.content.Context
import android.net.Uri
import java.io.File
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import dev.nitrate.core.model.ExternalSubtitle
import dev.nitrate.core.playback.SubtitleShift
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
 * streams onto the player's tracks, reports progress, and when direct play fails with a decoder
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
) {
    private val appContext = context.applicationContext
    private val nightMode = NightModeAudioProcessor()

    private val _speed = MutableStateFlow(1f)

    /** Playback speed, 0.5x to 2x. */
    val speed: StateFlow<Float> = _speed
    private val _subtitleOffsetMs = MutableStateFlow(0L)

    /** How far subtitles are moved: positive shows them later. */
    val subtitleOffsetMs: StateFlow<Long> = _subtitleOffsetMs
    private val _nightModeOn = MutableStateFlow(false)
    val nightModeOn: StateFlow<Boolean> = _nightModeOn

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
    private val _videoDecoder = MutableStateFlow<String?>(null)
    val videoDecoder: StateFlow<String?> = _videoDecoder
    private val _audioDecoder = MutableStateFlow<String?>(null)
    val audioDecoder: StateFlow<String?> = _audioDecoder

    private val _segments = MutableStateFlow<List<MediaSegment>>(emptyList())

    /** Intro, credits... from the server, for skip buttons and Up Next timing. */
    val segments: StateFlow<List<MediaSegment>> = _segments
    private val _trickplay = MutableStateFlow<TrickplayInfo?>(null)

    /** Scrubbing thumbnails, when the server has them. */
    val trickplay: StateFlow<TrickplayInfo?> = _trickplay

    /** Position and duration (ms), updated twice a second while playing. */
    data class Progress(val positionMs: Long, val durationMs: Long)

    private val _progress = MutableStateFlow(Progress(0, 0))
    val progress: StateFlow<Progress> = _progress

    private var selectionApplied = false
    private var progressJob: Job? = null

    /** Set for a downloaded file: where progress goes instead of the server. */
    private var localProgress: (suspend (positionMs: Long, durationMs: Long, ended: Boolean) -> Unit)? = null

    val player: ExoPlayer = run {
        // The auth header covers direct play, HLS playlists and segments, and subtitle files.
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Authorization" to server.authorizationHeader()))
        // Night mode sits in the audio path; it's off (passes audio through) until switched on.
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean) =
                DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(nightMode)).setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams).build()
        }.setEnableDecoderFallback(true)
        ExoPlayer.Builder(context)
            .setRenderersFactory(renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(http))
            .build()
    }

    val controls = object : PlaybackControls {
        override val isPlaying get() = player.isPlaying
        override fun togglePlay() = if (player.isPlaying) player.pause() else player.play()
        override fun seekBy(deltaMs: Long) = player.seekTo((player.currentPosition + deltaMs).coerceAtLeast(0))
    }

    private val listener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            val p = _plan.value ?: return
            if (!selectionApplied && !tracks.isEmpty) {
                selectionApplied = true
                applySelection(p)
            }
        }

        override fun onPlayerError(e: PlaybackException) {
            val failed = _plan.value
            if (failed == null || failed.method == PlayMethod.TRANSCODE || localProgress != null) {
                _error.value = "Playback failed: ${e.errorCodeName}"
                return
            }
            val at = player.currentPosition
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
            server.reportStopped(failed, at)
            _plan.value = null
            scope.launch { start(at, forceTranscode = true, audioIndex = failed.audioIndex, subtitleIndex = failed.subtitleIndex) }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state != Player.STATE_ENDED) return
            localProgress?.let { record ->
                val duration = player.duration.coerceAtLeast(0)
                localProgress = null // Recorded as watched here, not again on release.
                appScope.launch { record(duration, duration, true) }
            }
            onEnded()
        }
    }

    init {
        scope.launch { setNightMode(settings.current().nightMode) }
        scope.launch {
            while (isActive) {
                _progress.value = Progress(player.currentPosition, player.duration.coerceAtLeast(0))
                delay(500)
            }
        }
        player.addListener(listener)
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(e: AnalyticsListener.EventTime, name: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                _videoDecoder.value = name
            }

            override fun onAudioDecoderInitialized(e: AnalyticsListener.EventTime, name: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                _audioDecoder.value = name
            }
        })
    }

    /** Loads [itemId] and starts playing: at [startMs] if given, else resuming unless [fromStart]. */
    fun load(itemId: String, fromStart: Boolean, startMs: Long? = null) {
        scope.launch {
            runCatching { server.getItem(itemId) }
                .onSuccess {
                    _item.value = it
                    start(startMs ?: if (fromStart) 0L else it.user.positionMs, forceTranscode = false, useMemory = true)
                    loadExtras(it.id)
                }
                .onFailure { _error.value = it.message ?: "Could not load item" }
        }
    }

    /** Plays a downloaded file from [plan] (local paths), reporting progress to [onProgress]. */
    fun loadLocal(item: MediaItem, plan: PlaybackPlan, startMs: Long, onProgress: suspend (positionMs: Long, durationMs: Long, ended: Boolean) -> Unit) {
        localProgress = onProgress
        _item.value = item
        play(plan, startMs, item.name)
        // Skip markers still work offline if the server answers; thumbnails need it anyway.
        loadExtras(item.id, thumbnails = false)
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                localProgress?.invoke(player.currentPosition, player.duration.coerceAtLeast(0), false)
            }
        }
    }

    private fun loadExtras(itemId: String, thumbnails: Boolean = true) {
        scope.launch { _segments.value = runCatching { server.mediaSegments(itemId) }.getOrDefault(emptyList()) }
        if (thumbnails) scope.launch { _trickplay.value = runCatching { server.trickplay(itemId, _plan.value?.mediaSourceId) }.getOrNull() }
    }

    fun seekTo(positionMs: Long) = player.seekTo(positionMs.coerceAtLeast(0))

    private fun uriOf(url: String): Uri = if (url.startsWith("/")) Uri.fromFile(File(url)) else Uri.parse(url)

    private fun play(p: PlaybackPlan, startMs: Long, title: String) {
        _plan.value = p
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
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()
        // Jellyfin's HLS playlists span the whole file, so every method seeks client-side.
        player.setMediaItem(media, startMs)
        player.prepare()
        player.playWhenReady = true
    }

    private suspend fun start(startMs: Long, forceTranscode: Boolean, audioIndex: Int? = null, subtitleIndex: Int? = null, useMemory: Boolean = false) {
        val current = _item.value ?: return
        // A show's remembered languages, matched to this episode's streams.
        var audio = audioIndex
        var subtitle = subtitleIndex
        val series = current.seriesId
        if (useMemory && trackMemory != null && series != null && audio == null && subtitle == null) {
            trackMemory.get(series)?.let { choice ->
                runCatching { server.mediaSource(current.id) }.getOrNull()?.let { src ->
                    val (a, sub) = TrackMemory.pick(src.streams, choice)
                    audio = a
                    subtitle = sub
                }
            }
        }
        val audioIndex = audio
        val subtitleIndex = subtitle
        runCatching {
            server.resolvePlayback(
                itemId = current.id,
                isAudio = current.mediaKind == MediaKind.AUDIO,
                startMs = startMs,
                forceTranscode = forceTranscode,
                audioIndex = audioIndex,
                subtitleIndex = subtitleIndex,
                mediaSourceId = _plan.value?.mediaSourceId,
            )
        }.onSuccess { p ->
            play(p, startMs, current.name)
            server.reportStart(p, startMs)
            startProgressReports(p)
        }.onFailure { _error.value = it.message ?: "Could not start playback" }
    }

    /** Periodic progress so the server's "Continue Watching" stays accurate. */
    private fun startProgressReports(p: PlaybackPlan) {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                if (_plan.value != p) break
                server.reportProgress(p, player.currentPosition, isPaused = !player.isPlaying)
            }
        }
    }

    /** Picks a new audio or subtitle stream: in the player when it has the track, else via the server. */
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
        rememberLanguages(updated)
        // A downloaded file has only what's in it.
        if (clientSide || localProgress != null) {
            _plan.value = updated
            applySelection(updated)
        } else {
            val at = player.currentPosition
            server.reportStopped(p, at)
            scope.launch { start(at, forceTranscode = p.method == PlayMethod.TRANSCODE, audioIndex = updated.audioIndex, subtitleIndex = updated.subtitleIndex) }
        }
    }

    private fun rememberLanguages(p: PlaybackPlan) {
        val series = _item.value?.seriesId ?: return
        val memory = trackMemory ?: return
        appScope.launch { memory.set(series, TrackMemory.remember(p.source.streams, p.audioIndex, p.subtitleIndex, memory.get(series))) }
    }

    // ---- Speed, night mode, subtitle timing ----

    fun setSpeed(speed: Float) {
        _speed.value = speed
        player.setPlaybackSpeed(speed)
    }

    fun setNightMode(on: Boolean) {
        _nightModeOn.value = on
        nightMode.enabled = on
    }

    /**
     * Moves the shown subtitles by [offsetMs]. The subtitle is fetched as a file, retimed and
     * side-loaded in its place (text subtitles only), then playback continues where it was.
     */
    fun setSubtitleOffset(offsetMs: Long) {
        val p = _plan.value ?: return
        val index = p.subtitleIndex.takeIf { it >= 0 } ?: run { showNotice("Turn on a subtitle first"); return }
        val stream = p.source.streams.firstOrNull { it.index == index } ?: return
        val codec = stream.codec?.lowercase()
        if (codec !in setOf("srt", "subrip", "vtt", "webvtt")) {
            showNotice("Timing can be changed for SRT and WebVTT subtitles only")
            return
        }
        val itemId = _item.value?.id ?: return
        scope.launch {
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
                    source = p.source.copy(streams = p.source.streams.map { if (it.index == index) it.copy(isExternal = true, delivery = SubtitleDelivery.EXTERNAL) else it }),
                )
                _subtitleOffsetMs.value = offsetMs
                play(updated, player.currentPosition, _item.value?.name.orEmpty())
            }.onFailure { showNotice("Couldn't retime subtitles: ${it.message}") }
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
        val at = player.currentPosition
        scope.launch {
            val source = runCatching { server.mediaSource(p.itemId) }.getOrNull()
            val added = source?.streams?.filter { it.kind == StreamKind.SUBTITLE && it.index !in before }
                ?.let { list -> list.firstOrNull { it.language.equals(language, ignoreCase = true) } ?: list.firstOrNull() }
            server.reportStopped(p, at)
            start(at, forceTranscode = p.method == PlayMethod.TRANSCODE, audioIndex = p.audioIndex, subtitleIndex = added?.index ?: p.subtitleIndex)
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
            "NextTrack" -> player.seekTo(player.duration.coerceAtLeast(0))
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
     * Points the player at the plan's audio and subtitle streams. Embedded tracks line up with the
     * server's streams by order within their type; side-loaded subtitles carry a "jf-<index>" id.
     */
    private fun applySelection(plan: PlaybackPlan) {
        val streams = plan.source.streams
        val groups = player.currentTracks.groups
        val params = player.trackSelectionParameters.buildUpon()

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
        val target = when {
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
        player.trackSelectionParameters = params.build()
    }

    /** Reports the stop to the server and frees the player. Call once, when leaving playback. */
    fun release() {
        val local = localProgress
        if (local != null) {
            val position = player.currentPosition
            val duration = player.duration.coerceAtLeast(0)
            // Outlives this session, so leaving the player still saves where it stopped.
            appScope.launch { local(position, duration, false) }
        } else {
            _plan.value?.let { server.reportStopped(it, player.currentPosition) }
        }
        player.removeListener(listener)
        player.release()
        scope.cancel()
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 10_000L
        const val NOTICE_MS = 8_000L

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
