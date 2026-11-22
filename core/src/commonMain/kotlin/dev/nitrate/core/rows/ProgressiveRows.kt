package dev.nitrate.core.rows

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

/** A page's rows so far: [complete] once every row has answered. */
data class RowsProgress(
    val rows: List<MediaRow>,
    val complete: Boolean,
    /** Rows left out because the page hides them (key → title), never queried: the editor still names them. */
    val skipped: Map<String, String> = emptyMap(),
)

/** One independent part of a page (a row, or a few rows from one source), named for timing. */
class RowJob(
    val name: String,
    /** The one row this job makes, when that's known before it runs: a page that hides it never runs the job. */
    val key: String? = null,
    val load: suspend () -> List<MediaRow>,
) {
    /** Set by [showing]: a load that can show rows before it finishes. */
    internal var showingLoad: (suspend (show: suspend (List<MediaRow>) -> Unit) -> List<MediaRow>)? = null

    /** Every row this job can make, when known before it runs: hidden only when all of them are. */
    var keys: Map<String, String> = key?.let { mapOf(it to name) } ?: emptyMap()
        private set

    /** The same job, starting only once [first] has run (e.g. a shared answer it can then use). */
    fun after(first: suspend () -> Unit): RowJob {
        val source = this
        return RowJob(name, key) { first(); source.load() }.also { job ->
            job.keys = source.keys
            source.showingLoad?.let { early -> job.showingLoad = { show -> first(); early(show) } }
        }
    }

    companion object {
        /** A job making several rows with fixed keys ([keys]: key → title): it runs unless every one is hidden. */
        fun making(name: String, keys: Map<String, String>, load: suspend () -> List<MediaRow>): RowJob =
            RowJob(name, null, load).also { it.keys = keys }

        /** A job that can [show] rows early (e.g. from what it knew last time), then return the final ones. */
        fun showing(name: String, load: suspend (show: suspend (List<MediaRow>) -> Unit) -> List<MediaRow>): RowJob =
            RowJob(name, null) { load {} }.also { it.showingLoad = load }
    }
}

/** How long one [RowJob] took. */
data class RowTiming(val name: String, val ms: Long, val failed: Boolean)

/**
 * Loads a page's rows without waiting for the slowest: every job starts at once and the rows so
 * far are emitted, in page order, each time one finishes. A job that fails leaves its rows out;
 * if every job fails the flow fails (the server is unreachable).
 */
object ProgressiveRows {
    /**
     * [skip]: row keys the page hides; their jobs (when a job's [RowJob.key] says which row it makes)
     * never run, and come back as [RowsProgress.skipped] so the editor can still name them.
     */
    fun run(all: List<RowJob>, skip: Set<String> = emptySet(), onTimed: (RowTiming) -> Unit = {}): Flow<RowsProgress> = channelFlow {
        fun hidden(job: RowJob) = job.keys.isNotEmpty() && job.keys.keys.all { it in skip }
        val skipped = all.filter(::hidden).flatMap { it.keys.entries }.associate { it.key to it.value }
        val jobs = all.filterNot(::hidden)
        if (jobs.isEmpty()) { send(RowsProgress(emptyList(), complete = true, skipped)); return@channelFlow }
        val results = arrayOfNulls<List<MediaRow>>(jobs.size)
        val lock = Mutex()
        var done = 0
        var failures = 0
        var firstError: Throwable? = null
        jobs.forEachIndexed { i, job ->
            launch {
                val mark = TimeSource.Monotonic.markNow()
                var shown = false
                val rows = try {
                    dev.nitrate.core.diagnostics.withQueryOrigin(job.name) { job.showingLoad?.invoke { early ->
                        lock.withLock {
                            if (results[i] == null || !shown) {
                                if (!shown) onTimed(RowTiming("${job.name} (first shown)", mark.elapsedNow().inWholeMilliseconds, false))
                                shown = true
                                results[i] = early
                                send(RowsProgress(results.filterNotNull().flatten(), complete = false, skipped))
                            }
                        }
                    } ?: job.load() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lock.withLock { failures++; if (firstError == null) firstError = e }
                    null
                }
                onTimed(RowTiming(job.name, mark.elapsedNow().inWholeMilliseconds, rows == null))
                lock.withLock {
                    results[i] = rows.orEmpty()
                    done++
                    val complete = done == jobs.size
                    if (complete && failures == jobs.size) throw firstError!!
                    send(RowsProgress(results.filterNotNull().flatten(), complete, skipped))
                }
            }
        }
    }
}

/**
 * When a loading page shows what has arrived, so it changes as few times as possible instead of
 * popping rows in one by one: the whole page swaps in once everything has answered; if that takes
 * longer than the patience, what has answered by then is shown once ([partial]), and the rest waits
 * for the end.
 */
object PageReveal {
    /** Nothing on screen yet: how long to gather rows before showing some. */
    const val FIRST_PATIENCE_MS = 1_500L

    /** Last time's rows on screen: how long before the rows that have answered replace them. */
    const val REFRESH_PATIENCE_MS = 3_000L

    fun patienceMs(shown: List<MediaRow>?): Long = if (shown.isNullOrEmpty()) FIRST_PATIENCE_MS else REFRESH_PATIENCE_MS

    /**
     * What to show once the patience runs out, given what's [shown] and what has answered: with
     * nothing shown, the rows so far; otherwise the shown rows with each one that has answered
     * replaced in place, and no row added or removed, so nothing on screen moves.
     */
    fun partial(shown: List<MediaRow>?, progress: RowsProgress): List<MediaRow> {
        if (shown.isNullOrEmpty()) return progress.rows
        val fresh = progress.rows.associateBy { it.key }
        return shown.map { fresh[it.key] ?: it }
    }
}
