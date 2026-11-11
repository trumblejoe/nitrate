package dev.nitrate.core.episodes

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem

/** The episode after another in viewing order, specials included where they're placed. */
object UpNext {
    fun after(current: MediaItem, seriesEpisodes: List<MediaItem>): MediaItem? {
        if (current.kind != ItemKind.EPISODE) return null
        val byId = seriesEpisodes.associateBy { it.id }
        val order = EpisodeOrder.viewingOrder(seriesEpisodes.map { it.toRef() })
        val index = order.indexOfFirst { it.id == current.id }
        if (index < 0) return null
        return order.getOrNull(index + 1)?.let { byId[it.id] }
    }

    /**
     * The next [count] unwatched episodes after the last one watched, for keeping downloads
     * topped up. Specials with no placement are left out.
     */
    fun upcoming(seriesEpisodes: List<MediaItem>, count: Int): List<MediaItem> {
        val byId = seriesEpisodes.associateBy { it.id }
        val order = EpisodeOrder.viewingOrder(seriesEpisodes.map { it.toRef() })
        val lastWatched = order.indexOfLast { it.played }
        return order.drop(lastWatched + 1).filterNot { it.played }.take(count).mapNotNull { byId[it.id] }
    }

    internal fun MediaItem.toRef() = EpisodeRef(
        id = id,
        season = seasonNumber,
        episode = indexNumber,
        episodeEnd = indexNumberEnd,
        airsBeforeSeason = airsBeforeSeason,
        airsBeforeEpisode = airsBeforeEpisode,
        airsAfterSeason = airsAfterSeason,
        played = user.played,
        label = episodeLabel.orEmpty(),
    )
}
