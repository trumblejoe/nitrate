package dev.nitrate.core.partners

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.pages.DisplayPrefs

/**
 * One rule for what gives something away, the same on every screen of every app:
 *
 * - Something the user hasn't seen anywhere (not in their own history, not in any of their groups'
 *   records) is a spoiler when "Hide spoilers" is on, and always on a title they watch with a Together
 *   group (watching together is the pact, whatever the setting).
 * - On a show watched with Together partners, an episode the user has seen but a partner hasn't stays
 *   hidden too (unless the user keeps that show to their own spoiler rules).
 * - Whatever a group's schedule locks now is hidden whatever the setting.
 *
 * Hidden means: an episode's picture blurred and its name "Episode 5"; an episode's or a film's
 * description held back. [safe] makes the copy every screen shows, so no screen has to remember.
 */
object Spoilers {
    /**
     * Seen in one of the user's playlists (each playlist keeps its own record, apart from the user's own
     * Watched marks): set by the app at sign-in. For spoilers only: seen anywhere is seen.
     */
    var seenInPlaylists: (String) -> Boolean = { false }

    /** Whether the user has seen [item] anywhere: on their own, in a playlist, or with any of their groups. */
    fun seen(item: MediaItem, info: PartnersInfo?): Boolean =
        item.user.played || seenInPlaylists(item.id) || info?.groups.orEmpty().any { g -> g.ownRecord?.contains(item.id) == true }

    /** Whether [item] is part of a title the user watches with a Together group. */
    fun togetherTitle(item: MediaItem, info: PartnersInfo?): Boolean =
        info?.groups.orEmpty().any { g -> g.together && (g.holds(item.id) || item.seriesId?.let(g::holds) == true) }

    private fun hidden(prefs: DisplayPrefs, item: MediaItem, progress: List<PartnerProgress>, info: PartnersInfo?, locked: Boolean): Boolean {
        if (locked) return true
        if (!prefs.hideSpoilers && !togetherTitle(item, info)) return false
        if (!seen(item, info)) return true
        // Seen, but a Together partner hasn't (only Together groups' progress is given).
        return item.seriesId !in prefs.ownSpoilerShows && progress.any { item.id !in it.watched }
    }

    /** Whether an episode's picture (and name) gives something away. */
    fun picture(prefs: DisplayPrefs, item: MediaItem, progress: List<PartnerProgress>, info: PartnersInfo?, locked: Boolean): Boolean =
        item.spoiler || (item.kind == ItemKind.EPISODE && hidden(prefs, item, progress, info, locked))

    /** Whether an episode's or a film's description gives something away. */
    fun description(prefs: DisplayPrefs, item: MediaItem, progress: List<PartnerProgress>, info: PartnersInfo?, locked: Boolean): Boolean =
        item.spoiler || ((item.kind == ItemKind.EPISODE || item.kind == ItemKind.MOVIE) && hidden(prefs, item, progress, info, locked))

    /** The name a hidden episode goes by: "Episode 5" (just "Episode" without a number). */
    fun name(item: MediaItem): String = item.indexNumber?.let { "Episode $it" } ?: "Episode"

    /**
     * [item] as screens show it: a hidden episode named "Episode 5", without its description and marked
     * to be blurred; a film whose description would give something away, without it; anything else as it is.
     */
    fun safe(item: MediaItem, prefs: DisplayPrefs, progress: List<PartnerProgress>, info: PartnersInfo?, locked: (MediaItem) -> Boolean): MediaItem {
        if (item.spoiler) return item
        val lock = locked(item)
        return when {
            picture(prefs, item, progress, info, lock) -> item.copy(name = name(item), overview = null, spoiler = true)
            item.overview != null && description(prefs, item, progress, info, lock) -> item.copy(overview = null)
            else -> item
        }
    }
}
