package dev.nitrate.ios

import dev.nitrate.android.playback.ThemeMusic
import dev.nitrate.core.MediaServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.rate
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.volume
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification

/**
 * A title's theme music behind its details page, as on Android: quiet, faded in and out, the same
 * song kept from a series to its seasons and episodes, stopped when leaving the details pages,
 * when playback starts and while the app is in the background.
 */
class IosThemeMusic(private val server: MediaServer) : ThemeMusic {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: AVPlayer? = null
    private var playingSong: String? = null
    private var owner: String? = null
    private var stopJob: Job? = null
    private var startJob: Job? = null
    private val songs = mutableMapOf<String, String?>()
    private var looping: Any? = null
    private var backgrounded: String? = null

    init {
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) { _ -> onAppHidden() }
        center.addObserverForName(UIApplicationWillEnterForegroundNotification, null, NSOperationQueue.mainQueue) { _ -> onAppShown() }
    }

    override fun show(itemId: String) {
        owner = itemId
        stopJob?.cancel()
        startJob?.cancel()
        startJob = scope.launch {
            val song = if (songs.containsKey(itemId)) songs[itemId] else runCatching { server.themeSongs(itemId).firstOrNull()?.id }.getOrNull().also { songs[itemId] = it }
            if (song == null) {
                fadeOut()
                return@launch
            }
            if (song == playingSong && (player?.rate ?: 0f) > 0f) return@launch
            start(song)
        }
    }

    override fun hide(itemId: String) {
        if (owner != itemId) return
        owner = null
        stopJob?.cancel()
        stopJob = scope.launch {
            delay(HANDOVER_MS)
            if (owner == null) fadeOut()
        }
    }

    override fun stop() {
        owner = null
        startJob?.cancel()
        stopJob?.cancel()
        scope.launch { fadeOut() }
    }

    private suspend fun start(songId: String) {
        if (playingSong != null) fadeOut()
        val url = NSURL.URLWithString(server.audioStreamUrl(songId)) ?: return
        val asset = AVURLAsset(uRL = url, options = mapOf<Any?, Any?>("AVURLAssetHTTPHeaderFieldsKey" to mapOf("Authorization" to server.authorizationHeader())))
        val item = AVPlayerItem(asset = asset)
        val p = player ?: AVPlayer().also { player = it }
        looping?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        // Round and round while the page is open.
        looping = NSNotificationCenter.defaultCenter.addObserverForName(AVPlayerItemDidPlayToEndTimeNotification, item, NSOperationQueue.mainQueue) { _ ->
            p.seekToTime(CMTimeMake(0, 1))
            p.play()
        }
        playingSong = songId
        p.replaceCurrentItemWithPlayerItem(item)
        p.volume = 0f
        p.play()
        for (step in 1..FADE_STEPS) {
            p.volume = VOLUME * step / FADE_STEPS
            delay(FADE_IN_MS / FADE_STEPS)
        }
    }

    private suspend fun fadeOut() {
        val p = player ?: return
        if (p.rate > 0f) {
            val from = p.volume
            for (step in FADE_STEPS - 1 downTo 0) {
                p.volume = from * step / FADE_STEPS
                delay(FADE_OUT_MS / FADE_STEPS)
            }
        }
        p.pause()
        p.replaceCurrentItemWithPlayerItem(null)
        playingSong = null
    }

    override fun onAppHidden() {
        backgrounded = owner
        stop()
    }

    override fun onAppShown() {
        backgrounded?.let(::show)
        backgrounded = null
    }

    override fun release() {
        stop()
    }

    private companion object {
        const val VOLUME = 0.3f
        const val FADE_STEPS = 12
        const val FADE_IN_MS = 1_500L
        const val FADE_OUT_MS = 700L
        const val HANDOVER_MS = 700L
    }
}
