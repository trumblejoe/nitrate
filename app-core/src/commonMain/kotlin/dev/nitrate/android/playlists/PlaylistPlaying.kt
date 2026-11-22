package dev.nitrate.android.playlists

import dev.nitrate.android.storage.DataCache
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.playlists.PlaylistPlaces
import dev.nitrate.core.rows.MediaRow
import dev.nitrate.core.rows.RowDisplay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer

/** A playlist being watched through, as the player sees it: which one, and what comes after this. */
data class PlaylistContext(val playlistId: String, val index: Int, val total: Int, val next: MediaItem?)

/**
 * Video playlists played in their own order: everything in each (whole shows and seasons as their
 * episodes), kept a minute in memory and on disk so a big one opens at once next time. Where each was
 * left comes from Jellyfin itself (each entry's last-played date, so watching in any app on any device
 * counts); Nitrate keeps nothing of its own but, for this run, which playlist a title was just started
 * from (before the server has a play date for it). From those: what comes next in the player, Home's
 * "Continue your playlists" and each playlist page's Continue.
 */
class PlaylistPlaying(
    private val server: MediaServer,
    private val cache: DataCache?,
    private val nowMs: () -> Long,
) {
    private class Kept(val atMs: Long, val entries: List<MediaItem>)

    private val lock = Mutex()
    private val kept = HashMap<String, Kept>()

    /** What was shown last time (this device), for opening at once while it's fetched again. */
    suspend fun cached(playlistId: String): List<MediaItem>? {
        lock.withLock { kept[playlistId]?.let { return it.entries } }
        val account = server.currentAccountKey ?: return null
        return runCatching { cache?.read(account, cacheKey(playlistId), ListSerializer(MediaItem.serializer())) }.getOrNull()
    }

    /** Everything in [playlistId] in its order, fresh within [fresherThanMs]. */
    suspend fun entries(playlistId: String, fresherThanMs: Long = KEEP_MS): List<MediaItem> {
        lock.withLock { kept[playlistId]?.takeIf { nowMs() - it.atMs < fresherThanMs }?.let { return it.entries } }
        val entries = load(playlistId)
        lock.withLock { kept[playlistId] = Kept(nowMs(), entries) }
        server.currentAccountKey?.let { account -> runCatching { cache?.write(account, cacheKey(playlistId), entries, ListSerializer(MediaItem.serializer())) } }
        return entries
    }

    private fun cacheKey(playlistId: String) = "playlist-entries/$playlistId"

    private suspend fun load(playlistId: String): List<MediaItem> = coroutineScope {
        val items = server.playlistItems(playlistId)
        val shows = PlaylistPlaces.wholeShows(items).map { id -> async { id to runCatching { server.allEpisodes(id) }.getOrDefault(emptyList()) } }
        val seasons = PlaylistPlaces.wholeSeasons(items).map { s -> async { s.id to runCatching { server.episodes(s.seriesId ?: s.parentId.orEmpty(), s.id) }.getOrDefault(emptyList()) } }
        PlaylistPlaces.entries(items, (shows + seasons).awaitAll().toMap())
    }

    /** Lets go of what's kept (watched marks changed): the next ask fetches again. */
    suspend fun forget(playlistId: String? = null) = lock.withLock { if (playlistId == null) kept.clear() else kept.remove(playlistId) }

    // Titles just started from a playlist (title id → playlist id), for this run only: the server has no
    // play date for them yet. Rolling on adds the next one, so a sitting carries on before the server catches up.
    private val started = LinkedHashMap<String, String>()

    /** [itemId] is being started from [playlistId] (its page): what follows comes from that playlist. */
    suspend fun startedFrom(playlistId: String, itemId: String) = lock.withLock {
        started.remove(itemId)
        started[itemId] = playlistId
        while (started.size > 200) started.remove(started.keys.first())
    }

    /** The video playlists the user is watching through (played within `PlaylistPlaces.ACTIVE_MS`), most recent first, with their entries. */
    private suspend fun active(maxPlaylists: Int, fresherThanMs: Long?): List<Pair<String, List<MediaItem>>> = coroutineScope {
        val playlists = runCatching { PlaylistPlaces.candidates(server.playlists()) }.getOrDefault(emptyList()).take(maxPlaylists)
        val since = nowMs() - PlaylistPlaces.ACTIVE_MS
        playlists.map { pl ->
            async {
                val entries = runCatching {
                    if (fresherThanMs == null) cached(pl.id) ?: entries(pl.id) else entries(pl.id, fresherThanMs = fresherThanMs)
                }.getOrNull() ?: return@async null
                val at = PlaylistPlaces.lastPlayedMs(entries) ?: return@async null
                if (at < since) null else Triple(pl.id, entries, at)
            }
        }.awaitAll().filterNotNull().sortedByDescending { it.third }.map { it.first to it.second }
    }

    /**
     * The playlist [item] is being watched through, if any, and what follows it there: the one it was
     * just started from (this run), else a playlist watched lately whose last-played entry it is, or
     * just follows (past any watched since).
     */
    suspend fun contextOf(item: MediaItem): PlaylistContext? {
        val from = lock.withLock { started[item.id] }
        if (from != null) {
            val entries = runCatching { cached(from) ?: entries(from) }.getOrNull()
            val at = entries?.let { PlaylistPlaces.placeIndex(it, item.id) }?.takeIf { it >= 0 }
            if (entries != null && at != null) return remember(from, entries, at)
        }
        for ((playlistId, entries) in runCatching { active(LOOK_IN, fresherThanMs = null) }.getOrDefault(emptyList())) {
            val place = PlaylistPlaces.lastPlayed(entries) ?: continue
            val at = PlaylistPlaces.indexNearPlace(entries, place, item.id) ?: continue
            return remember(playlistId, entries, at)
        }
        return null
    }

    private suspend fun remember(playlistId: String, entries: List<MediaItem>, at: Int): PlaylistContext {
        val next = entries.getOrNull(at + 1)
        // The next one rolls on from this playlist too, before the server has its play date.
        if (next != null) startedFrom(playlistId, next.id)
        return PlaylistContext(playlistId, at, entries.size, next)
    }

    /**
     * Home's "Continue your playlists": for each playlist watched lately (most recent first), what carries
     * on from its last-played entry; finished ones drop out.
     */
    suspend fun continueRow(max: Int = 12): MediaRow? {
        val next = runCatching { active(LOOK_IN, fresherThanMs = FRESH_FOR_HOME_MS) }.getOrDefault(emptyList()).take(max)
            .mapNotNull { (_, entries) -> PlaylistPlaces.lastPlayed(entries)?.let { PlaylistPlaces.continueFrom(entries, it) } }
            .distinctBy { it.id }
        return if (next.isEmpty()) null else MediaRow(ROW_KEY, "Continue your playlists", next, RowDisplay())
    }

    /**
     * Marks everything in [playlistId] up to and including [itemId] watched (or, [watched] false, from it on
     * unwatched). [itemId] may be a whole show or season in the playlist: all its episodes count.
     */
    suspend fun markUpTo(playlistId: String, itemId: String, watched: Boolean) {
        val entries = entries(playlistId, fresherThanMs = 0)
        fun inside(e: MediaItem) = e.id == itemId || e.seriesId == itemId || e.seasonId == itemId
        val first = entries.indexOfFirst(::inside)
        val last = entries.indexOfLast(::inside)
        if (first < 0) return
        val chosen = if (watched) entries.take(last + 1) else entries.drop(first)
        chosen.filter { it.user.played != watched }.forEach { runCatching { server.setPlayed(it.id, watched) } }
        forget(playlistId)
    }

    companion object {
        /** Stored in page layouts: never rename. */
        const val ROW_KEY = "home/continue_playlists"
        const val KEEP_MS = 60_000L
        const val FRESH_FOR_HOME_MS = 20_000L
        /** How many video playlists are looked through for one being watched. */
        const val LOOK_IN = 20
    }
}
