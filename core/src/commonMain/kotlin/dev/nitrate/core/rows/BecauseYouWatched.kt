package dev.nitrate.core.rows

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem

/**
 * "Because you watched X" rows: titles like the movies and shows watched most recently,
 * leaving out what's been seen and what an earlier row already offers.
 */
object BecauseYouWatched {
    /** Row keys are fixed slots, so display settings survive the seed changing. */
    fun key(slot: Int) = "home/because_you_watched_$slot"

    /** What a row is based on: a movie, or the show an episode belongs to. */
    data class Seed(val id: String, val name: String)

    /** Up to [count] distinct seeds from recently watched items, most recent first. */
    fun seeds(recentlyWatched: List<MediaItem>, count: Int): List<Seed> =
        recentlyWatched.mapNotNull { item ->
            when {
                item.kind == ItemKind.EPISODE -> item.seriesId?.let { Seed(it, item.seriesName ?: item.name) }
                item.kind == ItemKind.MOVIE || item.kind == ItemKind.SERIES -> Seed(item.id, item.name)
                else -> null
            }
        }.distinctBy { it.id }.take(count)

    /** Similar titles worth suggesting: not watched, not the seed, not already shown. */
    fun pick(similar: List<MediaItem>, seed: Seed, shown: Set<String>, limit: Int = 16): List<MediaItem> =
        similar.filter { it.id != seed.id && it.id !in shown && !it.user.played }.take(limit)

    /** Builds the rows; rows with too little to show are dropped. */
    suspend fun rows(recentlyWatched: List<MediaItem>, similar: suspend (String) -> List<MediaItem>, count: Int = 2, minItems: Int = 3): List<MediaRow> {
        val shown = mutableSetOf<String>()
        // Watched titles never come back as suggestions.
        recentlyWatched.forEach { shown += it.id; it.seriesId?.let(shown::add) }
        val rows = mutableListOf<MediaRow>()
        // Ask for every seed's suggestions at once, then pick in order.
        val seeds = seeds(recentlyWatched, count * 2)
        val found = kotlinx.coroutines.coroutineScope {
            seeds.map { seed -> async { runCatching { similar(seed.id) }.getOrDefault(emptyList()) } }.awaitAll()
        }
        for ((seed, suggestions) in seeds.zip(found)) {
            if (rows.size == count) break
            val items = pick(suggestions, seed, shown)
            if (items.size < minItems) continue
            shown += items.map { it.id }
            rows += MediaRow(key(rows.size + 1), "Because you watched ${seed.name}", items, RowDisplay())
        }
        return rows
    }
}
