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
