package dev.nitrate.core.writing.story

import dev.nitrate.core.writing.TextOperation

/**
 * Who wrote each part of a script: runs of characters with their writer, kept in step with the
 * text by replaying its changes. Text that's moved (a scene dragged elsewhere, deleted and
 * inserted again in one change) keeps its writers.
 */
class Attribution private constructor(private val runs: List<Run>) {
    data class Run(val length: Int, val userId: String)

    val length get() = runs.sumOf { it.length }

    /** Runs in text order, neighbours by the same writer joined. */
    fun runs(): List<Run> = runs

    fun apply(op: TextOperation, author: String, baseText: String): Attribution {
        // Deleted stretches with their writers, so text moved within this change keeps them.
        val deleted = mutableListOf<Pair<String, List<Run>>>()
        var at = 0
        for (p in op.parts) when (p) {
            is TextOperation.Retain -> at += p.n
            is TextOperation.Delete -> { deleted += baseText.substring(at, at + p.n) to slice(at, at + p.n); at += p.n }
            is TextOperation.Insert -> Unit
        }
        val out = mutableListOf<Run>()
        at = 0
        for (p in op.parts) when (p) {
            is TextOperation.Retain -> { slice(at, at + p.n).forEach { add(out, it) }; at += p.n }
            is TextOperation.Delete -> at += p.n
            is TextOperation.Insert -> {
                val moved = if (p.text.length >= MIN_MOVE) deleted.firstOrNull { it.first == p.text } else null
                if (moved != null) moved.second.forEach { add(out, it) } else add(out, Run(p.text.length, author))
            }
        }
        return Attribution(out)
    }

    /** Who wrote the character at [index]. */
    fun at(index: Int): String? {
        var start = 0
        for (r in runs) {
            if (index < start + r.length) return r.userId
            start += r.length
        }
        return runs.lastOrNull()?.userId
    }

    /** Characters written by each person. */
    fun shares(): Map<String, Int> = runs.groupBy { it.userId }.mapValues { (_, rs) -> rs.sumOf { it.length } }

    private fun slice(from: Int, to: Int): List<Run> {
        val out = mutableListOf<Run>()
        var start = 0
        for (r in runs) {
            val end = start + r.length
            val a = maxOf(start, from)
            val b = minOf(end, to)
            if (a < b) out += Run(b - a, r.userId)
            start = end
            if (start >= to) break
        }
        return out
    }

    companion object {
        /** Shortest insert treated as a possible move (shorter repeats are just typing). */
        const val MIN_MOVE = 12

        private fun add(out: MutableList<Run>, r: Run) {
            if (r.length <= 0) return
            val last = out.lastOrNull()
            if (last != null && last.userId == r.userId) out[out.lastIndex] = Run(last.length + r.length, r.userId) else out += r
        }

        /** A text all by one writer (the script as it was when shared, by its owner). */
        fun start(length: Int, userId: String) = Attribution(if (length > 0) listOf(Run(length, userId)) else emptyList())
    }
}

/** One thing that happened to a script, for the activity list. */
data class Activity(
    val userId: String,
    val userName: String,
    val atMs: Long,
    val untilMs: Long,
    /** What it was about: a scene, an entry, a list, a comment... */
    val target: Ref?,
    val targetName: String,
    val kind: Kind,
    /** How many changes it groups. */
    val count: Int,
    val fromVersion: Long,
    val toVersion: Long,
) {
    enum class Kind(val verb: String) {
        WROTE("edited"), SCENE_DETAILS("changed details of"), ENTRY("edited"), LIST("changed list"),
        LINK("linked"), COMMENT("commented on"), DELETED("deleted"), RESTORED("restored"),
    }
}

object Activities {
    /** Changes by the same person to the same thing within this long count as one. */
    const val GROUP_MS = 10 * 60_000L

    /** Joins neighbouring activities by the same person on the same thing, newest first. */
    fun group(items: List<Activity>): List<Activity> {
        val out = mutableListOf<Activity>()
        for (a in items.sortedBy { it.fromVersion }) {
            val last = out.lastOrNull()
            if (last != null && last.userId == a.userId && last.target == a.target && last.kind == a.kind && a.atMs - last.untilMs <= GROUP_MS) {
                out[out.lastIndex] = last.copy(untilMs = a.untilMs, count = last.count + a.count, toVersion = a.toVersion, targetName = a.targetName)
            } else out += a
        }
        return out.reversed()
    }
}
