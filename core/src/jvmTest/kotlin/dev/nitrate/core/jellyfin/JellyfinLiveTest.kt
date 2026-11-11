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
import kotlinx.coroutines.runBlocking
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
}
