package dev.nitrate.tv.data.playback

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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

private val Context.playbackDataStore by preferencesDataStore(name = "playback_settings")

class PlaybackSettingsStore(private val context: Context) {
    companion object {
        fun hdrIdFor(format: String) = PlaybackSettings.hdrId(format)
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val key = stringPreferencesKey("settings")

    val settings: Flow<PlaybackSettings> = context.playbackDataStore.data.map { prefs ->
        prefs[key]?.let { runCatching { json.decodeFromString<PlaybackSettings>(it) }.getOrNull() } ?: PlaybackSettings()
    }

    suspend fun current(): PlaybackSettings = settings.first()

    suspend fun update(transform: (PlaybackSettings) -> PlaybackSettings) {
        context.playbackDataStore.edit { prefs ->
            val current = prefs[key]?.let { runCatching { json.decodeFromString<PlaybackSettings>(it) }.getOrNull() } ?: PlaybackSettings()
            prefs[key] = json.encodeToString(transform(current))
        }
    }

    suspend fun clearFailures() = update { it.copy(learnedFailures = emptyMap()) }

    suspend fun recordFailure(id: String, error: String) = update { s ->
        val previous = s.learnedFailures[id]
        s.copy(learnedFailures = s.learnedFailures + (id to LearnedFailure((previous?.count ?: 0) + 1, error, System.currentTimeMillis())))
    }
}
