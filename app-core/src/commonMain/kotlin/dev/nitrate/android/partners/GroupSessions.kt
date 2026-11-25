package dev.nitrate.android.partners

import dev.nitrate.core.MediaServer
import dev.nitrate.core.partners.GroupWatchSession
import dev.nitrate.core.partners.SessionChoice
import dev.nitrate.core.partners.SessionClock
import dev.nitrate.core.partners.SessionJoin
import dev.nitrate.core.partners.SessionNews
import dev.nitrate.core.partners.SessionNotice
import dev.nitrate.core.partners.SessionPrompt
import dev.nitrate.core.partners.SessionSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Play [itemId] from [startMs] for a Group Watch Session this device joined (the app opens its player). */
data class SessionOpen(val itemId: String, val startMs: Long)

/**
 * The apps' side of Group Watch Sessions (Companion feature `sessions`): this device's session
 * ([current]), the join prompt for one someone started ([asking], from the Companion's news on
 * the server's own connection; nothing polled), and what the players hear about the others
 * ([news]). Every call's answer carries the server's clock, which [clock] keeps the offset of.
 * Starting one ("Start a Group Watch Session") or joining one asks the app to open its player
 * ([openRequests]); the player then keeps pace through [SessionPlayback].
 */
class GroupSessions(
    private val server: MediaServer,
    private val scope: CoroutineScope,
    private val now: () -> Long,
) {
    val clock = SessionClock()

    private val currentState = MutableStateFlow<GroupWatchSession?>(null)

    /** The Group Watch Session this device is in (null: none). */
    val current: StateFlow<GroupWatchSession?> = currentState

    private val askingState = MutableStateFlow<GroupWatchSession?>(null)

    /** A session someone in the user's groups started, to offer joining ([SessionPrompt.of]). */
    val asking: StateFlow<GroupWatchSession?> = askingState

    private val newsFlow = MutableSharedFlow<SessionNews>(extraBufferCapacity = 16)

    /** What the Companion said about this device's session (pauses, jumps, joins…), for the player's notices. */
    val news: SharedFlow<SessionNews> = newsFlow

    private val opens = MutableSharedFlow<SessionOpen>(extraBufferCapacity = 4)

    /** The player to open for a session joined from the prompt (or a Group Watch Session's Join). */
    val openRequests: SharedFlow<SessionOpen> = opens

    /** Where this device's player starts in [current] (null: not said; the session's start). */
    var startMs: Long? = null
        private set

    /** Whether this device joined in time to start together. */
    var together: Boolean = true
        private set

    private var gatherJob: Job? = null

    /** The server's time now, by this device's clock and the offset. */
    fun serverNow(): Long = clock.serverAt(now())

    /** The Companion told this app about a session. */
    fun heard(news: SessionNews) {
        val s = news.session
        val mine = currentState.value
        if (mine != null && mine.id == s.id) {
            currentState.value = if (s.live) s.copy(joined = true) else null
            newsFlow.tryEmit(news)
            return
        }
        when {
            news.event == SessionNews.STARTED && !news.byMe && !s.joined && mine == null -> askingState.value = s
            askingState.value?.id == s.id -> askingState.value = s.takeIf { it.live && !it.joined }
        }
    }

    /** Offers joining [session] by hand (a group's card or Settings → Watch groups): the same prompt as its news. */
    fun offer(session: GroupWatchSession) {
        if (session.live && !session.joined) askingState.value = session
    }

    fun dismiss() { askingState.value = null }

    /** "Start a Group Watch Session": starts a Group Watch Session of [groupId] on [itemId] (or joins the group's live one). */
    suspend fun start(groupId: String, itemId: String, startMs: Long = 0): SessionJoin {
        val j = timed { server.startWatchSession(groupId, itemId, startMs) }
        took(j)
        return j
    }

    /** The prompt's answer: join (together, or late at the group's position / from the beginning), then the player opens. */
    suspend fun choose(session: GroupWatchSession, choice: SessionChoice): String? {
        askingState.value = null
        if (choice == SessionChoice.NotNow) return null
        // A second tap while the first is joining: one join, one player (two stacked players
        // left the session as the first one closed).
        if (!joining.tryLock()) return null
        return try { joinNow(session, choice) } finally { joining.unlock() }
    }

    private val joining = kotlinx.coroutines.sync.Mutex()

    private suspend fun joinNow(session: GroupWatchSession, choice: SessionChoice): String? {
        return runCatching {
            val j = timed { server.joinWatchSession(session.id, fromStart = choice == SessionChoice.FromStart) }
            took(j)
            opens.tryEmit(SessionOpen(j.session.itemId, j.startMs))
            Unit
        }.exceptionOrNull()?.let { it.message ?: "Couldn't join the Group Watch Session" }
    }

    /** Joins [sessionId] (a Group Watch Session's Join for one already going) and opens its player. */
    suspend fun join(sessionId: String): String? = runCatching {
        val s = timed { server.watchSession(sessionId) }
        choose(s, SessionChoice.Join())?.let { error(it) }
        Unit
    }.exceptionOrNull()?.let { it.message ?: "Couldn't join the Group Watch Session" }

    /** The starter's "Start now" (without waiting for the rest). */
    suspend fun startNow() {
        val s = currentState.value ?: return
        runCatching { timed { server.startWatchSessionNow(s.id) } }.onSuccess { update(it) }
    }

    /** Where this player is (every [SessionSync.REPORT_MS], and at once with [event]); the session as it stands comes back. */
    suspend fun report(positionMs: Long, paused: Boolean, event: String? = null): GroupWatchSession? {
        val s = currentState.value ?: return null
        return runCatching { timed { server.reportWatchSession(s.id, positionMs, paused, event) } }.getOrNull()?.also { update(it) }
    }

    /** Leaves this device's session (the player closed or moved on). */
    fun leave() {
        val s = currentState.value ?: return
        currentState.value = null
        startMs = null
        gatherJob?.cancel()
        scope.launch { runCatching { server.leaveWatchSession(s.id) } }
    }

    /** The starter ends it for everyone. */
    fun end() {
        val s = currentState.value ?: return
        currentState.value = null
        startMs = null
        gatherJob?.cancel()
        scope.launch { runCatching { server.endWatchSession(s.id) } }
    }

    /** Forgets everything (switching or signing out: it belonged to that account). */
    fun clear() {
        leave()
        askingState.value = null
    }

    /** The live sessions of the user's groups (to offer joining from a card). */
    suspend fun live(): List<GroupWatchSession> = runCatching { timed { server.watchSessions() } }.getOrDefault(emptyList())

    private fun took(j: SessionJoin) {
        startMs = j.startMs
        together = j.together
        update(j.session.copy(joined = true))
        askingState.value = null
    }

    private fun update(s: GroupWatchSession) {
        if (!s.live) { currentState.value = null; gatherJob?.cancel(); return }
        currentState.value = s.copy(joined = true)
        // While it gathers, look again now and then in case the countdown's news is missed (it normally comes at once).
        if (s.state == GroupWatchSession.WAITING && gatherJob?.isActive != true) gatherJob = scope.launch {
            while (currentState.value?.state == GroupWatchSession.WAITING) {
                delay(GATHER_LOOK_MS)
                val id = currentState.value?.id ?: break
                runCatching { timed { server.watchSession(id) } }.onSuccess { if (currentState.value?.id == id) update(it) }
            }
        }
    }

    private suspend fun <T> timed(call: suspend () -> T): T {
        val sent = now()
        val r = call()
        val received = now()
        val serverMs = when (r) {
            is GroupWatchSession -> r.serverTimeMs
            is SessionJoin -> r.session.serverTimeMs
            is List<*> -> (r.firstOrNull() as? GroupWatchSession)?.serverTimeMs
            else -> null
        }
        if (serverMs != null && serverMs > 0) clock.sample(serverMs, sent, received)
        return r
    }

    companion object {
        /** How often a gathering session is looked at again, in case its news is missed. */
        const val GATHER_LOOK_MS = 10_000L
    }
}

/**
 * One player's part in this device's Group Watch Session, for [itemId]: holds the player at the
 * start until the countdown ends, then starts it at the agreed moment ([countdown]); reports where
 * it is every [SessionSync.REPORT_MS] and at once when the user pauses, plays or jumps (found by
 * watching [position]/[paused], so the players need no hooks of their own); shows what the others
 * do as [notice] ("Sam paused · Pause too", "12 s behind Sam · Catch up"), acted on only when the
 * user picks it ([act]). Each player is its own: nothing here pauses or jumps by itself.
 */
class SessionPlayback(
    private val sessions: GroupSessions,
    private val scope: CoroutineScope,
    val itemId: String,
    private val now: () -> Long,
    private val position: () -> Long,
    private val paused: () -> Boolean,
    private val play: () -> Unit,
    private val pause: () -> Unit,
    private val seekTo: (Long) -> Unit,
) {
    private val countdownState = MutableStateFlow<Int?>(null)

    /** "Starting in 3": whole seconds to the start moment (null outside the countdown). */
    val countdown: StateFlow<Int?> = countdownState

    private val waitingState = MutableStateFlow(false)

    /** Gathering: the start moment isn't set yet (the starter may "Start now"). */
    val waiting: StateFlow<Boolean> = waitingState

    private val noticeState = MutableStateFlow<SessionNotice?>(null)

    /** What the others did, offered (null: nothing to show). */
    val notice: StateFlow<SessionNotice?> = noticeState

    /** The session this player is in, as last heard. */
    val session: StateFlow<GroupWatchSession?> get() = sessions.current

    private var jobs = mutableListOf<Job>()
    private var settleUntil = 0L
    private var driftQuietUntil = 0L
    private var noticeJob: Job? = null
    private var lastPos = -1L
    private var lastAt = 0L
    private var lastPaused = false
    private var holding = false

    /** Whether this player belongs to this device's session. */
    val active: Boolean get() = sessions.current.value?.itemId == itemId

    /** The session this player began in: closing leaves only that one, never one joined since. */
    private var joinedId: String? = null

    /** Starts following the session (call once the player is ready). */
    fun begin() {
        if (!active) return
        joinedId = sessions.current.value?.id
        jobs += scope.launch { gate() }
        jobs += scope.launch { watch() }
        jobs += scope.launch { reports() }
        jobs += scope.launch { sessions.news.collect { heard(it) } }
    }

    /** The player is going (closed, or on to another item): leave the session. */
    fun close() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        if (active && sessions.current.value?.id == joinedId) sessions.leave()
    }

    /** The user picked the notice's action ("Pause too", "Jump too", "Catch up"…). */
    fun act(n: SessionNotice? = noticeState.value) {
        val s = sessions.current.value
        val serverNow = sessions.serverNow()
        when (n) {
            is SessionNotice.Paused -> pause()
            is SessionNotice.Played -> play()
            is SessionNotice.Jumped -> seekTo(n.positionMs + since(s, serverNow))
            is SessionNotice.Drift -> {
                seekTo((s?.let { SessionSync.drift(it, position(), paused(), serverNow)?.targetMs } ?: n.targetMs).coerceAtLeast(0))
                // Caught up while paused: on with them.
                if (paused()) play()
            }
            else -> Unit
        }
        settleUntil = now() + SessionSync.SETTLE_MS
        // What we did isn't the user's own pause/jump to tell the others about.
        lastPos = -1
        noticeState.value = null
    }

    /** Puts the notice away without acting on it (Back, or it timed out). */
    fun dismiss() {
        if (noticeState.value is SessionNotice.Drift) driftQuietUntil = now() + DRIFT_QUIET_MS
        noticeState.value = null
    }

    /** The starter's "Start now". */
    fun startNow() { scope.launch { sessions.startNow() } }

    // Time since the others' position was said, while the group plays.
    private fun since(s: GroupWatchSession?, serverNow: Long): Long =
        if (s == null || s.paused) 0 else (serverNow - s.serverTimeMs).coerceAtLeast(0)

    private fun show(n: SessionNotice) {
        noticeState.value = n
        noticeJob?.cancel()
        noticeJob = scope.launch {
            delay(n.showMs)
            if (noticeState.value == n) dismiss()
        }
    }

    private fun heard(news: SessionNews) {
        if (news.session.itemId != itemId) return
        SessionSync.noticeOf(news)?.let(::show)
    }

    /** Holds the player at the start until the moment, then starts it there (a moment late: that much further on). */
    private suspend fun gate() {
        while (true) {
            val s = sessions.current.value ?: break
            if (s.itemId != itemId || !s.live) break
            val serverNow = sessions.serverNow()
            waitingState.value = s.state == GroupWatchSession.WAITING
            val started = SessionSync.started(s, serverNow)
            if (!started && sessions.together) {
                countdownState.value = SessionSync.countdown(s, serverNow)
                if (!holding) {
                    holding = true
                    seekTo(s.startMs)
                }
                // Held at the start (the player may try to start itself as it gets ready).
                if (!paused()) pause()
                delay(100)
                continue
            }
            countdownState.value = null
            waitingState.value = false
            if (holding || sessions.together) {
                // Joined in time: start where the group is (exactly the start, unless this player was late to the moment).
                seekTo(SessionSync.groupPosition(s, serverNow))
                play()
            }
            holding = false
            lastPos = -1
            break
        }
        countdownState.value = null
        waitingState.value = false
    }

    /** Finds the user's own pauses, plays and jumps by watching the player, and tells the session at once. */
    private suspend fun watch() {
        while (true) {
            delay(WATCH_MS)
            if (holding || !active) continue
            val p = position()
            val isPaused = paused()
            val t = now()
            if (lastPos >= 0) {
                val expected = lastPos + if (lastPaused) 0 else (t - lastAt)
                val event = when {
                    isPaused && !lastPaused -> SessionNews.PAUSE
                    !isPaused && lastPaused -> SessionNews.PLAY
                    kotlin.math.abs(p - expected) > JUMP_MS -> SessionNews.SEEK
                    else -> null
                }
                if (event != null && t >= settleUntil) {
                    sessions.report(p, isPaused, event)
                    if (event == SessionNews.SEEK) settleUntil = t + SessionSync.SETTLE_MS
                }
                // The others' pause offered and the user paused anyway: it's done.
                if (event == SessionNews.PAUSE && noticeState.value is SessionNotice.Paused) noticeState.value = null
                if (event == SessionNews.PLAY && noticeState.value is SessionNotice.Played) noticeState.value = null
            }
            lastPos = p
            lastAt = t
            lastPaused = isPaused
        }
    }

    /** Every [SessionSync.REPORT_MS]: where this player is; then whether it's drifted from the group. */
    private suspend fun reports() {
        while (true) {
            delay(SessionSync.REPORT_MS)
            if (holding || !active) continue
            val s = sessions.report(position(), paused()) ?: continue
            val t = now()
            if (t < settleUntil || t < driftQuietUntil) continue
            val drift = SessionSync.drift(s, position(), paused(), sessions.serverNow())
            val shown = noticeState.value
            if (drift != null && (shown == null || shown is SessionNotice.Drift)) show(drift)
            else if (drift == null && shown is SessionNotice.Drift) noticeState.value = null
        }
    }

    companion object {
        const val WATCH_MS = 500L

        /** A position this far from where it should be is a jump. */
        const val JUMP_MS = 3_000L

        /** After putting a drift notice away, it isn't offered again for this long. */
        const val DRIFT_QUIET_MS = 60_000L
    }
}
