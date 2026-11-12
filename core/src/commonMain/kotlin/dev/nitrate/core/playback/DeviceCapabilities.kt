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
            return best.copy(tenBit = tier.any { it.tenBit })
        }
    }
}

/** An audio codec: decoded on the device, passed through to the receiver over HDMI, or both. */
data class AudioCodecCaps(val codec: String, val decoder: String?, val passthrough: Boolean) {
    val supported get() = decoder != null || passthrough
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
        )

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
    }
}
