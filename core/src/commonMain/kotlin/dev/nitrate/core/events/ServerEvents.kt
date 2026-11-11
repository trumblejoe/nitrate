package dev.nitrate.core.events

/** Something the server pushed: another app controlling this one, or library and watch changes. */
sealed interface ServerEvent {
    /** The live connection came up (after a reconnect too). */
    data object Connected : ServerEvent

    data object Disconnected : ServerEvent

    /** Play these items here: [command] is PlayNow, PlayNext or PlayLast. */
    data class Play(val itemIds: List<String>, val startMs: Long?, val command: String, val audioIndex: Int?, val subtitleIndex: Int?) : ServerEvent

    /** Pause, Unpause, PlayPause, Stop, Seek ([seekMs]), NextTrack, PreviousTrack, Rewind, FastForward. */
    data class Playstate(val command: String, val seekMs: Long?) : ServerEvent

    /** DisplayMessage, VolumeUp/Down, SetVolume, ToggleMute, SetAudioStreamIndex... with string arguments. */
    data class General(val name: String, val arguments: Map<String, String>) : ServerEvent

    /** Items were added, changed or removed in libraries. */
    data class LibraryChanged(val added: List<String>, val updated: List<String>, val removed: List<String>) : ServerEvent

    /** Watched state, position or favourites changed for these items (on any device). */
    data class UserDataChanged(val itemIds: List<String>) : ServerEvent

    /** A SyncPlay command for this device's group. */
    data class SyncPlayCommand(val groupId: String, val command: dev.nitrate.core.syncplay.SyncPlayCommand) : ServerEvent

    /** A change to this device's SyncPlay group. */
    data class SyncPlayGroupUpdate(val groupId: String, val update: dev.nitrate.core.syncplay.SyncPlayUpdate) : ServerEvent
}
