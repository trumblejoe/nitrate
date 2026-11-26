package dev.nitrate.mobile.ui

import dev.nitrate.android.AppContainer
import dev.nitrate.android.nowMs
import dev.nitrate.core.admin.GeneralCommand
import dev.nitrate.core.admin.SessionCommand
import dev.nitrate.core.cast.CastCommand
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.StreamKind
import dev.nitrate.core.remote.RemotePrompt
import dev.nitrate.mobile.platform.CastControl

/** What the remote shows of the device it controls. */
data class RemoteView(
    val deviceName: String,
    val item: MediaItem?,
    val positionMs: Long,
    val paused: Boolean,
    val audioIndex: Int? = null,
    val subtitleIndex: Int? = null,
    /** It takes arrows, OK, Back and Home for its menus. */
    val navigable: Boolean = false,
    /** A question it's asking on screen, answerable from here. */
    val prompt: RemotePrompt? = null,
    /** Nitrate on a TV: pages and titles can be opened there, and text typed, from here. */
    val choosable: Boolean = false,
    /** The chat of what it plays (Nitrate on a TV), chatted in through it. */
    val chat: dev.nitrate.core.remote.RemoteChatState? = null,
)

/**
 * Something the phone's remote controls: another device running a Jellyfin app (through the
 * server), or a Chromecast playing through Jellyfin's receiver.
 */
interface RemoteTarget {
    /** How often to look again. */
    val pollMs: Long
    /** What it's doing now; null when it's gone. */
    suspend fun view(): RemoteView?
    suspend fun setPaused(paused: Boolean)
    suspend fun seek(ms: Long)
    suspend fun next()
    suspend fun previous()
    suspend fun stop()
    suspend fun track(kind: StreamKind, index: Int)
    /** [GeneralCommand.VOLUME_UP], [GeneralCommand.VOLUME_DOWN] or [GeneralCommand.TOGGLE_MUTE]. */
    suspend fun volume(command: GeneralCommand)
    /** Arrows, OK, Back and Home ([RemoteView.navigable] only). */
    suspend fun navigate(command: GeneralCommand) = Unit
    /** Opens a library's page or a title's details there ([RemoteView.choosable] only). */
    suspend fun show(item: MediaItem) = Unit
    /** Types [text] into the field in use there, else searches for it ([RemoteView.choosable] only). */
    suspend fun type(text: String) = Unit
    /** Plays [item] there from [startMs]. */
    suspend fun play(item: MediaItem, startMs: Long)
    suspend fun answer(prompt: RemotePrompt, choiceId: String) = Unit
    /** Asks its chat to do something ([RemoteView.chat] only): it posts as that TV. */
    suspend fun chatDo(what: dev.nitrate.core.remote.RemoteChatDo) = Unit
}

/**
 * Another of the user's devices, steered through the server. [beforeSending] runs the guards on
 * this phone before anything is sent to play there (what to send, or null for nothing).
 */
class SessionTarget(
    private val container: AppContainer,
    private val sessionId: String,
    private val beforeSending: suspend (MediaItem) -> MediaItem? = { it },
) : RemoteTarget {
    private val server get() = container.server
    override val pollMs = 1_500L

    override suspend fun view(): RemoteView? {
        val s = server.controllableSessions().firstOrNull { it.id == sessionId } ?: return null
        return RemoteView(
            deviceName = s.deviceName ?: s.client ?: "Device",
            item = s.nowPlaying,
            positionMs = s.positionMs ?: 0,
            paused = s.isPaused,
            audioIndex = s.audioIndex,
            subtitleIndex = s.subtitleIndex,
            navigable = s.navigable,
            prompt = container.remote.promptFrom(s.deviceId, nowMs()),
            choosable = s.choosable,
            chat = container.remote.chatFrom(s.deviceId, nowMs())?.takeIf { it.itemId == s.nowPlaying?.id },
        )
    }

    override suspend fun setPaused(paused: Boolean) = server.sessionCommand(sessionId, if (paused) SessionCommand.PAUSE else SessionCommand.UNPAUSE)
    override suspend fun seek(ms: Long) = server.seekSession(sessionId, ms.coerceAtLeast(0))
    override suspend fun next() = server.sessionCommand(sessionId, SessionCommand.NEXT)
    override suspend fun previous() = server.sessionCommand(sessionId, SessionCommand.PREVIOUS)
    override suspend fun stop() = server.sessionCommand(sessionId, SessionCommand.STOP)
    override suspend fun track(kind: StreamKind, index: Int) = server.setSessionTrack(sessionId, kind, index)
    override suspend fun volume(command: GeneralCommand) = server.sendGeneralCommand(sessionId, command)
    override suspend fun navigate(command: GeneralCommand) = server.sendGeneralCommand(sessionId, command)
    override suspend fun show(item: MediaItem) = server.showOn(sessionId, item)
    override suspend fun type(text: String) = server.typeOn(sessionId, text)
    override suspend fun answer(prompt: RemotePrompt, choiceId: String) = container.remote.answer(sessionId, prompt, choiceId)
    override suspend fun chatDo(what: dev.nitrate.core.remote.RemoteChatDo) = container.remote.chatDo(sessionId, what)

    override suspend fun play(item: MediaItem, startMs: Long) {
        val send = beforeSending(item) ?: return
        val tracks = runCatching { container.trackSetup(send) }.getOrNull()
        server.playOn(sessionId, listOf(send.id), if (send.id == item.id) startMs else send.user.positionMs, audioIndex = tracks?.audioIndex, subtitleIndex = tracks?.subtitleIndex, mediaSourceId = null)
    }
}

/** The Chromecast being cast to. */
class CastTarget(private val container: AppContainer, private val cast: CastControl) : RemoteTarget {
    private val server get() = container.server
    override val pollMs = 1_000L
    private var item: MediaItem? = null

    override suspend fun view(): RemoteView? {
        val state = cast.state.value
        val device = state.connected ?: return null
        val status = state.status?.takeIf { !it.stopped }
        val id = status?.itemId
        if (id == null) item = null else if (item?.id != id) item = runCatching { server.getItem(id) }.getOrNull()
        return RemoteView(
            deviceName = device.name,
            item = item,
            positionMs = status?.positionAt(nowMs() - state.statusAtMs) ?: 0,
            paused = status?.paused ?: true,
            audioIndex = status?.audioIndex,
            subtitleIndex = status?.subtitleIndex,
        )
    }

    private fun send(command: CastCommand, value: Long? = null) { server.castCommand(command, value)?.let(cast::send) }

    override suspend fun setPaused(paused: Boolean) = send(if (paused) CastCommand.PAUSE else CastCommand.UNPAUSE)
    override suspend fun seek(ms: Long) = send(CastCommand.SEEK, ms.coerceAtLeast(0))
    override suspend fun next() = send(CastCommand.NEXT)
    override suspend fun previous() = send(CastCommand.PREVIOUS)
    override suspend fun stop() = send(CastCommand.STOP)
    override suspend fun track(kind: StreamKind, index: Int) = send(if (kind == StreamKind.AUDIO) CastCommand.AUDIO_TRACK else CastCommand.SUBTITLE_TRACK, index.toLong())
    override suspend fun volume(command: GeneralCommand) = send(
        when (command) {
            GeneralCommand.VOLUME_UP -> CastCommand.VOLUME_UP
            GeneralCommand.VOLUME_DOWN -> CastCommand.VOLUME_DOWN
            else -> CastCommand.TOGGLE_MUTE
        },
    )

    override suspend fun play(item: MediaItem, startMs: Long) {
        castTo(container, cast, null, castPlayFor(container, item, fromStart = false, startMs = startMs))
    }
}
