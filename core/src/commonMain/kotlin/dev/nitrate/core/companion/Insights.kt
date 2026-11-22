package dev.nitrate.core.companion

import dev.nitrate.core.model.MediaSegment
import dev.nitrate.core.model.SegmentKind

/** One kind of request's server times by day (Companion `querytimes`, admins). */
data class QueryTrend(val shape: String, val days: List<QueryTrendDay>) {
    /** The last [n] days' typical time against the [n] before them, as a change in percent (null: not enough days). */
    fun change(n: Int = 7): Int? {
        if (days.size < 2) return null
        val recent = days.takeLast(minOf(n, days.size / 2))
        val before = days.dropLast(recent.size).takeLast(n)
        val a = weightedMedian(recent) ?: return null
        val b = weightedMedian(before) ?: return null
        return if (b == 0L) null else (((a - b) * 100) / b).toInt()
    }

    /** "312 asked · typically 120 ms (was 180 ms, 33% faster)". */
    fun line(n: Int = 7): String {
        val count = days.takeLast(n).sumOf { it.count }
        val now = weightedMedian(days.takeLast(n)) ?: 0
        val change = change(n)
        val trend = when {
            change == null -> ""
            change <= -5 -> ", ${-change}% faster than the week before"
            change >= 5 -> ", $change% slower than the week before"
            else -> ", about the same as the week before"
        }
        return "$count asked in $n days · typically about $now ms$trend"
    }

    private fun weightedMedian(list: List<QueryTrendDay>): Long? {
        val total = list.sumOf { it.count }
        if (total == 0L) return null
        var seen = 0L
        for (d in list.sortedBy { it.medianMs }) {
            seen += d.count
            if (seen * 2 >= total) return d.medianMs
        }
        return list.last().medianMs
    }
}

data class QueryTrendDay(val day: String, val count: Long, val medianMs: Long, val slowMs: Long)

/** Which found part of a title someone said was wrong (Companion `segmentfeedback`). */
enum class SegmentPart { INTRO, RECAP, CREDITS }

/**
 * Notices "that skip was wrong" from what people do: skipping an intro, recap or the credits and going straight
 * back into it (within [WINDOW_MS], to a point before its last [MARGIN_MS]). Each skip is judged once.
 */
class SkipWatch(private val report: (itemId: String, part: SegmentPart) -> Unit) {
    private data class Pending(val itemId: String, val part: SegmentPart, val startMs: Long, val endMs: Long, val atMs: Long)

    private var pending: Pending? = null

    /** A segment was skipped by the person (the Skip button). */
    fun skipped(itemId: String, segment: MediaSegment, nowMs: Long) {
        val part = when (segment.kind) {
            SegmentKind.INTRO -> SegmentPart.INTRO
            SegmentKind.RECAP -> SegmentPart.RECAP
            SegmentKind.OUTRO -> SegmentPart.CREDITS
            else -> null
        }
        pending = part?.let { Pending(itemId, it, segment.startMs, segment.endMs, nowMs) }
    }

    /** Where playback is now; going back into the skipped part soon after counts as "it was wrong". */
    fun position(itemId: String, positionMs: Long, nowMs: Long) {
        val p = pending ?: return
        if (p.itemId != itemId || nowMs - p.atMs > WINDOW_MS) { pending = null; return }
        if (positionMs >= p.startMs && positionMs < p.endMs - MARGIN_MS) {
            pending = null
            report(p.itemId, p.part)
        }
    }

    companion object {
        const val WINDOW_MS = 20_000L
        const val MARGIN_MS = 5_000L
    }
}

/** One sitting from the Companion's watch time (`watching`): pauses never counted. */
data class Sitting(val itemId: String, val kind: String, val name: String, val seriesName: String?, val startedAtMs: Long, val endedAtMs: Long, val watchedMs: Long)

/** What a year's sittings add up to. */
data class WatchTime(
    val totalMs: Long,
    val sittings: Int,
    /** Most days in a row with something watched. */
    val longestStreak: Int,
    /** 1 = Monday … 7 = Sunday, the day most watched; null with nothing. */
    val busiestWeekday: Int?,
    /** Month (1–12) → time watched. */
    val byMonth: Map<Int, Long>,
) {
    val hours: Long get() = totalMs / 3_600_000

    companion object {
        /** From sittings, with each one's day in the device's time zone ([offsetMs] from UTC). */
        fun of(sittings: List<Sitting>, offsetMs: Long): WatchTime {
            fun day(ms: Long) = (ms + offsetMs).floorDiv(86_400_000L)
            val days = sittings.map { day(it.startedAtMs) }.distinct().sorted()
            var longest = 0
            var run = 0
            var last: Long? = null
            for (d in days) {
                run = if (last != null && d == last + 1) run + 1 else 1
                longest = maxOf(longest, run)
                last = d
            }
            // Day 0 (1 Jan 1970) was a Thursday (4).
            val weekday = sittings.groupBy { ((day(it.startedAtMs) + 3).mod(7L) + 1).toInt() }
                .maxByOrNull { (_, list) -> list.sumOf { it.watchedMs } }?.key
            val months = sittings.groupBy { monthOf(day(it.startedAtMs)) }.mapValues { (_, l) -> l.sumOf { it.watchedMs } }
            return WatchTime(sittings.sumOf { it.watchedMs }, sittings.size, longest, weekday, months)
        }

        /** [of] for the sittings started in [year] (local time; null = all of them); null when there are none. */
        fun forYear(sittings: List<Sitting>, year: Int?, offsetMs: Long): WatchTime? =
            sittings.filter { year == null || dev.nitrate.core.stats.WatchStatsBuilder.civil(it.startedAtMs + offsetMs).first == year }
                .takeIf { it.isNotEmpty() }?.let { of(it, offsetMs) }

        /** "12 h 30 min of real watching · 48 sittings · 6 days in a row · mostly Fridays". */
        fun line(w: WatchTime): String = listOfNotNull(
            dev.nitrate.core.stats.WatchStatsBuilder.duration(w.totalMs / 60_000) + " of real watching",
            "${w.sittings} sitting" + if (w.sittings == 1) "" else "s",
            w.longestStreak.takeIf { it > 1 }?.let { "$it days in a row at most" },
            w.busiestWeekday?.let { "mostly ${WEEKDAYS[it - 1]}s" },
        ).joinToString(" · ")

        /** The month (1–12) of a day number since 1970 (civil calendar). */
        fun monthOf(day: Long): Int {
            val z = day + 719_468
            val era = z.floorDiv(146_097L)
            val doe = z - era * 146_097
            val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            return (if (mp < 10) mp + 3 else mp - 9).toInt()
        }

        val WEEKDAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    }
}

/** A title that misbehaves across the household (Companion `problems`, admins). */
data class ProblemTitle(val itemId: String, val name: String, val devices: Int, val failed: Int, val converted: Int, val reasons: List<String>, val deviceNames: List<String>, val lastAtMs: Long) {
    val line: String get() = buildList {
        add("$devices device" + if (devices == 1) "" else "s")
        if (failed > 0) add("failed $failed×")
        if (converted > 0) add("converted $converted×")
    }.joinToString(" · ")
}

/** A saved moment in a film or episode (Companion `moments`). */
data class Moment(val id: String, val userId: String, val userName: String, val itemId: String, val positionMs: Long, val note: String?, val groupId: String?, val atMs: Long)

/** A message from a viewing's chat, kept for those who were there (Companion `chathistory`). */
data class ChatRecord(val fromId: String, val name: String, val reaction: Boolean, val text: String, val positionMs: Long?, val atMs: Long)

object Moments {
    /** "1:02:03" or "12:34". */
    fun clock(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = (s % 60).toString().padStart(2, '0')
        return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$sec" else "$m:$sec"
    }

    /** "1:02:03 · the shot · Sam" (the name only for someone else's). */
    fun line(m: Moment, me: String?): String = listOfNotNull(clock(m.positionMs), m.note, m.userName.takeIf { m.userId != me }).joinToString(" · ")
}
