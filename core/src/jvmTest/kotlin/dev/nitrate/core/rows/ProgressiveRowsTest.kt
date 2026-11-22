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
    fun aPartialRevealNeverMovesWhatsOnScreen() {
        val shown = listOf(row("a"), row("b"), row("c"))
        val freshA = MediaRow("a", "A new", emptyList(), RowDisplay())
        // "a" answered (changed) and "d" is new: "a" updates in place, "d" waits for the end.
        val partial = RowsProgress(listOf(freshA, row("d")), complete = false)
        assertEquals(listOf("A new", "b", "c"), PageReveal.partial(shown, partial).map { it.title })
        // Nothing on screen yet: whatever has answered.
        assertEquals(listOf("a", "d"), PageReveal.partial(null, partial.copy(rows = listOf(row("a"), row("d")))).map { it.key })
        // A first load waits less than a refresh, which has last time's rows to show meanwhile.
        assertTrue(PageReveal.patienceMs(null) < PageReveal.patienceMs(shown))
    }

    @Test fun hiddenRowsAreNeverLoaded() = kotlinx.coroutines.test.runTest {
        var asked = false
        val done = ProgressiveRows.run(
            listOf(
                RowJob("Shown", "home/shown") { listOf(row("home/shown")) },
                RowJob("Hidden", "home/hidden") { asked = true; listOf(row("home/hidden")) },
            ),
            skip = setOf("home/hidden"),
        ).toList().last()
        kotlin.test.assertFalse(asked, "a hidden row's query should never run")
        kotlin.test.assertEquals(listOf("home/shown"), done.rows.map { it.key })
        kotlin.test.assertEquals(mapOf("home/hidden" to "Hidden"), done.skipped)
    }

    @Test fun aJobMakingSeveralRowsIsSkippedOnlyWhenAllAreHidden() = kotlinx.coroutines.test.runTest {
        var ran = 0
        fun job() = RowJob.making("Because", mapOf("a" to "A", "b" to "B")) { ran++; listOf(row("a"), row("b")) }
        ProgressiveRows.run(listOf(job()), skip = setOf("a")).toList()
        kotlin.test.assertEquals(1, ran, "one of its rows is still shown: it runs")
        val done = ProgressiveRows.run(listOf(job()), skip = setOf("a", "b")).toList().last()
        kotlin.test.assertEquals(1, ran, "both hidden: it never runs")
        kotlin.test.assertEquals(setOf("a", "b"), done.skipped.keys)
    }
}
