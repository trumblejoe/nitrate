package dev.nitrate.core.partners

import kotlin.math.abs

/**
 * One person in a Group Watch Session, as the server last heard ([positionMs] already moved on to
 * [GroupWatchSession.serverTimeMs] while playing).
 */
data class SessionMember(
    val id: String,
    val name: String,
    val me: Boolean,
    val positionMs: Long,
    val paused: Boolean = false,
    /** Sets the group's pace: whoever started it while they're in it, else who joined first. */
    val leader: Boolean = false,
    /** Joined in time to start together. */
    val together: Boolean = true,
)

/**
 * A Group Watch Session (Companion feature `sessions`): a watch group watching one part ([itemId])
 * at the same moment, each on their own player. Times are the server's clock ([serverTimeMs] is
 * its clock as it answered; [SessionClock] turns them into this device's).
 */
data class GroupWatchSession(
    val id: String,
    val groupId: String,
    val itemId: String,
    /** "S1:E4", or a film's name. */
    val partLabel: String?,
    /** The group's titles' names. */
    val title: String,
    val startedBy: String,
    val startedByMe: Boolean,
    val state: String,
    /** The moment every player starts (server clock; null while it gathers). */
    val startAtMs: Long?,
    /** Where in the part everyone starts. */
    val startMs: Long,
    /** Where the group was at [serverTimeMs]. */
    val positionMs: Long,
    val paused: Boolean,
    /** Until when joining still starts together (server clock). */
    val gatherUntilMs: Long,
    val members: List<SessionMember>,
    /** Whether the user is in it. */
    val joined: Boolean,
    val serverTimeMs: Long,
) {
    val live: Boolean get() = state != ENDED

    /** Joining now starts together with the rest (it hasn't started yet). */
    val startsTogether: Boolean get() = state == WAITING || state == STARTING

    /** Everyone in it but the user. */
    val others: List<SessionMember> get() = members.filter { !it.me }

    val me: SessionMember? get() = members.firstOrNull { it.me }

    /** Who sets the pace. */
    val leader: SessionMember? get() = members.firstOrNull { it.leader }

    /** What it plays, for prompts: "S1:E4 of The Bear", or the title. */
    val what: String get() = listOfNotNull(partLabel, title.takeIf { it.isNotBlank() }).joinToString(" of ").ifEmpty { "something" }

    companion object {
        const val WAITING = "waiting"
        const val STARTING = "starting"
        const val PLAYING = "playing"
        const val ENDED = "ended"

        /** The Companion feature. */
        const val FEATURE = "sessions"
    }
}

/** A join's answer: the session, where this player starts, and whether it starts together with the rest (at the countdown). */
data class SessionJoin(val session: GroupWatchSession, val startMs: Long, val together: Boolean)

/**
 * What the Companion told this app about a Group Watch Session (a "Nitrate.Party" message of kind
 * `session`): [event] ([STARTED], [COUNTDOWN], [JOINED], [LEFT], [PAUSE], [PLAY], [SEEK], [ENDED]),
 * by whom ([byName], [byMe]), where ([positionMs]) and the session as the user sees it.
 */
data class SessionNews(
    val event: String,
    val groupId: String,
    val seriesName: String,
    val others: List<String>,
    val byName: String?,
    val byMe: Boolean,
    val positionMs: Long,
    val session: GroupWatchSession,
) {
    companion object {
        const val KIND = "session"
        const val STARTED = "started"
        const val COUNTDOWN = "countdown"
        const val JOINED = "joined"
        const val LEFT = "left"
        const val PAUSE = "pause"
        const val PLAY = "play"
        const val SEEK = "seek"
        const val ENDED = "ended"

        fun decode(text: String): SessionNews? = dev.nitrate.core.jellyfin.decodeSessionNews(text)
    }
}

/**
 * The difference between the server's clock and this device's, from answers that carry the
 * server's time: the server's time is taken as the middle of the round trip, and the sample with
 * the shortest round trip so far wins (the least room for error).
 */
class SessionClock(offsetMs: Long = 0) {
    /** Server time minus this device's time. */
    var offsetMs: Long = offsetMs
        private set
    private var bestTripMs = Long.MAX_VALUE

    /** An answer carrying the server's time [serverMs], asked at [sentMs] and answered at [receivedMs] (this device's clock). */
    fun sample(serverMs: Long, sentMs: Long, receivedMs: Long) {
        val trip = (receivedMs - sentMs).coerceAtLeast(0)
        if (trip > bestTripMs) return
        bestTripMs = trip
        offsetMs = serverMs - (sentMs + receivedMs) / 2
    }

    /** The server's time when this device says [localMs]. */
    fun serverAt(localMs: Long): Long = localMs + offsetMs

    /** This device's time for the server's [serverMs]. */
    fun localAt(serverMs: Long): Long = serverMs - offsetMs
}

/**
 * Something about the others in a Group Watch Session the player shows over the video, with the
 * one thing it offers ([action], null: only words). Never acted on by itself: pausing or jumping
 * because someone else did is the user's choice.
 */
sealed interface SessionNotice {
    val text: String
    val action: String?

    /** How long it shows. */
    val showMs: Long get() = 10_000

    /** "Sam paused · Pause too". */
    data class Paused(val by: String, val positionMs: Long) : SessionNotice {
        override val text get() = "$by paused"
        override val action get() = "Pause too"
    }

    /** "Sam is playing again · Play too". */
    data class Played(val by: String) : SessionNotice {
        override val text get() = "$by is playing again"
        override val action get() = "Play too"
    }

    /** "Sam jumped to 24:10 · Jump too". */
    data class Jumped(val by: String, val positionMs: Long) : SessionNotice {
        override val text get() = "$by jumped to ${SessionSync.clock(positionMs)}"
        override val action get() = "Jump too"
    }

    /** "12 s behind Sam · Catch up" / "12 s ahead of Sam · Go back to Sam": [targetMs] is where they are. */
    data class Drift(val name: String, val apartMs: Long, val targetMs: Long) : SessionNotice {
        val behind: Boolean get() = apartMs > 0
        override val text get() = SessionSync.apart(abs(apartMs)) + if (behind) " behind $name" else " ahead of $name"
        override val action get() = if (behind) "Catch up" else "Go back to $name"
        override val showMs: Long get() = 15_000
    }

    /** "Suzy joined". */
    data class Joined(val name: String) : SessionNotice {
        override val text get() = "$name joined"
        override val action: String? get() = null
        override val showMs: Long get() = 5_000
    }

    /** "Suzy left". */
    data class Left(val name: String) : SessionNotice {
        override val text get() = "$name left"
        override val action: String? get() = null
        override val showMs: Long get() = 5_000
    }

    /** "Tommy ended the Group Watch Session": the player carries on alone. */
    data class Ended(val by: String?) : SessionNotice {
        override val text get() = (by?.let { "$it ended the Group Watch Session" } ?: "The Group Watch Session ended") + ". You carry on by yourself."
        override val action: String? get() = null
    }
}

/** The pure rules of keeping a Group Watch Session's players together. */
object SessionSync {
    /** Each player reports where it is this often (and at once on a pause, a play after one, or a jump). */
    const val REPORT_MS = 10_000L

    /** Further apart than this from the group's leader, the player offers to catch up. */
    const val DRIFT_MS = 10_000L

    /** After "Catch up" (or a jump) the drift isn't offered again for this long, so it settles first. */
    const val SETTLE_MS = 20_000L

    /** Where member [m] of [s] is at the server's [serverNowMs]. */
    fun positionOf(s: GroupWatchSession, m: SessionMember, serverNowMs: Long): Long =
        if (m.paused || !started(s, serverNowMs)) m.positionMs
        else m.positionMs + (serverNowMs - maxOf(s.serverTimeMs, s.startAtMs ?: s.serverTimeMs)).coerceAtLeast(0)

    /** Where the group is at the server's [serverNowMs]: its start until the countdown ends, then its leader's pace. */
    fun groupPosition(s: GroupWatchSession, serverNowMs: Long): Long = when {
        !started(s, serverNowMs) -> s.startMs
        s.paused -> s.positionMs
        else -> s.positionMs + (serverNowMs - maxOf(s.serverTimeMs, s.startAtMs ?: s.serverTimeMs)).coerceAtLeast(0)
    }

    /** Whether the countdown is over at [serverNowMs]. */
    fun started(s: GroupWatchSession, serverNowMs: Long): Boolean = s.startAtMs?.let { serverNowMs >= it } ?: false

    /** Whole seconds left of the countdown ("3, 2, 1"), null outside it. */
    fun countdown(s: GroupWatchSession, serverNowMs: Long): Int? {
        val at = s.startAtMs ?: return null
        val left = at - serverNowMs
        return if (left <= 0) null else ((left + 999) / 1000).toInt()
    }

    /**
     * Where a player joining [s] at [serverNowMs] starts: everyone in the first minute starts
     * together at the start; later, where the group is now, or the beginning ([fromStart]).
     */
    fun joinAt(s: GroupWatchSession, serverNowMs: Long, fromStart: Boolean): Long = when {
        s.startsTogether -> s.startMs
        fromStart -> 0
        else -> groupPosition(s, serverNowMs)
    }

    /**
     * Whom this player keeps pace with: the leader, unless that's the user (then nobody: the
     * others keep pace with them); with no leader, the one in the middle of the others.
     */
    fun reference(s: GroupWatchSession, serverNowMs: Long): SessionMember? {
        val leader = s.leader
        if (leader != null) return leader.takeIf { !it.me }
        val others = s.others.sortedBy { positionOf(s, it, serverNowMs) }
        return others.getOrNull(others.size / 2)
    }

    /**
     * "12 s behind Sam · Catch up": this player at [myPositionMs] (playing, not [myPaused]) is more
     * than [DRIFT_MS] from whom it keeps pace with ([reference], also playing). Null when close
     * enough, before the start, or when either is paused (a pause has its own notice).
     */
    fun drift(s: GroupWatchSession, myPositionMs: Long, myPaused: Boolean, serverNowMs: Long): SessionNotice.Drift? {
        if (myPaused || !s.live || !started(s, serverNowMs)) return null
        val ref = reference(s, serverNowMs) ?: return null
        if (ref.paused) return null
        val theirs = positionOf(s, ref, serverNowMs)
        val apart = theirs - myPositionMs
        if (abs(apart) <= DRIFT_MS) return null
        // Rounded to whole seconds, as said.
        return SessionNotice.Drift(ref.name, apart / 1000 * 1000, theirs)
    }

    /** What the player shows for [news] (null: nothing, e.g. what the user did themselves). */
    fun noticeOf(news: SessionNews): SessionNotice? {
        if (news.byMe && news.event != SessionNews.ENDED) return null
        val by = news.byName ?: "Someone"
        return when (news.event) {
            SessionNews.PAUSE -> SessionNotice.Paused(by, news.positionMs)
            SessionNews.PLAY -> SessionNotice.Played(by)
            SessionNews.SEEK -> SessionNotice.Jumped(by, news.positionMs)
            SessionNews.JOINED -> SessionNotice.Joined(by)
            SessionNews.LEFT -> SessionNotice.Left(by)
            SessionNews.ENDED -> if (news.byMe) null else SessionNotice.Ended(news.byName)
            else -> null
        }
    }

    /** "24:10", "1:02:03". */
    fun clock(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val sec = total % 60
        val ss = sec.toString().padStart(2, '0')
        return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$ss" else "$m:$ss"
    }

    /** "12 s", "3 min", "1 min 20 s". */
    fun apart(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "$s s"
            s % 60 == 0L -> "${s / 60} min"
            else -> "${s / 60} min ${s % 60} s"
        }
    }
}

/** A choice of [SessionPrompt]. */
sealed interface SessionChoice {
    val label: String

    /** Join: together at the countdown, or at where the group is now. */
    data class Join(override val label: String = "Join") : SessionChoice

    /** "Start from the beginning" (a late joiner). */
    data object FromStart : SessionChoice { override val label = "Start from the beginning" }

    data object NotNow : SessionChoice { override val label = "Not now" }

    /** The starter: "Start now" without waiting for the rest. */
    data object StartNow : SessionChoice { override val label = "Start now" }
}

/**
 * "Tommy started the session · S1:E4 of The Bear": the join prompt (both apps; outside the
 * player a dialog/sheet, in another player a note that never takes focus). Late: "Join at 12:30"
 * and "Start from the beginning".
 */
data class SessionPrompt(val sessionId: String, val title: String, val text: String, val choices: List<SessionChoice>) {
    companion object {
        const val TITLE = "Group Watch Session"

        /** The prompt for [s] at the server's [serverNowMs]; null when the user is in it already or it's over. */
        fun of(s: GroupWatchSession, serverNowMs: Long, easy: Boolean = false): SessionPrompt? {
            if (!s.live || s.joined) return null
            val who = if (s.startedByMe) "You" else s.startedBy
            return if (s.startsTogether) SessionPrompt(
                s.id, TITLE, "$who started the session · ${s.what}" + with(s), listOf(SessionChoice.Join(), SessionChoice.NotNow),
            ) else {
                val at = SessionSync.groupPosition(s, serverNowMs)
                SessionPrompt(
                    s.id, TITLE, "$who started the session · ${s.what}. They're at ${SessionSync.clock(at)}.",
                    if (easy) listOf(SessionChoice.Join("Join at ${SessionSync.clock(at)}"), SessionChoice.NotNow)
                    else listOf(SessionChoice.Join("Join at ${SessionSync.clock(at)}"), SessionChoice.FromStart, SessionChoice.NotNow),
                )
            }
        }

        private fun with(s: GroupWatchSession): String =
            s.others.filter { it.name != s.startedBy }.map { it.name }.takeIf { it.isNotEmpty() }?.let { " · with " + WatchPartners.names(it) }.orEmpty()

        /**
         * What the starter sees while it gathers: "Waiting for Tommy & Suzy · Start now"
         * (null once it's counting down or playing, or for anyone else).
         */
        fun waiting(s: GroupWatchSession, group: WatchGroup?): String? {
            if (s.state != GroupWatchSession.WAITING) return null
            val inIt = s.members.map { it.id }.toSet()
            val missing = group?.others.orEmpty().filter { it.id !in inIt }.map { it.name }
            return if (missing.isEmpty()) "Starting together…" else "Waiting for " + WatchPartners.names(missing) + " to join"
        }
    }
}
