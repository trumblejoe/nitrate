package dev.nitrate.core.jellyfin

import dev.nitrate.core.model.CollectionKind
import dev.nitrate.core.pages.BrowseOptions
import dev.nitrate.core.pages.Codecs
import dev.nitrate.core.playback.PlaybackSettings
import dev.nitrate.core.playback.PlaybackSettingsRepository
import dev.nitrate.core.playback.caps
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Library filters by codec, which Nitrate applies itself (Jellyfin can't). */
class CodecFilterTest {
    private fun id(n: Int) = "0000000000000000000000000000000$n"
    private fun streams(video: String, audio: String, depth: Int = 8, range: String = "SDR") =
        """[{"Type":"Video","Codec":"$video","BitDepth":$depth,"VideoRangeType":"$range"},{"Type":"Audio","Codec":"$audio"},{"Type":"Subtitle","Codec":"subrip"}]"""

    /** Films 1–4 and episodes of shows 5 and 6, as the server would list them. */
    private val films = listOf(
        // HEVC 10-bit SDR (like the Fire Stick file), H.264 8-bit, HEVC Dolby Vision over HDR10, AV1 8-bit.
        Triple(id(1), "Alpha", streams("hevc", "dts", depth = 10)),
        Triple(id(2), "Bravo", streams("h264", "aac")),
        Triple(id(3), "Charlie", streams("hevc", "truehd", depth = 10, range = "DOVIWithHDR10")),
        Triple(id(4), "Delta", streams("av1", "opus")),
    )
    private val episodes = listOf(id(5) to streams("h264", "ac3"), id(5) to streams("hevc", "eac3"), id(6) to streams("mpeg2video", "pcm_s16le"))
    private val requests = mutableListOf<io.ktor.http.Url>()

    private val engine = MockEngine { request ->
        requests += request.url
        val q = request.url.parameters
        val body = when {
            q["fields"] == "MediaStreams" && q["includeItemTypes"] == "Episode" ->
                episodes.joinToString(",", "[", "]") { (series, s) -> """{"Id":"e${series}","Name":"ep","Type":"Episode","SeriesId":"$series","MediaStreams":$s}""" }
            q["fields"] == "MediaStreams" -> films.joinToString(",", "[", "]") { (i, n, s) -> """{"Id":"$i","Name":"$n","Type":"Movie","MediaStreams":$s}""" }
            q["ids"] != null -> q["ids"]!!.split(",").joinToString(",", "[", "]") { i -> """{"Id":"${i.replace("-", "")}","Name":"n$i","Type":"Movie"}""" }
            q["includeItemTypes"] == "Series" -> """[{"Id":"${id(5)}","Name":"Echo","Type":"Series"},{"Id":"${id(6)}","Name":"Foxtrot","Type":"Series"}]"""
            else -> films.joinToString(",", "[", "]") { (i, n, _) -> """{"Id":"$i","Name":"$n","Type":"Movie"}""" }
        }
        respond("""{"Items":$body,"TotalRecordCount":0}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private val settings = object : PlaybackSettingsRepository {
        override val settings = MutableStateFlow(PlaybackSettings())
        override suspend fun current() = settings.value
        override suspend fun update(transform: (PlaybackSettings) -> PlaybackSettings) { settings.value = transform(settings.value) }
    }
    private val client = JellyfinClient(
        engine, ClientIdentity("Nitrate", "1", "dev", "Test"),
        MemorySessionStorage(listOf(StoredSession("http://jf", "t", "user-1", "joe"))), { caps() }, settings,
    )

    private fun names(page: Pair<List<dev.nitrate.core.model.MediaItem>, Int>) = page.first.map { it.id.replace("-", "") }

    @Test fun moviesByVideoAndAudio() = runBlocking {
        client.restoreSession()
        val hevc = client.queryPage(BrowseOptions(videoCodecs = listOf("hevc")).query("lib", CollectionKind.MOVIES), 0, 10)
        assertEquals(listOf(id(1), id(3)), names(hevc))
        assertEquals(2, hevc.second)
        // Both kinds chosen: HEVC and TrueHD.
        val both = client.queryPage(BrowseOptions(videoCodecs = listOf("hevc"), audioCodecs = listOf("truehd")).query("lib", CollectionKind.MOVIES), 0, 10)
        assertEquals(listOf(id(3)), names(both))
        // Paging over the filtered list.
        val second = client.queryPage(BrowseOptions(videoCodecs = listOf("hevc", "h264", "av1")).query("lib", CollectionKind.MOVIES), 2, 2)
        assertEquals(listOf(id(3), id(4)), names(second))
        assertEquals(4, second.second)
    }

    @Test fun showsByTheirEpisodes() = runBlocking {
        client.restoreSession()
        // Show 5 has an HEVC episode; show 6 is MPEG-2 with PCM.
        assertEquals(listOf(id(5)), names(client.queryPage(BrowseOptions(videoCodecs = listOf("hevc")).query("tv", CollectionKind.TV_SHOWS), 0, 10)))
        assertEquals(listOf(id(6)), names(client.queryPage(BrowseOptions(audioCodecs = listOf("pcm")).query("tv", CollectionKind.TV_SHOWS), 0, 10)))
    }

    @Test fun indexIsReused() = runBlocking {
        client.restoreSession()
        val q = BrowseOptions(videoCodecs = listOf("hevc")).query("lib", CollectionKind.MOVIES)
        client.queryPage(q, 0, 10)
        client.queryPage(q.copy(audioCodecs = listOf("dts")), 0, 10)
        assertEquals(1, requests.count { it.parameters["fields"] == "MediaStreams" })
    }

    @Test fun bitDepthAndRange() = runBlocking {
        client.restoreSession()
        fun q(o: BrowseOptions) = o.query("lib", CollectionKind.MOVIES)
        // HEVC, 10-bit, SDR: only Alpha (Charlie is HEVC 10-bit but Dolby Vision over HDR10).
        assertEquals(listOf(id(1)), names(client.queryPage(q(BrowseOptions(videoCodecs = listOf("hevc"), bitDepths = listOf(10), videoRanges = listOf("sdr"))), 0, 10)))
        // Dolby Vision files count as their base layer too.
        assertEquals(listOf(id(3)), names(client.queryPage(q(BrowseOptions(videoRanges = listOf("hdr10"))), 0, 10)))
        assertEquals(listOf(id(3)), names(client.queryPage(q(BrowseOptions(videoRanges = listOf("dovi"))), 0, 10)))
        // 8-bit anything.
        assertEquals(listOf(id(2), id(4)), names(client.queryPage(q(BrowseOptions(bitDepths = listOf(8))), 0, 10)))
    }

    @Test fun codecNames() {
        assertEquals("pcm", Codecs.normalize("pcm_s24le"))
        assertEquals("dts", Codecs.normalize("DCA"))
        assertEquals("hevc", Codecs.normalize("h265"))
        assertEquals(setOf("dovi", "hdr10plus", "hdr10"), Codecs.rangeTags("DOVIWithELHDR10Plus"))
        // One stream must meet every video choice: a 10-bit H.264 and an 8-bit HEVC don't make "10-bit HEVC".
        val mixed = dev.nitrate.core.pages.StreamInfo(
            setOf(dev.nitrate.core.pages.VideoStreamInfo("h264", 10, setOf("sdr")), dev.nitrate.core.pages.VideoStreamInfo("hevc", 8, setOf("sdr"))),
            setOf("aac"),
        )
        assertTrue(!Codecs.matches(mixed, dev.nitrate.core.pages.ItemQuery(videoCodecs = listOf("hevc"), bitDepths = listOf(10))))
        assertTrue(Codecs.matches(mixed, dev.nitrate.core.pages.ItemQuery(videoCodecs = listOf("hevc"), bitDepths = listOf(8))))
    }
}
