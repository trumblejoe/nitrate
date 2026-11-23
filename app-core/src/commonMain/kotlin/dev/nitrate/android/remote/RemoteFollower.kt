package dev.nitrate.android.remote

import dev.nitrate.core.MediaServer
import dev.nitrate.core.admin.ServerSession
import dev.nitrate.core.admin.SessionCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Another device's playback followed for the lock screen and notification (Android's
 * `RemoteControlService`, iOS's Now Playing): what it plays and where, looked at every [LOOK_MS]
 * and given up once nothing has played there for [GIVE_UP_MS]; play/pause, seek, next/previous and
 * stop go back through the server. [onGone] is told when it gives up.
 */
class RemoteFollower(
    private val server: MediaServer,
    val sessionId: String,
    private val scope: CoroutineScope,
    private val now: () -> Long = { dev.nitrate.android.nowMs() },
    private val onGone: () -> Unit = {},
) {
    /** What the device plays (null: nothing, or not found) and when that was seen. */
    data class Seen(val session: ServerSession?, val atMs: Long)

    private val _seen = MutableStateFlow(Seen(null, now()))
    val seen: StateFlow<Seen> = _seen

    private var looking: Job? = null

    fun start() {
        if (looking?.isActive == true) return
        looking = scope.launch {
            var idleSince = now()
            while (isActive) {
                val s = look()
                if (s?.nowPlaying != null) idleSince = now()
                else if (now() - idleSince > GIVE_UP_MS) { onGone(); break }
                delay(LOOK_MS)
            }
        }
    }

    fun stop() { looking?.cancel(); looking = null }

    /** Where it is now: the last position seen, moved on by the time since while it plays. */
    fun positionMs(): Long {
        val (s, at) = _seen.value
        val base = s?.positionMs ?: 0
        return if (s != null && !s.isPaused && s.nowPlaying != null) base + (now() - at) else base
    }

    private suspend fun look(): ServerSession? {
        val s = runCatching { server.controllableSessions().firstOrNull { it.id == sessionId } }.getOrNull()
        _seen.value = Seen(s, now())
        return s
    }

    fun setPlaying(playing: Boolean) = send { server.sessionCommand(sessionId, if (playing) SessionCommand.UNPAUSE else SessionCommand.PAUSE) }
    fun toggle() = setPlaying(_seen.value.session?.isPaused != false)
    fun next() = send { server.sessionCommand(sessionId, SessionCommand.NEXT) }
    fun previous() = send { server.sessionCommand(sessionId, SessionCommand.PREVIOUS) }
    fun seekTo(ms: Long) = send { server.seekSession(sessionId, ms.coerceAtLeast(0)) }
    fun stopPlayback() = send { server.sessionCommand(sessionId, SessionCommand.STOP) }

    /** Sends [work], then looks again soon so what's shown is what really happened. */
    private fun send(work: suspend () -> Unit) {
        scope.launch {
            runCatching { work() }
            delay(500)
            look()
        }
    }

    companion object {
        const val LOOK_MS = 4_000L
        const val GIVE_UP_MS = 60_000L
    }
}
