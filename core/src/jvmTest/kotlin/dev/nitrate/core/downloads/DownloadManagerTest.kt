package dev.nitrate.core.downloads

import dev.nitrate.core.jellyfin.ClientIdentity
import dev.nitrate.core.jellyfin.JellyfinClient
import dev.nitrate.core.jellyfin.SessionStorage
import dev.nitrate.core.jellyfin.StoredSession
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.MediaSource
import dev.nitrate.core.model.MediaStream
import dev.nitrate.core.model.StreamKind
import dev.nitrate.core.model.SubtitleDelivery
import dev.nitrate.core.playback.PlaybackSettings
import dev.nitrate.core.playback.PlaybackSettingsRepository
import dev.nitrate.core.playback.caps
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class DownloadManagerTest {
    private val root = Files.createTempDirectory("nitrate-downloads").toFile()
    private val video = ByteArray(10_000) { (it % 251).toByte() }
    private val transcoded = ByteArray(3_000) { (it % 7).toByte() }
    private val requests = mutableListOf<HttpRequestData>()
    private val posted = mutableListOf<String>()

    /** Swaps in a failure for download requests. */
    private var failDownloads: (MockRequestHandleScope.() -> HttpResponseData)? = null
    private var remoteUserData = """{"Played":false,"PlaybackPositionTicks":0}"""

    private val engine = MockEngine { request ->
        requests += request
        val path = request.url.encodedPath
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        when {
            path.endsWith("/Users/AuthenticateByName") -> respond("""{"User":{"Id":"$USER","Name":"joe"},"AccessToken":"tok"}""", headers = json)
            path.endsWith("/Download") || path.contains("/stream.mp4") -> failDownloads?.invoke(this) ?: if (path.endsWith("/Download")) {
                val from = request.headers[HttpHeaders.Range]?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
                if (from != null) {
                    respond(ByteReadChannel(video.copyOfRange(from, video.size)), HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentLength, (video.size - from).toString()))
                } else {
                    respond(ByteReadChannel(video), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, video.size.toString()))
                }
            } else {
                respond(ByteReadChannel(transcoded), HttpStatusCode.OK)
            }
            path.contains("/Subtitles/") -> respond("1\n00:00:01,000 --> 00:00:02,000\nHi\n")
            path.contains("/Images/") -> respond(ByteReadChannel(byteArrayOf(1, 2, 3)))
            path.endsWith("/UserData") && request.method.value == "POST" -> {
                posted += (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                respond("{}", headers = json)
            }
            path.endsWith("/UserData") -> respond(remoteUserData, headers = json)
            path.contains("/Items/") -> respond(ITEM, headers = json)
            else -> respond("", HttpStatusCode.NotFound)
        }
    }

    private val settings = object : PlaybackSettingsRepository {
        override val settings = MutableStateFlow(PlaybackSettings())
        override suspend fun current() = settings.value
        override suspend fun update(transform: (PlaybackSettings) -> PlaybackSettings) {}
    }

    private val sessions = object : SessionStorage {
        override suspend fun load(): StoredSession? = null
        override suspend fun save(session: StoredSession) {}
        override suspend fun clear() {}
    }

    private val server = JellyfinClient(engine, ClientIdentity("Nitrate", "1", "dev", "Test"), sessions, { caps() }, settings)
    private val store = MemoryDownloadStore()
    private var scheduled = 0
    private var clock = 1_000_000L
    private val manager = DownloadManager(server, store, engine, Path(root.path), { scheduled++ }, now = { clock })

    private val movie = MediaItem(id = ITEM_ID, name = "Movie", kind = ItemKind.MOVIE, runtimeMs = 60_000)

    private fun signIn() = runBlocking { server.login("http://jf", "joe", "pw") }

    private fun record() = runBlocking { manager.downloads.first().single() }

    @Test
    fun `original download keeps embedded subtitles and saves external ones beside it`() = runBlocking {
        signIn()
        assertEquals(1, manager.enqueue(listOf(movie), DownloadQuality.ORIGINAL, hevc = false))
        assertEquals(0, manager.enqueue(listOf(movie), DownloadQuality.ORIGINAL, hevc = false))
        assertEquals(1, scheduled)
        assertEquals(QueueResult.DONE, manager.runQueue())

        val r = record()
        assertEquals(DownloadState.COMPLETED, r.state)
        assertEquals(video.size.toLong(), r.totalBytes)
        val path = manager.videoPath(r)!!
        assertTrue(path.endsWith("video.mkv"))
        assertTrue(File(path).readBytes().contentEquals(video))
        assertTrue(requests.any { it.url.encodedPath.endsWith("/Items/$ITEM_ID/Download") })

        // Only the external text subtitle is fetched; embedded ones are in the file.
        assertEquals(listOf(2), r.subtitles.map { it.streamIndex })
        val plan = manager.playbackPlan(r)!!
        assertEquals(path, plan.url)
        assertEquals(1, plan.externalSubtitles.size)
        assertTrue(File(plan.externalSubtitles.single().url).readText().contains("Hi"))
        val subs = r.source!!.streams.filter { it.kind == StreamKind.SUBTITLE }
        assertEquals(listOf(2 to SubtitleDelivery.EXTERNAL, 3 to SubtitleDelivery.EMBED, 4 to SubtitleDelivery.EMBED), subs.map { it.index to it.delivery }.sortedBy { it.first })
        assertNotNull(manager.imagePath(r, poster = true))
    }

    @Test
    fun `an interrupted original resumes with a range request`() = runBlocking {
        signIn()
        manager.enqueue(listOf(movie), DownloadQuality.ORIGINAL, hevc = false)
        var calls = 0
        failDownloads = { if (calls++ == 0) throw IOException("connection reset") else error("unused") }
        assertEquals(QueueResult.RETRY, manager.runQueue())
        assertEquals(DownloadState.QUEUED, record().state)
        assertEquals("Waiting for connection", record().error)

        // Half the file made it to disk before the connection dropped.
        File(File(root, USER_DASHED), ITEM_ID).also { it.mkdirs() }.resolve("video.mkv").writeBytes(video.copyOf(4_000))
        failDownloads = null
        assertEquals(QueueResult.DONE, manager.runQueue())
        assertEquals("bytes=4000-", requests.last { it.url.encodedPath.endsWith("/Download") }.headers[HttpHeaders.Range])
        assertTrue(File(manager.videoPath(record())!!).readBytes().contentEquals(video))
    }

    @Test
    fun `transcoded download asks the server for a small MP4 and saves every text subtitle`() = runBlocking {
        signIn()
        manager.enqueue(listOf(movie), DownloadQuality.LOW, hevc = true)
        manager.runQueue()
        val r = record()
        assertEquals(DownloadState.COMPLETED, r.state)
        val url = requests.single { it.url.encodedPath.contains("/stream.mp4") }.url
        assertEquals("hevc", url.parameters["videoCodec"])
        assertEquals("aac", url.parameters["audioCodec"])
        assertEquals("900000", url.parameters["videoBitRate"])
        assertEquals("854", url.parameters["maxWidth"])
        assertEquals("false", url.parameters["static"])
        assertEquals(32, url.parameters["playSessionId"]!!.length)
        assertTrue(File(manager.videoPath(r)!!).readBytes().contentEquals(transcoded))
        assertTrue(manager.videoPath(r)!!.endsWith(".mp4"))

        // Embedded text subtitles are lost in a transcode, so both text ones are saved; PGS can't be.
        assertEquals(listOf(2, 3), r.subtitles.map { it.streamIndex }.sorted())
        val source = r.source!!
        assertEquals("mp4", source.container)
        assertEquals(listOf("hevc", "aac"), source.streams.filter { it.kind != StreamKind.SUBTITLE }.map { it.codec })
        // What the server's scaler produces for 1920x1080 into 854x480.
        assertEquals(852 to 480, source.streams.first { it.kind == StreamKind.VIDEO }.let { it.width to it.height })
        assertTrue(source.streams.filter { it.kind == StreamKind.SUBTITLE }.all { it.delivery == SubtitleDelivery.EXTERNAL })
    }

    @Test
    fun `server refusal fails the download with a reason`() = runBlocking {
        signIn()
        manager.enqueue(listOf(movie), DownloadQuality.ORIGINAL, hevc = false)
        failDownloads = { respond("", HttpStatusCode.Forbidden) }
        assertEquals(QueueResult.DONE, manager.runQueue())
        assertEquals(DownloadState.FAILED, record().state)
        assertEquals("Your account isn't allowed to download from this server", record().error)
        failDownloads = null
        manager.resume(ITEM_ID)
        manager.runQueue()
        assertEquals(DownloadState.COMPLETED, record().state)
    }

    @Test
    fun `remove deletes the files`() = runBlocking {
        signIn()
        manager.enqueue(listOf(movie), DownloadQuality.ORIGINAL, hevc = false)
        manager.runQueue()
        val path = manager.videoPath(record())!!
        manager.remove(ITEM_ID)
        assertFalse(File(path).exists())
        assertTrue(manager.downloads.first().isEmpty())
    }

    @Test
    fun `offline watch state is sent when this device played it last`() = runBlocking {
        signIn()
        manager.enqueue(listOf(movie), DownloadQuality.ORIGINAL, hevc = false)
        manager.runQueue()

        clock = 2_000_000L
        manager.recordPlayback(ITEM_ID, positionMs = 30_000, durationMs = 60_000, ended = false)
        assertTrue(record().pendingSync)
        assertEquals(30_000, record().item.user.positionMs)
        assertEquals(30_000, manager.resumePositionMs(record()))

        remoteUserData = """{"Played":false,"PlaybackPositionTicks":0,"LastPlayedDate":"1970-01-01T00:16:40Z"}""" // 1,000,000 ms: older
        assertEquals(1, manager.syncWatchState())
        val body = Json.parseToJsonElement(posted.single()).jsonObject
        assertEquals(300_000_000L, body["PlaybackPositionTicks"]!!.jsonPrimitive.content.toLong())
        assertEquals("false", body["Played"]!!.jsonPrimitive.content)
        assertEquals("1970-01-01T00:33:20Z", body["LastPlayedDate"]!!.jsonPrimitive.content)
        assertFalse(record().pendingSync)
    }

    @Test
    fun `watching elsewhere more recently wins over offline progress`() = runBlocking {
        signIn()
        manager.enqueue(listOf(movie), DownloadQuality.ORIGINAL, hevc = false)
        manager.runQueue()
        manager.recordPlayback(ITEM_ID, positionMs = 59_000, durationMs = 60_000, ended = false)
        assertTrue(record().item.user.played)
        assertEquals(0, record().item.user.positionMs)

        remoteUserData = """{"Played":false,"PlaybackPositionTicks":120000000,"LastPlayedDate":"2100-01-01T00:00:00.0000000Z"}"""
        assertEquals(1, manager.syncWatchState())
        assertTrue(posted.isEmpty())
        assertFalse(record().item.user.played)
        assertEquals(12_000, record().item.user.positionMs)
    }

    @Test
    fun `short watches don't count and the end marks watched`() = runBlocking {
        signIn()
        manager.enqueue(listOf(movie), DownloadQuality.ORIGINAL, hevc = false)
        manager.recordPlayback(ITEM_ID, positionMs = 2_000, durationMs = 60_000, ended = false)
        assertEquals(0, record().item.user.positionMs)
        assertFalse(record().item.user.played)
        manager.recordPlayback(ITEM_ID, positionMs = 10_000, durationMs = 60_000, ended = true)
        assertTrue(record().item.user.played)
    }

    @Test
    fun `sizes and which presets are worth offering`() {
        val source = MediaSource("s", "mkv", 10_000_000, listOf(MediaStream(0, StreamKind.VIDEO, width = 1280, height = 720)), size = 4_500_000_000)
        val options = DownloadSizing.options(source, runtimeMs = 3_600_000, hevc = false).associateBy { it.quality }
        assertEquals(4_500_000_000, options[DownloadQuality.ORIGINAL]!!.estimatedBytes)
        // 1080p would upscale a 720p file.
        assertFalse(options[DownloadQuality.HIGH]!!.worthwhile)
        assertTrue(options[DownloadQuality.MEDIUM]!!.worthwhile)
        assertEquals((4_000_000L + 160_000) * 3_600 / 8, options[DownloadQuality.MEDIUM]!!.estimatedBytes)
        // A low-bitrate source gains nothing from a 720p transcode.
        val small = DownloadSizing.options(source.copy(bitrate = 3_000_000), 3_600_000, hevc = false).associateBy { it.quality }
        assertFalse(small[DownloadQuality.MEDIUM]!!.worthwhile)
        assertTrue(small[DownloadQuality.LOW]!!.worthwhile)
        // Scope films count as 1080p by width.
        assertFalse(DownloadQuality.HIGH.largerThan(1920, 800))
        assertEquals("4.5 GB", DownloadSizing.formatBytes(4_500_000_000))
        assertEquals("820 MB", DownloadSizing.formatBytes(820_000_000))
        assertNull(DownloadSizing.estimate(null, 1))
    }

    private companion object {
        const val USER = "0123456789abcdef0123456789abcdef"
        const val USER_DASHED = "01234567-89ab-cdef-0123-456789abcdef"
        const val ITEM_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        val ITEM = """{"Id":"aaaaaaaabbbbccccddddeeeeeeeeeeee","Name":"Movie","Type":"Movie","RunTimeTicks":600000000,
            |"ImageTags":{"Primary":"p"},"BackdropImageTags":["b"],
            |"MediaSources":[{"Id":"src","Container":"mkv","Size":10000,"Bitrate":8000000,"MediaStreams":[
            |{"Index":0,"Type":"Video","Codec":"h264","Width":1920,"Height":1080},
            |{"Index":1,"Type":"Audio","Codec":"eac3","Channels":6,"IsDefault":true,"Language":"eng"},
            |{"Index":2,"Type":"Subtitle","Codec":"srt","IsExternal":true,"DeliveryMethod":"External","DeliveryUrl":"/x"},
            |{"Index":3,"Type":"Subtitle","Codec":"subrip","Language":"spa"},
            |{"Index":4,"Type":"Subtitle","Codec":"PGSSUB"}]}]}""".trimMargin()
    }
}
