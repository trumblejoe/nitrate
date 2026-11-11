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
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.MediaKind
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
) {
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

    private var selectionApplied = false
    private var progressJob: Job? = null

    /** Set for a downloaded file: where progress goes instead of the server. */
    private var localProgress: (suspend (positionMs: Long, durationMs: Long, ended: Boolean) -> Unit)? = null

    val player: ExoPlayer = run {
        // The auth header covers direct play, HLS playlists and segments, and subtitle files.
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Authorization" to server.authorizationHeader()))
        ExoPlayer.Builder(context)
            .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(true))
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

    /** Loads [itemId] and starts playing, resuming unless [fromStart]. */
    fun load(itemId: String, fromStart: Boolean) {
        scope.launch {
            runCatching { server.getItem(itemId) }
                .onSuccess {
                    _item.value = it
                    start(if (fromStart) 0L else it.user.positionMs, forceTranscode = false)
                }
                .onFailure { _error.value = it.message ?: "Could not load item" }
        }
    }

    /** Plays a downloaded file from [plan] (local paths), reporting progress to [onProgress]. */
    fun loadLocal(item: MediaItem, plan: PlaybackPlan, startMs: Long, onProgress: suspend (positionMs: Long, durationMs: Long, ended: Boolean) -> Unit) {
        localProgress = onProgress
        _item.value = item
        play(plan, startMs, item.name)
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                localProgress?.invoke(player.currentPosition, player.duration.coerceAtLeast(0), false)
            }
        }
    }

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

    private suspend fun start(startMs: Long, forceTranscode: Boolean, audioIndex: Int? = null, subtitleIndex: Int? = null) {
        val current = _item.value ?: return
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
