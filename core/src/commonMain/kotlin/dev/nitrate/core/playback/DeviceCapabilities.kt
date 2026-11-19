package dev.nitrate.core.playback

/** A video codec as the device's decoders describe it. */
data class VideoCodecCaps(
    val codec: String,
    val mime: String,
    val decoderName: String,
    val hardware: Boolean,
    val maxWidth: Int,
    val maxHeight: Int,
    val tenBit: Boolean,
    /**
     * Nitrate's own software decoder (FFmpeg) takes 10-bit where the device's decoders don't,
     * up to [CapabilityRules.SOFTWARE_MAX_HEIGHT] (Hi10P H.264).
     */
    val softwareTenBit: Boolean = false,
    /**
     * Profiles (Jellyfin's names, lower case) the device's own decoders don't take, e.g. H.264's
     * "high 4:2:2" from cameras and editors. Such files go to Nitrate's software decoder when
     * [softwareProfiles] (up to 1080p, progressive), else to the server.
     */
    val missingProfiles: Set<String> = emptySet(),
    /** Nitrate's software decoder takes [missingProfiles] (it converts any picture layout it decodes). */
    val softwareProfiles: Boolean = false,
) {
    companion object {
        /**
         * What the device can do with one codec, from all its decoders for it. Hardware decoders
         * speak for the device when there are any (Android's own software decoders often claim a
         * larger size but only 8-bit, and playback picks hardware first anyway); among them, the
         * largest size, and 10-bit if any of them decodes it.
         */
        fun combine(decoders: List<VideoCodecCaps>): VideoCodecCaps? {
            val tier = decoders.filter { it.hardware }.ifEmpty { decoders }
            val best = tier.maxByOrNull { it.maxWidth.toLong() * it.maxHeight } ?: return null
            // A profile is missing only when none of them takes it.
            val missing = tier.map { it.missingProfiles }.reduce { a, b -> a intersect b }
            return best.copy(tenBit = tier.any { it.tenBit }, missingProfiles = missing)
        }
    }
}

/** An audio codec: decoded on the device, passed through to the receiver over HDMI, or both. */
data class AudioCodecCaps(val codec: String, val decoder: String?, val passthrough: Boolean) {
    val supported get() = decoder != null || passthrough
}

/** The player a profile is built for; it decides containers and how subtitles arrive. */
enum class PlayerEngine {
    /** Android's Media3: MP4, MKV, WebM, MPEG-TS; text subtitles drawn here. */
    MEDIA3,

    /**
     * Apple's AVPlayer: MP4/MOV only (MKV is remuxed by the server into HLS, which is cheap),
     * HLS in fragmented MP4 so HEVC and HDR survive; subtitles only as WebVTT inside HLS, else
     * drawn into the picture by the server.
     */
    APPLE,

    /**
     * mpv (libmpv on iOS): reads every container and codec FFmpeg does, draws ASS/SSA with libass
     * and image subtitles itself; the server only sends the file.
     */
    MPV,
}

/** What the TV *claims* it can play. Real behaviour can differ; see the playback test. */
data class DeviceCapabilities(
    val video: Map<String, VideoCodecCaps>,
    val audio: Map<String, AudioCodecCaps>,
    /** HDR formats the display reports, as Jellyfin video range types (HDR10, HLG, DOVI, HDR10Plus). */
    val hdr: Set<String>,
    val displayWidth: Int,
    val displayHeight: Int,
    val model: String,
    /** Dolby Vision profiles a decoder advertises (5 = dvhe.05, 8 = dvhe.08, ...). */
    val dolbyVisionProfiles: Set<Int> = emptySet(),
    /** This app can rewrite profile 7 metadata as profile 8.1 while playing (libdovi built in). */
    val dolbyVisionConverter: Boolean = false,
    /**
     * Lossless and object audio the device (or the receiver behind it) takes untouched, as named in
     * [PASSTHROUGH_EXTRAS]: DTS-HD MA (and DTS:X in it), DTS:X Profile 2, Dolby Atmos in E-AC-3.
     * Media3 sends these as they are when listed, else the core (DTS) or plain E-AC-3.
     */
    val passthroughExtras: Set<String> = emptySet(),
    /** Which player plays the stream: Media3 (Android) or Apple's AVPlayer (iOS), which reads fewer containers. */
    val engine: PlayerEngine = PlayerEngine.MEDIA3,
) {
    companion object {
        const val DOLBY_VISION_MIME = "video/dolby-vision"

        /** Server codec name to the MIME type decoders advertise. */
        val VIDEO_CODECS = linkedMapOf(
            "av1" to "video/av01",
            "hevc" to "video/hevc",
            "vp9" to "video/x-vnd.on2.vp9",
            "h264" to "video/avc",
            "vp8" to "video/x-vnd.on2.vp8",
            "mpeg2video" to "video/mpeg2",
            "mpeg4" to "video/mp4v-es",
            "vc1" to "video/wvc1",
        )

        /** What Nitrate's software video decoder is called in capabilities and reports. */
        const val SOFTWARE_VIDEO_DECODER = "FFmpeg (software)"

        /** Codecs Media3 only reads from Matroska (not MP4 or MPEG-TS), so they direct play from MKV only. */
        val MATROSKA_ONLY_VIDEO = setOf("vc1")

        /** Server codec name to the MIME type decoders advertise. */
        val AUDIO_CODECS = linkedMapOf(
            "aac" to "audio/mp4a-latm",
            "mp3" to "audio/mpeg",
            "ac3" to "audio/ac3",
            "eac3" to "audio/eac3",
            "truehd" to "audio/true-hd",
            "dts" to "audio/vnd.dts",
            "opus" to "audio/opus",
            "vorbis" to "audio/vorbis",
            "flac" to "audio/flac",
            "alac" to "audio/alac",
        )

        val HDR_FORMATS = listOf("HDR10", "HDR10Plus", "HLG", "DOVI")

        /** What [passthroughExtras] may hold. */
        const val DTS_HD = "DTS-HD MA / DTS:X"
        const val DTS_X_P2 = "DTS:X Profile 2"
        const val ATMOS_EAC3 = "Dolby Atmos in E-AC-3"
        val PASSTHROUGH_EXTRAS = listOf(DTS_HD, DTS_X_P2, ATMOS_EAC3)
    }
}
