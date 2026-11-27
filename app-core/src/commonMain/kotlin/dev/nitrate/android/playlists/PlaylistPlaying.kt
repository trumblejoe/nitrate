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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/** A playlist being watched through, as the player sees it: which one (and its name), and what comes after this. */
data class PlaylistContext(val playlistId: String, val index: Int, val total: Int, val next: MediaItem?, val name: String? = null)

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
 * (`MediaServer.playlistFacts`), the server hands over facts (which playlists hold what was played lately, with
 * watch state) and these same rules are applied to them; nothing else is read.
 */
class PlaylistPlaying(
    private val server: MediaServer,
    private val cache: DataCache?,
    private val nowMs: () -> Long,
    /** Each playlist's own record (watched entries, resume points, where it's at), apart from the user's own. */
    private val records: PlaylistRecordStore = PlaylistRecordStore.InMemory(),
) {
    // ---- Each playlist's own record ----

    /** Records read or written this run, by account and playlist (for answers that can't wait: resume points, spoilers). */
    private val known = kotlinx.coroutines.flow.MutableStateFlow<Map<String, dev.nitrate.core.playlists.PlaylistRecord>>(emptyMap())

    private fun knownKey(playlistId: String) = "${server.currentAccountKey.orEmpty()}|$playlistId"

    /**
     * [playlistId]'s own record: kept, else stored, else (the first time, [raw] given) started from the
     * user's own progress on its entries ([dev.nitrate.core.playlists.PlaylistRecords.seed]) and stored.
     */
    private suspend fun recordFor(playlistId: String, raw: List<MediaItem>?): dev.nitrate.core.playlists.PlaylistRecord? {
        known.value[knownKey(playlistId)]?.let { return it }
        val stored = runCatching { records.get(playlistId) }.getOrNull()
            ?: raw?.let { dev.nitrate.core.playlists.PlaylistRecords.seed(playlistId, it).also { r -> runCatching { records.put(r) } } }
            ?: return null
        known.update { it + (knownKey(playlistId) to stored) }
        return stored
    }

    private suspend fun save(record: dev.nitrate.core.playlists.PlaylistRecord) {
        known.update { it + (knownKey(record.playlistId) to record) }
        runCatching { records.put(record) }
    }

    /** Every record of the signed-in user's, read into memory (at sign-in: spoilers and resume points need them at once). */
    suspend fun loadRecords(): List<dev.nitrate.core.playlists.PlaylistRecord> {
        val all = runCatching { records.all() }.getOrDefault(emptyList())
        known.update { k -> k + all.associateBy { knownKey(it.playlistId) } }
        return all
    }

    /** Where [itemId] left off in [playlistId] (0: from the start), from its own record. */
    fun resumeIn(playlistId: String, itemId: String): Long = known.value[knownKey(playlistId)]?.resume?.get(itemId) ?: 0L

    /** Whether [itemId] was watched in any of the user's playlists (it isn't a spoiler then). */
    fun seenInPlaylists(itemId: String): Boolean {
        val account = server.currentAccountKey.orEmpty() + "|"
        return known.value.any { (k, r) -> k.startsWith(account) && itemId in r.watched }
    }

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

    /** What was shown last time (this device), for opening at once while it's fetched again; with the playlist's own record laid over it. */
    suspend fun cached(playlistId: String): List<MediaItem>? {
        val raw = lock.withLock { kept[playlistId]?.entries } ?: fromDisk(playlistId)?.entries ?: return null
        val record = recordFor(playlistId, null) ?: return raw
        return dev.nitrate.core.playlists.PlaylistRecords.apply(raw, record)
    }

    private suspend fun fromDisk(playlistId: String): OnDisk? {
        val account = server.currentAccountKey ?: return null
        return runCatching { cache?.read(account, cacheKey(playlistId), OnDisk.serializer()) }.getOrNull()
    }

    /**
     * Everything in [playlistId] in its order, fresh within [fresherThanMs], each one watched, part
     * watched or not by the playlist's own record (not the user's own marks).
     */
    suspend fun entries(playlistId: String, fresherThanMs: Long = KEEP_MS): List<MediaItem> {
        val raw = rawEntries(playlistId, fresherThanMs)
        val record = recordFor(playlistId, raw) ?: return raw
        return dev.nitrate.core.playlists.PlaylistRecords.apply(raw, record)
    }

    /** The playlist's entries with the user's own marks, as the server gives them. */
    private suspend fun rawEntries(playlistId: String, fresherThanMs: Long = KEEP_MS): List<MediaItem> {
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

    // Titles about to play as a sitting of a playlist (title id → playlist id): said where the play
    // starts (the playlist's page, a "Continue your playlists" card, a title opened from a playlist, the
    // next one rolling on in it). Nothing else is a playlist sitting: no guessing from what was played.
    private val started = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())

    // Sittings playing now (title id → playlist id): their stop goes to the playlist's own record.
    private val sittings = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())

    /** [itemId] is being started from [playlistId]: a sitting of that playlist, and what follows comes from it. */
    fun startedFrom(playlistId: String, itemId: String) = started.update { m ->
        val next = (m - itemId) + (itemId to playlistId)
        if (next.size > MAX_STARTED) next.entries.drop(next.size - MAX_STARTED).associate { it.key to it.value } else next
    }

    /** [itemId] plays on its own after all (started elsewhere since): not a playlist sitting. */
    fun playsOnItsOwn(itemId: String) = started.update { it - itemId }

    /**
     * The video playlists the user is watching through (something in them played within
     * `PlaylistPlaces.ACTIVE_MS`), most recent first, with their entries and fresh watch state: one
     * "played lately" request, each playlist's kept members, and only the matching ones' contents.
     */
    private suspend fun active(maxPlaylists: Int): List<Pair<String, List<MediaItem>>> =
        fromFacts(maxPlaylists) ?: activeHere(maxPlaylists)

    /**
     * With the Companion: its facts (which playlists hold something played lately, and their entries
     * with watch state), the rules applied here, most recently played from first. Null when the server
     * doesn't offer them; throws when it does but doesn't answer (nothing else is tried then).
     */
    private suspend fun fromFacts(maxPlaylists: Int): List<Pair<String, List<MediaItem>>>? {
        // From the start of the day, so the server's kept answer serves the whole day's asking.
        val since = (nowMs() - PlaylistPlaces.ACTIVE_MS).let { it - it % DAY_MS }
        val facts = server.playlistFacts(since) ?: return null
        return facts.map { it.playlistId to PlaylistPlaces.entries(it.items, it.episodes) }
            .filter { (_, entries) -> (PlaylistPlaces.lastPlayedMs(entries) ?: 0) >= nowMs() - PlaylistPlaces.ACTIVE_MS }
            .sortedByDescending { (_, entries) -> PlaylistPlaces.lastPlayedMs(entries) ?: 0 }
            .take(maxPlaylists)
    }

    private suspend fun activeHere(maxPlaylists: Int): List<Pair<String, List<MediaItem>>> = coroutineScope {
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
            async { runCatching { id to withWatchState(rawEntries(id, fresherThanMs = STRUCTURE_MS), byId) }.getOrNull() }
        }.awaitAll().filterNotNull()
    }

    /**
     * The playlist [item] is a sitting of, if any, and what follows it there: only when it was started
     * from a playlist (its page, its card, the one before it in it), never guessed from what was played.
     * Said as the player starts (queues too): a sitting's own state for the user is kept, to be put back
     * when it stops, and its stop counts in the playlist's own record.
     */
    suspend fun contextOf(item: MediaItem): PlaylistContext? {
        val from = started.value[item.id] ?: return null
        val entries = runCatching { entries(from, fresherThanMs = STRUCTURE_MS) }.getOrNull() ?: return null
        val at = PlaylistPlaces.placeIndex(entries, item.id).takeIf { it >= 0 } ?: return null
        val id = entries[at].id
        if (sittings.value[id] == null) {
            val own = runCatching { server.userData(id) }.getOrNull()
            server.keepOwnState(id, own?.let { dev.nitrate.core.model.UserState(played = it.played, positionMs = it.positionMs, lastPlayedMs = it.lastPlayedMs) } ?: item.user)
            sittings.update { it + (id to from) }
        }
        return remember(from, entries, at)
    }

    private suspend fun remember(playlistId: String, entries: List<MediaItem>, at: Int): PlaylistContext {
        // Entries from the Companion's facts are ids and watch state only: the next one is read whole.
        val next = entries.getOrNull(at + 1)?.let { n -> if (n.name.isNotEmpty()) n else runCatching { server.getItem(n.id) }.getOrDefault(n) }
            ?.copy(playlistId = playlistId)
        // The next one rolls on as a sitting of this playlist too.
        if (next != null) startedFrom(playlistId, next.id)
        return PlaylistContext(playlistId, at, entries.size, next, nameOf(playlistId))
    }

    private val names = HashMap<String, String>()

    /** A playlist's name, for "Next in …" (from the list kept, else asked once). */
    private suspend fun nameOf(playlistId: String): String? {
        lock.withLock { names[playlistId] ?: playlistList?.entries?.firstOrNull { it.id == playlistId }?.name }?.let { return it }
        val name = runCatching { server.getItem(playlistId).name }.getOrNull() ?: return null
        lock.withLock { names[playlistId] = name }
        return name
    }

    /**
     * Follows every playback stop (for as long as it runs): a playlist sitting's stop goes to that
     * playlist's own record (watched, or where it left off; where the playlist carries on from).
     */
    suspend fun followStops() {
        server.playStops.collect { stop ->
            if (stop.forGroup) return@collect
            val from = sittings.value[stop.itemId] ?: return@collect
            sittings.update { it - stop.itemId }
            val raw = runCatching { rawEntries(from, fresherThanMs = STRUCTURE_MS) }.getOrNull() ?: return@collect
            val record = recordFor(from, raw) ?: return@collect
            val runtime = raw.firstOrNull { it.id == stop.itemId }?.runtimeMs
            save(dev.nitrate.core.playlists.PlaylistRecords.stopped(record, stop.itemId, stop.positionMs, runtime, ended = false, atMs = nowMs()))
        }
    }

    /** What's left to watch in [playlistId] from where it was left (by its own record), for packing a trip. */
    suspend fun remaining(playlistId: String): List<MediaItem> {
        val entries = entries(playlistId, fresherThanMs = STRUCTURE_MS)
        val from = PlaylistPlaces.lastPlayed(entries) ?: 0
        return entries.drop(from).filterNot { it.user.played }
    }

    /**
     * Home's "Continue your playlists": each playlist sat in lately (its own record, most recent
     * first), what carries on from where it was left; finished ones drop out. Each card stands for its
     * playlist ([MediaItem.playlistId]): playing it is a sitting of that playlist. Reads only the
     * records and the playlists' kept contents.
     */
    suspend fun continueRow(max: Int = 12): MediaRow? {
        seedOnce()
        val since = nowMs() - PlaylistPlaces.ACTIVE_MS
        val active = loadRecords().filter { it.lastAtMs >= since && it.lastId != null }.sortedByDescending { it.lastAtMs }.take(LOOK_IN)
        val items = active.mapNotNull { r ->
            val entries = runCatching { entries(r.playlistId, fresherThanMs = STRUCTURE_MS) }.getOrNull() ?: return@mapNotNull null
            val place = entries.indexOfFirst { it.id == r.lastId }.takeIf { it >= 0 } ?: return@mapNotNull null
            PlaylistPlaces.continueFrom(entries, place)?.copy(playlistId = r.playlistId)
        }.distinctBy { it.id }.take(max)
        return if (items.isEmpty()) null else MediaRow(ROW_KEY, "Continue your playlists", items, RowDisplay())
    }

    /**
     * Once per account and device: playlists being watched through by the user's own marks (as worked
     * out before playlists kept records) get a record started from those marks, so none goes back to
     * the start. From then on, a playlist moves on only by its own sittings.
     */
    private suspend fun seedOnce() {
        val account = server.currentAccountKey ?: return
        if (lock.withLock { account in seeded }) return
        val done = runCatching { cache?.read(account, SEEDED, Boolean.serializer()) }.getOrNull() == true
        if (!done) {
            val lists = runCatching { fromFacts(LOOK_IN) }.getOrNull() ?: runCatching { activeHere(LOOK_IN) }.getOrDefault(emptyList())
            for ((playlistId, entries) in lists) {
                if (runCatching { records.get(playlistId) }.getOrNull() == null) save(dev.nitrate.core.playlists.PlaylistRecords.seed(playlistId, entries))
            }
            runCatching { cache?.write(account, SEEDED, true, Boolean.serializer()) }
        }
        lock.withLock { seeded += account }
    }

    private val seeded = HashSet<String>()

    /** How "Continue your playlists" is getting on with the Companion, for Diagnostics (the query analyzer). */
    data class FactsStatus(val answeredMs: Long? = null, val failingSinceMs: Long? = null, val reason: String? = null) {
        /** One line for Diagnostics, or null before the Companion has been asked. */
        fun line(time: (Long) -> String): String? = when {
            failingSinceMs != null -> "Continue your playlists: " + (answeredMs?.let { "last answered ${time(it)}; " } ?: "") +
                "the Companion hasn't answered since ${time(failingSinceMs)} (${reason ?: "no answer"}), so the row shows its last answer"
            answeredMs != null -> "Continue your playlists: answered by the Companion at ${time(answeredMs)}"
            else -> null
        }
    }

    private val _status = kotlinx.coroutines.flow.MutableStateFlow(FactsStatus())
    val status: kotlinx.coroutines.flow.StateFlow<FactsStatus> = _status

    @kotlinx.serialization.Serializable
    private data class KeptRow(val row: MediaRow?)

    private var lastRow: MediaRow? = null
    private var lastRowAccount: String? = null
    private var failedAtMs: Long? = null

    /**
     * The Companion didn't answer: never the costly reading here instead (it would add to a busy server's
     * load), and the row never just disappears; the last answer stays, its watched marks read afresh, and
     * the failure shows in Diagnostics. Not asked again for [RETRY_MS].
     */
    private suspend fun failed(account: String?, e: Exception): MediaRow? {
        val at = nowMs()
        lock.withLock { failedAtMs = at }
        val was = _status.value
        _status.value = was.copy(failingSinceMs = was.failingSinceMs ?: at, reason = e.message ?: e::class.simpleName ?: "no answer")
        return keptRow(account)
    }

    private suspend fun keptRow(account: String?): MediaRow? {
        val row = lock.withLock { lastRow?.takeIf { lastRowAccount == account } }
            ?: account?.let { a -> runCatching { cache?.read(a, ROW_CACHE, KeptRow.serializer()) }.getOrNull()?.row }
        return row?.let { r -> runCatching { server.withFreshMarks(listOf(r)).first() }.getOrDefault(r) }
    }

    /**
     * Marks everything in [playlistId] up to and including [itemId] watched in the playlist (or,
     * [watched] false, from it on not), in its own record: the user's own marks stay as they are.
     * [itemId] may be a whole show or season in the playlist: all its episodes count.
     */
    suspend fun markUpTo(playlistId: String, itemId: String, watched: Boolean) {
        val raw = rawEntries(playlistId, fresherThanMs = 0)
        val record = recordFor(playlistId, raw) ?: return
        save(dev.nitrate.core.playlists.PlaylistRecords.markUpTo(record, raw, itemId, watched))
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
        /** After the Companion fails to answer, how long until it's asked again (the last row shows meanwhile). */
        const val RETRY_MS = 60_000L
        private const val DAY_MS = 86_400_000L
        private const val ROW_CACHE = "playlist-row"
        private const val SEEDED = "playlist-records-seeded"
        /** Titles remembered as about to play from a playlist (a queue started from one can be long). */
        const val MAX_STARTED = 1000
    }
}
