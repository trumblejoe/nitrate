package dev.nitrate.mobile.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.MediaPlayer.MPChangePlaybackPositionCommandEvent
import platform.MediaPlayer.MPMediaItemArtwork
import platform.MediaPlayer.MPMediaItemPropertyAlbumTitle
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyArtwork
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandHandlerStatusCommandFailed
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import platform.MediaPlayer.MPSkipIntervalCommandEvent
import platform.UIKit.UIImage

/**
 * The lock screen's and Control Center's Now Playing on iOS (`MPNowPlayingInfoCenter` and
 * `MPRemoteCommandCenter`), shared by whatever is playing: the listening player, or another device
 * followed from here. The last to [take] it owns it; [release] by its owner clears it.
 */
object NowPlaying {
    /** What's shown. */
    data class Info(
        val title: String,
        val artist: String? = null,
        val album: String? = null,
        val durationMs: Long = 0,
        val positionMs: Long = 0,
        val playing: Boolean = false,
        val rate: Float = 1f,
        val artworkUrl: String? = null,
    )

    /** What the lock screen's buttons do. [skipMs] (back, ahead) shows ±15/30 s buttons (audiobooks) instead of previous/next. */
    interface Controls {
        fun play()
        fun pause()
        fun toggle()
        fun next()
        fun previous()
        fun seekTo(ms: Long)
        fun skipBy(ms: Long) {}
        val skipMs: Pair<Long, Long>? get() = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var owner: Any? = null
    private var controls: Controls? = null
    private var commandsSet = false
    private var artworkFor: String? = null
    private var artwork: MPMediaItemArtwork? = null
    private var last: Info? = null

    /** [owner] now plays: its [controls] answer the lock screen. Also makes the app's sound a playback session (keeps playing with the screen locked). */
    fun take(owner: Any, controls: Controls) {
        this.owner = owner
        this.controls = controls
        activateAudio()
        setCommands()
        val center = MPRemoteCommandCenter.sharedCommandCenter()
        val skip = controls.skipMs
        center.skipBackwardCommand.enabled = skip != null
        center.skipForwardCommand.enabled = skip != null
        center.nextTrackCommand.enabled = skip == null
        center.previousTrackCommand.enabled = skip == null
        if (skip != null) {
            center.skipBackwardCommand.preferredIntervals = listOf(skip.first / 1000.0)
            center.skipForwardCommand.preferredIntervals = listOf(skip.second / 1000.0)
        }
    }

    /** What [owner] plays now (ignored when another has taken over since). */
    fun update(owner: Any, info: Info) {
        if (owner !== this.owner) return
        last = info
        show(info)
        val url = info.artworkUrl
        if (url != null && url != artworkFor) {
            artworkFor = url
            artwork = null
            scope.launch {
                val image = withContext(Dispatchers.Default) { NSURL.URLWithString(url)?.let { NSData.dataWithContentsOfURL(it) }?.let { UIImage(data = it) } }
                if (image != null && artworkFor == url) {
                    artwork = artworkOf(image)
                    last?.let(::show)
                }
            }
        }
    }

    /** [owner] stopped: the lock screen goes back to nothing. */
    fun release(owner: Any) {
        if (owner !== this.owner) return
        this.owner = null
        controls = null
        last = null
        artworkFor = null
        artwork = null
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = null
    }

    private fun show(info: Info) {
        val map = mutableMapOf<Any?, Any?>(
            MPMediaItemPropertyTitle to info.title,
            MPMediaItemPropertyPlaybackDuration to info.durationMs / 1000.0,
            MPNowPlayingInfoPropertyElapsedPlaybackTime to info.positionMs / 1000.0,
            MPNowPlayingInfoPropertyPlaybackRate to if (info.playing) info.rate.toDouble() else 0.0,
        )
        info.artist?.let { map[MPMediaItemPropertyArtist] = it }
        info.album?.let { map[MPMediaItemPropertyAlbumTitle] = it }
        artwork?.let { map[MPMediaItemPropertyArtwork] = it }
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = map
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun artworkOf(image: UIImage): MPMediaItemArtwork = MPMediaItemArtwork(boundsSize = image.size, requestHandler = { _ -> image })

    @OptIn(ExperimentalForeignApi::class)
    private fun activateAudio() {
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayback, error = null)
        session.setActive(true, error = null)
    }

    private fun setCommands() {
        if (commandsSet) return
        commandsSet = true
        val center = MPRemoteCommandCenter.sharedCommandCenter()
        fun run(block: (Controls) -> Unit): Long {
            val c = controls ?: return MPRemoteCommandHandlerStatusCommandFailed
            block(c)
            return MPRemoteCommandHandlerStatusSuccess
        }
        center.playCommand.addTargetWithHandler { _ -> run { it.play() } }
        center.pauseCommand.addTargetWithHandler { _ -> run { it.pause() } }
        center.togglePlayPauseCommand.addTargetWithHandler { _ -> run { it.toggle() } }
        center.nextTrackCommand.addTargetWithHandler { _ -> run { it.next() } }
        center.previousTrackCommand.addTargetWithHandler { _ -> run { it.previous() } }
        center.changePlaybackPositionCommand.addTargetWithHandler { event ->
            val at = (event as? MPChangePlaybackPositionCommandEvent)?.positionTime ?: return@addTargetWithHandler MPRemoteCommandHandlerStatusCommandFailed
            run { it.seekTo((at * 1000).toLong()) }
        }
        center.skipForwardCommand.addTargetWithHandler { event ->
            val by = (event as? MPSkipIntervalCommandEvent)?.interval ?: 30.0
            run { it.skipBy((by * 1000).toLong()) }
        }
        center.skipBackwardCommand.addTargetWithHandler { event ->
            val by = (event as? MPSkipIntervalCommandEvent)?.interval ?: 15.0
            run { it.skipBy(-(by * 1000).toLong()) }
        }
    }
}
