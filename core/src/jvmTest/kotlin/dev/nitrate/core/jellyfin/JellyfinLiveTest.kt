package dev.nitrate.core.jellyfin

import dev.nitrate.core.downloads.DownloadManager
import dev.nitrate.core.downloads.DownloadQuality
import dev.nitrate.core.downloads.MemoryDownloadStore
import dev.nitrate.core.downloads.QueueResult
import dev.nitrate.core.model.ImageKind
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.PlayMethod
import dev.nitrate.core.model.SessionState
import dev.nitrate.core.playback.PlaybackSettings
import dev.nitrate.core.playback.PlaybackSettingsRepository
import dev.nitrate.core.playback.caps
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs [JellyfinClient] against a real server. Skipped unless JELLYFIN_URL is set:
 *
 *     JELLYFIN_URL=http://127.0.0.1:8096 JELLYFIN_USER=nitrate JELLYFIN_PASSWORD=... ./gradlew :core:jvmTest --tests '*LiveTest*'
 *
 * Needs at least one movie or episode. Only changes state it restores (a favourite toggle) and a
 * scratch display-preferences entry.
 */
class JellyfinLiveTest {
    private val url = System.getenv("JELLYFIN_URL")

    private val settings = object : PlaybackSettingsRepository {
        override val settings = MutableStateFlow(PlaybackSettings())
        override suspend fun current() = settings.value
        override suspend fun update(transform: (PlaybackSettings) -> PlaybackSettings) {
            settings.value = transform(settings.value)
        }
    }

    private val storage = object : SessionStorage {
        var session: StoredSession? = null
        override suspend fun load() = session
        override suspend fun save(session: StoredSession) {
            this.session = session
        }
        override suspend fun clear() {
            session = null
        }
    }

    @Test
    fun `client works against a live server`() = runBlocking {
        assumeTrue("Set JELLYFIN_URL to run", url != null)
        val client = JellyfinClient(OkHttp.create(), ClientIdentity("Nitrate", "test", "nitrate-live-test", "Live test"), storage, { caps() }, settings)
        val raw = HttpClient(OkHttp)

        val address = client.findServer(url!!.removePrefix("http://").removePrefix("https://"))
        println("Found ${address.name} at ${address.address}")
        client.login(url, System.getenv("JELLYFIN_USER") ?: "nitrate", System.getenv("JELLYFIN_PASSWORD").orEmpty())
        assertEquals(SessionState.VALID, client.checkSession())

        // A second client restores the saved session.
        val restored = JellyfinClient(OkHttp.create(), ClientIdentity("Nitrate", "test", "nitrate-live-test", "Live test"), storage, { caps() }, settings)
        assertEquals(SessionState.VALID, restored.restoreSession())

        val views = client.views()
        assertTrue("No libraries", views.isNotEmpty())
        val rows = client.homeRows()
        println("Home rows: ${rows.map { "${it.key} (${it.items.size})" }}")
        for (view in views) println("${view.name}: ${client.libraryRows(view).map { it.key }}, ${client.libraryPage(view, dev.nitrate.core.LibrarySort.NAME, 0, 20, true).second} items")

        val all = views.flatMap { client.libraryPage(it, dev.nitrate.core.LibrarySort.NAME, 0, 50, false).first }
        all.firstOrNull { it.kind == ItemKind.SERIES }?.let { series ->
            val seasons = client.seasons(series.id)
            val episodes = client.allEpisodes(series.id)
            println("${series.name}: ${seasons.map { it.name }}, episodes ${episodes.map { "S${it.seasonNumber}E${it.indexNumber}" }}")
            assertTrue(episodes.isNotEmpty())
            assertEquals(seasons.first().id, client.episodes(series.id, seasons.first().id).first().seasonId)
            client.nextUpFor(series.id)
        }

        val playable = all.firstOrNull { it.kind == ItemKind.MOVIE }
            ?: all.firstOrNull { it.kind == ItemKind.SERIES }?.let { client.allEpisodes(it.id).first() }
        assertNotNull("No movie or episode to play", playable)
        val item = client.getItem(playable!!.id)
        assertEquals(playable.id, item.id)
        client.similar(item.id)

        val found = client.search("test")
        println("Search: ${found.movies.map { it.name }} ${found.shows.map { it.name }} ${found.episodes.size} episodes, ${found.people.size} people")
        assertTrue(!found.isEmpty)
        println("Segments: ${client.mediaSegments(item.id)}")
        client.trickplay(item.id, null)?.let { info ->
            println("Trickplay: $info")
            val sheet = raw.get(client.trickplaySheetUrl(item.id, info, 0)) { header("Authorization", client.authorizationHeader()) }
            assertEquals(200, sheet.status.value)
            assertEquals("image/jpeg", sheet.headers["Content-Type"])
        }

        client.imageUrl(item, ImageKind.PRIMARY, 300)?.let { assertEquals(200, raw.get(it).status.value) }

        val direct = client.resolvePlayback(item.id, isAudio = false, startMs = 0, forceTranscode = false, audioIndex = null, subtitleIndex = null, mediaSourceId = null)
        println("Direct: ${direct.method} ${direct.url}")
        assertEquals(PlayMethod.DIRECT_PLAY, direct.method)
        assertEquals(200, raw.get(direct.url) { header("Range", "bytes=0-1023") }.status.value.let { if (it == 206) 200 else it })
        direct.externalSubtitles.forEach { assertTrue(raw.get(it.url).bodyAsText().isNotBlank()) }

        val transcode = client.resolvePlayback(item.id, isAudio = false, startMs = 2000, forceTranscode = true, audioIndex = null, subtitleIndex = null, mediaSourceId = null)
        println("Transcode: ${transcode.method} hls=${transcode.isHls} reasons=${transcode.transcodeReasons}")
        assertEquals(PlayMethod.TRANSCODE, transcode.method)
        assertTrue(transcode.isHls)
        assertTrue(raw.get(transcode.url) { header("Authorization", client.authorizationHeader()) }.bodyAsText().startsWith("#EXTM3U"))

        client.reportStart(direct, 0)
        client.reportProgress(direct, 5_000, isPaused = false)
        client.reportStopped(direct, 6_000)

        val wasFavorite = item.user.isFavorite
        client.setFavorite(item.id, !wasFavorite)
        assertEquals(!wasFavorite, client.getItem(item.id).user.isFavorite)
        client.setFavorite(item.id, wasFavorite)

        client.saveCustomPrefs(mapOf("live-test" to "1"), "nitrate-live-test")
        assertEquals("1", client.loadCustomPrefs("nitrate-live-test")["live-test"])

        client.logout()
        assertEquals(null, storage.session)
    }

    @Test
    fun `downloads work against a live server`() = runBlocking {
        assumeTrue("Set JELLYFIN_URL to run", url != null)
        val client = JellyfinClient(OkHttp.create(), ClientIdentity("Nitrate", "test", "nitrate-live-test", "Live test"), storage, { caps() }, settings)
        client.login(url!!, System.getenv("JELLYFIN_USER") ?: "nitrate", System.getenv("JELLYFIN_PASSWORD").orEmpty())
        val root = java.nio.file.Files.createTempDirectory("nitrate-live-dl").toFile()
        val manager = DownloadManager(client, MemoryDownloadStore(), OkHttp.create(), kotlinx.io.files.Path(root.path), object : dev.nitrate.core.downloads.DownloadScheduler {
            override fun schedule(wifiOnly: Boolean) {}
        })

        val all = client.views().flatMap { client.libraryPage(it, dev.nitrate.core.LibrarySort.NAME, 0, 50, false).first }
        val movie = all.first { it.kind == ItemKind.MOVIE }
        val episode = all.first { it.kind == ItemKind.SERIES }.let { client.allEpisodes(it.id).first { e -> (e.seasonNumber ?: 0) > 0 } }
        manager.enqueue(listOf(movie), DownloadQuality.ORIGINAL, hevc = false)
        manager.enqueue(listOf(episode), DownloadQuality.LOW, hevc = false)
        assertEquals(QueueResult.DONE, manager.runQueue())

        val records = manager.downloads.first()
        for (r in records) {
            val file = java.io.File(manager.videoPath(r) ?: error("${r.item.name}: ${r.state} ${r.error}"))
            println("${r.item.name} (${r.quality}): ${file.length()} bytes, subtitles ${r.subtitles.map { it.file }}, source ${r.source?.streams?.map { "${it.kind}:${it.codec}" }}")
            assertTrue(file.length() > 10_000)
            assertNotNull(r.posterFile)
        }
        val original = records.first { it.itemId == movie.id }
        // The original is byte-for-byte the server's file size.
        assertEquals(client.mediaSource(movie.id).size, original.totalBytes)
        assertEquals(1, original.subtitles.size)

        // Offline progress reaches the server.
        val runtime = episode.runtimeMs ?: 20_000
        manager.recordPlayback(episode.id, runtime / 2, runtime, ended = false)
        assertEquals(1, manager.syncWatchState())
        assertEquals(runtime / 2 / 1000, client.userData(episode.id).positionMs / 1000)
        client.saveUserData(episode.id, 0, false, System.currentTimeMillis())
        root.deleteRecursively()
        Unit
    }

    @Test
    fun `administration works against a live server`() = runBlocking {
        assumeTrue("Set JELLYFIN_URL to run", url != null)
        val client = JellyfinClient(OkHttp.create(), ClientIdentity("Nitrate", "test", "nitrate-live-test", "Live test"), storage, { caps() }, settings)
        client.login(url!!, System.getenv("JELLYFIN_USER") ?: "nitrate", System.getenv("JELLYFIN_PASSWORD").orEmpty())
        assertTrue("The live test account must be an admin", client.account().isAdmin)

        val movie = client.views().flatMap { client.libraryPage(it, dev.nitrate.core.LibrarySort.NAME, 0, 50, false).first }.first { it.kind == ItemKind.MOVIE }
        val before = client.metadata(movie.id)
        println("Metadata: ${before.name} ${before.productionYear} genres=${before.genres} providerIds=${before.providerIds}")
        client.saveMetadata(before.copy(tagline = "Live test tagline", genres = before.genres + "Nitrate Test", lockedFields = listOf("Genres")))
        val after = client.metadata(movie.id)
        assertEquals("Live test tagline", after.tagline)
        assertTrue("Nitrate Test" in after.genres)
        assertEquals(listOf("Genres"), after.lockedFields)
        assertEquals("Live test tagline", client.getItem(movie.id).tagline)
        client.saveMetadata(before)
        assertEquals(before.genres, client.metadata(movie.id).genres)

        println("Images: ${client.itemImages(movie.id).map { "${it.type} ${it.width}x${it.height}" }}")
        println("Remote posters: ${runCatching { client.remoteImages(movie.id, dev.nitrate.core.admin.ImageType.PRIMARY).size }.getOrElse { "failed: ${it.message}" }}")
        println("Identify: ${runCatching { client.remoteSearch(movie, before.name, before.productionYear).map { "${it.name} (${it.year}) ${it.providerName}" } }.getOrElse { "failed: ${it.message}" }}")
        client.refreshItem(movie.id, dev.nitrate.core.admin.RefreshMode.NEW_FILES, replaceImages = false, regenerateTrickplay = false)

        val tasks = client.tasks()
        println("Tasks: ${tasks.size}, e.g. ${tasks.take(4).map { "${it.name} (${it.state}, last ${it.lastResult})" }}")
        val clean = tasks.first { it.name.contains("Clean", ignoreCase = true) || it.name.contains("Log", ignoreCase = true) }
        client.startTask(clean.id)
        client.stopTask(clean.id)
        println("Activity: ${client.activity(5).map { it.name }}")
        val sessions = client.sessions()
        println("Sessions: ${sessions.map { "${it.userName} on ${it.client} / ${it.deviceName}" }}")
        assertTrue(sessions.any { it.deviceId == "nitrate-live-test" })

        val temp = client.createUser("nitrate-temp-user", "pw-123")
        try {
            client.setPermissions(temp.id, temp.permissions.copy(enableContentDownloading = false, enableRemoteAccess = false))
            val reread = client.users().first { it.id == temp.id }
            assertEquals(false, reread.permissions.enableContentDownloading)
            assertEquals(false, reread.permissions.enableRemoteAccess)
            client.setPassword(temp.id, "pw-456")
        } finally {
            client.deleteUser(temp.id)
        }
        assertTrue(client.users().none { it.id == temp.id })
    }

    @Test
    fun `live events reach a controllable client`() = runBlocking {
        assumeTrue("Set JELLYFIN_URL to run", url != null)
        val user = System.getenv("JELLYFIN_USER") ?: "nitrate"
        val pw = System.getenv("JELLYFIN_PASSWORD").orEmpty()
        val tv = JellyfinClient(OkHttp.create(), ClientIdentity("Nitrate", "test", "nitrate-live-tv", "Live TV"), storage, { caps() }, settings)
        tv.login(url!!, user, pw)
        val phone = JellyfinClient(OkHttp.create(), ClientIdentity("Nitrate", "test", "nitrate-live-phone", "Live phone"), object : SessionStorage {
            override suspend fun load(): StoredSession? = null
            override suspend fun save(session: StoredSession) {}
            override suspend fun clear() {}
        }, { caps() }, settings)
        phone.login(url, user, pw)

        val received = java.util.concurrent.CopyOnWriteArrayList<dev.nitrate.core.events.ServerEvent>()
        val listening = launch { tv.events().collect { received += it } }
        kotlinx.coroutines.withTimeout(10_000) { while (received.none { it is dev.nitrate.core.events.ServerEvent.Connected }) kotlinx.coroutines.delay(100) }
        tv.reportCapabilities()

        val target = phone.sessions().first { it.deviceId == "nitrate-live-tv" }
        assertTrue("The TV must look controllable", target.supportsRemoteControl)
        phone.sessionCommand(target.id, dev.nitrate.core.admin.SessionCommand.PAUSE)
        phone.sendMessage(target.id, "Hello", "From the phone")
        kotlinx.coroutines.withTimeout(10_000) {
            while (received.none { it is dev.nitrate.core.events.ServerEvent.General && it.name == "DisplayMessage" }) kotlinx.coroutines.delay(100)
        }
        println("Events: $received")
        assertTrue(received.any { it == dev.nitrate.core.events.ServerEvent.Playstate("Pause", null) })
        // Watch state changed elsewhere reaches this device (pages reload on it).
        val someItem = phone.views().flatMap { phone.libraryPage(it, dev.nitrate.core.LibrarySort.NAME, 0, 5, false).first }.first { it.kind == ItemKind.MOVIE || it.kind == ItemKind.EPISODE }
        phone.setFavorite(someItem.id, true)
        phone.setFavorite(someItem.id, false)
        kotlinx.coroutines.withTimeout(15_000) {
            while (received.none { it is dev.nitrate.core.events.ServerEvent.UserDataChanged && someItem.id in it.itemIds }) kotlinx.coroutines.delay(100)
        }
        println("Subtitles: ${runCatching { tv.searchSubtitles(phone.views().first().id, "eng").size }.getOrElse { "search failed (expected without providers): ${it.message}" }}")
        listening.cancel()
    }

    /** A player that just keeps time, for SyncPlay. */
    private class ClockPlayer : dev.nitrate.core.syncplay.SyncPlayer {
        @Volatile override var itemId: String? = null
        @Volatile private var base = 0L
        @Volatile private var since = 0L
        @Volatile override var isPlaying = false
        @Volatile override var isReady = false
        val log = java.util.concurrent.CopyOnWriteArrayList<String>()
        override val positionMs get() = if (isPlaying) base + (System.currentTimeMillis() - since) else base
        override fun load(itemId: String, startMs: Long) { this.itemId = itemId; base = startMs; isPlaying = false; isReady = true; log += "load $startMs" }
        override fun play() { base = positionMs; since = System.currentTimeMillis(); isPlaying = true; log += "play" }
        override fun pause() { base = positionMs; isPlaying = false; log += "pause" }
        override fun seek(positionMs: Long) { base = positionMs; since = System.currentTimeMillis(); log += "seek $positionMs" }
        override fun setRate(rate: Float) {}
        override fun stop() { log += "stop" }
    }

    @Test
    fun `two devices watch together`() = runBlocking {
        assumeTrue("Set JELLYFIN_URL to run", url != null)
        val user = System.getenv("JELLYFIN_USER") ?: "nitrate"
        val pw = System.getenv("JELLYFIN_PASSWORD").orEmpty()
        fun device(id: String) = JellyfinClient(OkHttp.create(), ClientIdentity("Nitrate", "test", id, id), object : SessionStorage {
            override suspend fun load(): StoredSession? = null
            override suspend fun save(session: StoredSession) {}
            override suspend fun clear() {}
        }, { caps() }, settings)
        val a = device("nitrate-sync-a").also { it.login(url!!, user, pw) }
        val b = device("nitrate-sync-b").also { it.login(url!!, user, pw) }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
        val ca = dev.nitrate.core.syncplay.SyncPlayController(a, scope)
        val cb = dev.nitrate.core.syncplay.SyncPlayController(b, scope)
        val log = java.util.concurrent.CopyOnWriteArrayList<String>()
        for ((name, client, c) in listOf(Triple("A", a, ca), Triple("B", b, cb))) {
            scope.launch {
                client.events().collect {
                    if (it is dev.nitrate.core.events.ServerEvent.SyncPlayCommand || it is dev.nitrate.core.events.ServerEvent.SyncPlayGroupUpdate) log += "$name $it"
                    c.handle(it)
                }
            }
            scope.launch { c.notices.collect { log += "$name notice: $it" } }
        }
        delay(1_500)
        suspend fun waitFor(what: String, check: () -> Boolean) =
            kotlinx.coroutines.withTimeoutOrNull(15_000) { while (!check()) delay(100) } ?: error("Timed out waiting for $what\n${log.joinToString("\n")}")

        ca.create("Nitrate live test")
        waitFor("A in a group") { ca.group.value != null }
        val group = cb.groups().first { it.id == ca.group.value!!.id }
        cb.join(group.id)
        waitFor("B in the group") { cb.group.value != null }
        println("Clock offsets: A ${ca.clockOffsetMs} ms, B ${cb.clockOffsetMs} ms")

        val movie = a.views().flatMap { a.libraryPage(it, dev.nitrate.core.LibrarySort.NAME, 0, 50, false).first }.first { it.kind == ItemKind.MOVIE || it.kind == ItemKind.EPISODE }
        val pa = ClockPlayer()
        val pb = ClockPlayer()
        val opened = scope.launch { cb.openRequests.collect { q -> cb.attach(pb, q.currentItemId!!, q.startMs) } }
        ca.attach(pa, movie.id, 5_000)
        waitFor("both loaded") { pa.itemId == movie.id && pb.itemId == movie.id }
        // Loads are instant here, so both say ready and the group starts.
        waitFor("both playing") { pa.isPlaying && pb.isPlaying }
        delay(1_000)
        println("Positions: A ${pa.positionMs}, B ${pb.positionMs}")
        assertTrue("In step", kotlin.math.abs(pa.positionMs - pb.positionMs) < 300)

        cb.requestPause()
        waitFor("both paused") { !pa.isPlaying && !pb.isPlaying }
        cb.requestSeek(15_000)
        waitFor("both at 0:15") { pa.positionMs in 14_500..15_500 && pb.positionMs in 14_500..15_500 }
        ca.requestPlay()
        waitFor("playing again") { pa.isPlaying && pb.isPlaying }

        cb.leave()
        ca.leave()
        println(log.joinToString("\n"))
        println("A: ${pa.log}\nB: ${pb.log}")
        opened.cancel()
        scope.cancel()
    }

    @Test
    fun `a phone approves a TV's Quick Connect code`() = runBlocking {
        assumeTrue("Set JELLYFIN_URL to run", url != null)
        val user = System.getenv("JELLYFIN_USER") ?: "nitrate"
        val pw = System.getenv("JELLYFIN_PASSWORD").orEmpty()
        fun device(id: String) = JellyfinClient(OkHttp.create(), ClientIdentity("Nitrate", "test", id, id), object : SessionStorage {
            override suspend fun load(): StoredSession? = null
            override suspend fun save(session: StoredSession) {}
            override suspend fun clear() {}
        }, { caps() }, settings)
        val tv = device("nitrate-qc-tv")
        assumeTrue("Quick Connect is off on this server", tv.quickConnectEnabled(url!!))
        val request = tv.startQuickConnect(url)
        assertTrue(!tv.quickConnectApproved(request.secret))
        val phone = device("nitrate-qc-phone").also { it.login(url, user, pw) }
        phone.authorizeQuickConnect(request.code)
        assertTrue(tv.quickConnectApproved(request.secret))
        tv.finishQuickConnect(url, request.secret)
        assertEquals(phone.userId, tv.userId)
        val wrong = runCatching { phone.authorizeQuickConnect("000000") }
        println("Wrong code: ${wrong.exceptionOrNull()?.message}")
        assertTrue(wrong.isFailure)
    }
}
