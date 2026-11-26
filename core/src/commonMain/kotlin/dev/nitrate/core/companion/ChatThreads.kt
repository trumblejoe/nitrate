package dev.nitrate.core.companion

import dev.nitrate.core.partners.PartyTime

/**
 * One conversation of a watch group on one of its titles (Companion `threads`), as the viewer sees
 * it on one part: everything any member writes on any part of that title, whenever they watch,
 * goes into the group's current thread. The group's creator can start a new one (a rewatch); the
 * older ones are kept and can be replayed.
 */
data class ChatThread(
    val id: String,
    /** When it was started. */
    val startedMs: Long = 0,
    /** How many of its messages on this part the viewer may read. */
    val count: Int = 0,
    /** The group's current thread: what's written goes here, and its messages pop up as they're reached. */
    val current: Boolean = false,
    /** The viewer made it a favourite: the server's time rules never delete it. */
    val favourite: Boolean = false,
)

/** One of the viewer's watch groups on a title, with its threads on it (the current one first). */
data class ThreadGroup(
    val groupId: String,
    /** The group's titles' names. */
    val title: String,
    /** The group's title holding this part. */
    val titleId: String,
    /** The viewer made the group: they may start a new thread. */
    val canStart: Boolean,
    val threads: List<ChatThread>,
) {
    val current: ChatThread? get() = threads.firstOrNull { it.current }
    val older: List<ChatThread> get() = threads.filter { !it.current }
}

/** A part's conversations for the viewer: their groups on it, each group's threads, and the current threads' messages they may read. */
data class TitleThreads(val groups: List<NoteGroup>, val threads: List<ThreadGroup>, val notes: List<TimelineNote>)

/**
 * A Group Watch Session's conversation on one title: the thread everyone there writes into
 * ([chatId], the group's current one), and the older thread someone there picked to replay for
 * everyone ([along], by [by]) with its messages as the viewer gets them (brought ones only for the session).
 */
data class SessionChat(val chatId: String?, val along: String?, val by: String?, val notes: List<TimelineNote>)

/** Threads' words and rules (no I/O). */
object Threads {
    /**
     * The day a thread was started, on the device's own calendar: "2026/11/22" ([offsetMinutes]: the
     * device's time zone's offset from UTC at that moment).
     */
    fun date(ms: Long, offsetMinutes: Int): String {
        val (y, m, d) = civil((ms + offsetMinutes * 60_000L).floorDiv(PartyTime.DAY_MS))
        return "$y/" + m.toString().padStart(2, '0') + "/" + d.toString().padStart(2, '0')
    }

    private fun civil(day: Long): Triple<Long, Int, Int> {
        // Days since 1970-01-01 to year, month, day (Howard Hinnant's algorithm).
        val z = day + 719_468
        val era = z.floorDiv(146_097)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val y = yoe + era * 400 + if (m <= 2) 1 else 0
        return Triple(y, m, d)
    }

    /** "14 messages", "1 message". */
    fun count(n: Int): String = if (n == 1) "1 message" else "$n messages"

    /** A thread's name: "Started 2026/10/02" ([offsetAt]: the device's offset from UTC in minutes at a moment). */
    fun name(thread: ChatThread, offsetAt: (Long) -> Int): String = "Started " + date(thread.startedMs, offsetAt(thread.startedMs))

    /** Its second line: "14 messages here" (on this part), "Nothing here yet". */
    fun detail(thread: ChatThread): String = if (thread.count == 0) "Nothing here yet" else count(thread.count) + " here"

    /** What a playback is in: "Group chat · Will", or a replay: "Replaying 2026/10/02 · Will". */
    fun line(replaying: String?, groups: List<ThreadGroup>, who: String?, offsetAt: (Long) -> Int): String {
        val older = replaying?.let { id -> groups.flatMap { it.threads }.firstOrNull { it.id == id } }
        val what = when {
            older != null -> "Replaying " + date(older.startedMs, offsetAt(older.startedMs))
            replaying != null -> "Replaying an earlier thread"
            else -> "Group chat"
        }
        return what + (who?.let { " · $it" } ?: "")
    }

    /** What the heart beside a thread says: "Favourite" or "Not a favourite". */
    fun heart(thread: ChatThread): String = if (thread.favourite) FAVOURITE else NOT_FAVOURITE

    const val FAVOURITE = "Favourite"
    const val NOT_FAVOURITE = "Not a favourite"

    /** What a playback shows: the replayed thread's messages, then the current thread's (here), each once. */
    fun showing(along: List<TimelineNote>, here: List<TimelineNote>): List<TimelineNote> {
        val ids = HashSet<String>()
        return (along + here).filter { ids.add(it.id) }.sortedWith(compareBy({ it.positionMs }, { it.atMs }))
    }
}
