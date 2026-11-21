package dev.nitrate.core.music

import dev.nitrate.core.model.MediaItem
import kotlin.math.pow

/** Evening out loudness between songs, from the gain Jellyfin measures for each track and album. */
enum class Levelling(val label: String, val note: String) {
    OFF("Off", "Songs play as loud as they were made"),
    TRACK("Every song", "Each song brought to the same loudness: best for mixes and shuffle"),
    ALBUM("By album", "Albums kept as mastered, quiet songs quiet, and albums levelled against each other"),
}

/**
 * Loudness levelling: Jellyfin measures each track (and album) and stores the gain in dB that
 * brings it to its target loudness ([MediaItem.normalizationGain]). Only quieting is done here, as
 * player volume: a track that would need boosting plays at full volume rather than clipping.
 */
object Loudness {
    /** Never quieter than this, whatever a track's measurement says. */
    const val FLOOR = 0.1f

    /** The gain in dB for [track] under [mode]: the album's when levelling by album and it's known. */
    fun gainDb(track: MediaItem, albumGain: Double?, mode: Levelling): Double? = when (mode) {
        Levelling.OFF -> null
        Levelling.TRACK -> track.normalizationGain
        Levelling.ALBUM -> albumGain ?: track.normalizationGain
    }

    /** Player volume (0–1) for a gain in dB. */
    fun volume(gainDb: Double?): Float {
        if (gainDb == null || gainDb.isNaN()) return 1f
        return 10.0.pow(gainDb / 20.0).toFloat().coerceIn(FLOOR, 1f)
    }
}

/**
 * How a song reaches the player: the file as it is when Media3 reads its kind of file, else
 * converted by the server. A file that still fails as it is gets converted on the spot.
 */
object ListenStream {
    /** Containers Media3 reads (the codec inside them is checked when it plays). */
    val DIRECT_CONTAINERS = setOf("mp3", "flac", "m4a", "m4b", "mp4", "aac", "ogg", "oga", "opus", "webm", "webma", "wav", "mka", "alac")

    /** What the server converts to: AAC, which every device plays. */
    const val CONVERT_CODEC = "aac"
    const val CONVERT_BITRATE = 256_000

    fun direct(item: MediaItem): Boolean = item.container?.split(',')?.any { it.trim() in DIRECT_CONTAINERS } ?: true
}

/** Playing the queue over again: never, all of it, or the one song. */
enum class Repeat(val label: String) { OFF("Repeat off"), ALL("Repeat all"), ONE("Repeat one") }

/** Where the listening player is: its queue in playing order, the song on, and how it's playing. */
data class ListenState(
    val queue: List<MediaItem> = emptyList(),
    val index: Int = -1,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: Repeat = Repeat.OFF,
    /** A song that couldn't play, in words (it was skipped). */
    val problem: String? = null,
    /** Playing speed (audiobooks; music stays at 1). */
    val speed: Float = 1f,
    /** The sleep timer set, and when it stops playing (null for the end of the chapter). */
    val sleep: Sleep? = null,
    val sleepEndsAtMs: Long? = null,
) {
    val current: MediaItem? get() = queue.getOrNull(index)
    val active: Boolean get() = current != null
}

/**
 * The listening player (music, later audiobooks): one queue that plays song after song without
 * gaps, carries on in the background with lock-screen controls, levels loudness and reports to
 * the server. It lives as long as the app, not a screen, so browsing never stops it.
 */
interface Listening {
    val state: kotlinx.coroutines.flow.StateFlow<ListenState>

    /** Where in the current song, in ms (read as often as a progress bar needs). */
    fun positionMs(): Long

    /**
     * Plays [items] from [startIndex], replacing the queue ([shuffle]: in a random order), from
     * [startMs] into that one (an audiobook carrying on where it was left).
     */
    fun play(items: List<MediaItem>, startIndex: Int = 0, shuffle: Boolean = false, startMs: Long = 0)

    /** Back or on by [ms] within what's playing (audiobooks: −15 s, +30 s). */
    fun skipBy(ms: Long) {}

    /** Playing speed (audiobooks). */
    fun setSpeed(speed: Float) {}

    /** Stops by itself after a while or at the end of the chapter; null turns it off. */
    fun setSleep(sleep: Sleep?) {}

    /** After the current song. */
    fun playNext(items: List<MediaItem>)

    /** At the end of the queue. */
    fun addToQueue(items: List<MediaItem>)

    fun toggle()
    fun pause()
    /** The next song; in an audiobook, the next chapter. */
    fun next()

    /** Back to the start of the song (or chapter), or to the one before when only just started. */
    fun previous()
    fun seekTo(ms: Long)
    fun skipTo(index: Int)
    fun setShuffle(on: Boolean)
    fun setRepeat(repeat: Repeat)
    fun remove(index: Int)
    fun move(from: Int, to: Int)

    /** Stops and empties the queue (reported to the server). */
    fun stop()
}

/** Rules for going back: past this far into a song, Previous restarts it. */
object ListenRules {
    const val RESTART_AFTER_MS = 3_000L

    /** How often the server hears how a song is going. */
    const val REPORT_EVERY_MS = 10_000L
}

/** Songs on the device: played from their file (online or not), their plays kept to send later. */
interface ListenDownloads {
    /** The song's downloaded file, when it's on the device. */
    fun file(itemId: String): String?

    /** A downloaded song stopped at [positionMs] of [durationMs]; kept for the server, as films are. */
    suspend fun played(itemId: String, positionMs: Long, durationMs: Long, ended: Boolean)
}
