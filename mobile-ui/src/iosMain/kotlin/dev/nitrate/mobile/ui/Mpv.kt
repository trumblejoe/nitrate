package dev.nitrate.mobile.ui

import dev.nitrate.core.playback.AudioCodecCaps
import dev.nitrate.core.playback.DeviceCapabilities
import dev.nitrate.core.playback.PlayerEngine
import dev.nitrate.core.playback.VideoCodecCaps
import platform.UIKit.UIView

/**
 * One libmpv player, implemented in Swift over MPVKit (LGPL build) by the app and handed to
 * Kotlin through [Mpv.factory]. Times are seconds; track ids are mpv's (0 = off, -1 = automatic).
 */
interface MpvPlayer {
    /** The view mpv draws into (a Metal layer); shown full screen by the player. */
    fun view(): UIView

    /** Opens [url] with the account's [authorization] header, from [startSeconds], with tracks, adding [subtitleUrls] as files. */
    fun load(url: String, authorization: String, startSeconds: Double, audioId: Int, subtitleId: Int, subtitleUrls: List<String>)
    fun setAudio(id: Int)
    fun setSubtitle(id: Int)
    fun play()
    fun pause()
    fun seek(seconds: Double)
    fun position(): Double
    fun duration(): Double
    fun isPaused(): Boolean
    fun isEnded(): Boolean
    fun release()
}

/** Makes [MpvPlayer]s. */
interface MpvFactory {
    fun create(): MpvPlayer
}

/** The Swift app registers mpv here at start when it's built in; without it Apple's player plays everything. */
object Mpv {
    var factory: MpvFactory? = null

    val available: Boolean get() = factory != null

    /**
     * What mpv plays (FFmpeg's decoders, VideoToolbox where the hardware has it): every common
     * codec and container, HDR and Dolby Vision tone-mapped by libplacebo for the screen.
     */
    fun capabilities(apple: DeviceCapabilities): DeviceCapabilities {
        fun video(codec: String, tenBit: Boolean = true) = VideoCodecCaps(
            codec = codec,
            mime = DeviceCapabilities.VIDEO_CODECS.getValue(codec),
            decoderName = "mpv",
            hardware = codec == "h264" || codec == "hevc",
            maxWidth = 3840,
            maxHeight = 2160,
            tenBit = tenBit,
        )
        return apple.copy(
            video = DeviceCapabilities.VIDEO_CODECS.keys.associateWith { video(it, tenBit = it != "mpeg2video" && it != "mpeg4" && it != "vc1") },
            audio = DeviceCapabilities.AUDIO_CODECS.keys.associateWith { AudioCodecCaps(it, decoder = "mpv", passthrough = false) },
            engine = PlayerEngine.MPV,
        )
    }
}
