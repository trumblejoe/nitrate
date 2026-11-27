package dev.nitrate.core.diagnostics

/** One of the admin's Watched/Unwatched requests, as long as the server took over it. */
data class TimedPress(val atMs: Long, val watched: Boolean, val ms: Double)

/** One listener's time for one save. */
data class ListenerTime(val name: String, val ms: Double)

/** One of the admin's watch-state saves: every listener the server ran inside it. */
data class TimedSave(val atMs: Long, val title: String, val reason: String, val listenersMs: Double, val listeners: List<ListenerTime>)

/** A listener over the whole window. */
data class ListenerSummary(val name: String, val saves: Int, val medianMs: Double, val maxMs: Double)

/**
 * The Companion timing the admin's own Watched/Unwatched presses for a few minutes (feature
 * [WatchTiming.FEATURE]): each request whole, and every plugin's watch-state listener, which Jellyfin
 * runs inside the save before it answers.
 */
data class WatchTimingReport(
    val supported: Boolean,
    val running: Boolean,
    val endsAtMs: Long,
    val presses: List<TimedPress>,
    val saves: List<TimedSave>,
    val listeners: List<ListenerSummary>,
)

object WatchTiming {
    const val FEATURE = "watchtiming"

    /** A listener this slow, every save, is worth naming. */
    const val SLOW_LISTENER_MS = 100.0

    /** What the presses and listeners say, in words. */
    fun findings(r: WatchTimingReport): List<String> {
        if (r.presses.isEmpty() && r.saves.isEmpty()) {
            return listOf(if (r.running) "Nothing yet: press Watched or Unwatched on a few titles, in any app, while this runs." else "Nothing was pressed while it ran.")
        }
        val out = mutableListOf<String>()
        val press = median(r.presses.map { it.ms })
        val listeners = median(r.saves.map { it.listenersMs })
        if (r.presses.isNotEmpty()) out += "Watched/Unwatched took about ${ms(press)} on the server (the middle of ${r.presses.size})."
        if (!r.supported) out += "This server's listeners can't be timed one by one; the requests' times are still right."
        else if (r.saves.isNotEmpty()) out += "Every plugin listening to watch changes took about ${ms(listeners)} of each save together."
        r.listeners.filter { it.medianMs >= SLOW_LISTENER_MS }.forEach { l ->
            out += "${l.name} takes about ${ms(l.medianMs)} of every save (slowest ${ms(l.maxMs)}): turning it off, or its setting that reacts to watched changes, would speed up Watched by that much."
        }
        if (r.presses.isNotEmpty() && r.saves.isNotEmpty()) {
            val rest = press - listeners
            if (rest >= 500) out += "The rest, about ${ms(rest)}, is Jellyfin saving to its database and answering: that's the server's disk or processor, or another program keeping it busy."
            else if (r.listeners.none { it.medianMs >= SLOW_LISTENER_MS }) out += "Nothing here is slow: if Watched still feels slow in the app, it's the network between it and the server."
        }
        return out
    }

    /** The whole report as lines of text (for copying). */
    fun render(r: WatchTimingReport): String = buildString {
        append("Nitrate watch-change timing\n\n== Findings ==\n")
        findings(r).forEach { append("- ").append(it).append('\n') }
        if (r.presses.isNotEmpty()) {
            append("\n== Presses ==\n")
            r.presses.forEach { append(if (it.watched) "Watched" else "Unwatched").append(": ").append(ms(it.ms)).append('\n') }
        }
        if (r.listeners.isNotEmpty()) {
            append("\n== Listeners (middle, slowest, saves) ==\n")
            r.listeners.forEach { append("${it.name}: ${ms(it.medianMs)}, ${ms(it.maxMs)}, ${it.saves}\n") }
        }
    }

    fun ms(v: Double): String = if (v >= 1000) "${(v / 100).toInt() / 10.0} s" else "${v.toInt()} ms"

    private fun median(v: List<Double>): Double = v.sorted().let { if (it.isEmpty()) 0.0 else if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
}
