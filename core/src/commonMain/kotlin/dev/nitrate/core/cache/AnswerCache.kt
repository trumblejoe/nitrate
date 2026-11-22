package dev.nitrate.core.cache

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The kinds of server answers the client keeps, each for its own time. [userData] answers show
 * what the user has watched (progress, watched marks, what's next), so a change in watch state —
 * the server's own event or anything this app sends — lets them go at once; a change to the
 * library lets everything go.
 */
enum class AnswerKind(val keepMs: Long, val userData: Boolean) {
    /** Continue Watching, Next Up: what the user is in the middle of. */
    WATCHING(30_000, true),

    /** "Latest in…" and other new arrivals. */
    LATEST(5 * 60_000L, true),

    /** Lists of titles in general: browsing, rows built from queries. */
    ITEMS(2 * 60_000L, true),

    /** The library list. */
    VIEWS(5 * 60_000L, false),

    /** Similar titles: costly for the server and stable for hours. */
    SIMILAR(60 * 60_000L, false),
}

/** How an answer was had: asked for, joined while another asker waited for it, or kept from before. */
enum class AnswerSource { FETCHED, JOINED, KEPT }

/**
 * Server answers kept a short while and shared, so every page and row asking the same thing gets
 * one trip to the server: one asker fetches, anyone asking meanwhile waits for that same answer
 * ([AnswerSource.JOINED]), and later askers get it as kept ([AnswerSource.KEPT]) until its
 * [AnswerKind.keepMs] runs out or a change lets it go. Answers are keyed by the whole request,
 * account included, so nothing is shared across accounts.
 */
class AnswerCache(private val nowMs: () -> Long, private val maxEntries: Int = 400) {
    private class Kept(val value: Any, val atMs: Long, val kind: AnswerKind)

    private val lock = Mutex()
    private val kept = LinkedHashMap<String, Kept>()
    private val waiting = HashMap<String, CompletableDeferred<Any>>()

    /**
     * The answer to [key], fetched with [load] only when nobody has it or is already fetching it.
     * A failure is never kept: the next asker tries again.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> get(key: String, kind: AnswerKind, load: suspend () -> T): Pair<T, AnswerSource> {
        while (true) {
            var mine: CompletableDeferred<Any>? = null
            val other: CompletableDeferred<Any>?
            lock.withLock {
                kept[key]?.let { k ->
                    if (nowMs() - k.atMs in 0 until k.kind.keepMs) return k.value as T to AnswerSource.KEPT
                    kept.remove(key)
                }
                other = waiting[key]
                if (other == null) mine = CompletableDeferred<Any>().also { waiting[key] = it }
            }
            if (other != null) {
                try {
                    return other.await() as T to AnswerSource.JOINED
                } catch (e: CancellationException) {
                    // The asker fetching it went away: fetch it ourselves, unless we're going too.
                    currentCoroutineContext().ensureActive()
                    continue
                }
            }
            val deferred = mine!!
            try {
                val value = load()
                lock.withLock {
                    if (waiting[key] === deferred) {
                        waiting.remove(key)
                        kept[key] = Kept(value, nowMs(), kind)
                        while (kept.size > maxEntries) kept.remove(kept.keys.first())
                    }
                }
                deferred.complete(value)
                return value to AnswerSource.FETCHED
            } catch (e: Throwable) {
                lock.withLock { if (waiting[key] === deferred) waiting.remove(key) }
                deferred.completeExceptionally(e)
                throw e
            }
        }
    }

    /** Keeps [value] as the answer to [key], as if just fetched (an answer that came another way, e.g. in a bundle). */
    suspend fun put(key: String, kind: AnswerKind, value: Any) = lock.withLock {
        kept[key] = Kept(value, nowMs(), kind)
        while (kept.size > maxEntries) kept.remove(kept.keys.first())
    }

    /** Lets go of the kept answers [drop] picks ([key] is the whole request). */
    suspend fun forget(drop: (key: String, kind: AnswerKind) -> Boolean) = lock.withLock { kept.entries.removeAll { drop(it.key, it.value.kind) } }

    /**
     * Changes kept answers in place ([change] gives the new value, or the same one), keeping when
     * they were fetched: for watch state that changed without changing which titles a list holds.
     */
    suspend fun patch(change: (Any) -> Any) = lock.withLock {
        kept.entries.forEach { e -> val now = change(e.value.value); if (now !== e.value.value) e.setValue(Kept(now, e.value.atMs, e.value.kind)) }
    }

    /** Lets go of answers showing watch state (after it changed here or on another device). */
    suspend fun userDataChanged() = lock.withLock { kept.entries.removeAll { it.value.kind.userData } }

    /** Lets go of everything (the library changed, or another account). */
    suspend fun clear() = lock.withLock { kept.clear() }

    /** How many answers are kept now. */
    suspend fun size(): Int = lock.withLock { kept.size }
}
