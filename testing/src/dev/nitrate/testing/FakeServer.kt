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
    override suspend fun ssoProvider(serverUrl: String) = dev.nitrate.core.model.SsoProvider("Home ID")
    override suspend fun startSso(serverUrl: String) =
        dev.nitrate.core.model.SsoRequest("K4Q8-RUT2", "s", "http://192.168.1.20:8096/Nitrate/Sso/Login/K4Q8RUT2", 600_000)
    override suspend fun pollSso(serverUrl: String, request: dev.nitrate.core.model.SsoRequest) = dev.nitrate.core.model.SsoStatus.Waiting
    override suspend fun logout() = Unit
    /** Per-user storage, kept in memory: display-preferences id → its custom prefs. */
    val customPrefs = mutableMapOf<String, Map<String, String>>()
    override suspend fun loadCustomPrefs(prefsId: String) = customPrefs[prefsId].orEmpty()
    override suspend fun saveCustomPrefs(prefs: Map<String, String>, prefsId: String) { customPrefs[prefsId] = prefs }
    override suspend fun views() = listOf(movies, shows)
    override fun castPlay(play: dev.nitrate.core.cast.CastPlay) = "play ${play.items.getOrNull(play.startIndex)?.id} at ${play.startMs}"
    override fun castCommand(command: dev.nitrate.core.cast.CastCommand, value: Long?) = "${command.name.lowercase()}${value?.let { " $it" } ?: ""}"
    override suspend fun appRow(key: String, libraryId: String?): MediaRow? {
        val homeKey = if (key == dev.nitrate.core.rows.AppRows.LATEST) "home/latest/$libraryId" else "home/$key"
        return homeRows().firstOrNull { it.key == homeKey }
    }

    override suspend fun householdQueries() = listOf(
        dev.nitrate.core.diagnostics.HouseholdQuery("/Items/{id}/Similar?limit&userid", 14, 3, 21_000, 1_200, 2_900, 3_400),
        dev.nitrate.core.diagnostics.HouseholdQuery("/Nitrate/Home?because&latest&limit&nextup&resume", 40, 4, 4_800, 110, 190, 260),
    )

    /** A few requests as the analyzer would have seen them. */
    override val queryLog: dev.nitrate.core.diagnostics.QueryLog = dev.nitrate.core.diagnostics.QueryLog().also { log ->
        fun add(endpoint: String, params: Map<String, String>, origin: String, ms: Long, outcome: dev.nitrate.core.diagnostics.QueryOutcome) {
            val (id, tag) = log.nextTag()
            log.add(
                dev.nitrate.core.diagnostics.QueryRecord(
                    id, tag, 1_700_000_000_000 + id * 1_000, "GET", endpoint, dev.nitrate.core.diagnostics.QueryLog.shapeOf(endpoint, params), origin, ms, outcome,
                    status = 200, bytes = 40_000, serverMs = if (outcome == dev.nitrate.core.diagnostics.QueryOutcome.FETCHED) ms - 40 else null,
                    hints = if (outcome == dev.nitrate.core.diagnostics.QueryOutcome.FETCHED) dev.nitrate.core.diagnostics.QueryHints.of(endpoint, params, ms, 40_000) else emptyList(),
                ),
            )
        }
        val similar = mapOf("limit" to "16")
        val played = mapOf("sortBy" to "DatePlayed", "recursive" to "true", "limit" to "8")
        add("/Items", played, "Home › Because you watched", 1_850, dev.nitrate.core.diagnostics.QueryOutcome.FETCHED)
        add("/Items/{id}/Similar", similar, "Home › Because you watched", 2_400, dev.nitrate.core.diagnostics.QueryOutcome.FETCHED)
        add("/Shows/NextUp", mapOf("limit" to "24"), "Home › Next Up", 120, dev.nitrate.core.diagnostics.QueryOutcome.FETCHED)
        add("/Shows/NextUp", mapOf("limit" to "24"), "Custom page Evenings › Next Up", 2, dev.nitrate.core.diagnostics.QueryOutcome.KEPT)
        add("/Items/{id}/Similar", similar, "Details", 3, dev.nitrate.core.diagnostics.QueryOutcome.JOINED)
    }

    override suspend fun homeRows() = listOf(
        MediaRow("home/continue_watching", "Continue Watching", listOf(films[0], episode(2, 4, false).copy(user = UserState(playedPercentage = 60.0))), RowDisplay(RowStyle.WIDE, showTitles = true)),
        MediaRow("home/next_up", "Next Up", listOf(episode(2, 5, false)), RowDisplay(RowStyle.WIDE, showTitles = true)),
        MediaRow("home/latest/lib-movies", "Latest in Movies", films, RowDisplay(), dev.nitrate.core.rows.RowMore(listOf("latest"), mapOf("parentId" to "lib-movies"))),
        MediaRow("home/latest/lib-shows", "Latest in Shows", listOf(series), RowDisplay()),
    )
    override suspend fun libraryRows(library: MediaItem) = homeRows().drop(2)
    /** More of "Latest in Movies": the films again under new ids, 20 titles in all. */
    override suspend fun moreOfRow(more: dev.nitrate.core.rows.RowMore, startIndex: Int, limit: Int): List<MediaItem> =
        (startIndex until minOf(startIndex + limit, 20)).map { i -> films[i % films.size].copy(id = "${films[i % films.size].id}-more$i") }
    override suspend fun libraryPage(library: MediaItem, sort: LibrarySort, startIndex: Int, limit: Int, includeTags: Boolean) =
        (if (startIndex == 0) films + films.map { it.copy(id = it.id + "b") } else emptyList()) to films.size * 2
    val album = MediaItem("al1", "Second Reel", ItemKind.MUSIC_ALBUM, productionYear = 2022, albumArtist = "The Projectionists", albumArtistId = "ar1", genres = listOf("Rock"),
        overview = "Songs written in the booth between changeovers.")
    private val tracks = listOf("Changeover", "Cue Dots", "Lamp House", "Take-up Reel", "House Lights").mapIndexed { i, n ->
        MediaItem("t$i", n, ItemKind.AUDIO, mediaKind = dev.nitrate.core.model.MediaKind.AUDIO, indexNumber = i + 1, runtimeMs = 180_000L + i * 17_000, album = album.name, albumId = album.id,
            artists = if (i == 3) listOf("The Projectionists", "Maya Ortiz") else listOf("The Projectionists"), albumArtist = "The Projectionists")
    }
    private val channels = listOf("Reel One" to "1", "Cartoon Booth" to "2", "Night Matinee" to "3", "Newsreel" to "4").mapIndexed { i, (n, num) ->
        MediaItem("ch$i", n, ItemKind.TV_CHANNEL, mediaKind = dev.nitrate.core.model.MediaKind.VIDEO)  to num
    }
    private val programmeTitles = listOf("Morning Matinee", "The Projectionist Hour", "Silent Classics", "Double Bill", "Behind the Screen", "Late Feature", "Credits Roll", "Overnight Reels")
    private fun programmes(): List<dev.nitrate.core.livetv.TvProgram> {
        val slot = 30 * 60_000L
        val base = System.currentTimeMillis().let { it - it % slot } - slot
        return channels.flatMapIndexed { c, (ch, _) ->
            var t = base - c * 10 * 60_000L
            (0 until 8).map { i ->
                val length = slot * (1 + (i + c) % 3)
                dev.nitrate.core.livetv.TvProgram("p$c-$i", ch.id, programmeTitles[(i + c * 3) % programmeTitles.size], t, t + length, overview = "Tonight on ${ch.name}.", timerId = if (c == 0 && i == 3) "timer" else null).also { t += length }
            }
        }
    }
    override suspend fun liveTvAvailable() = true
    override suspend fun liveChannels() = programmes().let { all ->
        channels.map { (ch, num) -> dev.nitrate.core.livetv.LiveChannel(ch, num, all.firstOrNull { it.channelId == ch.id && it.isAiring(System.currentTimeMillis()) }) }
    }
    override suspend fun guide(channelIds: List<String>, fromMs: Long, toMs: Long) = programmes().filter { it.channelId in channelIds && it.endMs > fromMs && it.startMs < toMs }
    override suspend fun recordings() = listOf(films[1].copy(id = "rec1", name = "Silent Classics"))
    override suspend fun albumTracks(albumId: String) = tracks
    override suspend fun instantMix(itemId: String) = tracks.shuffled()
    override suspend fun getItem(id: String) = when (id) {
        "p1" -> MediaItem("p1", "Rainy day", ItemKind.PLAYLIST)
        album.id -> album
        series.id -> series
        movies.id -> movies
        shows.id -> shows
        else -> Regex("s(\\d)e(\\d)").matchEntire(id)?.let { m ->
            // An episode, with chapters (as a whole item carries them).
            episode(m.groupValues[1].toInt(), m.groupValues[2].toInt(), false).copy(
                chapters = listOf("Cold open" to 0L, "The booth" to 420_000L, "Reel change" to 1_260_000L, "Projection" to 2_100_000L)
                    .map { (n, at) -> dev.nitrate.core.model.Chapter(at, n) },
            )
        } ?: films.firstOrNull { it.id == id } ?: films[0]
    }
    override suspend fun seasons(seriesId: String) = seasonList
    override suspend fun episodes(seriesId: String, seasonId: String) = (seasonId.removePrefix("season-").toIntOrNull() ?: 2).let { s -> (1..6).map { episode(s, it, s < 2 || (s == 2 && it < 4)) } }
    override suspend fun allEpisodes(seriesId: String) = (1..3).flatMap { s -> (1..6).map { episode(s, it, s < 2) } }
    override suspend fun nextUpFor(seriesId: String) = episode(2, 4, false)
    override suspend fun similar(itemId: String) = films
    /** "Rainy day" is a video playlist in watch order: a film, a few episodes, another film, then the rest of the show. */
    override suspend fun playlistItems(playlistId: String) =
        if (playlistId == "p1") listOf(films[0], episode(1, 1, true), episode(1, 2, true), films[1], episode(1, 3, false), episode(2, 1, false)) else films
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
    override suspend fun servers() = listOf(
        dev.nitrate.core.model.SavedServer(
            url = "http://192.168.1.20:8096", name = "Home", local = "http://192.168.1.20:8096", vpn = "http://100.64.0.7:8096", remote = "https://media.example.net",
            primary = true, accounts = savedAccounts().filter { it.serverUrl.contains("192.168") }, active = "http://192.168.1.20:8096",
        ),
        dev.nitrate.core.model.SavedServer(
            url = "https://cabin.example.net", name = "Cabin", local = null, vpn = null, remote = "https://cabin.example.net",
            primary = false, accounts = savedAccounts().filter { it.serverUrl.contains("cabin") }, active = null,
        ),
    )
    override suspend fun testAddress(input: String, serverUrl: String?): dev.nitrate.core.model.AddressCheck =
        if (input.contains("192.168")) dev.nitrate.core.model.AddressCheck.Reached("http://192.168.1.20:8096", "Home", 18, sameServer = true)
        else dev.nitrate.core.model.AddressCheck.Failed("No Jellyfin server answered at \"$input\"")

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
    /** The first films are a saga (a collection), so a film's page shows "The collection". */
    override suspend fun collectionsOf(itemId: String) =
        if (films.take(3).any { it.id == itemId }) listOf(MediaItem("saga-1", "The Nitrate Collection", ItemKind.BOX_SET)) else emptyList()
    override suspend fun genres(libraryId: String?) = listOf("Action", "Comedy", "Drama", "Horror", "Mystery", "Sci-Fi", "Thriller")
    override suspend fun collections() = listOf(MediaItem("c1", "Reel Classics", ItemKind.BOX_SET), MediaItem("c2", "Midnight Movies", ItemKind.BOX_SET))
    /** A check from far away: a slow round trip and a slow library search, the rest fine. */
    override suspend fun speedCheck(onStep: (dev.nitrate.core.diagnostics.SpeedStep) -> Unit): List<dev.nitrate.core.diagnostics.SpeedStep> {
        val S = dev.nitrate.core.diagnostics.SpeedCheck
        return listOf(
            dev.nitrate.core.diagnostics.SpeedStep(S.PING, "Round trip", "Time to reach the server and back, with no work for it to do", listOf(210, 190, 230, 205, 198)),
            dev.nitrate.core.diagnostics.SpeedStep(S.SIGNED_IN, "Signed-in call", "A small request that checks your sign-in (your account)", listOf(260, 240, 250)),
            dev.nitrate.core.diagnostics.SpeedStep(S.LIBRARIES, "Library list", "Your libraries, as the menu shows them", listOf(320, 300, 310)),
            dev.nitrate.core.diagnostics.SpeedStep(S.SEARCH, "Library search", "Searching the whole library for 100 titles with details: the server's database at work", listOf(1800, 1650, 1700), extra = "18,402 items in the library"),
            dev.nitrate.core.diagnostics.SpeedStep(S.HOME, "Home page", "Loading every row of Home, all at once as Nitrate does; each part timed on its own", listOf(4100, 3900), extra = "9 rows", parts = listOf("Because you watched" to 3800L, "Next Up" to 900L, "Latest in each library" to 650L, "Continue Watching" to 420L)),
            dev.nitrate.core.diagnostics.SpeedStep(S.DOWNLOAD, "Download speed", "Downloading 2 MB from the server", listOf(700, 650), mbps = 24.6),
            dev.nitrate.core.diagnostics.SpeedStep(S.PARSE, "Reading on this device", "Turning the library search's answer (184 KB) into titles, here on this device", listOf(120, 110, 115)),
        ).onEach(onStep)
    }

    override suspend fun playlists() = listOf(MediaItem("p1", "Rainy day", ItemKind.PLAYLIST), MediaItem("p2", "Road trip", ItemKind.PLAYLIST))
    override suspend fun extras(itemId: String) =
        if (itemId == "m1") listOf(MediaItem("m1-trailer", "Trailer", ItemKind.VIDEO, extraType = "Trailer")) else emptyList()
    override suspend fun account() = dev.nitrate.core.admin.AccountInfo("user-1", "Joe", isAdmin = true)
    override suspend fun metadata(itemId: String) = getItem(itemId).let { i ->
        dev.nitrate.core.admin.ItemMetadata(
            id = i.id, name = i.name, overview = i.overview, productionYear = i.productionYear, officialRating = i.officialRating,
            communityRating = i.communityRating, genres = i.genres, tags = listOf("cult"), studios = listOf("Reel House"),
            people = i.people.map { dev.nitrate.core.admin.PersonCredit(it.name, it.role) }, premiereDate = "2023-10-13",
            providerIds = mapOf("Tmdb" to "12345", "Imdb" to "tt0000001"), lockedFields = listOf("Overview"),
        )
    }
    override suspend fun remoteSearch(item: MediaItem, name: String, year: Int?, providerIds: Map<String, String>) = listOf(
        dev.nitrate.core.admin.RemoteMatch("Nitrate", 2023, "A projectionist discovers a reel that shouldn't exist.", art("m1-poster"), "TheMovieDb", mapOf("Tmdb" to "12345"), "{}"),
        dev.nitrate.core.admin.RemoteMatch("Nitrate (Director's Cut)", 2024, null, art("m3-poster"), "TheMovieDb", mapOf("Tmdb" to "67890"), "{}"),
    )
    override suspend fun itemImages(itemId: String) = listOfNotNull(
        art("m1-poster")?.let { dev.nitrate.core.admin.ItemImage(dev.nitrate.core.admin.ImageType.PRIMARY, null, 1000, 1500, it) },
        art("m1-backdrop")?.let { dev.nitrate.core.admin.ItemImage(dev.nitrate.core.admin.ImageType.BACKDROP, 0, 1920, 1080, it) },
        art("m2-backdrop")?.let { dev.nitrate.core.admin.ItemImage(dev.nitrate.core.admin.ImageType.BACKDROP, 1, 1920, 1080, it) },
    )
    override suspend fun externalIdFields(itemId: String) = listOf(
        dev.nitrate.core.admin.ExternalIdField("Tmdb", "TheMovieDb", "Movie"),
        dev.nitrate.core.admin.ExternalIdField("Imdb", "IMDb", "Movie"),
    )
    override suspend fun imageProviders(itemId: String) = listOf(
        dev.nitrate.core.admin.ImageProvider("TheMovieDb", setOf(dev.nitrate.core.admin.ImageType.PRIMARY, dev.nitrate.core.admin.ImageType.BACKDROP, dev.nitrate.core.admin.ImageType.LOGO)),
        dev.nitrate.core.admin.ImageProvider("Fanart", setOf(dev.nitrate.core.admin.ImageType.BACKDROP, dev.nitrate.core.admin.ImageType.LOGO, dev.nitrate.core.admin.ImageType.ART)),
    )
    override suspend fun remoteImages(itemId: String, type: dev.nitrate.core.admin.ImageType, provider: String?, allLanguages: Boolean) =
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
    /** The user's own controllable devices: the living-room TV, playing a film. */
    override suspend fun controllableSessions() = listOf(
        dev.nitrate.core.admin.ServerSession("s1", "Joe", "Nitrate TV", "Living room TV", "d1", films[0], 2_100_000, false, "DirectPlay", "192.168.1.30", null, true, true, audioIndex = 1, subtitleIndex = -1, volume = 60),
        dev.nitrate.core.admin.ServerSession("s3", "Joe", "Nitrate TV", "Bedroom TV", "d3", null, null, false, null, "192.168.1.31", null, true, true),
    )
    override val deviceId: String? get() = "fake-phone"

    /** A year of watching, for the stats screens. */
    override suspend fun watchHistory(max: Int): List<MediaItem> {
        fun at(month: Int, day: Int) = java.time.LocalDateTime.of(2026, month, day, 20, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        fun watched(item: MediaItem, month: Int, day: Int, plays: Int = 1, minutes: Int = 110, genres: List<String> = listOf("Drama")) =
            item.copy(runtimeMs = minutes * 60_000L, genres = genres, user = item.user.copy(played = true, lastPlayedMs = at(month, day), playCount = plays))
        return listOf(
            watched(films[0], 1, 12, plays = 3, genres = listOf("Drama", "Mystery")), watched(films[1], 3, 2, genres = listOf("Science Fiction")),
            watched(films[2], 7, 19, plays = 2, genres = listOf("Science Fiction")), watched(films[3], 11, 5, genres = listOf("Comedy")),
        ) + (1..14).map { n ->
            watched(MediaItem("e$n", "Episode $n", ItemKind.EPISODE, seriesId = "series-1", seriesName = "The Night Reel"), 2 + n % 8, n, minutes = 45, genres = listOf("Mystery"))
        } + (1..6).map { n ->
            watched(MediaItem("x$n", "Episode $n", ItemKind.EPISODE, seriesId = "series-2", seriesName = "Gate Weave"), 9, n, minutes = 30, genres = listOf("Comedy"))
        } + (1..5).map { n ->
            watched(MediaItem("t$n", "Song $n", ItemKind.AUDIO, artists = listOf(if (n < 4) "Iris Out" else "The Projectionists")), 5, n, plays = 12 - n, minutes = 4)
        }
    }

    /** What [recentEpisodes] returns; tests add to it. */
    var recent: List<MediaItem> = emptyList()
    override suspend fun recentEpisodes(limit: Int) = recent
    override suspend fun followedSeries() = setOf("series-1")
    override suspend fun watchedSeries() = setOf("series-1")
    override suspend fun savedAccounts() = listOf(
        dev.nitrate.core.model.SavedAccount("home|joe", "http://192.168.1.20:8096", "Home", "user-1", "Joe", null),
        dev.nitrate.core.model.SavedAccount("home|sam", "http://192.168.1.20:8096", "Home", "user-2", "Sam", null),
        dev.nitrate.core.model.SavedAccount("home|kids", "http://192.168.1.20:8096", "Home", "user-3", "Kids", null),
        dev.nitrate.core.model.SavedAccount("cabin|joe", "https://cabin.example.net", "Cabin", "user-9", "Joe", null),
    )
    override val currentAccountKey: String? get() = "home|joe"
    /** A watch-together chat already under way; later requests wait (as the server's long poll would). */
    val chat = mutableListOf(
        dev.nitrate.core.companion.ChatMessage(1, "user-2", "sam", dev.nitrate.core.companion.ChatKind.TEXT, "Here we go. Popcorn ready?", 0, 0),
        dev.nitrate.core.companion.ChatMessage(2, "user-1", "nitrate", dev.nitrate.core.companion.ChatKind.REACTION, "🍿", 12_000, 0),
        dev.nitrate.core.companion.ChatMessage(3, "user-2", "sam", dev.nitrate.core.companion.ChatKind.TEXT, "This score is unreal", 754_000, 0),
        dev.nitrate.core.companion.ChatMessage(4, "user-3", "jo", dev.nitrate.core.companion.ChatKind.REACTION, "😱", 3_721_000, 0),
    )

    /** A small library for the playback overview: plays as it is, sound converted, picture converted, software decoded. */
    override suspend fun playbackSources(libraryId: String?, onProgress: (Int, Int) -> Unit): List<Pair<dev.nitrate.core.model.MediaItem, dev.nitrate.core.model.MediaSource>> {
        fun v(codec: String, h: Int, bits: Int = 8, range: String = "SDR", profile: String? = null, fps: Float = 23.976f) =
            dev.nitrate.core.model.MediaStream(0, dev.nitrate.core.model.StreamKind.VIDEO, codec = codec, profile = profile, width = h * 16 / 9, height = h, bitDepth = bits, rangeType = range, frameRate = fps)
        fun a(codec: String, ch: Int = 6) = dev.nitrate.core.model.MediaStream(1, dev.nitrate.core.model.StreamKind.AUDIO, codec = codec, channels = ch, isDefault = true)
        fun src(vararg s: dev.nitrate.core.model.MediaStream, container: String = "mkv", bitrate: Int = 20_000_000) = dev.nitrate.core.model.MediaSource("s", container, bitrate, s.toList())
        fun movie(id: String, name: String, year: Int) = dev.nitrate.core.model.MediaItem(id, name, dev.nitrate.core.model.ItemKind.MOVIE, productionYear = year)
        fun ep(n: Int) = dev.nitrate.core.model.MediaItem("o-e$n", "Episode $n", dev.nitrate.core.model.ItemKind.EPISODE, seriesId = "o-show", seriesName = "Harbour Lights", seasonNumber = 1, indexNumber = n)
        val list = listOf(
            movie("o-1", "Northern Lights", 2021) to src(v("hevc", 2160, 10, "HDR10"), a("eac3"), bitrate = 72_000_000),
            movie("o-2", "Silver Screen", 2019) to src(v("h264", 1080), a("truehd", 8)),
            movie("o-3", "Gate Weave", 2024) to src(v("hevc", 1080, 10, profile = "Rext"), a("aac", 2)),
            movie("o-4", "Persistence", 2020) to src(v("vc1", 1080), a("dts"), container = "mkv"),
            movie("o-5", "Old Reel", 1987) to src(v("mpeg4", 480, fps = 25f), a("mp3", 2), container = "avi"),
        ) + (1..3).map { ep(it) to src(v("h264", 1080, fps = 29.97f), a("ac3")) }
        onProgress(list.size, list.size)
        return list
    }

    /** A library with a few things to fix, for the library health screens. */
    override suspend fun healthScan(libraryId: String?): dev.nitrate.core.diagnostics.HealthScan {
        fun streams(codec: String, audio: String = "aac", ranges: Set<String> = setOf("sdr")) =
            dev.nitrate.core.pages.StreamInfo(setOf(dev.nitrate.core.pages.VideoStreamInfo(codec, 8, ranges)), setOf(audio))
        val art = dev.nitrate.core.model.ItemImages(primary = "p", backdrops = listOf("b"))
        fun movie(id: String, name: String, year: Int, images: dev.nitrate.core.model.ItemImages = art) =
            dev.nitrate.core.model.MediaItem(id, name, dev.nitrate.core.model.ItemKind.MOVIE, productionYear = year, images = images)
        val show = dev.nitrate.core.model.MediaItem("h-show", "Harbour Lights", dev.nitrate.core.model.ItemKind.SERIES, productionYear = 2019, images = art)
        val episodes = (1..10).map { dev.nitrate.core.model.MediaItem("h-e$it", "Episode $it", dev.nitrate.core.model.ItemKind.EPISODE, seriesId = "h-show") }
        return dev.nitrate.core.diagnostics.HealthScan(
            titles = listOf(
                movie("h-1", "Night Shift", 2021).let { dev.nitrate.core.diagnostics.HealthTitle(it, true, true, listOf(it to streams("h264"))) },
                movie("h-2", "Night Shift", 2021).let { dev.nitrate.core.diagnostics.HealthTitle(it, true, true, listOf(it to streams("hevc"))) },
                movie("h-3", "The Long Field", 2017, dev.nitrate.core.model.ItemImages(backdrops = listOf("b"))).let { dev.nitrate.core.diagnostics.HealthTitle(it, false, true, listOf(it to streams("av1"))) },
                movie("h-4", "home_movie_2004_final", 2004, dev.nitrate.core.model.ItemImages()).let { dev.nitrate.core.diagnostics.HealthTitle(it, false, false, listOf(it to streams("h264", audio = "truehd"))) },
                dev.nitrate.core.diagnostics.HealthTitle(show, true, true, episodes.mapIndexed { i, e -> e to streams(if (i < 4) "hevc" else "h264", ranges = if (i < 4) setOf("dovi") else setOf("sdr")) }),
            ),
            missingEpisodes = listOf(2, 5).map { n ->
                dev.nitrate.core.model.MediaItem("h-m$n", "Missing $n", dev.nitrate.core.model.ItemKind.EPISODE, seriesId = "h-show", seriesName = "Harbour Lights", seasonNumber = 1, indexNumber = n)
            },
        )
    }

    val houseChannels = mutableListOf(
        dev.nitrate.core.channels.HouseChannel(
            "c1", "Comfort TV", 3,
            listOf(dev.nitrate.core.channels.ChannelSource(dev.nitrate.core.channels.SourceKind.SERIES, "series-1", "The Long Night"), dev.nitrate.core.channels.ChannelSource(dev.nitrate.core.channels.SourceKind.ITEM, "m1", "Northern Lights", shuffle = true)),
            blocks = listOf(dev.nitrate.core.channels.ChannelBlock("Saturday cartoons", listOf(6), 480, 600, listOf(dev.nitrate.core.channels.ChannelSource(dev.nitrate.core.channels.SourceKind.SERIES, "series-1", "The Long Night")))),
        ),
        dev.nitrate.core.channels.HouseChannel("c2", "Movie Night", 7, listOf(dev.nitrate.core.channels.ChannelSource(dev.nitrate.core.channels.SourceKind.LIBRARY, "lib-movies", "Movies")), everyone = false, users = listOf("user-1")),
    )
    override suspend fun houseChannels() = houseChannels.toList()
    override suspend fun channelGuide(fromMs: Long, toMs: Long) = houseChannels.mapIndexed { c, ch ->
        val now = System.currentTimeMillis()
        val base = now - now % (30 * 60_000L) - c * 15 * 60_000L
        val titles = if (c == 0) listOf("The Long Night" to "S1 E3 · Embers", "Northern Lights" to "2019", "The Long Night" to "S1 E4 · Thaw", "Northern Lights" to "2019", "The Long Night" to "S1 E5 · Ice")
        else listOf("Northern Lights" to "2019", "Harbour" to "2021", "Glass Coast" to "2018")
        val lengths = if (c == 0) listOf(30, 60, 30, 60, 30) else listOf(105, 95, 120)
        var t = base
        dev.nitrate.core.channels.ChannelGuide(ch, titles.mapIndexed { i, (title, sub) ->
            val start = t
            t += lengths[i] * 60_000L
            dev.nitrate.core.channels.ChannelProgram("ep-$i", start, t, title, sub, if (sub.startsWith("S")) "Episode" else "Movie", null, i != 3)
        })
    }
    override suspend fun tuneChannel(channelId: String, count: Int) = channelGuide(0, 0).first { it.channel.id == channelId }.programs
    override suspend fun saveChannel(channel: dev.nitrate.core.channels.HouseChannel, startOver: Boolean) = channel
    var partnerInfo = dev.nitrate.core.partners.PartnersInfo(
        groups = listOf(
            dev.nitrate.core.partners.WatchGroup("g1", "series-1", "The Night Reel", listOf(dev.nitrate.core.writing.UserRef("user-2", "Will")), 0),
            dev.nitrate.core.partners.WatchGroup("g2", "series-1", "The Night Reel", listOf(dev.nitrate.core.writing.UserRef("user-4", "Frank"), dev.nitrate.core.writing.UserRef("user-5", "Tommy"), dev.nitrate.core.writing.UserRef("user-6", "Jane")), 0),
        ),
        incoming = listOf(dev.nitrate.core.partners.IncomingRequest("rq1", "user-3", "Jessica", "series-1", "The Night Reel", "g9", listOf("Jessica", "Lee"), 0)),
        outgoing = listOf(dev.nitrate.core.partners.OutgoingRequest("rq2", "kim", "series-1", "The Night Reel", null, 0)),
    )
    override suspend fun partners() = partnerInfo

    /** What [watchParty] answers (one quick look, as a phone's reminder takes). */
    var watchPartyState: dev.nitrate.core.partners.WatchParty? = null
    override suspend fun watchParty(groupId: String) = watchPartyState?.takeIf { it.groupId == groupId }

    /** A sample watch party reminder: Tommy and Suzy, 8:15 after Suzy's +15, S1:E4. */
    /** Sam asking the group to go weekly with Together on ([done]: how it went, with [outcome]). */
    fun sampleScheduleNews(done: Boolean = false, outcome: String = dev.nitrate.core.partners.ScheduleNews.PASSED) = dev.nitrate.core.partners.ScheduleNews(
        if (done) dev.nitrate.core.partners.ScheduleNews.DONE else dev.nitrate.core.partners.ScheduleNews.ASK, "g2", "c1",
        listOf("The Night Reel", "Nitrate"), listOf("Sam"), "Sam", false,
        dev.nitrate.core.partners.GroupSchedule(dev.nitrate.core.partners.GroupSchedule.WEEKLY, day = 4, hour = 19, together = true),
        outcome = if (done) outcome else null,
    )

    fun sampleSchedulePrompt(done: Boolean = false) = dev.nitrate.core.partners.SchedulePrompt.of(sampleScheduleNews(done))!!

    fun samplePartyPrompt(easy: Boolean = false): dev.nitrate.core.partners.PartyPrompt {
        val at = java.time.Instant.parse("2026-11-19T20:00:00Z").toEpochMilli()
        val party = dev.nitrate.core.partners.WatchParty(
            "g2", at, at + 15 * 60_000, partLabel = "S1:E4", canPushBack = true,
            delays = listOf(dev.nitrate.core.partners.PartyDelay("Suzy", 15, false)),
        )
        val news = dev.nitrate.core.partners.PartyNews(dev.nitrate.core.partners.PartyNews.REMINDER, "g2", "The Night Reel", listOf("Tommy", "Suzy"), null, false, 0, party)
        return dev.nitrate.core.partners.PartyPrompt.of(news, easy)!!
    }
    override suspend fun privacy() = dev.nitrate.core.partners.Privacy(isPrivate = false, byAdmin = false)

    /** What the watch-group setup offers before anything is typed. */
    override suspend fun recentlyWatched(limit: Int): List<MediaItem> = listOfNotNull(runCatching { getItem("series-1") }.getOrNull(), runCatching { getItem("m1") }.getOrNull())

    /** What was marked for groups ("[g1] [e1]" per call), for tests. */
    val groupMarks = mutableListOf<String>()

    override suspend fun markForGroups(groupIds: List<String>, itemIds: List<String>, watched: Boolean): List<String> {
        groupMarks += "$groupIds $itemIds" + if (watched) "" else " unwatched"
        return groupIds
    }
    override suspend fun partnerPeople() = listOf(dev.nitrate.core.writing.UserRef("user-2", "Will"), dev.nitrate.core.writing.UserRef("user-3", "Jessica"))
    override suspend fun partnerProgress(seriesId: String) =
        if (seriesId == "series-1") listOf(
            dev.nitrate.core.partners.PartnerProgress("g1", "user-2", "Will", emptySet(), "S1 E1", null),
            dev.nitrate.core.partners.PartnerProgress("g2", "user-4", "Frank", emptySet(), "S2 E3", null),
            dev.nitrate.core.partners.PartnerProgress("g2", "user-5", "Tommy", emptySet(), "S2 E1", null),
            dev.nitrate.core.partners.PartnerProgress("g2", "user-6", "Jane", emptySet(), null, null),
        ) else emptyList()
    /** A "Last time" recap of The Night Reel, three weeks after S1 E6. */
    suspend fun sampleRecap(): dev.nitrate.core.recap.Recap {
        val day = 24 * 60 * 60_000L
        val now = System.currentTimeMillis()
        val eps = allEpisodes("series-1").mapIndexed { i, e ->
            if (e.user.played) e.copy(runtimeMs = 45 * 60_000L, user = e.user.copy(lastPlayedMs = now - (27 - i) * day)) else e
        }
        val next = eps.first { !it.user.played }
        return dev.nitrate.core.recap.Recaps.forSeries(series, eps, next, dev.nitrate.core.pages.DisplayPrefs(recapAfterDays = 14), now)!!
    }
    override suspend fun sendDeviceSummary(summary: dev.nitrate.core.playback.DeviceSummary) = Unit
    override suspend fun forgetHouseholdDevice(userId: String, deviceId: String) = Unit
    override suspend fun householdDevices(): List<dev.nitrate.core.playback.HouseholdDevice> {
        val avatar = dev.nitrate.core.playback.SummaryTitle("m1", "Northern Lights (2021)", "Picture converted by the server", "Dolby Vision is tone-mapped to SDR by the server: the display doesn't report Dolby Vision")
        val reel = dev.nitrate.core.playback.SummaryTitle("m5", "Old Reel (1987)", "Picture converted by the server", "MPEG-4 video isn't something this device decodes")
        fun summary(name: String, model: String, hdr: List<String>, converted: List<dev.nitrate.core.playback.SummaryTitle>, tests: List<dev.nitrate.core.playback.SummaryTest> = emptyList(), seen: List<dev.nitrate.core.playback.SummaryLine> = emptyList()) =
            dev.nitrate.core.playback.DeviceSummary(name, model, "Nitrate 0.0.0-dev.54", 0, "All libraries",
                settings = listOf(dev.nitrate.core.playback.SummaryLine("Bitrate limit", "No limit"), dev.nitrate.core.playback.SummaryLine("Refresh rate", "Always")),
                displayHdr = hdr,
                counts = listOf(dev.nitrate.core.playback.SummaryLine("Everything", "240"), dev.nitrate.core.playback.SummaryLine("Plays as it is", "${240 - converted.size}"), dev.nitrate.core.playback.SummaryLine("Picture converted", "${converted.size}"), dev.nitrate.core.playback.SummaryLine("Sound converted", "0")),
                converted = converted, tests = tests, seen = seen)
        return listOf(
            dev.nitrate.core.playback.HouseholdDevice("u1", "joe", "tv-1", "Living room", System.currentTimeMillis() - 2 * 3_600_000, summary("Living room", "AFTKRT", emptyList(), listOf(avatar, reel),
                tests = listOf(dev.nitrate.core.playback.SummaryTest("m1", "Northern Lights (2021)", "1 thing not as predicted", false, listOf(dev.nitrate.core.playback.SummaryLine("Did the TV switch to HDR while it played?", "No")))),
                seen = listOf(dev.nitrate.core.playback.SummaryLine("HDR10 · 4K", "No"), dev.nitrate.core.playback.SummaryLine("Dolby TrueHD 5.1", "Yes (sent untouched)")))),
            dev.nitrate.core.playback.HouseholdDevice("u2", "sam", "phone-1", "Sam's phone", System.currentTimeMillis() - 26 * 3_600_000, summary("Sam's phone", "Pixel 9", listOf("HDR10", "HLG"), listOf(reel))),
            dev.nitrate.core.playback.HouseholdDevice("u2", "sam", "tv-2", "Bedroom TV", System.currentTimeMillis() - 5 * 60_000, summary("Bedroom TV", "Chromecast HD", listOf("HDR10"), listOf(avatar, reel))),
        )
    }

    override suspend fun checkClips() = listOf(
        dev.nitrate.core.playback.CheckClip("hdr10", "HDR10 · 4K", "Picture", "HDR10", "The TV shows HDR (or HDR10), or switches to its HDR picture mode."),
        dev.nitrate.core.playback.CheckClip("dolby_vision_81", "Dolby Vision 8.1 · 4K", "Picture", "DOVI", "A Dolby Vision TV shows Dolby Vision; other HDR TVs show HDR10."),
        dev.nitrate.core.playback.CheckClip("dolby_truehd_51", "Dolby TrueHD 5.1", "Sound", "truehd", "The receiver shows Dolby TrueHD, and each speaker plays when it's named."),
    )

    /** The Companion's features; tests add `notes` for group chat pinned to the timeline. */
    var companionFeatures = setOf(dev.nitrate.core.companion.CompanionInfo.CHAT, "scripts", "channels", "partners", "diary", "lines", "invitebatch")

    override suspend fun companion() = dev.nitrate.core.companion.CompanionInfo("0.1.0", companionFeatures)

    /** The Companion's own updates (feature `updates`, admins): a newer version waiting for a restart. */
    var companionUpdatesAnswer = dev.nitrate.core.companion.CompanionUpdates("0.1.0.71", "0.1.0.72", restartNeeded = true, playing = 0, lastCheckedAtMs = null, lastCheck = null, autoUpdate = true)
    var restartedForUpdate = false
    override suspend fun companionUpdates() = companionUpdatesAnswer
    override suspend fun checkCompanionUpdates() = companionUpdatesAnswer
    override suspend fun restartForCompanionUpdate() { restartedForUpdate = true }

    /** Group chat pinned along each title (feature `notes`): the group's, at points through the episode. */
    val notes = mutableListOf(
        dev.nitrate.core.companion.TimelineNote("n1", "user-2", "Will", "s1e2", 4_000, dev.nitrate.core.companion.ChatKind.TEXT, "The opening shot!", listOf("g1"), 1),
        dev.nitrate.core.companion.TimelineNote("n2", "user-4", "Frank", "s1e2", 6_000, dev.nitrate.core.companion.ChatKind.REACTION, "😱", listOf("g2"), 2),
        dev.nitrate.core.companion.TimelineNote("n3", "user-2", "Will", "s1e2", 1_500_000, dev.nitrate.core.companion.ChatKind.TEXT, "Called it.", listOf("g1"), 3),
        dev.nitrate.core.companion.TimelineNote("m1", "user-1", "nitrate", "s1e2", 900_000, dev.nitrate.core.companion.ChatKind.TEXT, "", listOf("g1"), 4, moment = true),
    )

    override suspend fun titleNotes(itemId: String): dev.nitrate.core.companion.TitleNotes? =
        if ("notes" !in companionFeatures) null
        else dev.nitrate.core.companion.TitleNotes(
            if (itemId.startsWith("s1e")) listOf(dev.nitrate.core.companion.NoteGroup("g1", "The Night Reel"), dev.nitrate.core.companion.NoteGroup("g2", "The Night Reel")) else emptyList(),
            notes.filter { it.itemId == itemId }.sortedBy { it.positionMs },
        )

    override suspend fun pinNote(itemId: String, positionMs: Long, kind: dev.nitrate.core.companion.ChatKind, text: String, sessionId: String?) =
        dev.nitrate.core.companion.TimelineNote("n${notes.size + 1}", "user-1", "nitrate", itemId, positionMs, kind, text, listOf("g1", "g2"), notes.size + 1L).also { notes += it }

    override suspend fun deleteNote(itemId: String, id: String) { notes.removeAll { it.id == id && it.userId == userId } }

    /** A few weeks of watching, for the diary. */
    val diaryEntries = mutableListOf(
        dev.nitrate.core.diary.DiaryEntry("d1", "m1", ItemKind.MOVIE, "Silver Screen", null, null, null, null, 2021, 7_200_000, listOf("Drama"), 1_793_000_000_000, 9, "The ending got me.", false),
        dev.nitrate.core.diary.DiaryEntry("d2", "s2e5", ItemKind.EPISODE, "Episode title 5", "series-1", "The Night Reel", 2, 5, 2024, 2_700_000, listOf("Mystery"), 1_792_700_000_000, 7, null, false),
        dev.nitrate.core.diary.DiaryEntry("d3", "s2e4", ItemKind.EPISODE, "Episode title 4", "series-1", "The Night Reel", 2, 4, 2024, 2_700_000, listOf("Mystery"), 1_792_600_000_000, null, null, false),
        dev.nitrate.core.diary.DiaryEntry("d4", "m1", ItemKind.MOVIE, "Silver Screen", null, null, null, null, 2021, 7_200_000, listOf("Drama"), 1_790_000_000_000, 8, null, false),
        dev.nitrate.core.diary.DiaryEntry("d5", "m3", ItemKind.MOVIE, "Gate Weave", null, null, null, null, 2024, 6_300_000, listOf("Science Fiction"), 1_789_500_000_000, 6, "Gorgeous, a little long.", true),
    )

    override suspend fun diary(fromMs: Long?, toMs: Long?) =
        diaryEntries.filter { (fromMs == null || it.watchedAtMs >= fromMs) && (toMs == null || it.watchedAtMs < toMs) }.sortedByDescending { it.watchedAtMs }

    override suspend fun searchLines(query: String, limit: Int) = listOf(
        dev.nitrate.core.lines.SpokenLine("m1", ItemKind.MOVIE, "Silver Screen", null, null, null, null, 2021, 5_415_000, 5_418_000, "We're going to need a bigger boat.", "eng", "Nobody crosses the river after dark.", "Says who?"),
        dev.nitrate.core.lines.SpokenLine("s2e5", ItemKind.EPISODE, "Episode title 5", "series-1", "The Night Reel", 2, 5, 2024, 1_262_000, 1_265_000, "If we need a bigger boat, we build one.", "eng", null, "Then start sawing."),
    ).filter { query.isNotBlank() }

    /** People to share scripts with, and one script someone shared (its text, at version 3). */
    val people = listOf(dev.nitrate.core.writing.UserRef("user-2", "sam"), dev.nitrate.core.writing.UserRef("user-3", "jo"), dev.nitrate.core.writing.UserRef("user-4", "alex"))
    val sharedText = "Title: Night Shift\n\nINT. DINER - NIGHT\n\nRain streaks the window. SAM (40s) nurses a coffee.\n\nSAM\nYou're late.\n"
    private val sharedInfo = dev.nitrate.core.writing.SharedScriptInfo("shared-1", "Night Shift", dev.nitrate.core.writing.UserRef("user-2", "sam"), listOf(dev.nitrate.core.writing.UserRef("user-1", "joe")), 3, 0)

    override suspend fun sharingUsers(name: String?) = if (name == null) people else people.filter { it.name == name }
    override suspend fun sharedScripts() = listOf(sharedInfo)
    override suspend fun sharedScript(id: String) = dev.nitrate.core.writing.SharedScriptDoc(sharedInfo, sharedText, 3)
    override suspend fun sendScriptEdit(id: String, editId: String, baseVersion: Long, op: dev.nitrate.core.writing.TextOperation, cursor: Int?) =
        dev.nitrate.core.writing.ScriptEditResult(true, baseVersion + 1)
    override suspend fun sendScriptPresence(id: String, cursor: Int, version: Long) = Unit

    /** Sam is in the script, at the start of "You're late."; later requests wait (as the long poll would). */
    override suspend fun scriptEdits(id: String, after: Long, seen: Long?, waitMs: Int): dev.nitrate.core.writing.ScriptEditsPage {
        if (seen != null && waitMs > 0) kotlinx.coroutines.awaitCancellation()
        return dev.nitrate.core.writing.ScriptEditsPage(emptyList(), 3, listOf(dev.nitrate.core.writing.ScriptPresence("user-2", "sam", sharedText.indexOf("You're"), 3)), 1)
    }

    override suspend fun chatMessages(groupId: String, after: Long, waitMs: Int): dev.nitrate.core.companion.ChatPage {
        val page = chat.filter { it.seq > after }
        if (page.isEmpty() && waitMs > 0) kotlinx.coroutines.awaitCancellation()
        return dev.nitrate.core.companion.ChatPage(if (after < 0) chat.toList() else page, chat.lastOrNull()?.seq ?: 0)
    }

    override suspend fun sendChat(groupId: String, kind: dev.nitrate.core.companion.ChatKind, text: String, positionMs: Long?) =
        dev.nitrate.core.companion.ChatMessage((chat.lastOrNull()?.seq ?: 0) + 1, "user-1", "nitrate", kind, text, positionMs, 0).also { chat += it }

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
    override suspend fun serverSettings() = dev.nitrate.core.account.ServerSettings(
        listOf(
            dev.nitrate.core.account.ServerSettingsGroup(
                "Languages and subtitles", "Jellyfin → your profile → Playback and Subtitles",
                listOf(
                    dev.nitrate.core.account.ServerSetting("Preferred audio language", "Japanese (jpn)", dev.nitrate.core.account.SettingReach.APPLIES, "The audio a title starts with when you haven't picked one for that show or film."),
                    dev.nitrate.core.account.ServerSetting("Subtitle mode", "Smart", dev.nitrate.core.account.SettingReach.APPLIES, "Subtitles in your preferred language start when the audio isn't in your preferred audio language."),
                    dev.nitrate.core.account.ServerSetting("Play the next episode automatically", "On", dev.nitrate.core.account.SettingReach.JELLYFIN_APPS, "Nitrate's Up Next card works either way."),
                ),
            ),
            dev.nitrate.core.account.ServerSettingsGroup(
                "Playback permissions", "Jellyfin dashboard → Users → you → Profile (your server's admin)",
                listOf(dev.nitrate.core.account.ServerSetting("Bitrate limit away from home", "8 Mbps", dev.nitrate.core.account.SettingReach.APPLIES, "On top of this device's own limit in Playback settings.")),
            ),
        ),
    )

    /** A typical file: English 5.1 and Japanese audio, full and signs-only ASS subtitles; the server picks English, subtitles off. */
    override suspend fun mediaSource(itemId: String) = dev.nitrate.core.model.MediaSource(
        "src-$itemId", "mkv", 12_000_000,
        listOf(
            dev.nitrate.core.model.MediaStream(0, dev.nitrate.core.model.StreamKind.VIDEO, codec = "h264", width = 1920, height = 1080, bitDepth = 8, rangeType = "SDR"),
            dev.nitrate.core.model.MediaStream(1, dev.nitrate.core.model.StreamKind.AUDIO, codec = "eac3", language = "eng", title = "English", channels = 6, channelLayout = "5.1", isDefault = true),
            dev.nitrate.core.model.MediaStream(2, dev.nitrate.core.model.StreamKind.AUDIO, codec = "aac", language = "jpn", title = "Japanese", channels = 2, channelLayout = "stereo"),
            dev.nitrate.core.model.MediaStream(3, dev.nitrate.core.model.StreamKind.SUBTITLE, codec = "ass", language = "eng", title = "Full"),
            dev.nitrate.core.model.MediaStream(4, dev.nitrate.core.model.StreamKind.SUBTITLE, codec = "ass", language = "eng", title = "Signs & Songs", isForced = true),
        ),
        size = 3_200_000_000, defaultAudioIndex = 1, defaultSubtitleIndex = -1, defaultsKnown = true,
    )

    override suspend fun resolvePlayback(itemId: String, isAudio: Boolean, startMs: Long, forceTranscode: Boolean, audioIndex: Int?, subtitleIndex: Int?, mediaSourceId: String?, deviceCaps: dev.nitrate.core.playback.DeviceCapabilities?): PlaybackPlan =
        error("not used in screenshots")
    override suspend fun reportStart(plan: PlaybackPlan, positionMs: Long) = Unit
    override suspend fun reportProgress(plan: PlaybackPlan, positionMs: Long, isPaused: Boolean) = Unit
    override fun reportStopped(plan: PlaybackPlan, positionMs: Long) = Unit
    override suspend fun channelTestClips() = emptyList<ChannelClip>()
}
