package dev.nitrate.core.rows

import dev.nitrate.core.model.AirDates
import dev.nitrate.core.model.MediaItem

/**
 * Home's Upcoming row: for each show the user is watching, when its next episode airs. The card is
 * the show itself with the date as its line ("Monday, April 19th, 2026"), never the episode's
 * number or title, and opens the show.
 */
object Upcoming {
    /** Watched an episode within this long counts as watching the show. */
    const val WATCHING_MS = 365L * 24 * 60 * 60_000

    /**
     * The row: the earliest coming episode of each show in [watching] that has an air date (from
     * today on), as that show from [shows] (falling back to the episode's own show name and art),
     * soonest first.
     */
    fun row(upcoming: List<MediaItem>, watching: Set<String>, shows: Map<String, MediaItem>, nowMs: Long): List<MediaItem> {
        val today = nowMs - nowMs.mod(86_400_000L)
        return upcoming
            .filter { it.seriesId in watching && (it.premiereDateMs ?: -1) >= today }
            .groupBy { it.seriesId!! }
            .mapNotNull { (seriesId, eps) ->
                val next = eps.minBy { it.premiereDateMs!! }
                val show = shows[seriesId] ?: return@mapNotNull null
                show.copy(note = AirDates.long(next.premiereDateMs!!), premiereDateMs = next.premiereDateMs)
            }
            .sortedBy { it.premiereDateMs }
    }

    /** Shows counted as watching: an episode played within [WATCHING_MS], plus favourites. */
    fun watching(playedEpisodes: List<MediaItem>, favouriteShows: Collection<String>, nowMs: Long): Set<String> =
        playedEpisodes.filter { (it.user.lastPlayedMs ?: 0) >= nowMs - WATCHING_MS }.mapNotNull { it.seriesId }.toSet() + favouriteShows
}
