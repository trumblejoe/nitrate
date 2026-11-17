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

/**
 * One title playing in Apple's player, with what the shared player screen needs: where it is,
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

    private suspend fun play(itemId: String, startMs: Long, audioIndex: Int?, subtitleIndex: Int?) {
        val previous = _plan.value
        val p = server.resolvePlayback(
            itemId, isAudio = false, startMs = startMs, audioIndex = audioIndex, subtitleIndex = subtitleIndex,
            mediaSourceId = dev.nitrate.android.playback.VersionChoices[itemId],
        )
        val url = NSURL.URLWithString(p.url) ?: error("The server gave an address the player can't open.")
        val headers = mapOf<Any?, Any?>("Authorization" to server.authorizationHeader())
        val asset = AVURLAsset(uRL = url, options = mapOf<Any?, Any?>(HEADER_FIELDS to headers))
        val playerItem = AVPlayerItem(asset = asset)
        endObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        endObserver = NSNotificationCenter.defaultCenter.addObserverForName(AVPlayerItemDidPlayToEndTimeNotification, playerItem, NSOperationQueue.mainQueue) { _ -> _ended.value = true }
        player.replaceCurrentItemWithPlayerItem(playerItem)
        if (startMs > 0) player.seekToTime(CMTimeMakeWithSeconds(startMs / 1000.0, 600))
        player.play()
        _plan.value = p
        _ended.value = false
        if (reported) {
            // A track change restarts the stream: the old play session ends where it was.
            previous?.let { server.reportStopped(it, startMs) }
            runCatching { server.reportStart(p, startMs) }
        }
        followProgress()
    }

    private fun followProgress() {
        progressJob?.cancel()
        progressJob = scope.launch {
            var lastReport = 0L
            while (true) {
                delay(250)
                _positionMs.value = player.positionMs()
                player.currentItem?.let { current ->
                    val seconds = CMTimeGetSeconds(current.duration)
                    if (!seconds.isNaN() && seconds > 0) _durationMs.value = (seconds * 1000).toLong()
                }
                val now = dev.nitrate.android.nowMs()
                if (reported && now - lastReport >= 10_000) {
                    lastReport = now
                    _plan.value?.let { p -> runCatching { server.reportProgress(p, _positionMs.value, isPaused = player.rate == 0f) } }
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
        player.seekToTime(CMTimeMakeWithSeconds(ms.coerceAtLeast(0) / 1000.0, 600))
        _positionMs.value = ms
    }

    fun togglePlay() {
        if (player.rate == 0f) player.play() else player.pause()
    }

    /** Picks a track: remembered for the show (or title), then the stream restarts from here with it. */
    override fun selectTrack(kind: StreamKind, index: Int) {
        val item = _item.value ?: return
        val p = _plan.value ?: return
        scope.launch {
            tracks?.let { setup -> tracks = runCatching { container.pickTrack(item, setup, kind, index) }.getOrNull() ?: setup }
            val audio = if (kind == StreamKind.AUDIO) index else p.audioIndex
            val subtitle = if (kind == StreamKind.SUBTITLE) index else p.subtitleIndex
            runCatching { play(item.id, player.positionMs(), audio, subtitle) }.onFailure { _error.value = "Couldn't change the track: ${it.message}" }
        }
    }

    override fun handleRemote(command: String, seekMs: Long?) {
        when (command) {
            "Pause", "Stop" -> player.pause()
            "Unpause" -> player.play()
            "PlayPause" -> togglePlay()
            "Seek" -> seekMs?.let(::seekTo)
        }
    }

    fun release() {
        if (reported) _plan.value?.let { server.reportStopped(it, player.positionMs()) }
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
