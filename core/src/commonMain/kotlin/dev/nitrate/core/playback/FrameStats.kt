package dev.nitrate.core.playback

/**
 * How smoothly the player's screen drew, by what was open (controls up, More, Info, chat, nothing):
 * each frame's time against the screen's refresh, for the playback report. A frame slower than one
 * refresh is late (a hitch); over [SLOW_MS] is a lag someone notices on a key press. Feed it from
 * one thread.
 */
class FrameStats {
    private class Tally {
        var frames = 0
        var late = 0
        var slow = 0
        var worstMs = 0.0
        var refreshMs = 0.0
        // The most recent frames' times (for a typical one), at most [KEPT].
        val recent = ArrayDeque<Double>()
    }

    private val tallies = linkedMapOf<String, Tally>()

    /** One frame drawn in [durationMs] while [what] was open, the screen refreshing every [refreshMs]. */
    fun record(what: String, durationMs: Double, refreshMs: Double) {
        val t = tallies.getOrPut(what) { Tally() }
        t.frames++
        if (durationMs > refreshMs) t.late++
        if (durationMs > SLOW_MS) t.slow++
        if (durationMs > t.worstMs) t.worstMs = durationMs
        t.refreshMs = refreshMs
        t.recent.addLast(durationMs)
        if (t.recent.size > KEPT) t.recent.removeFirst()
    }

    /** "With More open: 412 frames, 37 late (over one refresh, 41.7 ms), 5 over 100 ms, typical 23 ms, worst 260 ms". */
    fun lines(): List<String> = tallies.map { (what, t) ->
        val typical = t.recent.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        "With $what: ${t.frames} frames, ${t.late} late (over one refresh, ${one(t.refreshMs)} ms), ${t.slow} over ${SLOW_MS.toInt()} ms, " +
            "typical ${typical.toInt()} ms, worst ${t.worstMs.toInt()} ms"
    }

    private fun one(ms: Double) = ((ms * 10).toLong() / 10.0).toString()

    companion object {
        const val SLOW_MS = 100.0
        const val KEPT = 600
    }
}
