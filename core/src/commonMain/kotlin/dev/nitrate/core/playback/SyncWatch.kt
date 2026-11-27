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
    /**
     * The player's idea of where the sound is crept away from real time while playing smoothly:
     * the picture follows that idea, so it drifted off the sound actually heard (typically sound
     * passed through to a receiver, whose timing the device reads poorly).
     */
    DRIFT("Sound and picture drifted apart"),
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

    // Drift: where the sound was (by the player) against real time, from a steady stretch's start.
    private var baseline: Pair<Long, Long>? = null
    private var carriedMs = 0L
    private var lastOffsetMs = 0L
    private var overSamples = 0
    private var steadySinceMs = 0L
    private var lastFixMs = Long.MIN_VALUE / 2
    private var fixes = 0
    private var context = ""
    // The last look's offset in this steady stretch (null: none yet), to tell a stall from drift.
    private var prevOffsetMs: Long? = null
    private var stallCount = 0

    /** How many times drift was fixed in this playback. */
    val driftFixes: Int get() = fixes

    /** How many times the sound held up for a moment (a jump, not drift: nothing to fix). */
    val stalls: Int get() = stallCount

    /** How far the last stall moved the clock, in ms. */
    var lastStallMs: Long = 0
        private set

    val warning: SyncWarning? get() = current

    /** Playback started or jumped: give it [SETTLE_MS] to settle. */
    fun settling(atMs: Long) {
        settleUntil = atMs + SETTLE_MS
        underruns.clear()
        lateReports = 0
        lateSumUs = 0
        // A seek or a start sets the sound output going afresh: drift is measured from here.
        restartDrift()
    }

    private fun restartDrift() {
        baseline = null
        carriedMs = 0
        lastOffsetMs = 0
        overSamples = 0
        prevOffsetMs = null
    }

    /** What the sound goes out as, for the warning's words (e.g. "DTS passed through to the receiver"). */
    fun soundPath(words: String) { context = words }

    /**
     * One look at the clock: [positionMs] is where the player says the sound is, [realMs] a steady
     * clock ([steady]: playing at normal speed, not buffering). Playing smoothly, the two advance
     * together; a gap that keeps growing past [DRIFT_MS], faster than clocks differ by nature
     * ([DRIFT_RATE]), is drift. Answers the drift in ms when it's time to re-sync (at most once per
     * [FIX_EVERY_MS]), else null. A pause or buffering keeps what was measured (the sound output
     * isn't set up again then); a seek or a start ([settling]) and the sound running dry
     * ([underrun]) start afresh.
     */
    fun clock(positionMs: Long, realMs: Long, steady: Boolean): Long? {
        if (!settled(realMs) || !steady) {
            if (baseline != null) carriedMs = lastOffsetMs
            baseline = null
            overSamples = 0
            prevOffsetMs = null
            return null
        }
        val base = baseline ?: (positionMs to realMs).also {
            baseline = it
            if (carriedMs == 0L) steadySinceMs = realMs
        }
        val offset = carriedMs + (positionMs - base.first) - (realMs - base.second)
        // A jump between two looks is the sound held up for a moment (the device busy), not drift:
        // the picture waits for the sound, so they're still together, and a re-sync (a seek) would
        // only cost seconds of buffering. Keep what was measured before it and carry on from here.
        val before = prevOffsetMs
        if (before != null && kotlin.math.abs(offset - before) > STALL_STEP_MS) {
            stallCount++
            lastStallMs = offset - before
            carriedMs = before
            baseline = positionMs to realMs
            lastOffsetMs = before
            overSamples = 0
            return null
        }
        prevOffsetMs = offset
        lastOffsetMs = offset
        val elapsed = (realMs - steadySinceMs).coerceAtLeast(1)
        val drifting = kotlin.math.abs(offset) >= DRIFT_MS && kotlin.math.abs(offset).toDouble() / elapsed >= DRIFT_RATE
        overSamples = if (drifting) overSamples + 1 else 0
        if (overSamples < DRIFT_SAMPLES || realMs - lastFixMs < FIX_EVERY_MS) return null
        lastFixMs = realMs
        fixes++
        if (fixes >= DRIFTS_FOR_WARNING) raise(
            SyncWarning(
                SyncTrouble.DRIFT,
                "Sound and picture drifted apart $fixes times (about ${kotlin.math.abs(offset)} ms each time)${context.takeIf { it.isNotBlank() }?.let { " with $it" } ?: ""}. " +
                    "Each time it was lined up again; letting this device decode the sound instead usually stops it for good.",
                convertHelps = false,
            ),
        )
        restartDrift()
        return offset
    }

    /** After a reload that lined them up again: forget what was seen. */
    fun reset(atMs: Long) {
        current = null
        settling(atMs)
    }

    private fun settled(atMs: Long) = atMs >= settleUntil

    /** The sound output ran out of data. */
    fun underrun(atMs: Long) {
        // The sound really stopped for a moment (the picture held for it): not drift.
        restartDrift()
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
        SyncTrouble.DRIFT -> 3
        SyncTrouble.PICTURE_LATE -> 4
    }

    companion object {
        const val SETTLE_MS = 4_000L
        const val WINDOW_MS = 60_000L
        const val UNDERRUNS = 3
        /** Frames this late on average (40 ms) are visibly behind the sound. */
        const val LATE_US = 40_000L
        const val LATE_REPORTS = 3

        /** Drift this big (ms) is about where lips visibly part from voices. */
        const val DRIFT_MS = 120L
        /** Faster than clocks differ by nature (0.02%: 0.72 s an hour; crystals differ by about 0.005%). */
        const val DRIFT_RATE = 0.0002
        /** Looks in a row past the line before acting (the player's position wobbles a little). */
        const val DRIFT_SAMPLES = 3
        /** How often the clock is looked at while playing. */
        const val CLOCK_EVERY_MS = 2_000L

        /**
         * A jump this big between two looks ([CLOCK_EVERY_MS] apart) is a stall, not drift: drift
         * creeps (even 1% slow is 20 ms a look).
         */
        const val STALL_STEP_MS = 60L
        /** At most one re-sync this often. */
        const val FIX_EVERY_MS = 120_000L
        /** After this many re-syncs in one playback, say so and offer the lasting fix. */
        const val DRIFTS_FOR_WARNING = 2
    }
}
