package dev.nitrate.android.trips

import dev.nitrate.android.notifications.NewEpisodeNotifier
import dev.nitrate.android.storage.DisplaySettingsStore
import dev.nitrate.android.storage.KeyValues
import dev.nitrate.android.sync.SettingsSync
import dev.nitrate.core.MediaServer
import dev.nitrate.core.downloads.DownloadManager
import dev.nitrate.core.downloads.DownloadQuality
import dev.nitrate.core.downloads.DownloadRecord
import dev.nitrate.core.downloads.Packer
import dev.nitrate.core.downloads.PickKind
import dev.nitrate.core.downloads.Trip
import dev.nitrate.core.downloads.TripDevice
import dev.nitrate.core.downloads.TripPick
import dev.nitrate.core.downloads.TripPlanner
import dev.nitrate.core.downloads.TripReadiness
import dev.nitrate.core.episodes.UpNext
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.pages.DisplayProfiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Packing for trips: plans kept in the user's synced settings (one entry per trip; planned on any
 * device, the TV included), the devices that can pack ([Packer]s, each registering itself), and on
 * a device that carries a trip, the packing itself: its share downloaded at home before leaving
 * (one title at a time, so the server converts one at a time), "ready to go" or "won't finish"
 * said once, shows topped up on Wi-Fi during the trip when asked, and the trip's downloads cleared
 * when it's over, as the user chooses on this device.
 */
class Trips(
    private val server: MediaServer,
    private val settings: DisplaySettingsStore,
    private val sync: SettingsSync,
    /** Downloads on this device; null where there are none (the TV). */
    private val downloads: () -> DownloadManager?,
    /** This device as a packer (its id, name and kind); null where it can't pack (the TV). */
    private val me: () -> Packer?,
    private val freeBytes: () -> Long?,
    /** Whether this device plays HEVC (smaller converted downloads). */
    private val hevcHere: () -> Boolean,
    private val onMobileData: () -> Boolean,
    /** Small values kept on this device only: what's been said and cleared for each trip. */
    private val local: KeyValues,
    private val notifier: () -> NewEpisodeNotifier,
    private val now: () -> Long,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val lock = Mutex()

    /** The signed-in user's trips, soonest first. */
    fun trips(): Flow<List<Trip>> {
        val user = server.userId ?: return flowOf(emptyList())
        return settings.entriesStartingWith(user, DisplayProfiles.TRIP).map { entries ->
            entries.values.mapNotNull { runCatching { json.decodeFromString<Trip>(it) }.getOrNull() }.sortedBy { it.leaveMs }
        }
    }

    /** The user's devices that can pack, most recently seen first. */
    fun packers(): Flow<List<Packer>> {
        val user = server.userId ?: return flowOf(emptyList())
        return settings.entriesStartingWith(user, DisplayProfiles.PACKER).map { entries ->
            entries.values.mapNotNull { runCatching { json.decodeFromString<Packer>(it) }.getOrNull() }.sortedByDescending { it.seenMs }
        }
    }

    /** This device, when it can pack. */
    val here: Packer? get() = me()

    suspend fun save(trip: Trip) {
        val user = server.userId ?: return
        settings.saveEntry(user, DisplayProfiles.TRIP + trip.id, json.encodeToString(Trip.serializer(), trip))
    }

    /** Removes the plan everywhere (what's downloaded stays until cleared on each device). */
    suspend fun delete(tripId: String) {
        val user = server.userId ?: return
        settings.saveEntry(user, DisplayProfiles.TRIP + tripId, null)
    }

    /**
     * Puts this device in the user's list of devices that can pack, or brings it up to date (a new
     * name, free space a gigabyte or more different, or not seen for a day), so planning elsewhere
     * can name it. Not on the TV.
     */
    suspend fun register() {
        val user = server.userId ?: return
        val mine = me() ?: return
        val entry = DisplayProfiles.PACKER + mine.deviceId
        val stored = settings.entriesStartingWith(user, entry).first()[entry]?.let { runCatching { json.decodeFromString<Packer>(it) }.getOrNull() }
        val free = freeBytes()
        val fresh = mine.copy(freeBytes = free, seenMs = now())
        val stale = stored == null || stored.name != fresh.name || stored.kind != fresh.kind ||
            kotlin.math.abs((stored.freeBytes ?: 0) - (free ?: 0)) >= 1_000_000_000 || fresh.seenMs - stored.seenMs > TripPlanner.DAY_MS
        if (stale) settings.saveEntry(user, entry, json.encodeToString(Packer.serializer(), fresh))
    }

    /**
     * A new trip's plan: [days] away from [leaveMs], carried by [devices], filled with suggestions for
     * how much the user watches (their history): shows they're in the middle of, films they
     * started, favourite films not seen yet. Each pick has its size worked out from one of its files.
     */
    suspend fun plan(name: String, leaveMs: Long, returnMs: Long, devices: List<TripDevice>): Trip {
        val history = runCatching { server.recentlyPlayed(400) }.getOrDefault(emptyList())
        val perDay = TripPlanner.hoursPerDay(history, now())
        val watching = runCatching { server.watchingRows() }.getOrDefault(emptyList())
        val nextUp = watching.firstOrNull { it.key == "home/next_up" }?.items.orEmpty() +
            watching.firstOrNull { it.key == "home/continue_watching" }?.items.orEmpty().filter { it.kind == ItemKind.EPISODE }
        val started = watching.firstOrNull { it.key == "home/continue_watching" }?.items.orEmpty().filter { it.kind == ItemKind.MOVIE }
        val favourites = runCatching {
            server.queryItems(dev.nitrate.core.pages.ItemQuery(types = listOf(ItemKind.MOVIE), favoritesOnly = true, watched = dev.nitrate.core.pages.WatchFilter.UNWATCHED, limit = 12))
        }.getOrDefault(emptyList())
        val picks = TripPlanner.suggest(nextUp, started, favourites, perDay, TripPlanner.days(leaveMs, returnMs)).map { withSize(it) }
        return Trip(
            id = kotlin.uuid.Uuid.random().toHexString().take(12), name = name, leaveMs = leaveMs, returnMs = returnMs,
            devices = devices, picks = picks, hoursPerDay = perDay, createdMs = now(),
        )
    }

    /** [item] as a pick: a show (its next [episodes]), a season, a playlist or collection, or one title, with its size. */
    suspend fun pickFor(item: MediaItem, episodes: Int = 3): TripPick {
        val pick = when (item.kind) {
            ItemKind.SERIES -> TripPick(item.id, PickKind.SHOW, item.name, episodes = episodes, seriesId = item.id)
            ItemKind.SEASON -> {
                val list = runCatching { server.episodes(item.seriesId ?: "", item.id) }.getOrDefault(emptyList()).filterNot { it.user.played }
                TripPick(item.id, PickKind.SEASON, listOfNotNull(item.seriesName, item.name).joinToString(" · "), count = list.size.coerceAtLeast(1),
                    runtimeMs = list.firstOrNull()?.runtimeMs, seriesId = item.seriesId)
            }
            ItemKind.PLAYLIST, ItemKind.BOX_SET -> {
                val list = members(item).filter { it.isPlayable }
                TripPick(item.id, if (item.kind == ItemKind.PLAYLIST) PickKind.PLAYLIST else PickKind.COLLECTION, item.name,
                    count = list.size.coerceAtLeast(1), runtimeMs = list.sumOf { it.runtimeMs ?: TripPlanner.FILM_MS }.takeIf { list.isNotEmpty() })
            }
            ItemKind.EPISODE -> TripPick(item.id, PickKind.TITLE, "${item.seriesName ?: item.name} · S${item.seasonNumber ?: "?"}:E${item.indexNumber ?: "?"}", runtimeMs = item.runtimeMs)
            else -> TripPick(item.id, PickKind.TITLE, item.name, runtimeMs = item.runtimeMs)
        }
        return withSize(pick)
    }

    /** Adds [item] to [trip]'s picks (kept as it is when it's already there) and saves it. */
    suspend fun addTo(trip: Trip, item: MediaItem) {
        if (trip.picks.any { it.id == item.id }) return
        save(trip.copy(picks = trip.picks + pickFor(item)))
    }

    private suspend fun members(item: MediaItem): List<MediaItem> = runCatching {
        if (item.kind == ItemKind.PLAYLIST) server.playlistItems(item.id) else server.collectionItems(item.id)
    }.getOrDefault(emptyList())

    /** The original's bitrate (and runtime when missing) from one of the pick's files, for its size at Original. */
    private suspend fun withSize(pick: TripPick): TripPick {
        val sample = when (pick.kind) {
            PickKind.SHOW -> runCatching { server.nextUpFor(pick.seriesId ?: pick.id) }.getOrNull()?.id
            PickKind.TITLE -> pick.id
            else -> null
        } ?: return pick
        val source = runCatching { server.mediaSource(sample) }.getOrNull() ?: return pick
        val runtime = pick.runtimeMs ?: if (pick.kind == PickKind.SHOW) runCatching { server.getItem(sample).runtimeMs }.getOrNull() else null
        val size = source.size
        val bps = source.bitrate?.takeIf { it > 0 }
            ?: if (size != null && runtime != null && runtime > 0) (size * 8_000 / runtime).toInt() else null
        return pick.copy(bitrate = bps, runtimeMs = runtime ?: pick.runtimeMs)
    }

    // ---- On a device carrying a trip ----

    /** The trips this device carries that aren't over yet. */
    private suspend fun carried(): List<Trip> {
        val mine = me() ?: return emptyList()
        return trips().first().filter { it.device(mine.deviceId) != null }
    }

    /** The quality this device packs [trip] at (its own setting, or the best that fits its room). */
    fun qualityHere(trip: Trip): DownloadQuality? {
        val mine = me() ?: return null
        val device = trip.device(mine.deviceId) ?: return null
        val packed = downloads()?.tripRecords(trip.id).orEmpty().filter { it.state == dev.nitrate.core.downloads.DownloadState.COMPLETED }.sumOf { it.totalBytes ?: it.bytesDownloaded }
        val free = freeBytes()?.let { it + packed }
        return TripPlanner.qualityFor(trip, device.copy(hevc = device.hevc && hevcHere()), free, computer = mine.kind == "computer")
    }

    /** How this device's share of [trip] is going. */
    fun readinessHere(trip: Trip): TripReadiness? {
        val mine = me() ?: return null
        if (trip.device(mine.deviceId) == null) return null
        val records = downloads()?.tripRecords(trip.id).orEmpty()
        return TripPlanner.readiness(records, trip.picksFor(mine.deviceId).sumOf { it.titles }, trip.leaveMs, now())
    }

    /** This device's downloads for [tripId]. */
    fun recordsHere(tripId: String): List<DownloadRecord> = downloads()?.tripRecords(tripId).orEmpty()

    /**
     * Packs what this device carries: first the latest plans from the server, then for each trip not
     * yet left on, its picks queued (picks taken out of the plan are dropped, if not watched yet);
     * during a trip, with top-ups on and on Wi-Fi that isn't mobile data, shows topped up as they're
     * watched. Then says once when a trip's ready, or won't be in time. Needs the server; never
     * awaited before Home.
     */
    suspend fun pack() = lock.withLock {
        val dl = downloads() ?: return@withLock
        val mine = me() ?: return@withLock
        runCatching { sync.syncNow() }
        runCatching { register() }
        val at = now()
        val trips = carried()
        if (trips.any { at < it.returnMs }) dl.keepRefreshing()
        for (trip in trips) {
            val before = at < trip.leaveMs
            val during = !before && at < trip.returnMs
            val topUp = during && trip.topUpOnWifi && !onMobileData()
            if (!before && !topUp) continue
            val quality = qualityHere(trip) ?: continue
            val device = trip.device(mine.deviceId) ?: continue
            val hevc = device.hevc && hevcHere() && !quality.isOriginal
            val picks = trip.picksFor(mine.deviceId)
            // Picks taken out of the plan: their downloads go (before leaving, and not watched yet).
            if (before) {
                val ids = picks.map { it.id }.toSet()
                dl.tripRecords(trip.id).filter { it.tripPickId !in ids && !it.item.user.played && it.item.user.positionMs == 0L }.forEach { dl.remove(it.itemId) }
            }
            for (pick in picks) {
                val items = try {
                    expand(pick, dl)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    continue
                }
                if (topUp && pick.kind == PickKind.SHOW) {
                    // Room for the next ones: watched episodes of this show packed for the trip go.
                    dl.tripRecords(trip.id).filter { it.tripPickId == pick.id && it.item.user.played && !it.pendingSync }.forEach { dl.remove(it.itemId) }
                } else if (topUp) continue
                dl.enqueueForTrip(items, pick.quality ?: quality, hevc, trip.id, pick.id)
            }
        }
        say(trips)
    }

    /** What [pick] packs now: a show's next episodes (counting what's been watched here), a season's unwatched ones, a title, a playlist or collection. */
    private suspend fun expand(pick: TripPick, dl: DownloadManager): List<MediaItem> = when (pick.kind) {
        PickKind.SHOW -> {
            val episodes = server.allEpisodes(pick.seriesId ?: pick.id)
            val playedHere = dl.downloads.first().filter { it.item.user.played }.map { it.itemId }.toSet()
            UpNext.upcoming(episodes.map { if (it.id in playedHere) it.copy(user = it.user.copy(played = true)) else it }, pick.episodes ?: 3)
        }
        PickKind.SEASON -> server.episodes(pick.seriesId ?: "", pick.id).filterNot { it.user.played }
        PickKind.TITLE -> listOf(server.getItem(pick.id))
        PickKind.PLAYLIST -> server.playlistItems(pick.id)
        PickKind.COLLECTION -> server.collectionItems(pick.id)
    }.filter { it.isPlayable && it.kind != ItemKind.AUDIO }

    /** After downloads ran (also in the background): says once when a trip is ready, or won't be in time. */
    suspend fun afterDownloads() = say(runCatching { carried() }.getOrDefault(emptyList()))

    private fun say(trips: List<Trip>) {
        val mine = me() ?: return
        val at = now()
        for (trip in trips) {
            if (at >= trip.leaveMs) continue
            val r = readinessHere(trip) ?: continue
            val said = local.get(SAID + trip.id)
            when {
                r.ready && said != READY -> {
                    notifier().tripNews(trip.id, "Packed for ${trip.name}", "All ${r.titles} on this ${mine.kind}: ready to go.")
                    local.put(SAID + trip.id, READY)
                }
                r.late && said == null -> {
                    notifier().tripNews(trip.id, "${trip.name} won't be packed in time", "${r.done} of ${r.titles} so far. Pick a smaller quality or fewer titles, or keep this ${mine.kind} on Wi-Fi.")
                    local.put(SAID + trip.id, LATE)
                }
            }
        }
    }

    // ---- When it's over ----

    /** Trips over (back home) with downloads still on this device and not settled here yet. */
    suspend fun over(): List<Trip> {
        val dl = downloads() ?: return emptyList()
        val at = now()
        val withRecords = dl.tripIds()
        return trips().first().filter { at >= it.returnMs && it.id in withRecords && local.get(SETTLED + it.id) == null }
    }

    /** Trips gone from the plan list whose downloads are still here, by id (cleared like any other). */
    suspend fun orphaned(): Set<String> {
        val dl = downloads() ?: return emptySet()
        val known = trips().first().map { it.id }.toSet()
        return dl.tripIds() - known
    }

    /**
     * The trip's over on this device: clears what was watched ([watchedOnly]), or everything not
     * started, or ([keep]) nothing; titles packed here then stay as ordinary downloads.
     */
    suspend fun settle(tripId: String, watchedOnly: Boolean, keep: Boolean = false): Int {
        val dl = downloads() ?: return 0
        runCatching { dl.syncWatchState() }
        val gone = if (keep) emptyList() else TripPlanner.toClear(dl.tripRecords(tripId), tripId, watchedOnly)
        gone.forEach { dl.remove(it.itemId) }
        local.put(SETTLED + tripId, "1")
        return gone.size
    }

    private companion object {
        const val SAID = "trip.said."
        const val SETTLED = "trip.settled."
        const val READY = "ready"
        const val LATE = "late"
    }
}
