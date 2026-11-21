package dev.nitrate.core.cast

import dev.nitrate.core.model.MediaItem

/**
 * Casting to a Chromecast (or a TV with Chromecast built in) through Jellyfin's own receiver app:
 * the phone sends it what to play and the sign-in to fetch it with, and the receiver plays it
 * straight from the server, reporting to the server as its own device. Messages are built and read
 * by the server client (`MediaServer.castPlay`/`castCommand`/`castStatus`), since they speak
 * Jellyfin's language.
 */
object Cast {
    /** Jellyfin's published receiver app (the one Jellyfin's web app casts to). */
    const val RECEIVER_APP_ID = "F007D354"
    /** The channel Jellyfin's receiver listens and answers on. */
    const val NAMESPACE = "urn:x-cast:com.connectsdk"

    /** How long a jump back or ahead is from the cast bar. */
    const val JUMP_MS = 10_000L
}

/** What to start on the cast device. */
data class CastPlay(
    val items: List<MediaItem>,
    val startIndex: Int = 0,
    val startMs: Long = 0,
    val audioIndex: Int? = null,
    val subtitleIndex: Int? = null,
    val mediaSourceId: String? = null,
)

/** Controls sent to the receiver. */
enum class CastCommand { PAUSE, UNPAUSE, STOP, SEEK, NEXT, PREVIOUS, SET_VOLUME }

/** What the receiver says it's doing. */
data class CastStatus(
    val itemId: String? = null,
    val title: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val paused: Boolean = false,
    /** It stopped (or finished): nothing is playing any more. */
    val stopped: Boolean = false,
) {
    /** Where it is now, [sinceMs] after this status arrived while playing. */
    fun positionAt(sinceMs: Long): Long {
        val at = if (paused || stopped) positionMs else positionMs + sinceMs.coerceAtLeast(0)
        return durationMs?.let { at.coerceAtMost(it) } ?: at
    }
}
