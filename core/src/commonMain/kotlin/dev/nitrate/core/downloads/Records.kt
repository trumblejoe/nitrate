package dev.nitrate.core.downloads

import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.MediaSource
import kotlinx.serialization.Serializable

@Serializable
enum class DownloadState { QUEUED, DOWNLOADING, PAUSED, FAILED, COMPLETED }

/** A subtitle saved next to the video. */
@Serializable
data class LocalSubtitle(
    val streamIndex: Int,
    val file: String,
    val codec: String,
    val language: String? = null,
    val title: String? = null,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
)

/**
 * One downloaded (or downloading) item for one user on one server. [item] is a snapshot for
 * showing it offline; its [MediaItem.user] holds the watch state, updated by offline playback.
 */
@Serializable
data class DownloadRecord(
    val itemId: String,
    val serverUrl: String,
    val userId: String,
    val item: MediaItem,
    val quality: DownloadQuality,
    val hevc: Boolean = false,
    val state: DownloadState = DownloadState.QUEUED,
    val bytesDownloaded: Long = 0,
    /** From the server's Content-Length when it sends one (originals), else the estimate. */
    val totalBytes: Long? = null,
    val videoFile: String? = null,
    /** The downloaded file's streams, for track selection offline. */
    val source: MediaSource? = null,
    val subtitles: List<LocalSubtitle> = emptyList(),
    val posterFile: String? = null,
    val backdropFile: String? = null,
    val error: String? = null,
    val createdAtMs: Long = 0,
    val completedAtMs: Long? = null,
    /** Watch state changed on this device and not yet sent to the server. */
    val pendingSync: Boolean = false,
    /** When the local watch state last changed. */
    val watchedAtMs: Long? = null,
) {
    val key get() = Key(serverUrl, userId, itemId)

    /** 0..1, or null when the size is unknown. */
    val progress: Float? get() = totalBytes?.takeIf { it > 0 }?.let { (bytesDownloaded.toFloat() / it).coerceIn(0f, if (state == DownloadState.COMPLETED) 1f else 0.99f) }

    data class Key(val serverUrl: String, val userId: String, val itemId: String)
}

/** Per-device download preferences. */
@Serializable
data class DownloadSettings(
    val quality: DownloadQuality = DownloadQuality.MEDIUM,
    /** Ask the server for HEVC: smaller files, if this device decodes HEVC. */
    val hevc: Boolean = false,
    val wifiOnly: Boolean = true,
)

@Serializable
data class DownloadsData(
    val records: List<DownloadRecord> = emptyList(),
    val settings: DownloadSettings = DownloadSettings(),
)
