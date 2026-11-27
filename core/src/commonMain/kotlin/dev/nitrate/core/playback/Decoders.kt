package dev.nitrate.core.playback

import dev.nitrate.core.model.PlayMethod
import dev.nitrate.core.model.PlaybackPlan

/**
 * Who does the decoding, in plain words with the decoder's own name in brackets, the same on the
 * player's Info, its trail, Settings and playback reports: this device's hardware, Android's software
 * decoder, Nitrate's FFmpeg, or (sound) the TV or receiver it's passed through to; and what the
 * server does with the file.
 */
object Decoders {
    /** Which of them a decoder [name] is (as the player names it: "OMX.MTK…", "c2.android.opus.decoder", "ffmpeg6.0-opus", "FFmpeg (software) hevc"). */
    fun tierOf(name: String): DecoderTier = when {
        name.startsWith(DeviceCapabilities.SOFTWARE_VIDEO_DECODER) || name.startsWith("ffmpeg") || name.startsWith("nitrate.ffmpeg.") -> DecoderTier.NITRATE_SOFTWARE
        name.startsWith("c2.android.") || name.startsWith("OMX.google.") -> DecoderTier.DEVICE_SOFTWARE
        else -> DecoderTier.HARDWARE
    }

    /** "This device's hardware (OMX.MTK.VIDEO.DECODER.AV1)", "Nitrate's FFmpeg (ffmpeg6.0-opus)", "Nitrate's FFmpeg (hevc)". */
    fun describe(name: String): String {
        val tier = tierOf(name)
        // Nitrate's own label carries no name of its own beyond the codec it decodes.
        val raw = if (name.startsWith(DeviceCapabilities.SOFTWARE_VIDEO_DECODER)) name.removePrefix(DeviceCapabilities.SOFTWARE_VIDEO_DECODER).trim() else name
        return tier.plain + (raw.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "")
    }

    /** The sound's decoder: passed through untouched, else [describe] of [name] (null while not known). */
    fun describeSound(name: String?, passedThrough: Boolean): String? = when {
        passedThrough -> PASSED_THROUGH
        name != null -> describe(name)
        else -> null
    }

    /**
     * What a device reports for a format, in the same words: "c2.android.opus.decoder for stereo;
     * FFmpeg (software) for 5.1 and 7.1" → "Android's software decoder (c2.android.opus.decoder)
     * for stereo; Nitrate's FFmpeg for 5.1 and 7.1".
     */
    fun describeReported(reported: String): String = reported.split("; ").joinToString("; ") { part ->
        val name = part.substringBefore(" for ")
        val forWhat = part.substringAfter(" for ", "").takeIf { it.isNotEmpty() }?.let { " for $it" }.orEmpty()
        describe(name) + forWhat
    }

    const val PASSED_THROUGH = "Sent untouched to the TV or receiver"

    /** Apple's own player: iOS picks the decoder (VideoToolbox, hardware or software) and names none. */
    const val APPLE_PLAYER = "Apple's player (iOS chooses the decoder)"

    /** The picture's decoder in mpv ([d]); [hardware] names the hardware ("This computer's graphics card", "This device's hardware"). */
    fun mpvPicture(d: MpvDecoding, hardware: String): String? {
        val codec = d.videoCodec ?: return null
        val hw = d.hwdec?.takeIf { it.isNotBlank() && it != "no" }
        return if (hw != null) "$hardware ($hw, $codec)" else "mpv's software decoder (FFmpeg, $codec)"
    }

    /** The sound's decoder in mpv: passed through ("spdif-…" out) to the TV or receiver, else mpv's FFmpeg. */
    fun mpvSound(d: MpvDecoding): String? = when {
        d.audioOut?.startsWith("spdif") == true -> "$PASSED_THROUGH (${d.audioOut})"
        d.audioCodec != null -> "mpv's FFmpeg (${d.audioCodec})"
        else -> null
    }

    /**
     * Who decodes format [id] here, for Settings: "This device's hardware (OMX…)", "Nitrate's FFmpeg",
     * and for sound the TV or receiver too when it's passed through; null when nothing here does (or an HDR format).
     */
    fun decodedBy(caps: DeviceCapabilities, id: String): String? {
        caps.video[id]?.let { return describe(it.decoderName) }
        val a = caps.audio[id] ?: return null
        val here = a.decoder?.let(::describeReported)
        val passed = if (a.passthrough) "or sent untouched to the TV or receiver" else null
        return listOfNotNull(here, passed).joinToString(", ").ifEmpty { null }
    }

    /** What the server does with format [id] when it isn't played as it is: converts the sound, or the picture. */
    fun serverConverts(id: String): String =
        if (id in DeviceCapabilities.AUDIO_CODECS) "server converts the sound" else "server converts the picture"

    /** What the server does with the file: plays as it is, repackages it, or converts the sound only or picture and sound. */
    fun serverWords(plan: PlaybackPlan): String = when (plan.method) {
        PlayMethod.DIRECT_PLAY -> "Plays as it is"
        PlayMethod.DIRECT_STREAM -> "Repackaged (nothing converted)"
        PlayMethod.TRANSCODE -> if (soundOnly(plan)) "Converting the sound only (the picture passes through)" else "Converting picture and sound"
    }

    /** A conversion for sound reasons alone: the server passes the picture through (Jellyfin copies it). */
    fun soundOnly(plan: PlaybackPlan): Boolean =
        plan.method == PlayMethod.TRANSCODE && plan.transcodeReasons.isNotEmpty() && plan.transcodeReasons.all { it.startsWith("Audio") }
}

/**
 * What mpv says decodes the playback (desktop, and iOS when it plays in mpv): `hwdec-current` ("no"
 * or empty: software), `video-format` ("hevc"), `audio-codec-name` ("eac3"), `audio-out-params/format`
 * ("spdif-eac3" when passed through). All null until mpv knows.
 */
data class MpvDecoding(
    val hwdec: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val audioOut: String? = null,
) {
    companion object {
        /** Read through [property] (mpv's property as a string, or null). */
        fun read(property: (String) -> String?): MpvDecoding {
            fun p(name: String) = property(name)?.takeIf { it.isNotBlank() }
            return MpvDecoding(p("hwdec-current"), p("video-format"), p("audio-codec-name"), p("audio-out-params/format"))
        }
    }
}
