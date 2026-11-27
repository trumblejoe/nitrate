package dev.nitrate.core.downloads

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.UserState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TripPlannerTest {
    private val day = TripPlanner.DAY_MS
    private val now = 1_800_000_000_000L

    private fun episode(series: String, minutes: Long = 45, playedDaysAgo: Int? = null) = MediaItem(
        "$series-e", "Episode", ItemKind.EPISODE, seriesId = series, seriesName = "Show $series", runtimeMs = minutes * 60_000,
        user = UserState(played = playedDaysAgo != null, lastPlayedMs = playedDaysAgo?.let { now - it * day }),
    )

    private fun film(id: String, minutes: Long = 120, started: Boolean = false) =
        MediaItem(id, "Film $id", ItemKind.MOVIE, runtimeMs = minutes * 60_000, user = UserState(positionMs = if (started) 60_000 else 0))

    @Test
    fun watchRateComesFromRecentHistory() {
        // 30 episodes of 45 min over the last 30 days: 0.75 h a day.
        val history = (1..30).map { episode("s$it", playedDaysAgo = it) }
        assertEquals(0.75, TripPlanner.hoursPerDay(history, now), 0.05)
        // Too little to go by, or all of it long ago: the default.
        assertEquals(TripPlanner.DEFAULT_HOURS_PER_DAY, TripPlanner.hoursPerDay(history.take(3), now))
        assertEquals(TripPlanner.DEFAULT_HOURS_PER_DAY, TripPlanner.hoursPerDay((1..30).map { episode("o$it", playedDaysAgo = 200) }, now))
    }

    @Test
    fun aWeekendPlaysAsTwoWeeksAtLeast() {
        // Ten episodes in the last two days aren't a 3.75 hours a day habit.
        val history = (1..10).map { episode("s$it", playedDaysAgo = 2) }
        assertEquals(7.5 / 14, TripPlanner.hoursPerDay(history, now), 0.01)
    }

    @Test
    fun suggestionsShareOutShowsThenFilms() {
        // 3 days at 2 h: 6 h × 1.25 = 7.5 h to cover.
        val picks = TripPlanner.suggest(
            nextUp = listOf(episode("a"), episode("b")),
            started = listOf(film("f1", started = true)),
            favourites = listOf(film("f2"), film("f1")),
            hoursPerDay = 2.0, days = 3,
        )
        val shows = picks.filter { it.kind == PickKind.SHOW }
        assertEquals(listOf("a", "b"), shows.map { it.id })
        // A day's worth of each first (2 episodes of 45 min fit in 2 h), then the films, then more episodes.
        assertTrue(picks.any { it.id == "f1" } && picks.any { it.id == "f2" })
        val total = picks.sumOf { it.totalMs }
        assertTrue(total >= 7.5 * 3_600_000, "covers the trip: ${total / 3_600_000.0} h")
        assertTrue(picks.all { it.suggested })
    }

    @Test
    fun qualityFitsTheRoom() {
        val picks = listOf(TripPick("a", PickKind.SHOW, "A", episodes = 10, runtimeMs = 45 * 60_000, bitrate = 30_000_000))
        // 7.5 h: 720p HEVC ≈ 2.6 Mbps → ~8.6 GB; 1080p HEVC ≈ 5 Mbps → ~16.9 GB.
        assertEquals(DownloadQuality.MEDIUM, TripPlanner.fit(picks, room = 10_000_000_000, hevc = true, original = false))
        assertEquals(DownloadQuality.HIGH, TripPlanner.fit(picks, room = 20_000_000_000, hevc = true, original = false))
        // The laptop takes the originals when there's room: 30 Mbps for 7.5 h ≈ 101 GB.
        assertEquals(DownloadQuality.ORIGINAL, TripPlanner.fit(picks, room = 200_000_000_000, hevc = true, original = true))
        assertEquals(DownloadQuality.HIGH, TripPlanner.fit(picks, room = 200_000_000_000, hevc = true, original = false))
        // Nothing fits: the smallest.
        assertEquals(DownloadQuality.DATA_SAVER, TripPlanner.fit(picks, room = 1_000, hevc = true, original = false))
    }

    @Test
    fun roomLeavesAMargin() {
        assertEquals(8_000_000_000, TripPlanner.room(TripDevice("p", "Phone"), 10_000_000_000))
        assertEquals(90_000_000_000, TripPlanner.room(TripDevice("l", "Laptop"), 100_000_000_000))
        assertEquals(5_000_000_000, TripPlanner.room(TripDevice("p", "Phone", roomBytes = 5_000_000_000), 100_000_000_000))
        // A set room bigger than what's free counts only what's free.
        assertEquals(2_000_000_000, TripPlanner.room(TripDevice("p", "Phone", roomBytes = 16_000_000_000), 4_000_000_000))
    }

    @Test
    fun picksGoToTheirDevices() {
        val trip = Trip(
            "t", "Lake", now, now + 7 * day,
            devices = listOf(TripDevice("phone", "Phone"), TripDevice("laptop", "Laptop")),
            picks = listOf(TripPick("a", PickKind.TITLE, "A"), TripPick("b", PickKind.TITLE, "B", devices = listOf("laptop"))),
        )
        assertEquals(listOf("a"), trip.picksFor("phone").map { it.id })
        assertEquals(listOf("a", "b"), trip.picksFor("laptop").map { it.id })
        assertEquals(emptyList(), trip.picksFor("tv"))
        assertEquals(7, trip.days)
    }

    @Test
    fun readinessSaysWhenItWillFinish() {
        fun r(id: String, state: DownloadState, total: Long, got: Long) = DownloadRecord(
            id, "u", "user", MediaItem(id, id, ItemKind.MOVIE), DownloadQuality.MEDIUM, state = state, totalBytes = total, bytesDownloaded = got,
            createdAtMs = now - 3_600_000, tripId = "t",
        )
        // 2 GB fetched in an hour, 2 GB left: an hour to go.
        val records = listOf(r("a", DownloadState.COMPLETED, 1_000_000_000, 1_000_000_000), r("b", DownloadState.DOWNLOADING, 2_000_000_000, 1_000_000_000), r("c", DownloadState.QUEUED, 1_000_000_000, 0))
        val soon = TripPlanner.readiness(records, 3, leaveMs = now + day, nowMs = now)
        assertEquals(now + 3_600_000, soon.finishMs)
        assertEquals("1 of 3 on this phone · finishing about 1 h from now", soon.describe(now, "this phone"))
        val late = TripPlanner.readiness(records, 3, leaveMs = now + 60_000, nowMs = now)
        assertTrue(late.late)
        val done = TripPlanner.readiness(records.map { it.copy(state = DownloadState.COMPLETED) }, 3, now + day, now)
        assertTrue(done.ready)
        assertEquals("All 3 on this phone: ready to go", done.describe(now, "this phone"))
    }

    @Test
    fun afterTheTripStartedTitlesStay() {
        fun r(id: String, played: Boolean, position: Long, trip: String? = "t") =
            DownloadRecord(id, "u", "user", MediaItem(id, id, ItemKind.MOVIE, user = UserState(played = played, positionMs = position)), DownloadQuality.MEDIUM, tripId = trip)
        val records = listOf(r("watched", true, 0), r("started", false, 60_000), r("untouched", false, 0), r("mine", true, 0, trip = null))
        assertEquals(listOf("watched"), TripPlanner.toClear(records, "t", watchedOnly = true).map { it.itemId })
        assertEquals(listOf("watched", "untouched"), TripPlanner.toClear(records, "t", watchedOnly = false).map { it.itemId })
    }

    @Test
    fun tripsAndPackersFollowTheUserEverywhere() {
        // One synced entry each, the same on every display profile: devices editing different trips merge.
        assertTrue(dev.nitrate.core.pages.DisplayProfiles.isShared("trip:abc"))
        assertTrue(dev.nitrate.core.pages.DisplayProfiles.isShared("packer:phone-1"))
        val merged = dev.nitrate.core.pages.DisplayProfiles.merge(
            base = mapOf("trip:a" to "1"),
            local = mapOf("trip:a" to "1", "trip:b" to "2"),
            remote = mapOf("trip:a" to "1b"),
        )
        assertEquals(mapOf("trip:a" to "1b", "trip:b" to "2"), merged)
    }
}
