package dev.nitrate.core.diary

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.UserState

/**
 * One watch in the user's diary (Companion plugin, feature [Diary.FEATURE]): a film or episode,
 * when it was finished, and the user's own [rating] (1–10, half stars) and [note]. [logged]: added
 * by hand rather than recorded while watching.
 */
data class DiaryEntry(
    val id: String,
    val itemId: String,
    val kind: ItemKind,
    val name: String,
    val seriesId: String?,
    val seriesName: String?,
    val season: Int?,
    val episode: Int?,
    val year: Int?,
    val runtimeMs: Long?,
    val genres: List<String>,
    val watchedAtMs: Long,
    val rating: Int?,
    val note: String?,
    val logged: Boolean,
    /** Watched for these watch groups (kept apart from the user's own watching); empty: their own. */
    val groupIds: List<String> = emptyList(),
) {
    /** Watched with a group rather than on the user's own. */
    val withGroup: Boolean get() = groupIds.isNotEmpty()

    val title: String get() = if (kind == ItemKind.EPISODE) seriesName ?: name else name
    val episodeLabel: String? get() = if (kind == ItemKind.EPISODE) "S${season ?: "?"}:E${episode ?: "?"} · $name" else null
}

/**
 * The watch diary: every film and episode finished (in any app, kept by the server's Companion
 * plugin), with the date, plus a rating and note. Jellyfin alone keeps only the last time each
 * title was watched; the diary keeps every time, so "Your year" counts exactly.
 */
object Diary {
    const val FEATURE = "diary"

    /** "★★★½" for a rating of 7 (out of 10). */
    fun stars(rating: Int?): String {
        val r = rating ?: return ""
        return "★".repeat(r / 2) + if (r % 2 == 1) "½" else ""
    }

    /** Which entries are rewatches: a title already in the diary earlier. */
    fun rewatches(entries: List<DiaryEntry>): Set<String> {
        val seen = mutableSetOf<String>()
        return entries.sortedBy { it.watchedAtMs }.mapNotNull { e -> if (!seen.add(e.itemId)) e.id else null }.toSet()
    }

    /**
     * Films as a Letterboxd import (Title, Year, WatchedDate, Rating out of 5 in halves, Rewatch,
     * Review). [utcOffsetMs]: the device's time zone, for the dates.
     */
    fun letterboxdCsv(entries: List<DiaryEntry>, utcOffsetMs: Long = 0): String {
        val rewatch = rewatches(entries)
        val rows = entries.filter { it.kind == ItemKind.MOVIE }.sortedBy { it.watchedAtMs }.map { e ->
            listOf(
                e.name,
                e.year?.toString().orEmpty(),
                date(e.watchedAtMs + utcOffsetMs),
                e.rating?.let { r -> if (r % 2 == 0) "${r / 2}" else "${r / 2}.5" }.orEmpty(),
                if (e.id in rewatch) "true" else "false",
                e.note.orEmpty(),
            ).joinToString(",") { csv(it) }
        }
        return (listOf("Title,Year,WatchedDate,Rating,Rewatch,Review") + rows).joinToString("\n") + "\n"
    }

    /**
     * The diary as "history" for watching stats: one item per title watched in the period, its
     * plays counted and its last watch as the date. Titles finished before the diary began
     * ([history] items last played before the first entry) are added as before.
     */
    /** "With Tommy & Suzy" for an entry watched with groups ([groupName] names a group id; null: no longer in it), else null. */
    fun withLabel(entry: DiaryEntry, groupName: (String) -> String?): String? =
        if (!entry.withGroup) null else "With " + entry.groupIds.map { groupName(it) ?: "a past group" }.distinct().joinToString(", ")

    fun asHistory(entries: List<DiaryEntry>, history: List<MediaItem>): List<MediaItem> {
        val start = entries.minOfOrNull { it.watchedAtMs } ?: return history
        val fromDiary = entries.groupBy { it.itemId }.map { (_, plays) ->
            val e = plays.maxBy { it.watchedAtMs }
            MediaItem(
                e.itemId, e.name, e.kind,
                seriesId = e.seriesId, seriesName = e.seriesName, seasonNumber = e.season, indexNumber = e.episode,
                productionYear = e.year, runtimeMs = e.runtimeMs, genres = e.genres,
                user = UserState(played = true, lastPlayedMs = e.watchedAtMs, playCount = plays.size),
            )
        }
        val inDiary = fromDiary.map { it.id }.toSet()
        val before = history.filter { h ->
            val watched = h.kind == ItemKind.MOVIE || h.kind == ItemKind.EPISODE
            !watched || (h.id !in inDiary && (h.user.lastPlayedMs ?: 0) < start)
        }
        return fromDiary + before
    }

    /** "2026-03-01" for local milliseconds. */
    internal fun date(localMs: Long): String {
        val days = localMs.floorDiv(86_400_000L)
        val z = days + 719_468
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = doy - (153 * mp + 2) / 5 + 1
        val month = if (mp < 10) mp + 3 else mp - 9
        val year = yoe + era * 400 + if (month <= 2) 1 else 0
        return "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
    }

    private fun csv(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
