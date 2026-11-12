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

    fun label(id: String): String = (VIDEO + AUDIO).firstOrNull { it.first == id }?.second ?: id.uppercase()

    /**
     * Whether an item with these video and audio codecs passes: any of the chosen video codecs
     * (if any are chosen) and any of the chosen audio codecs (if any are chosen).
     */
    fun matches(video: Set<String>, audio: Set<String>, wantVideo: Collection<String>, wantAudio: Collection<String>): Boolean =
        (wantVideo.isEmpty() || video.any { it in wantVideo }) && (wantAudio.isEmpty() || audio.any { it in wantAudio })
}
