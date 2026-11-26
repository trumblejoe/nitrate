package dev.nitrate.android.remote

import dev.nitrate.core.MediaServer
import dev.nitrate.core.events.ServerEvent
import dev.nitrate.core.model.StreamKind
import dev.nitrate.core.remote.RemotePrompt
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

/** A player that other apps can steer through the server (pause, seek, change tracks). */
interface RemotePlayer {
    /** A playstate command from the server ("Pause", "Seek" with [seekMs]…). */
    fun handleRemote(command: String, seekMs: Long?)
    fun selectTrack(kind: StreamKind, index: Int)
}

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
    @kotlin.concurrent.Volatile
    var active: RemotePlayer? = null

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
                    // Watch party news from the Companion: never shown as a message.
                    is ServerEvent.General if e.name == "DisplayMessage" && e.arguments["Header"] == dev.nitrate.core.partners.PartyNews.HEADER -> {
                        val text = e.arguments["Text"].orEmpty()
                        // A new invite into a watch group: the invites are read again (and notified).
                        if (dev.nitrate.core.jellyfin.isInviteNews(text)) { _inviteNews.tryEmit(Unit); return@collect }
                        // Schedule changes, Group Watch Sessions and asks to watch together come the same way, told apart by their kind.
                        dev.nitrate.core.partners.AskNews.decode(text)?.let { _askNews.tryEmit(it) }
                            ?: dev.nitrate.core.partners.SessionNews.decode(text)?.let { _sessionNews.tryEmit(it) }
                            ?: dev.nitrate.core.partners.ScheduleNews.decode(text)?.let { _scheduleNews.tryEmit(it) }
                            ?: dev.nitrate.core.partners.PartyNews.decode(text)?.let { _partyNews.tryEmit(it) }
                        return@collect
                    }
                    // Questions between the user's own TV and phones: never shown as messages.
                    is ServerEvent.General if e.name == "DisplayMessage" && RemotePrompt.isOurs(e.arguments["Header"]) -> {
                        prompted(e.arguments["Header"], e.arguments["Text"].orEmpty())
                        return@collect
                    }
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

    private val _inviteNews = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    /** The Companion says the user has a new invite into a watch group (nothing more: their invites are read again). */
    val inviteNews: SharedFlow<Unit> = _inviteNews

    private val _partyNews = MutableSharedFlow<dev.nitrate.core.partners.PartyNews>(extraBufferCapacity = 16)

    /** What the Companion tells this app about the user's groups' watch parties (reminders, postponements, requests), as it happens. */
    val partyNews: SharedFlow<dev.nitrate.core.partners.PartyNews> = _partyNews

    private val _sessionNews = MutableSharedFlow<dev.nitrate.core.partners.SessionNews>(extraBufferCapacity = 32)

    /** What the Companion tells this app about Group Watch Sessions in the user's groups (started, countdown, pauses, jumps…), as it happens. */
    val sessionNews: SharedFlow<dev.nitrate.core.partners.SessionNews> = _sessionNews

    private val _askNews = MutableSharedFlow<dev.nitrate.core.partners.AskNews>(extraBufferCapacity = 16)

    /** What the Companion tells this app about asks to watch together in the user's groups, as it happens. */
    val askNews: SharedFlow<dev.nitrate.core.partners.AskNews> = _askNews

    private val _scheduleNews = MutableSharedFlow<dev.nitrate.core.partners.ScheduleNews>(extraBufferCapacity = 16)

    /** What the Companion tells this app about the user's groups' schedule changes (asked for, settled), as it happens. */
    val scheduleNews: SharedFlow<dev.nitrate.core.partners.ScheduleNews> = _scheduleNews

    // ---- Questions the TV asks, answered from a phone ----

    private val _prompts = MutableStateFlow<Map<String, Pair<RemotePrompt, Long>>>(emptyMap())
    /** Phone: questions the user's TVs are asking, by the asking device's id, with when they were last heard. */
    val prompts: StateFlow<Map<String, Pair<RemotePrompt, Long>>> = _prompts

    /** Phone: what the device [deviceId] is asking now, if heard lately. */
    fun promptFrom(deviceId: String?, nowMs: Long): RemotePrompt? =
        _prompts.value[deviceId]?.takeIf { nowMs - it.second < RemotePrompt.FORGET_MS }?.first

    private val _answers = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 8)
    /** TV: answers from a phone, as (question id, choice id). */
    val answers: SharedFlow<Pair<String, String>> = _answers

    private fun prompted(header: String?, text: String) {
        when (header) {
            RemotePrompt.PROMPT -> RemotePrompt.decode(text)?.let { p -> _prompts.value = _prompts.value + (p.deviceId to (p to dev.nitrate.android.nowMs())) }
            RemotePrompt.PROMPT_GONE -> _prompts.value = _prompts.value.filterValues { it.first.id != text }
            RemotePrompt.ANSWER -> RemotePrompt.readAnswer(text)?.let { _answers.tryEmit(it) }
            dev.nitrate.core.remote.RemoteSessionStart.HEADER -> dev.nitrate.core.remote.RemoteSessionStart.decode(text)?.let { _sessionStarts.tryEmit(it) }
        }
    }

    /** The user's phones running Nitrate (not this device), which may answer for it. */
    private suspend fun phones() = server.controllableSessions().filter {
        it.client == RemotePrompt.PHONE_CLIENT && it.deviceId != server.deviceId && (it.userId == null || it.userId == server.userId)
    }

    /** TV: tells the user's phones what it's asking (again every [RemotePrompt.REPEAT_MS] while asked from [ask]). */
    suspend fun offer(prompt: RemotePrompt) {
        val text = RemotePrompt.encode(prompt)
        runCatching { phones() }.getOrDefault(emptyList()).forEach { runCatching { server.sendMessage(it.id, RemotePrompt.PROMPT, text) } }
    }

    /** TV: the question was answered or went away. */
    suspend fun withdraw(promptId: String) {
        runCatching { phones() }.getOrDefault(emptyList()).forEach { runCatching { server.sendMessage(it.id, RemotePrompt.PROMPT_GONE, promptId) } }
    }

    /** TV: [withdraw] without waiting (a question leaving the screen). */
    fun withdrawSoon(promptId: String) { scope.launch { withdraw(promptId) } }

    private val _sessionStarts = MutableSharedFlow<dev.nitrate.core.remote.RemoteSessionStart>(extraBufferCapacity = 2)
    /** TV: a phone's remote asks it to start a Group Watch Session, hosted here. */
    val sessionStarts: SharedFlow<dev.nitrate.core.remote.RemoteSessionStart> = _sessionStarts

    /** Phone: asks the TV whose session is [sessionId] to start a Group Watch Session of [groupId] on [itemId] there. */
    suspend fun askToStartSession(sessionId: String, groupId: String, itemId: String) =
        server.sendMessage(sessionId, dev.nitrate.core.remote.RemoteSessionStart.HEADER, dev.nitrate.core.remote.RemoteSessionStart.encode(dev.nitrate.core.remote.RemoteSessionStart(groupId, itemId)))

    /** Phone: answers [prompt] on the device whose session is [sessionId]. */
    suspend fun answer(sessionId: String, prompt: RemotePrompt, choiceId: String) {
        _prompts.value = _prompts.value - prompt.deviceId
        server.sendMessage(sessionId, RemotePrompt.ANSWER, RemotePrompt.answer(prompt.id, choiceId))
    }

    fun stop() {
        job?.cancel()
        job = null
        _connected.value = false
    }
}
