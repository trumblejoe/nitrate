package dev.nitrate.core.playback

/** Human names for capability ids (codecs and "range:<format>"). */
fun capabilityLabel(id: String): String = when (id) {
    "h264" -> "H.264 (AVC)"
    "hevc" -> "HEVC (H.265)"
    "av1" -> "AV1"
    "vp9" -> "VP9"
    "vp8" -> "VP8"
    "mpeg2video" -> "MPEG-2"
    "mpeg4" -> "MPEG-4 Part 2"
    "vc1" -> "VC-1"
    "aac" -> "AAC"
    "mp3" -> "MP3"
    "ac3" -> "Dolby Digital (AC-3)"
    "eac3" -> "Dolby Digital Plus (E-AC-3)"
    "truehd" -> "Dolby TrueHD"
    "dts" -> "DTS"
    "opus" -> "Opus"
    "vorbis" -> "Vorbis"
    "flac" -> "FLAC"
    "alac" -> "ALAC"
    "range:HDR10" -> "HDR10"
    "range:HDR10Plus" -> "HDR10+"
    "range:HLG" -> "HLG"
    "range:DOVI" -> "Dolby Vision"
    else -> id
}

fun formatBitrate(bps: Int) = if (bps >= PlaybackSettings.NO_LIMIT) "No limit" else "${bps / 1_000_000} Mbps"

/** A codec's everyday name ("HEVC", "Dolby Digital Plus"), for sentences. */
fun codecLabel(codec: String?): String = when (codec?.lowercase()) {
    null -> "Unknown"
    "hevc", "h265" -> "HEVC"
    "h264", "avc" -> "H.264"
    "av1" -> "AV1"
    "vp9" -> "VP9"
    "vp8" -> "VP8"
    "mpeg2video" -> "MPEG-2"
    "mpeg4" -> "MPEG-4"
    "vc1" -> "VC-1"
    "aac" -> "AAC"
    "mp3" -> "MP3"
    "ac3" -> "Dolby Digital"
    "eac3" -> "Dolby Digital Plus"
    "truehd" -> "Dolby TrueHD"
    "dts" -> "DTS"
    "flac" -> "FLAC"
    "opus" -> "Opus"
    "vorbis" -> "Vorbis"
    "alac" -> "ALAC"
    else -> if (codec.startsWith("pcm")) "PCM" else codec.uppercase()
}
