package dev.nitrate.android.remote

import dev.nitrate.android.playback.PlaybackSession
import dev.nitrate.core.MediaServer
import dev.nitrate.core.events.ServerEvent
import dev.nitrate.core.model.StreamKind
import dev.nitrate.core.syncplay.SyncPlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The server's live connection while signed in. Playback commands from other apps go straight
 * to the [active] player; everything else (play this, messages, volume, library changes) is
 * passed on through [events] for the app to act on.
 */
class RemoteControlHub(
    private val server: MediaServer,
    private val syncPlay: SyncPlayController? = null,
    /** The server added items to libraries (e.g. look for new episodes). */
    private val onItemsAdded: () -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val _events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ServerEvent> = _events
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    /** The player on screen, if any; set by player screens. */
    @Volatile
    var active: PlaybackSession? = null

    /** Connects (once) and keeps reconnecting until [stop]. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            server.events().collect { e ->
                when (e) {
                    ServerEvent.Connected -> {
                        _connected.value = true
                        launch { server.reportCapabilities() }
                    }
                    ServerEvent.Disconnected -> _connected.value = false
                    is ServerEvent.Playstate -> active?.handleRemote(e.command, e.seekMs)
                    is ServerEvent.SyncPlayCommand, is ServerEvent.SyncPlayGroupUpdate -> syncPlay?.handle(e)
                    is ServerEvent.LibraryChanged -> if (e.added.isNotEmpty()) onItemsAdded()
                    is ServerEvent.General -> when (e.name) {
                        "SetAudioStreamIndex" -> e.arguments["Index"]?.toIntOrNull()?.let { active?.selectTrack(StreamKind.AUDIO, it) }
                        "SetSubtitleStreamIndex" -> e.arguments["Index"]?.toIntOrNull()?.let { active?.selectTrack(StreamKind.SUBTITLE, it) }
                        else -> Unit
                    }
                    else -> Unit
                }
                _events.emit(e)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _connected.value = false
    }
}
