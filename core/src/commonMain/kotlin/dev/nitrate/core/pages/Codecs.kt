package dev.nitrate.core.pages

/**
 * Codecs offered as library filters, by the names Jellyfin reports for streams. Jellyfin can't
 * filter by codec itself, so Nitrate reads the streams of a library's items and filters them
 * (see [ItemQuery.videoCodecs]).
 */
object Codecs {
    /** Video codecs: id as Jellyfin reports it, and its label. */
    val VIDEO = listOf(
        "h264" to "H.264",
        "hevc" to "HEVC",
        "av1" to "AV1",
        "vp9" to "VP9",
        "mpeg2video" to "MPEG-2",
        "vc1" to "VC-1",
        "mpeg4" to "MPEG-4",
    )

    /** Audio codecs; "pcm" covers every PCM variant. */
    val AUDIO = listOf(
        "aac" to "AAC",
        "ac3" to "Dolby Digital",
        "eac3" to "Dolby Digital Plus",
        "truehd" to "Dolby TrueHD",
        "dts" to "DTS",
        "flac" to "FLAC",
        "opus" to "Opus",
        "mp3" to "MP3",
        "pcm" to "PCM",
    )

    /** Bit depths offered. */
    val BIT_DEPTHS = listOf(8, 10, 12)

    /**
     * Dynamic ranges offered. Dolby Vision files also count as their base layer (HDR10, HLG or
     * SDR), and HDR10+ files as HDR10, since that's what they play as without the extra layer.
     */
    val RANGES = listOf(
        "sdr" to "SDR",
        "hdr10" to "HDR10",
        "hdr10plus" to "HDR10+",
        "hlg" to "HLG",
        "dovi" to "Dolby Vision",
    )

    /** The range filters a stream of Jellyfin's [videoRangeType] counts under. */
    fun rangeTags(videoRangeType: String?): Set<String> = when (videoRangeType) {
        "SDR" -> setOf("sdr")
        "HDR10" -> setOf("hdr10")
        "HDR10Plus" -> setOf("hdr10plus", "hdr10")
        "HLG" -> setOf("hlg")
        "DOVI", "DOVIInvalid" -> setOf("dovi")
        "DOVIWithHDR10", "DOVIWithEL" -> setOf("dovi", "hdr10")
        "DOVIWithHDR10Plus", "DOVIWithELHDR10Plus" -> setOf("dovi", "hdr10plus", "hdr10")
        "DOVIWithHLG" -> setOf("dovi", "hlg")
        "DOVIWithSDR" -> setOf("dovi", "sdr")
        else -> emptySet()
    }

    /** One stream's codec as a filter id: lower case, PCM variants as "pcm", aliases folded. */
    fun normalize(codec: String?): String? {
        val c = codec?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            c.startsWith("pcm") -> "pcm"
            c == "h265" -> "hevc"
            c == "avc" -> "h264"
            c == "dca" -> "dts"
            c == "mlp" -> "truehd"
            else -> c
        }
    }

    /** Libraries whose items have video to filter by: not music, collections or playlists. */
    fun offeredFor(kind: dev.nitrate.core.model.CollectionKind?): Boolean = kind !in setOf(
        dev.nitrate.core.model.CollectionKind.MUSIC, dev.nitrate.core.model.CollectionKind.BOX_SETS,
        dev.nitrate.core.model.CollectionKind.PLAYLISTS, dev.nitrate.core.model.CollectionKind.BOOKS, dev.nitrate.core.model.CollectionKind.PHOTOS,
    )

    fun label(id: String): String = (VIDEO + AUDIO + RANGES).firstOrNull { it.first == id }?.second ?: id.uppercase()

    /**
     * Whether an item passes the stream filters in [query]: one of its video streams matches every
     * video choice at once (codec, bit depth, range; each "any of"), and one of its audio streams
     * is a chosen audio codec. Kinds with nothing chosen don't narrow.
     */
    fun matches(item: StreamInfo, query: ItemQuery): Boolean {
        val videoChosen = query.videoCodecs.isNotEmpty() || query.bitDepths.isNotEmpty() || query.videoRanges.isNotEmpty()
        val videoOk = !videoChosen || item.video.any { v ->
            (query.videoCodecs.isEmpty() || v.codec in query.videoCodecs) &&
                (query.bitDepths.isEmpty() || v.bitDepth in query.bitDepths) &&
                (query.videoRanges.isEmpty() || v.ranges.any { it in query.videoRanges })
        }
        return videoOk && (query.audioCodecs.isEmpty() || item.audio.any { it in query.audioCodecs })
    }
}

/** One video stream, as the library filters see it. */
data class VideoStreamInfo(val codec: String?, val bitDepth: Int?, val ranges: Set<String>)

/** What an item (or, for a show, all its episodes) holds: its video streams and audio codecs. */
data class StreamInfo(val video: Set<VideoStreamInfo>, val audio: Set<String>)
