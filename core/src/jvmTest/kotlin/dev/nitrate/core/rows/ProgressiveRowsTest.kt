package dev.nitrate.core.rows

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProgressiveRowsTest {
    private fun row(key: String) = MediaRow(key, key, emptyList(), RowDisplay())

    @Test
    fun fastRowsArriveWithoutWaitingForTheSlowOne() = runBlocking {
        val slow = CompletableDeferred<Unit>()
        val seen = mutableListOf<RowsProgress>()
        val timings = mutableListOf<RowTiming>()
        val job = launch {
            ProgressiveRows.run(
                listOf(
                    RowJob("first") { listOf(row("a")) },
                    RowJob("slow") { slow.await(); listOf(row("b")) },
                    RowJob("third") { listOf(row("c")) },
                ),
            ) { timings += it }.collect { seen += it }
        }
        while (seen.size < 2) kotlinx.coroutines.yield()
        // Both quick rows are out, in page order, before the slow one answers.
        assertEquals(listOf("a", "c"), seen.last().rows.map { it.key })
        assertTrue(!seen.last().complete)
        slow.complete(Unit)
        job.join()
        assertEquals(listOf("a", "b", "c"), seen.last().rows.map { it.key })
        assertTrue(seen.last().complete)
        assertEquals(setOf("first", "slow", "third"), timings.map { it.name }.toSet())
    }

    @Test
    fun oneFailureLeavesItsRowsOutAllFailuresFail() = runBlocking {
        val ok = ProgressiveRows.run(listOf(RowJob("bad") { error("nope") }, RowJob("good") { listOf(row("g")) })).toList()
        assertEquals(listOf("g"), ok.last().rows.map { it.key })
        assertFailsWith<IllegalStateException> {
            ProgressiveRows.run(listOf(RowJob("bad") { error("offline") }, RowJob("worse") { error("offline") })).toList()
        }
        Unit
    }

    @Test
    fun overlayKeepsLastTimesRowsUntilTheyAnswer() {
        val previous = listOf(row("a"), row("b"), row("c"))
        val freshA = MediaRow("a", "A new", emptyList(), RowDisplay())
        val partial = RowsProgress(listOf(freshA, row("d")), complete = false)
        assertEquals(listOf("A new", "b", "c", "d"), ProgressiveRows.overlay(previous, partial).map { it.title })
        val done = RowsProgress(listOf(freshA, row("c")), complete = true)
        assertEquals(listOf("a", "c"), ProgressiveRows.overlay(previous, done).map { it.key })
    }
}
