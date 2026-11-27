package dev.nitrate.core.playlists

import dev.nitrate.core.model.MediaItem
import kotlinx.serialization.Serializable

/**
 * A playlist's own record, apart from the user's: which of its entries were watched in it, where each
 * one left off, and the last one played from it. Watching an episode on its own never touches it, and
 * watching from the playlist never touches the user's own Watched marks or resume points (a playlist
 * of 300 entries watched in its own order doesn't lose its place because the show was watched on its
 * own). Kept with the user's synced settings, one entry per playlist.
 */
@Serializable
data class PlaylistRecord(
    val playlistId: String,
    val watched: Set<String> = emptySet(),
    /** Where each started entry left off (ms); an entry watched to the end has none. */
    val resume: Map<String, Long> = emptyMap(),
    /** The entry last played from the playlist, and when: where it carries on from. */
    val lastId: String? = null,
    val lastAtMs: Long = 0,
) {
    fun watched(itemId: String) = itemId in watched
}

/**
 * The rules for a playlist's own record: laying it over the playlist's entries (so the playlist's
 * ordering rules, [PlaylistPlaces], read the playlist's progress, not the user's own), starting it
 * from the user's own progress once, and what a sitting in it changes.
 */
object PlaylistRecords {
    /** Watched to here (of its length) counts as watched, as Jellyfin's own default. */
    const val WATCHED_SHARE = 0.9

    /** Less than this in: not worth a resume point (as Jellyfin's own minimum). */
    const val MIN_RESUME_MS = 60_000L

    /**
     * [entries] as the playlist knows them: each one's watched mark, resume point and last-played
     * time from [record] in place of the user's own.
     */
    fun apply(entries: List<MediaItem>, record: PlaylistRecord): List<MediaItem> = entries.map { e ->
        e.copy(
            user = e.user.copy(
                played = e.id in record.watched,
                positionMs = record.resume[e.id] ?: 0,
                playedPercentage = null,
                lastPlayedMs = if (e.id == record.lastId) record.lastAtMs else null,
            ),
        )
    }

    /**
     * The record a playlist starts with: its progress as it stood by the user's own marks (so a
     * playlist 212 entries in isn't put back to the start), taken once; from then on the two are apart.
     */
    fun seed(playlistId: String, entries: List<MediaItem>): PlaylistRecord {
        val last = PlaylistPlaces.lastPlayed(entries)?.let { entries[it] }
        return PlaylistRecord(
            playlistId = playlistId,
            watched = entries.filter { it.user.played }.map { it.id }.toSet(),
            resume = entries.filter { !it.user.played && it.user.positionMs >= MIN_RESUME_MS }.associate { it.id to it.user.positionMs },
            lastId = last?.id,
            lastAtMs = last?.user?.lastPlayedMs ?: 0,
        )
    }

    /**
     * A sitting of [itemId] from the playlist stopped at [positionMs] of [runtimeMs] (null: not known)
     * at [atMs]: watched when it got to [WATCHED_SHARE] (or [ended]), else its resume point.
     */
    fun stopped(record: PlaylistRecord, itemId: String, positionMs: Long, runtimeMs: Long?, ended: Boolean, atMs: Long): PlaylistRecord {
        val done = ended || (runtimeMs != null && runtimeMs > 0 && positionMs >= runtimeMs * WATCHED_SHARE)
        return record.copy(
            watched = if (done) record.watched + itemId else record.watched,
            resume = if (done || positionMs < MIN_RESUME_MS) record.resume - itemId else record.resume + (itemId to positionMs),
            lastId = itemId,
            lastAtMs = atMs,
        )
    }

    /**
     * Everything in [entries] up to and including [itemId] watched in the playlist (or, [watched]
     * false, from it on not); [itemId] may be a whole show or season put in the playlist.
     */
    fun markUpTo(record: PlaylistRecord, entries: List<MediaItem>, itemId: String, watched: Boolean): PlaylistRecord {
        fun inside(e: MediaItem) = e.id == itemId || e.seriesId == itemId || e.seasonId == itemId
        val first = entries.indexOfFirst(::inside)
        val last = entries.indexOfLast(::inside)
        if (first < 0) return record
        val chosen = (if (watched) entries.take(last + 1) else entries.drop(first)).map { it.id }.toSet()
        return record.copy(
            watched = if (watched) record.watched + chosen else record.watched - chosen,
            resume = record.resume - chosen,
        )
    }
}

/**
 * What Up Next says it follows: "NEXT IN KIDS BEDTIME · 9s" (a playlist sitting), "NEXT EPISODE · 9s"
 * (the show's order), "NEXT IN THE COLLECTION" (a film's collection, with no countdown).
 */
fun upNextHeading(secondsLeft: Int?, playlist: String?, next: MediaItem): String {
    val what = when {
        playlist != null -> "NEXT IN ${playlist.uppercase()}"
        next.kind == dev.nitrate.core.model.ItemKind.EPISODE -> "NEXT EPISODE"
        else -> "NEXT IN THE COLLECTION"
    }
    return if (secondsLeft == null) what else "$what · ${secondsLeft}s"
}
