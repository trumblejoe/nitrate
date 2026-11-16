package dev.nitrate.core.notifications

import dev.nitrate.core.model.MediaItem

/** New episodes of one show, for one notification. */
data class NewEpisodeAlert(val seriesId: String, val seriesName: String, val episodes: List<MediaItem>) {
    val title: String get() = if (episodes.size == 1) "New episode of $seriesName" else "${episodes.size} new episodes of $seriesName"

    val text: String get() = episodes.sortedWith(compareBy({ it.seasonNumber ?: 0 }, { it.indexNumber ?: 0 })).joinToString(", ") { e ->
        listOfNotNull(e.seasonNumber?.let { s -> e.indexNumber?.let { "S${s}E$it" } }, e.name.takeIf { it.isNotBlank() }).joinToString(" · ")
    }

    /** What tapping it opens: the episode itself when there's one, else the show. */
    val openItemId: String get() = episodes.singleOrNull()?.id ?: seriesId
}

/** Which recently added episodes to tell this user about. */
object NewEpisodes {
    /**
     * Episodes in [recent] added after [sinceMs], of shows in [following], that the user hasn't
     * watched, grouped per show (most recent show first).
     */
    fun alerts(recent: List<MediaItem>, following: Set<String>, sinceMs: Long): List<NewEpisodeAlert> =
        recent.asSequence()
            .filter { it.isEpisode && !it.user.played }
            .filter { (it.dateAddedMs ?: 0) > sinceMs }
            .filter { it.seriesId in following }
            .groupBy { it.seriesId!! }
            .map { (series, episodes) -> NewEpisodeAlert(series, episodes.first().seriesName ?: "a show you watch", episodes) }
            .sortedByDescending { a -> a.episodes.maxOf { it.dateAddedMs ?: 0 } }

    /** Where to start counting from: newest add seen, so nothing is announced twice. */
    fun newestAdded(recent: List<MediaItem>, previous: Long): Long = maxOf(previous, recent.maxOfOrNull { it.dateAddedMs ?: 0 } ?: 0)
}

/**
 * A show starting a new season, for one notification. It names the season only: no episode titles
 * or counts, which can give away how the season goes.
 */
data class NewSeasonAlert(val seriesId: String, val seriesName: String, val seasonNumber: Int, val seasonId: String?) {
    val title: String get() = "New season of $seriesName"
    val text: String get() = "Season $seasonNumber is here"

    /** What tapping it opens: the new season, else the show. */
    val openItemId: String get() = seasonId ?: seriesId
}

/** Which shows just got the first episode of a new season, for this user. */
object NewSeasons {
    /**
     * First episodes (of season 1 or later; specials don't count) in [recent], added after
     * [sinceMs], of [shows] (followed or watched before), not yet watched: one alert per show,
     * for its newest season, most recent first.
     */
    fun alerts(recent: List<MediaItem>, shows: Set<String>, sinceMs: Long): List<NewSeasonAlert> =
        recent.asSequence()
            .filter { it.isEpisode && !it.user.played && it.indexNumber == 1 && (it.seasonNumber ?: 0) >= 1 }
            .filter { (it.dateAddedMs ?: 0) > sinceMs }
            .filter { it.seriesId in shows }
            .groupBy { it.seriesId!! }
            .map { (series, firsts) ->
                val first = firsts.maxBy { it.seasonNumber ?: 0 }
                first.dateAddedMs to NewSeasonAlert(series, first.seriesName ?: "a show you watch", first.seasonNumber!!, first.seasonId)
            }
            .sortedByDescending { it.first ?: 0 }
            .map { it.second }
}
