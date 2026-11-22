package dev.nitrate.core.diagnostics

/**
 * What makes a request costly for a Jellyfin server, in words, from what it asks (no timing
 * needed): sorting by play date, similar titles, counting every match, full file details…
 */
object QueryHints {
    const val SLOW_MS = 2_000L
    const val LARGE_BYTES = 1_000_000L

    fun of(endpoint: String, params: Map<String, String>, ms: Long? = null, bytes: Long? = null): List<String> = buildList {
        val p = params.mapKeys { it.key.lowercase() }
        val sort = p["sortby"].orEmpty()
        val fields = p["fields"].orEmpty()
        val recursive = p["recursive"].equals("true", ignoreCase = true)
        val itemsList = endpoint == "/Items" || endpoint.endsWith("/Items") || endpoint == "/Users/{id}/Items"
        if (endpoint.endsWith("/Similar")) add("Similar titles: the server compares this title with the whole library")
        if (sort.contains("DatePlayed", ignoreCase = true)) add("Sorted by date played: the server reads the play date of every match")
        if (sort.contains("Random", ignoreCase = true)) add("Random order: the server shuffles every match")
        if (itemsList && recursive && p["limit"] == null) add("No limit: every match in the library is sent")
        if (itemsList && recursive && p["limit"] != null && p["enabletotalrecordcount"]?.equals("false", ignoreCase = true) != true)
            add("Counts every match (enableTotalRecordCount): ask with false when the total isn't shown")
        if (fields.contains("MediaSources", ignoreCase = true) || fields.contains("MediaStreams", ignoreCase = true))
            add("Full file details for every title: big answers")
        if (p["searchterm"] != null) add("Text search across the library")
        if (p["filters"].orEmpty().contains("IsPlayed", ignoreCase = true) && recursive) add("Filtered by watched state: the server checks every match's play record")
        if (ms != null && ms >= SLOW_MS) add("Slow: over ${SLOW_MS / 1000} s")
        if (bytes != null && bytes >= LARGE_BYTES) add("Large answer: over ${LARGE_BYTES / 1_000_000} MB")
    }
}

/** One kind of request ([shape]) over the records looked at. */
data class QueryGroup(
    val endpoint: String,
    val shape: String,
    val count: Int,
    val fetched: Int,
    val shared: Int,
    val failed: Int,
    val totalMs: Long,
    val medianMs: Long,
    val maxMs: Long,
    val bytes: Long,
    val serverMedianMs: Long?,
    val origins: List<String>,
    val hints: List<String>,
) {
    /** "12× · 3 fetched, 9 shared · 840 ms in all, median 210 ms, max 400 ms · server 180 ms". */
    val counts: String get() = listOfNotNull(
        "$count×",
        "$fetched fetched, $shared shared" + if (failed > 0) ", $failed failed" else "",
        if (fetched > 0) "$totalMs ms in all, median $medianMs ms, max $maxMs ms" else "no time waited",
        serverMedianMs?.let { "server $it ms" },
        if (bytes > 0) QuerySummary.size(bytes) else null,
    ).joinToString(" · ")
}

/** Everything asked from one place ([origin]: a page, a row, a feature). */
data class OriginGroup(val origin: String, val count: Int, val fetched: Int, val shared: Int, val totalMs: Long, val slowest: String?) {
    val counts: String get() = "$count requests · $fetched fetched, $shared shared · $totalMs ms" + (slowest?.let { " · slowest $it" } ?: "")
}

/** Sums of a set of records: the costliest kinds of request first, and where they came from. */
data class QuerySummary(
    val records: Int,
    val fetched: Int,
    val shared: Int,
    val failed: Int,
    val totalMs: Long,
    val bytes: Long,
    val groups: List<QueryGroup>,
    val origins: List<OriginGroup>,
) {
    companion object {
        /** A request fetched again within this long of the last fetch of the same shape counts as repeated. */
        const val REPEAT_WINDOW_MS = 60_000L

        fun of(records: List<QueryRecord>): QuerySummary {
            val fetched = records.filter { it.outcome == QueryOutcome.FETCHED }
            val groups = records.groupBy { it.shape }.map { (shape, list) ->
                val got = list.filter { it.outcome == QueryOutcome.FETCHED }
                val times = got.map { it.ms }.sorted()
                val server = got.mapNotNull { it.serverMs }.sorted()
                val repeats = repeats(got)
                QueryGroup(
                    endpoint = list.first().endpoint,
                    shape = shape,
                    count = list.size,
                    fetched = got.size,
                    shared = list.count { it.outcome == QueryOutcome.JOINED || it.outcome == QueryOutcome.KEPT },
                    failed = list.count { it.outcome == QueryOutcome.FAILED },
                    totalMs = got.sumOf { it.ms },
                    medianMs = times.median() ?: 0,
                    maxMs = times.lastOrNull() ?: 0,
                    bytes = got.sumOf { it.bytes ?: 0 },
                    serverMedianMs = server.median(),
                    origins = list.groupingBy { it.origin }.eachCount().entries.sortedByDescending { it.value }.map { it.key },
                    hints = (list.flatMap { it.hints }.distinct() +
                        listOfNotNull(if (repeats >= 2) "Fetched again $repeats times within a minute: these could share one answer" else null)),
                )
            }.sortedWith(compareByDescending<QueryGroup> { it.totalMs }.thenByDescending { it.count })
            val origins = records.groupBy { it.origin }.map { (origin, list) ->
                val got = list.filter { it.outcome == QueryOutcome.FETCHED }
                OriginGroup(
                    origin = origin,
                    count = list.size,
                    fetched = got.size,
                    shared = list.count { it.outcome == QueryOutcome.JOINED || it.outcome == QueryOutcome.KEPT },
                    totalMs = got.sumOf { it.ms },
                    slowest = got.maxByOrNull { it.ms }?.endpoint,
                )
            }.sortedByDescending { it.totalMs }
            return QuerySummary(
                records = records.size,
                fetched = fetched.size,
                shared = records.count { it.outcome == QueryOutcome.JOINED || it.outcome == QueryOutcome.KEPT },
                failed = records.count { it.outcome == QueryOutcome.FAILED },
                totalMs = fetched.sumOf { it.ms },
                bytes = fetched.sumOf { it.bytes ?: 0 },
                groups = groups,
                origins = origins,
            )
        }

        /** How many fetches came within [REPEAT_WINDOW_MS] of the one before (same shape). */
        private fun repeats(fetched: List<QueryRecord>): Int =
            fetched.sortedBy { it.atMs }.zipWithNext().count { (a, b) -> b.atMs - a.atMs in 0 until REPEAT_WINDOW_MS }

        fun size(bytes: Long) = when {
            bytes >= 1_000_000 -> "${bytes / 100_000 / 10.0} MB"
            bytes >= 1_000 -> "${bytes / 1_000} KB"
            else -> "$bytes B"
        }

        private fun List<Long>.median(): Long? = if (isEmpty()) null else this[size / 2]
    }

    /** As text for the server's log: totals, the costliest kinds of request with hints, then by place. */
    fun render(title: String, device: String? = null): String = buildString {
        appendLine("Nitrate query analyzer: $title")
        device?.let { appendLine("Device: $it") }
        appendLine("Requests: $records ($fetched fetched, $shared shared, $failed failed), ${totalMs} ms waited, ${size(bytes)} received")
        appendLine()
        appendLine("Costliest requests (time waited for fetched answers):")
        groups.take(40).forEach { g ->
            appendLine("- ${g.shape}")
            append("    ${g.count}× (${g.fetched} fetched, ${g.shared} shared")
            if (g.failed > 0) append(", ${g.failed} failed")
            append("), ${g.totalMs} ms total, median ${g.medianMs} ms, max ${g.maxMs} ms")
            g.serverMedianMs?.let { append(", server median $it ms") }
            if (g.bytes > 0) append(", ${size(g.bytes)}")
            appendLine()
            appendLine("    From: ${g.origins.take(5).joinToString("; ")}")
            g.hints.forEach { appendLine("    Hint: $it") }
        }
        appendLine()
        appendLine("By page or feature:")
        origins.forEach { o ->
            appendLine("- ${o.origin}: ${o.count} requests (${o.fetched} fetched, ${o.shared} shared), ${o.totalMs} ms" + (o.slowest?.let { ", slowest $it" } ?: ""))
        }
    }

    /** "Requests: 40 (12 fetched, 28 shared) · 3.2 s waited · 1.4 MB". */
    val headline: String get() = "$records requests ($fetched fetched, $shared shared" + (if (failed > 0) ", $failed failed" else "") + ") · ${totalMs} ms waited · ${size(bytes)}"

}
