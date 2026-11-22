package dev.nitrate.core.playback

/** What knocked sound and picture out of step while playing, in plain words. */
enum class SyncTrouble(val title: String) {
    /** The sound ran out of data several times: each gap can leave it a little off the picture. */
    SOUND_GAPS("Sound kept running dry"),
    /** Frames went to the screen after their time, again and again: the picture fell behind the sound. */
    PICTURE_LATE("Picture behind the sound"),
    /** The sound output failed and restarted. */
    SOUND_RESTARTED("Sound restarted"),
    /** The file's own timing jumped (a gap or a step in its timestamps). */
    TIMING_JUMP("The file's timing jumped"),
}

/** A sync problem seen while playing: what, in a sentence, and whether having the server convert it helps. */
data class SyncWarning(val trouble: SyncTrouble, val text: String, val convertHelps: Boolean)

/**
 * Watches the player's signals for sound and picture falling out of step. Media3 times every
 * frame against the sound, so a healthy file on a healthy device stays in step; what knocks it
 * loose leaves traces: the sound running dry (underruns), frames rendered late again and again,
 * the sound output restarting, or the file's timestamps jumping. The first moments after starting
 * or seeking are left alone (everything settles then). Not thread-safe: feed it from one thread.
 */
class SyncWatch {
    private var settleUntil = 0L
    private val underruns = ArrayDeque<Long>()
    private var lateReports = 0
    private var lateSumUs = 0L
    private var current: SyncWarning? = null

    val warning: SyncWarning? get() = current

    /** Playback started or jumped: give it [SETTLE_MS] to settle. */
    fun settling(atMs: Long) {
        settleUntil = atMs + SETTLE_MS
        underruns.clear()
        lateReports = 0
        lateSumUs = 0
    }

    /** After a reload that lined them up again: forget what was seen. */
    fun reset(atMs: Long) {
        current = null
        settling(atMs)
    }

    private fun settled(atMs: Long) = atMs >= settleUntil

    /** The sound output ran out of data. */
    fun underrun(atMs: Long) {
        if (!settled(atMs)) return
        underruns.addLast(atMs)
        while (underruns.isNotEmpty() && atMs - underruns.first() > WINDOW_MS) underruns.removeFirst()
        if (underruns.size >= UNDERRUNS) raise(
            SyncWarning(
                SyncTrouble.SOUND_GAPS,
                "The sound ran out of data ${underruns.size} times in a minute, which can leave it a little off the picture. " +
                    "Usually a slow network or a busy device; reloading from here lines them up again.",
                convertHelps = true,
            ),
        )
    }

    /**
     * Media3's frame timing for a batch of frames: [totalOffsetUs] over [frames], positive when
     * frames were ready ahead of their time, negative when they went out late.
     */
    fun frameTiming(totalOffsetUs: Long, frames: Int, atMs: Long) {
        if (frames <= 0 || !settled(atMs)) return
        val average = totalOffsetUs / frames
        if (average > -LATE_US) {
            lateReports = 0
            lateSumUs = 0
            return
        }
        lateReports++
        lateSumUs += average
        if (lateReports >= LATE_REPORTS) {
            val behindMs = -lateSumUs / lateReports / 1000
            raise(
                SyncWarning(
                    SyncTrouble.PICTURE_LATE,
                    "Frames keep reaching the screen about $behindMs ms after their time, so the picture runs behind the sound. " +
                        "This device is struggling to decode it; having the server convert it to something lighter keeps them together.",
                    convertHelps = true,
                ),
            )
        }
    }

    /** The sound output failed (and Media3 set it up again). */
    fun soundRestarted(atMs: Long) {
        if (!settled(atMs)) return
        raise(SyncWarning(SyncTrouble.SOUND_RESTARTED, "The sound output stopped and started again, which can leave it off the picture. Reloading from here lines them up again.", convertHelps = false))
    }

    /** The player's position jumped by itself (not a seek or the next item): the file's timestamps. */
    fun timingJump(atMs: Long) {
        if (!settled(atMs)) return
        raise(
            SyncWarning(
                SyncTrouble.TIMING_JUMP,
                "The file's timing jumped by itself, a sign of a gap or a fault in how it was made. If sound and picture drift apart, " +
                    "having the server convert it rebuilds the timing; Library health lists files the server found like this.",
                convertHelps = true,
            ),
        )
    }

    /** The more serious warning stays (late picture over the others, which a reload fixes). */
    private fun raise(w: SyncWarning) {
        val now = current
        if (now == null || rank(w.trouble) >= rank(now.trouble)) current = w
    }

    private fun rank(t: SyncTrouble) = when (t) {
        SyncTrouble.SOUND_RESTARTED -> 0
        SyncTrouble.SOUND_GAPS -> 1
        SyncTrouble.TIMING_JUMP -> 2
        SyncTrouble.PICTURE_LATE -> 3
    }

    companion object {
        const val SETTLE_MS = 4_000L
        const val WINDOW_MS = 60_000L
        const val UNDERRUNS = 3
        /** Frames this late on average (40 ms) are visibly behind the sound. */
        const val LATE_US = 40_000L
        const val LATE_REPORTS = 3
    }
}
