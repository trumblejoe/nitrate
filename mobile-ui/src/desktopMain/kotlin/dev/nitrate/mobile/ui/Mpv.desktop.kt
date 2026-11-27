package dev.nitrate.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

/** What to open in mpv: the address, its headers, where to start and which tracks (mpv's ids). */
data class MpvMedia(
    val url: String,
    /** HTTP headers for the stream and the subtitle files (the account's Authorization; none for a download). */
    val headers: Map<String, String>,
    val startSeconds: Double,
    /** mpv's aid; null lets mpv choose (a stream the server repackaged has one audio track). */
    val audioId: Int?,
    /** mpv's sid; 0 is off. Subtitle files in [subtitleUrls] come after the file's own, in order. */
    val subtitleId: Int,
    val subtitleUrls: List<String> = emptyList(),
    /** A download's fonts for its styled subtitles (folder of font files), or null. */
    val fontsDir: String? = null,
)

/** Where mpv is, as it last said (observed properties and events). Times are seconds. */
data class MpvState(
    val positionSeconds: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val paused: Boolean = false,
    /** At the end (keep-open holds the last frame). */
    val ended: Boolean = false,
    /** Waiting for the network. */
    val buffering: Boolean = false,
    /** Why the file couldn't play (end-file with an error), or null. */
    val error: String? = null,
    val loaded: Boolean = false,
)

/**
 * One libmpv player (the LGPL build), implemented by :desktop over JNA ([DesktopMpv.factory]); a
 * fake in tests. Calls may come from the UI thread; the player does its work off it.
 */
interface MpvPlayer {
    val state: StateFlow<MpvState>

    /** The picture: mpv's own native window inside Nitrate's, or frames Nitrate draws ([VideoOutput]). */
    @Composable
    fun Video(modifier: Modifier)

    /** Whether the picture is a native window (the controls then go in a window of their own above it). */
    val nativeWindow: Boolean

    fun load(media: MpvMedia)
    fun setAudio(id: Int)
    fun setSubtitle(id: Int)
    fun play()
    fun pause()
    fun seek(seconds: Double)
    fun setSpeed(speed: Double)
    /** 0–130 (100 = as the file is). */
    fun setVolume(volume: Int)
    fun setMuted(muted: Boolean)
    /** Sound later (positive) or earlier, in ms (mpv's audio-delay). */
    fun setAudioDelay(ms: Int)
    /** Subtitles later (positive) or earlier, in ms (mpv's sub-delay). */
    fun setSubtitleDelay(ms: Int)

    /** What mpv says about the playback for the Info panel: label to value, in order. */
    fun describe(): List<Pair<String, String>>

    /** Who decodes, from mpv's own properties (for Info's plain "Picture decoder"/"Sound decoder"). */
    fun decoding(): dev.nitrate.core.playback.MpvDecoding = dev.nitrate.core.playback.MpvDecoding()

    fun release()
}

/** How a player is set up (from [DesktopPlayerSettings] when it's made). */
data class MpvConfig(
    val output: VideoOutput,
    val hardwareDecoding: Boolean,
    /** mpv's audio-spdif ("" decodes everything). */
    val spdif: String,
    val volume: Int,
    val userAgent: String,
)

/** :desktop registers mpv here when libmpv loads; without it the player says what's missing. */
object DesktopMpv {
    @Volatile var factory: ((MpvConfig) -> MpvPlayer)? = null

    /** Why mpv isn't there (shown in the player), when [factory] is null. */
    @Volatile var missing: String = "This build has no mpv (libmpv-2.dll), so it can't play videos."

    val available: Boolean get() = factory != null
}
