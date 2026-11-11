package dev.nitrate.core.syncplay

/** A SyncPlay ("watch together") group on the server. */
data class SyncPlayGroup(
    val id: String,
    val name: String,
    val state: GroupState,
    val participants: List<String>,
)

enum class GroupState { IDLE, WAITING, PAUSED, PLAYING }

/** What the group is watching: [itemIds] in order, [index] the current one. */
data class SyncPlayQueue(
    /** NewPlaylist, SetCurrentItem, NextItem, PreviousItem, Queue, RemoveItems... */
    val reason: String,
    val itemIds: List<String>,
    val playlistItemIds: List<String>,
    val index: Int,
    val startMs: Long,
    val isPlaying: Boolean,
) {
    val currentItemId: String? get() = itemIds.getOrNull(index)
    val currentPlaylistItemId: String? get() = playlistItemIds.getOrNull(index)

    /** True when this update changes what's on screen (rather than adding to the queue). */
    val changesItem: Boolean get() = reason in setOf("NewPlaylist", "SetCurrentItem", "NextItem", "PreviousItem")
}

/** A change to the group this device is in, pushed by the server. */
sealed interface SyncPlayUpdate {
    data class Joined(val group: SyncPlayGroup) : SyncPlayUpdate
    data object Left : SyncPlayUpdate
    data class UserJoined(val name: String) : SyncPlayUpdate
    data class UserLeft(val name: String) : SyncPlayUpdate
    data class State(val state: GroupState, val reason: String?) : SyncPlayUpdate
    data class Queue(val queue: SyncPlayQueue) : SyncPlayUpdate

    /** NotInGroup, GroupDoesNotExist or LibraryAccessDenied. */
    data class Refused(val reason: String) : SyncPlayUpdate
}

/** A command from the group: Unpause, Pause, Seek or Stop, to carry out at [whenMs] (server clock). */
data class SyncPlayCommand(
    val command: String,
    val whenMs: Long?,
    val positionMs: Long?,
    val playlistItemId: String?,
)

/** One /GetUtcTime answer, in server epoch ms. */
data class ServerTimeSample(val requestReceivedMs: Long, val responseSentMs: Long)

/**
 * The server's clock relative to ours, from a round trip: sent at [sentMs] and answered at
 * [receivedMs] (local), received and sent at [sample] (server). NTP's formula.
 */
data class ClockMeasurement(val offsetMs: Long, val roundTripMs: Long) {
    companion object {
        fun of(sentMs: Long, sample: ServerTimeSample, receivedMs: Long) = ClockMeasurement(
            offsetMs = ((sample.requestReceivedMs - sentMs) + (sample.responseSentMs - receivedMs)) / 2,
            roundTripMs = (receivedMs - sentMs) - (sample.responseSentMs - sample.requestReceivedMs),
        )

        /** The least-delayed measurement is the most accurate. */
        fun best(all: List<ClockMeasurement>): ClockMeasurement? = all.minByOrNull { it.roundTripMs }
    }
}

/** How to bring a drifting player back in line with the group. */
sealed interface DriftFix {
    data object None : DriftFix

    /** Play at [rate] until back in step (small drift, no visible jump). */
    data class Rate(val rate: Float) : DriftFix

    /** Jump to [positionMs] (large drift). */
    data class Seek(val positionMs: Long) : DriftFix

    companion object {
        const val TOLERANCE_MS = 100L
        const val SEEK_ABOVE_MS = 2_000L
        const val RATE_STEP = 0.05f

        /** [actualMs] is where the player is, [expectedMs] where the group is. */
        fun of(actualMs: Long, expectedMs: Long): DriftFix {
            val drift = actualMs - expectedMs
            return when {
                kotlin.math.abs(drift) <= TOLERANCE_MS -> None
                kotlin.math.abs(drift) >= SEEK_ABOVE_MS -> Seek(expectedMs)
                drift > 0 -> Rate(1f - RATE_STEP) // Ahead: slow down.
                else -> Rate(1f + RATE_STEP)
            }
        }
    }
}

/** The player SyncPlay drives. Loads start paused; the group says when to play. */
interface SyncPlayer {
    val itemId: String?
    val positionMs: Long
    val isPlaying: Boolean

    /** Loaded and not buffering. */
    val isReady: Boolean

    fun load(itemId: String, startMs: Long)
    fun play()
    fun pause()
    fun seek(positionMs: Long)
    fun setRate(rate: Float)

    /** The group stopped: leave the player. */
    fun stop()
}

/** Something this device tells its group. */
sealed interface SyncPlayRequest {
    data class SetQueue(val itemIds: List<String>, val index: Int, val startMs: Long) : SyncPlayRequest
    data object Pause : SyncPlayRequest
    data object Unpause : SyncPlayRequest
    data object Stop : SyncPlayRequest
    data class Seek(val positionMs: Long) : SyncPlayRequest

    /** This device is stalled ([buffering]) or ready again; [whenMs] on the server clock. */
    data class Status(val buffering: Boolean, val whenMs: Long, val positionMs: Long, val isPlaying: Boolean, val playlistItemId: String) : SyncPlayRequest
    data class NextItem(val playlistItemId: String) : SyncPlayRequest
    data class Ping(val roundTripMs: Long) : SyncPlayRequest
}
