package dev.nitrate.tv.ui.settings

/** Human names for capability ids (codecs and "range:<format>"). */
fun capabilityLabel(id: String): String = when (id) {
    "h264" -> "H.264 (AVC)"
    "hevc" -> "HEVC (H.265)"
    "av1" -> "AV1"
    "vp9" -> "VP9"
    "vp8" -> "VP8"
    "mpeg2video" -> "MPEG-2"
    "mpeg4" -> "MPEG-4 Part 2"
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

fun formatBitrate(bps: Int) = "${bps / 1_000_000} Mbps"
