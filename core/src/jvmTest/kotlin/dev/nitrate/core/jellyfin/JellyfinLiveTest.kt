package dev.nitrate.core.jellyfin

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
}
