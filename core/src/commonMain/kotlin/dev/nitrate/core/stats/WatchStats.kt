package dev.nitrate.core.stats

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem

/** A name with how many things and how long ("The Night Reel: 24 episodes, 18 h"). */
data class Tally(val name: String, val count: Int, val minutes: Long, val id: String? = null)

/** What someone watched and listened to in a period ([year], or all time when null). */
data class WatchStats(
    val year: Int?,
    val films: Int,
    val episodes: Int,
    val shows: Int,
    val songs: Int,
    /** Watching time (films and episodes), counting each play. */
    val watchMinutes: Long,
    val listenMinutes: Long,
    val topShows: List<Tally>,
    val topGenres: List<Tally>,
    val topArtists: List<Tally>,
    /** Films watched more than once (all their plays counted). */
    val rewatched: List<Tally>,
    /** Things finished per month (1–12), for a year. */
    val byMonth: List<Int>,
) {
    val isEmpty: Boolean get() = films + episodes + songs == 0
}

/**
 * Watching stats from the server's own record (what's been finished, when each was last played,
 * how many times). Jellyfin keeps only the last play date, so a title counts in the year it was
 * last watched; time counts every play of it.
 */
object WatchStatsBuilder {
    const val TOP = 5

    /** [utcOffsetMs]: the device's time zone, so a film watched on New Year's Eve counts in its year. */
    fun build(history: List<MediaItem>, year: Int?, utcOffsetMs: Long = 0): WatchStats {
        fun yearOf(ms: Long) = civil(ms + utcOffsetMs).first
        val items = history.filter { year == null || it.user.lastPlayedMs?.let(::yearOf) == year }
        val watched = items.filter { it.kind == ItemKind.MOVIE || it.kind == ItemKind.EPISODE }
        val songs = items.filter { it.kind == ItemKind.AUDIO }
        fun plays(i: MediaItem) = maxOf(1, i.user.playCount)
        fun minutes(i: MediaItem) = (i.runtimeMs ?: 0) / 60_000 * plays(i)

        val shows = watched.filter { it.kind == ItemKind.EPISODE }.groupBy { it.seriesId ?: it.seriesName ?: "?" }
            .map { (id, eps) -> Tally(eps.first().seriesName ?: "Unknown show", eps.size, eps.sumOf(::minutes), id) }
            .sortedWith(compareByDescending<Tally> { it.minutes }.thenByDescending { it.count })
        val genres = watched.flatMap { i -> i.genres.map { it to i } }.groupBy({ it.first }, { it.second })
            .map { (g, its) -> Tally(g, its.size, its.sumOf(::minutes)) }
            .sortedByDescending { it.minutes }
        val artists = songs.flatMap { s -> s.artists.ifEmpty { listOfNotNull(s.albumArtist) }.map { it to s } }.groupBy({ it.first }, { it.second })
            .map { (a, ss) -> Tally(a, ss.sumOf(::plays), ss.sumOf(::minutes)) }
            .sortedByDescending { it.count }
        val rewatched = watched.filter { it.kind == ItemKind.MOVIE && it.user.playCount > 1 }
            .map { Tally(it.name, it.user.playCount, minutes(it), it.id) }
            .sortedByDescending { it.count }
        val months = IntArray(12)
        if (year != null) items.forEach { i ->
            i.user.lastPlayedMs?.let { months[civil(it + utcOffsetMs).second - 1]++ }
        }
        return WatchStats(
            year = year,
            films = watched.count { it.kind == ItemKind.MOVIE },
            episodes = watched.count { it.kind == ItemKind.EPISODE },
            shows = shows.size,
            songs = songs.size,
            watchMinutes = watched.sumOf(::minutes),
            listenMinutes = songs.sumOf(::minutes),
            topShows = shows.take(TOP),
            topGenres = genres.take(TOP),
            topArtists = artists.take(TOP),
            rewatched = rewatched.take(TOP),
            byMonth = if (year != null) months.toList() else emptyList(),
        )
    }

    /** The years someone has watched in, newest first (for choosing one). */
    fun years(history: List<MediaItem>, utcOffsetMs: Long = 0): List<Int> =
        history.mapNotNull { it.user.lastPlayedMs }.map { civil(it + utcOffsetMs).first }.distinct().sortedDescending()

    /** Year and month (1–12) of a moment in local milliseconds (days from 1970 to the calendar). */
    internal fun civil(localMs: Long): Pair<Int, Int> {
        val days = localMs.floorDiv(86_400_000L)
        val z = days + 719_468
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val year = (yoe + era * 400 + if (month <= 2) 1 else 0).toInt()
        return year to month
    }

    /** "3 days, 4 hours" / "18 hours" / "45 minutes". */
    fun duration(minutes: Long): String {
        val days = minutes / (60 * 24)
        val hours = (minutes / 60) % 24
        return when {
            days > 0 -> "$days day${if (days == 1L) "" else "s"}" + if (hours > 0) ", $hours hour${if (hours == 1L) "" else "s"}" else ""
            minutes >= 60 -> "${minutes / 60} hour${if (minutes / 60 == 1L) "" else "s"}"
            else -> "$minutes minute${if (minutes == 1L) "" else "s"}"
        }
    }
}
