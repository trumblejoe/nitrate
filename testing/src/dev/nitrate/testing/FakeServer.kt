package dev.nitrate.testing

import dev.nitrate.core.LibrarySort
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.CollectionKind
import dev.nitrate.core.model.DiscoveredServer
import dev.nitrate.core.model.ImageKind
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.Person
import dev.nitrate.core.model.PlaybackPlan
import dev.nitrate.core.model.QuickConnectRequest
import dev.nitrate.core.model.ServerAddress
import dev.nitrate.core.model.ServerUser
import dev.nitrate.core.model.SessionState
import dev.nitrate.core.model.UserState
import dev.nitrate.core.playback.ChannelClip
import dev.nitrate.core.rows.MediaRow
import dev.nitrate.core.rows.RowDisplay
import dev.nitrate.core.rows.RowStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** A small, believable library for rendering screens without a server. */
class FakeServer : MediaServer {
    override val userId = "user-1"
    override val serverUrl = "http://192.168.1.20:8096"

    private val movies = MediaItem("lib-movies", "Movies", ItemKind.LIBRARY, collection = CollectionKind.MOVIES)
    private val shows = MediaItem("lib-shows", "Shows", ItemKind.LIBRARY, collection = CollectionKind.TV_SHOWS)

    private fun movie(id: String, name: String, year: Int, pct: Double? = null) = MediaItem(
        id, name, ItemKind.MOVIE, productionYear = year, officialRating = "PG-13", runtimeMs = 6_960_000, communityRating = 7.8f,
        genres = listOf("Drama", "Mystery"), overview = "A projectionist discovers a reel that shouldn't exist.",
        user = UserState(playedPercentage = pct, positionMs = if (pct != null) 2_100_000 else 0),
    )

    val series = MediaItem(
        "series-1", "The Night Reel", ItemKind.SERIES, productionYear = 2021, endYear = 2024, officialRating = "TV-14",
        communityRating = 8.4f, genres = listOf("Thriller"), overview = "Three seasons inside a cinema that never closes.",
        people = listOf(Person("p1", "Maya Ortiz", "Lead"), Person("p2", "Sam Reel", "Projectionist"), Person("p3", "Ana Bell", "Director")),
    )

    private fun episode(season: Int, n: Int, played: Boolean) = MediaItem(
        "s${season}e$n", "Episode title $n", ItemKind.EPISODE, seriesId = series.id, seriesName = series.name, seasonId = "season-$season", seasonNumber = season,
        indexNumber = n, runtimeMs = 2_700_000, overview = "Something flickers in booth $n.", user = UserState(played = played),
    )

    private val seasonList = (1..3).map { MediaItem("season-$it", "Season $it", ItemKind.SEASON, seriesId = series.id, seasonNumber = it, indexNumber = it) }
    private val films = listOf(movie("m1", "Nitrate", 2023, 34.0), movie("m2", "Silver Screen", 2019), movie("m3", "Gate Weave", 2024), movie("m4", "Persistence", 2020))

    override suspend fun restoreSession() = SessionState.VALID
    override suspend fun checkSession() = SessionState.VALID
    override suspend fun findServer(input: String) = ServerAddress(serverUrl, "Home")
    override fun discoverServers(): Flow<DiscoveredServer> = flowOf(DiscoveredServer("1", "Living room", serverUrl))
    override suspend fun publicUsers(serverUrl: String) = listOf(ServerUser("u1", "Joe", true, null), ServerUser("u2", "Guest", false, null))
    override suspend fun login(serverUrl: String, username: String, password: String) = Unit
    override suspend fun quickConnectEnabled(serverUrl: String) = true
    override suspend fun startQuickConnect(serverUrl: String) = QuickConnectRequest("482913", "s")
    override suspend fun quickConnectApproved(secret: String) = false
    override suspend fun finishQuickConnect(serverUrl: String, secret: String) = Unit
    override suspend fun logout() = Unit
    override suspend fun loadCustomPrefs(prefsId: String) = emptyMap<String, String>()
    override suspend fun saveCustomPrefs(prefs: Map<String, String>, prefsId: String) = Unit
    override suspend fun views() = listOf(movies, shows)
    override suspend fun homeRows() = listOf(
        MediaRow("home/continue_watching", "Continue Watching", listOf(films[0], episode(2, 4, false).copy(user = UserState(playedPercentage = 60.0))), RowDisplay(RowStyle.WIDE, showTitles = true)),
        MediaRow("home/next_up", "Next Up", listOf(episode(2, 5, false)), RowDisplay(RowStyle.WIDE, showTitles = true)),
        MediaRow("home/latest/lib-movies", "Latest in Movies", films, RowDisplay()),
        MediaRow("home/latest/lib-shows", "Latest in Shows", listOf(series), RowDisplay()),
    )
    override suspend fun libraryRows(library: MediaItem) = homeRows().drop(2)
    override suspend fun libraryPage(library: MediaItem, sort: LibrarySort, startIndex: Int, limit: Int, includeTags: Boolean) =
        (if (startIndex == 0) films + films.map { it.copy(id = it.id + "b") } else emptyList()) to films.size * 2
    override suspend fun getItem(id: String) = when (id) {
        series.id -> series
        movies.id -> movies
        shows.id -> shows
        else -> films.firstOrNull { it.id == id } ?: films[0]
    }
    override suspend fun seasons(seriesId: String) = seasonList
    override suspend fun episodes(seriesId: String, seasonId: String) = (1..6).map { episode(2, it, it < 4) }
    override suspend fun allEpisodes(seriesId: String) = (1..3).flatMap { s -> (1..6).map { episode(s, it, s < 2) } }
    override suspend fun nextUpFor(seriesId: String) = episode(2, 4, false)
    override suspend fun similar(itemId: String) = films
    override suspend fun playlistItems(playlistId: String) = films
    override suspend fun setPlayed(itemId: String, played: Boolean) = Unit
    override suspend fun setFavorite(itemId: String, favorite: Boolean) = Unit
    /** Generated abstract artwork (tools/generate-sample-art.py) as local files. */
    private fun art(name: String): String? = javaClass.getResource("/art/$name.jpg")?.let { java.io.File(it.toURI()).path }

    private fun artKey(item: MediaItem) = if (item.kind == ItemKind.EPISODE) item.seriesId ?: item.id else item.id.removeSuffix("b")

    override fun imageUrl(item: MediaItem, kind: ImageKind, maxHeight: Int): String? = when {
        kind == ImageKind.BACKDROP -> backdropUrl(item)
        item.kind == ItemKind.EPISODE -> art("episode-${(item.indexNumber ?: 1).coerceIn(1, 6)}")
        item.kind == ItemKind.LIBRARY -> art(if (item.collection == CollectionKind.MOVIES) "m2-backdrop" else "series-1-backdrop")
        else -> art("${artKey(item)}-poster")
    }
    override fun backdropUrl(item: MediaItem): String? = art("${artKey(item)}-backdrop")
    override fun backdropUrls(item: MediaItem) = listOfNotNull(backdropUrl(item))
    override suspend fun search(query: String) = dev.nitrate.core.model.SearchResults(
        movies = films.filter { it.name.contains(query, ignoreCase = true) || query.length < 3 },
        shows = listOf(series),
        episodes = (1..3).map { episode(1, it, false) },
        people = series.people,
    )
    override fun trickplaySheetUrl(itemId: String, info: dev.nitrate.core.model.TrickplayInfo, sheet: Int) = art("trickplay-0")!!
    override suspend fun queryItems(query: dev.nitrate.core.pages.ItemQuery) = films.reversed()

    /** A longer list for browsing screens: the films plus more, in name order. */
    private val shelf = (films + listOf("Arclight", "Booth Nine", "Cue Marks", "Dissolve", "Emulsion", "Frame Line", "Iris Out", "Leader Tape", "Matte Box", "Optical Sound", "Reel Change", "Splice")
        .mapIndexed { i, n -> movie("x$i", n, 1980 + i * 3) }).sortedBy { it.name }
    override suspend fun queryPage(query: dev.nitrate.core.pages.ItemQuery, startIndex: Int, limit: Int) =
        shelf.drop(startIndex).take(limit) to shelf.size
    override suspend fun collectionItems(collectionId: String) = films.take(3)
    override suspend fun genres(libraryId: String?) = listOf("Action", "Comedy", "Drama", "Horror", "Mystery", "Sci-Fi", "Thriller")
    override suspend fun collections() = listOf(MediaItem("c1", "Reel Classics", ItemKind.BOX_SET), MediaItem("c2", "Midnight Movies", ItemKind.BOX_SET))
    override suspend fun playlists() = listOf(MediaItem("p1", "Rainy day", ItemKind.PLAYLIST), MediaItem("p2", "Road trip", ItemKind.PLAYLIST))
    override suspend fun account() = dev.nitrate.core.admin.AccountInfo("user-1", "Joe", isAdmin = true)
    override suspend fun metadata(itemId: String) = getItem(itemId).let { i ->
        dev.nitrate.core.admin.ItemMetadata(
            id = i.id, name = i.name, overview = i.overview, productionYear = i.productionYear, officialRating = i.officialRating,
            communityRating = i.communityRating, genres = i.genres, tags = listOf("cult"), studios = listOf("Reel House"),
            people = i.people.map { dev.nitrate.core.admin.PersonCredit(it.name, it.role) }, premiereDate = "2023-10-13",
            providerIds = mapOf("Tmdb" to "12345", "Imdb" to "tt0000001"), lockedFields = listOf("Overview"),
        )
    }
    override suspend fun remoteSearch(item: MediaItem, name: String, year: Int?) = listOf(
        dev.nitrate.core.admin.RemoteMatch("Nitrate", 2023, "A projectionist discovers a reel that shouldn't exist.", art("m1-poster"), "TheMovieDb", mapOf("Tmdb" to "12345"), "{}"),
        dev.nitrate.core.admin.RemoteMatch("Nitrate (Director's Cut)", 2024, null, art("m3-poster"), "TheMovieDb", mapOf("Tmdb" to "67890"), "{}"),
    )
    override suspend fun itemImages(itemId: String) = listOfNotNull(
        art("m1-poster")?.let { dev.nitrate.core.admin.ItemImage(dev.nitrate.core.admin.ImageType.PRIMARY, null, 1000, 1500, it) },
        art("m1-backdrop")?.let { dev.nitrate.core.admin.ItemImage(dev.nitrate.core.admin.ImageType.BACKDROP, 0, 1920, 1080, it) },
        art("m2-backdrop")?.let { dev.nitrate.core.admin.ItemImage(dev.nitrate.core.admin.ImageType.BACKDROP, 1, 1920, 1080, it) },
    )
    override suspend fun remoteImages(itemId: String, type: dev.nitrate.core.admin.ImageType) =
        listOf("m1", "m2", "m3", "m4", "series-1").mapNotNull { k -> art("$k-poster")?.let { dev.nitrate.core.admin.RemoteImage(it, it, "TheMovieDb", 1000, 1500, "en", 7.2) } }
    override suspend fun tasks() = listOf(
        dev.nitrate.core.admin.ServerTask("t1", "Scan media library", "Library", "Scans your libraries for new files", dev.nitrate.core.admin.TaskState.RUNNING, 42.0, "Completed", null, "2026-11-11T08:00:00Z"),
        dev.nitrate.core.admin.ServerTask("t2", "Generate trickplay images", "Library", "Creates scrubbing thumbnails", dev.nitrate.core.admin.TaskState.IDLE, null, "Failed", "ffmpeg exited with code 1", "2026-11-10T03:00:00Z"),
        dev.nitrate.core.admin.ServerTask("t3", "Clean cache directory", "Maintenance", null, dev.nitrate.core.admin.TaskState.IDLE, null, "Completed", null, "2026-11-11T02:00:00Z"),
    )
    override suspend fun activity(limit: Int) = listOf(
        dev.nitrate.core.admin.ActivityEntry(3, "Joe is playing Nitrate on Living room TV", null, "VideoPlayback", "2026-11-11T20:14:00Z", "Information"),
        dev.nitrate.core.admin.ActivityEntry(2, "Sam successfully authenticated", null, "AuthenticationSucceeded", "2026-11-11T19:02:00Z", "Information"),
        dev.nitrate.core.admin.ActivityEntry(1, "Generate trickplay images failed", "ffmpeg exited with code 1", "ScheduledTaskFailed", "2026-11-10T03:00:00Z", "Error"),
    )
    override suspend fun sessions() = listOf(
        dev.nitrate.core.admin.ServerSession("s1", "Joe", "Nitrate TV", "Living room TV", "d1", films[0], 2_100_000, false, "DirectPlay", "192.168.1.30", null, true, true),
        dev.nitrate.core.admin.ServerSession("s2", "Sam", "Jellyfin Web", "Firefox", "d2", null, null, false, null, "192.168.1.44", null, true, false),
    )
    /** What [recentEpisodes] returns; tests add to it. */
    var recent: List<MediaItem> = emptyList()
    override suspend fun recentEpisodes(limit: Int) = recent
    override suspend fun followedSeries() = setOf("series-1")
    override suspend fun savedAccounts() = listOf(
        dev.nitrate.core.model.SavedAccount("home|joe", "http://192.168.1.20:8096", "Home", "user-1", "Joe", null),
        dev.nitrate.core.model.SavedAccount("home|sam", "http://192.168.1.20:8096", "Home", "user-2", "Sam", null),
        dev.nitrate.core.model.SavedAccount("home|kids", "http://192.168.1.20:8096", "Home", "user-3", "Kids", null),
        dev.nitrate.core.model.SavedAccount("cabin|joe", "https://cabin.example.net", "Cabin", "user-9", "Joe", null),
    )
    override val currentAccountKey: String? get() = "home|joe"
    override suspend fun syncPlayGroups() = listOf(
        dev.nitrate.core.syncplay.SyncPlayGroup("g1", "Friday movie night", dev.nitrate.core.syncplay.GroupState.PAUSED, listOf("Sam", "Alex")),
        dev.nitrate.core.syncplay.SyncPlayGroup("g2", "Kids' cartoons", dev.nitrate.core.syncplay.GroupState.PLAYING, listOf("Kids")),
    )
    override suspend fun users() = listOf(
        dev.nitrate.core.admin.UserAccount("user-1", "Joe", true, "2026-11-11T19:00:00Z", null, dev.nitrate.core.admin.UserPermissions(isAdministrator = true, enableContentDeletion = true)),
        dev.nitrate.core.admin.UserAccount("user-2", "Sam", true, "2026-11-11T19:02:00Z", null, dev.nitrate.core.admin.UserPermissions()),
        dev.nitrate.core.admin.UserAccount("user-3", "Kids", false, null, null, dev.nitrate.core.admin.UserPermissions(enableContentDownloading = false, enableRemoteAccess = false)),
    )
    override suspend fun itemsWithPerson(personId: String) = films + series
    override fun logoUrl(item: MediaItem): String? = null
    override fun personImageUrl(person: Person): String? = null
    override fun authorizationHeader() = ""
    override suspend fun resolvePlayback(itemId: String, isAudio: Boolean, startMs: Long, forceTranscode: Boolean, audioIndex: Int?, subtitleIndex: Int?, mediaSourceId: String?): PlaybackPlan =
        error("not used in screenshots")
    override suspend fun reportStart(plan: PlaybackPlan, positionMs: Long) = Unit
    override suspend fun reportProgress(plan: PlaybackPlan, positionMs: Long, isPaused: Boolean) = Unit
    override fun reportStopped(plan: PlaybackPlan, positionMs: Long) = Unit
    override suspend fun channelTestClips() = emptyList<ChannelClip>()
}
