package dev.nitrate.android.franchise

import dev.nitrate.android.storage.DataCache
import dev.nitrate.android.storage.DisplaySettingsStore
import dev.nitrate.core.MediaServer
import dev.nitrate.core.franchise.FranchiseEntry
import dev.nitrate.core.franchise.Franchises
import dev.nitrate.core.franchise.WatchOrder
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.pages.DisplayPrefs
import dev.nitrate.core.rows.MediaRow
import dev.nitrate.core.rows.RowDisplay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer

/** A playlist being watched through, as the player sees it: which one, and what comes after this. */
data class PlaylistContext(val playlistId: String, val index: Int, val total: Int, val next: MediaItem?)

/** Where an item sits in one of the user's franchises, for its details page. */
data class PlaylistPlace(val playlist: MediaItem, val index: Int, val total: Int, val before: MediaItem?, val after: MediaItem?) {
    /** "47 of 712 · after Arrow S3:E7 · before The Flash S1:E9". */
    val line: String get() = listOfNotNull(
        "${index + 1} of $total",
        before?.let { "after " + label(it) },
        after?.let { "before " + label(it) },
    ).joinToString(" · ")

    private fun label(i: MediaItem) = Franchises.code(i)?.let { "${i.seriesName ?: ""} $it".trim() } ?: i.name
}

/**
 * Video playlists played in their own order: everything in each (whole shows and seasons as their
 * episodes, in the order chosen for it), kept a minute in memory and on disk so a big one opens at once
 * next time. Where each was left comes from Jellyfin itself (each entry's last-played date, so watching
 * in any app on any device counts); Nitrate keeps nothing of its own but, for this run, which playlist a
 * title was just started from (before the server has a play date for it). From those: what comes next in
 * the player, Home's "Continue your playlists" and each page's Continue.
 */
class PlaylistOrders(
    private val server: MediaServer,
    private val store: DisplaySettingsStore,
    private val cache: DataCache?,
    private val nowMs: () -> Long,
) {
    private class Kept(val atMs: Long, val order: WatchOrder, val entries: List<FranchiseEntry>)

    private val lock = Mutex()
    private val kept = HashMap<String, Kept>()

    private suspend fun prefs(): DisplayPrefs? = server.userId?.let { runCatching { store.prefs(it).first() }.getOrNull() }

    /** The order chosen for [playlistId], else the playlist's own. */
    suspend fun orderOf(playlistId: String): WatchOrder = prefs()?.franchiseOrders?.get(playlistId) ?: WatchOrder.PLAYLIST

    /** What was shown last time (this device), for opening at once while it's fetched again. */
    suspend fun cached(playlistId: String, order: WatchOrder): List<FranchiseEntry>? {
        lock.withLock { kept[playlistId]?.takeIf { it.order == order }?.let { return it.entries } }
        val account = server.currentAccountKey ?: return null
        val items = runCatching { cache?.read(account, "franchise/$playlistId/$order", ListSerializer(MediaItem.serializer())) }.getOrNull() ?: return null
        val crossovers = Franchises.crossovers(items)
        return items.map { FranchiseEntry(it, crossovers[it.id]) }
    }

    /** Everything in [playlistId] in [order], fresh within [fresherThanMs]. */
    suspend fun entries(playlistId: String, order: WatchOrder? = null, fresherThanMs: Long = KEEP_MS): List<FranchiseEntry> {
        val o = order ?: orderOf(playlistId)
        lock.withLock { kept[playlistId]?.takeIf { it.order == o && nowMs() - it.atMs < fresherThanMs }?.let { return it.entries } }
        val entries = load(playlistId, o)
        lock.withLock { kept[playlistId] = Kept(nowMs(), o, entries) }
        server.currentAccountKey?.let { account -> runCatching { cache?.write(account, "franchise/$playlistId/$o", entries.map { it.item }, ListSerializer(MediaItem.serializer())) } }
        return entries
    }

    private suspend fun load(playlistId: String, order: WatchOrder): List<FranchiseEntry> = coroutineScope {
        val items = server.playlistItems(playlistId)
        val shows = Franchises.wholeShows(items).map { id -> async { id to runCatching { server.allEpisodes(id) }.getOrDefault(emptyList()) } }
        val seasons = Franchises.wholeSeasons(items).map { s -> async { s.id to runCatching { server.episodes(s.seriesId ?: s.parentId.orEmpty(), s.id) }.getOrDefault(emptyList()) } }
        Franchises.entries(items, (shows + seasons).awaitAll().toMap(), order)
    }

    /** The playlist itself as last seen on this device (its name and pictures), for opening at once. */
    suspend fun keptPlaylist(playlistId: String): MediaItem? =
        server.currentAccountKey?.let { a -> runCatching { cache?.read(a, "franchise/$playlistId/playlist", MediaItem.serializer()) }.getOrNull() }

    suspend fun keepPlaylist(playlist: MediaItem) {
        server.currentAccountKey?.let { a -> runCatching { cache?.write(a, "franchise/${playlist.id}/playlist", playlist, MediaItem.serializer()) } }
    }

    /** Lets go of what's kept (watched marks changed): the next ask fetches again. */
    suspend fun forget(playlistId: String? = null) = lock.withLock { if (playlistId == null) kept.clear() else kept.remove(playlistId) }

    // Titles just started from a playlist (title id → playlist id), for this run only: the server has no
    // play date for them yet. Rolling on adds the next one, so a sitting carries on before the server catches up.
    private val started = LinkedHashMap<String, String>()

    /** [itemId] is being started from [playlistId] (its page or hub): what follows comes from that playlist. */
    suspend fun startedFrom(playlistId: String, itemId: String) = lock.withLock {
        started.remove(itemId)
        started[itemId] = playlistId
        while (started.size > 200) started.remove(started.keys.first())
    }

    /** The video playlists the user is watching through (played within `Franchises.ACTIVE_MS`), most recent first, with their entries. */
    private suspend fun active(maxPlaylists: Int, fresherThanMs: Long?): List<Pair<String, List<FranchiseEntry>>> = coroutineScope {
        val playlists = runCatching { Franchises.candidates(server.playlists()) }.getOrDefault(emptyList()).take(maxPlaylists)
        val since = nowMs() - Franchises.ACTIVE_MS
        playlists.map { pl ->
            async {
                val entries = runCatching {
                    if (fresherThanMs == null) cached(pl.id, orderOf(pl.id)) ?: entries(pl.id) else entries(pl.id, fresherThanMs = fresherThanMs)
                }.getOrNull() ?: return@async null
                val at = Franchises.lastPlayedMs(entries) ?: return@async null
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
            val entries = runCatching { cached(from, orderOf(from)) ?: entries(from) }.getOrNull()
            val at = entries?.let { Franchises.placeIndex(it, item.id) }?.takeIf { it >= 0 }
            if (entries != null && at != null) return remember(from, entries, at)
        }
        for ((playlistId, entries) in runCatching { active(LOOK_IN, fresherThanMs = null) }.getOrDefault(emptyList())) {
            val place = Franchises.lastPlayed(entries) ?: continue
            val at = Franchises.indexNearPlace(entries, place, item.id) ?: continue
            return remember(playlistId, entries, at)
        }
        return null
    }

    private suspend fun remember(playlistId: String, entries: List<FranchiseEntry>, at: Int): PlaylistContext {
        val next = entries.getOrNull(at + 1)?.item
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
            .mapNotNull { (_, entries) -> Franchises.lastPlayed(entries)?.let { Franchises.continueFrom(entries, it) } }
            .distinctBy { it.id }
        return if (next.isEmpty()) null else MediaRow(ROW_KEY, "Continue your playlists", next, RowDisplay())
    }

    /** The user's franchises (video playlists not set aside) that hold [item], with where it sits in each. */
    suspend fun placesOf(item: MediaItem, maxPlaylists: Int = 30): List<PlaylistPlace> = coroutineScope {
        val hidden = prefs()?.franchiseHidden.orEmpty()
        val playlists = runCatching { Franchises.candidates(server.playlists()) }.getOrDefault(emptyList()).filter { it.id !in hidden }.take(maxPlaylists)
        playlists.map { pl ->
            async {
                val items = runCatching { server.playlistItems(pl.id) }.getOrDefault(emptyList())
                val holds = items.any { it.id == item.id || (item.seriesId != null && it.id == item.seriesId) || (it.kind == ItemKind.SEASON && it.id == item.seasonId) }
                if (!holds) return@async null
                val entries = runCatching { entries(pl.id) }.getOrNull() ?: return@async null
                val at = entries.indexOfFirst { it.item.id == item.id }.takeIf { it >= 0 } ?: return@async null
                PlaylistPlace(pl, at, entries.size, entries.getOrNull(at - 1)?.item, entries.getOrNull(at + 1)?.item)
            }
        }.awaitAll().filterNotNull()
    }

    /** Marks everything in [playlistId] up to and including [itemId] watched (or, [watched] false, from it on unwatched). */
    suspend fun markUpTo(playlistId: String, itemId: String, watched: Boolean) {
        val entries = entries(playlistId)
        val at = entries.indexOfFirst { it.item.id == itemId }
        if (at < 0) return
        val chosen = if (watched) entries.take(at + 1) else entries.drop(at)
        chosen.filter { it.item.user.played != watched }.forEach { runCatching { server.setPlayed(it.item.id, watched) } }
        forget(playlistId)
    }

    companion object {
        const val ROW_KEY = "home/continue_playlists"
        const val KEEP_MS = 60_000L
        const val FRESH_FOR_HOME_MS = 20_000L
        /** How many video playlists are looked through for one being watched. */
        const val LOOK_IN = 20
    }
}
