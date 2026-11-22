package dev.nitrate.core.playback

import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem

/**
 * What Play on a card means for things that aren't played themselves: a show plays its next
 * episode (the server's Next Up for it, resuming), else its first; a season its first episode not
 * watched yet, else its first. Anything else plays as it is.
 */
object PlayTargets {
    /** Whether Play on [item] picks an episode first. */
    fun needsEpisode(item: MediaItem): Boolean = item.kind == ItemKind.SERIES || item.kind == ItemKind.SEASON

    suspend fun episodeFor(server: MediaServer, item: MediaItem): MediaItem? = when (item.kind) {
        ItemKind.SERIES -> runCatching { server.nextUpFor(item.id) }.getOrNull()
            ?: firstOf(server, item.id, runCatching { server.seasons(item.id) }.getOrDefault(emptyList()).firstOrNull()?.id)
        ItemKind.SEASON -> {
            val seriesId = item.seriesId ?: return null
            val episodes = runCatching { server.episodes(seriesId, item.id) }.getOrDefault(emptyList()).filterNot { it.isMissing }
            pick(episodes)
        }
        else -> item
    }

    private suspend fun firstOf(server: MediaServer, seriesId: String, seasonId: String?): MediaItem? {
        seasonId ?: return null
        return pick(runCatching { server.episodes(seriesId, seasonId) }.getOrDefault(emptyList()).filterNot { it.isMissing })
    }

    /** The first not yet watched (it resumes if part way), else the first. */
    fun pick(episodes: List<MediaItem>): MediaItem? = episodes.firstOrNull { !it.user.played } ?: episodes.firstOrNull()
}
