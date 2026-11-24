package dev.nitrate.core.diagnostics

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** How a query was answered: by the server, by joining one already on its way, or from the client's kept answers. */
enum class QueryOutcome(val label: String) {
    FETCHED("Fetched"),
    JOINED("Shared (joined)"),
    KEPT("Shared (kept)"),
    FAILED("Failed"),
}

/**
 * One request the app made (or would have made, for a shared answer). [endpoint] is the path with
 * ids as `{id}`; [shape] adds the query parameters (ids as `{id}`, paging left out), so the same
 * kind of question groups together whichever title it was about. [ms] is the wait as the app saw
 * it, network included; [serverMs] is the server's own time for it, from the Companion plugin.
 */
@Serializable
data class QueryRecord(
    val id: Long,
    val tag: String,
    val atMs: Long,
    val method: String,
    val endpoint: String,
    val shape: String,
    val origin: String,
    val ms: Long,
    val outcome: QueryOutcome,
    val status: Int? = null,
    val bytes: Long? = null,
    val serverMs: Long? = null,
    val hints: List<String> = emptyList(),
    /** Why it failed when no answer came at all (the error, briefly); a refused request has its [status] instead. */
    val error: String? = null,
    /**
     * The whole request (path and parameters, ids as they are), so only the very same question asked
     * again counts as a repeat — not another title's lookup of the same [shape].
     */
    val request: String? = null,
)

/**
 * Where a request comes from, carried in the coroutine: "Home › Next Up", "Details", "Search".
 * Nested origins join up ([withQueryOrigin]).
 */
class QueryOrigin(val label: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<QueryOrigin>
}

/** Runs [block] with [part] added to the current origin, so its requests are put down to it. */
suspend fun <T> withQueryOrigin(part: String, block: suspend () -> T): T {
    val now = currentCoroutineContext()[QueryOrigin]?.label
    val label = if (now == null || now == part) part else "$now › $part"
    return withContext(QueryOrigin(label)) { block() }
}

/** A recording of one visit: the requests made between [fromId] and [untilId] (open while recording). */
@Serializable
data class QueryRecording(val label: String, val fromId: Long, val untilId: Long? = null) {
    val recording get() = untilId == null
    fun covers(r: QueryRecord) = r.id > fromId && (untilId == null || r.id <= untilId)
}

/**
 * Every request the server client makes, newest last, up to [capacity] (the oldest go first).
 * Cheap enough to be always on: a record is a few small strings.
 */
class QueryLog(private val capacity: Int = 2_000) {
    private val _records = MutableStateFlow<List<QueryRecord>>(emptyList())
    val records: StateFlow<List<QueryRecord>> = _records

    private val _recording = MutableStateFlow<QueryRecording?>(null)
    val recording: StateFlow<QueryRecording?> = _recording

    private val nextId = MutableStateFlow(0L)

    /** This run's prefix for request tags, so the server's timings match only this app's requests. */
    val run: String = (1..6).map { "abcdefghijkmnpqrstuvwxyz23456789".random() }.joinToString("")

    /** A new id and its tag ("<run>-<id>"), sent with the request so the server can time it under that name. */
    fun nextTag(): Pair<Long, String> {
        var id = 0L
        nextId.update { id = it + 1; id }
        return id to "$run-$id"
    }

    fun add(record: QueryRecord) {
        _records.update { list -> if (list.size >= capacity) list.drop(list.size - capacity + 1) + record else list + record }
    }

    /** Adds the server's own times ([tag] → ms) to the records they belong to. */
    fun addServerTimes(times: Map<String, Long>) {
        if (times.isEmpty()) return
        _records.update { list -> list.map { r -> times[r.tag]?.let { r.copy(serverMs = it) } ?: r } }
    }

    fun clear() {
        _records.value = emptyList()
        _recording.value = null
    }

    /** Starts recording a visit: what's asked from now until [stopRecording]. */
    fun startRecording(label: String) {
        _recording.value = QueryRecording(label, fromId = nextId.value)
    }

    fun stopRecording() {
        _recording.update { it?.copy(untilId = nextId.value) }
    }

    fun forgetRecording() {
        _recording.value = null
    }

    companion object {
        /** Where a request was made from, as the app labels it ("Other" when nothing said). */
        suspend fun originHere(): String = currentCoroutineContext()[QueryOrigin]?.label ?: "Other"

        /** A page's name for origins: "Home", "Library <id>", "Custom page <id>" (ids named by [readable]). */
        fun pageLabel(pageKey: String): String {
            val parts = pageKey.split('/')
            return when (parts.first()) {
                "home" -> "Home"
                "more-from" -> "More from"
                "library" -> (listOf("Library ${parts.getOrNull(1).orEmpty()}") + parts.drop(2)).joinToString(" › ")
                "custom" -> "Custom page ${parts.getOrNull(1).orEmpty()}"
                else -> pageKey
            }.trim()
        }

        /** [origin] with any ids in it replaced by what they're called ([names]: library and page names). */
        fun readable(origin: String, names: Map<String, String>): String =
            origin.split(' ').joinToString(" ") { word -> names[word] ?: names[word.replace("-", "")] ?: word }

        private val dashed = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        private val dashless = Regex("^[0-9a-fA-F]{32}$")

        fun isId(s: String) = dashed.matches(s) || dashless.matches(s)

        /** The path with ids as `{id}`: "/Items/{id}/Similar". */
        fun endpointOf(path: String): String =
            "/" + path.trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/") { if (isId(it)) "{id}" else it }

        /** Parameters that don't change what kind of question it is. */
        private val paging = setOf("startIndex", "limit", "api_key", "_")

        /** The endpoint and its parameters (ids as `{id}`, sorted, paging left out). */
        fun shapeOf(endpoint: String, params: Map<String, String>): String {
            val p = params.entries.filter { it.key !in paging }.sortedBy { it.key.lowercase() }
                .map { (k, v) -> "$k=" + v.split(',').joinToString(",") { if (isId(it)) "{id}" else it } }
            return if (p.isEmpty()) endpoint else endpoint + "?" + p.joinToString("&")
        }
    }
}
