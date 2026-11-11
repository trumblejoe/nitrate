package dev.nitrate.core.syncplay

import dev.nitrate.core.MediaServer
import dev.nitrate.core.events.ServerEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Watch together: keeps this device's player in step with a SyncPlay group.
 *
 * The server runs the group. Pressing play, pause or seek here only asks it to ([requestPlay]...);
 * it then tells every member what to do and when, on its clock. This class measures that clock,
 * carries the commands out on time, says when the player is ready or stalled, and nudges the
 * playback rate (or seeks, if far out) to stay in step.
 *
 * Feed it the server's events ([handle]); the player on screen [attach]es itself and calls
 * [onPlayerState] whenever it starts or stops buffering.
 */
@OptIn(ExperimentalTime::class)
class SyncPlayController(
    private val server: MediaServer,
    private val scope: CoroutineScope,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val _group = MutableStateFlow<SyncPlayGroup?>(null)
    val group: StateFlow<SyncPlayGroup?> = _group
    private val _queue = MutableStateFlow<SyncPlayQueue?>(null)
    val queue: StateFlow<SyncPlayQueue?> = _queue
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** Short messages for the user: "Sam joined", "Waiting for others"... */
    val notices: SharedFlow<String> = _notices
    private val _openRequests = MutableSharedFlow<SyncPlayQueue>(extraBufferCapacity = 4)

    /** The group started something and no player is open: open one on the queue's current item. */
    val openRequests: SharedFlow<SyncPlayQueue> = _openRequests

    val inGroup: Boolean get() = _group.value != null

    /** Server clock minus ours. */
    var clockOffsetMs: Long = 0
        private set

    private var player: SyncPlayer? = null
    private var awaitingReady = false
    private var ownSeekAt = Long.MIN_VALUE / 2

    /** The group's last Unpause: playing from [positionMs] at local time [atMs]. */
    private var playingFrom: Pair<Long, Long>? = null
    private var commandJob: Job? = null
    private var driftJob: Job? = null
    private var clockJob: Job? = null

    private fun serverNow() = now() + clockOffsetMs

    // ---- Groups ----

    suspend fun groups(): List<SyncPlayGroup> = server.syncPlayGroups()

    suspend fun create(name: String) {
        measureClock()
        server.syncPlayCreate(name)
    }

    suspend fun join(groupId: String) {
        measureClock()
        server.syncPlayJoin(groupId)
    }

    suspend fun leave() {
        runCatching { server.syncPlayLeave() }
        left()
    }

    // ---- Server events ----

    fun handle(event: ServerEvent) {
        when (event) {
            is ServerEvent.SyncPlayGroupUpdate -> onUpdate(event.update)
            is ServerEvent.SyncPlayCommand -> if (inGroup) onCommand(event.command)
            else -> Unit
        }
    }

    private fun onUpdate(update: SyncPlayUpdate) {
        when (update) {
            is SyncPlayUpdate.Joined -> {
                _group.value = update.group
                notice("Watching together in ${update.group.name}")
                startClock()
            }
            SyncPlayUpdate.Left -> if (inGroup) {
                left()
                notice("Left the group")
            }
            is SyncPlayUpdate.UserJoined -> {
                _group.value = _group.value?.let { it.copy(participants = it.participants + update.name) }
                notice("${update.name} joined")
            }
            is SyncPlayUpdate.UserLeft -> {
                _group.value = _group.value?.let { it.copy(participants = it.participants - update.name) }
                notice("${update.name} left")
            }
            is SyncPlayUpdate.State -> {
                _group.value = _group.value?.copy(state = update.state)
                if (update.state == GroupState.WAITING) notice("Waiting for everyone to catch up…")
            }
            is SyncPlayUpdate.Queue -> {
                _queue.value = update.queue
                if (update.queue.changesItem) follow(update.queue)
            }
            is SyncPlayUpdate.Refused -> {
                notice(
                    when (update.reason) {
                        "LibraryAccessDenied" -> "Someone in the group can't see this item"
                        "GroupDoesNotExist" -> "That group has ended"
                        else -> "Not in a group"
                    },
                )
                if (update.reason != "LibraryAccessDenied") left()
            }
        }
    }

    /** Puts the group's current item on screen, paused at its start, then reports ready. */
    private fun follow(queue: SyncPlayQueue) {
        val itemId = queue.currentItemId ?: return
        stopTiming()
        val p = player
        if (p == null) {
            _openRequests.tryEmit(queue)
            return
        }
        awaitingReady = true
        if (p.itemId == itemId) {
            p.pause()
            p.seek(queue.startMs)
        } else {
            p.load(itemId, queue.startMs)
        }
        onPlayerState()
    }

    private fun onCommand(command: SyncPlayCommand) {
        val p = player ?: return
        stopTiming()
        val atLocal = command.whenMs?.let { it - clockOffsetMs } ?: now()
        when (command.command) {
            "Unpause" -> commandJob = scope.launch {
                val start = command.positionMs ?: p.positionMs
                val wait = atLocal - now()
                if (wait > 0) {
                    seekIfOff(p, start)
                    delay(wait)
                } else {
                    // Late (it took a while to get here): join where the others are now.
                    seekIfOff(p, start - wait)
                }
                p.play()
                playingFrom = start to atLocal
                keepInStep()
            }
            "Pause" -> commandJob = scope.launch {
                delay((atLocal - now()).coerceAtLeast(0))
                p.pause()
                command.positionMs?.let { seekIfOff(p, it) }
            }
            "Seek" -> {
                p.pause()
                command.positionMs?.let(p::seek)
                awaitingReady = true
                onPlayerState()
            }
            "Stop" -> {
                p.pause()
                p.stop()
            }
        }
    }

    /**
     * Seeks only when the player is noticeably elsewhere: a seek makes it buffer, and buffering
     * while the group plays would pause everyone.
     */
    private fun seekIfOff(p: SyncPlayer, positionMs: Long) {
        if (kotlin.math.abs(p.positionMs - positionMs) > SEEK_TOLERANCE_MS) ownSeek(p, positionMs)
    }

    private fun ownSeek(p: SyncPlayer, positionMs: Long) {
        ownSeekAt = now()
        p.seek(positionMs)
    }

    private fun stopTiming() {
        commandJob?.cancel()
        driftJob?.cancel()
        playingFrom = null
        player?.setRate(1f)
    }

    /** While playing, compares the player with where the group is and corrects drift. */
    private fun keepInStep() {
        driftJob?.cancel()
        driftJob = scope.launch {
            var rate = 1f
            while (isActive) {
                delay(DRIFT_CHECK_MS)
                val p = player ?: break
                val (from, at) = playingFrom ?: break
                if (!p.isReady || !p.isPlaying) continue
                val expected = from + (now() - at)
                val newRate = when (val fix = DriftFix.of(p.positionMs, expected)) {
                    DriftFix.None -> 1f
                    is DriftFix.Rate -> fix.rate
                    is DriftFix.Seek -> {
                        ownSeek(p, fix.positionMs)
                        1f
                    }
                }
                if (newRate != rate) {
                    rate = newRate
                    p.setRate(rate)
                }
            }
        }
    }

    // ---- The player ----

    /**
     * A player opened on [itemId]. If it's what the group is on, it follows the group; otherwise
     * the group switches to it (everyone else follows), from [startMs].
     */
    fun attach(p: SyncPlayer, itemId: String, startMs: Long) {
        player = p
        val queue = _queue.value
        if (queue != null && queue.currentItemId == itemId) {
            follow(queue)
        } else {
            send(SyncPlayRequest.SetQueue(listOf(itemId), 0, startMs))
        }
    }

    fun detach(p: SyncPlayer) {
        if (player !== p) return
        stopTiming()
        player = null
        awaitingReady = false
    }

    /** The player started or stopped buffering, or finished loading. */
    fun onPlayerState() {
        val p = player ?: return
        val queue = _queue.value ?: return
        val playlistItemId = queue.currentPlaylistItemId ?: return
        if (p.itemId != queue.currentItemId) return
        if (p.isReady && awaitingReady) {
            awaitingReady = false
            send(SyncPlayRequest.Status(buffering = false, whenMs = serverNow(), positionMs = p.positionMs, isPlaying = p.isPlaying, playlistItemId = playlistItemId))
        } else if (!p.isReady && !awaitingReady && _group.value?.state == GroupState.PLAYING && now() - ownSeekAt > OWN_SEEK_GRACE_MS) {
            // Stalled mid-play: the others wait for us.
            awaitingReady = true
            send(SyncPlayRequest.Status(buffering = true, whenMs = serverNow(), positionMs = p.positionMs, isPlaying = true, playlistItemId = playlistItemId))
        }
    }

    /** The player's own controls ask the group rather than acting alone. */
    fun requestPlay() = send(SyncPlayRequest.Unpause)
    fun requestPause() = send(SyncPlayRequest.Pause)
    fun requestSeek(positionMs: Long) = send(SyncPlayRequest.Seek(positionMs.coerceAtLeast(0)))

    /** The current item finished here: move the group on (it stops when the queue is done). */
    fun onEnded() {
        _queue.value?.currentPlaylistItemId?.let { send(SyncPlayRequest.NextItem(it)) }
    }

    private fun send(request: SyncPlayRequest) {
        scope.launch {
            try {
                server.syncPlay(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice("Couldn't reach the group: ${e.message ?: "error"}")
            }
        }
    }

    // ---- Clock ----

    private fun startClock() {
        clockJob?.cancel()
        clockJob = scope.launch {
            while (isActive && inGroup) {
                measureClock()?.let { runCatching { server.syncPlay(SyncPlayRequest.Ping(it.roundTripMs)) } }
                delay(CLOCK_INTERVAL_MS)
            }
        }
    }

    private suspend fun measureClock(): ClockMeasurement? {
        val samples = (1..CLOCK_SAMPLES).mapNotNull {
            val sent = now()
            runCatching { server.serverTime() }.getOrNull()?.let { ClockMeasurement.of(sent, it, now()) }
        }
        return ClockMeasurement.best(samples)?.also { clockOffsetMs = it.offsetMs }
    }

    private fun left() {
        stopTiming()
        clockJob?.cancel()
        _group.value = null
        _queue.value = null
        awaitingReady = false
    }

    private fun notice(text: String) {
        _notices.tryEmit(text)
    }

    private companion object {
        const val DRIFT_CHECK_MS = 1_000L
        const val CLOCK_INTERVAL_MS = 60_000L
        const val CLOCK_SAMPLES = 3
        const val SEEK_TOLERANCE_MS = 500L
        const val OWN_SEEK_GRACE_MS = 2_000L
    }
}
