package dev.nitrate.core.rows

import dev.nitrate.core.model.MediaItem

/**
 * Continue Watching and Next Up as one row (`DisplayPrefs.combineContinueNextUp`): what's part way
 * first, newest first as the server gives it, then the next episodes of shows not already in it,
 * so a show appears once. Jellyfin has no such row; Nitrate puts it together.
 */
object ContinueAndNext {
    const val KEY = "home/continue_and_next"
    const val TITLE = "Continue and Next Up"

    fun merge(resume: List<MediaItem>, nextUp: List<MediaItem>): List<MediaItem> {
        val shows = resume.mapNotNull { it.seriesId }.toSet()
        val ids = resume.map { it.id }.toSet()
        return resume + nextUp.filter { it.id !in ids && (it.seriesId == null || it.seriesId !in shows) }
    }
}
