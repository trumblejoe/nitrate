package dev.nitrate.core.jellyfin

import dev.nitrate.core.admin.ImageFacts
import dev.nitrate.core.admin.ImageType
import dev.nitrate.core.admin.RemoteImage
import dev.nitrate.core.admin.RemoteImages
import dev.nitrate.core.comics.ImageSize
import dev.nitrate.core.playback.PlaybackSettings
import dev.nitrate.core.playback.PlaybackSettingsRepository
import dev.nitrate.core.playback.caps
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** A found image's size and transparency read from the start of its file, asked of the provider with nothing of the user's sent. */
class RemoteImageSizeTest {
    private val seen = mutableListOf<Headers>()
    private val asked = mutableListOf<String>()

    private val engine = MockEngine { request ->
        asked += request.url.toString()
        seen += request.headers
        when (request.url.host) {
            "assets.fanart.tv" -> respond(png(6, listOf("IDAT")), HttpStatusCode.PartialContent)
            else -> respond("nope", HttpStatusCode.NotFound)
        }
    }

    private val settings = object : PlaybackSettingsRepository {
        override val settings = MutableStateFlow(PlaybackSettings())
        override suspend fun current() = settings.value
        override suspend fun update(transform: (PlaybackSettings) -> PlaybackSettings) { settings.value = transform(settings.value) }
    }

    private fun client() = JellyfinClient(
        engine, ClientIdentity("Nitrate", "1.0", "dev1", "Test"),
        MemorySessionStorage(listOf(StoredSession("http://jf.example:8096", "token", "user1", "sam"))), { caps() }, settings,
    ).also { runBlocking { it.restoreSession() } }

    /** A PNG 800×310 of colour type [colour] with [chunks] after its header. */
    private fun png(colour: Int, chunks: List<String>): ByteArray {
        val out = ArrayList<Byte>()
        out += listOf(0x89, 'P'.code, 'N'.code, 'G'.code, 13, 10, 26, 10).map { it.toByte() }
        out += listOf(0, 0, 0, 13).map { it.toByte() }
        out += "IHDR".map { it.code.toByte() }
        out += listOf(0, 0, 3, 32, 0, 0, 1, 54, 8, colour, 0, 0, 0).map { it.toByte() }
        repeat(4) { out += 0 }
        chunks.forEach { type ->
            out += listOf(0, 0, 0, 6).map { it.toByte() }
            out += type.map { it.code.toByte() }
            repeat(10) { out += 0 }
        }
        return out.toByteArray()
    }

    @Test fun theFactsAreReadFromTheFileAndNothingOfOursIsSent() = runBlocking {
        val c = client()
        assertEquals(ImageFacts(800, 310, true), c.remoteImageFacts("https://assets.fanart.tv/fanart/movies/1/hdmovielogo/logo.png"))
        val h = seen.last()
        assertEquals("bytes=0-262143", h["Range"])
        assertNull(h["Authorization"])
        assertNull(h["X-Emby-Authorization"])
        assertFalse(h.names().any { it.contains("Nitrate", ignoreCase = true) || it.contains("Emby", ignoreCase = true) })
        // Read once.
        c.remoteImageFacts("https://assets.fanart.tv/fanart/movies/1/hdmovielogo/logo.png")
        assertEquals(1, asked.count { it.contains("fanart") })
        // Unreadable or not a web address: none.
        assertNull(c.remoteImageFacts("https://image.example.org/missing.png"))
        assertNull(c.remoteImageFacts("file:///etc/passwd"))
    }

    @Test fun seeThroughIsReadFromTheHeader() {
        assertEquals(true, ImageSize.transparent(png(6, listOf("IDAT"))))
        assertEquals(true, ImageSize.transparent(png(4, listOf("IDAT"))))
        assertEquals(true, ImageSize.transparent(png(3, listOf("PLTE", "tRNS", "IDAT"))))
        assertEquals(false, ImageSize.transparent(png(2, listOf("pHYs", "IDAT"))))
        assertNull(ImageSize.transparent(png(2, listOf("pHYs"))))
        assertEquals(false, ImageSize.transparent(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals(true, ImageSize.transparent("<?xml version=\"1.0\"?><svg width=\"10\"/>".encodeToByteArray()))
    }

    @Test fun readSizesCountInTheOrderAndOpaqueLogosAreFlagged() = runBlocking {
        val type = ImageType.LOGO
        val a = RemoteImage("a", null, "Fanart", null, null, null, null)
        val b = RemoteImage("b", null, "TheMovieDb", 600, 232, "en", null)
        val facts = mapOf("a" to ImageFacts(1600, 620, false), "b" to ImageFacts(600, 232, true))
        val out = RemoteImages.measured(type, listOf(b, a), { facts[it] })
        assertEquals(listOf("a", "b"), out.map { it.url })
        assertEquals(1600, out[0].width)
        assertEquals(true, RemoteImages.isOpaque(type, out[0]))
        assertEquals("Fanart · Textless · 1600×620 · no transparency", RemoteImages.band(type, out[0]))
        assertEquals(false, RemoteImages.isOpaque(type, out[1]))
        // Posters needn't be see-through: a sized one isn't read.
        assertFalse(RemoteImages.needsReading(ImageType.PRIMARY, b))
    }

    @Test fun linksMustBeWebAddressesOnTheInternet() {
        assertNull(RemoteImages.linkProblem("https://mediux.pro/assets/abc.jpg"))
        assertNull(RemoteImages.linkProblem("  http://theposterdb.com/api/assets/1  "))
        assertEquals("Paste an image's address", RemoteImages.linkProblem(""))
        assertEquals("It needs to start with https://", RemoteImages.linkProblem("ftp://x.org/a.png"))
        assertEquals("Only addresses on the internet", RemoteImages.linkProblem("http://192.168.1.5/a.png"))
        assertEquals("Only addresses on the internet", RemoteImages.linkProblem("https://localhost/a.png"))
        assertEquals("That isn't a web address", RemoteImages.linkProblem("https://nohost/a.png"))
    }
}
