package dev.nitrate.core.playback

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** Force a capability on or off, regardless of what the TV reports. Absent means Auto. */
@Serializable
enum class Override(val label: String) { ALWAYS("Always"), NEVER("Never") }

@Serializable
enum class MaxResolution(val label: String, val width: Int?, val height: Int?) {
    AUTO("Auto", null, null),
    UHD("4K", 3840, 2160),
    FHD("1080p", 1920, 1080),
    HD("720p", 1280, 720),
}

/** A direct-play failure seen on this TV, so the same file isn't tried again. */
@Serializable
data class LearnedFailure(val count: Int, val lastError: String, val lastSeenMs: Long)

/**
 * Per-device playback settings. Not synced: they describe this TV, not the user.
 *
 * Capability ids: a video codec ("hevc"), an audio codec ("eac3"), or an HDR format ("range:DOVI").
 */
@Serializable
data class PlaybackSettings(
    val maxBitrate: Int = 120_000_000,
    val maxResolution: MaxResolution = MaxResolution.AUTO,
    val allowDirectPlay: Boolean = true,
    val allowDirectStream: Boolean = true,
    val overrides: Map<String, Override> = emptyMap(),
    val learnedFailures: Map<String, LearnedFailure> = emptyMap(),
    /** Per video codec: largest height to direct play, e.g. 1080 when 4K decodes too slowly. */
    val codecMaxHeight: Map<String, Int> = emptyMap(),
    /** Video codecs limited to 8-bit even if the device reports 10-bit support. */
    val eightBitOnly: Set<String> = emptySet(),
    /** Treat learned failures as Never (unless overridden to Always). */
    val useLearnedFailures: Boolean = true,
    /** How subtitles look on this device. */
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    /** Keep audio playing with the screen off or in another app (phones). */
    val backgroundAudio: Boolean = false,
    /** Shrink the video into a corner window when leaving the app mid-playback (phones). */
    val pictureInPicture: Boolean = true,
    /** Evens out loud and quiet sound and lifts dialogue, for late nights. */
    val nightMode: Boolean = false,
) {
    /** Whether [id] may be direct played, given what the device claims. */
    fun allows(id: String, claimed: Boolean): Boolean = when (overrides[id]) {
        Override.ALWAYS -> true
        Override.NEVER -> false
        null -> claimed && !(useLearnedFailures && id in learnedFailures)
    }

    companion object {
        val BITRATES = listOf(120_000_000, 80_000_000, 40_000_000, 20_000_000, 10_000_000, 4_000_000)
        fun hdrId(format: String) = "range:$format"
    }
}


/** Where per-device playback settings live (DataStore on Android). */
interface PlaybackSettingsRepository {
    val settings: Flow<PlaybackSettings>
    suspend fun current(): PlaybackSettings
    suspend fun update(transform: (PlaybackSettings) -> PlaybackSettings)
    suspend fun clearFailures() = update { it.copy(learnedFailures = emptyMap()) }

    suspend fun recordFailure(id: String, error: String, nowMs: Long) = update { s ->
        val previous = s.learnedFailures[id]
        s.copy(learnedFailures = s.learnedFailures + (id to LearnedFailure((previous?.count ?: 0) + 1, error, nowMs)))
    }
}
