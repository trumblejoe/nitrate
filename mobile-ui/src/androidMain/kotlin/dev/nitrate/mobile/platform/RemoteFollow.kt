package dev.nitrate.mobile.platform

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dev.nitrate.android.AppContainer
import dev.nitrate.core.MediaServer
import dev.nitrate.core.admin.ServerSession
import dev.nitrate.core.admin.SessionCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Another device's playback as a Media3 player, for the phone's notification and lock screen:
 * what it plays and where (from the server, looked at every few seconds), with play/pause, seek,
 * next/previous and stop sent back through the server.
 */
@OptIn(UnstableApi::class)
class RemoteSessionPlayer(private val server: MediaServer, val sessionId: String, private val scope: CoroutineScope) : SimpleBasePlayer(Looper.getMainLooper()) {
    private var session: ServerSession? = null
    private var seenAt = 0L

    fun update(s: ServerSession?) {
        session = s
        seenAt = SystemClock.elapsedRealtime()
        invalidateState()
    }

    override fun getState(): State {
        val commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA,
        ).build()
        val builder = State.Builder().setAvailableCommands(commands)
        val s = session
        val item = s?.nowPlaying ?: return builder.setPlaybackState(Player.STATE_IDLE).build()
        val playing = !s.isPaused
        val at = (s.positionMs ?: 0) + if (playing) SystemClock.elapsedRealtime() - seenAt else 0
        val metadata = MediaMetadata.Builder()
            .setTitle(item.displayTitle)
            .setArtist(listOfNotNull(item.episodeLabel, "On ${s.deviceName ?: "your TV"}").joinToString(" · "))
            .setArtworkUri(server.imageUrl(item)?.let(android.net.Uri::parse))
            .build()
        val data = MediaItemData.Builder(item.id)
            .setMediaItem(MediaItem.Builder().setMediaId(item.id).setMediaMetadata(metadata).build())
            .setDurationUs((item.runtimeMs ?: 0) * 1000)
            .setIsSeekable(true)
            .build()
        return builder
            .setPlaylist(listOf(data))
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setContentPositionMs(PositionSupplier.getExtrapolating(at, if (playing) 1f else 0f))
            .build()
    }

    private fun run(work: suspend () -> Unit): ListenableFuture<*> {
        val done = SettableFuture.create<Any>()
        scope.launch {
            runCatching { work() }
            // Look again soon, so the notification shows what really happened.
            delay(500)
            update(runCatching { server.controllableSessions().firstOrNull { it.id == sessionId } }.getOrNull() ?: session)
            done.set(Unit)
        }
        return done
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean) =
        run { server.sessionCommand(sessionId, if (playWhenReady) SessionCommand.UNPAUSE else SessionCommand.PAUSE) }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> = when (seekCommand) {
        Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> run { server.sessionCommand(sessionId, SessionCommand.NEXT) }
        Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> run { server.sessionCommand(sessionId, SessionCommand.PREVIOUS) }
        else -> run { server.seekSession(sessionId, positionMs.coerceAtLeast(0)) }
    }

    override fun handleStop() = run { server.sessionCommand(sessionId, SessionCommand.STOP) }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
}

/**
 * Hosts [RemoteSessionPlayer]'s session, for the media notification while another device plays.
 * Looks every few seconds; once nothing has played there for a minute it goes away by itself.
 */
@OptIn(UnstableApi::class)
class RemoteControlService : MediaSessionService() {
    companion object {
        private const val EXTRA_SESSION = "session"
        private const val LOOK_MS = 4_000L
        private const val GIVE_UP_MS = 60_000L

        fun follow(context: Context, sessionId: String) {
            runCatching { context.startService(Intent(context, RemoteControlService::class.java).putExtra(EXTRA_SESSION, sessionId)) }
        }
    }

    private val scope = MainScope()
    private var player: RemoteSessionPlayer? = null
    private var session: MediaSession? = null
    private var looking: Job? = null

    private val server get() = (application as? AppContainer.Owner)?.container?.server

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_SESSION)
        val server = server
        if (id != null && server != null && player?.sessionId != id) {
            session?.let { removeSession(it); it.release() }
            val p = RemoteSessionPlayer(server, id, scope)
            player = p
            session = MediaSession.Builder(this, p).setId("remote-$id").build().also { addSession(it) }
            looking?.cancel()
            looking = scope.launch {
                var idleSince = SystemClock.elapsedRealtime()
                while (isActive) {
                    val s = runCatching { server.controllableSessions().firstOrNull { it.id == id } }.getOrNull()
                    p.update(s)
                    if (s?.nowPlaying != null) idleSince = SystemClock.elapsedRealtime()
                    else if (SystemClock.elapsedRealtime() - idleSince > GIVE_UP_MS) { stopSelf(); break }
                    delay(LOOK_MS)
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        looking?.cancel()
        session?.release()
        session = null
        player = null
        scope.cancel()
        super.onDestroy()
    }
}

@Composable
actual fun rememberRemoteFollower(): (sessionId: String) -> Unit {
    val context = LocalContext.current
    return remember(context) { { id -> RemoteControlService.follow(context.applicationContext, id) } }
}
