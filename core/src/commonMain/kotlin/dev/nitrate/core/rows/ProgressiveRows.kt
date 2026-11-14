package dev.nitrate.core.rows

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

/** A page's rows so far: [complete] once every row has answered. */
data class RowsProgress(val rows: List<MediaRow>, val complete: Boolean)

/** One independent part of a page (a row, or a few rows from one source), named for timing. */
class RowJob(val name: String, val load: suspend () -> List<MediaRow>)

/** How long one [RowJob] took. */
data class RowTiming(val name: String, val ms: Long, val failed: Boolean)

/**
 * Loads a page's rows without waiting for the slowest: every job starts at once and the rows so
 * far are emitted, in page order, each time one finishes. A job that fails leaves its rows out;
 * if every job fails the flow fails (the server is unreachable).
 */
object ProgressiveRows {
    fun run(jobs: List<RowJob>, onTimed: (RowTiming) -> Unit = {}): Flow<RowsProgress> = channelFlow {
        if (jobs.isEmpty()) { send(RowsProgress(emptyList(), complete = true)); return@channelFlow }
        val results = arrayOfNulls<List<MediaRow>>(jobs.size)
        val lock = Mutex()
        var done = 0
        var failures = 0
        var firstError: Throwable? = null
        jobs.forEachIndexed { i, job ->
            launch {
                val mark = TimeSource.Monotonic.markNow()
                val rows = try {
                    job.load()
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
                    send(RowsProgress(results.filterNotNull().flatten(), complete))
                }
            }
        }
    }

    /**
     * What to show while rows are still arriving, given what was shown before (e.g. last time's
     * rows): rows that have answered replace their old selves in place, new ones are added at the
     * end, and the rest stay until they answer. Once [progress] is complete it's shown as it is.
     */
    fun overlay(previous: List<MediaRow>?, progress: RowsProgress): List<MediaRow> {
        if (progress.complete || previous.isNullOrEmpty()) return progress.rows
        val fresh = progress.rows.associateBy { it.key }
        val kept = previous.map { fresh[it.key] ?: it }
        val known = previous.map { it.key }.toSet()
        return kept + progress.rows.filter { it.key !in known }
    }
}

