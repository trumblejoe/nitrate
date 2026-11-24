package dev.nitrate.mobile.ui

import dev.nitrate.android.AppContainer
import dev.nitrate.android.remote.RemotePlayer
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.MediaSegment
import dev.nitrate.core.model.PlaybackPlan
import dev.nitrate.core.model.StreamKind
import dev.nitrate.core.playback.CreditScenes
import dev.nitrate.core.playback.CreditScenesInfo
import dev.nitrate.core.playback.TrackSetup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import dev.nitrate.core.playback.trackName
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.rate
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL

/** Which player shows the title. */
enum class IosEngine { APPLE, MPV }

/**
 * One title playing in Apple's player, or in mpv when the server would otherwise have to convert
 * it for Apple's (styled or image subtitles, DTS/TrueHD, Hi10P, odd containers), with what the
 * shared player screen needs: where it is,
 * the server's decision, the tracks (starting as the show's remembered pick or the server's,
 * changed by asking the server again from the same spot), skippable segments and scenes in the
 * credits. Start, progress (with the track indices) and stop are reported unless it's a recap clip.
 */
class ApplePlayerSession(private val container: AppContainer, private val reported: Boolean) : RemotePlayer {
    private val server = container.server
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val player = AVPlayer()

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
    private val _engine = MutableStateFlow(IosEngine.APPLE)
    val engine: StateFlow<IosEngine> = _engine

    /** The second subtitle shown at the top (dual subtitles): its stream index, name and lines. */
    data class Second(val index: Int, val label: String, val cues: List<dev.nitrate.core.playback.Cue>)

    private val _second = MutableStateFlow<Second?>(null)
    val secondSubtitle: StateFlow<Second?> = _second
    private var secondJob: Job? = null

    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed

    /** Whether the speed can change (Apple's player; mpv's bridge has no speed yet). */
    val canChangeSpeed: Boolean get() = !usingMpv

    /** Plays faster or slower (Apple's player). */
    fun setSpeed(rate: Float) {
        if (usingMpv) return
        _speed.value = rate
        // Playing again after a pause comes back at 1×: the progress loop puts the speed back.
        if (player.rate != 0f) player.rate = rate
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
                    ?.takeIf { it.url.startsWith("/") && (it.mimeType.contains("subrip", true) || it.mimeType.contains("vtt", true)) }
                val text = if (local != null) {
                    okio.FileSystem.SYSTEM.read(okio.Path.Companion.run { local.url.toPath() }) { readUtf8() }
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

    /** mpv, once a title needs it (kept for the rest of this session's titles). */
    var mpv: MpvPlayer? = null
        private set

    private var tracks: TrackSetup? = null
    private var progressJob: Job? = null
    private var endObserver: Any? = null

    /** Plays [itemId] from [startMs] (null: its resume point, or the start with [fromStart]). */
    fun load(itemId: String, fromStart: Boolean, startMs: Long?) {
        scope.launch {
            runCatching {
                val item = server.getItem(itemId)
                _item.value = item
                val start = startMs ?: if (fromStart) 0L else item.user.positionMs
                tracks = runCatching { container.trackSetup(item) }.getOrNull()
                play(itemId, start, tracks?.audioIndex, tracks?.subtitleIndex)
                launch {
                    _segments.value = runCatching { server.mediaSegments(itemId) }.getOrDefault(emptyList())
                    updateCredits()
                }
            }.onFailure { _error.value = "Couldn't start: ${it.message}" }
        }
    }

    /** The download being played (null: streaming). Its watch state is kept on the device, never reported. */
    private var download: dev.nitrate.core.downloads.DownloadRecord? = null

    /**
     * Plays a download from the device: MP4/MOV (a converted copy) in Apple's player, anything else
     * (an original MKV...) in mpv. Where it stops is saved for [dev.nitrate.core.downloads.DownloadManager.syncWatchState].
     */
    fun loadLocal(itemId: String, fromStart: Boolean, startMs: Long?) {
        scope.launch {
            runCatching {
                val downloads = container.downloads
                var record = downloads.find(itemId) ?: error("This download isn't on the device any more.")
                downloads.makeSeekable(record)
                record = downloads.withServerDefaults(downloads.find(itemId) ?: record)
                download = record
                _item.value = record.item
                val p = downloads.playbackPlan(record) ?: error("This download's file is missing.")
                val start = startMs ?: if (fromStart) 0L else downloads.resumePositionMs(record)
                val fileContainer = p.source.container.orEmpty().lowercase()
                val apple = fileContainer.split(',').any { it.trim() in setOf("mp4", "m4v", "mov") }
                val factory = Mpv.factory
                if (!apple && factory != null) {
                    player.pause()
                    val m = mpv ?: factory.create().also { mpv = it }
                    m.load(p.url, "", start / 1000.0,
                        dev.nitrate.core.playback.MpvTracks.audioId(p, p.audioIndex) ?: -1,
                        dev.nitrate.core.playback.MpvTracks.subtitleId(p, p.subtitleIndex),
                        p.externalSubtitles.map { it.url })
                    _engine.value = IosEngine.MPV
                } else {
                    _engine.value = IosEngine.APPLE
                    val playerItem = AVPlayerItem(asset = AVURLAsset(uRL = NSURL.fileURLWithPath(p.url), options = null))
                    endObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
                    endObserver = NSNotificationCenter.defaultCenter.addObserverForName(AVPlayerItemDidPlayToEndTimeNotification, playerItem, NSOperationQueue.mainQueue) { _ -> _ended.value = true }
                    player.replaceCurrentItemWithPlayerItem(playerItem)
                    if (start > 0) player.seekToTime(CMTimeMakeWithSeconds(start / 1000.0, 600))
                    player.play()
                }
                _plan.value = p
                _ended.value = false
                // Skip Intro and the credits from the server when it answers (offline: none).
                _segments.value = kotlinx.coroutines.withTimeoutOrNull(3_000) { runCatching { server.mediaSegments(itemId) }.getOrNull() }.orEmpty()
                updateCredits()
                followProgress()
            }.onFailure { _error.value = "Couldn't play the download: ${it.message}" }
        }
    }

    /** Saves where a download is (every few seconds, and when it stops). */
    private suspend fun recordLocal(ended: Boolean = false) {
        val r = download ?: return
        runCatching { container.downloads.recordPlayback(r.itemId, currentMs(), _durationMs.value, ended) }
    }

    private suspend fun play(itemId: String, startMs: Long, audioIndex: Int?, subtitleIndex: Int?) {
        val previous = _plan.value
        val version = dev.nitrate.android.playback.VersionChoices[itemId]
        var p = server.resolvePlayback(itemId, isAudio = false, startMs = startMs, audioIndex = audioIndex, subtitleIndex = subtitleIndex, mediaSourceId = version)
        // Apple's player would need the server to convert it: mpv plays the file as it is instead.
        val factory = Mpv.factory
        if (p.method == dev.nitrate.core.model.PlayMethod.TRANSCODE && factory != null) {
            val forMpv = runCatching {
                server.resolvePlayback(itemId, isAudio = false, startMs = startMs, audioIndex = audioIndex, subtitleIndex = subtitleIndex, mediaSourceId = version,
                    deviceCaps = Mpv.capabilities(container.capabilities))
            }.getOrNull()
            if (forMpv != null && forMpv.method != dev.nitrate.core.model.PlayMethod.TRANSCODE) {
                // The Apple play session the server opened is closed straight away.
                server.reportStopped(p, startMs)
                p = forMpv
                player.pause()
                player.replaceCurrentItemWithPlayerItem(null)
                val m = mpv ?: factory.create().also { mpv = it }
                m.load(
                    p.url, server.authorizationHeader(), startMs / 1000.0,
                    dev.nitrate.core.playback.MpvTracks.audioId(p, p.audioIndex) ?: -1,
                    dev.nitrate.core.playback.MpvTracks.subtitleId(p, p.subtitleIndex),
                    p.externalSubtitles.map { it.url },
                )
                _engine.value = IosEngine.MPV
                started(p, previous, startMs)
                return
            }
        }
        mpv?.pause()
        _engine.value = IosEngine.APPLE
        val url = NSURL.URLWithString(p.url) ?: error("The server gave an address the player can't open.")
        val headers = mapOf<Any?, Any?>("Authorization" to server.authorizationHeader())
        val asset = AVURLAsset(uRL = url, options = mapOf<Any?, Any?>(HEADER_FIELDS to headers))
        val playerItem = AVPlayerItem(asset = asset)
        endObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        endObserver = NSNotificationCenter.defaultCenter.addObserverForName(AVPlayerItemDidPlayToEndTimeNotification, playerItem, NSOperationQueue.mainQueue) { _ -> _ended.value = true }
        player.replaceCurrentItemWithPlayerItem(playerItem)
        if (startMs > 0) player.seekToTime(CMTimeMakeWithSeconds(startMs / 1000.0, 600))
        player.play()
        started(p, previous, startMs)
    }

    private suspend fun started(p: PlaybackPlan, previous: PlaybackPlan?, startMs: Long) {
        _plan.value = p
        _ended.value = false
        if (reported) {
            // A track change restarts the stream: the old play session ends where it was.
            previous?.let { server.reportStopped(it, startMs) }
            runCatching { server.reportStart(p, startMs) }
        }
        followProgress()
    }

    private val usingMpv get() = _engine.value == IosEngine.MPV

    /** Where the title is now, in ms, in whichever player shows it. */
    fun currentMs(): Long = if (usingMpv) ((mpv?.position() ?: 0.0) * 1000).toLong() else player.positionMs()

    private fun paused(): Boolean = if (usingMpv) mpv?.isPaused() ?: true else player.rate == 0f

    /** Called when the viewer did something (paused, played, jumped): for "Still watching?". */
    var onInput: (() -> Unit)? = null

    private fun followProgress() {
        progressJob?.cancel()
        progressJob = scope.launch {
            var lastReport = 0L
            var wasPaused = paused()
            while (true) {
                delay(250)
                val before = _positionMs.value
                _positionMs.value = currentMs()
                // Apple's own controls take the touches: a pause, a play or a jump means someone's there.
                val pausedNow = paused()
                if (pausedNow != wasPaused || kotlin.math.abs(_positionMs.value - before) > 3_000) onInput?.invoke()
                wasPaused = pausedNow
                if (!usingMpv && !pausedNow && _speed.value != 1f && player.rate != _speed.value) player.rate = _speed.value
                if (usingMpv) {
                    mpv?.let { m ->
                        m.duration().takeIf { it > 0 }?.let { _durationMs.value = (it * 1000).toLong() }
                        if (m.isEnded()) _ended.value = true
                    }
                } else {
                    player.currentItem?.let { current ->
                        val seconds = CMTimeGetSeconds(current.duration)
                        if (!seconds.isNaN() && seconds > 0) _durationMs.value = (seconds * 1000).toLong()
                    }
                }
                val now = dev.nitrate.android.nowMs()
                if (download != null && now - lastReport >= 10_000) {
                    lastReport = now
                    recordLocal()
                } else if (reported && now - lastReport >= 10_000) {
                    lastReport = now
                    _plan.value?.let { p -> runCatching { server.reportProgress(p, _positionMs.value, isPaused = paused()) } }
                }
                if (_creditScenes.value == null && _durationMs.value > 0) updateCredits()
            }
        }
    }

    private fun updateCredits() {
        val item = _item.value ?: return
        val runtime = item.runtimeMs ?: _durationMs.value.takeIf { it > 0 } ?: return
        // Markers, chapters and tags (reading the scrub thumbnails is Android's for now).
        _creditScenes.value = CreditScenes.info(runtime, item.tags, _segments.value, item.chapters)
    }

    fun seekTo(ms: Long) {
        if (usingMpv) mpv?.seek(ms.coerceAtLeast(0) / 1000.0) else player.seekToTime(CMTimeMakeWithSeconds(ms.coerceAtLeast(0) / 1000.0, 600))
        _positionMs.value = ms
    }

    fun togglePlay() {
        if (usingMpv) mpv?.let { if (it.isPaused()) it.play() else it.pause() }
        else if (player.rate == 0f) player.play() else player.pause()
    }

    /** Pauses (the sleep timer, "Pause too" in a Group Watch Session). */
    fun pause() {
        if (usingMpv) mpv?.pause() else player.pause()
    }

    /** Plays (a Group Watch Session's start, "Play too"). */
    fun play() {
        if (usingMpv) mpv?.play() else player.play()
    }

    val isPaused: Boolean get() = paused()

    /** Picks a track: remembered for the show (or title), then the stream restarts from here with it. */
    override fun selectTrack(kind: StreamKind, index: Int) {
        val item = _item.value ?: return
        val p = _plan.value ?: return
        scope.launch {
            tracks?.let { setup -> tracks = runCatching { container.pickTrack(item, setup, kind, index) }.getOrNull() ?: setup }
            val audio = if (kind == StreamKind.AUDIO) index else p.audioIndex
            val subtitle = if (kind == StreamKind.SUBTITLE) index else p.subtitleIndex
            val m = mpv
            // mpv has a file's own tracks at hand: switch without restarting.
            if (usingMpv && m != null && p.method == dev.nitrate.core.model.PlayMethod.DIRECT_PLAY) {
                if (download != null) {
                    if (kind == StreamKind.AUDIO) dev.nitrate.core.playback.MpvTracks.audioId(p, index)?.let(m::setAudio) else m.setSubtitle(dev.nitrate.core.playback.MpvTracks.subtitleId(p, index))
                    _plan.value = p.copy(audioIndex = audio, subtitleIndex = subtitle)
                    return@launch
                }
                if (kind == StreamKind.AUDIO) dev.nitrate.core.playback.MpvTracks.audioId(p, index)?.let(m::setAudio) else m.setSubtitle(dev.nitrate.core.playback.MpvTracks.subtitleId(p, index))
                _plan.value = p.copy(audioIndex = audio, subtitleIndex = subtitle)
                if (reported) runCatching { server.reportProgress(_plan.value!!, currentMs(), isPaused = paused()) }
                return@launch
            }
            // A download in Apple's player has the tracks it was saved with.
            if (download != null) return@launch
            runCatching { play(item.id, currentMs(), audio, subtitle) }.onFailure { _error.value = "Couldn't change the track: ${it.message}" }
        }
    }

    override fun handleRemote(command: String, seekMs: Long?) {
        when (command) {
            "Pause", "Stop" -> if (usingMpv) mpv?.pause() else player.pause()
            "Unpause" -> if (usingMpv) mpv?.play() else player.play()
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
        mpv?.release()
        endObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        player.pause()
        player.replaceCurrentItemWithPlayerItem(null)
        scope.cancel()
    }

    private companion object {
        /** AVURLAsset's (long-standing, unlisted) option for request headers, used by most players. */
        const val HEADER_FIELDS = "AVURLAssetHTTPHeaderFieldsKey"
    }
}

private fun AVPlayer.positionMs(): Long {
    if (currentItem == null) return 0
    val seconds = CMTimeGetSeconds(currentTime())
    return if (seconds.isNaN()) 0 else (seconds * 1000).toLong()
}
