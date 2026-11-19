package dev.nitrate.core.playback

import kotlinx.serialization.Serializable

/**
 * What the server says it's doing with this device's stream right now (its session's
 * TranscodingInfo): what it copies and converts, on what hardware, how fast, and why.
 */
@Serializable
data class ServerConversion(
    val videoDirect: Boolean? = null,
    val audioDirect: Boolean? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val container: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val audioChannels: Int? = null,
    val bitrate: Int? = null,
    /** "qsv", "nvenc", "vaapi"... or "none" (the processor). */
    val hardware: String? = null,
    /** Frames per second it converts at. */
    val fps: Float? = null,
    val completion: Double? = null,
    val reasons: List<String> = emptyList(),
) {
    /** The hardware in words. */
    val hardwareLabel: String?
        get() = when (hardware?.lowercase()) {
            null -> null
            "none" -> "the processor"
            "qsv" -> "Intel Quick Sync"
            "nvenc" -> "NVIDIA"
            "vaapi" -> "VA-API"
            "amf" -> "AMD"
            "videotoolbox" -> "Apple VideoToolbox"
            "v4l2m2m" -> "V4L2"
            "rkmpp" -> "Rockchip"
            else -> hardware
        }

    /**
     * Whether it keeps up with a film of [filmFps]: converting faster than the film plays. Null
     * when there's nothing to judge (copied, or no speed yet).
     */
    fun keepsUp(filmFps: Float?): Boolean? {
        if (videoDirect != false) return null
        val speed = fps?.takeIf { it > 0 } ?: return null
        val film = filmFps?.takeIf { it > 0 } ?: return null
        return speed >= film * 1.05f
    }

    /** "Converted to HEVC 3840×2160 on Intel Quick Sync at 41 fps (the film is 23.976 fps: keeps up)". */
    fun describe(filmFps: Float?): String = buildString {
        if (videoDirect == true) append("Picture copied untouched")
        else {
            append("Picture converted")
            videoCodec?.let { append(" to ${codecLabel(it)}") }
            if (width != null && height != null && width > 0) append(" ${width}×$height")
            hardwareLabel?.let { append(" on $it") }
            fps?.takeIf { it > 0 }?.let { append(" at ${it.toInt()} fps") }
            filmFps?.takeIf { it > 0 && fps != null }?.let { f ->
                append(" (the film is ${PlaybackPredictor.hz(f)} fps: ")
                append(if (keepsUp(f) == true) "keeps up)" else "can't keep up, expect pauses)")
            }
        }
        append("; sound ")
        append(if (audioDirect == true) "copied untouched" else "converted" + (audioCodec?.let { " to ${codecLabel(it)}" } ?: "") + (PlaybackPredictor.channels(audioChannels)?.let { " $it" } ?: ""))
    }
}
