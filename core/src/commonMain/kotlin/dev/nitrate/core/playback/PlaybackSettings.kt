package dev.nitrate.core.playback

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** Force a capability on or off, regardless of what the TV reports. Absent means Auto. */
@Serializable
/**
 * The user's choice for one format on this device; none is Automatic (what the device reports, and
 * what the device check measured). The names are stored: never rename them.
 */
enum class Override(val label: String) { ALWAYS("This device"), NEVER("Server converts") }

@Serializable
enum class MaxResolution(val label: String, val width: Int?, val height: Int?) {
    AUTO("Auto", null, null),
    UHD("4K", 3840, 2160),
    FHD("1080p", 1920, 1080),
    HD("720p", 1280, 720),
}

/**
 * What older versions learned from direct-play failures. No longer used: a failure now converts
 * that play only, and whether a format is always converted here is the user's choice ([Override]).
 * Kept so saved settings still read; cleared on the next playback ([PlaybackSettingsRepository.clearFailures]).
 */
@Serializable
data class LearnedFailure(
    val count: Int,
    val lastError: String,
    val lastSeenMs: Long,
    val items: Set<String> = emptySet(),
)

/** One title that failed here, as older versions kept it (see [LearnedFailure]). */
@Serializable
data class TitleFailure(val capability: String, val error: String, val atMs: Long)

/**
 * Per-device playback settings. Not synced: they describe this TV, not the user.
 *
 * Capability ids: a video codec ("hevc"), an audio codec ("eac3"), or an HDR format ("range:DOVI").
 */
@Serializable
data class PlaybackSettings(
    /**
     * At home (on the server's own network): above this the server converts down; [NO_LIMIT] (the
     * default) leaves every file as it is. Away and on mobile data: [awayMaxBitrate], [mobileMaxBitrate].
     */
    val maxBitrate: Int = NO_LIMIT,
    /** Away from home (not on the server's network; a VPN counts as away): a cap, or [AUTO_BITRATE] from a quick speed test. */
    val awayMaxBitrate: Int = AUTO_BITRATE,
    /** On mobile data or metered Wi-Fi (phones and tablets): a cap, or [AUTO_BITRATE]. */
    val mobileMaxBitrate: Int = AUTO_BITRATE,
    /** When playback keeps buffering, lower the quality by itself (else it's offered with one press). */
    val autoLowerQuality: Boolean = false,
    val maxResolution: MaxResolution = MaxResolution.AUTO,
    val allowDirectPlay: Boolean = true,
    val allowDirectStream: Boolean = true,
    val overrides: Map<String, Override> = emptyMap(),
    /** No longer used (see [LearnedFailure]); kept so saved settings read, and cleared. */
    val learnedFailures: Map<String, LearnedFailure> = emptyMap(),
    /** No longer used (see [LearnedFailure]). */
    val failedTitles: Map<String, TitleFailure> = emptyMap(),
    /** What the device check found here (null until it's run). */
    val deviceCheck: DeviceCheck? = null,
    /** Per video codec: largest height to direct play, e.g. 1080 when 4K decodes too slowly. */
    val codecMaxHeight: Map<String, Int> = emptyMap(),
    /** Video codecs limited to 8-bit even if the device reports 10-bit support. */
    val eightBitOnly: Set<String> = emptySet(),
    /** Codecs proven to play 10-bit here (by the playback test) though the TV doesn't report it. */
    val tenBitAllowed: Set<String> = emptySet(),
    /** No longer used (see [LearnedFailure]); kept so saved settings read. */
    val useLearnedFailures: Boolean = true,
    /** How subtitles look on this device. */
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    /** Keep audio playing with the screen off or in another app (phones). */
    val backgroundAudio: Boolean = false,
    /** Swipe up and down on the video for brightness (left) and volume (right) (phones). */
    val swipeControls: Boolean = true,
    /** Shrink the video into a corner window when leaving the app mid-playback (phones). */
    val pictureInPicture: Boolean = true,
    /**
     * Moves the sound against the picture, in milliseconds: positive plays it later, negative
     * earlier (for Bluetooth headphones or a receiver that adds lag). Per device.
     */
    val audioDelayMs: Int = 0,
    /**
     * Tunneled playback (TVs): the video and audio go straight to the hardware together, which some
     * TVs need for smooth or in-sync playback; others misbehave with it, so it's off by default.
     */
    val tunneling: Boolean = false,
    /** TVs: switch the refresh rate to the video's frame rate, so films don't judder. */
    val frameRateMatch: FrameRateMatch = FrameRateMatch.SEAMLESS,
    /** Evens out loud and quiet sound and lifts dialogue, for late nights. */
    val nightMode: Boolean = false,
    /** Lifts voices over music and effects (decodes sound that would go to a receiver untouched). */
    val dialogueBoost: Boolean = false,
    /**
     * Automatic (on): stereo films and episodes are spread over the output's speakers (3.1, 5.1
     * or 7.1), as answered once per output. Off: always stereo.
     */
    val upmixStereo: Boolean = true,
    /** What speakers each sound output has, by [SoundOutput.key] (asked once per output). */
    val speakerLayouts: Map<String, SpeakerLayout> = emptyMap(),
    /** Outputs whose speakers question was put off ("Later"), until when (ms). */
    val speakerAskLater: Map<String, Long> = emptyMap(),
    /**
     * After episodes keep playing on with nothing pressed, stop and ask "Still watching?"; with no
     * answer, go back to where the last press was.
     */
    val askStillWatching: Boolean = true,
    /** Music: evening out loudness between songs (per device). */
    val levelling: dev.nitrate.core.music.Levelling = dev.nitrate.core.music.Levelling.TRACK,
    /** How long the TV stays at a film's refresh rate after the player closes (with Match frame rate: Always). */
    val refreshHold: RefreshHold = RefreshHold.ALWAYS,
    /** Sends this device's playback summary (overview, test plays, TV check) for the server's admin to read (Companion `devices`). */
    val shareWithAdmin: Boolean = true,
    /**
     * Dual-layer Dolby Vision (profile 7) on a device that decodes profile 8: rewrite its
     * metadata as profile 8.1 while playing, for Dolby Vision instead of HDR10.
     */
    val convertProfile7: Boolean = true,
    /** Side-by-side and top-and-bottom 3D: one view in 2D, or both for a 3D screen. */
    val threeD: ThreeDMode = ThreeDMode.TWO_D,
    /** How the writing pane shares the screen with the video (phones and tablets). */
    val writingSplit: WritingSplit = WritingSplit.AUTO,
    /**
     * Sound and picture drifting apart while playing smoothly (core [SyncWatch]): line them up
     * again at once, the way stopping and playing again does.
     */
    val autoResync: Boolean = true,
    /**
     * Encoded sound formats (server codec names, e.g. "dts") decoded on this device rather than
     * passed through to a receiver: the lasting fix when they keep drifting from the picture.
     */
    val decodeHere: Set<String> = emptySet(),
) {
    /** Whether [id] may be direct played, given what the device claims: the user's choice, else Automatic. */
    fun allows(id: String, claimed: Boolean): Boolean = when (overrides[id]) {
        Override.ALWAYS -> true
        Override.NEVER -> false
        null -> claimed
    }

    companion object {
        /**
         * No cap: a file is never converted for its bitrate alone (high-bitrate remuxes play as they
         * are, buffering if the network falls short). The server's own per-user streaming limit
         * still applies away from home. Jellyfin takes the cap as an Int, so this is 1 Gbps.
         */
        const val NO_LIMIT = 1_000_000_000
        val BITRATES = listOf(NO_LIMIT, 120_000_000, 80_000_000, 40_000_000, 20_000_000, 10_000_000, 4_000_000)

        /** Quality from a quick speed test to the server ([TravelQuality.autoLimit]). */
        const val AUTO_BITRATE = 0
        val AWAY_BITRATES = listOf(AUTO_BITRATE, NO_LIMIT, 120_000_000, 80_000_000, 40_000_000, 20_000_000, 10_000_000, 4_000_000)
        val MOBILE_BITRATES = listOf(AUTO_BITRATE, 8_000_000, 4_000_000, 2_000_000, NO_LIMIT)
        fun hdrId(format: String) = "range:$format"
    }
}


/** Where per-device playback settings live (DataStore on Android). */
interface PlaybackSettingsRepository {
    val settings: Flow<PlaybackSettings>
    suspend fun current(): PlaybackSettings
    suspend fun update(transform: (PlaybackSettings) -> PlaybackSettings)
    /** Lets go of what older versions learned from failures ([LearnedFailure]); writes only when there's something. */
    suspend fun clearFailures() {
        if (current().let { it.learnedFailures.isNotEmpty() || it.failedTitles.isNotEmpty() }) {
            update { it.copy(learnedFailures = emptyMap(), failedTitles = emptyMap()) }
        }
    }
}
