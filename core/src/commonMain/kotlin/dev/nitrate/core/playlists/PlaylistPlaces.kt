package dev.nitrate.core.playlists

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem

/**
 * A playlist's contents as facts from the server: its [items] as stored (titles, and shows or seasons
 * put in whole) and those shows' and seasons' [episodes] (by their id), each with only an id, kind,
 * numbers and the user's watch state. [PlaylistPlaces.entries] makes the watching order from them.
 */
data class PlaylistFacts(val playlistId: String, val items: List<MediaItem>, val episodes: Map<String, List<MediaItem>>)

/** Where the user is in a playlist. */
data class PlaylistStatus(val watched: Int, val total: Int, val next: MediaItem?, val resuming: Boolean) {
    val share: Float get() = if (total == 0) 0f else watched / total.toFloat()
    /** "34 of 52 watched". */
    val line: String get() = if (total == 0) "Nothing to watch yet" else if (watched == total) "All $total watched" else "$watched of $total watched"
}

/**
 * Video playlists played in their order: a playlist's order is the watch order the user curated
 * (often with a plugin that builds such playlists). Shows and seasons put in whole count as their
 * episodes. Where a playlist was left comes from Jellyfin's own play dates, so watching in any app
 * on any device counts and nothing is stored.
 */
object PlaylistPlaces {
    /** Video playlists (music playlists play as queues). */
    fun candidates(playlists: List<MediaItem>): List<MediaItem> = playlists.filter { it.mediaKind != dev.nitrate.core.model.MediaKind.AUDIO }

    /** Shows put in a playlist whole: their episodes are fetched to stand in for them. */
    fun wholeShows(items: List<MediaItem>): List<String> = items.filter { it.kind == ItemKind.SERIES }.map { it.id }.distinct()

    /** Seasons put in a playlist whole: their episodes stand in for them too (keyed by season id). */
    fun wholeSeasons(items: List<MediaItem>): List<MediaItem> = items.filter { it.kind == ItemKind.SEASON }.distinctBy { it.id }

    /**
     * Everything to watch, in the playlist's order, from its [items]. A whole show or season is its
     * [episodes] (by its id) in the show's own order. Specials (season 0) and episodes not in the
     * library are left out of whole shows; anything put in the playlist by hand stays.
     */
    fun entries(items: List<MediaItem>, episodes: Map<String, List<MediaItem>>): List<MediaItem> {
        fun watchable(e: MediaItem) = !e.isMissing && (e.seasonNumber ?: 1) > 0
        return items.flatMap { t -> if (t.kind == ItemKind.SERIES || t.kind == ItemKind.SEASON) episodes[t.id].orEmpty().filter(::watchable) else listOf(t) }
            .filter { it.kind == ItemKind.MOVIE || it.kind == ItemKind.EPISODE || it.kind == ItemKind.VIDEO }
            .distinctBy { it.id }
    }

    /**
     * How much is watched, and what's next: the one under way, else the first unwatched after the
     * furthest one watched, else the first unwatched at all.
     */
    fun progress(entries: List<MediaItem>): PlaylistStatus {
        val watched = entries.count { it.user.played }
        val resuming = entries.firstOrNull { !it.user.played && it.user.positionMs > 0 }
        val furthest = entries.indexOfLast { it.user.played }
        val next = resuming
            ?: entries.drop(furthest + 1).firstOrNull { !it.user.played }
            ?: entries.firstOrNull { !it.user.played }
        return PlaylistStatus(watched, entries.size, next, resuming != null)
    }

    /** The entry played most recently (Jellyfin's play dates, any app), or null when nothing in it has been. */
    fun lastPlayed(entries: List<MediaItem>): Int? =
        entries.withIndex().filter { it.value.user.lastPlayedMs != null }.maxByOrNull { it.value.user.lastPlayedMs!! }?.index

    /** When [entries] were last played from, if ever. */
    fun lastPlayedMs(entries: List<MediaItem>): Long? = lastPlayed(entries)?.let { entries[it].user.lastPlayedMs }

    /**
     * Whether [itemId] carries on from a playlist left at [place]: the place itself, the one before
     * (gone back), or up to [PLACE_AHEAD] past it, past any already watched since. Gives its index.
     */
    fun indexNearPlace(entries: List<MediaItem>, place: Int, itemId: String): Int? {
        if (place !in entries.indices) return null
        val at = entries.indexOfFirst { it.id == itemId }
        val watchedAfter = entries.drop(place + 1).indexOfFirst { !it.user.played }.let { if (it < 0) entries.size else it }
        return at.takeIf { it >= 0 && it in (place - 1)..(place + watchedAfter + PLACE_AHEAD) }
    }

    /** What to carry on with in a playlist left at [place]: that one if not finished, else the next unwatched after it. */
    fun continueFrom(entries: List<MediaItem>, place: Int): MediaItem? =
        if (place !in entries.indices) null else entries.drop(place).firstOrNull { !it.user.played }

    /**
     * Where [startedId] sits among [entries]: the entry itself, or for a whole show or season started
     * from a playlist page, its first unwatched episode (else its first). -1 when it isn't there.
     */
    fun placeIndex(entries: List<MediaItem>, startedId: String): Int {
        entries.indexOfFirst { it.id == startedId }.takeIf { it >= 0 }?.let { return it }
        fun inside(e: MediaItem) = e.seriesId == startedId || e.seasonId == startedId
        return entries.indexOfFirst { inside(it) && !it.user.played }.takeIf { it >= 0 } ?: entries.indexOfFirst(::inside)
    }

    /** The entry a playlist page marks NEXT: carrying on from where it was left, else the progress's next. */
    fun nextUp(entries: List<MediaItem>): MediaItem? =
        lastPlayed(entries)?.let { continueFrom(entries, it) } ?: progress(entries).next

    const val PLACE_AHEAD = 3

    /** A playlist counts as being watched through when something in it was played within this long. */
    const val ACTIVE_MS = 60L * 86_400_000L
}
