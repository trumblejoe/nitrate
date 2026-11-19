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
     * Dynamic ranges offered. Each format is matched as itself: HDR10 is plain HDR10 only, not HDR10+
     * or Dolby Vision. "HDR10 compatible" gathers every file an HDR10-only TV shows in HDR (HDR10,
     * HDR10+, Dolby Vision over an HDR10 base, profile 7 included). Dolby Vision is also offered by
     * profile: 5 (streaming, no base layer), 7 (UHD Blu-ray, dual layer) and 8 (single layer over
     * HDR10, HLG or SDR).
     */
    val RANGES = listOf(
        "sdr" to "SDR",
        "hdr10" to "HDR10",
        "hdr10plus" to "HDR10+",
        "hlg" to "HLG",
        "dovi" to "Dolby Vision",
        "dovi5" to "DV profile 5",
        "dovi7" to "DV profile 7",
        "dovi8" to "DV profile 8",
        "hdr10base" to "HDR10 compatible",
    )

    /**
     * The range filters a stream counts under: from Jellyfin's [videoRangeType], plus its Dolby
     * Vision profile ([dvProfile], else worked out from the range type: profile 7 is the one with
     * an enhancement layer, profile 5 the one with no base layer).
     */
    fun rangeTags(videoRangeType: String?, dvProfile: Int? = null): Set<String> {
        val tags = baseRangeTags(videoRangeType)
        if ("dovi" !in tags) return tags
        val profile = dvProfile?.takeIf { it > 0 } ?: when (videoRangeType) {
            "DOVI" -> 5
            "DOVIWithEL", "DOVIWithELHDR10Plus" -> 7
            "DOVIWithHDR10", "DOVIWithHDR10Plus", "DOVIWithHLG", "DOVIWithSDR" -> 8
            else -> null
        }
        return if (profile in setOf(5, 7, 8)) tags + "dovi$profile" else tags
    }

    private fun baseRangeTags(videoRangeType: String?): Set<String> = when (videoRangeType) {
        "SDR" -> setOf("sdr")
        "HDR10" -> setOf("hdr10", "hdr10base")
        "HDR10Plus" -> setOf("hdr10plus", "hdr10base")
        "HLG" -> setOf("hlg")
        "DOVI", "DOVIInvalid" -> setOf("dovi")
        "DOVIWithHDR10", "DOVIWithEL" -> setOf("dovi", "hdr10base")
        "DOVIWithHDR10Plus", "DOVIWithELHDR10Plus" -> setOf("dovi", "hdr10base")
        // Not offered as filters: they keep what a Dolby Vision file plays as without it (LibraryHealth).
        "DOVIWithHLG" -> setOf("dovi", "hlgbase")
        "DOVIWithSDR" -> setOf("dovi", "sdrbase")
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
