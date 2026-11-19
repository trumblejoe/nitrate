package dev.nitrate.core.playback

import kotlinx.serialization.Serializable

/**
 * What the device check (Settings → Tools → Playback test) found on this device, kept so decisions
 * and How it plays can say "confirmed" rather than "claimed". Per device, never synced; carried by
 * backups only to the same model, like the other hardware results.
 */
@Serializable
data class DeviceCheck(
    val atMs: Long,
    val model: String,
    /** By clip ([TestClip.asset]). */
    val clips: Map<String, CheckedClip> = emptyMap(),
    /** A real refresh-rate switch, on a TV that offers one. */
    val refresh: RefreshCheck? = null,
    /** The TV and receiver check: what the user saw for each of the plugin's clips (by clip id). */
    val seen: Map<String, SeenClip> = emptyMap(),
)

@Serializable
data class CheckedClip(
    val outcome: Outcome,
    val decoder: String? = null,
    /** Audio: whether it went out untouched (bitstream) rather than decoded; null for video or unknown. */
    val passthrough: Boolean? = null,
    val shown: Int = 0,
    val dropped: Int = 0,
)

/**
 * One refresh-rate switch: how long the screen was black, and whether (and when) the sound output
 * took surround formats untouched again afterwards (a TV or receiver re-syncing HDMI drops them
 * for a moment, and some never bring them back).
 */
@Serializable
data class RefreshCheck(
    val fromHz: Float,
    val toHz: Float,
    val switched: Boolean,
    val blackMs: Long? = null,
    /** Surround formats taken untouched before the switch (Media3 encodings). */
    val surroundBefore: Set<Int> = emptySet(),
    /** How long after the picture they were all back; null if they didn't come back within the wait. */
    val surroundBackMs: Long? = null,
)
