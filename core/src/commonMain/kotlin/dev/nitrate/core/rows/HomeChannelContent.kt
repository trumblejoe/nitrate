package dev.nitrate.core.rows

import dev.nitrate.core.model.MediaItem

/**
 * What a TV launcher shows for this app: the system "Play Next" row (things to carry on with) and
 * the app's own channel (things to start), worked out from Home's rows.
 */
data class HomeChannelContent(val watchNext: List<WatchNextEntry>, val channel: List<MediaItem>) {
    data class WatchNextEntry(val item: MediaItem, val kind: Kind) {
        enum class Kind { CONTINUE, NEXT }
    }

    companion object {
        const val WATCH_NEXT_LIMIT = 12
        const val CHANNEL_LIMIT = 20

        fun from(rows: List<MediaRow>): HomeChannelContent {
            val resume = rows.firstOrNull { it.key == "home/continue_watching" }?.items.orEmpty().filter { it.isPlayable }
            val nextUp = rows.firstOrNull { it.key == "home/next_up" }?.items.orEmpty().filter { it.isPlayable }
            val watchNext = (resume.map { WatchNextEntry(it, WatchNextEntry.Kind.CONTINUE) } + nextUp.map { WatchNextEntry(it, WatchNextEntry.Kind.NEXT) })
                .distinctBy { it.item.id }
                // A show partway through needs one entry, not one per episode.
                .distinctBy { it.item.seriesId ?: it.item.id }
                .take(WATCH_NEXT_LIMIT)
            val inPlayNext = watchNext.map { it.item.id }.toSet()
            val latest = rows.filter { it.key.startsWith("home/latest/") }.flatMap { it.items }
            val channel = (nextUp + latest).filterNot { it.id in inPlayNext }.distinctBy { it.id }.take(CHANNEL_LIMIT)
            return HomeChannelContent(watchNext, channel)
        }
    }
}
