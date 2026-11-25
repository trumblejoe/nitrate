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
 * episodes). Where each was left comes from Jellyfin itself (each entry's last-played date, so watching
 * in any app on any device counts); Nitrate keeps nothing of its own but, for this run, which playlist a
 * title was just started from (before the server has a play date for it). From those: what comes next
 * in the player, Home's "Continue your playlists" and each playlist page's Continue.
 *
 * Kept light for the server, since Home asks on every reload: which playlists are being watched comes
 * from one small "played lately" list (`MediaServer.recentlyPlayed`) checked against each playlist's
 * members (ids only, kept on the device until the playlist's etag changes); only those being watched have their whole contents read
 * (shows put in whole as their episodes, also kept [STRUCTURE_MS], in memory and on disk), with the
 * watch state laid over them from the played-lately list, which is always fresh. With the Companion
 * (`MediaServer.continuePlaylists`), Home's row is worked out by the server and none of this is asked.
 */
class PlaylistPlaying(
    private val server: MediaServer,
    private val cache: DataCache?,
    private val nowMs: () -> Long,
) {
    private class Kept(val atMs: Long, val entries: List<MediaItem>)

    @kotlinx.serialization.Serializable
    private data class OnDisk(val atMs: Long, val entries: List<MediaItem>)

    private val lock = Mutex()
    private val kept = HashMap<String, Kept>()
    private class KeptIds(val atMs: Long, val version: String?, val ids: Set<String>)

    @kotlinx.serialization.Serializable
    private data class MembersOnDisk(val version: String, val ids: List<String>)

    private val members = HashMap<String, KeptIds>()
    private var playlistList: Kept? = null

    /** What was shown last time (this device), for opening at once while it's fetched again. */
    suspend fun cached(playlistId: String): List<MediaItem>? {
        lock.withLock { kept[playlistId]?.let { return it.entries } }
        return fromDisk(playlistId)?.entries
    }

    private suspend fun fromDisk(playlistId: String): OnDisk? {
        val account = server.currentAccountKey ?: return null
        return runCatching { cache?.read(account, cacheKey(playlistId), OnDisk.serializer()) }.getOrNull()
    }

    /** Everything in [playlistId] in its order, fresh within [fresherThanMs]. */
    suspend fun entries(playlistId: String, fresherThanMs: Long = KEEP_MS): List<MediaItem> {
        lock.withLock { kept[playlistId]?.takeIf { nowMs() - it.atMs < fresherThanMs }?.let { return it.entries } }
        // Kept on disk from an earlier run, still fresh enough.
        fromDisk(playlistId)?.takeIf { nowMs() - it.atMs < fresherThanMs }?.let { disk ->
            lock.withLock { kept[playlistId] = Kept(disk.atMs, disk.entries) }
            return disk.entries
        }
        val entries = load(playlistId)
        val at = nowMs()
        lock.withLock { kept[playlistId] = Kept(at, entries) }
        server.currentAccountKey?.let { account -> runCatching { cache?.write(account, cacheKey(playlistId), OnDisk(at, entries), OnDisk.serializer()) } }
        return entries
    }

    private fun cacheKey(playlistId: String) = "playlist-kept/$playlistId"

    private suspend fun load(playlistId: String): List<MediaItem> = coroutineScope {
        val items = server.playlistItems(playlistId)
        val shows = PlaylistPlaces.wholeShows(items).map { id -> async { id to runCatching { server.allEpisodes(id) }.getOrDefault(emptyList()) } }
        val seasons = PlaylistPlaces.wholeSeasons(items).map { s -> async { s.id to runCatching { server.episodes(s.seriesId ?: s.parentId.orEmpty(), s.id) }.getOrDefault(emptyList()) } }
        PlaylistPlaces.entries(items, (shows + seasons).awaitAll().toMap())
    }

    /**
     * The ids a playlist holds (titles, and shows or seasons put in whole). Kept, in memory and on the
     * device, for as long as the server's etag for the playlist stays the same (made from when it was
     * last saved, and any change to its entries saves it), so a start or an account switch reads nothing for playlists that haven't
     * changed; without an etag, kept [STRUCTURE_MS] in memory.
     */
    private suspend fun membersOf(playlist: MediaItem): Set<String> {
        val saved = playlist.version
        lock.withLock {
            members[playlist.id]?.takeIf { k -> if (saved != null && k.version != null) k.version == saved else nowMs() - k.atMs < STRUCTURE_MS }
                ?.let { return it.ids }
        }
        val account = server.currentAccountKey.takeIf { saved != null }
        if (account != null) {
            runCatching { cache?.read(account, membersKey(playlist.id), MembersOnDisk.serializer()) }.getOrNull()?.takeIf { it.version == saved }?.let { d ->
                val ids = d.ids.toSet()
                lock.withLock { members[playlist.id] = KeptIds(nowMs(), saved, ids) }
                return ids
            }
        }
        val ids = server.playlistMembers(playlist.id).mapTo(LinkedHashSet()) { it.id }
        lock.withLock { members[playlist.id] = KeptIds(nowMs(), saved, ids) }
        if (account != null && saved != null) runCatching { cache?.write(account, membersKey(playlist.id), MembersOnDisk(saved, ids.toList()), MembersOnDisk.serializer()) }
        return ids
    }

    private fun membersKey(playlistId: String) = "playlist-members/$playlistId"

    // Set when this app changed a playlist (the server's change hook): the next ask reads them all again.
    private var listsChanged = false

    /** This app changed the user's playlists: the list and contents kept are read again next time. */
    fun playlistsChanged() { listsChanged = true }

    /** The user's video playlists, kept [LIST_MS] (a change made here lets it go: [playlistsChanged]). */
    private suspend fun videoPlaylists(): List<MediaItem> {
        lock.withLock {
            if (listsChanged) { listsChanged = false; kept.clear(); members.clear(); playlistList = null }
            playlistList?.takeIf { nowMs() - it.atMs < LIST_MS }?.let { return it.entries }
        }
        val list = PlaylistPlaces.candidates(server.playlists())
        lock.withLock { playlistList = Kept(nowMs(), list) }
        return list
    }

    /** [entries] with the watch state of anything in [recent] (newer than what was kept). */
    private fun withWatchState(entries: List<MediaItem>, recent: Map<String, MediaItem>): List<MediaItem> =
        if (recent.isEmpty()) entries else entries.map { e -> recent[e.id]?.let { e.copy(user = it.user) } ?: e }

    /** Lets go of what's kept (watched marks changed): the next ask fetches again. */
    suspend fun forget(playlistId: String? = null) = lock.withLock {
        if (playlistId == null) { kept.clear(); members.clear(); playlistList = null } else { kept.remove(playlistId); members.remove(playlistId) }
    }

    // Titles just started from a playlist (title id → playlist id), for this run only: the server has no
    // play date for them yet. Rolling on adds the next one, so a sitting carries on before the server catches up.
    private val started = LinkedHashMap<String, String>()

    /** [itemId] is being started from [playlistId] (its page): what follows comes from that playlist. */
    suspend fun startedFrom(playlistId: String, itemId: String) = lock.withLock {
        started.remove(itemId)
        started[itemId] = playlistId
        while (started.size > 200) started.remove(started.keys.first())
    }

    /**
     * The video playlists the user is watching through (something in them played within
     * `PlaylistPlaces.ACTIVE_MS`), most recent first, with their entries and fresh watch state: one
     * "played lately" request, each playlist's kept members, and only the matching ones' contents.
     */
    private suspend fun active(maxPlaylists: Int): List<Pair<String, List<MediaItem>>> = coroutineScope {
        // The (kept) playlist list first: with no video playlists, the library isn't sorted by date played at all.
        val playlists = runCatching { videoPlaylists() }.getOrDefault(emptyList()).take(maxPlaylists)
        if (playlists.isEmpty()) return@coroutineScope emptyList()
        val since = nowMs() - PlaylistPlaces.ACTIVE_MS
        val recent = runCatching { server.recentlyPlayed() }.getOrDefault(emptyList()).filter { (it.user.lastPlayedMs ?: 0) >= since }
        if (recent.isEmpty()) return@coroutineScope emptyList()
        val watched = playlists.map { pl ->
            async {
                val ids = runCatching { membersOf(pl) }.getOrNull() ?: return@async null
                // A title in it, or an episode of a show (or season) put in whole.
                val latest = recent.filter { it.id in ids || it.seriesId in ids || it.seasonId in ids }.maxOfOrNull { it.user.lastPlayedMs ?: 0 }
                latest?.let { pl.id to it }
            }
        }.awaitAll().filterNotNull().sortedByDescending { it.second }
        val byId = recent.associateBy { it.id }
        watched.map { (id, _) ->
            async { runCatching { id to withWatchState(entries(id, fresherThanMs = STRUCTURE_MS), byId) }.getOrNull() }
        }.awaitAll().filterNotNull()
    }

    /**
     * The playlist [item] is being watched through, if any, and what follows it there: the one it was
     * just started from (this run), else a playlist watched lately whose last-played entry it is, or
     * just follows (past any watched since).
     */
    suspend fun contextOf(item: MediaItem): PlaylistContext? {
        val from = lock.withLock { started[item.id] }
        if (from != null) {
            val entries = runCatching { entries(from, fresherThanMs = STRUCTURE_MS) }.getOrNull()
            val at = entries?.let { PlaylistPlaces.placeIndex(it, item.id) }?.takeIf { it >= 0 }
            if (entries != null && at != null) return remember(from, entries, at)
        }
        for ((playlistId, entries) in runCatching { active(LOOK_IN) }.getOrDefault(emptyList())) {
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
        // Worked out by the server (the Companion): no playlist is read here at all.
        server.continuePlaylists(max)?.let { items ->
            return if (items.isEmpty()) null else MediaRow(ROW_KEY, "Continue your playlists", items.distinctBy { it.id }, RowDisplay())
        }
        val next = runCatching { active(LOOK_IN) }.getOrDefault(emptyList()).take(max)
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
        /** How long what a playlist holds is trusted before it's read again (watch state comes fresh regardless). */
        const val STRUCTURE_MS = 6 * 60 * 60_000L
        /** How long the list of the user's playlists is kept. */
        /** The playlist list rarely changes and costs the server seconds on a large library: kept half an hour. */
        const val LIST_MS = 30 * 60_000L
        /** How many video playlists are looked through for one being watched. */
        const val LOOK_IN = 20
    }
}
