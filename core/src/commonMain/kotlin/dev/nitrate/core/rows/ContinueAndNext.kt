package dev.nitrate.core.rows

import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.partners.TogetherCards

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

    /**
     * Continue Watching with what's watched together in it (one row to check, not two): invites
     * first, then what's part way, then the shared titles that are the user's move (catching up
     * included), then Next Up, then shared titles waiting on the others. A card stands once: a
     * title already in the row by id, or a show already in it by an episode, isn't added again;
     * Next Up leaves out the shared titles (their card says where everyone is).
     */
    fun withTogether(resume: List<MediaItem>, nextUp: List<MediaItem>, together: TogetherCards): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        val ids = mutableSetOf<String>()
        val shows = mutableSetOf<String>()
        fun add(item: MediaItem): Boolean {
            if (item.id in ids || item.id in shows || item.seriesId?.let { it in shows } == true) return false
            out += item; ids += item.id; item.seriesId?.let { shows += it }
            return true
        }
        together.invites.forEach(::add)
        resume.forEach(::add)
        together.yours.forEach(::add)
        nextUp.filter { (it.seriesId ?: it.id) !in together.titles }.forEach(::add)
        together.waiting.forEach(::add)
        return out
    }

    /** The two-row layout: Continue Watching takes the shared cards, Next Up leaves their titles out. */
    fun continueWithTogether(resume: List<MediaItem>, together: TogetherCards): List<MediaItem> = withTogether(resume, emptyList(), together)

    fun nextUpWithout(nextUp: List<MediaItem>, together: TogetherCards): List<MediaItem> =
        nextUp.filter { (it.seriesId ?: it.id) !in together.titles }
}
