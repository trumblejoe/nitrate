package dev.nitrate.core.downloads

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import kotlinx.serialization.Serializable

/**
 * Packing for a trip: a plan of what to have on which devices, made on any of the user's devices
 * (the TV included) and synced with the user's display settings (one entry per trip, so devices
 * changing different trips merge). Each device that [carries][TripDevice] the trip downloads its
 * share at home before [leaveMs]; after [returnMs] it offers to clear what was packed.
 */
@Serializable
data class Trip(
    val id: String,
    val name: String,
    val leaveMs: Long,
    val returnMs: Long,
    val devices: List<TripDevice> = emptyList(),
    val picks: List<TripPick> = emptyList(),
    /** During the trip, on Wi-Fi that isn't mobile data, top shows up as they're watched. */
    val topUpOnWifi: Boolean = false,
    /** Hours a day of watching, from the user's history when it was planned. */
    val hoursPerDay: Double = TripPlanner.DEFAULT_HOURS_PER_DAY,
    val createdMs: Long = 0,
) {
    val days: Int get() = TripPlanner.days(leaveMs, returnMs)

    /** The picks [deviceId] packs (picks name their devices, or go on every one). */
    fun picksFor(deviceId: String): List<TripPick> =
        if (devices.none { it.deviceId == deviceId }) emptyList() else picks.filter { it.devices == null || deviceId in it.devices }

    fun device(deviceId: String): TripDevice? = devices.firstOrNull { it.deviceId == deviceId }
}

/** A device carrying a trip: how much room to use (null: its free space, less a margin) and the quality (null: the best that fits). */
@Serializable
data class TripDevice(
    val deviceId: String,
    val name: String,
    val roomBytes: Long? = null,
    val quality: DownloadQuality? = null,
    val hevc: Boolean = true,
)

@Serializable
enum class PickKind { SHOW, SEASON, TITLE, PLAYLIST, COLLECTION }

/**
 * Something to pack: a show's next [episodes] unwatched episodes, a season's unwatched episodes,
 * one title, a playlist or a collection. [runtimeMs] and [bitrate] are for one episode or title (or
 * the whole list for playlists and collections, with [count] items), for estimates. [devices]: only
 * these devices (null: every device on the trip).
 */
@Serializable
data class TripPick(
    val id: String,
    val kind: PickKind,
    val name: String,
    val episodes: Int? = null,
    val count: Int = 1,
    val runtimeMs: Long? = null,
    /** The original file's bitrate (bps), when known: what Original takes. */
    val bitrate: Int? = null,
    val seriesId: String? = null,
    val quality: DownloadQuality? = null,
    val devices: List<String>? = null,
    /** Put there by the planner (a suggestion), rather than added by hand. */
    val suggested: Boolean = false,
) {
    /** How many titles it packs. */
    val titles: Int get() = if (kind == PickKind.SHOW) episodes ?: 1 else count

    /** Viewing time it packs, for the hours-to-days total. */
    val totalMs: Long get() = when (kind) {
        PickKind.SHOW, PickKind.SEASON -> (runtimeMs ?: TripPlanner.EPISODE_MS) * titles
        PickKind.PLAYLIST, PickKind.COLLECTION -> runtimeMs ?: (TripPlanner.FILM_MS * count)
        PickKind.TITLE -> runtimeMs ?: TripPlanner.FILM_MS
    }
}

/**
 * A device that can pack (phones, tablets, iPhone/iPad, the laptop; never the TV): it puts itself
 * in the user's synced settings when the app runs, so a trip planned elsewhere can name it.
 */
@Serializable
data class Packer(
    val deviceId: String,
    val name: String,
    /** "phone", "tablet" or "computer". */
    val kind: String,
    val freeBytes: Long? = null,
    val seenMs: Long = 0,
)

/** What one device carries for a trip: at which quality, how big, in how much room. */
data class DeviceShare(val device: TripDevice, val quality: DownloadQuality, val bytes: Long, val roomBytes: Long?, val titles: Int) {
    val fits: Boolean get() = roomBytes == null || bytes <= roomBytes

    /** "9.4 GB of 40 GB at 720p", "Too big: 52 GB of 40 GB even at 360p". */
    fun describe(): String {
        val room = roomBytes?.let { " of ${DownloadSizing.formatBytes(it)}" }.orEmpty()
        val at = if (device.quality == null) "${quality.label} (fits)" else quality.label
        return if (fits) "$titles titles · ${DownloadSizing.formatBytes(bytes)}$room at $at"
        else "Too big: ${DownloadSizing.formatBytes(bytes)}$room at ${quality.label}. Take something out or give it more room."
    }
}

/** How one device's share of a trip is going. */
data class TripReadiness(
    val titles: Int,
    val done: Int,
    val downloading: Int,
    val waiting: Int,
    val failed: Int,
    val bytesLeft: Long,
    /** When it should finish at the speed so far; null when unknown or done. */
    val finishMs: Long?,
    val leaveMs: Long,
) {
    val ready: Boolean get() = titles > 0 && done == titles
    val late: Boolean get() = !ready && finishMs != null && finishMs > leaveMs

    /** "All 14 on this phone", "9 of 14 · about 2 h to go", "3 couldn't download". */
    fun describe(nowMs: Long, device: String = "this device"): String = when {
        titles == 0 -> "Nothing packed on $device yet"
        ready -> "All $titles on $device: ready to go"
        else -> buildString {
            append("$done of $titles on $device")
            finishMs?.let { append(if (late) " · won't finish before you leave" else " · finishing about ${TripPlanner.durationWords(it - nowMs)} from now") }
            if (failed > 0) append(" · $failed couldn't download")
        }
    }
}

/**
 * The rules for packing: how much the user watches (their history), what to suggest (shows they're
 * in the middle of, films they started, favourite films not yet seen) to cover the trip, how big each
 * quality would be and which quality fits a device's room.
 */
object TripPlanner {
    const val DEFAULT_HOURS_PER_DAY = 2.0
    const val EPISODE_MS = 45 * 60_000L
    const val FILM_MS = 120 * 60_000L
    const val DAY_MS = 86_400_000L

    /** How far back the history counts, and how little of it is too little to go by. */
    const val HISTORY_DAYS = 60
    private const val MIN_PLAYS = 5

    /** Free space kept for the device itself when the room isn't set. */
    const val MARGIN_BYTES = 2_000_000_000L
    private const val MIN_MARGIN_SHARE = 0.1

    /** Original files with no bitrate known: a typical 1080p Blu-ray rip. */
    const val ORIGINAL_GUESS_BPS = 20_000_000

    /** Suggestions cover the trip and this much more (a spare episode or film is better than running out). */
    const val COVER = 1.25
    private const val MAX_EPISODES_PER_SHOW = 30
    private const val MAX_SHOWS = 6

    /** Whole days away, at least 1. */
    fun days(leaveMs: Long, returnMs: Long): Int = (((returnMs - leaveMs) + DAY_MS - 1) / DAY_MS).toInt().coerceAtLeast(1)

    /**
     * Hours a day the user watches: what they finished in the last [HISTORY_DAYS] days ([history],
     * most recent first, with when each last played), over the days from the first of those plays to
     * now (at least two weeks, so one long weekend doesn't count as a habit); [DEFAULT_HOURS_PER_DAY]
     * with too little to go by.
     */
    fun hoursPerDay(history: List<MediaItem>, nowMs: Long): Double {
        val since = nowMs - HISTORY_DAYS * DAY_MS
        val recent = history.filter { (it.user.lastPlayedMs ?: 0) >= since && it.kind in WATCHED_KINDS }
        if (recent.size < MIN_PLAYS) return DEFAULT_HOURS_PER_DAY
        val first = recent.minOf { it.user.lastPlayedMs!! }
        val days = ((nowMs - first) / DAY_MS.toDouble()).coerceIn(14.0, HISTORY_DAYS.toDouble())
        val hours = recent.sumOf { it.runtimeMs ?: if (it.kind == ItemKind.MOVIE) FILM_MS else EPISODE_MS } / 3_600_000.0
        return (hours / days).coerceIn(0.25, 12.0)
    }

    private val WATCHED_KINDS = setOf(ItemKind.MOVIE, ItemKind.EPISODE, ItemKind.VIDEO)

    /**
     * What to pack for [days] away at [hoursPerDay]: the shows in [nextUp] (the next episode of each,
     * most recent first) shared out an episode at a time, then films the user started ([started]),
     * then [favourites] not yet seen, until the trip's hours (× [COVER]) are covered. Films go in once
     * the shows have a day's worth each, so a long trip isn't only one show.
     */
    fun suggest(nextUp: List<MediaItem>, started: List<MediaItem>, favourites: List<MediaItem>, hoursPerDay: Double, days: Int): List<TripPick> {
        val goal = (hoursPerDay * days * COVER * 3_600_000).toLong()
        val shows = nextUp.filter { it.kind == ItemKind.EPISODE && it.seriesId != null }.distinctBy { it.seriesId }.take(MAX_SHOWS)
        val episodes = IntArray(shows.size)
        var total = 0L
        val films = (started + favourites).filter { it.kind == ItemKind.MOVIE && !it.user.played }.distinctBy { it.id }
        val picked = mutableListOf<MediaItem>()
        // A day's worth of each show first (round-robin), then films, then more episodes.
        val dayMs = (hoursPerDay * 3_600_000).toLong()
        fun addEpisodes(limitPerShowMs: Long) {
            var added = true
            while (total < goal && added) {
                added = false
                shows.forEachIndexed { i, e ->
                    val runtime = e.runtimeMs ?: EPISODE_MS
                    if (total < goal && episodes[i] < MAX_EPISODES_PER_SHOW && (episodes[i] + 1) * runtime <= limitPerShowMs.coerceAtLeast(runtime)) {
                        episodes[i]++
                        total += runtime
                        added = true
                    }
                }
            }
        }
        addEpisodes(dayMs)
        for (f in films) {
            if (total >= goal) break
            picked += f
            total += f.runtimeMs ?: FILM_MS
        }
        addEpisodes(Long.MAX_VALUE)
        return shows.mapIndexedNotNull { i, e ->
            if (episodes[i] == 0) null
            else TripPick(e.seriesId!!, PickKind.SHOW, e.seriesName ?: e.name, episodes = episodes[i], runtimeMs = e.runtimeMs, seriesId = e.seriesId, suggested = true)
        } + picked.map { f -> TripPick(f.id, PickKind.TITLE, f.name, runtimeMs = f.runtimeMs, suggested = true) }
    }

    /** Bytes [pick] takes at [quality] (Original: the file's own bitrate, or a guess). */
    fun bytes(pick: TripPick, quality: DownloadQuality, hevc: Boolean): Long {
        val bps = quality.totalBitrate(hevc)
            ?: pick.bitrate
            ?: ORIGINAL_GUESS_BPS
        // A preset bigger than the file is no bigger than the file (it's sent as it is).
        val capped = pick.bitrate?.let { minOf(bps, it) } ?: bps
        return capped.toLong() * pick.totalMs / 8_000
    }

    /** Bytes of [picks] on a device packing at [quality] (each pick's own quality where set). */
    fun total(picks: List<TripPick>, quality: DownloadQuality, hevc: Boolean): Long = picks.sumOf { bytes(it, it.quality ?: quality, hevc) }

    /**
     * The room a device has: its free space less a margin (2 GB or a tenth, the larger), or what's
     * set when that's less (a set room never counts space the device doesn't have).
     */
    fun room(device: TripDevice, freeBytes: Long?): Long? {
        val usable = freeBytes?.let { free -> (free - maxOf(MARGIN_BYTES, (free * MIN_MARGIN_SHARE).toLong())).coerceAtLeast(0) }
        return when {
            device.roomBytes == null -> usable
            usable == null -> device.roomBytes
            else -> minOf(device.roomBytes, usable)
        }
    }

    /**
     * The best quality whose total fits [room]: Original only where [original] is allowed (the
     * laptop; a phone's screen gains little from it), then 1080p down to 360p; the smallest when
     * nothing fits (the plan then says it's over). With no room known, 720p.
     */
    fun fit(picks: List<TripPick>, room: Long?, hevc: Boolean, original: Boolean): DownloadQuality {
        room ?: return DownloadQuality.MEDIUM
        val order = DownloadQuality.entries.filter { original || !it.isOriginal }
        return order.firstOrNull { total(picks, it, hevc) <= room } ?: order.last()
    }

    /** The quality [device] packs at. */
    fun qualityFor(trip: Trip, device: TripDevice, freeBytes: Long?, computer: Boolean): DownloadQuality =
        device.quality ?: fit(trip.picksFor(device.deviceId), room(device, freeBytes), device.hevc, original = computer)

    /**
     * How [records] (this device's downloads for the trip) are going; [startedMs] when the first
     * began, to work out the speed so far.
     */
    fun readiness(records: List<DownloadRecord>, expectedTitles: Int, leaveMs: Long, nowMs: Long): TripReadiness {
        val done = records.count { it.state == DownloadState.COMPLETED }
        val failed = records.count { it.state == DownloadState.FAILED }
        val downloading = records.count { it.state == DownloadState.DOWNLOADING }
        val titles = maxOf(expectedTitles, records.size)
        val waiting = titles - done - failed - downloading
        val left = records.filter { it.state != DownloadState.COMPLETED }.sumOf { r -> ((r.totalBytes ?: 0) - r.bytesDownloaded).coerceAtLeast(0) }
        // Speed: what's been fetched since the first of these began.
        val began = records.minOfOrNull { it.createdAtMs }
        val fetched = records.sumOf { if (it.state == DownloadState.COMPLETED) it.totalBytes ?: it.bytesDownloaded else it.bytesDownloaded }
        val finish = if (done == titles || began == null || fetched <= 0 || nowMs <= began) null
        else nowMs + left * (nowMs - began) / fetched
        return TripReadiness(titles, done, downloading, waiting.coerceAtLeast(0), failed, left, finish, leaveMs)
    }

    /** [device]'s share of [trip]: [freeBytes] its free space as last seen, [computer] for the laptop (Original allowed). */
    fun share(trip: Trip, device: TripDevice, freeBytes: Long?, computer: Boolean): DeviceShare {
        val picks = trip.picksFor(device.deviceId)
        val quality = qualityFor(trip, device, freeBytes, computer)
        return DeviceShare(device, quality, total(picks, quality, device.hevc && !quality.isOriginal), room(device, freeBytes), picks.sumOf { it.titles })
    }

    /** "About 2 h a day from what you watch: 14 hours for 7 days · packed: 17 hours". */
    fun summary(trip: Trip): String {
        val need = (trip.hoursPerDay * trip.days * 3_600_000).toLong()
        val packed = trip.picks.sumOf { it.totalMs }
        val perDay = if (trip.hoursPerDay >= 1) "${kotlin.math.round(trip.hoursPerDay * 10) / 10.0} h" else "${(trip.hoursPerDay * 60).toInt()} min"
        return "About $perDay a day from what you watch: ${viewingWords(need)} for ${trip.days} ${if (trip.days == 1) "day" else "days"} · packed: ${viewingWords(packed)}"
    }

    /** "Show: next 6 episodes · 4.5 h", "Film · 2 h 10 min", "Playlist: 12 titles · 20 hours". */
    fun pickWords(pick: TripPick): String = when (pick.kind) {
        PickKind.SHOW -> "Next ${pick.episodes ?: 1} ${if ((pick.episodes ?: 1) == 1) "episode" else "episodes"} · ${viewingWords(pick.totalMs)}"
        PickKind.SEASON -> "${pick.count} unwatched ${if (pick.count == 1) "episode" else "episodes"} · ${viewingWords(pick.totalMs)}"
        PickKind.PLAYLIST -> "Playlist: ${pick.count} titles · ${viewingWords(pick.totalMs)}"
        PickKind.COLLECTION -> "Collection: ${pick.count} titles · ${viewingWords(pick.totalMs)}"
        PickKind.TITLE -> durationWords(pick.totalMs)
    }

    /** "2 h 10 min", "40 min", "3 days". */
    fun durationWords(ms: Long): String {
        val minutes = (ms / 60_000).coerceAtLeast(1)
        return when {
            minutes >= 48 * 60 -> "${minutes / (24 * 60)} days"
            minutes >= 60 -> "${minutes / 60} h" + (if (minutes % 60 >= 5) " ${minutes % 60} min" else "")
            else -> "$minutes min"
        }
    }

    /** "31 hours", "45 min" of viewing. */
    fun viewingWords(ms: Long): String {
        val hours = ms / 3_600_000.0
        return if (hours >= 1.5) "${kotlin.math.round(hours).toInt()} hours" else "${ms / 60_000} min"
    }

    /** Records of [tripId] that go once the trip's over: watched ones only ([watchedOnly]), else all; a title someone started stays. */
    fun toClear(records: List<DownloadRecord>, tripId: String, watchedOnly: Boolean): List<DownloadRecord> =
        records.filter { it.tripId == tripId && !it.pendingSync }.filter { r ->
            val watched = r.item.user.played
            val started = !watched && r.item.user.positionMs > 0
            if (watchedOnly) watched else !started
        }
}
