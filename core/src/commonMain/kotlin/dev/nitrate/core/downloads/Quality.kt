package dev.nitrate.core.downloads

import dev.nitrate.core.model.MediaSource
import dev.nitrate.core.model.StreamKind
import kotlinx.serialization.Serializable

/** What the server should transcode a download to: one MP4 file with AAC audio. */
data class TranscodeSpec(
    val videoCodec: String,
    val maxWidth: Int,
    val maxHeight: Int,
    val videoBitrate: Int,
    val audioBitrate: Int,
    val audioChannels: Int = 2,
)

/**
 * Download sizes. Transcoded presets are made by the server (like Streamyfin's optimized
 * downloads), so a phone gets a file a fraction of the original's size.
 */
@Serializable
enum class DownloadQuality(val label: String, val height: Int?, private val width: Int?, private val videoBitrate: Int?, private val audioBitrate: Int) {
    ORIGINAL("Original", null, null, null, 0),
    HIGH("1080p", 1080, 1920, 8_000_000, 192_000),
    MEDIUM("720p", 720, 1280, 4_000_000, 160_000),
    LOW("480p", 480, 854, 1_500_000, 128_000),
    DATA_SAVER("360p", 360, 640, 700_000, 96_000),
    ;

    val isOriginal get() = this == ORIGINAL

    /**
     * True when this preset is bigger than the source both ways (1080p for a 720p file). One
     * dimension is enough to fit, so 1920x800 scope films and 1440x1080 4:3 both count as 1080p.
     */
    fun largerThan(sourceWidth: Int?, sourceHeight: Int?): Boolean {
        if (isOriginal || sourceWidth == null || sourceHeight == null) return false
        return width!! > sourceWidth * 21 / 20 && height!! > sourceHeight * 21 / 20
    }

    /** HEVC gives the same picture at about 60% of H.264's bitrate. */
    fun spec(hevc: Boolean): TranscodeSpec? {
        if (isOriginal) return null
        val video = if (hevc) videoBitrate!! * 6 / 10 else videoBitrate!!
        return TranscodeSpec(if (hevc) "hevc" else "h264", width!!, height!!, video, audioBitrate)
    }

    /** Bits per second of the result, or null for the original. */
    fun totalBitrate(hevc: Boolean): Int? = spec(hevc)?.let { it.videoBitrate + it.audioBitrate }
}

/** One option in the download picker. */
data class DownloadOption(
    val quality: DownloadQuality,
    /** Estimated file size in bytes, null when unknown. */
    val estimatedBytes: Long?,
    /** False when transcoding wouldn't make it meaningfully smaller (or would upscale). */
    val worthwhile: Boolean,
)

object DownloadSizing {
    /** Transcoded presets must save at least this much to be offered. */
    private const val MIN_SAVING = 0.85

    fun options(source: MediaSource, runtimeMs: Long?, hevc: Boolean): List<DownloadOption> {
        val video = source.streams.firstOrNull { it.kind == StreamKind.VIDEO }
        val sourceBitrate = source.bitrate?.takeIf { it > 0 }
            ?: if (source.size != null && runtimeMs != null && runtimeMs > 0) (source.size * 8_000 / runtimeMs).toInt() else null
        return DownloadQuality.entries.map { q ->
            if (q.isOriginal) return@map DownloadOption(q, source.size ?: estimate(sourceBitrate, runtimeMs), true)
            val bitrate = q.totalBitrate(hevc)!!
            val smaller = sourceBitrate == null || bitrate < sourceBitrate * MIN_SAVING
            DownloadOption(q, estimate(bitrate, runtimeMs), smaller && !q.largerThan(video?.width, video?.height))
        }
    }

    fun estimate(bitrate: Int?, runtimeMs: Long?): Long? =
        if (bitrate == null || runtimeMs == null || runtimeMs <= 0) null else bitrate.toLong() * runtimeMs / 8_000

    /** "1.4 GB", "820 MB". */
    fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> formatTenths(bytes / 100_000_000.0) + " GB"
        bytes >= 1_000_000 -> "${(bytes + 500_000) / 1_000_000} MB"
        else -> "${(bytes + 500) / 1_000} KB"
    }

    private fun formatTenths(tenths: Double): String {
        val t = kotlin.math.round(tenths).toLong()
        return "${t / 10}.${t % 10}"
    }
}
