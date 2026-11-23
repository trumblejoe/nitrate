package dev.nitrate.mobile.ui

import dev.nitrate.android.AppContainer
import dev.nitrate.android.remote.RemotePlayer
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.MediaSegment
import dev.nitrate.core.model.PlayMethod
import dev.nitrate.core.model.PlaybackPlan
import dev.nitrate.core.model.StreamKind
import dev.nitrate.core.model.TrickplayInfo
import dev.nitrate.core.playback.CreditScenes
import dev.nitrate.core.playback.CreditScenesInfo
import dev.nitrate.core.playback.MpvTracks
import dev.nitrate.core.playback.TrackSetup
import dev.nitrate.core.playback.trackName
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * One title playing in mpv on the desktop, with what the player screen needs: where it is, the
 * server's decision (the file as it is, almost always: the desktop profile takes everything), the
 * tracks (the show's remembered pick or the server's; switched inside mpv without restarting when
 * the file plays as it is), skippable segments, scenes in the credits and scrub thumbnails.
 * Start, progress (with the track indices) and stop are reported unless it's a recap clip; a
 * download's watch state is kept on the device instead. If mpv can't play the file as it is, it
 * asks the server once to convert it from the same spot.
 */
class DesktopPlayerSession(
    private val container: AppContainer,
    private val reported: Boolean,
    val player: MpvPlayer,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) : RemotePlayer {
    private val server = container.server

    private val _item = MutableStateFlow<MediaItem?>(null)
    val item: StateFlow<MediaItem?> = _item
    private val _plan = MutableStateFlow<PlaybackPlan?>(null)
    val plan: StateFlow<PlaybackPlan?> = _plan
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs
    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs
    private val _segments = MutableStateFlow<List<MediaSegment>>(emptyList())
    val segments: StateFlow<List<MediaSegment>> = _segments
    private val _creditScenes = MutableStateFlow<CreditScenesInfo?>(null)
    val creditScenes: StateFlow<CreditScenesInfo?> = _creditScenes
    private val _ended = MutableStateFlow(false)
    val ended: StateFlow<Boolean> = _ended
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused
    private val _buffering = MutableStateFlow(false)
    val buffering: StateFlow<Boolean> = _buffering
    private val _trickplay = MutableStateFlow<TrickplayInfo?>(null)
    val trickplay: StateFlow<TrickplayInfo?> = _trickplay

    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed
    private val _volume = MutableStateFlow(100)
    val volume: StateFlow<Int> = _volume
    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted
    private val _audioDelayMs = MutableStateFlow(0)
    val audioDelayMs: StateFlow<Int> = _audioDelayMs
    private val _subtitleDelayMs = MutableStateFlow(0)
    val subtitleDelayMs: StateFlow<Int> = _subtitleDelayMs

    /** The second subtitle shown at the top (dual subtitles): its stream index, name and lines. */
    data class Second(val index: Int, val label: String, val cues: List<dev.nitrate.core.playback.Cue>)

    private val _second = MutableStateFlow<Second?>(null)
    val secondSubtitle: StateFlow<Second?> = _second
    private var secondJob: Job? = null

    /** Called when the viewer did something (paused, played, jumped): for "Still watching?". */
    var onInput: (() -> Unit)? = null

    private var tracks: TrackSetup? = null
    private var progressJob: Job? = null
    private var convertedAfterFailure = false

    /** The download being played (null: streaming). Its watch state is kept on the device, never reported. */
    private var download: dev.nitrate.core.downloads.DownloadRecord? = null

    /** Plays [itemId] from [startMs] (null: its resume point, or the start with [fromStart]). */
    fun load(itemId: String, fromStart: Boolean, startMs: Long?) {
        scope.launch {
            runCatching {
                val item = server.getItem(itemId)
                _item.value = item
                val start = startMs ?: if (fromStart) 0L else item.user.positionMs
                tracks = runCatching { container.trackSetup(item) }.getOrNull()
                play(itemId, start, tracks?.audioIndex, tracks?.subtitleIndex)
                launch { _trickplay.value = runCatching { server.trickplay(itemId, _plan.value?.mediaSourceId) }.getOrNull() }
                launch {
                    _segments.value = runCatching { server.mediaSegments(itemId) }.getOrDefault(emptyList())
                    updateCredits()
                }
            }.onFailure { _error.value = "Couldn't start: ${it.message}" }
        }
    }

    /** Plays a download from this PC (mpv plays any container). Where it stops is saved on the device. */
    fun loadLocal(itemId: String, fromStart: Boolean, startMs: Long?) {
        scope.launch {
            runCatching {
                val downloads = container.downloads
                var record = downloads.find(itemId) ?: error("This download isn't on the PC any more.")
                downloads.makeSeekable(record)
                record = downloads.withServerDefaults(downloads.find(itemId) ?: record)
                download = record
                _item.value = record.item
                val p = downloads.playbackPlan(record) ?: error("This download's file is missing.")
                val start = startMs ?: if (fromStart) 0L else downloads.resumePositionMs(record)
                player.load(media(p, start, headers = emptyMap()))
                _plan.value = p
                _ended.value = false
                // Skip Intro and the credits from the server when it answers (offline: none).
                _segments.value = kotlinx.coroutines.withTimeoutOrNull(3_000) { runCatching { server.mediaSegments(itemId) }.getOrNull() }.orEmpty()
                updateCredits()
                followProgress()
            }.onFailure { _error.value = "Couldn't play the download: ${it.message}" }
        }
    }

    private fun media(p: PlaybackPlan, startMs: Long, headers: Map<String, String>) = MpvMedia(
        url = p.url,
        headers = headers,
        startSeconds = startMs / 1000.0,
        audioId = MpvTracks.audioId(p, p.audioIndex),
        subtitleId = MpvTracks.subtitleId(p, p.subtitleIndex),
        subtitleUrls = p.externalSubtitles.map { it.url },
        fontsDir = p.fontFiles.firstOrNull()?.let { path -> path.substring(0, maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\')).coerceAtLeast(0)) }?.takeIf { it.isNotEmpty() },
    )

    private fun authHeaders(): Map<String, String> = server.authorizationHeader().takeIf { it.isNotEmpty() }?.let { mapOf("Authorization" to it) }.orEmpty()

    private suspend fun play(itemId: String, startMs: Long, audioIndex: Int?, subtitleIndex: Int?, forceTranscode: Boolean = false) {
        val previous = _plan.value
        val version = dev.nitrate.android.playback.VersionChoices[itemId]
        val p = server.resolvePlayback(
            itemId, isAudio = false, startMs = startMs, forceTranscode = forceTranscode,
            audioIndex = audioIndex, subtitleIndex = subtitleIndex, mediaSourceId = version,
        )
        player.load(media(p, startMs, authHeaders()))
        _plan.value = p
        _ended.value = false
        _error.value = null
        if (reported) {
            // A track change or a conversion restarts the stream: the old play session ends where it was.
            previous?.let { server.reportStopped(it, startMs) }
            runCatching { server.reportStart(p, startMs) }
        }
        followProgress()
    }

    private fun followProgress() {
        progressJob?.cancel()
        progressJob = scope.launch {
            var lastReport = dev.nitrate.android.nowMs()
            var wasPaused = player.state.value.paused
            while (true) {
                delay(250)
                val s = player.state.value
                val before = _positionMs.value
                _positionMs.value = (s.positionSeconds * 1000).toLong()
                if (s.durationSeconds > 0) _durationMs.value = (s.durationSeconds * 1000).toLong()
                _paused.value = s.paused
                _buffering.value = s.buffering
                if (s.ended && !_ended.value) {
                    _ended.value = true
                    recordLocal(ended = true)
                }
                // A pause, a play or a jump made in mpv (a remote's, a media key) means someone's there.
                if (s.paused != wasPaused || kotlin.math.abs(_positionMs.value - before) > 3_000) onInput?.invoke()
                wasPaused = s.paused
                s.error?.let { failed(it) }
                val now = dev.nitrate.android.nowMs()
                if (now - lastReport >= 10_000) {
                    lastReport = now
                    if (download != null) recordLocal()
                    else if (reported) _plan.value?.let { p -> runCatching { server.reportProgress(p, _positionMs.value, isPaused = s.paused) } }
                }
                if (_creditScenes.value == null && _durationMs.value > 0) updateCredits()
            }
        }
    }

    /** mpv couldn't play it: the file as it is gets one more go converted by the server, from here. */
    private suspend fun failed(message: String) {
        val p = _plan.value
        val item = _item.value
        if (p != null && item != null && download == null && p.method == PlayMethod.DIRECT_PLAY && !convertedAfterFailure) {
            convertedAfterFailure = true
            runCatching { play(item.id, _positionMs.value, p.audioIndex, p.subtitleIndex, forceTranscode = true) }
                .onFailure { _error.value = "Couldn't play this: $message" }
        } else if (_error.value == null) {
            _error.value = "Couldn't play this: $message"
            progressJob?.cancel()
        }
    }

    /** Saves where a download is (every few seconds, and when it stops). */
    private suspend fun recordLocal(ended: Boolean = false) {
        val r = download ?: return
        runCatching { container.downloads.recordPlayback(r.itemId, currentMs(), _durationMs.value, ended) }
    }

    private fun updateCredits() {
        val item = _item.value ?: return
        val runtime = item.runtimeMs ?: _durationMs.value.takeIf { it > 0 } ?: return
        _creditScenes.value = CreditScenes.info(runtime, item.tags, _segments.value, item.chapters)
    }

    /** Where the title is now, in ms. */
    fun currentMs(): Long = (player.state.value.positionSeconds * 1000).toLong()

    val isPaused: Boolean get() = player.state.value.paused

    fun seekTo(ms: Long) {
        val to = ms.coerceAtLeast(0).let { t -> _durationMs.value.takeIf { it > 0 }?.let { d -> minOf(t, d - 500) } ?: t }
        player.seek(to / 1000.0)
        _positionMs.value = to
        if (_ended.value && to < _durationMs.value - 1_000) _ended.value = false
    }

    fun seekBy(deltaMs: Long) = seekTo(currentMs() + deltaMs)

    fun togglePlay() {
        if (player.state.value.paused) player.play() else player.pause()
        _paused.value = !_paused.value
    }

    /** Pauses (the sleep timer). */
    fun pause() {
        player.pause()
        _paused.value = true
    }

    fun setSpeed(rate: Float) {
        _speed.value = rate
        player.setSpeed(rate.toDouble())
    }

    fun setVolume(volume: Int) {
        val v = volume.coerceIn(0, 130)
        _volume.value = v
        player.setVolume(v)
        if (_muted.value && v > 0) setMuted(false)
    }

    fun setMuted(muted: Boolean) {
        _muted.value = muted
        player.setMuted(muted)
    }

    fun setAudioDelay(ms: Int) {
        _audioDelayMs.value = ms
        player.setAudioDelay(ms)
    }

    fun setSubtitleDelay(ms: Int) {
        _subtitleDelayMs.value = ms
        player.setSubtitleDelay(ms)
    }

    /** Volume commands from other apps (the phone as a remote). */
    fun adjustVolume(command: String) {
        when (command) {
            "VolumeUp" -> setVolume(_volume.value + 5)
            "VolumeDown" -> setVolume(_volume.value - 5)
            "Mute" -> setMuted(true)
            "Unmute" -> setMuted(false)
            "ToggleMute" -> setMuted(!_muted.value)
        }
    }

    /**
     * Shows a second subtitle (text tracks only) at the top: a download's own SRT/WebVTT file, else
     * the server's copy as SRT. Null turns it off.
     */
    fun setSecondSubtitle(index: Int?) {
        secondJob?.cancel()
        val p = _plan.value
        if (index == null || p == null) {
            _second.value = null
            return
        }
        val label = p.source.trackName(index)
        secondJob = scope.launch {
            runCatching {
                val local = p.externalSubtitles.firstOrNull { it.streamIndex == index }
                    ?.takeIf { !it.url.startsWith("http") && (it.mimeType.contains("subrip", true) || it.mimeType.contains("vtt", true)) }
                val text = if (local != null) {
                    java.io.File(local.url).readText()
                } else {
                    io.ktor.client.HttpClient(container.platform.serverEngine()).use { http ->
                        http.get(server.subtitleFileUrl(p.itemId, p.source, index, "srt")) {
                            headers.append("Authorization", server.authorizationHeader())
                        }.bodyAsText()
                    }
                }
                _second.value = Second(index, label, dev.nitrate.core.playback.SecondSubtitles.parse(text))
            }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        }
    }

    /** Picks a track: remembered for the show (or title), switched in mpv, or the stream restarts from here with it. */
    override fun selectTrack(kind: StreamKind, index: Int) {
        val item = _item.value ?: return
        val p = _plan.value ?: return
        scope.launch {
            if (download == null) tracks?.let { setup -> tracks = runCatching { container.pickTrack(item, setup, kind, index) }.getOrNull() ?: setup }
            val audio = if (kind == StreamKind.AUDIO) index else p.audioIndex
            val subtitle = if (kind == StreamKind.SUBTITLE) index else p.subtitleIndex
            // mpv has the file's own tracks (and the subtitle files it added) at hand: switch without restarting.
            val inPlace = p.method == PlayMethod.DIRECT_PLAY || download != null ||
                (kind == StreamKind.SUBTITLE && (index < 0 || p.externalSubtitles.any { it.streamIndex == index }))
            if (inPlace) {
                if (kind == StreamKind.AUDIO) MpvTracks.audioId(p, index)?.let(player::setAudio) else player.setSubtitle(MpvTracks.subtitleId(p, index))
                _plan.value = p.copy(audioIndex = audio, subtitleIndex = subtitle)
                if (reported && download == null) runCatching { server.reportProgress(_plan.value!!, currentMs(), isPaused = isPaused) }
                return@launch
            }
            runCatching { play(item.id, currentMs(), audio, subtitle) }.onFailure { _error.value = "Couldn't change the track: ${it.message}" }
        }
    }

    override fun handleRemote(command: String, seekMs: Long?) {
        when (command) {
            "Pause", "Stop" -> pause()
            "Unpause" -> { player.play(); _paused.value = false }
            "PlayPause" -> togglePlay()
            "Seek" -> seekMs?.let(::seekTo)
        }
    }

    fun release() {
        if (download != null) {
            // Outlives the screen: the last position is saved after the player's gone.
            val at = currentMs()
            val d = download!!
            val ended = _ended.value
            kotlinx.coroutines.MainScope().launch { runCatching { container.downloads.recordPlayback(d.itemId, at, _durationMs.value, ended) } }
        } else if (reported) _plan.value?.let { server.reportStopped(it, currentMs()) }
        player.release()
        scope.cancel()
    }
}
