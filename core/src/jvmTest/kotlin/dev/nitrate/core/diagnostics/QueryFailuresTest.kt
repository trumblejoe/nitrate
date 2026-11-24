package dev.nitrate.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Failed requests say why: the server's status when it refused, else why no answer came. */
class QueryFailuresTest {
    private fun failed(id: Long, status: Int?, error: String? = null) = QueryRecord(
        id, "t$id", 0, "DELETE", "/UserPlayedItems/{id}", "DELETE /UserPlayedItems/{id}", "Details", 0, QueryOutcome.FAILED, status, error = error,
    )

    @Test fun failuresAreCountedByWhy() {
        val summary = QuerySummary.of(listOf(failed(1, 403), failed(2, 403), failed(3, null, "SocketTimeoutException: timeout")))
        val g = summary.groups.single()
        assertEquals(listOf("403 ×2", "no answer (SocketTimeoutException: timeout) ×1"), g.failures)
        assertTrue(g.counts, g.counts.contains("3 failed (403 ×2, no answer (SocketTimeoutException: timeout) ×1)"))
        assertTrue(summary.render("test").contains("3 failed: 403 ×2, no answer (SocketTimeoutException: timeout) ×1"))
    }
}
